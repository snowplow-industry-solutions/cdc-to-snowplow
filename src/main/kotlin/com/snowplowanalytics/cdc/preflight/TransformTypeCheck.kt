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
 * Cross-references each configured transform against the live [DbMetadata] snapshot and reports
 * any transform that targets a column whose Postgres `data_type` is not in [STRING_TYPES].
 *
 * Columns that are missing from [DbMetadata] are skipped here — that's [ColumnVerification]'s job,
 * which runs first in the EngineRunner preflight chain. Columns without transforms are skipped
 * trivially.
 *
 * Empty result = pass. The caller (EngineRunner) translates a non-empty result into a
 * [StartupException] via [formatMessage].
 */
class TransformTypeCheck(
    private val metadata: DbMetadata,
    private val config: Config,
) {

    /**
     * One findings entry. One per `(column, transform)` pair targeting a non-string column — so a
     * column with two transforms produces two findings, which keeps the rendered error list as
     * specific as possible for the operator.
     */
    data class TypeMismatch(
        val table: TableKey,
        val column: String,
        /** `information_schema.columns.data_type` as seen at startup. */
        val dbType: String,
        /** Lowercase transform keyword — `lowercase` / `regex_extract` / etc. */
        val transformName: String,
    )

    fun verify(): List<TypeMismatch> {
        val misses = mutableListOf<TypeMismatch>()
        for (table in config.tables) {
            val key = TableKey(table.schema, table.name)
            val tableMd = metadata.forTable(key) ?: continue
            val dbColByName = tableMd.columns.associateBy { it.name }
            for (spec in table.columns) {
                if (spec.transforms.isEmpty()) continue
                val dbCol = dbColByName[spec.name] ?: continue
                if (dbCol.dataType in STRING_TYPES) continue
                for (t in spec.transforms) {
                    misses += TypeMismatch(
                        table = key,
                        column = spec.name,
                        dbType = dbCol.dataType,
                        transformName = t.name,
                    )
                }
            }
        }
        return misses
    }

    companion object {
        internal val STRING_TYPES = setOf("text", "character varying", "character")

        /**
         * Pre-formatted multi-line message grouping mismatches by table, one line per
         * `(column, transform)` pair. The caller wraps the return value in a [StartupException].
         */
        fun formatMessage(misses: List<TypeMismatch>): String {
            // groupBy + flatten clusters rows per table even when callers pass interleaved
            // findings — `verify()` already produces table-clustered output, so this is a
            // defensive shape rather than a behaviour the production caller relies on.
            val byTable = misses.groupBy { it.table }
            val sb = StringBuilder("Configured transforms target non-string columns:\n")
            for (row in byTable.values.flatten()) {
                sb.append("  ${row.table}.${row.column} — ")
                sb.append("db type \"${row.dbType}\"; ")
                sb.append("transform \"${row.transformName}\" requires a string column ")
                sb.append("(text / character varying / character)\n")
            }
            return sb.toString().trimEnd()
        }
    }
}
