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
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.preflight.ColumnVerification
import com.snowplowanalytics.cdc.preflight.ReplicaIdentityCheck
import com.snowplowanalytics.cdc.preflight.TransformTypeCheck
import com.snowplowanalytics.cdc.preflight.UnconfiguredColumnCheck

/**
 * The four drift-check finding lists, packaged for rendering. Tests against this type's
 * [errorCount] and [warningCount] lock the footer arithmetic separately from line rendering.
 *
 * - [errorCount] coalesces `tableMissing` per-column entries to one error per table (matching the
 *   one rendered "table not found" line), then sums per-`(column, transform)` type mismatches.
 * - [warningCount] is a simple sum of replica-identity findings and extra-column findings.
 */
data class DriftReport(
    val missingColumns: List<ColumnVerification.MissingColumn>,
    val typeMismatches: List<TransformTypeCheck.TypeMismatch>,
    val replicaIdentityFindings: List<ReplicaIdentityCheck.Finding>,
    val extraColumns: List<UnconfiguredColumnCheck.ExtraColumn>,
) {
    val errorCount: Int =
        missingColumns.distinctBy { it.table to (if (it.tableMissing) "" else it.column) }.size +
            typeMismatches.size
    val warningCount: Int =
        replicaIdentityFindings.size + extraColumns.size
}

/**
 * Renders a [DriftReport] into the CI-diffable line-prefixed report defined by slice 11 §5.
 *
 * Output shape:
 * ```
 * [OK]    <schema>.<table> — N columns, REPLICA IDENTITY <NAME>
 * [ERROR] <schema>.<table>[.<col>] — <message>
 * [WARN]  <schema>.<table>[.<col>] — <message>
 * ---
 * validate: <E> errors, <W> warnings across <K> tables
 * ```
 *
 * Contracts (kept stable so CI greps and downstream tooling don't break):
 * - Prefix column is exactly 8 characters: `"[OK]    "`, `"[WARN]  "`, `"[ERROR] "`.
 * - Body is separated from prefix and from `<table>` with an em-dash (`—`, U+2014).
 * - Tables sorted alphabetically by `schema.name`.
 * - Within a table, ERROR section precedes WARN section. Inside ERROR, table-level lines
 *   (tableMissing) precede column-level lines; column-level lines sort by source column name.
 * - A table emits exactly one `[OK]` line iff it has zero findings of any severity.
 * - Footer "across K tables" uses `config.tables.size` — not the count of tables with findings.
 * - The `[OK]` column count is `table.columns.count { !it.exclude }`.
 * - The `[OK]` REPLICA IDENTITY value reads from [DbMetadata], not hardcoded.
 * - No trailing newline; the caller appends one if writing to stdout.
 */
object DriftReportFormatter {

    private const val PREFIX_OK = "[OK]    "
    private const val PREFIX_WARN = "[WARN]  "
    private const val PREFIX_ERROR = "[ERROR] "
    private const val EM_DASH = "—"

    fun format(report: DriftReport, config: Config, metadata: DbMetadata): String {
        // Bucket findings per table.
        val errorLinesByTable = mutableMapOf<TableKey, MutableList<String>>()
        val warnLinesByTable = mutableMapOf<TableKey, MutableList<String>>()

        // --- ERRORs ---
        // tableMissing entries: emit a single table-level line per table, regardless of how many
        // per-column entries the verifier supplied.
        val tableMissingKeys = report.missingColumns.filter { it.tableMissing }.map { it.table }.toSet()
        for (key in tableMissingKeys) {
            errorLinesByTable.getOrPut(key) { mutableListOf() }
                .add("$PREFIX_ERROR$key $EM_DASH table not found in database")
        }
        // Column-level missing entries (table is present): sorted by source column within each table.
        report.missingColumns
            .filterNot { it.tableMissing }
            .groupBy { it.table }
            .forEach { (key, rows) ->
                val sorted = rows.sortedBy { it.column }
                for (row in sorted) {
                    val hint = row.suggestion?.let { " (did you mean: $it?)" } ?: ""
                    errorLinesByTable.getOrPut(key) { mutableListOf() }
                        .add("$PREFIX_ERROR$key.${row.column} $EM_DASH configured column not found in database$hint")
                }
            }
        // Type mismatches: column-level, sorted by source column then transform name for stability.
        report.typeMismatches
            .groupBy { it.table }
            .forEach { (key, rows) ->
                val sorted = rows.sortedWith(compareBy({ it.column }, { it.transformName }))
                for (row in sorted) {
                    errorLinesByTable.getOrPut(key) { mutableListOf() }
                        .add(
                            "$PREFIX_ERROR$key.${row.column} $EM_DASH transform '${row.transformName}' " +
                                "requires a string column; db type is \"${row.dbType}\"",
                        )
                }
            }

        // --- WARNs ---
        // Replica-identity findings: table-level lines, one per finding.
        for (finding in report.replicaIdentityFindings) {
            val key = TableKey(finding.schema, finding.table)
            warnLinesByTable.getOrPut(key) { mutableListOf() }
                .add(
                    "$PREFIX_WARN$key $EM_DASH REPLICA IDENTITY '${finding.identity}' (not FULL); " +
                        "UPDATE/DELETE 'before' carries only the primary key. " +
                        "To fix: ALTER TABLE $key REPLICA IDENTITY FULL;",
                )
        }
        // Extra columns: column-level lines, sorted by column name within each table.
        report.extraColumns
            .groupBy { it.table }
            .forEach { (key, rows) ->
                val sorted = rows.sortedBy { it.column }
                for (row in sorted) {
                    warnLinesByTable.getOrPut(key) { mutableListOf() }
                        .add("$PREFIX_WARN$key.${row.column} $EM_DASH present in database, not in config")
                }
            }

        // Walk configured tables in schema.name order; emit either the OK line or the
        // bucketed ERROR-then-WARN block for each. Configured-table order is the universe of
        // tables that may appear — the footer's `K` is `config.tables.size`.
        val sb = StringBuilder()
        val orderedKeys = config.tables
            .map { TableKey(it.schema, it.name) }
            .sortedWith(compareBy({ it.schema }, { it.name }))
        for (key in orderedKeys) {
            val errs = errorLinesByTable[key].orEmpty()
            val warns = warnLinesByTable[key].orEmpty()
            if (errs.isEmpty() && warns.isEmpty()) {
                val tableConfig = config.tablesByKey[key] ?: continue
                val columnCount = tableConfig.columns.count { !it.exclude }
                val identity = metadata.forTable(key)?.replicaIdentity?.name ?: "UNKNOWN"
                sb.append("$PREFIX_OK$key $EM_DASH $columnCount columns, REPLICA IDENTITY $identity\n")
            } else {
                for (line in errs) sb.append(line).append('\n')
                for (line in warns) sb.append(line).append('\n')
            }
        }

        // Footer is unconditional. K = configured tables, not findings-bearing tables.
        sb.append("---\n")
        sb.append(
            "validate: ${report.errorCount} errors, ${report.warningCount} warnings " +
                "across ${config.tables.size} tables",
        )
        return sb.toString()
    }
}
