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
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.snowplowanalytics.cdc.config.TableConfig
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.snowplow.tracker.events.Event
import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson

/**
 * Converts a raw Debezium JSON envelope string into a Snowplow [Event] (a [DeterministicEvent]
 * wrapping a [SelfDescribing]) ready for emission.
 *
 * Handles INSERT (op=c), UPDATE (op=u), DELETE (op=d), and snapshot READ (op=r). All other
 * operations (today, just Debezium's `t` for TRUNCATE, plus any future op character we don't
 * model) throw [UnsupportedOpException] so the caller can log-and-skip cleanly.
 */
class PayloadAssembler(
    private val tablesByKey: Map<TableKey, TableConfig>,
    @Suppress("unused") // carried for Slice 4 (per-table app_id override)
    private val defaultAppId: String,
    private val connectorName: String,
    private val cdcSourceSchemaUri: String,
    private val eventIdGenerator: EventIdGenerator = EventIdGenerator(),
    private val mapper: ObjectMapper = defaultMapper(),
) {
    // One projector per table, built once at construction. Mirrors `tablesByKey`'s shape so
    // assemble() can do a constant-time lookup keyed by the same TableKey.
    private val projectorsByKey: Map<TableKey, ColumnProjector> =
        tablesByKey.mapValues { (_, tableConfig) -> ColumnProjector(tableConfig) }

    // One TransformPipeline per table — applied after the projector. Built here so the per-event
    // path stays a constant-time lookup keyed by the same TableKey as projectorsByKey.
    private val pipelinesByKey: Map<TableKey, TransformPipeline> =
        tablesByKey.mapValues { (_, tableConfig) -> TransformPipeline(tableConfig) }

    fun assemble(envelopeJson: String): AssembledEvent {
        // mapper.readTree() parses to a generic JsonNode tree, letting us probe `op` before
        // committing to any typed POJO.
        val envelope = mapper.readTree(envelopeJson)

        // schemas-enabled envelopes wrap the original {op, source, after, before, ts_ms} under
        // a top-level "payload" node, with a sibling "schema" node describing the Connect struct.
        val payload = envelope.path("payload")

        // Debezium `op` values: c=INSERT, u=UPDATE, d=DELETE, r=READ (snapshot), t=TRUNCATE
        val op = payload.path("op").asText()

        // The `source` node carries database metadata: schema, table, ts_ms (DB commit time),
        // lsn (log sequence number), txid, etc.
        val source = payload.path("source")
        val key = TableKey(source.path("schema").asText(), source.path("table").asText())
        val tableConfig = tablesByKey[key]
            ?: error( // error() is Kotlin stdlib for IllegalStateException
                "change event for unconfigured table $key — Debezium include-list is " +
                    "out of sync with config"
            )

        // For c/u/r the row state is in `after`; for d it's in `before`. The fields that aren't
        // applicable to a given op are JSON null in the envelope (Debezium populates only the
        // applicable side). We convert each side once and let the per-op switch decide.
        val beforeMap: Map<String, Any?>? = readRowMap(payload.path("before"))
        val afterMap: Map<String, Any?>?  = readRowMap(payload.path("after"))

        // source.ts_ms is the PostgreSQL commit timestamp in milliseconds since the Unix epoch.
        // We use this as trueTimestamp rather than letting the tracker set deviceCreatedTimestamp
        // (dvce_created_tstamp), which would reflect when this JVM process received the event —
        // much later than the actual DB transaction. The Snowplow tracker 2.1.0 builder also
        // does not expose a dvce_created_tstamp setter, so trueTimestamp is the only option.
        val sourceTsMs = source.path("ts_ms").asLong()

        // Project the raw Debezium row maps through the per-table column plan BEFORE placing them
        // into the payload. Rename and exclude both happen here; PK extraction below still reads
        // from the un-projected maps because PK identity is a DB-side fact.
        // The error() branch is unreachable today (projectorsByKey is built from tablesByKey via
        // mapValues, so the lookup never misses once tableConfig has been resolved above) — it
        // guards against a future refactor that constructs projectorsByKey out-of-band.
        val projector = projectorsByKey[key]
            ?: error("no ColumnProjector for $key — tablesByKey and projectorsByKey out of sync")
        val pipeline = pipelinesByKey[key]
            ?: error("no TransformPipeline for $key — tablesByKey and pipelinesByKey out of sync")
        val projectedBefore = pipeline.apply(projector.project(beforeMap))
        val projectedAfter  = pipeline.apply(projector.project(afterMap))

        val tablePayload: Map<String, Any?> = when (op) {
            "c" -> linkedMapOf("op" to "c", "after" to (projectedAfter ?: error("op=c with null after")))
            "u" -> linkedMapOf(
                "op" to "u",
                "before" to (projectedBefore ?: error("op=u with null before")),
                "after"  to (projectedAfter  ?: error("op=u with null after")),
            )
            "d" -> linkedMapOf(
                "op" to "d",
                "before" to (projectedBefore ?: error("op=d with null before")),
            )
            "r" -> linkedMapOf(
                "op" to "r",
                "after" to (projectedAfter ?: error("op=r with null after")),
            )
            else -> throw UnsupportedOpException(op, key)
        }

        // Postgres LSN: Debezium reports as Long; Postgres canonical form is "high32hex/low32hex".
        // ushr is unsigned right shift; AND with 0xFFFFFFFFL extracts the low 32 bits.
        // takeIf { it.isNumber } guards both MissingNode (field absent) and NullNode (field null).
        val lsnLong = source.path("lsn").takeIf { it.isNumber }?.asLong()
        val lsnString = lsnLong?.let { "%X/%X".format(it ushr 32, it and 0xFFFFFFFFL) }
        // Debezium 3.x Postgres source field is `txId` (camelCase) — not `txid`.
        val txid = source.path("txId").takeIf { it.isNumber }?.asLong()

        val fingerprint = SourceSchemaFingerprinter.snapshot(envelope).fingerprint
        val cdcSourceContext = buildCdcSourceContext(source, fingerprint, lsnString, txid)

        // SelfDescribingJson wraps the payload with its Iglu schema URI.
        // SelfDescribing.builder() is the Snowplow Java tracker 2.x fluent builder for
        // unstructured (self-describing) events — the inner SelfDescribing is then wrapped in a
        // DeterministicEvent so the eid the tracker stamps gets overwritten with our UUIDv5.
        val inner = SelfDescribing.builder()
            .eventData(SelfDescribingJson(tableConfig.igluSchema, tablePayload))
            .customContext(listOf(cdcSourceContext))
            .trueTimestamp(sourceTsMs)
            .build()

        // Build the per-event coordinates the deterministic event_id depends on. PK values come
        // from the post-state for c/u/r, the pre-state for d (the only state that exists for a
        // delete). PK values: post-state for c/u/r, pre-state for d. Canonicality is enforced by
        // EventIdGenerator's sorted-key mapper.
        val pkSourceMap = when (op) {
            "d" -> beforeMap!!
            else -> afterMap!!
        }
        val pkValues = LinkedHashMap<String, Any?>().apply {
            for (col in tableConfig.primaryKey) put(col, pkSourceMap[col])
        }

        val eventId = eventIdGenerator.generate(
            connector = connectorName,
            db = source.path("db").asText(),
            schema = source.path("schema").asText().ifEmpty { null },
            table = source.path("table").asText(),
            op = op,
            lsn = lsnString,
            txid = txid,
            pkValues = pkValues,
        )

        return AssembledEvent(DeterministicEvent(inner, eventId), key, op)
    }

    /**
     * Builds the cdc_source self-describing context entity from the change event's `source` node
     * plus a precomputed column fingerprint. See spec §6 for field-population decisions.
     */
    private fun buildCdcSourceContext(source: JsonNode, fingerprint: String, lsnString: String?, txid: Long?): SelfDescribingJson {
        // Debezium source.snapshot ∈ {"false", "true", "last", "incremental"}. Anything other
        // than "false" means this row came from a snapshot read, not live streaming.
        val isSnapshot = source.path("snapshot").asText("false") != "false"

        // LinkedHashMap preserves insertion order — the SelfDescribingJson serializes deterministically.
        val data = linkedMapOf<String, Any?>(
            "connector"          to connectorName,
            "db"                 to source.path("db").asText(),
            "schema"             to source.path("schema").asText().ifEmpty { null },
            "table"              to source.path("table").asText(),
            "lsn"                to lsnString,
            // output key stays snake_case `txid` to match the cdc_source Iglu schema.
            "txid"               to txid,
            "is_snapshot"        to isSnapshot,
            "ts_ms"              to source.path("ts_ms").asLong(),
            "column_fingerprint" to fingerprint,
        )
        return SelfDescribingJson(cdcSourceSchemaUri, data)
    }

    /**
     * Convert a Debezium `before` or `after` JSON node to a plain Map, returning null when the
     * node is JSON null or missing (e.g., `before` on an INSERT). Use Jackson's convertValue
     * rather than re-parsing JSON text — same tree-to-pojo mechanism, no string round-trip.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readRowMap(node: JsonNode): Map<String, Any?>? {
        if (!node.isObject) return null
        // convertValue returns Map<*,*>; Debezium row JSON guarantees String keys, so the cast is safe.
        return mapper.convertValue(node, Map::class.java).let { it as Map<String, Any?> }
    }

    // `companion object` is the class-level static namespace. defaultMapper() is only called
    // once per PayloadAssembler instance (used as a default parameter) so no caching is needed.
    companion object {
        private fun defaultMapper(): ObjectMapper =
            ObjectMapper().registerKotlinModule()
    }
}
