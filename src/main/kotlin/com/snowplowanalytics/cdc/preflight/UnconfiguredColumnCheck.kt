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
 * Cross-references the live [DbMetadata] snapshot against [Config] and reports each DB column
 * that has no corresponding entry in the per-table `columns:` list. Returns an empty list when
 * every DB column is accounted for.
 *
 * `exclude: true` entries count as configured: they appear in `table.columns` (their emission is
 * suppressed elsewhere), so an excluded column produces no [ExtraColumn].
 *
 * **Not invoked by [com.snowplowanalytics.cdc.engine.EngineRunner.start].** The runtime path uses
 * the configured `columns:` list as a whitelist — unconfigured DB columns are silently dropped
 * from the emitted payload by design (US #24). This check exists to surface those columns at
 * *pre-deploy* time via the `validate` subcommand, where the operator can choose to add them to
 * config (with `exclude: true` if they're sensitive) or accept the drift knowingly.
 */
class UnconfiguredColumnCheck(
    private val metadata: DbMetadata,
    private val config: Config,
) {

    /**
     * One DB-column-not-in-config finding.
     *
     * - [table] is always populated and matches a configured table.
     * - [column] is the DB column name.
     * - [dbType] is the `information_schema.columns.data_type` of the column (e.g. "text",
     *   "integer", "USER-DEFINED").
     */
    data class ExtraColumn(val table: TableKey, val column: String, val dbType: String)

    fun verify(): List<ExtraColumn> {
        val out = mutableListOf<ExtraColumn>()
        for (table in config.tables) {
            val key = TableKey(table.schema, table.name)
            val tm = metadata.forTable(key) ?: continue
            val configuredNames = table.columns.map { it.name }.toSet()
            val extras = tm.columns
                .filter { it.name !in configuredNames }
                .sortedBy { it.name }
            for (col in extras) {
                out += ExtraColumn(key, col.name, col.dataType)
            }
        }
        return out
    }
}
