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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

// `data class` is Kotlin's record type — the compiler auto-generates equals(), hashCode(),
// copy(), and toString() from the constructor parameters. Think of it as a TypeScript
// interface with structural equality baked in.

/**
 * Root configuration object. Each field maps to a top-level YAML key; snake_case YAML keys are
 * translated to camelCase Kotlin fields automatically by the ObjectMapper's SNAKE_CASE naming
 * strategy — no @JsonProperty annotations needed.
 *
 * [tablesByKey] is a computed convenience index and is excluded from Jackson binding — see its
 * @get:JsonIgnore annotation for details.
 */
data class Config(
    val service: ServiceConfig,
    val source: SourceConfig,
    val debezium: DebeziumConfig,
    val snowplow: SnowplowConfig,
    val tables: List<TableConfig>,
    val observability: ObservabilityConfig = ObservabilityConfig(),
) {
    // `@get:JsonIgnore` targets the generated getter rather than the backing field.
    // Without it, Jackson's Kotlin module sees this computed property as a bindable field and
    // tries to deserialize it from the YAML — which fails because there is no "tables_by_key"
    // key in the document. The `@get:` site-target is Kotlin's way of annotating the getter
    // specifically when both field and getter exist.
    @get:JsonIgnore
    val tablesByKey: Map<TableKey, TableConfig>
        get() = tables.associateBy { TableKey(it.schema, it.name) }
}

data class ServiceConfig(
    val appId: String,
)

data class SourceConfig(
    val connector: String,
    val hostname: String,
    val port: Int = 5432,
    val database: String,
    val username: String,
    val password: String,
    val slotName: String,
    val publicationName: String,
)

data class DebeziumConfig(
    // "never" skips the initial snapshot and starts streaming from the current WAL position.
    // Operators who need a backfill can set this to "initial" at first run, then revert to "never".
    val snapshotMode: String = "never",
    val offsetStore: OffsetStoreConfig,
    val heartbeatIntervalMs: Long = 30_000,
    val publicationAutocreateMode: String = "filtered",
    val provideTransactionMetadata: Boolean = false,
)

/**
 * Discriminated offset-store config. [type] selects the Debezium offset backing store:
 * - "file" — Kafka Connect FileOffsetBackingStore; requires [filePath]. The default; simplest
 *   for the local demo, but the file is lost on an ephemeral filesystem.
 * - "jdbc" — Debezium JdbcOffsetBackingStore; requires [jdbc]. Durable across restarts. The
 *   offset table lives in the source database (URL derived from `source.*`), owned by [jdbc].
 */
data class OffsetStoreConfig(
    val type: String = "file",
    val filePath: String? = null,
    val jdbc: JdbcOffsetConfig? = null,
)

data class JdbcOffsetConfig(
    val username: String,
    val password: String,
    val tableName: String = "cdc_offset_storage",
)

data class SnowplowConfig(
    val collectorUrl: String,
    val cdcSourceSchema: String,
    val emitter: EmitterConfig = EmitterConfig(),
)

/**
 * Snowplow emitter knobs. Both keys map directly to the underlying Snowplow Java tracker:
 * - [batchSize]      — number of events sent per HTTP request to the collector.
 * - [bufferCapacity] — the gate. The service holds at most [bufferCapacity] events in flight.
 *                      When the buffer is full, [com.snowplowanalytics.cdc.emitter.SnowplowEmitter.emit]
 *                      blocks instead of dropping the event, propagating backpressure to the
 *                      Debezium engine and ultimately to Postgres WAL retention.
 *
 * The semaphore inside [SnowplowEmitter] uses [bufferCapacity] permits; the underlying tracker's
 * own buffer is sized identically. A startup self-test ([com.snowplowanalytics.cdc.preflight.EmitterSelfTest])
 * proves the wrapper still blocks before the service begins reading WAL.
 */
data class EmitterConfig(
    val batchSize: Int = 1,
    val bufferCapacity: Int = 1_000,
)

data class HttpConfig(val port: Int = 8080)

data class HeartbeatConfig(val intervalMs: Long = 60_000)

data class ObservabilityConfig(
    val http: HttpConfig = HttpConfig(),
    val heartbeat: HeartbeatConfig = HeartbeatConfig(),
)

/**
 * Per-table CDC configuration. The [columns] field uses a custom deserializer because its YAML
 * entries can be either bare strings or single-key maps with `rename`, `exclude`, or `transforms`.
 */
data class TableConfig(
    val name: String,
    val schema: String,
    val igluSchema: String,
    val primaryKey: List<String>,
    val appId: String? = null,
    // @JsonDeserialize(using = X::class) tells Jackson to delegate this field's deserialization
    // to ColumnsDeserializer instead of using reflective binding. Needed because the column list
    // can contain heterogeneous entries (strings OR single-key maps).
    @JsonDeserialize(using = ColumnsDeserializer::class)
    val columns: List<ColumnSpec>,
)

