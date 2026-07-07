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

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.DebeziumConfig
import com.snowplowanalytics.cdc.config.EmitterConfig
import com.snowplowanalytics.cdc.config.OffsetStoreConfig
import com.snowplowanalytics.cdc.config.ServiceConfig
import com.snowplowanalytics.cdc.config.SnowplowConfig
import com.snowplowanalytics.cdc.config.SourceConfig
import com.snowplowanalytics.cdc.config.TableConfig
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbColumn
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.pg.TableMetadata
import com.snowplowanalytics.cdc.transform.Transform
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class TransformTypeCheckTest {

    private val ordersKey = TableKey("public", "orders")

    private fun metadataFor(vararg cols: Pair<String, String>): DbMetadata {
        val dbCols = cols.mapIndexed { i, (name, type) ->
            DbColumn(name, ordinalPosition = i + 1, isNullable = true,
                     dataType = type, udtName = name + "_udt", enumLabels = null)
        }
        return DbMetadata(listOf(TableMetadata(ordersKey, ReplicaIdentity.FULL, dbCols)))
    }

    private fun configFor(vararg columns: ColumnSpec): Config = Config(
        service = ServiceConfig(appId = "test"),
        source = SourceConfig(
            connector = "postgres", hostname = "h", port = 5432, database = "d",
            username = "u", password = "p", slotName = "s", publicationName = "pub",
        ),
        debezium = DebeziumConfig(offsetStore = OffsetStoreConfig(type = "file", filePath = "/tmp/x")),
        snowplow = SnowplowConfig(
            collectorUrl = "http://x",
            cdcSourceSchema = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
            emitter = EmitterConfig(),
        ),
        tables = listOf(
            TableConfig(
                name = "orders", schema = "public",
                igluSchema = "iglu:com.example/orders/jsonschema/1-0-0",
                primaryKey = listOf("id"),
                columns = columns.toList(),
            ),
        ),
    )

    @Test
    fun `text column with lowercase is OK`() {
        val md = metadataFor("description" to "text")
        val cfg = configFor(ColumnSpec("description", transforms = listOf(Transform.Lowercase)))
        TransformTypeCheck(md, cfg).verify().shouldBeEmpty()
    }

    @Test
    fun `character varying with trim is OK`() {
        val md = metadataFor("name" to "character varying")
        val cfg = configFor(ColumnSpec("name", transforms = listOf(Transform.Trim)))
        TransformTypeCheck(md, cfg).verify().shouldBeEmpty()
    }

    @Test
    fun `character with uppercase is OK`() {
        val md = metadataFor("code" to "character")
        val cfg = configFor(ColumnSpec("code", transforms = listOf(Transform.Uppercase)))
        TransformTypeCheck(md, cfg).verify().shouldBeEmpty()
    }

    @Test
    fun `numeric with trim is rejected`() {
        val md = metadataFor("total" to "numeric")
        val cfg = configFor(ColumnSpec("total", transforms = listOf(Transform.Trim)))
        val misses = TransformTypeCheck(md, cfg).verify()
        misses.size shouldBe 1
        misses.single().dbType shouldBe "numeric"
        misses.single().transformName shouldBe "trim"
    }

    @Test
    fun `uuid with regex_extract is rejected`() {
        val md = metadataFor("id" to "uuid")
        val cfg = configFor(ColumnSpec("id",
            transforms = listOf(Transform.RegexExtract(Pattern.compile("."), 0))))
        TransformTypeCheck(md, cfg).verify().size shouldBe 1
    }

    @Test
    fun `timestamp with lowercase is rejected`() {
        val md = metadataFor("created_at" to "timestamp without time zone")
        val cfg = configFor(ColumnSpec("created_at", transforms = listOf(Transform.Lowercase)))
        TransformTypeCheck(md, cfg).verify().size shouldBe 1
    }

    @Test
    fun `enum (USER-DEFINED) with lowercase is rejected`() {
        val md = metadataFor("status" to "USER-DEFINED")
        val cfg = configFor(ColumnSpec("status", transforms = listOf(Transform.Lowercase)))
        TransformTypeCheck(md, cfg).verify().size shouldBe 1
    }

    @Test
    fun `json with regex_replace is rejected`() {
        val md = metadataFor("payload" to "json")
        val cfg = configFor(ColumnSpec("payload",
            transforms = listOf(Transform.RegexReplace(Pattern.compile("."), ""))))
        TransformTypeCheck(md, cfg).verify().size shouldBe 1
    }

    @Test
    fun `two transforms on one mismatched column produce two mismatches`() {
        val md = metadataFor("total" to "numeric")
        val cfg = configFor(ColumnSpec("total",
            transforms = listOf(Transform.Trim, Transform.Lowercase)))
        val misses = TransformTypeCheck(md, cfg).verify()
        misses.map { it.transformName }.shouldContainExactlyInAnyOrder("trim", "lowercase")
    }

    @Test
    fun `column missing from metadata is skipped`() {
        // ColumnVerification reports the missing column; TransformTypeCheck must not duplicate it.
        val md = metadataFor("other" to "text")
        val cfg = configFor(ColumnSpec("ghost", transforms = listOf(Transform.Trim)))
        TransformTypeCheck(md, cfg).verify().shouldBeEmpty()
    }

    @Test
    fun `column without transforms is skipped`() {
        val md = metadataFor("total" to "numeric")
        val cfg = configFor(ColumnSpec("total"))
        TransformTypeCheck(md, cfg).verify().shouldBeEmpty()
    }

    @Test
    fun `formatMessage clusters interleaved findings by table`() {
        // Caller-passes-interleaved scenario: rows for two tables come in alternating order.
        // formatMessage must render all rows for one table contiguously before any row of the
        // other — `verify()` already produces clustered output, but `formatMessage` doesn't
        // assume that.
        val customersKey = TableKey("public", "customers")
        val misses = listOf(
            TransformTypeCheck.TypeMismatch(ordersKey, "total", "numeric", "trim"),
            TransformTypeCheck.TypeMismatch(customersKey, "age", "integer", "trim"),
            TransformTypeCheck.TypeMismatch(ordersKey, "created_at", "timestamp without time zone", "lowercase"),
            TransformTypeCheck.TypeMismatch(customersKey, "id", "uuid", "uppercase"),
        )
        val msg = TransformTypeCheck.formatMessage(misses)
        val ordersFirstIdx = msg.indexOf("public.orders.total")
        val ordersLastIdx = msg.indexOf("public.orders.created_at")
        val customersFirstIdx = msg.indexOf("public.customers.age")
        val customersLastIdx = msg.indexOf("public.customers.id")
        // The two orders rows must both appear before the two customers rows (or vice versa).
        val ordersBlock = ordersFirstIdx..ordersLastIdx
        val customersBlock = customersFirstIdx..customersLastIdx
        val noOverlap = ordersBlock.last < customersBlock.first || customersBlock.last < ordersBlock.first
        noOverlap shouldBe true
    }

    @Test
    fun `formatMessage groups by table and names db type and transform`() {
        val misses = listOf(
            TransformTypeCheck.TypeMismatch(ordersKey, "total", "numeric", "trim"),
            TransformTypeCheck.TypeMismatch(ordersKey, "created_at",
                "timestamp without time zone", "lowercase"),
        )
        val msg = TransformTypeCheck.formatMessage(misses)
        msg shouldContain "Configured transforms target non-string columns"
        msg shouldContain "public.orders.total"
        msg shouldContain "\"numeric\""
        msg shouldContain "\"trim\""
        msg shouldContain "public.orders.created_at"
        msg shouldContain "\"lowercase\""
        msg shouldContain "text / character varying / character"
    }
}
