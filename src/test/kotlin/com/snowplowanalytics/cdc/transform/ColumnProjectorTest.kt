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

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.TableConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ColumnProjectorTest {

    private fun tableConfig(vararg cols: ColumnSpec) = TableConfig(
        name = "orders",
        schema = "public",
        igluSchema = "iglu:com.example/orders/jsonschema/1-0-0",
        primaryKey = listOf("id"),
        columns = cols.toList(),
    )

    @Test
    fun `unlisted columns are dropped`() {
        val projector = ColumnProjector(
            tableConfig(ColumnSpec("id"), ColumnSpec("status"))
        )
        val out = projector.project(mapOf("id" to 1, "status" to "pending", "secret" to "x"))
        out shouldBe linkedMapOf("id" to 1, "status" to "pending")
    }

    @Test
    fun `excluded columns are dropped`() {
        val projector = ColumnProjector(
            tableConfig(
                ColumnSpec("id"),
                ColumnSpec("password_hash", exclude = true),
                ColumnSpec("status"),
            )
        )
        val out = projector.project(mapOf("id" to 1, "password_hash" to "abc", "status" to "p"))
        out shouldBe linkedMapOf("id" to 1, "status" to "p")
    }

    @Test
    fun `renamed columns use the rename target as key`() {
        val projector = ColumnProjector(
            tableConfig(ColumnSpec("id"), ColumnSpec("customer_id", rename = "customerId"))
        )
        val out = projector.project(mapOf("id" to 1, "customer_id" to 42))
        out shouldBe linkedMapOf("id" to 1, "customerId" to 42)
    }

    @Test
    fun `column present in config but missing from row emits explicit null`() {
        // Simulates a column dropped from the DB after startup: present in our config-derived plan,
        // absent from the actual change-event payload.
        val projector = ColumnProjector(
            tableConfig(ColumnSpec("id"), ColumnSpec("status"))
        )
        val out = projector.project(mapOf("id" to 1))  // "status" absent
        out shouldBe linkedMapOf("id" to 1, "status" to null)
        // Map equality is content-only; also pin key order so a regression to a row-driven loop
        // (which would yield {id} instead of {id, status}) and any future key-reordering bug both fail.
        out!!.keys.toList() shouldBe listOf("id", "status")
    }

    @Test
    fun `output map preserves YAML declaration order, not row insertion order`() {
        val projector = ColumnProjector(
            tableConfig(ColumnSpec("a"), ColumnSpec("b"), ColumnSpec("c"))
        )
        // Row insertion order is c, a, b — output should still be a, b, c.
        val row = linkedMapOf<String, Any?>("c" to 3, "a" to 1, "b" to 2)
        val out = projector.project(row)
        out!!.keys.toList() shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `null input returns null`() {
        val projector = ColumnProjector(tableConfig(ColumnSpec("id")))
        projector.project(null) shouldBe null
    }
}
