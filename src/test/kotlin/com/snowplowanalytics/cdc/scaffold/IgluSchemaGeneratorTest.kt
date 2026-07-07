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

import com.fasterxml.jackson.databind.ObjectMapper
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class IgluSchemaGeneratorTest {
    private val om = ObjectMapper()
    private fun column(name: String, udt: String, dataType: String = udt, nullable: Boolean = false) =
        DbColumn(name, 1, nullable, dataType, udt, null, null)

    private val table = TableMetadata(
        key = TableKey("public", "orders"),
        replicaIdentity = ReplicaIdentity.FULL,
        columns = listOf(
            column("id", "int4", "integer"),
            column("note", "text", "text", nullable = true),
        ),
        primaryKeyColumns = listOf("id"),
    )

    @Test
    fun `self block uses vendor table-name format version`() {
        val tree = om.readTree(IgluSchemaGenerator.generate(table, "com.acme.cdc"))
        tree["self"]["vendor"].asText() shouldBe "com.acme.cdc"
        tree["self"]["name"].asText() shouldBe "orders_change"
        tree["self"]["format"].asText() shouldBe "jsonschema"
        tree["self"]["version"].asText() shouldBe "1-0-0"
    }

    @Test
    fun `op before after envelope present with op enum`() {
        val tree = om.readTree(IgluSchemaGenerator.generate(table, "com.acme.cdc"))
        val props = tree["properties"]
        props["op"]["enum"].map { it.asText() } shouldContainExactly listOf("c", "u", "d", "r")
        props["before"]["type"].map { it.asText() } shouldContainExactly listOf("object", "null")
        props["after"]["type"].map { it.asText() } shouldContainExactly listOf("object", "null")
    }

    @Test
    fun `columns mapped under before and after row objects`() {
        val tree = om.readTree(IgluSchemaGenerator.generate(table, "com.acme.cdc"))
        val afterProps = tree["properties"]["after"]["properties"]
        afterProps["id"]["type"].asText() shouldBe "integer"
        afterProps["note"]["type"].map { it.asText() } shouldContainExactly listOf("string", "null")
    }

    @Test
    fun `not null columns are required within the row object`() {
        val tree = om.readTree(IgluSchemaGenerator.generate(table, "com.acme.cdc"))
        tree["properties"]["after"]["required"].map { it.asText() } shouldContainExactly listOf("id")
    }

    @Test
    fun `top-level required is op only`() {
        val tree = om.readTree(IgluSchemaGenerator.generate(table, "com.acme.cdc"))
        tree["required"].map { it.asText() } shouldContainExactly listOf("op")
    }

    @Test
    fun `description marks the schema auto-generated and names the source table`() {
        val text = IgluSchemaGenerator.generate(table, "com.acme.cdc")
        text shouldContain "auto-generated"
        text shouldContain "public.orders"
    }

    @Test
    fun `unmapped column type is flagged in its description`() {
        val t = table.copy(columns = listOf(column("addr", "inet", "inet")))
        val tree = om.readTree(IgluSchemaGenerator.generate(t, "com.acme.cdc"))
        tree["properties"]["after"]["properties"]["addr"]["description"].asText() shouldContain "unmapped"
    }
}
