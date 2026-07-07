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
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbMetadata

/**
 * Cross-references [Config] tables and columns against the live [DbMetadata] snapshot. Reports
 * each configured column that is absent from the database, including `exclude: true` columns
 * (per the spec — exclude is an explicit intent statement about a column that exists).
 *
 * Empty result = pass. The caller (EngineRunner) is responsible for translating a non-empty
 * result into a [StartupException].
 */
class ColumnVerification(
    private val metadata: DbMetadata,
    private val config: Config,
) {

    /**
     * One missing-column finding.
     *
     * - [table] is always populated.
     * - [column] is the configured column name (source-side).
     * - [tableMissing] is true when the entire table is absent from the DB — in that case the
     *   verifier emits one entry per configured column plus this flag so the caller can render
     *   a clear table-level message.
     * - [suggestion] is a Levenshtein-distance ≤ 2 candidate from the DB's column set, or null
     *   when no candidate qualifies (or the table itself is missing).
     */
    data class MissingColumn(
        val table: TableKey,
        val column: String,
        val tableMissing: Boolean,
        val suggestion: String?,
    )

    fun verify(): List<MissingColumn> {
        val misses = mutableListOf<MissingColumn>()
        for (table in config.tables) {
            val key = TableKey(table.schema, table.name)
            val tableMetadata = metadata.forTable(key)
            val dbColumnNames = tableMetadata?.columns?.map { it.name }?.toSet() ?: emptySet()
            val tableMissing = tableMetadata == null
            for (spec in table.columns) {
                if (spec.name !in dbColumnNames) {
                    misses += MissingColumn(
                        table = key,
                        column = spec.name,
                        tableMissing = tableMissing,
                        suggestion = if (tableMissing) null else closestCandidate(spec.name, dbColumnNames),
                    )
                }
            }
        }
        return misses
    }

    private fun closestCandidate(needle: String, haystack: Set<String>): String? =
        haystack
            .map { it to levenshtein(needle, it) }
            .filter { it.second <= MAX_HINT_DISTANCE }
            .minByOrNull { it.second }
            ?.first

    // Classic O(n*m) DP implementation; fine here — column names are short.
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            for (k in prev.indices) prev[k] = curr[k]
        }
        return prev[b.length]
    }

    companion object {
        // Catches typical 1-2 character typos without surfacing unrelated names.
        private const val MAX_HINT_DISTANCE = 2

        /**
         * Pre-formatted multi-line message for a non-empty miss list. Groups entries by table
         * and renders one line per miss; appends "did you mean: X?" when a suggestion exists.
         */
        fun formatMessage(misses: List<MissingColumn>): String {
            val byTable = misses.groupBy { it.table }
            val sb = StringBuilder("Configured columns missing from the live database:\n")
            for ((table, rows) in byTable) {
                // If any row for this table has tableMissing, render the table-level message instead
                // of per-column lines — the columns wouldn't be findable anyway.
                if (rows.any { it.tableMissing }) {
                    sb.append("  $table — table not found\n")
                    continue
                }
                for (row in rows) {
                    val hint = row.suggestion?.let { "  (did you mean: $it?)" } ?: ""
                    sb.append("  $table.${row.column}$hint\n")
                }
            }
            return sb.toString().trimEnd()
        }
    }
}
