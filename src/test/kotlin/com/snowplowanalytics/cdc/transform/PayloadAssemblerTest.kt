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

import com.fasterxml.jackson.databind.ObjectMapper
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.snowplow.tracker.events.Event
import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// Uses a realistic Debezium JSON envelope (insertEnvelope()) rather than a minimal stub, so
// the test catches integration bugs at unit-test depth without needing a live database.
class PayloadAssemblerTest {

    private val schemaUri = "iglu:com.example/orders_change/jsonschema/1-0-0"
    private val tableConfig = com.snowplowanalytics.cdc.config.TableConfig(
        name = "orders",
        schema = "public",
        igluSchema = schemaUri,
        primaryKey = listOf("id"),
        columns = listOf(
            com.snowplowanalytics.cdc.config.ColumnSpec("id"),
            com.snowplowanalytics.cdc.config.ColumnSpec("customer_id"),
            com.snowplowanalytics.cdc.config.ColumnSpec("status"),
            com.snowplowanalytics.cdc.config.ColumnSpec("total"),
        ),
    )
    private val tablesByKey = mapOf(
        com.snowplowanalytics.cdc.config.TableKey("public", "orders") to tableConfig
    )
    private val assembler = PayloadAssembler(
        tablesByKey = tablesByKey,
        defaultAppId = "orders-cdc",
        connectorName = "postgres",
        cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
    )
    private val mapper = ObjectMapper()

    /**
     * Decodes the base64 `ue_px` payload — every event from PayloadAssembler is base64-encoded
     * because DeterministicEvent forces it via setBase64Encode(true).
     */
    @Suppress("UNCHECKED_CAST")
    private fun innerSdj(event: Event): Map<String, Any?> {
        val payloadMap = event.payload.map
        val ueB64 = payloadMap["ue_px"] as? String ?: error("ue_px not found in payload")
        val ueSdj = String(java.util.Base64.getDecoder().decode(ueB64))
        val outer = mapper.readValue(ueSdj, Map::class.java) as Map<String, Any?>
        return outer["data"] as Map<String, Any?>
    }

    @Test
    fun `assembles INSERT change event into a SelfDescribing event with the configured schema URI`() {
        val event = assembler.assemble(insertEnvelope())
        val sdj = innerSdj(event)
        sdj["schema"] shouldBe schemaUri
        (sdj.containsKey("data")) shouldBe true
    }

    @Test
    fun `payload contains op equal to c and the after row state for an INSERT`() {
        val event = assembler.assemble(insertEnvelope())
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>
        data["op"] shouldBe "c"
        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>
        after["id"] shouldBe 42
        after["customer_id"] shouldBe 1
        after["status"] shouldBe "pending"
        after["total"] shouldBe "99.00"
    }

    @Test
    fun `true timestamp is taken from source ts_ms on the envelope`() {
        val event = assembler.assemble(insertEnvelope(sourceTsMs = 1714400000000L))
        event.trueTimestamp shouldNotBe null
        // trueTimestamp is the DB commit time from source.ts_ms — not the time this JVM
        // processed the event, which would arrive later and lose the original timing.
        event.trueTimestamp shouldBe 1714400000000L
    }

