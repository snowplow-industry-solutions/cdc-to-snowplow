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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PgIntrospectorAssemblyTest {

    // These synthetic-row data classes mirror the projections of the three queries in PgIntrospector.
    // Tests construct lists of these and feed them to the pure assembleDbMetadata helper.

    @Test
    fun `single-table single-column produces one TableMetadata`() {
        val metadata = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "orders", "id", 1, isNullable = false, dataType = "integer", udtName = "int4"),
            ),
            identityRows = listOf(
                PgIntrospector.IdentityRow("public", "orders", relreplident = "f"),
            ),
            enumRows = emptyList(),
        )

        metadata.tables shouldHaveSize 1
        val t = metadata.tables.single()
        t.key shouldBe TableKey("public", "orders")
        t.replicaIdentity shouldBe ReplicaIdentity.FULL
        t.columns shouldBe listOf(
            DbColumn("id", 1, isNullable = false, dataType = "integer", udtName = "int4", enumLabels = null),
        )
    }

    @Test
    fun `replica identity letters map to the enum`() {
        val tests = listOf("f" to ReplicaIdentity.FULL, "d" to ReplicaIdentity.DEFAULT,
                           "n" to ReplicaIdentity.NOTHING, "i" to ReplicaIdentity.INDEX,
                           "?" to ReplicaIdentity.UNKNOWN)
        for ((letter, expected) in tests) {
            val md = PgIntrospector.assembleDbMetadata(
                columnRows = listOf(PgIntrospector.ColumnRow("public", "t", "c", 1, false, "integer", "int4")),
                identityRows = listOf(PgIntrospector.IdentityRow("public", "t", letter)),
                enumRows = emptyList(),
            )
            md.tables.single().replicaIdentity shouldBe expected
        }
    }

    @Test
    fun `enum labels are stitched into the matching column`() {
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "orders", "status", 1, false, "USER-DEFINED", "order_status_enum"),
            ),
            identityRows = listOf(PgIntrospector.IdentityRow("public", "orders", "f")),
            enumRows = listOf(
                PgIntrospector.EnumRow("order_status_enum", "pending", 1.0f),
                PgIntrospector.EnumRow("order_status_enum", "shipped", 2.0f),
            ),
        )
        md.tables.single().columns.single().enumLabels shouldBe listOf("pending", "shipped")
    }

    @Test
    fun `non-enum columns get null enumLabels`() {
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "t", "id", 1, false, "integer", "int4"),
            ),
            identityRows = listOf(PgIntrospector.IdentityRow("public", "t", "f")),
            enumRows = listOf(PgIntrospector.EnumRow("other_enum", "x", 1.0f)),
        )
        md.tables.single().columns.single().enumLabels shouldBe null
    }

    @Test
    fun `table with no identity row gets UNKNOWN replica identity`() {
        // Defensive: if the identity query returns no row for a configured table, we surface
        // it via UNKNOWN rather than crashing — the column-verification step will then catch
        // a fully-absent table separately.
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "t", "id", 1, false, "integer", "int4"),
            ),
            identityRows = emptyList(),
            enumRows = emptyList(),
        )
        md.tables.single().replicaIdentity shouldBe ReplicaIdentity.UNKNOWN
    }

    @Test
    fun `columns are returned in ordinal_position order regardless of input order`() {
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "t", "z", 3, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "t", "a", 1, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "t", "m", 2, false, "integer", "int4"),
            ),
            identityRows = listOf(PgIntrospector.IdentityRow("public", "t", "f")),
            enumRows = emptyList(),
        )
        md.tables.single().columns.map { it.name } shouldBe listOf("a", "m", "z")
    }

    @Test
    fun `composite PK assembles into primaryKeyColumns in keySeq order regardless of input order`() {
        // Supply rows deliberately out of keySeq order to prove sorting is applied.
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "orders", "tenant_id", 1, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "orders", "order_id",  2, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "orders", "line_no",   3, false, "integer", "int4"),
            ),
            identityRows = listOf(PgIntrospector.IdentityRow("public", "orders", "d")),
            enumRows = emptyList(),
            pkRows = listOf(
                PgIntrospector.PkRow(schema = "public", table = "orders", column = "line_no",   keySeq = 3),
                PgIntrospector.PkRow(schema = "public", table = "orders", column = "tenant_id", keySeq = 1),
                PgIntrospector.PkRow(schema = "public", table = "orders", column = "order_id",  keySeq = 2),
            ),
        )
        md.tables.single().primaryKeyColumns shouldBe listOf("tenant_id", "order_id", "line_no")
    }

    @Test
    fun `two distinct unique indexes assemble ordered by index name each inner list in keySeq order`() {
        // idx_b is supplied before idx_a in the input — output must be sorted alphabetically.
        val md = PgIntrospector.assembleDbMetadata(
            columnRows = listOf(
                PgIntrospector.ColumnRow("public", "t", "c1", 1, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "t", "c2", 2, false, "integer", "int4"),
                PgIntrospector.ColumnRow("public", "t", "c3", 3, false, "integer", "int4"),
            ),
            identityRows = listOf(PgIntrospector.IdentityRow("public", "t", "d")),
            enumRows = emptyList(),
            uniqueRows = listOf(
                // idx_b comes first in the input
                PgIntrospector.UniqueIndexRow(schema = "public", table = "t", indexName = "idx_b", column = "c2", keySeq = 2),
                PgIntrospector.UniqueIndexRow(schema = "public", table = "t", indexName = "idx_b", column = "c1", keySeq = 1),
                // idx_a comes second
                PgIntrospector.UniqueIndexRow(schema = "public", table = "t", indexName = "idx_a", column = "c3", keySeq = 1),
            ),
        )
        val uniqueIndexes = md.tables.single().uniqueIndexes
        uniqueIndexes shouldHaveSize 2
        // idx_a sorts before idx_b alphabetically
        uniqueIndexes[0] shouldBe listOf("c3")
        uniqueIndexes[1] shouldBe listOf("c1", "c2")
    }
}
