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

package com.snowplowanalytics.cdc.validate

import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import com.snowplowanalytics.cdc.preflight.ColumnVerification
import com.snowplowanalytics.cdc.preflight.ReplicaIdentityCheck
import com.snowplowanalytics.cdc.preflight.TransformTypeCheck
import com.snowplowanalytics.cdc.preflight.UnconfiguredColumnCheck
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

class DriftReportFormatterTest {

    /**
     * Spec for a single table in the YAML rendered by [configFor].
     * Each column entry is `(name, exclude)` — exclude=true emits the `{ exclude: true }` form.
     */
    private data class TableSpec(
        val schema: String,
        val name: String,
        val columns: List<Pair<String, Boolean>>,
    )

    // Builds a Config by writing YAML to a tempfile and parsing through ConfigLoader — same production code path used by Main.runService.
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

    private fun col(name: String, ord: Int, dataType: String = "text"): DbColumn =
        DbColumn(name, ord, isNullable = false, dataType = dataType, udtName = dataType, enumLabels = null)

    private fun tableMd(
        schema: String,
        name: String,
        columns: List<Pair<String, String>>,
        identity: ReplicaIdentity = ReplicaIdentity.FULL,
    ): TableMetadata =
        TableMetadata(
            TableKey(schema, name),
            identity,
            columns.mapIndexed { i, (n, t) -> col(n, i + 1, t) },
        )

