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

package com.snowplowanalytics.cdc.transform

import com.snowplowanalytics.cdc.config.TableKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SourceSchemaFingerprinterTest {

    private val baselineColumns = listOf(
        ColSpec("id", "int32"),
        ColSpec("customer_id", "int32"),
        ColSpec("status", "string"),
        ColSpec("total", "string"),
    )

    private val baselineAfter = """{"id":1,"customer_id":42,"status":"pending","total":"99.00"}"""

    @Test
    fun `same envelope produces identical snapshot`() {
        val envelope = changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        val first = SourceSchemaFingerprinter.snapshot(envelope)
        val second = SourceSchemaFingerprinter.snapshot(envelope)
        first shouldBe second
    }

    @Test
    fun `column list is in declaration order`() {
        val envelope = changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        val snap = SourceSchemaFingerprinter.snapshot(envelope)
        // Declaration order, not alphabetical — would be "customer_id, id, status, total" if sorted.
        snap.columnNames shouldContainExactly listOf("id", "customer_id", "status", "total")
    }

    @Test
    fun `adding a column changes fingerprint and lengthens column list`() {
        val baseline = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        )
        val withExtra = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(
                op = "c",
                after = baselineAfter,
                columns = baselineColumns + ColSpec("created_at", "int64"),
            )
        )
        withExtra.fingerprint shouldNotBe baseline.fingerprint
        withExtra.columnNames shouldContainExactly listOf("id", "customer_id", "status", "total", "created_at")
    }

    @Test
    fun `removing a column changes fingerprint and shortens column list`() {
        val baseline = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        )
        val trimmed = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(
                op = "c",
                after = baselineAfter,
                columns = baselineColumns.dropLast(1),
            )
        )
        trimmed.fingerprint shouldNotBe baseline.fingerprint
        trimmed.columnNames shouldContainExactly listOf("id", "customer_id", "status")
    }

    @Test
    fun `same names with different types yields different fingerprint, same column list`() {
        val baseline = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        )
        // Identical names + order; only the primitive of `total` changes.
        val retyped = SourceSchemaFingerprinter.snapshot(
            changeEventEnvelope(
                op = "c",
                after = baselineAfter,
                columns = listOf(
                    ColSpec("id", "int32"),
                    ColSpec("customer_id", "int32"),
                    ColSpec("status", "string"),
                    ColSpec("total", "int64"),
                ),
            )
        )
        retyped.fingerprint shouldNotBe baseline.fingerprint
        retyped.columnNames shouldContainExactly baseline.columnNames
    }

    @Test
    fun `TableKey is extracted from payload source schema and table`() {
        val envelope = changeEventEnvelope(
            op = "c",
            after = baselineAfter,
            source = SourceSpec(schema = "sales", table = "invoices"),
            columns = baselineColumns,
        )
        val snap = SourceSchemaFingerprinter.snapshot(envelope)
        snap.table shouldBe TableKey(schema = "sales", name = "invoices")
    }

    @Test
    fun `fingerprint is 64 lowercase hex characters`() {
        val envelope = changeEventEnvelope(op = "c", after = baselineAfter, columns = baselineColumns)
        val snap = SourceSchemaFingerprinter.snapshot(envelope)
        snap.fingerprint shouldMatch Regex("^[0-9a-f]{64}$")
    }

    @Test
    fun `malformed envelope missing schema fields after throws IllegalStateException`() {
        // Hand-rolled envelope with a schema node whose fields array does not include "after".
        val envelope = """
            {
              "schema": {"type": "struct", "fields": [{"type":"string","field":"op","optional":false}]},
              "payload": {
                "before": null,
                "after": {"id": 1},
                "source": {"schema": "public", "table": "orders", "ts_ms": 0, "snapshot": "false"},
                "op": "c",
                "ts_ms": 0,
                "transaction": null
              }
            }
        """.trimIndent()
        val ex = assertThrows<IllegalStateException> {
            SourceSchemaFingerprinter.snapshot(envelope)
        }
        ex.message!! shouldContain "schema.fields[after]"
    }
}
