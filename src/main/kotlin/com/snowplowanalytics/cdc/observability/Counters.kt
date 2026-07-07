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

package com.snowplowanalytics.cdc.observability

import com.snowplowanalytics.cdc.config.TableKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Counters(val bufferCapacity: Int) {
    val received = AtomicLong()
    val emitted = AtomicLong()
    val dropped = AtomicLong()
    val emitterPending = AtomicLong()
    private val startedAtMillis = System.currentTimeMillis()
    private val perTableOp = ConcurrentHashMap<TableKey, ConcurrentHashMap<String, AtomicLong>>()

    fun incPerTableOp(key: TableKey, op: String) {
        perTableOp.computeIfAbsent(key) { ConcurrentHashMap() }
            .computeIfAbsent(op) { AtomicLong() }
            .incrementAndGet()
    }

    fun snapshot(): CounterSnapshot =
        snapshot(emitterPending.get().toInt().coerceIn(0, bufferCapacity))

    fun snapshot(bufferUsed: Int): CounterSnapshot {
        // Build immutable copies of the per-table-op map at this instant
        val perTableSnapshot: Map<String, Map<String, Long>> = perTableOp
            .entries
            .associate { (tableKey, opMap) ->
                tableKey.toString() to opMap.entries.associate { (op, count) -> op to count.get() }
            }

        val uptimeSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000L

        return CounterSnapshot(
            received = received.get(),
            emitted = emitted.get(),
            dropped = dropped.get(),
            bufferUsed = bufferUsed,
            bufferCapacity = bufferCapacity,
            perTable = perTableSnapshot,
            uptimeSeconds = uptimeSeconds,
        )
    }
}

data class CounterSnapshot(
    val received: Long,
    val emitted: Long,
    val dropped: Long,
    val bufferUsed: Int,
    val bufferCapacity: Int,
    val perTable: Map<String, Map<String, Long>>,
    val uptimeSeconds: Long,
)
