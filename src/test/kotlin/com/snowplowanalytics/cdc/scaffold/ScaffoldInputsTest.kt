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

package com.snowplowanalytics.cdc.scaffold

import com.snowplowanalytics.cdc.config.TableKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ScaffoldInputsTest {
    @Test fun `bare table defaults to public schema`() {
        parseTables("orders") shouldContainExactly listOf(TableKey("public", "orders"))
    }
    @Test fun `schema-qualified entry is respected`() {
        parseTables("sales.orders") shouldContainExactly listOf(TableKey("sales", "orders"))
    }
    @Test fun `comma list mixes qualified and bare`() {
        parseTables("orders,sales.invoices") shouldContainExactly
            listOf(TableKey("public", "orders"), TableKey("sales", "invoices"))
    }
    @Test fun `whitespace around entries trimmed`() {
        parseTables(" orders , customers ") shouldContainExactly
            listOf(TableKey("public", "orders"), TableKey("public", "customers"))
    }
    @Test fun `empty entry rejected`() {
        shouldThrow<ScaffoldInputError> { parseTables("orders,,customers") }.message!! shouldContain "empty"
    }
    @Test fun `too many dots rejected`() {
        shouldThrow<ScaffoldInputError> { parseTables("a.b.c") }.message!! shouldContain "too many"
    }
    @Test fun `duplicate after resolution rejected`() {
        shouldThrow<ScaffoldInputError> { parseTables("orders,public.orders") }.message!! shouldContain "twice"
    }

    @Test fun `postgres uri parsed with pgpassword`() {
        val c = parseConnection("postgres://cdc@db.example.com:5432/orders_db", env = mapOf("PGPASSWORD" to "secret"))
        c.coords.hostname shouldBe "db.example.com"
        c.coords.port shouldBe 5432
        c.coords.database shouldBe "orders_db"
        c.coords.username shouldBe "cdc"
        c.password shouldBe "secret"
        c.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:5432/orders_db"
    }
    @Test fun `postgresql scheme accepted`() {
        parseConnection("postgresql://cdc@h:5432/d", env = mapOf("PGPASSWORD" to "x")).coords.hostname shouldBe "h"
    }
    @Test fun `jdbc prefix accepted with username from env`() {
        val c = parseConnection("jdbc:postgresql://h:5432/d", env = mapOf("PGUSER" to "cdc", "PGPASSWORD" to "x"))
        c.coords.username shouldBe "cdc"
    }
    @Test fun `embedded password accepted and flagged`() {
        val c = parseConnection("postgres://cdc:inline@h:5432/d", env = emptyMap())
        c.password shouldBe "inline"
        c.passwordFromUri shouldBe true
    }
    @Test fun `missing password fails`() {
        shouldThrow<ScaffoldInputError> {
            parseConnection("postgres://cdc@h:5432/d", env = emptyMap())
        }.message!! shouldContain "password"
    }
    @Test fun `missing username fails`() {
        shouldThrow<ScaffoldInputError> {
            parseConnection("postgres://h:5432/d", env = mapOf("PGPASSWORD" to "x"))
        }.message!! shouldContain "user"
    }
    @Test fun `unencoded at-sign in password yields guidance`() {
        shouldThrow<ScaffoldInputError> {
            parseConnection("postgres://user:p@ss@host:5432/db", env = emptyMap())
        }.message!! shouldContain "%40"
    }

    private fun parseTables(s: String) = ScaffoldInputs.parseTables(s)
    private fun parseConnection(s: String, env: Map<String, String>) = ScaffoldInputs.parseConnection(s, env::get)
}
