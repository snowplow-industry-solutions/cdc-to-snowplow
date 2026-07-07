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
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.PostgresReplicationFixture
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import com.snowplowanalytics.cdc.testutil.withCapturedLogs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import net.logstash.logback.encoder.LogstashEncoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.writeText

/**
 * End-to-end Testcontainers exercise of the live DDL detection path. Boots Postgres, runs
 * EngineRunner against `public.orders`, inserts a row, ALTERs the table to add a new column,
 * inserts a second row, and asserts that exactly one structured `source_schema_change` WARN
 * line was emitted between the two inserts. The JSON shape is verified by re-encoding the
 * captured logging event through a programmatic LogstashEncoder so the test asserts on the
 * same wire format an operator would see.
 */
@ExtendWith(RequiresDocker::class)
@Testcontainers
class DdlChangeDetectionComponentTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    @TempDir
    lateinit var tmp: Path

    private val mapper = ObjectMapper()
    private val fixture = PostgresReplicationFixture(postgres)
    private lateinit var runner: EngineRunner
    private lateinit var emitter: RecordingEmitter

    @BeforeEach
    fun setUp() {
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()

        emitter = RecordingEmitter()
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
                columns: [id, customer_id, status, total]
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)
        runner = EngineRunner(config, emitter, Counters(bufferCapacity = 1_000), ReadinessProbe())
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
    fun `ALTER TABLE between INSERTs produces exactly one source_schema_change WARN line`() {
        // Capture root-logger events for the duration of the body; withCapturedLogs detaches the
        // appender even if an assertion throws, so a failure here can't leak it onto later tests.
        // The source_schema_change WARN is emitted on the post-ALTER event, all within this block.
        withCapturedLogs { appender ->
            // 1. First INSERT — establishes the baseline fingerprint in the detector.
            DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "INSERT INTO public.orders (id, customer_id, status, total) " +
                            "VALUES (1, 1, 'pending', 10.00)"
                    )
                }
            }
            // Make the ordering deterministic: the first event must be observed before the ALTER.
            awaitEventCount(1)

            // 2. ALTER TABLE adds a column not present in the YAML whitelist. Debezium re-reads the
            // catalog on subsequent events so the next change carries the new schema in its
            // value envelope — the fingerprint should change even though the YAML column list does not.
            DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("ALTER TABLE public.orders ADD COLUMN country TEXT")
                }
            }

            // 3. Second INSERT — populates `country`, but the column is whitelisted-out so it does not
            // appear in the emitted payload. The fingerprint over the source schema still flips.
            DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "INSERT INTO public.orders (id, customer_id, status, total, country) " +
                            "VALUES (2, 1, 'pending', 20.00, 'GB')"
                    )
                }
            }
            awaitEventCount(2)

            // 4. Exactly one source_schema_change WARN line — across all log events, regardless of source.
            val schemaChangeEvents = appender.list.filter {
                it.level == Level.WARN && it.formattedMessage == "source_schema_change"
            }
            schemaChangeEvents shouldHaveSize 1

            // 5. Render that event's structured arguments through a real LogstashEncoder and parse
            // the resulting JSON line. This asserts on the wire format operators actually see.
            val encoder = LogstashEncoder().apply {
                context = LoggerFactory.getILoggerFactory() as LoggerContext
                start()
            }
            val json = try {
                mapper.readTree(encoder.encode(schemaChangeEvents.single()))
            } finally {
                encoder.stop()
            }

            json.get("event").asText() shouldBe "source_schema_change"
            json.get("table").asText() shouldBe "public.orders"

            val added = json.get("added_columns").map { it.asText() }
            added shouldBe listOf("country")
            json.get("removed_columns").size() shouldBe 0

            val previous = json.get("previous_fingerprint").asText()
            val current = json.get("current_fingerprint").asText()
            previous shouldMatch Regex("^[0-9a-f]{64}$")
            current shouldMatch Regex("^[0-9a-f]{64}$")
            previous shouldNotBe current

            // 6. Cross-check: both endpoints of the fingerprint transition must match the
            // cdc_source.column_fingerprint that the assembler put on the wire — the pre-DDL
            // event carries `previous_fingerprint`, the post-DDL event carries `current_fingerprint`.
            val preDdlEvent = emitter.events[0]
            val preCdcSource = preDdlEvent.context.single()
            @Suppress("UNCHECKED_CAST")
            val preOuter = preCdcSource.map as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val data0 = preOuter["data"] as Map<String, Any?>
            data0["column_fingerprint"] shouldBe previous

            val postDdlEvent = emitter.events[1]
            val cdcSource = postDdlEvent.context.single()
            @Suppress("UNCHECKED_CAST")
            val outer = cdcSource.map as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val data = outer["data"] as Map<String, Any?>
            data["column_fingerprint"] shouldBe current
        }
    }

    private fun awaitEventCount(expected: Int, timeoutMs: Long = PostgresReplicationFixture.AWAIT_DEFAULT_TIMEOUT_MS) {
        fixture.awaitWith(timeoutMs, "RecordingEmitter to receive $expected event(s)") {
            emitter.events.size >= expected
        }
    }
}
