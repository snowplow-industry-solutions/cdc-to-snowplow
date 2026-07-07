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

import ch.qos.logback.classic.Level
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.HeartbeatScheduler
import com.snowplowanalytics.cdc.observability.ObservabilityServer
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.PostgresReplicationFixture
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import com.snowplowanalytics.cdc.testutil.withCapturedLogs
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import net.logstash.logback.argument.StructuredArgument
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

// Exercises the HeartbeatScheduler in a full-wiring component test:
// verifies cadence (≥3 ticks in ~1 second at 200ms interval) and counter reflection
// (received ≥ 1, per_table["public.orders"]["c"] ≥ 1) after a real Postgres INSERT.
@ExtendWith(RequiresDocker::class)
@Testcontainers
class HeartbeatCadenceTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    @TempDir
    lateinit var tmp: Path

    private val fixture = PostgresReplicationFixture(postgres)
    private lateinit var runner: EngineRunner
    private lateinit var probe: ReadinessProbe
    private lateinit var counters: Counters
    private lateinit var emitter: RecordingEmitter
    private lateinit var heartbeat: HeartbeatScheduler
    private lateinit var httpServer: ObservabilityServer
    private var httpPort: Int = 0

    @BeforeEach
    fun setUp() {
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()

        probe = ReadinessProbe()
        emitter = RecordingEmitter()
        counters = Counters(bufferCapacity = 1000)

        val offsetFile = Files.createTempFile("cdc-offsets", ".dat").toFile()
        offsetFile.delete()

        val configPath = tmp.resolve("config.yaml")
        configPath.writeText(
            """
            service:
              app_id: heartbeat-cadence-test
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
              heartbeat_interval_ms: 0
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
            observability:
              heartbeat:
                interval_ms: 200
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)

        runner = EngineRunner(config, emitter, counters, probe)
        httpServer = ObservabilityServer(port = 0, readinessProbe = probe)
        httpPort = httpServer.start()

        heartbeat = HeartbeatScheduler(
            intervalMs = 200L,
            counters = counters,
            probe = probe,
            emitter = emitter,
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { heartbeat.close() }
        runCatching { runner.stop() }
        runCatching { httpServer.close() }
        runCatching { fixture.dropReplicationSlotIfPresent() }
        runCatching { fixture.dropPublicationIfPresent() }
    }

    @Test
    fun `heartbeat fires at cadence and reflects INSERT counters`() {
        // Capture the "Heartbeat" logger at INFO for the duration of the body. withCapturedLogs
        // attaches the appender before the block runs (so no ticks are missed once the scheduler
        // starts) and detaches it even if an assertion throws.
        withCapturedLogs(loggerName = "Heartbeat", level = Level.INFO) { appender ->
            // Start heartbeat before engine so ticks begin immediately.
            heartbeat.start()
            probe.markEmitterReady()
            runner.start()

            // --- Phase 1: cadence check ---
            // Wait ~1 second (5× the 200ms interval) and assert ≥3 heartbeat log events were captured.
            Thread.sleep(1_000)

            val capturedAfterWait = appender.list.toList()
            capturedAfterWait shouldHaveAtLeastSize 3

            // Every captured event must carry event=heartbeat as a StructuredArgument.
            capturedAfterWait.forEach { event ->
                val eventArg = event.argumentArray
                    ?.filterIsInstance<StructuredArgument>()
                    ?.firstOrNull { it.toString().startsWith("event=") }
                eventArg?.toString() shouldBe "event=heartbeat"
            }

            // --- Wait for engine to be ready before inserting ---
            // Poll until the replication slot exists, meaning Debezium has connected and is streaming.
            fixture.awaitReplicationSlotPresent()

            // --- Phase 2: counter reflection ---
            // Insert one row into public.orders, then wait for Debezium to pick it up.
            DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "INSERT INTO public.orders (id, customer_id, status, total) " +
                            "VALUES (1001, 5, 'pending', 49.99)"
                    )
                }
            }

            // Poll until counters.received ≥ 1 (Debezium has delivered the row to the assembler).
            // Timeout of 15s is generous enough for slow CI.
            fixture.awaitWith(timeoutMs = 15_000, description = "counters.received >= 1") {
                counters.received.get() >= 1L
            }

            // Wait an additional heartbeat cycle so the scheduler fires at least once more after
            // the counter has been incremented.
            Thread.sleep(500)

            // Grab the latest heartbeat event; its snapshot must reflect the INSERT.
            val latestEvents = appender.list.toList()
            val lastEvent = latestEvents.last()

            // Locate the StructuredArgument for "received" — its toString() is "received=N".
            val receivedArg = lastEvent.argumentArray
                ?.filterIsInstance<StructuredArgument>()
                ?.firstOrNull { it.toString().startsWith("received=") }
            val receivedValue = receivedArg?.toString()
                ?.removePrefix("received=")
                ?.toLongOrNull() ?: 0L
            receivedValue shouldBeGreaterThanOrEqualTo 1L

            // Locate the StructuredArgument for "per_table".
            // HeartbeatFormatter renders it as kv("per_table", map), producing an
            // ObjectAppendingMarker.  getFieldValue() on SingleFieldAppendingMarker is protected,
            // so we access it via reflection to recover the raw Map without string parsing.
            val perTableArg = lastEvent.argumentArray
                ?.filterIsInstance<net.logstash.logback.marker.SingleFieldAppendingMarker>()
                ?.firstOrNull { it.fieldName == "per_table" }

            val perTableValue: Any? = perTableArg?.let { marker ->
                val method = net.logstash.logback.marker.SingleFieldAppendingMarker::class.java
                    .getDeclaredMethod("getFieldValue")
                method.isAccessible = true
                method.invoke(marker)
            }

            @Suppress("UNCHECKED_CAST")
            val perTable = perTableValue as? Map<String, Map<String, Long>>
            val ordersOps = perTable?.get("public.orders")
            val insertCount = ordersOps?.get("c") ?: 0L
            insertCount shouldBeGreaterThanOrEqualTo 1L
        }
    }
}
