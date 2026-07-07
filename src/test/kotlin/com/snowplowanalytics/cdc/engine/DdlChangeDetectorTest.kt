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

import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.transform.SourceSchemaSnapshot
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DdlChangeDetectorTest {

    private val orders = TableKey("public", "orders")
    private val customers = TableKey("public", "customers")

    private fun snapshot(table: TableKey, fp: String, columns: List<String>) =
        SourceSchemaSnapshot(table, fp, columns)

    @Test
    fun `first event for a table returns null`() {
        val detector = DdlChangeDetector()

        val result = detector.observe(orders, snapshot(orders, "fp-1", listOf("id", "total")))

        result.shouldBeNull()
    }

    @Test
    fun `repeat identical fingerprint returns null`() {
        val detector = DdlChangeDetector()
        val snap = snapshot(orders, "fp-1", listOf("id", "total"))

        detector.observe(orders, snap)
        val second = detector.observe(orders, snap)

        second.shouldBeNull()
    }

    @Test
    fun `added column produces SchemaChange with alphabetical addedColumns and empty removedColumns`() {
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "fp-1", listOf("id", "total")))

        val change = detector.observe(
            orders,
            snapshot(orders, "fp-2", listOf("id", "total", "status", "customer_id")),
        )

        change.shouldNotBeNull()
        change.table shouldBe "public.orders"
        change.previousFingerprint shouldBe "fp-1"
        change.currentFingerprint shouldBe "fp-2"
        change.addedColumns shouldBe listOf("customer_id", "status")
        change.removedColumns shouldBe emptyList()
    }

    @Test
    fun `removed column produces SchemaChange with alphabetical removedColumns and empty addedColumns`() {
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "fp-1", listOf("id", "total", "status", "customer_id")))

        val change = detector.observe(orders, snapshot(orders, "fp-2", listOf("id", "total")))

        change.shouldNotBeNull()
        change.table shouldBe "public.orders"
        change.previousFingerprint shouldBe "fp-1"
        change.currentFingerprint shouldBe "fp-2"
        change.addedColumns shouldBe emptyList()
        change.removedColumns shouldBe listOf("customer_id", "status")
    }

    @Test
    fun `mixed add and remove produces both lists alphabetised`() {
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "fp-1", listOf("id", "total", "status")))

        val change = detector.observe(
            orders,
            snapshot(orders, "fp-2", listOf("id", "total", "customer_id", "shipped_at")),
        )

        change.shouldNotBeNull()
        change.addedColumns shouldBe listOf("customer_id", "shipped_at")
        change.removedColumns shouldBe listOf("status")
    }

    @Test
    fun `same names with different types yields empty diff arrays but non-null SchemaChange`() {
        // Documents the type-change gap from the spec: the fingerprint differs (type changed)
        // but the column-name diff is empty because both lists carry the same names.
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "fp-int32", listOf("id", "total")))

        val change = detector.observe(orders, snapshot(orders, "fp-int64", listOf("id", "total")))

        change.shouldNotBeNull()
        change.previousFingerprint shouldBe "fp-int32"
        change.currentFingerprint shouldBe "fp-int64"
        change.addedColumns shouldBe emptyList()
        change.removedColumns shouldBe emptyList()
    }

    @Test
    fun `independent tables maintain independent state`() {
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "orders-fp-1", listOf("id")))
        detector.observe(customers, snapshot(customers, "customers-fp-1", listOf("id")))

        // Transitioning customers must not affect orders' stored state.
        val customersChange = detector.observe(
            customers,
            snapshot(customers, "customers-fp-2", listOf("id", "email")),
        )
        customersChange.shouldNotBeNull()
        customersChange.table shouldBe "public.customers"

        // Re-observing orders with its original snapshot must still be a silent no-op.
        val ordersRepeat = detector.observe(orders, snapshot(orders, "orders-fp-1", listOf("id")))
        ordersRepeat.shouldBeNull()
    }

    @Test
    fun `state updates monotonically across multiple transitions`() {
        val detector = DdlChangeDetector()
        detector.observe(orders, snapshot(orders, "fp-1", listOf("id")))
        detector.observe(orders, snapshot(orders, "fp-2", listOf("id", "total")))

        val third = detector.observe(orders, snapshot(orders, "fp-3", listOf("id", "total", "status")))

        third.shouldNotBeNull()
        // The third transition's "previous" must be fp-2 (most recent), not fp-1.
        third.previousFingerprint shouldBe "fp-2"
        third.currentFingerprint shouldBe "fp-3"
        third.addedColumns shouldBe listOf("status")
        third.removedColumns shouldBe emptyList()
    }

    @Test
    fun `toLogMap returns the documented field set in insertion order`() {
        val change = SchemaChange(
            table = "public.orders",
            previousFingerprint = "fp-1",
            currentFingerprint = "fp-2",
            addedColumns = listOf("customer_id", "status"),
            removedColumns = listOf("legacy_flag"),
        )

        val map = change.toLogMap()

        map.keys.toList() shouldBe listOf(
            "event",
            "table",
            "previous_fingerprint",
            "current_fingerprint",
            "added_columns",
            "removed_columns",
        )
        map["event"] shouldBe "source_schema_change"
        map["table"] shouldBe "public.orders"
        map["previous_fingerprint"] shouldBe "fp-1"
        map["current_fingerprint"] shouldBe "fp-2"
        map["added_columns"] shouldBe listOf("customer_id", "status")
        map["removed_columns"] shouldBe listOf("legacy_flag")
    }
}
