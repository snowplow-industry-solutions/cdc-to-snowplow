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

import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class HeartbeatFormatterTest {

    private fun defaultSnapshot(
        received: Long = 0L,
        emitted: Long = 0L,
        dropped: Long = 0L,
        bufferUsed: Int = 0,
        bufferCapacity: Int = 1000,
        perTable: Map<String, Map<String, Long>> = emptyMap(),
        uptimeSeconds: Long = 0L,
    ) = CounterSnapshot(
        received = received,
        emitted = emitted,
        dropped = dropped,
        bufferUsed = bufferUsed,
        bufferCapacity = bufferCapacity,
        perTable = perTable,
        uptimeSeconds = uptimeSeconds,
    )

    @Test
    fun `format returns map with exact key set and event=heartbeat and ready=false`() {
        val snap = defaultSnapshot()
        val result = HeartbeatFormatter.format(snap, ready = false)

        val expectedKeys = setOf(
            "event", "received", "emitted", "dropped",
            "buffer_used", "buffer_capacity", "per_table",
            "uptime_seconds", "ready",
        )
        result.keys shouldBe expectedKeys
        result["event"] shouldBe "heartbeat"
        result["ready"] shouldBe false
    }

    @Test
    fun `format with non-empty per_table passes through nested structure`() {
        val perTable = mapOf(
            "public.orders" to mapOf("c" to 5L, "u" to 2L),
            "public.users" to mapOf("d" to 1L),
        )
        val snap = defaultSnapshot(perTable = perTable)
        val result = HeartbeatFormatter.format(snap, ready = true)

        @Suppress("UNCHECKED_CAST")
        val resultPerTable = result["per_table"] as Map<String, Map<String, Long>>
        resultPerTable shouldBe perTable
    }

    @Test
    fun `all numeric counters round-trip as Long or Int not as String`() {
        val snap = defaultSnapshot(
            received = 10L,
            emitted = 8L,
            dropped = 2L,
            bufferUsed = 3,
            bufferCapacity = 100,
            uptimeSeconds = 42L,
        )
        val result = HeartbeatFormatter.format(snap, ready = true)

        result["received"].shouldBeInstanceOf<Long>()
        result["emitted"].shouldBeInstanceOf<Long>()
        result["dropped"].shouldBeInstanceOf<Long>()
        result["uptime_seconds"].shouldBeInstanceOf<Long>()
        // buffer_used and buffer_capacity are Int in CounterSnapshot
        result["buffer_used"].shouldBeInstanceOf<Number>()
        result["buffer_capacity"].shouldBeInstanceOf<Number>()

        result["received"] shouldBe 10L
        result["emitted"] shouldBe 8L
        result["dropped"] shouldBe 2L
        result["uptime_seconds"] shouldBe 42L
        result["buffer_used"] shouldBe 3
        result["buffer_capacity"] shouldBe 100
    }
}
