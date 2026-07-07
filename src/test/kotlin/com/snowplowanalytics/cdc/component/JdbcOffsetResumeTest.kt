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

import com.fasterxml.jackson.databind.ObjectMapper
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.PostgresReplicationFixture
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import com.snowplowanalytics.snowplow.tracker.events.Event
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import kotlin.io.path.writeText

@ExtendWith(RequiresDocker::class)
@Testcontainers
class JdbcOffsetResumeTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()

        private const val OFFSET_TABLE = "cdc_offset_storage"
    }

    @TempDir
    lateinit var tmp: Path

    private val mapper = ObjectMapper()
    private val fixture = PostgresReplicationFixture(postgres)

    @BeforeEach
    fun setUp() {
        // Clean slate: no slot, no publication, no offset table carried over from a prior test.
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()
        exec("DROP TABLE IF EXISTS $OFFSET_TABLE")
    }

    @AfterEach
    fun tearDown() {
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()
    }

    @Test
    fun `jdbc offset store persists position and a fresh engine resumes from it`() {
        // --- Run 1: stream one change, prove the offset is persisted to the DB, then stop. ---
        val emitter1 = RecordingEmitter()
        val runner1 = newRunner(emitter1)
        runner1.start()
        // Graceful stop flushes the latest offsets for delivered records before the engine closes.
        // Debezium's embedded (async) engine persists offsets to the backing store either when a
        // *non-empty* poll batch finishes and the commit policy fires, or unconditionally when the
        // task is stopped (AsyncEmbeddedEngine.stopSourceTasks -> commitOffsets). For an otherwise
        // idle stream — one change, then no further WAL activity — no subsequent batch arrives to
        // trigger a periodic flush, so the durable write happens at stop(). Asserting a row mid-run
        // therefore races a flush that may never occur; we assert persistence *after* stop instead.
        try {
            fixture.awaitReplicationSlotPresent()
            insertOrder(1)
            awaitEventCount(emitter1, 1)
        } finally {
            runner1.stop()
        }
        // JDBC-specific proof: the offset table now holds a row (file mode never writes this table).
        offsetRowCount() shouldBeGreaterThanOrEqual 1

        // --- A change occurs while the service is down; the retained slot keeps its WAL. ---
        insertOrder(2)

        // --- Run 2: a brand-new engine + emitter against the SAME offset table. ---
        val emitter2 = RecordingEmitter()
        val runner2 = newRunner(emitter2)
        runner2.start()
        try {
            awaitEventCount(emitter2, 1)
            // Resumed from the persisted offset: only id=2 is delivered; id=1 is not re-read.
            emitter2.events shouldHaveSize 1
            orderIdOf(emitter2.events.single()) shouldBe 2
        } finally {
            runner2.stop()
        }
    }

    private fun newRunner(emitter: RecordingEmitter): EngineRunner {
        val configPath = tmp.resolve("config-${System.nanoTime()}.yaml")
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
                type: jdbc
                jdbc:
                  username: cdc
                  password: cdc
                  table_name: $OFFSET_TABLE
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
            """.trimIndent(),
        )
        val config = ConfigLoader.load(configPath)
        return EngineRunner(config, emitter, Counters(bufferCapacity = 1000), ReadinessProbe())
    }

    private fun insertOrder(id: Int) =
        exec("INSERT INTO public.orders (id, customer_id, status, total) VALUES ($id, 1, 'pending', 9.99)")

    private fun exec(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    private fun offsetRowCount(): Int =
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $OFFSET_TABLE").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }

    private fun awaitEventCount(emitter: RecordingEmitter, expected: Int) {
        fixture.awaitWith(description = "RecordingEmitter to receive $expected event(s)") {
            emitter.events.size >= expected
        }
    }

    // Decode ue_px → outer SDE → inner table payload → after.id
    @Suppress("UNCHECKED_CAST")
    private fun orderIdOf(event: Event): Int {
        val ueB64 = event.payload.map["ue_px"] as? String
            ?: error("ue_px not found in event payload — was base64 encoding disabled?")
        val outer = mapper.readValue(String(Base64.getDecoder().decode(ueB64)), Map::class.java)
            as Map<String, Any?>
        val sdj = outer["data"] as Map<String, Any?>
        val tablePayload = sdj["data"] as Map<String, Any?>
        val after = tablePayload["after"] as Map<String, Any?>
        return (after["id"] as Number).toInt()
    }
}
