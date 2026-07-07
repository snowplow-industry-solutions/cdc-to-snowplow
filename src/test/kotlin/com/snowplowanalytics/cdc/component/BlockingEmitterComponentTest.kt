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

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.DebeziumConfig
import com.snowplowanalytics.cdc.config.EmitterConfig
import com.snowplowanalytics.cdc.config.OffsetStoreConfig
import com.snowplowanalytics.cdc.config.ServiceConfig
import com.snowplowanalytics.cdc.config.SnowplowConfig
import com.snowplowanalytics.cdc.config.SourceConfig
import com.snowplowanalytics.cdc.config.TableConfig
import com.snowplowanalytics.cdc.emitter.SnowplowEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.StubCollector
import io.debezium.engine.ChangeEvent
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives [EngineRunner.handleRecord] directly with synthetic Debezium-shaped JSON envelopes
 * and a [SnowplowEmitter] pointed at a stalled [StubCollector]. Proves:
 *
 *   - The producer thread blocks once the emitter buffer fills.
 *   - When the stub starts returning 200, the producer thread completes and all events arrive.
 *   - A blocked producer thread is woken by [EngineRunner.stop] within 5s.
 *
 * No Testcontainers; no Debezium engine. The point of this test is the egress backpressure
 * path; TracerBulletTest already covers the engine wiring end-to-end against a real Postgres.
 *
 * Design notes on the synthetic envelope:
 *   - The outer `schema` node is required because [PayloadAssembler.computeFingerprint] reads
 *     `schema.fields[after].fields` to build the column-fingerprint SHA-256 hash.
 *   - `source.db`, `source.schema`, `source.table` must match the [Config] table entry
 *     (`public.orders`) so [PayloadAssembler] can look up the [TableConfig].
 *   - `payload.op = "c"` is an INSERT; `after` is populated and `before` is null — the minimal
 *     viable op for this test.
 */
class BlockingEmitterComponentTest {

    private fun makeConfig(collectorUrl: String, bufferCapacity: Int): Config = Config(
        service = ServiceConfig(appId = "test"),
        source = SourceConfig(
            // The connector value is only used in Postgres-specific startup preflight inside
            // start(). We never call start() here, so any string is safe.
            connector = "stub",
            hostname = "unused",
            port = 5432,
            database = "unused",
            username = "unused",
            password = "unused",
            slotName = "unused",
            publicationName = "unused",
        ),
        debezium = DebeziumConfig(
            snapshotMode = "never",
            offsetStore = OffsetStoreConfig(type = "file", filePath = "/tmp/unused-offsets.dat"),
        ),
        snowplow = SnowplowConfig(
            collectorUrl = collectorUrl,
            cdcSourceSchema = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
            emitter = EmitterConfig(batchSize = 1, bufferCapacity = bufferCapacity),
        ),
        tables = listOf(
            TableConfig(
                name = "orders",
                schema = "public",
                igluSchema = "iglu:com.example/orders_change/jsonschema/1-0-0",
                primaryKey = listOf("id"),
                columns = listOf(
                    ColumnSpec(name = "id"),
                    ColumnSpec(name = "status"),
                ),
            ),
        ),
    )

    /**
     * Builds a synthetic INSERT envelope for `public.orders` with id=[id] and status="open".
     *
     * The outer `schema` block mirrors the Debezium schemas-enabled envelope shape that
     * [PayloadAssembler.computeFingerprint] reads. Only the `after` fields array matters for
     * fingerprint computation; `before`, `source`, `op`, and `ts_ms` entries in the schema
     * section are required to be present but their individual field entries are not inspected
     * by the fingerprint logic.
     */
    private fun syntheticInsertEnvelope(id: Int): String =
        """
        {
          "schema": {
            "type": "struct",
            "fields": [
              {
                "field": "before",
                "type": "struct",
                "optional": true,
                "fields": [
                  {"field": "id",     "type": "int32",  "optional": false},
                  {"field": "status", "type": "string", "optional": true}
                ]
              },
              {
                "field": "after",
                "type": "struct",
                "optional": true,
                "fields": [
                  {"field": "id",     "type": "int32",  "optional": false},
                  {"field": "status", "type": "string", "optional": true}
                ]
              },
              {
                "field": "source",
                "type": "struct",
                "optional": false,
                "fields": [
                  {"field": "db",     "type": "string", "optional": false},
                  {"field": "schema", "type": "string", "optional": false},
                  {"field": "table",  "type": "string", "optional": false},
                  {"field": "ts_ms",  "type": "int64",  "optional": false},
                  {"field": "lsn",    "type": "int64",  "optional": true}
                ]
              },
              {"field": "op",    "type": "string", "optional": false},
              {"field": "ts_ms", "type": "int64",  "optional": false}
            ]
          },
          "payload": {
            "before": null,
            "after": {"id": $id, "status": "open"},
            "source": {
              "db": "test_db",
              "schema": "public",
              "table": "orders",
              "ts_ms": 0,
              "lsn": $id
            },
            "op": "c",
            "ts_ms": 0
          }
        }
        """.trimIndent()

