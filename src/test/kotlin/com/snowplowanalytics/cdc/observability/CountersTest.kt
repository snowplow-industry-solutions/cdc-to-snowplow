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
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountersTest {

    @Test
    fun `default Counters has all AtomicLongs at zero and empty per-table-op map`() {
        val counters = Counters(bufferCapacity = 1000)

        counters.received.get() shouldBe 0L
        counters.emitted.get() shouldBe 0L
        counters.dropped.get() shouldBe 0L
        counters.emitterPending.get() shouldBe 0L

        val snap = counters.snapshot()
        snap.perTable.shouldBeEmpty()
    }

    @Test
    fun `incrementing received shows in snapshot`() {
        val counters = Counters(bufferCapacity = 1000)
        counters.received.incrementAndGet()

        val snap = counters.snapshot()
        snap.received shouldBe 1L
    }

    @Test
    fun `incPerTableOp increments per-table map correctly`() {
        val counters = Counters(bufferCapacity = 1000)
        val key = TableKey("public", "orders")

        repeat(3) { counters.incPerTableOp(key, "c") }

        val snap = counters.snapshot()
        snap.perTable shouldContainKey "public.orders"
        snap.perTable["public.orders"]!!["c"] shouldBe 3L
    }

    @Test
    fun `concurrent received increments are thread-safe`() {
        val counters = Counters(bufferCapacity = 1000)
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.submit {
                repeat(1000) { counters.received.incrementAndGet() }
            }
        }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        counters.snapshot().received shouldBe 8000L
    }

    @Test
    fun `concurrent incPerTableOp calls are thread-safe`() {
        val counters = Counters(bufferCapacity = 1000)
        val key = TableKey("public", "orders")
        val executor = Executors.newFixedThreadPool(4)

        repeat(4) {
            executor.submit {
                repeat(1000) { counters.incPerTableOp(key, "c") }
            }
        }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        val snap = counters.snapshot()
        snap.perTable["public.orders"]!!["c"] shouldBe 4000L
    }

    @Test
    fun `snapshot returns immutable view - live counter changes do not affect prior snapshot`() {
        val counters = Counters(bufferCapacity = 1000)
        counters.received.set(5L)
        counters.incPerTableOp(TableKey("public", "orders"), "u")

        val snap = counters.snapshot()
        snap.received shouldBe 5L
        snap.perTable["public.orders"]!!["u"] shouldBe 1L

        // Mutate live counters after taking snapshot
        counters.received.set(999L)
        counters.incPerTableOp(TableKey("public", "orders"), "u")

        // Snapshot values must remain unchanged
        snap.received shouldBe 5L
        snap.perTable["public.orders"]!!["u"] shouldBe 1L
    }

    @Test
    fun `snapshot uptimeSeconds is non-negative and within expected bounds`() {
        val beforeMs = System.currentTimeMillis()
        val counters = Counters(bufferCapacity = 1000)
        val snap = counters.snapshot()
        val afterMs = System.currentTimeMillis()

        snap.uptimeSeconds shouldBeGreaterThanOrEqualTo 0L
        // Loose upper bound: (elapsed ms / 1000) + 1
        val upperBound = (afterMs - beforeMs) / 1000L + 1L
        snap.uptimeSeconds shouldBeLessThanOrEqualTo upperBound
    }
}
