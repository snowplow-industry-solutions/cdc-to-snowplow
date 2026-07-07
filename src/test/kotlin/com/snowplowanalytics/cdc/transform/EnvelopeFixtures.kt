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
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Single column descriptor used by [envelopeWithSchema] to build the Connect "after" struct
 * schema. Mirrors the four pieces of metadata Debezium's JSON converter renders per field:
 * the field name, the Connect primitive type, an optional logical-type name, and the
 * optional flag (Connect's nullability marker).
 */
data class ColSpec(
    val name: String,
    val primitive: String,            // "int32", "string", "int64", "boolean", ...
    val logicalName: String? = null,  // e.g., "io.debezium.time.MicroTimestamp"
    val optional: Boolean = false,
)

private val mapper = ObjectMapper()

/**
 * Wraps a Debezium-payload JSON string in the schemas-enabled envelope shape. Returns the JSON
 * string `{"schema":{...struct describing payload+source+after+...},"payload":<payload>}`,
 * with the after-struct schema fields populated from [columns]. Source/op/ts_ms field schemas
 * are stubbed minimally — only the after-struct is consumed by the production fingerprint
 * helper, so other fields can stay schema-shaped placeholders.
 */
fun envelopeWithSchema(payload: String, columns: List<ColSpec>): String {
    val payloadNode = mapper.readTree(payload)
    val schemaNode = mapper.createObjectNode().apply {
        put("type", "struct")
        val fields = putArray("fields")

        // Stub field schema for "before" — same shape as "after". Connect always renders this.
        fields.add(structFieldSchema("before", columns, optional = true))
        // The "after" struct schema is what SourceSchemaFingerprinter reads.
        fields.add(structFieldSchema("after", columns, optional = true))
        // Stub source struct — fields irrelevant for unit tests; the helper just needs the slot.
        fields.add(mapper.createObjectNode().apply {
            put("type", "struct")
            put("optional", false)
            put("field", "source")
            putArray("fields")  // empty stub
        })
        fields.add(scalarFieldSchema("op", "string", optional = false))
        fields.add(scalarFieldSchema("ts_ms", "int64", optional = true))
    }

    val envelope = mapper.createObjectNode().apply {
        set<ObjectNode>("schema", schemaNode)
        set<ObjectNode>("payload", payloadNode as ObjectNode)
    }
    return mapper.writeValueAsString(envelope)
}

private fun structFieldSchema(
    fieldName: String,
    columns: List<ColSpec>,
    optional: Boolean,
): ObjectNode = mapper.createObjectNode().apply {
    put("type", "struct")
    val cols = putArray("fields")
    for (col in columns) {
        cols.add(mapper.createObjectNode().apply {
            put("type", col.primitive)
            put("optional", col.optional)
            if (col.logicalName != null) put("name", col.logicalName)
            put("field", col.name)
        })
    }
    put("optional", optional)
    put("field", fieldName)
}

private fun scalarFieldSchema(fieldName: String, primitive: String, optional: Boolean): ObjectNode =
    mapper.createObjectNode().apply {
        put("type", primitive)
        put("optional", optional)
        put("field", fieldName)
    }

/**
 * Caller-friendly source-field block. Mirrors the Debezium 3.x Postgres `source` struct:
 * `txId` is camelCase (matches Debezium 3.x), `snapshot` is one of "false" / "true" / "last" /
 * "incremental", `lsn` is a Long (or null) — the assembler formats it to canonical "X/Y" hex.
 */
data class SourceSpec(
    val connector: String = "postgresql",
    val db: String = "orders_db",
    val schema: String = "public",
    val table: String = "orders",
    val tsMs: Long = 1714400000000L,
    val snapshot: String = "false",
    val lsn: Long? = 23000000L,
    val txId: Long? = null,
)

/**
 * Build a change-event envelope (schemas-enabled wrapping included) for an arbitrary op.
 * `before` and `after` are JSON string literals (or null). The caller is responsible for ensuring
 * they match the column schema implied by [columns]; the helper does no validation.
 */
fun changeEventEnvelope(
    op: String,
    before: String? = null,
    after: String? = null,
    source: SourceSpec = SourceSpec(),
    columns: List<ColSpec> = listOf(
        ColSpec("id", "int32"),
        ColSpec("customer_id", "int32"),
        ColSpec("status", "string"),
        ColSpec("total", "string"),
    ),
): String {
    val sourceJson = mapper.writeValueAsString(
        linkedMapOf<String, Any?>(
            "version" to "3.0.7.Final",
            "connector" to source.connector,
            "name" to "cdc-service",
            "ts_ms" to source.tsMs,
            "snapshot" to source.snapshot,
            "db" to source.db,
            "schema" to source.schema,
            "table" to source.table,
            "lsn" to source.lsn,
            "xmin" to null,
        ).also { map -> source.txId?.let { map["txId"] = it } }
    )
    val payload = """
        {
          "before": ${before ?: "null"},
          "after": ${after ?: "null"},
          "source": $sourceJson,
          "op": "$op",
          "ts_ms": ${source.tsMs + 50},
          "transaction": null
        }
    """.trimIndent()
    return envelopeWithSchema(payload = payload, columns = columns)
}