    @Test
    fun `assemble throws IllegalStateException for an unconfigured table`() {
        val foreignEnvelope = insertEnvelope().replace("\"table\":\"orders\"", "\"table\":\"widgets\"")
        val ex = assertThrows<IllegalStateException> {
            assembler.assemble(foreignEnvelope)
        }
        ex.message shouldContain "public.widgets"
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstContextMap(event: Event): Map<String, Any?> {
        val contexts = event.context
        contexts.size shouldBe 1
        return contexts.single().map as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstContextData(event: Event): Map<String, Any?> =
        firstContextMap(event)["data"] as Map<String, Any?>

    private fun String.replaceOrThrow(old: String, new: String): String {
        check(contains(old)) { "replaceOrThrow: substring not found: $old" }
        return replace(old, new)
    }

    @Test
    fun `every assembled event carries one cdc_source context entity`() {
        val event = assembler.assemble(insertEnvelope())
        event.context shouldHaveSize 1
    }

    @Test
    fun `cdc_source context entity references the configured iglu URI`() {
        val event = assembler.assemble(insertEnvelope())
        firstContextMap(event)["schema"] shouldBe
            "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0"
    }

    @Test
    fun `cdc_source connector field comes from config not envelope`() {
        // Envelope reports "postgresql" (Debezium's internal name); config carries "postgres".
        // The context entity must use the config value so analysts see the operator-controlled name.
        val event = assembler.assemble(insertEnvelope())
        firstContextData(event)["connector"] shouldBe "postgres"
    }

    @Test
    fun `cdc_source lsn is canonical X slash Y hex format`() {
        // Envelope's source.lsn is the Long 23000000 = 0x015EF3C0 → expected "0/15EF3C0"
        // (high 32 bits = 0x0, low 32 bits = 0x15EF3C0).
        val event = assembler.assemble(insertEnvelope())
        firstContextData(event)["lsn"] shouldBe "0/15EF3C0"
    }

    @Test
    fun `cdc_source lsn is null when envelope has no lsn field`() {
        val withoutLsn = insertEnvelope().replaceOrThrow("\"lsn\":23000000,", "\"lsn\":null,")
        val event = assembler.assemble(withoutLsn)
        firstContextData(event)["lsn"] shouldBe null
    }

    @Test
    fun `cdc_source txid carries the envelope source txid as a Long`() {
        // Default envelope has no txid; add one and verify pass-through. Real Debezium 3.x
        // Postgres emits the source field as `txId` (camelCase), so the fixture matches.
        val withTxid = insertEnvelope().replaceOrThrow("\"xmin\":null", "\"txId\":555444,\"xmin\":null")
        val event = assembler.assemble(withTxid)
        firstContextData(event)["txid"] shouldBe 555444L
    }

    @Test
    fun `cdc_source txid is null when envelope source has no txid`() {
        val event = assembler.assemble(insertEnvelope())
        firstContextData(event)["txid"] shouldBe null
    }

    @Test
    fun `cdc_source is_snapshot is false when source snapshot is the string false`() {
        val event = assembler.assemble(insertEnvelope())
        firstContextData(event)["is_snapshot"] shouldBe false
    }

    @Test
    fun `cdc_source is_snapshot is true when source snapshot is true, last, or incremental`() {
        listOf("true", "last", "incremental").forEach { value ->
            val envelope = insertEnvelope().replaceOrThrow("\"snapshot\":\"false\"", "\"snapshot\":\"$value\"")
            val event = assembler.assemble(envelope)
            withClue("snapshot=$value") { firstContextData(event)["is_snapshot"] shouldBe true }
        }
    }

    @Test
    fun `cdc_source schema is null when source schema is the empty string`() {
        val withEmptySchema = insertEnvelope().replaceOrThrow("\"schema\":\"public\"", "\"schema\":\"\"")
        // Note: this envelope still maps to TableKey("", "orders") which isn't configured.
        // Use a one-off TableConfig keyed by "" to verify the null coalescing.
        val emptySchemaConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "orders", schema = "", igluSchema = schemaUri,
            primaryKey = listOf("id"), columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec("customer_id"),
                com.snowplowanalytics.cdc.config.ColumnSpec("status"),
                com.snowplowanalytics.cdc.config.ColumnSpec("total"),
            ),
        )
        val emptySchemaAssembler = PayloadAssembler(
            tablesByKey = mapOf(com.snowplowanalytics.cdc.config.TableKey("", "orders") to emptySchemaConfig),
            defaultAppId = "orders-cdc",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )
        val event = emptySchemaAssembler.assemble(withEmptySchema)
        firstContextData(event)["schema"] shouldBe null
    }

    /**
     * Builds an envelope with caller-specified columns instead of the default order schema.
     * The payload still references "orders"/"public" so the existing tableConfig matches.
     */
    private fun envelopeWithColumns(columns: List<ColSpec>): String =
        envelopeWithSchema(
            payload = """
                {
                  "before": null,
                  "after": {"id": 42},
                  "source": {
                    "connector": "postgresql",
                    "ts_ms": 1714400000000,
                    "snapshot": "false",
                    "db": "orders_db",
                    "schema": "public",
                    "table": "orders",
                    "lsn": 23000000
                  },
                  "op": "c",
                  "ts_ms": 1714400000050
                }
            """.trimIndent(),
            columns = columns,
        )

    private fun fingerprint(envelope: String): String {
        val event = assembler.assemble(envelope)
        return firstContextData(event)["column_fingerprint"] as String
    }

    @Test
    fun `fingerprint matches the expected hex SHA-256 shape`() {
        val fp = fingerprint(insertEnvelope())
        fp shouldMatch Regex("^[0-9a-f]{64}$")
    }

    @Test
    fun `fingerprint is stable for the same column schema`() {
        val cols = listOf(ColSpec("id", "int32"), ColSpec("email", "string", optional = true))
        fingerprint(envelopeWithColumns(cols)) shouldBe fingerprint(envelopeWithColumns(cols))
    }

