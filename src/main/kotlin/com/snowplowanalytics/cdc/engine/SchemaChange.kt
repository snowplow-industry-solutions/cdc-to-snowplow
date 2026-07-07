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

package com.snowplowanalytics.cdc.engine

/**
 * Value object describing a single observed transition of a source table's column fingerprint.
 * Carries everything the engine needs to emit a structured WARN line; produced by
 * [DdlChangeDetector] and consumed by the engine layer (no logging happens here).
 */
data class SchemaChange(
    /** Schema-qualified flat table identifier, e.g. `"public.orders"`. */
    val table: String,
    val previousFingerprint: String,
    val currentFingerprint: String,
    val addedColumns: List<String>,
    val removedColumns: List<String>,
) {
    /**
     * Render this change as an ordered map for structured logging. Keys appear in the order
     * documented in the slice spec; `linkedMapOf` preserves insertion order on iteration.
     */
    fun toLogMap(): Map<String, Any> = linkedMapOf(
        "event" to "source_schema_change",
        "table" to table,
        "previous_fingerprint" to previousFingerprint,
        "current_fingerprint" to currentFingerprint,
        "added_columns" to addedColumns,
        "removed_columns" to removedColumns,
    )
}
