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
import java.sql.Connection

class PgIntrospector(private val conn: Connection) {

    internal data class ColumnRow(
        val schema: String,
        val table: String,
        val name: String,
        val ordinalPosition: Int,
        val isNullable: Boolean,
        val dataType: String,
        val udtName: String,
        val characterMaximumLength: Int? = null,
    )

    internal data class IdentityRow(
        val schema: String,
        val table: String,
        /** Raw `pg_class.relreplident` character ('f','d','n','i' or unrecognised). */
        val relreplident: String,
    )

    internal data class EnumRow(
        val enumTypeName: String,
        val label: String,
        /** pg_enum.enumsortorder — float4 — caller uses this to order labels. */
        val sortOrder: Float,
    )

    internal data class PkRow(
        val schema: String,
        val table: String,
        val column: String,
        /** Position of the column within the PK (1-based), from the index key order. */
        val keySeq: Int,
    )

    internal data class UniqueIndexRow(
        val schema: String,
        val table: String,
        val indexName: String,
        val column: String,
        val keySeq: Int,
    )

    fun introspect(tables: List<TableKey>): DbMetadata {
        if (tables.isEmpty()) return DbMetadata(emptyList())
        val columnRows = queryColumns(tables)
        val identityRows = queryIdentities(tables)
        val enumTypes = columnRows.filter { it.dataType == "USER-DEFINED" }.map { it.udtName }.toSet()
        val enumRows = if (enumTypes.isEmpty()) emptyList() else queryEnums(enumTypes)
        val pkRows = queryPrimaryKeys(tables)
        val uniqueRows = queryUniqueIndexes(tables)
        return assembleDbMetadata(columnRows, identityRows, enumRows, pkRows, uniqueRows)
    }

