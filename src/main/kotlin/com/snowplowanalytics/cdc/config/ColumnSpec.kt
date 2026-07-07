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

package com.snowplowanalytics.cdc.config

import com.snowplowanalytics.cdc.transform.Transform

/**
 * Parsed representation of one entry in `tables[].columns`.
 * [outputName] is [rename] if provided, otherwise [name].
 * [exclude] = true means the column is still verified at startup (it must exist in the DB)
 * but is never emitted in the output payload — it is listed-but-suppressed, not skipped entirely.
 * [transforms] is the ordered list of compiled per-column transforms; empty when omitted in YAML
 * or written explicitly as `transforms: []`.
 */
data class ColumnSpec(
    val name: String,
    val rename: String? = null,
    val exclude: Boolean = false,
    val transforms: List<Transform> = emptyList(),
) {
    /** Property name this column will produce in the emitted payload. */
    val outputName: String get() = rename ?: name
}
