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

package com.snowplowanalytics.cdc.engine

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.DebeziumConfig
import com.snowplowanalytics.cdc.config.EmitterConfig
import com.snowplowanalytics.cdc.config.OffsetStoreConfig
import com.snowplowanalytics.cdc.config.ServiceConfig
import com.snowplowanalytics.cdc.config.SnowplowConfig
import com.snowplowanalytics.cdc.config.SourceConfig
import com.snowplowanalytics.cdc.config.TableConfig
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.transform.changeEventEnvelope
import io.debezium.engine.ChangeEvent
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit-style tests for EngineRunner.handleRecord instrumentation.
 * These tests bypass the full Debezium engine and invoke handleRecord directly via the
 * `internal` visibility modifier, which is accessible from the same module's test sources.
 */
class EngineRunnerInstrumentationTest {

    private lateinit var counters: Counters
    private lateinit var probe: ReadinessProbe
    private lateinit var runner: EngineRunner

    @BeforeEach
    fun setUp() {
        counters = Counters(bufferCapacity = 1000)
        probe = ReadinessProbe()
        val emitter = RecordingEmitter()

        // Build a minimal Config using data-class constructors. The connector is "postgres" but
        // start() is never called in these tests — only handleRecord() is exercised directly.
        val config = Config(
            service = ServiceConfig(appId = "test"),
            source = SourceConfig(
                connector = "postgres",
                hostname = "localhost",
                port = 5432,
                database = "test_db",
                username = "test",
                password = "test",
                slotName = "test_slot",
                publicationName = "test_pub",
            ),
            debezium = DebeziumConfig(
                offsetStore = OffsetStoreConfig(type = "file", filePath = "/tmp/test-offsets.dat"),
            ),
            snowplow = SnowplowConfig(
                collectorUrl = "http://unused",
                cdcSourceSchema = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
                emitter = EmitterConfig(),
            ),
            tables = listOf(
                TableConfig(
                    name = "orders",
                    schema = "public",
                    igluSchema = "iglu:com.example/orders_change/jsonschema/1-0-0",
                    primaryKey = listOf("id"),
                    columns = listOf(
                        ColumnSpec("id"),
                        ColumnSpec("customer_id"),
                        ColumnSpec("status"),
                        ColumnSpec("total"),
                    ),
                ),
            ),
        )

        runner = EngineRunner(config, emitter, counters, probe)
    }

    @Test
    fun `handleRecord with INSERT increments received and emitted, records perTable op, leaves dropped at zero`() {
        val envelope = changeEventEnvelope(
            op = "c",
            after = """{"id": 1, "customer_id": 10, "status": "pending", "total": "99.00"}""",
        )

        runner.handleRecord(fakeChangeEvent(envelope))

        counters.received.get() shouldBe 1L
        counters.emitted.get() shouldBe 1L
        counters.dropped.get() shouldBe 0L

        val snap = counters.snapshot()
        snap.perTable["public.orders"]?.get("c") shouldBe 1L
    }

    @Test
    fun `handleRecord with TRUNCATE (op=t) increments received and dropped, leaves emitted and perTable unchanged`() {
        // op=t (TRUNCATE) triggers UnsupportedOpException in the assembler —
        // it should be counted as dropped, not emitted.
        val envelope = changeEventEnvelope(op = "t")

        runner.handleRecord(fakeChangeEvent(envelope))

        counters.received.get() shouldBe 1L
        counters.dropped.get() shouldBe 1L
        counters.emitted.get() shouldBe 0L
        counters.snapshot().perTable.shouldBeEmpty()
    }

    @Test
    fun `handleRecord skips heartbeat records, leaving all counters at zero and perTable empty`() {
        // Debezium heartbeats arrive on the `__debezium-heartbeat.*` topic with a ts_ms-only
        // value (no `after`). The value below would throw "missing schema.fields[after]" in the
        // fingerprinter if it reached it — proving the skip happens by destination, before the
        // fingerprinter, and that heartbeats are not counted as received/dropped.
        val heartbeatValue =
            """{"schema":{"type":"struct","fields":[{"type":"int64","optional":false,""" +
                """"field":"ts_ms"}],"optional":true,"name":"io.debezium.connector.common.Heartbeat"},""" +
                """"payload":{"ts_ms":1700000000000}}"""

        runner.handleRecord(heartbeatChangeEvent(heartbeatValue))

        counters.received.get() shouldBe 0L
        counters.emitted.get() shouldBe 0L
        counters.dropped.get() shouldBe 0L
        counters.snapshot(0).perTable.shouldBeEmpty()
    }

    @Test
    fun `handleRecord with tombstone (null value) leaves all counters at zero and perTable empty`() {
        // Spec §7: tombstones do not count — they are silently discarded before any counter is incremented.
        runner.handleRecord(tombstoneChangeEvent())

        counters.received.get() shouldBe 0L
        counters.emitted.get() shouldBe 0L
        counters.dropped.get() shouldBe 0L
        counters.snapshot(0).perTable.shouldBeEmpty()
    }

    // ChangeEvent<String, String> is a simple interface — inline implementation sufficient for tests.
    private fun fakeChangeEvent(value: String): ChangeEvent<String, String> =
        object : ChangeEvent<String, String> {
            override fun key(): String = "{\"id\":1}"
            override fun value(): String = value
            override fun destination(): String = "cdc-service.public.orders"
            override fun partition(): Int = 0
        }

    // A heartbeat record lands on Debezium's dedicated heartbeat topic
    // (`__debezium-heartbeat.<topic.prefix>`). The value carries a ts_ms-only schema, not a change.
    private fun heartbeatChangeEvent(value: String): ChangeEvent<String, String> =
        object : ChangeEvent<String, String> {
            override fun key(): String = "{\"serverName\":\"cdc-service\"}"
            override fun value(): String = value
            override fun destination(): String = "__debezium-heartbeat.cdc-service"
            override fun partition(): Int = 0
        }

    // A tombstone has a non-null key but a null value — Kafka-level delete marker.
    private fun tombstoneChangeEvent(): ChangeEvent<String, String> =
        object : ChangeEvent<String, String> {
            override fun key(): String = "{\"id\":1}"
            override fun value(): String? = null
            override fun destination(): String = "cdc-service.public.orders"
            override fun partition(): Int = 0
        }
}