    private fun queryColumns(tables: List<TableKey>): List<ColumnRow> {
        val placeholders = tables.joinToString(",") { "(?,?)" }
        val sql = """
            SELECT table_schema, table_name, column_name, ordinal_position,
                   is_nullable, data_type, udt_name, character_maximum_length
            FROM information_schema.columns
            WHERE (table_schema, table_name) IN ($placeholders)
            ORDER BY table_schema, table_name, ordinal_position
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            tables.forEachIndexed { i, t ->
                ps.setString(i * 2 + 1, t.schema)
                ps.setString(i * 2 + 2, t.name)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<ColumnRow>()
                while (rs.next()) {
                    out += ColumnRow(
                        schema = rs.getString(1),
                        table = rs.getString(2),
                        name = rs.getString(3),
                        ordinalPosition = rs.getInt(4),
                        isNullable = rs.getString(5).equals("YES", ignoreCase = true),
                        dataType = rs.getString(6),
                        udtName = rs.getString(7),
                        characterMaximumLength = rs.getObject(8) as Int?,
                    )
                }
                return out
            }
        }
    }

    private fun queryIdentities(tables: List<TableKey>): List<IdentityRow> {
        val placeholders = tables.joinToString(",") { "(?,?)" }
        val sql = """
            SELECT n.nspname, c.relname, c.relreplident::text
            FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE (n.nspname, c.relname) IN ($placeholders)
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            tables.forEachIndexed { i, t ->
                ps.setString(i * 2 + 1, t.schema)
                ps.setString(i * 2 + 2, t.name)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<IdentityRow>()
                while (rs.next()) {
                    out += IdentityRow(
                        schema = rs.getString(1),
                        table = rs.getString(2),
                        relreplident = rs.getString(3),
                    )
                }
                return out
            }
        }
    }

    private fun queryEnums(enumTypeNames: Set<String>): List<EnumRow> {
        val placeholders = enumTypeNames.joinToString(",") { "?" }
        val sql = """
            SELECT t.typname, e.enumlabel, e.enumsortorder
            FROM pg_enum e
            JOIN pg_type t ON e.enumtypid = t.oid
            WHERE t.typname IN ($placeholders)
            ORDER BY t.typname, e.enumsortorder
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            enumTypeNames.forEachIndexed { i, name -> ps.setString(i + 1, name) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<EnumRow>()
                while (rs.next()) {
                    out += EnumRow(
                        enumTypeName = rs.getString(1),
                        label = rs.getString(2),
                        sortOrder = rs.getFloat(3),
                    )
                }
                return out
            }
        }
    }

    private fun queryPrimaryKeys(tables: List<TableKey>): List<PkRow> {
        val placeholders = tables.joinToString(",") { "(?,?)" }
        val sql = """
            SELECT n.nspname, c.relname, a.attname, k.ord AS key_seq
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            WHERE i.indisprimary
              AND (n.nspname, c.relname) IN ($placeholders)
            ORDER BY n.nspname, c.relname, k.ord
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            tables.forEachIndexed { idx, t ->
                ps.setString(idx * 2 + 1, t.schema)
                ps.setString(idx * 2 + 2, t.name)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<PkRow>()
                while (rs.next()) out += PkRow(
                        schema = rs.getString(1),
                        table = rs.getString(2),
                        column = rs.getString(3),
                        keySeq = rs.getInt(4),
                    )
                return out
            }
        }
    }

    private fun queryUniqueIndexes(tables: List<TableKey>): List<UniqueIndexRow> {
        val placeholders = tables.joinToString(",") { "(?,?)" }
        val sql = """
            SELECT n.nspname, c.relname, ic.relname AS index_name, a.attname, k.ord AS key_seq
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indrelid
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            WHERE i.indisunique AND NOT i.indisprimary
              AND k.attnum <> 0
              AND (n.nspname, c.relname) IN ($placeholders)
            ORDER BY n.nspname, c.relname, ic.relname, k.ord
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            tables.forEachIndexed { idx, t ->
                ps.setString(idx * 2 + 1, t.schema)
                ps.setString(idx * 2 + 2, t.name)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<UniqueIndexRow>()
                while (rs.next()) out += UniqueIndexRow(
                        schema = rs.getString(1),
                        table = rs.getString(2),
                        indexName = rs.getString(3),
                        column = rs.getString(4),
                        keySeq = rs.getInt(5),
                    )
                return out
            }
        }
    }

    companion object {
        internal fun assembleDbMetadata(
            columnRows: List<ColumnRow>,
            identityRows: List<IdentityRow>,
            enumRows: List<EnumRow>,
            pkRows: List<PkRow> = emptyList(),
            uniqueRows: List<UniqueIndexRow> = emptyList(),
        ): DbMetadata {
            val enumLabelsByType: Map<String, List<String>> = enumRows
                .groupBy { it.enumTypeName }
                .mapValues { (_, rows) -> rows.sortedBy { it.sortOrder }.map { it.label } }

            val identityByKey: Map<TableKey, String> = identityRows
                .associate { TableKey(it.schema, it.table) to it.relreplident }

            val columnsByKey: Map<TableKey, List<ColumnRow>> = columnRows
                .groupBy { TableKey(it.schema, it.table) }

            val pkByKey: Map<TableKey, List<String>> = pkRows
                .groupBy { TableKey(it.schema, it.table) }
                .mapValues { (_, rows) -> rows.sortedBy { it.keySeq }.map { it.column } }

            val uniqueByKey: Map<TableKey, List<List<String>>> = uniqueRows
                .groupBy { TableKey(it.schema, it.table) }
                .mapValues { (_, rows) ->
                    rows.groupBy { it.indexName }
                        .entries
                        .sortedBy { it.key }
                        .map { (_, idxRows) -> idxRows.sortedBy { it.keySeq }.map { it.column } }
                }

            val keys = (columnsByKey.keys + identityByKey.keys).toSet()

            val tables = keys.map { key ->
                val cols = (columnsByKey[key] ?: emptyList())
                    .sortedBy { it.ordinalPosition }
                    .map { row ->
                        DbColumn(
                            name = row.name,
                            ordinalPosition = row.ordinalPosition,
                            isNullable = row.isNullable,
                            dataType = row.dataType,
                            udtName = row.udtName,
                            enumLabels = if (row.dataType == "USER-DEFINED") enumLabelsByType[row.udtName]
                                         else null,
                            characterMaximumLength = row.characterMaximumLength,
                        )
                    }
                val identity = mapReplicaIdentity(identityByKey[key])
                TableMetadata(
                    key,
                    identity,
                    cols,
                    primaryKeyColumns = pkByKey[key] ?: emptyList(),
                    uniqueIndexes = uniqueByKey[key] ?: emptyList(),
                )
            }
            return DbMetadata(tables)
        }

        private fun mapReplicaIdentity(c: String?): ReplicaIdentity = when (c) {
            "f" -> ReplicaIdentity.FULL
            "d" -> ReplicaIdentity.DEFAULT
            "n" -> ReplicaIdentity.NOTHING
            "i" -> ReplicaIdentity.INDEX
            else -> ReplicaIdentity.UNKNOWN
        }
    }
}
