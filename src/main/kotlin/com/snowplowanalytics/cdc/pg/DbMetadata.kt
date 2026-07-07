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

package com.snowplowanalytics.cdc.pg

import com.snowplowanalytics.cdc.config.TableKey

/**
 * Typed snapshot of the relevant Postgres catalog state for the configured tables. Built once at
 * startup by [PgIntrospector.introspect]; consumed by [com.snowplowanalytics.cdc.preflight.ColumnVerification]
 * in Slice 5 and by Slices 10 (`scaffold`) and 11 (`validate`).
 */
data class DbMetadata(val tables: List<TableMetadata>) {
    fun forTable(key: TableKey): TableMetadata? = tables.firstOrNull { it.key == key }
}

data class TableMetadata(
    val key: TableKey,
    val replicaIdentity: ReplicaIdentity,
    val columns: List<DbColumn>,
    /** Primary-key column names in index order (feeds the deterministic event_id). Empty if no PK. */
    val primaryKeyColumns: List<String> = emptyList(),
    /** Candidate unique indexes (non-PK), each a column-name list in index order. Used for the PK-less TODO hint. */
    val uniqueIndexes: List<List<String>> = emptyList(),
)

data class DbColumn(
    val name: String,
    val ordinalPosition: Int,
    val isNullable: Boolean,
    /** `information_schema.columns.data_type` — e.g. "integer", "text", "USER-DEFINED". */
    val dataType: String,
    /** `pg_type.typname` — e.g. "int4", "text", "order_status_enum". */
    val udtName: String,
    /** Ordered enum labels when [udtName] is a user-defined enum type; null otherwise. */
    val enumLabels: List<String>?,
    /** information_schema.columns.character_maximum_length; null when not applicable (e.g. text, integer). */
    val characterMaximumLength: Int? = null,
)

/**
 * Translation of `pg_class.relreplident`:
 *   'f' -> FULL    — whole row image in WAL
 *   'd' -> DEFAULT — primary key only
 *   'n' -> NOTHING — nothing
 *   'i' -> INDEX   — specific unique index columns
 *   anything else -> UNKNOWN (preserved rather than silently mapped)
 */
enum class ReplicaIdentity { FULL, DEFAULT, NOTHING, INDEX, UNKNOWN }
