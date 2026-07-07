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

import com.snowplowanalytics.cdc.emitter.Emitter
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeartbeatScheduler(
    private val intervalMs: Long,
    private val counters: Counters,
    private val probe: ReadinessProbe,
    private val emitter: Emitter,
) {
    private val log = LoggerFactory.getLogger("Heartbeat")
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cdc-heartbeat").apply { isDaemon = true }
    }

    fun start() {
        // Use a short initial delay so operators see a "service alive" signal quickly.
        // At the 60s production default the first heartbeat fires after 5s rather than 60s;
        // at test intervals (e.g. 50ms) the initial delay equals the period — unchanged.
        val initialDelay = intervalMs.coerceAtMost(5_000L)
        executor.scheduleAtFixedRate({
            try {
                val snap = counters.snapshot(emitter.bufferUsed())
                val line = HeartbeatFormatter.format(snap, probe.isReady())
                log.info(
                    "heartbeat",
                    *line.entries.map { kv(it.key, it.value) }.toTypedArray(),
                )
            } catch (t: Throwable) {
                // Catch Throwable so a transient log/snapshot failure does not cancel future heartbeat ticks.
                // ScheduledExecutorService.scheduleAtFixedRate cancels the schedule if the task throws — we
                // explicitly defeat that contract so the scheduler self-heals across blips.
                log.error("heartbeat tick failed", t)
            }
        }, initialDelay, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        executor.shutdownNow()
    }
}
