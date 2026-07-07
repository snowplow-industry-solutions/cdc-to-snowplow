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
 * Applies per-column transform chains to a projected payload map.
 *
 * Runs **after** [ColumnProjector] — keys in the input map are already output names (post-rename).
 * The pre-computed [chainsByOutputName] index makes per-cell lookup constant-time.
 *
 * Stateless after construction. The compiled `Pattern` instances carried by
 * [Transform.RegexExtract] and [Transform.RegexReplace] are immutable and thread-safe; the
 * `Matcher` created inside each transform's `apply` call is local to the call.
 */
class TransformPipeline(tableConfig: TableConfig) {

    private val chainsByOutputName: Map<String, List<Transform>> = tableConfig.columns
        .filter { !it.exclude && it.transforms.isNotEmpty() }
        .associate { it.outputName to it.transforms }

    /**
     * @param projected the per-table payload map already routed through [ColumnProjector]
     *   (renamed and exclude-filtered). Null inputs return null (mirrors the projector's null
     *   convention — `before` on an INSERT, `after` on a DELETE).
     * @return a new LinkedHashMap preserving [projected]'s key order, with each chained column's
     *   value passed through its transform chain.
     */
    fun apply(projected: Map<String, Any?>?): Map<String, Any?>? {
        if (projected == null) return null
        if (chainsByOutputName.isEmpty()) return projected
        val out = LinkedHashMap<String, Any?>(projected.size)
        for ((k, v) in projected) {
            val chain = chainsByOutputName[k]
            out[k] = if (chain == null) v else applyChain(v, chain, k)
        }
        return out
    }

    private fun applyChain(initial: Any?, chain: List<Transform>, columnPath: String): Any? =
        chain.fold(initial) { cell, t -> t.apply(cell, columnPath) }
}
