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
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ConfigYamlGeneratorTest {
    private fun col(name: String, nullable: Boolean = false) =
        DbColumn(name, 1, nullable, "integer", "int4", null, null)

    private val coords = ScaffoldConnectionCoords(
        hostname = "db.example.com", port = 5432, database = "orders_db", username = "cdc",
    )

    private fun gen(md: DbMetadata) = ConfigYamlGenerator.generate(md, vendor = "com.acme.cdc", coords = coords)

    private val ordersPk = TableMetadata(
        TableKey("public", "orders"), ReplicaIdentity.FULL,
        listOf(col("id"), col("customer_id"), col("total", nullable = true)),
        primaryKeyColumns = listOf("id"),
    )

    @Test fun `lsp hint points at the colocated config schema`() {
        gen(DbMetadata(listOf(ordersPk))) shouldContain "# yaml-language-server: \$schema=./cdc-service-config.schema.json"
    }

    @Test fun `header marks file auto-generated and explains env var interpolation`() {
        val y = gen(DbMetadata(listOf(ordersPk)))
        y shouldContain "auto-generated"
        y shouldContain "\${ENV_VAR}"
    }

    @Test fun `source block uses literal non-secrets and env-var password`() {
        val y = gen(DbMetadata(listOf(ordersPk)))
        y shouldContain "hostname: db.example.com"
        y shouldContain "port: 5432"
        y shouldContain "database: orders_db"
        y shouldContain "username: cdc"
        y shouldContain "password: \${POSTGRES_PASSWORD}"
    }

    @Test fun `collector url is an env var placeholder`() {
        gen(DbMetadata(listOf(ordersPk))) shouldContain "collector_url: \${COLLECTOR_URL}"
    }

    @Test fun `iglu schema uri uses the vendor`() {
        gen(DbMetadata(listOf(ordersPk))) shouldContain "iglu:com.acme.cdc/orders_change/jsonschema/1-0-0"
    }

    @Test fun `all columns listed plainly with no transforms or renames`() {
        val y = gen(DbMetadata(listOf(ordersPk)))
        y shouldContain "      - id"
        y shouldContain "      - customer_id"
        y shouldContain "      - total"
        (y.contains("rename:") || y.contains("transforms:")) shouldBe false
    }

    @Test fun `detected primary key is populated`() {
        gen(DbMetadata(listOf(ordersPk))) shouldContain "primary_key: [id]"
    }

    @Test fun `composite primary key preserves order`() {
        val li = TableMetadata(
            TableKey("public", "line_items"), ReplicaIdentity.FULL,
            listOf(col("tenant_id"), col("id"), col("sku")),
            primaryKeyColumns = listOf("tenant_id", "id"),
        )
        gen(DbMetadata(listOf(li))) shouldContain "primary_key: [tenant_id, id]"
    }

    @Test fun `pk-less table emits empty pk with todo and unique-index hint`() {
        val contacts = TableMetadata(
            TableKey("public", "contacts"), ReplicaIdentity.DEFAULT,
            listOf(col("email"), col("name", nullable = true)),
            primaryKeyColumns = emptyList(),
            uniqueIndexes = listOf(listOf("email")),
        )
        val y = gen(DbMetadata(listOf(contacts)))
        y shouldContain "primary_key: []"
        y shouldContain "TODO"
        y shouldContain "email"
    }
}
