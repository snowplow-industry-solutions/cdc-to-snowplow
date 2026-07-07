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

package com.snowplowanalytics.cdc.engine

import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.transform.SourceSchemaSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * State machine that detects column-fingerprint transitions per source table.
 *
 * Semantics (see slice spec §5):
 *  - The first event for a table is recorded silently and returns `null`. Cold-start does not log.
 *  - Subsequent events with the same fingerprint also return `null`.
 *  - A differing fingerprint returns a [SchemaChange] describing the transition; the caller (engine
 *    layer in Task 4) owns the log emission. This class never calls `log.*` itself.
 *  - Op-agnostic: snapshot reads (`op=r`) and streaming changes go through the same path. We
 *    fingerprint the *schema*, not the *operation*, so distinguishing ops would create false
 *    negatives at the read-to-stream boundary.
 *
 * Backed by [ConcurrentHashMap] because Debezium's notifying callback can fan out across worker
 * threads in some configurations; `put` is an atomic put-and-return-previous, which avoids the
 * `containsKey` + `get` + `put` race. In production this is called on the single Debezium engine
 * thread, which is what guarantees per-table transition ordering; under hypothetical concurrent
 * callers no state is lost, but the order of returned [SchemaChange] records is not deterministic.
 */
class DdlChangeDetector {

    private val lastSeen: ConcurrentMap<TableKey, SourceSchemaSnapshot> = ConcurrentHashMap()

    fun observe(table: TableKey, snapshot: SourceSchemaSnapshot): SchemaChange? {
        // put() atomically writes and returns the prior value (or null), avoiding a read-then-write race.
        val previous = lastSeen.put(table, snapshot)
        if (previous == null) return null
        if (previous.fingerprint == snapshot.fingerprint) return null

        val previousCols = previous.columnNames.toSet()
        val currentCols = snapshot.columnNames.toSet()
        return SchemaChange(
            table = table.toString(),
            previousFingerprint = previous.fingerprint,
            currentFingerprint = snapshot.fingerprint,
            addedColumns = (currentCols - previousCols).sorted(),
            removedColumns = (previousCols - currentCols).sorted(),
        )
    }
}
