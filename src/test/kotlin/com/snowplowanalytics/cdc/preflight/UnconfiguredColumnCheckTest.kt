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
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class UnconfiguredColumnCheckTest {

    private fun col(name: String, ord: Int, dataType: String = "text"): DbColumn =
        DbColumn(name, ord, isNullable = false, dataType = dataType, udtName = dataType, enumLabels = null)

    private fun tableMetadata(schema: String, name: String, columns: List<Pair<String, String>>): TableMetadata =
        TableMetadata(
            TableKey(schema, name),
            ReplicaIdentity.FULL,
            columns.mapIndexed { i, (n, t) -> col(n, i + 1, t) },
        )

    /**
     * Spec for a single table in the YAML rendered by [configFor].
     * Each column entry is `(name, exclude)` — exclude=true emits the `{ exclude: true }` form.
     */
    private data class TableSpec(
        val schema: String,
        val name: String,
        val columns: List<Pair<String, Boolean>>,
    )

    // Builds a Config by writing YAML to a tempfile and parsing it through ConfigLoader — the same production code path used by Main.runService.
    private fun configFor(tables: List<TableSpec>): Config {
        val tablesYaml = tables.joinToString("\n") { spec ->
            val columnsYaml = spec.columns.joinToString("\n") { (name, exclude) ->
                if (exclude) "      - $name: { exclude: true }"
                else "      - $name"
            }
            """
              |  - name: ${spec.name}
              |    schema: ${spec.schema}
              |    iglu_schema: iglu:com.example/${spec.name}/jsonschema/1-0-0
              |    primary_key: [${spec.columns.first().first}]
              |    columns:
            """.trimMargin().trimEnd() + "\n" + columnsYaml
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
        return ConfigLoader.load(tmp)
    }

    @Test
    fun `empty when every db column appears in config`() {
        val metadata = DbMetadata(
            listOf(tableMetadata("public", "orders", listOf("id" to "integer", "status" to "text"))),
        )
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "status" to false))),
        )
        UnconfiguredColumnCheck(metadata, config).verify().shouldBeEmpty()
    }

    @Test
    fun `reports a db column not in config`() {
        val metadata = DbMetadata(
            listOf(tableMetadata("public", "orders", listOf("id" to "integer", "status" to "text", "secret" to "text"))),
        )
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "status" to false))),
        )
        UnconfiguredColumnCheck(metadata, config).verify() shouldContainExactly listOf(
            UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "secret", "text"),
        )
    }

    @Test
    fun `reports multiple extras across multiple tables`() {
        val metadata = DbMetadata(
            listOf(
                // Out-of-alphabetical order in the metadata to prove the check sorts within a table.
                tableMetadata(
                    "public", "orders",
                    listOf("id" to "integer", "zeta" to "text", "alpha" to "text"),
                ),
                tableMetadata(
                    "public", "customers",
                    listOf("id" to "integer", "email" to "text"),
                ),
            ),
        )
        // Table iteration order in the output follows config.tables order: orders, then customers.
        val config = configFor(
            listOf(
                TableSpec("public", "orders", listOf("id" to false)),
                TableSpec("public", "customers", listOf("id" to false)),
            ),
        )
        UnconfiguredColumnCheck(metadata, config).verify() shouldContainExactly listOf(
            UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "alpha", "text"),
            UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "zeta", "text"),
            UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "customers"), "email", "text"),
        )
    }

    @Test
    fun `exclude=true counts as configured (no extra finding for excluded columns)`() {
        val metadata = DbMetadata(
            listOf(
                tableMetadata(
                    "public", "users",
                    listOf("id" to "integer", "password_hash" to "text"),
                ),
            ),
        )
        // password_hash is in the config as exclude:true; the DB still has it, but it must NOT
        // surface as ExtraColumn (the column is "configured" — just suppressed at emit).
        val config = configFor(
            listOf(TableSpec("public", "users", listOf("id" to false, "password_hash" to true))),
        )
        UnconfiguredColumnCheck(metadata, config).verify().shouldBeEmpty()
    }

    @Test
    fun `skips tables absent from db metadata`() {
        // public.missing is in config but has no metadata snapshot. That's a ColumnVerification
        // concern (tableMissing); this check must not flag every column of the missing table as extra.
        val metadata = DbMetadata(
            listOf(tableMetadata("public", "orders", listOf("id" to "integer", "extra" to "text"))),
        )
        val config = configFor(
            listOf(
                TableSpec("public", "orders", listOf("id" to false)),
                TableSpec("public", "missing", listOf("id" to false)),
            ),
        )
        UnconfiguredColumnCheck(metadata, config).verify() shouldContainExactly listOf(
            UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "extra", "text"),
        )
    }

    @Test
    fun `skips db tables not in config (only configured tables produce rows)`() {
        // DbMetadata may defensively contain tables outside the configured set; they must not surface.
        val metadata = DbMetadata(
            listOf(
                tableMetadata("public", "orders", listOf("id" to "integer")),
                tableMetadata("public", "audit_log", listOf("id" to "integer", "event" to "text")),
            ),
        )
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false))),
        )
        UnconfiguredColumnCheck(metadata, config).verify().shouldBeEmpty()
    }
}
