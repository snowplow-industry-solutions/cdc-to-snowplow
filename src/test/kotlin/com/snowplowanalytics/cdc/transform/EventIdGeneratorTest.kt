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

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class EventIdGeneratorTest {
    private val gen = EventIdGenerator()

    @Test
    fun `pinned UUIDv5 for the canonical orders UPDATE coordinates`() {
        // The canonical JSON for these inputs (sorted keys) is:
        //   {"connector":"postgres","db":"orders_db","lsn":"0/16D5DC0","op":"u","pk":{"id":42},
        //    "schema":"public","table":"orders","txid":123}
        // With namespace 8a1f4e5c-c0de-4cdc-a000-000000000001 the UUIDv5 is:
        val expected = UUID.fromString("8172d518-4b02-5788-8da0-e5f65ecda71d")
        val actual = gen.generate(
            connector = "postgres",
            db = "orders_db",
            schema = "public",
            table = "orders",
            op = "u",
            lsn = "0/16D5DC0",
            txid = 123L,
            pkValues = linkedMapOf("id" to 42),
        )
        actual shouldBe expected
    }

    @Test
    fun `pinned UUIDv5 with null txid (snapshot read case)`() {
        val expected = UUID.fromString("c9fe6c8d-b3f5-52ad-a2e1-de5410177919")
        val actual = gen.generate(
            connector = "postgres",
            db = "orders_db",
            schema = "public",
            table = "orders",
            op = "r",
            lsn = "0/16D5DC0",
            txid = null,
            pkValues = linkedMapOf("id" to 42),
        )
        actual shouldBe expected
    }

    @Test
    fun `same coordinates produce identical UUIDs across calls`() {
        val first  = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        val second = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        first shouldBe second
    }

    @Test
    fun `changing only op produces a different UUID`() {
        val asInsert = gen.generate("postgres", "orders_db", "public", "orders", "c", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        val asUpdate = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        (asInsert == asUpdate) shouldBe false
    }

    @Test
    fun `changing only the pk value produces a different UUID`() {
        val pk42 = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        val pk43 = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 43))
        (pk42 == pk43) shouldBe false
    }

    @Test
    fun `changing only the lsn produces a different UUID`() {
        val lsnA = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC0", 123L, linkedMapOf("id" to 42))
        val lsnB = gen.generate("postgres", "orders_db", "public", "orders", "u", "0/16D5DC1", 123L, linkedMapOf("id" to 42))
        (lsnA == lsnB) shouldBe false
    }
}
