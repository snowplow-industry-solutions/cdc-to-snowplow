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

package com.snowplowanalytics.cdc.emitter

import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.snowplow.tracker.Tracker
import com.snowplowanalytics.snowplow.tracker.configuration.EmitterConfiguration
import com.snowplowanalytics.snowplow.tracker.configuration.NetworkConfiguration
import com.snowplowanalytics.snowplow.tracker.configuration.TrackerConfiguration
import com.snowplowanalytics.snowplow.tracker.emitter.BatchEmitter
import com.snowplowanalytics.snowplow.tracker.emitter.EmitterCallback
import com.snowplowanalytics.snowplow.tracker.emitter.FailureType
import com.snowplowanalytics.snowplow.tracker.events.Event
import com.snowplowanalytics.snowplow.tracker.payload.TrackerPayload
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Production [Emitter] backed by the Snowplow Java tracker 2.x with block-on-overflow semantics.
 *
 * The tracker's default behaviour is drop-on-overflow: when its internal [InMemoryEventStore]
 * is full, `addEvent()` returns false and the event is silently lost. This wrapper installs a
 * [Semaphore] of [bufferCapacity] permits in front of the tracker. [emit] acquires one permit
 * before calling `tracker.track()`; a registered [EmitterCallback] releases permits when batches
 * terminate (success or non-retried failure). When the collector is down, the tracker retries
 * internally; while it retries, our callback is *not* invoked, permits stay held, and [emit]
 * blocks — backpressure propagates through Debezium's `handleRecord` loop back to the Postgres
 * WAL.
 *
 * The semaphore capacity equals the tracker's internal buffer capacity (1:1). Because the
 * semaphore caps how many events we admit, the tracker's `InMemoryEventStore` cannot overflow.
 *
 * Cooperative close: [close] sets a volatile [closed] flag, flood-releases the semaphore so
 * any blocked acquirer wakes immediately, then closes the underlying tracker. Acquirers that
 * wake after the flag is set throw [EmitterClosedException]. Thread interrupt is treated
 * identically — the wrapper preserves the interrupt flag and throws [EmitterClosedException]
 * so callers see one type for "shutdown happened mid-emit."
 *
 * Operator-visible logging:
 *   - WARN, once: "emitter buffer full; backpressure engaged" the first time an acquire blocks
 *     for more than 100ms.
 *   - INFO, once-per-engagement: "emitter buffer drained; backpressure released" the next time
 *     [Semaphore.availablePermits] returns to [bufferCapacity].
 *
 * [counters] mirrors the semaphore's in-flight count for observability. [emit] increments
 * [Counters.emitterPending] after acquiring a permit; [release] decrements it alongside the
 * semaphore release. The counter is the source for [bufferUsed], which feeds the heartbeat log.
 *
 * Note: okhttp is declared as an explicit `implementation` dependency in build.gradle.kts because
 * the Snowplow Java tracker 2.1.0 marks it as `provided` (compile-only) — without the explicit
 * dep, the HTTP calls fail at runtime with a ClassNotFoundException.
 */
class SnowplowEmitter(
    collectorUrl: String,
    namespace: String,
    appId: String,
    batchSize: Int = 1,
    bufferCapacity: Int = 1_000,
    private val counters: Counters,
) : Emitter {

    private val log = LoggerFactory.getLogger(SnowplowEmitter::class.java)
    private val capacity: Int = bufferCapacity
    private val semaphore: Semaphore = Semaphore(bufferCapacity, /* fair = */ true)

    // @Volatile so writes in close() are immediately visible to acquirers waking on flood-release.
    @Volatile private var closed: Boolean = false

    // Single-flag state machine for log-once-per-engagement. `false` means "currently not in
    // backpressure" (the last engaged-state we logged is "released"). Flipped to true when we
    // log the WARN; flipped back to false when permits return to capacity and we log the INFO.
    @Volatile private var backpressureLogged: Boolean = false

    private val callback = object : EmitterCallback {
        override fun onSuccess(payloads: MutableList<TrackerPayload>) {
            release(payloads.size)
        }

        override fun onFailure(
            failureType: FailureType,
            willRetry: Boolean,
            payloads: MutableList<TrackerPayload>,
        ) {
            // Permits release only on terminal outcomes. While willRetry is true the tracker
            // re-inserts payloads at the head of its own buffer and continues retrying; we
            // keep the semaphore at zero so emit() stays blocked until the collector recovers.
            if (!willRetry) release(payloads.size)
        }
    }

    private val tracker: Tracker = Tracker(
        TrackerConfiguration(namespace, appId),
        BatchEmitter(
            NetworkConfiguration(collectorUrl),
            EmitterConfiguration()
                .batchSize(batchSize)
                // Semaphore is the gate; we admit at most `capacity` events to the tracker.
                // Matching the tracker's bufferCapacity to ours means the tracker's own store
                // can never overflow. Single operator-visible knob.
                .bufferCapacity(bufferCapacity)
                .callback(callback),
        ),
    )

    override fun emit(event: Event) {
        // Fast-fail: emit() after close() throws without contacting the tracker. Avoids
        // calling track() on a tracker whose internals are shutting down.
        if (closed) throw EmitterClosedException()

        // Interruptible acquire. tryAcquire-with-timeout lets us detect the "blocked for >100ms"
        // transition for the operator-visible WARN log without sampling availablePermits in a
        // tight loop.
        val acquired = try {
            semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EmitterClosedException("emit interrupted")
        }

        if (!acquired) {
            // Log once per engagement; subsequent slow acquires within the same engagement
            // do not re-log. `backpressureLogged` is reset when permits return to capacity.
            if (!backpressureLogged) {
                backpressureLogged = true
                log.warn("emitter buffer full; backpressure engaged")
            }
            // Now do the unbounded interruptible wait.
            try {
                semaphore.acquire()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw EmitterClosedException("emit interrupted")
            }
        }

        // Either tryAcquire returned true (no engagement), or acquire() above returned. Either
        // way, we hold one permit. If close() flooded the semaphore in the meantime, the closed
        // flag is set — recheck and bail without sending. The permit we just took is not
        // returned because flood-release added Integer.MAX_VALUE / 2 permits; one missing one
        // is irrelevant and the tracker's about to be closed anyway.
        if (closed) throw EmitterClosedException()

        // Increment the observability counter to mirror the just-acquired permit. Increment
        // BEFORE track() so the counter is never transiently smaller than the in-flight set.
        counters.emitterPending.incrementAndGet()
        tracker.track(event)
    }

    override fun close() {
        closed = true
        // Wake every possible blocked acquirer. Integer.MAX_VALUE / 2 is chosen so successive
        // close() calls (defensive) cannot overflow the semaphore's internal counter.
        semaphore.release(Int.MAX_VALUE / 2)
        tracker.close()
    }

    // Returns how many events are currently in flight, clamped to [0, bufferCapacity]. Reads
    // from the counter rather than the semaphore so tests can preset [Counters.emitterPending]
    // without driving the full emit lifecycle; in production the two stay in lockstep because
    // [emit] and [release] update both together.
    override fun bufferUsed(): Int =
        counters.emitterPending.get().toInt().coerceIn(0, capacity)

    private fun release(n: Int) {
        semaphore.release(n)
        counters.emitterPending.addAndGet(-n.toLong())
        // INFO when permits return to capacity. The `if` guards against duplicate INFO lines
        // when several batches complete in quick succession.
        if (backpressureLogged && semaphore.availablePermits() >= capacity) {
            backpressureLogged = false
            log.info("emitter buffer drained; backpressure released")
        }
    }
}
