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
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
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

// @Testcontainers activates the Testcontainers JUnit 5 extension, which manages the lifecycle
// of @Container fields — starting them before the test class and stopping them after.
// This test uses a real PostgreSQL container rather than mocks so that Debezium's actual
// replication protocol is exercised end-to-end.
@ExtendWith(RequiresDocker::class)
@Testcontainers
class TracerBulletTest {

    // companion object is Kotlin's static namespace. @JvmStatic is required here because
    // Testcontainers needs the container field to be a true Java static — without @JvmStatic
    // it's an instance field on the companion object and the Testcontainers extension
    // cannot find it via reflection. The container is shared across all tests in the class
    // (started once, stopped once) to avoid the overhead of starting a new PG per test.
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    // @TempDir gives each test its own temp directory for config file and offset file.
    @TempDir
    lateinit var tmp: Path

    private val mapper = ObjectMapper()
    // Shared replication-slot / publication lifecycle helpers (issue #24).
    private val fixture = PostgresReplicationFixture(postgres)
    // `lateinit var` signals that these fields will be set in @BeforeEach. Kotlin requires
    // a non-null type here; accessing them before initialization throws UninitializedPropertyAccessException.
    private lateinit var runner: EngineRunner
    private lateinit var emitter: RecordingEmitter

    @BeforeEach
    fun setUp() {
        // The Postgres container is shared across the class, so the snowplow_cdc replication slot
        // and publication can survive from a prior test (even if @AfterEach ran cleanly, a panic
        // mid-test would skip it). Drop them up-front so each test starts from an empty WAL
        // position — otherwise Debezium replays the prior test's commits into RecordingEmitter.
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()

        emitter = RecordingEmitter()
        val offsetFile = Files.createTempFile("cdc-offsets", ".dat").toFile()
        offsetFile.delete()  // Debezium creates the file; it must not already exist

        // Write the YAML config to a temp file and load it through the production ConfigLoader
        // code path — no test-only constructors or back doors.
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
                columns:
                  - id
                  - customer_id
                  - status
                  - total
              - name: customers
                schema: public
                iglu_schema: iglu:com.example/customers_change/jsonschema/1-0-0
                primary_key: [id]
                columns:
                  - id
                  - email:
                      rename: emailAddress
                      transforms: [trim, lowercase]
                  - full_name
                  - password_hash: { exclude: true }
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)
        runner = EngineRunner(config, emitter, Counters(bufferCapacity = 1000), ReadinessProbe())
        runner.start()
        // Wait until Debezium has created the replication slot before the test inserts rows —
        // rows inserted before the slot is ready would be missed entirely.
        fixture.awaitReplicationSlotPresent()
    }

    @AfterEach
    fun tearDown() {
        runner.stop()
        // Drop after stop so the next test's @BeforeEach starts cleanly even if no other test
        // runs between (the @BeforeEach drop is defensive; this is the normal-path cleanup).
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()
    }

    @Test
    fun `INSERTs into both captured tables emit one Snowplow event per table`() {
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            // `.use { }` is Kotlin's try-with-resources — closes the Connection after the block
            // even if an exception is thrown. Same pattern for the inner Statement.
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (42, 1, 'pending', 99.00)"
                )
                st.execute(
                    "INSERT INTO public.customers (id, email, full_name, password_hash) " +
                        "VALUES (1, 'a@example.com', 'Ada Lovelace', 'pbkdf2:abc')"
                )
            }
        }

        awaitEventCount(2)

        emitter.events shouldHaveSize 2
        val schemas = emitter.events.map { schemaUriOf(it) }.toSet()
        schemas shouldBe setOf(
            "iglu:com.example/orders_change/jsonschema/1-0-0",
            "iglu:com.example/customers_change/jsonschema/1-0-0",
        )

        emitter.events.forEach { it.trueTimestamp shouldNotBe null }

        // Every event carries exactly one cdc_source context entity, with the contract-shape fields populated.
        emitter.events.forEach { event ->
            val contexts = event.context
            contexts shouldHaveSize 1

            val cdcSource = contexts.single()
            @Suppress("UNCHECKED_CAST")
            val outer = cdcSource.map as Map<String, Any?>
            outer["schema"] shouldBe "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0"

            @Suppress("UNCHECKED_CAST")
            val data = outer["data"] as Map<String, Any?>
            data["connector"]   shouldBe "postgres"
            data["db"]          shouldBe "orders_db"
            data["schema"]      shouldBe "public"
            (data["table"] as String) shouldBeIn setOf("orders", "customers")
            (data["lsn"] as String) shouldMatch Regex("^[0-9A-F]+/[0-9A-F]+$")
            data["txid"]        shouldNotBe null
            data["is_snapshot"] shouldBe false
            (data["ts_ms"] as Long) shouldBeGreaterThan 0L
            (data["column_fingerprint"] as String) shouldMatch Regex("^[0-9a-f]{64}$")
        }
    }

    // Decodes the base64 ue_px payload into the outer self-describing-event map.
    // DeterministicEvent forces setBase64Encode(true), so events carry `ue_px`, not `ue_pr`.
    @Suppress("UNCHECKED_CAST")
    private fun outerSdeOf(event: Event): Map<String, Any?> {
        val payloadMap = event.payload.map
        val ueB64 = payloadMap["ue_px"] as? String ?: error("ue_px not found in payload")
        val ueJson = String(java.util.Base64.getDecoder().decode(ueB64))
        return mapper.readValue(ueJson, Map::class.java) as Map<String, Any?>
    }

    // Returns the inner self-describing-event's schema URI (the per-table iglu URI).
    @Suppress("UNCHECKED_CAST")
    private fun schemaUriOf(event: Event): String {
        val sdj = outerSdeOf(event)["data"] as Map<String, Any?>
        return sdj["schema"] as String
    }

    // Returns the inner self-describing-data payload — the per-table change envelope with op/before/after.
    @Suppress("UNCHECKED_CAST")
    private fun tablePayloadOf(event: Event): Map<String, Any?> {
        val sdj = outerSdeOf(event)["data"] as Map<String, Any?>
        return sdj["data"] as Map<String, Any?>
    }

    @Test
    fun `UPDATE in Postgres emits one event with op=u, before, and after`() {
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (43, 1, 'pending', 99.00)"
                )
                st.execute("UPDATE public.orders SET status = 'shipped' WHERE id = 43")
            }
        }

        awaitEventCount(2)

        val updateEvent = emitter.events.first { e ->
            @Suppress("UNCHECKED_CAST")
            (tablePayloadOf(e)["op"] as? String) == "u"
        }
        val data = tablePayloadOf(updateEvent)

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["status"] shouldBe "pending"

        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>
        after["status"] shouldBe "shipped"
    }

    @Test
    fun `DELETE in Postgres emits one event with op=d, before only, no after key`() {
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (99, 7, 'cancelled', 0.00)"
                )
                st.execute("DELETE FROM public.orders WHERE id = 99")
            }
        }

        awaitEventCount(2)

        val deleteEvent = emitter.events.first { e ->
            @Suppress("UNCHECKED_CAST")
            (tablePayloadOf(e)["op"] as? String) == "d"
        }
        val data = tablePayloadOf(deleteEvent)

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["id"] shouldBe 99
        before["status"] shouldBe "cancelled"

        data.containsKey("after") shouldBe false
    }

    @Test
    fun `customers events apply rename, exclude, and transforms in the emitted payload`() {
        // The fixture YAML configures `email` with rename: emailAddress AND
        // transforms: [trim, lowercase]. Inserting "  B@Example.COM  " proves all three
        // directives travel end-to-end: leading/trailing whitespace is stripped, mixed case
        // is folded, the property is renamed, and password_hash is dropped.
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.customers (id, email, full_name, password_hash) " +
                        "VALUES (2, '  B@Example.COM  ', 'Grace Hopper', 'pbkdf2:xyz')"
                )
            }
        }

        awaitEventCount(1)

        val event = emitter.events.single()
        @Suppress("UNCHECKED_CAST")
        val after = tablePayloadOf(event)["after"] as Map<String, Any?>

        after.keys shouldBe setOf("id", "emailAddress", "full_name")
        // Renamed: email -> emailAddress. Transformed: trim then lowercase.
        after["emailAddress"] shouldBe "b@example.com"
        after.containsKey("password_hash") shouldBe false
        after.containsKey("email") shouldBe false
    }

    @Test
    fun `event_id is a present, well-formed UUID`() {
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "INSERT INTO public.orders (id, customer_id, status, total) " +
                        "VALUES (123, 4, 'new', 12.34)"
                )
            }
        }
        awaitEventCount(1)

        val firstEid = emitter.events[0].payload.map["eid"]
        firstEid shouldNotBe null

        (firstEid as String) shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }

    private fun awaitEventCount(expected: Int, timeoutMs: Long = PostgresReplicationFixture.AWAIT_DEFAULT_TIMEOUT_MS) {
        fixture.awaitWith(timeoutMs, "RecordingEmitter to receive $expected event(s)") {
            emitter.events.size >= expected
        }
    }
}
