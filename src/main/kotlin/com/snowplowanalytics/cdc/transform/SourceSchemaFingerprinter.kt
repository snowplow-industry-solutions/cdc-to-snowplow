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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.snowplowanalytics.cdc.config.TableKey
import java.security.MessageDigest

/**
 * Pure helper that reads a Debezium schemas-enabled envelope and produces a [SourceSchemaSnapshot]:
 * the source [TableKey], a stable SHA-256 fingerprint of the after-struct columns, and the column
 * names in declaration order. The fingerprint formula is the single source of truth for both
 * [PayloadAssembler] (per-event cdc_source.column_fingerprint) and the engine-level DDL detector.
 *
 * Canonical line per column is `name<TAB>primitive<TAB>logical_name<TAB>optional`, lines joined by
 * `\n`, then SHA-256, lowercase hex. See Slice 3 spec §6 for why we hash `schema.fields[after]`.
 *
 * Process-wide singleton: the cached `mapper` is thread-safe for read operations as long as no
 * callers reconfigure it (none do).
 */
object SourceSchemaFingerprinter {

    private val mapper = ObjectMapper()

    fun snapshot(envelopeJson: String): SourceSchemaSnapshot =
        snapshot(mapper.readTree(envelopeJson))

    fun snapshot(envelope: JsonNode): SourceSchemaSnapshot {
        val schemaNode = envelope.path("schema")
        val source = envelope.path("payload").path("source")

        val table = TableKey(
            schema = source.path("schema").asText(),
            name = source.path("table").asText(),
        )

        val afterFields = schemaNode.path("fields")
            .firstOrNull { it.path("field").asText() == "after" }
            ?.path("fields")
            ?: error(
                "envelope missing schema.fields[after] — value.converter.schemas.enable misconfigured"
            )

        val columnNames = afterFields.map { it.path("field").asText() }

        // Canonical line per column: name<TAB>primitive<TAB>logical_name?<TAB>optional. Connect
        // defaults: logical name absent → empty string; defensive `asBoolean(true)` only matters
        // for a malformed envelope (Connect always emits `optional`).
        val canonical = afterFields.joinToString(separator = "\n") { col ->
            val name = col.path("field").asText()
            val primitive = col.path("type").asText()
            val logicalName = col.path("name").asText("")
            val optional = col.path("optional").asBoolean(true).toString()
            "$name\t$primitive\t$logicalName\t$optional"
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val fingerprint = digest.joinToString("") { "%02x".format(it) }

        return SourceSchemaSnapshot(table = table, fingerprint = fingerprint, columnNames = columnNames)
    }
}
