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

package com.snowplowanalytics.cdc.preflight

import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class ReplicaIdentityCheckTest {

    private fun tableMetadata(schema: String, name: String, identity: ReplicaIdentity) =
        TableMetadata(
            TableKey(schema, name),
            identity,
            listOf(DbColumn("id", 1, isNullable = false, dataType = "integer", udtName = "int4", enumLabels = null)),
        )

    private fun dbMetadata(vararg tables: TableMetadata) = DbMetadata(tables.toList())

    // Builds a Config by writing YAML to a tempfile and parsing it through ConfigLoader — the same production code path used by Main.runService.
    // ConfigLoader's semantic validator rejects non-postgres connectors, so the non-postgres test
    // takes the result of a postgres load and `.copy()`s the source.connector field.
    private fun configFor(connector: String = "postgres", tables: List<Pair<String, String>>): Config {
        val tablesYaml = tables.joinToString("\n") { (schema, name) ->
            """
              - name: $name
                schema: $schema
                iglu_schema: iglu:com.example/$name/jsonschema/1-0-0
                primary_key: [id]
                columns:
                  - id
            """.trimIndent().prependIndent("            ")
        }
        val yaml = """
            service:
              app_id: cdc
            source:
              connector: postgres
              hostname: localhost
              port: 5432
              database: db
              username: u
              password: p
              slot_name: s
              publication_name: pp
            debezium:
              snapshot_mode: never
              offset_store:
                type: file
                file_path: /tmp/o
              heartbeat_interval_ms: 0
              publication_autocreate_mode: filtered
              provide_transaction_metadata: false
            snowplow:
              collector_url: http://unused
              cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
            tables:
        """.trimIndent() + "\n" + tablesYaml
        val tmp = Files.createTempFile("cfg", ".yaml")
        tmp.writeText(yaml)
        val loaded = ConfigLoader.load(tmp)
        return if (connector == "postgres") loaded
        else loaded.copy(source = loaded.source.copy(connector = connector))
    }

    @Test
    fun `returns empty list when every table has REPLICA IDENTITY FULL`() {
        val metadata = dbMetadata(
            tableMetadata("public", "orders", ReplicaIdentity.FULL),
            tableMetadata("public", "customers", ReplicaIdentity.FULL),
        )
        val config = configFor(tables = listOf("public" to "orders", "public" to "customers"))
        ReplicaIdentityCheck(metadata, config).check().shouldBeEmpty()
    }

    @Test
    fun `returns one Finding when a single table has REPLICA IDENTITY default`() {
        val metadata = dbMetadata(
            tableMetadata("public", "orders", ReplicaIdentity.FULL),
            tableMetadata("public", "customers", ReplicaIdentity.DEFAULT),
        )
        val config = configFor(tables = listOf("public" to "orders", "public" to "customers"))
        val findings = ReplicaIdentityCheck(metadata, config).check()
        findings shouldHaveSize 1
        findings[0].schema shouldBe "public"
        findings[0].table shouldBe "customers"
        findings[0].identity shouldBe "default"
    }

    @Test
    fun `returns Finding with identity "default" for ReplicaIdentity DEFAULT`() {
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.DEFAULT))
        val config = configFor(tables = listOf("public" to "orders"))
        val findings = ReplicaIdentityCheck(metadata, config).check()
        findings shouldHaveSize 1
        findings[0].identity shouldBe "default"
    }

    @Test
    fun `returns Finding with identity "nothing" for ReplicaIdentity NOTHING`() {
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.NOTHING))
        val config = configFor(tables = listOf("public" to "orders"))
        val findings = ReplicaIdentityCheck(metadata, config).check()
        findings shouldHaveSize 1
        findings[0].identity shouldBe "nothing"
    }

    @Test
    fun `returns Finding with identity "index" for ReplicaIdentity INDEX`() {
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.INDEX))
        val config = configFor(tables = listOf("public" to "orders"))
        val findings = ReplicaIdentityCheck(metadata, config).check()
        findings shouldHaveSize 1
        findings[0].identity shouldBe "index"
    }

    @Test
    fun `returns Finding with identity "unknown" for ReplicaIdentity UNKNOWN`() {
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.UNKNOWN))
        val config = configFor(tables = listOf("public" to "orders"))
        val findings = ReplicaIdentityCheck(metadata, config).check()
        findings shouldHaveSize 1
        findings[0].identity shouldBe "unknown"
    }

    @Test
    fun `returns empty list for non-postgres connectors (check returns before inspecting metadata)`() {
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.DEFAULT))
        val config = configFor(connector = "mysql", tables = listOf("public" to "orders"))
        ReplicaIdentityCheck(metadata, config).check().shouldBeEmpty()
    }

    @Test
    fun `skips tables absent from metadata`() {
        // A configured table missing from the DbMetadata snapshot is a ColumnVerification concern
        // (tableMissing finding), not a replica-identity concern. This check should not flag it.
        val metadata = dbMetadata(tableMetadata("public", "orders", ReplicaIdentity.FULL))
        val config = configFor(tables = listOf("public" to "orders", "public" to "missing"))
        ReplicaIdentityCheck(metadata, config).check().shouldBeEmpty()
    }
}