    private fun changeEvent(json: String): ChangeEvent<String, String> =
        object : ChangeEvent<String, String> {
            override fun key(): String? = null
            override fun value(): String = json
            override fun destination(): String = "cdc-service.public.orders"
            override fun partition(): Int? = null
        }

    @Test
    fun `handleRecord blocks producer when buffer fills and unblocks on 200`() {
        StubCollector().use { stub ->
            val counters = Counters(bufferCapacity = 2)
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 2,
                counters = counters,
            )
            val runner = EngineRunner(makeConfig(stub.url, 2), emitter, counters, ReadinessProbe())
            try {
                val producer = Thread {
                    repeat(3) { i -> runner.handleRecord(changeEvent(syntheticInsertEnvelope(i + 1))) }
                }.apply { start() }

                // Wait up to 500ms; producer should be blocked on the 3rd event with a full buffer.
                producer.join(500)
                producer.isAlive shouldBe true

                // The stub is stalled so the tracker cannot complete batches and permit the
                // semaphore to release. The producer is parked in emit() on the 3rd event.
                // The stub has received at least 1 request (batchSize=1 so first event fired
                // immediately) but fewer than 3 because the producer is still blocked.
                (stub.received.size in 1..2) shouldBe true

                stub.unblock()
                producer.join(2_000)
                producer.isAlive shouldBe false

                // Give the tracker a moment to drain any remaining in-flight batches.
                Thread.sleep(500)
                stub.received.size shouldBe 3
            } finally {
                emitter.close()
            }
        }
    }

    @Test
    fun `runner stop releases a blocked producer within 5s`() {
        StubCollector().use { stub ->
            val counters = Counters(bufferCapacity = 2)
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 2,
                counters = counters,
            )
            val runner = EngineRunner(makeConfig(stub.url, 2), emitter, counters, ReadinessProbe())

            val thrown = AtomicReference<Throwable?>(null)
            val producer = Thread {
                try {
                    repeat(3) { i -> runner.handleRecord(changeEvent(syntheticInsertEnvelope(i + 1))) }
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }.apply { start() }

            // Confirm the producer is parked before we invoke stop().
            producer.join(500)
            producer.isAlive shouldBe true

            // Run stop() on a background thread so we can independently time how quickly the
            // producer unblocks. stop() calls emitter.close() first (flood-releases the
            // semaphore), which wakes the blocked producer almost immediately. stop() then
            // calls tracker.close(), which can block waiting for OkHttp to drain in-flight
            // requests — that is independent of the producer's liveness.
            val stopStart = System.nanoTime()
            val stopper = Thread { runner.stop() }.apply { start() }

            // The producer must exit well within the 10s awaitTermination budget.
            // 5s here is generous — cooperative semaphore release should happen in milliseconds.
            producer.join(5_000)
            val producerReleasedMs = (System.nanoTime() - stopStart) / 1_000_000

            producer.isAlive shouldBe false
            producerReleasedMs shouldBeLessThan 5_000L

            // Wait for the stopper to finish (it may block in tracker.close() longer than
            // the producer, but we don't assert on its duration — only that it completes).
            stopper.join(15_000)

            // Either the loop finished cleanly (handleRecord swallowed EmitterClosedException
            // via its internal catch) or thrown holds no exception — either is acceptable.
            // The contract is that the producer thread does not hang.
        }
    }
}
