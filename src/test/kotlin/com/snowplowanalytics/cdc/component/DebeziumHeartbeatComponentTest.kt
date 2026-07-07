/*
 * Copyright 2026 Snowplow Analytics Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowplowanalytics.cdc.component

import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.PostgresReplicationFixture
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.writeText

// Regression test for the heartbeat-handling bug: with Debezium heartbeats enabled, the
// connector periodically emits a control record on the `__debezium-heartbeat.*` topic. Those
// records carry a ts_ms-only schema (no `after` field), so before the fix they reached
// SourceSchemaFingerprinter, threw "envelope missing schema.fields[after]", were swallowed by
// handleRecord's catch-all, logged as a (false) ERROR, and counted as `dropped` — every
// interval. TracerBulletTest never caught this because it sets heartbeat_interval_ms: 0.
//
// This test enables a short heartbeat interval and asserts heartbeats are treated as control
// messages: not counted as received, not counted as dropped — while a real INSERT still emits.
@ExtendWith(RequiresDocker::class)
@Testcontainers
class DebeziumHeartbeatComponentTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    @TempDir
    lateinit var tmp: Path

    private val fixture = PostgresReplicationFixture(postgres)
    private lateinit var runner: EngineRunner
    private lateinit var emitter: RecordingEmitter
    private lateinit var counters: Counters

    @BeforeEach
    fun setUp() {
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()

        emitter = RecordingEmitter()
        counters = Counters(bufferCapacity = 1000)
        val offsetFile = Files.createTempFile("cdc-offsets", ".dat").toFile()
        offsetFile.delete()

        val configPath = tmp.resolve("config.yaml")
        configPath.writeText(
            """
            service:
              app_id: orders-cdc
            source:
              connector: postgres
              hostname: ${postgres.host}
              port: ${postgres.firstMappedPort}
              database: orders_db
              username: cdc
              password: cdc
              slot_name: snowplow_cdc
              publication_name: snowplow_cdc_pub
            debezium:
              snapshot_mode: never
              offset_store:
                type: file
                file_path: ${offsetFile.absolutePath}
              heartbeat_interval_ms: 500
              publication_autocreate_mode: filtered
              provide_transaction_metadata: false
            snowplow:
              collector_url: http://unused
              cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
            tables:
              - name: orders
                schema: public
                iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
                primary_key: [id]
                columns:
                  - id
                  - customer_id
                  - status
                  - total
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)
        runner = EngineRunner(config, emitter, counters, ReadinessProbe())
        runner.start()
        fixture.awaitReplicationSlotPresent()
    }

    @AfterEach
    fun tearDown() {
        runner.stop()
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()
    }

    @Test
    fun `heartbeat records are ignored, not counted as received or dropped`() {
        // A single real change so we can prove the engine is live and streaming.
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (1, 1, 'pending', 99.00)"
                )
            }
        }
        awaitEmittedAtLeast(1)

        // Now sit idle for several heartbeat intervals (500ms each). The connector emits a
        // heartbeat control record on each interval; none of them must touch the counters.
        // (Calibrated against the pre-fix RED run, which observed 6 dropped heartbeats in this
        // exact 3s window — so the window reliably produces heartbeats and this is not vacuous.
        // The discriminator logic itself is pinned deterministically by
        // EngineRunnerInstrumentationTest; this test proves the prefix matches what real Debezium
        // emits and that heartbeats don't disrupt streaming end-to-end.)
        Thread.sleep(3_000)

        // A second change after the idle window proves the engine streamed correctly *through*
        // the heartbeats — they neither stalled it nor corrupted its state.
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (2, 2, 'shipped', 1.00)"
                )
            }
        }
        awaitEmittedAtLeast(2)

        // Exactly the two INSERTs were received and emitted; the heartbeats in between are control
        // messages — invisible to every counter (not received, not dropped).
        counters.emitted.get() shouldBe 2L
        counters.dropped.get() shouldBe 0L
        counters.received.get() shouldBe 2L
    }

    private fun awaitEmittedAtLeast(expected: Int, timeoutMs: Long = PostgresReplicationFixture.AWAIT_DEFAULT_TIMEOUT_MS) {
        fixture.awaitWith(timeoutMs, "RecordingEmitter to receive $expected event(s)") {
            emitter.events.size >= expected
        }
    }
}