    @Test
    fun `fingerprint changes when a column is added`() {
        val before = listOf(ColSpec("id", "int32"))
        val after = listOf(ColSpec("id", "int32"), ColSpec("email", "string"))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    @Test
    fun `fingerprint changes when a column is removed`() {
        val before = listOf(ColSpec("id", "int32"), ColSpec("email", "string"))
        val after = listOf(ColSpec("id", "int32"))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    @Test
    fun `fingerprint changes when a primitive type changes`() {
        val before = listOf(ColSpec("id", "int32"))
        val after = listOf(ColSpec("id", "int64"))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    @Test
    fun `fingerprint changes when a logical type changes`() {
        val before = listOf(ColSpec("created_at", "int64", logicalName = "io.debezium.time.Timestamp"))
        val after  = listOf(ColSpec("created_at", "int64", logicalName = "io.debezium.time.Date"))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    @Test
    fun `fingerprint changes when nullability changes`() {
        val before = listOf(ColSpec("id", "int32", optional = false))
        val after  = listOf(ColSpec("id", "int32", optional = true))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    @Test
    fun `fingerprint changes when column declaration order changes`() {
        val before = listOf(ColSpec("id", "int32"), ColSpec("email", "string"))
        val after  = listOf(ColSpec("email", "string"), ColSpec("id", "int32"))
        fingerprint(envelopeWithColumns(before)) shouldNotBe fingerprint(envelopeWithColumns(after))
    }

    // Slice 3: with value.converter.schemas.enable=true, the envelope is wrapped under
    // {"schema": {...}, "payload": {...}}. envelopeWithSchema() handles the wrapping.
    private val orderColumns = listOf(
        ColSpec("id", "int32"),
        ColSpec("customer_id", "int32"),
        ColSpec("status", "string"),
        ColSpec("total", "string"),
    )

    @Test
    fun `assembles UPDATE change event with op=u, before, and after maps`() {
        val envelope = changeEventEnvelope(
            op = "u",
            before = """{"id": 42, "customer_id": 1, "status": "pending", "total": "99.00"}""",
            after  = """{"id": 42, "customer_id": 1, "status": "shipped", "total": "99.00"}""",
        )
        val event = assembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>

        data["op"] shouldBe "u"

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["status"] shouldBe "pending"

        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>
        after["status"] shouldBe "shipped"
    }

    @Test
    fun `assembles DELETE change event with op=d and before only, no after key`() {
        val envelope = changeEventEnvelope(
            op = "d",
            before = """{"id": 42, "customer_id": 1, "status": "shipped", "total": "99.00"}""",
            after  = null,
        )
        val event = assembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>

        data["op"] shouldBe "d"

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["id"] shouldBe 42

        // The delete payload must NOT contain an "after" key (not "after"=null, but absent entirely).
        // Downstream consumers should be able to assume after is present iff the row has a post-state.
        data.containsKey("after") shouldBe false
    }

    @Test
    fun `assembles snapshot READ with op=r, after only, and is_snapshot=true in cdc_source`() {
        val envelope = changeEventEnvelope(
            op = "r",
            before = null,
            after = """{"id": 42, "customer_id": 1, "status": "pending", "total": "99.00"}""",
            source = SourceSpec(snapshot = "true"),
        )
        val event = assembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>

        data["op"] shouldBe "r"

        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>
        after["status"] shouldBe "pending"

        data.containsKey("before") shouldBe false

        // is_snapshot flows through Slice 3's cdc_source helper unchanged — verify it lights up here.
        firstContextData(event)["is_snapshot"] shouldBe true
    }

    @Test
    fun `assembles TRUNCATE op throws UnsupportedOpException with op and table identity`() {
        val envelope = changeEventEnvelope(op = "t", before = null, after = null)
        val ex = assertThrows<UnsupportedOpException> { assembler.assemble(envelope) }
        ex.op shouldBe "t"
        ex.table.toString() shouldBe "public.orders"
    }

    @Test
    fun `assembling the same envelope twice yields the same eid on both payloads`() {
        val envelope = changeEventEnvelope(
            op = "u",
            before = """{"id": 42, "customer_id": 1, "status": "pending", "total": "99.00"}""",
            after  = """{"id": 42, "customer_id": 1, "status": "shipped", "total": "99.00"}""",
            source = SourceSpec(lsn = 23911616L, txId = 123L),
        )
        val first  = assembler.assemble(envelope).payload.map["eid"]
        val second = assembler.assemble(envelope).payload.map["eid"]
        first shouldNotBe null
        first shouldBe second
    }

    @Test
    fun `event_id changes when only the op changes (c→u against the same row)`() {
        val rowJson = """{"id": 42, "customer_id": 1, "status": "pending", "total": "99.00"}"""
        val asInsert = assembler.assemble(
            changeEventEnvelope(op = "c", before = null, after = rowJson)
        ).payload.map["eid"]
        val asUpdate = assembler.assemble(
            changeEventEnvelope(op = "u", before = rowJson, after = rowJson)
        ).payload.map["eid"]
        asInsert shouldNotBe null
        asUpdate shouldNotBe null
        asInsert shouldNotBe asUpdate
    }

    @Test
    fun `event_id is well-formed even when source txid is null (snapshot read)`() {
        val envelope = changeEventEnvelope(
            op = "r",
            before = null,
            after = """{"id": 42, "customer_id": 1, "status": "pending", "total": "99.00"}""",
            source = SourceSpec(snapshot = "true", txId = null),
        )
        val eid = assembler.assemble(envelope).payload.map["eid"] as String
        // UUID format: 8-4-4-4-12 hex characters separated by hyphens
        eid shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }

    @Test
    fun `assemble applies rename and exclude before placing maps into the payload`() {
        val customersTableConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "customers",
            schema = "public",
            igluSchema = "iglu:com.example/customers_change/jsonschema/1-0-0",
            primaryKey = listOf("id"),
            columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec("email", rename = "emailAddress"),
                com.snowplowanalytics.cdc.config.ColumnSpec("password_hash", exclude = true),
            ),
        )
        val customAssembler = PayloadAssembler(
            tablesByKey = mapOf(
                com.snowplowanalytics.cdc.config.TableKey("public", "customers") to customersTableConfig
            ),
            defaultAppId = "test",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )

        val envelope = changeEventEnvelope(
            op = "c",
            after = """{"id": 7, "email": "a@b.com", "password_hash": "abc123"}""",
            source = SourceSpec(schema = "public", table = "customers"),
            columns = listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string"),
                ColSpec("password_hash", "string"),
            ),
        )
        val event = customAssembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>

        // toList() is order-sensitive; setOf would silently accept a regression that swapped keys.
        after.keys.toList() shouldBe listOf("id", "emailAddress")  // password_hash excluded
        after["emailAddress"] shouldBe "a@b.com"
        after.containsKey("password_hash") shouldBe false
        after.containsKey("email") shouldBe false  // renamed away
    }

    @Test
    fun `assemble routes after through transform chain after projection`() {
        // Input "  Alice@Example.COM  " should be normalised to "alice@example.com" via
        // [trim, lowercase]; rename also applies, so the key in the payload is emailAddress.
        val customersTableConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "customers",
            schema = "public",
            igluSchema = "iglu:com.example/customers_change/jsonschema/1-0-0",
            primaryKey = listOf("id"),
            columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec(
                    name = "email",
                    rename = "emailAddress",
                    transforms = listOf(Transform.Trim, Transform.Lowercase),
                ),
            ),
        )
        val customAssembler = PayloadAssembler(
            tablesByKey = mapOf(
                com.snowplowanalytics.cdc.config.TableKey("public", "customers") to customersTableConfig
            ),
            defaultAppId = "test",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )

        val envelope = changeEventEnvelope(
            op = "c",
            after = """{"id": 7, "email": "  Alice@Example.COM  "}""",
            source = SourceSpec(schema = "public", table = "customers"),
            columns = listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string"),
            ),
        )
        val event = customAssembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>