// A value-type key for the tablesByKey map — data class gives structural equality for free,
// so Map lookups work correctly when schema+name match even across different instances.
data class TableKey(val schema: String, val name: String) {
    override fun toString(): String = "$schema.$name"
}

/**
 * Reads `tables[].columns` entries that are EITHER:
 *   - a bare string scalar:                       `- id`
 *   - a single-key map (inline or block):         `- email: { rename: emailAddress }`
 *
 * Honors three directive keys:
 *   - `rename: <string>`                — output property name (default: the source column name)
 *   - `exclude: <boolean>`              — true marks the column as listed-but-suppressed
 *   - `transforms: <list>`              — ordered per-column transform chain (see [TransformsDeserializer])
 *
 * Unknown directive keys (anything other than the three above) are silently tolerated so the
 * deserializer survives the introduction of future per-column directives without an intermediate
 * transitional state. The value half of the single-key map is interpreted as a directive bag;
 * if it is not a map,
 * the column is treated as bare (its value is discarded).
 *
 * Known directives with the wrong YAML type throw a JsonMappingException naming the column.
 */
class ColumnsDeserializer : JsonDeserializer<List<ColumnSpec>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<ColumnSpec> {
        val node: JsonNode = p.codec.readTree(p)
        if (!node.isArray) {
            throw JsonMappingException.from(p, "columns must be a YAML list")
        }
        return node.map { entry ->
            when {
                entry.isTextual -> ColumnSpec(name = entry.asText())
                entry.isObject -> parseSingleKeyEntry(p, entry)
                else -> throw JsonMappingException.from(
                    p,
                    "column entry must be a string or single-key map",
                )
            }
        }
    }

    private fun parseSingleKeyEntry(p: JsonParser, entry: JsonNode): ColumnSpec {
        // fieldNames() returns a Java Iterator; .asSequence() bridges it to a Kotlin Sequence.
        val keys = entry.fieldNames().asSequence().toList()
        if (keys.size != 1) {
            throw JsonMappingException.from(p, multiKeyErrorMessage(keys))
        }
        val name = keys.first()
        val directives = entry.get(name)
        // If the value half isn't a map (e.g. `- foo: 1`), treat the entry as bare — preserves
        // the existing tolerant behavior and keeps the rule "the key is the column name".
        if (directives == null || !directives.isObject) {
            return ColumnSpec(name = name)
        }
        val renameNode = directives.get("rename")
        val rename = renameNode?.let {
            if (!it.isTextual) throw JsonMappingException.from(
                p, "column '$name': rename must be a string; got ${it.nodeType}",
            )
            it.asText()
        }
        val excludeNode = directives.get("exclude")
        val exclude = excludeNode?.let {
            if (!it.isBoolean) throw JsonMappingException.from(
                p, "column '$name': exclude must be a boolean; got ${it.nodeType}",
            )
            it.asBoolean()
        } ?: false
        val transformsNode = directives.get("transforms")
        val transforms = if (transformsNode == null) emptyList()
                         else TransformsDeserializer.parseList(transformsNode, name, p)
        return ColumnSpec(name = name, rename = rename, exclude = exclude, transforms = transforms)
    }

    // Builds the error message for a multi-key column entry. When at least one key matches a
    // known directive (rename/exclude/transforms), the most likely cause is an under-indented
    // directive — a real two-key column-entry map is not a documented shape, so a directive
    // key appearing as a sibling is a strong signal. The pointed message names the inferred
    // column (first non-directive key) and the first misplaced directive. When every key is
    // a directive we cannot infer a column name and emit a generic "<column>" placeholder.
    // When no directive keys are present at all we keep the original message: that path
    // catches genuinely malformed entries like `{ foo: 1, bar: 2 }` where guessing intent
    // would mislead the operator.
    private fun multiKeyErrorMessage(keys: List<String>): String {
        val directiveKeys = keys.filter { it in DIRECTIVE_KEYS }
        if (directiveKeys.isEmpty()) {
            return "column entry must be a single-key map; got keys: $keys"
        }
        val nonDirectiveKeys = keys - directiveKeys.toSet()
        val inferredColumn = nonDirectiveKeys.firstOrNull() ?: "<column>"
        val exampleDirective = directiveKeys.first()
        val leadIn = if (nonDirectiveKeys.isEmpty()) {
            "column entry parsed as multi-key map $keys.\n" +
                "All keys are directives — did you mean to nest them under a column name?"
        } else {
            "column entry parsed as multi-key map $keys.\n" +
                "This usually means a directive was under-indented. Did you mean:"
        }
        return """
            $leadIn

              - $inferredColumn:
                  $exampleDirective: <value>

            (directives must be nested under the column key, indented further than `- $inferredColumn:`)
        """.trimIndent()
    }

    companion object {
        private val DIRECTIVE_KEYS = setOf("rename", "exclude", "transforms")
    }
}
