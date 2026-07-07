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

package com.snowplowanalytics.cdc.transform

import com.snowplowanalytics.cdc.config.TableConfig

/**
 * Projects a Debezium `before`/`after` row map into the emitted payload map for a single table.
 *
 * Pre-computes a per-table plan: an ordered list of `(sourceName, outputName)` pairs for every
 * non-excluded column, in YAML declaration order. At runtime [project] iterates the plan and
 * reads from the input row by source name — never the other way around. That direction matters:
 * a column listed in config but absent from the row (because the DB column was dropped after
 * startup) yields an explicit `null` entry instead of being silently omitted.
 *
 * Stateless after construction.
 */
class ColumnProjector(tableConfig: TableConfig) {

    private val plan: List<Pair<String, String>> = tableConfig.columns
        .filter { !it.exclude }
        .map { it.name to it.outputName }

    /**
     * @param row the raw Debezium row map (e.g. `before` or `after`). Null inputs return null
     *   (preserves the existing assembler's convention that `before` on an INSERT is null).
     * @return a LinkedHashMap in plan order, with `null` for any planned column absent from [row].
     */
    fun project(row: Map<String, Any?>?): Map<String, Any?>? {
        if (row == null) return null
        val out = LinkedHashMap<String, Any?>(plan.size)
        for ((src, dst) in plan) {
            out[dst] = row[src]  // returns null when the key is absent — intentional
        }
        return out
    }
}