        after["emailAddress"] shouldBe "alice@example.com"
    }

    @Test
    fun `assemble routes both before and after through transform chain for UPDATE`() {
        // op=u carries both before and after. Both must be projected and transformed; PK
        // extraction still reads the raw map so event_id is unaffected.
        val customersTableConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "customers",
            schema = "public",
            igluSchema = "iglu:com.example/customers_change/jsonschema/1-0-0",
            primaryKey = listOf("id"),
            columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec(
                    name = "email",
                    rename = "emailAddress",
                    transforms = listOf(Transform.Trim, Transform.Lowercase),
                ),
            ),
        )
        val customAssembler = PayloadAssembler(
            tablesByKey = mapOf(
                com.snowplowanalytics.cdc.config.TableKey("public", "customers") to customersTableConfig
            ),
            defaultAppId = "test",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )

        val envelope = changeEventEnvelope(
            op = "u",
            before = """{"id": 7, "email": "  Alice@Example.COM  "}""",
            after  = """{"id": 7, "email": "  ALICE+New@Example.COM  "}""",
            source = SourceSpec(schema = "public", table = "customers"),
            columns = listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string"),
            ),
        )
        val event = customAssembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["emailAddress"] shouldBe "alice@example.com"

        @Suppress("UNCHECKED_CAST")
        val after = data["after"] as Map<String, Any?>
        after["emailAddress"] shouldBe "alice+new@example.com"
    }

    @Test
    fun `assemble routes before through transform chain for DELETE and emits no after`() {
        // op=d carries only before. The chain must apply to before; the payload must not
        // include an after key.
        val customersTableConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "customers",
            schema = "public",
            igluSchema = "iglu:com.example/customers_change/jsonschema/1-0-0",
            primaryKey = listOf("id"),
            columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec(
                    name = "email",
                    rename = "emailAddress",
                    transforms = listOf(Transform.Trim, Transform.Lowercase),
                ),
            ),
        )
        val customAssembler = PayloadAssembler(
            tablesByKey = mapOf(
                com.snowplowanalytics.cdc.config.TableKey("public", "customers") to customersTableConfig
            ),
            defaultAppId = "test",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )

        val envelope = changeEventEnvelope(
            op = "d",
            before = """{"id": 7, "email": "  Alice@Example.COM  "}""",
            after  = null,
            source = SourceSpec(schema = "public", table = "customers"),
            columns = listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string"),
            ),
        )
        val event = customAssembler.assemble(envelope)
        @Suppress("UNCHECKED_CAST")
        val data = innerSdj(event)["data"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val before = data["before"] as Map<String, Any?>
        before["emailAddress"] shouldBe "alice@example.com"
        data.containsKey("after") shouldBe false
    }

    @Test
    fun `assemble propagates IllegalStateException when a non-String cell reaches a transform`() {
        // DDL-drift mid-stream: the column was string-typed at startup (so TransformTypeCheck
        // passed) but a value arrives that isn't a String. Transform.apply throws; the throw
        // must propagate out of assemble() so EngineRunner.handleRecord can log + skip the event.
        val customersTableConfig = com.snowplowanalytics.cdc.config.TableConfig(
            name = "customers",
            schema = "public",
            igluSchema = "iglu:com.example/customers_change/jsonschema/1-0-0",
            primaryKey = listOf("id"),
            columns = listOf(
                com.snowplowanalytics.cdc.config.ColumnSpec("id"),
                com.snowplowanalytics.cdc.config.ColumnSpec(
                    name = "email",
                    transforms = listOf(Transform.Lowercase),
                ),
            ),
        )
        val customAssembler = PayloadAssembler(
            tablesByKey = mapOf(
                com.snowplowanalytics.cdc.config.TableKey("public", "customers") to customersTableConfig
            ),
            defaultAppId = "test",
            connectorName = "postgres",
            cdcSourceSchemaUri = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        )

        // email arrives as a JSON number, not a string. The envelope's column schema declares it
        // string-typed (mirroring how startup type-checking saw it), but the row data violates
        // that — Jackson parses the number node to an Int in the row map.
        val envelope = changeEventEnvelope(
            op = "c",
            after = """{"id": 7, "email": 42}""",
            source = SourceSpec(schema = "public", table = "customers"),
            columns = listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string"),
            ),
        )
        val ex = assertThrows<IllegalStateException> { customAssembler.assemble(envelope) }
        ex.message!! shouldContain "email"
        ex.message!! shouldContain "Int"
    }

    @Test
    fun `assemble returns an AssembledEvent exposing tableKey and op`() {
        val result = assembler.assemble(insertEnvelope())
        result.shouldBeInstanceOf<AssembledEvent>()
        result.tableKey shouldBe TableKey("public", "orders")
        result.op shouldBe "c"
    }

    private fun insertEnvelope(sourceTsMs: Long = 1714400000000L): String =
        envelopeWithSchema(
            payload = """
                {
                  "before": null,
                  "after": {
                    "id": 42,
                    "customer_id": 1,
                    "status": "pending",
                    "total": "99.00"
                  },
                  "source": {
                    "version": "3.0.7.Final",
                    "connector": "postgresql",
                    "name": "cdc-service",
                    "ts_ms": $sourceTsMs,
                    "snapshot": "false",
                    "db": "orders_db",
                    "schema": "public",
                    "table": "orders",
                    "lsn": 23000000,
                    "xmin": null
                  },
                  "op": "c",
                  "ts_ms": ${sourceTsMs + 50},
                  "transaction": null
                }
            """.trimIndent(),
            columns = orderColumns,
        )
}