    private fun emptyReport() = DriftReport(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `clean report — every table OK plus footer`() {
        val config = configFor(
            listOf(
                TableSpec("public", "customers", listOf("id" to false, "email" to false)),
                TableSpec("public", "orders", listOf("id" to false, "user_id" to false, "amount" to false, "status" to false)),
            ),
        )
        val metadata = DbMetadata(
            listOf(
                tableMd("public", "customers", listOf("id" to "integer", "email" to "text")),
                tableMd(
                    "public", "orders",
                    listOf("id" to "integer", "user_id" to "integer", "amount" to "integer", "status" to "text"),
                ),
            ),
        )
        val out = DriftReportFormatter.format(emptyReport(), config, metadata)
        out shouldBe """
            [OK]    public.customers — 2 columns, REPLICA IDENTITY FULL
            [OK]    public.orders — 4 columns, REPLICA IDENTITY FULL
            ---
            validate: 0 errors, 0 warnings across 2 tables
        """.trimIndent()
    }

    @Test
    fun `single missing column produces ERROR with did-you-mean hint`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "user_email" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "email" to "text"))),
        )
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(
                    table = TableKey("public", "orders"),
                    column = "user_email",
                    tableMissing = false,
                    suggestion = "email",
                ),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [ERROR] public.orders.user_email — configured column not found in database (did you mean: email?)
            ---
            validate: 1 errors, 0 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `single missing column with no suggestion`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "user_email" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer"))),
        )
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(
                    table = TableKey("public", "orders"),
                    column = "user_email",
                    tableMissing = false,
                    suggestion = null,
                ),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [ERROR] public.orders.user_email — configured column not found in database
            ---
            validate: 1 errors, 0 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `tableMissing produces one table-level ERROR line`() {
        val config = configFor(
            listOf(TableSpec("public", "invoices", listOf("id" to false, "amount" to false, "status" to false))),
        )
        val metadata = DbMetadata(emptyList())
        val key = TableKey("public", "invoices")
        // ColumnVerification emits one MissingColumn per configured column when the table is missing —
        // the formatter must coalesce these into a single table-level line.
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(key, "id", tableMissing = true, suggestion = null),
                ColumnVerification.MissingColumn(key, "amount", tableMissing = true, suggestion = null),
                ColumnVerification.MissingColumn(key, "status", tableMissing = true, suggestion = null),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [ERROR] public.invoices — table not found in database
            ---
            validate: 1 errors, 0 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `type mismatch produces ERROR with db type and transform name`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "amount_cents" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "amount_cents" to "integer"))),
        )
        val report = DriftReport(
            missingColumns = emptyList(),
            typeMismatches = listOf(
                TransformTypeCheck.TypeMismatch(
                    table = TableKey("public", "orders"),
                    column = "amount_cents",
                    dbType = "integer",
                    transformName = "lowercase",
                ),
            ),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [ERROR] public.orders.amount_cents — transform 'lowercase' requires a string column; db type is "integer"
            ---
            validate: 1 errors, 0 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `REPLICA IDENTITY default produces WARN with remediation hint`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer"), ReplicaIdentity.DEFAULT)),
        )
        val report = DriftReport(
            missingColumns = emptyList(),
            typeMismatches = emptyList(),
            replicaIdentityFindings = listOf(
                ReplicaIdentityCheck.Finding(schema = "public", table = "orders", identity = "default"),
            ),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [WARN]  public.orders — REPLICA IDENTITY 'default' (not FULL); UPDATE/DELETE 'before' carries only the primary key. To fix: ALTER TABLE public.orders REPLICA IDENTITY FULL;
            ---
            validate: 0 errors, 1 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `unconfigured-but-present produces WARN`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "shipping_country" to "text"))),
        )
        val report = DriftReport(
            missingColumns = emptyList(),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "shipping_country", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out shouldBe """
            [WARN]  public.orders.shipping_country — present in database, not in config
            ---
            validate: 0 errors, 1 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `OK line is suppressed on any table with any finding`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "extra" to "text"))),
        )
        val report = DriftReport(
            missingColumns = emptyList(),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "extra", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        out.lines().count { it.startsWith("[OK]") } shouldBe 0
        out shouldEndWith "validate: 0 errors, 1 warnings across 1 tables"
    }

    @Test
    fun `mixed severities — order within a table is ERROR then WARN`() {
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "user_email" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "shipping_country" to "text"))),
        )
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(
                    table = TableKey("public", "orders"),
                    column = "user_email",
                    tableMissing = false,
                    suggestion = null,
                ),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "shipping_country", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        val lines = out.lines()
        val errorIdx = lines.indexOfFirst { it.startsWith("[ERROR]") }
        val warnIdx = lines.indexOfFirst { it.startsWith("[WARN]") }
        (errorIdx >= 0 && warnIdx >= 0 && errorIdx < warnIdx) shouldBe true
    }

    @Test
    fun `tables sorted alphabetically by schema dot table`() {
        val config = configFor(
            listOf(
                TableSpec("public", "zebras", listOf("id" to false)),
                TableSpec("public", "apples", listOf("id" to false)),
            ),
        )
        val metadata = DbMetadata(
            listOf(
                tableMd("public", "zebras", listOf("id" to "integer", "stripes" to "text")),
                tableMd("public", "apples", listOf("id" to "integer", "variety" to "text")),
            ),
        )
        val report = DriftReport(
            missingColumns = emptyList(),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "zebras"), "stripes", "text"),
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "apples"), "variety", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        val applesIdx = out.indexOf("public.apples")
        val zebrasIdx = out.indexOf("public.zebras")
        (applesIdx in 0 until zebrasIdx) shouldBe true
    }

    @Test
    fun `column-level findings within a table sorted alphabetically by source column name`() {
        val config = configFor(
            listOf(
                TableSpec(
                    "public",
                    "orders",
                    listOf("id" to false, "user_email" to false, "created_at" to false, "amount" to false),
                ),
            ),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer"))),
        )
        val key = TableKey("public", "orders")
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(key, "user_email", tableMissing = false, suggestion = null),
                ColumnVerification.MissingColumn(key, "created_at", tableMissing = false, suggestion = null),
                ColumnVerification.MissingColumn(key, "amount", tableMissing = false, suggestion = null),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        val amountIdx = out.indexOf("public.orders.amount ")
        val createdIdx = out.indexOf("public.orders.created_at ")
        val emailIdx = out.indexOf("public.orders.user_email ")
        (amountIdx in 0 until createdIdx && createdIdx < emailIdx) shouldBe true
    }

    @Test
    fun `prefix-column alignment`() {
        // Reuse the mixed-severity fixture from case 9.
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false, "user_email" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer", "shipping_country" to "text"))),
        )
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(
                    table = TableKey("public", "orders"),
                    column = "user_email",
                    tableMissing = false,
                    suggestion = null,
                ),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "orders"), "shipping_country", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        val nonFooter = out.lines().filterNot { it.startsWith("validate:") || it == "---" || it.isBlank() }
        nonFooter.forEach { line ->
            val prefix = line.substring(0, 8)
            (prefix == "[OK]    " || prefix == "[WARN]  " || prefix == "[ERROR] ") shouldBe true
        }
    }

    @Test
    fun `footer always present and counts are correct`() {
        // 4 configured tables: 2 produce ERRORs, 1 produces WARNs, 1 is clean.
        val config = configFor(
            listOf(
                TableSpec("public", "a_table", listOf("id" to false)),
                TableSpec("public", "b_table", listOf("id" to false, "missing_col" to false)),
                TableSpec("public", "c_table", listOf("id" to false)),
                TableSpec("public", "d_table", listOf("id" to false)),
            ),
        )
        val metadata = DbMetadata(
            listOf(
                tableMd("public", "a_table", listOf("id" to "integer")),
                tableMd("public", "b_table", listOf("id" to "integer")),
                tableMd("public", "c_table", listOf("id" to "integer", "extra1" to "text", "extra2" to "text")),
                tableMd("public", "d_table", listOf("id" to "integer", "amount" to "integer"), ReplicaIdentity.DEFAULT),
            ),
        )
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(TableKey("public", "b_table"), "missing_col", false, null),
            ),
            typeMismatches = listOf(
                TransformTypeCheck.TypeMismatch(TableKey("public", "d_table"), "amount", "integer", "lowercase"),
            ),
            replicaIdentityFindings = listOf(
                ReplicaIdentityCheck.Finding("public", "d_table", "default"),
            ),
            extraColumns = listOf(
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "c_table"), "extra1", "text"),
                UnconfiguredColumnCheck.ExtraColumn(TableKey("public", "c_table"), "extra2", "text"),
            ),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        val lines = out.lines()
        lines.last() shouldBe "validate: 2 errors, 3 warnings across 4 tables"
    }

    @Test
    fun `tableMissing finding does not double-count the table in the footer across K tables`() {
        // 2 tables configured; one has a tableMissing (3 per-column entries collapse to 1 ERROR).
        val config = configFor(
            listOf(
                TableSpec("public", "present", listOf("id" to false)),
                TableSpec("public", "invoices", listOf("id" to false, "amount" to false, "status" to false)),
            ),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "present", listOf("id" to "integer"))),
        )
        val key = TableKey("public", "invoices")
        val report = DriftReport(
            missingColumns = listOf(
                ColumnVerification.MissingColumn(key, "id", tableMissing = true, suggestion = null),
                ColumnVerification.MissingColumn(key, "amount", tableMissing = true, suggestion = null),
                ColumnVerification.MissingColumn(key, "status", tableMissing = true, suggestion = null),
            ),
            typeMismatches = emptyList(),
            replicaIdentityFindings = emptyList(),
            extraColumns = emptyList(),
        )
        val out = DriftReportFormatter.format(report, config, metadata)
        // K = configured tables = 2 (not 3 from collapsed missing columns, not 1 from "tables w findings")
        out.lines().last() shouldBe "validate: 1 errors, 0 warnings across 2 tables"
    }

    @Test
    fun `OK line uses the configured non-exclude column count`() {
        // 3 configured columns, one with exclude:true → OK line should read "2 columns".
        val config = configFor(
            listOf(
                TableSpec(
                    "public",
                    "orders",
                    // ColumnsDeserializer special-cases exclude:true entries.
                    columns = listOf("id" to false, "email" to false, "password_hash" to true),
                ),
            ),
        )
        val metadata = DbMetadata(
            listOf(
                tableMd(
                    "public", "orders",
                    listOf("id" to "integer", "email" to "text", "password_hash" to "text"),
                ),
            ),
        )
        val out = DriftReportFormatter.format(emptyReport(), config, metadata)
        out shouldBe """
            [OK]    public.orders — 2 columns, REPLICA IDENTITY FULL
            ---
            validate: 0 errors, 0 warnings across 1 tables
        """.trimIndent()
    }

    @Test
    fun `OK line REPLICA IDENTITY value reflects DbMetadata`() {
        // Synthetic fixture: NOTHING replica identity with zero findings (unusual in production —
        // the runtime ReplicaIdentityCheck would have emitted a WARN — but the formatter contract
        // is "read the value from DbMetadata", not "always print FULL".
        val config = configFor(
            listOf(TableSpec("public", "orders", listOf("id" to false))),
        )
        val metadata = DbMetadata(
            listOf(tableMd("public", "orders", listOf("id" to "integer"), ReplicaIdentity.NOTHING)),
        )
        val out = DriftReportFormatter.format(emptyReport(), config, metadata)
        out shouldBe """
            [OK]    public.orders — 1 columns, REPLICA IDENTITY NOTHING
            ---
            validate: 0 errors, 0 warnings across 1 tables
        """.trimIndent()
    }
}
