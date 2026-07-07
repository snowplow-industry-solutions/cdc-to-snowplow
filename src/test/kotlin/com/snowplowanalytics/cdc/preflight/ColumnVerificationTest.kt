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

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class ColumnVerificationTest {

    private fun col(name: String, ord: Int) =
        DbColumn(name, ord, isNullable = false, dataType = "integer", udtName = "int4", enumLabels = null)

    private fun tableMetadata(schema: String, name: String, columns: List<String>) =
        TableMetadata(
            TableKey(schema, name),
            ReplicaIdentity.FULL,
            columns.mapIndexed { i, c -> col(c, i + 1) },
        )

    // Builds a Config by writing YAML to a tempfile and parsing it through ConfigLoader — the same production code path used by Main.runService.
    private fun configFor(tableSchema: String, tableName: String, columns: List<ColumnSpec>): Config {
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
              - name: $tableName
                schema: $tableSchema
                iglu_schema: iglu:com.example/foo/jsonschema/1-0-0
                primary_key: [${columns.first().name}]
                columns:
        """.trimIndent() + "\n" + columns.joinToString("\n") { spec ->
            when {
                spec.exclude -> "      - ${spec.name}: { exclude: true }"
                spec.rename != null -> "      - ${spec.name}: { rename: ${spec.rename} }"
                else -> "      - ${spec.name}"
            }
        }
        val tmp = Files.createTempFile("cfg", ".yaml")
        tmp.writeText(yaml)
        return ConfigLoader.load(tmp)
    }

    @Test
    fun `fully-matched config returns empty miss list`() {
        val metadata = DbMetadata(listOf(tableMetadata("public", "orders", listOf("id", "status"))))
        val config = configFor("public", "orders", listOf(ColumnSpec("id"), ColumnSpec("status")))
        ColumnVerification(metadata, config).verify().shouldBeEmpty()
    }

    @Test
    fun `missing column in db is reported`() {
        val metadata = DbMetadata(listOf(tableMetadata("public", "orders", listOf("id"))))
        val config = configFor("public", "orders", listOf(ColumnSpec("id"), ColumnSpec("status")))
        val misses = ColumnVerification(metadata, config).verify()
        misses shouldHaveSize 1
        misses[0].table shouldBe TableKey("public", "orders")
        misses[0].column shouldBe "status"
    }

    @Test
    fun `excluded column missing from db is reported`() {
        // Per the design's strict-reading policy: exclude:true is an explicit intent statement
        // and still requires the column to exist.
        val metadata = DbMetadata(listOf(tableMetadata("public", "orders", listOf("id"))))
        val config = configFor(
            "public", "orders",
            listOf(ColumnSpec("id"), ColumnSpec("password_hash", exclude = true)),
        )
        val misses = ColumnVerification(metadata, config).verify()
        misses.map { it.column } shouldContain "password_hash"
    }

    @Test
    fun `entirely absent table reports all its columns and tableMissing flag`() {
        // No metadata for public.orders at all (a synthetic "table not found" + per-column entries).
        val metadata = DbMetadata(emptyList())
        val config = configFor("public", "orders", listOf(ColumnSpec("id"), ColumnSpec("status")))
        val misses = ColumnVerification(metadata, config).verify()
        misses.map { it.column } shouldContain "id"
        misses.map { it.column } shouldContain "status"
        misses.any { it.tableMissing } shouldBe true
    }

    @Test
    fun `Levenshtein hint suggests a close match`() {
        val metadata = DbMetadata(listOf(tableMetadata("public", "orders", listOf("id", "status"))))
        val config = configFor("public", "orders", listOf(ColumnSpec("id"), ColumnSpec("statuus")))
        val misses = ColumnVerification(metadata, config).verify()
        val miss = misses.single { it.column == "statuus" }
        miss.suggestion shouldBe "status"
    }

    @Test
    fun `no Levenshtein hint when no candidate is within distance 2`() {
        val metadata = DbMetadata(listOf(tableMetadata("public", "orders", listOf("id", "status"))))
        val config = configFor("public", "orders", listOf(ColumnSpec("id"), ColumnSpec("totally_unrelated")))
        val misses = ColumnVerification(metadata, config).verify()
        misses.single { it.column == "totally_unrelated" }.suggestion shouldBe null
    }
}
