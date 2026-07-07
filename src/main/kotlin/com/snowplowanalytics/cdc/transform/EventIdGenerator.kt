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
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Builds a deterministic UUIDv5 event_id from the change-event coordinates documented in the
 * Slice 4 design doc and parent design §4. Two events that share the same
 * `(connector, db, schema, table, op, lsn, txid, primary_key)` produce the same UUID; downstream
 * Snowplow deduplication uses the UUID to fold replays.
 *
 * The canonical input string is a JSON object with sorted keys (Jackson's
 * ORDER_MAP_ENTRIES_BY_KEYS); nulls render as JSON `null` so the canonical bytes are stable across
 * the various null-coordinate cases (snapshot reads with null txid, non-Postgres connectors with
 * null lsn, etc.).
 *
 * The namespace UUID is a project-specific constant minted at design time. Changing it would
 * invalidate every previously-generated event_id; the pin test in EventIdGeneratorTest guards
 * against accidental changes here AND in Jackson's serialization defaults.
 */
class EventIdGenerator(
    private val namespace: UUID = EVENT_ID_NAMESPACE,
    private val mapper: ObjectMapper = canonicalMapper(),
) {

    fun generate(
        connector: String,
        db: String,
        schema: String?,
        table: String,
        op: String,
        lsn: String?,
        txid: Long?,
        pkValues: Map<String, Any?>,
    ): UUID {
        val canonical = linkedMapOf(
            "connector" to connector,
            "db"        to db,
            "schema"    to schema,
            "table"     to table,
            "op"        to op,
            "lsn"       to lsn,
            "txid"      to txid,
            "pk"        to pkValues,
        )
        val name = mapper.writeValueAsString(canonical)
        return uuidV5(namespace, name)
    }

    companion object {
        // Minted via `uuidgen` at design time. See spec §5; the `c0de`/`4cdc` middle words make the
        // value visually identifiable as a tool-specific constant in code review. Don't change.
        val EVENT_ID_NAMESPACE: UUID = UUID.fromString("8a1f4e5c-c0de-4cdc-a000-000000000001")

        /**
         * UUIDv5 per RFC 4122 §4.3: SHA-1 over (namespace_bytes || name_bytes), keep the first
         * 16 bytes, set the version (top 4 bits of byte 6) to 5, and the RFC-4122 variant
         * (top 2 bits of byte 8) to 10.
         */
        private fun uuidV5(namespace: UUID, name: String): UUID {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(uuidToBytes(namespace))
            md.update(name.toByteArray(Charsets.UTF_8))
            val hash = md.digest().copyOf(16)
            hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()  // version 5
            hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()  // variant 10
            val bb = ByteBuffer.wrap(hash)
            return UUID(bb.long, bb.long)
        }

        private fun uuidToBytes(uuid: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            return bb.array()
        }

        private fun canonicalMapper(): ObjectMapper =
            ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    }
}
