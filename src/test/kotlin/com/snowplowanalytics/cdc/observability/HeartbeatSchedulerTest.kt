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
import com.snowplowanalytics.snowplow.tracker.events.Event
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HeartbeatSchedulerTest {

    private var scheduler: HeartbeatScheduler? = null

    @AfterEach
    fun teardown() {
        scheduler?.close()
    }

    /** A minimal fake emitter that always reports a fixed bufferUsed value. */
    private fun fakeEmitter(bufferUsed: Int): Emitter = object : Emitter {
        override fun emit(event: Event) {}
        override fun close() {}
        override fun bufferUsed(): Int = bufferUsed
    }

    @Test
    fun `scheduler fires at least 2 ticks in 400ms at 50ms interval and log messages contain event=heartbeat`() {
        // Attach a Logback list-appender to the "Heartbeat" logger
        val context = org.slf4j.LoggerFactory.getILoggerFactory()
            as ch.qos.logback.classic.LoggerContext
        val listAppender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        listAppender.context = context
        listAppender.start()
        val logger = context.getLogger("Heartbeat")
        logger.addAppender(listAppender)
        logger.level = ch.qos.logback.classic.Level.INFO

        try {
            val counters = Counters(bufferCapacity = 1000)
            val probe = ReadinessProbe()
            val emitter = fakeEmitter(bufferUsed = 7)

            scheduler = HeartbeatScheduler(
                intervalMs = 50L,
                counters = counters,
                probe = probe,
                emitter = emitter,
            )
            scheduler!!.start()

            // Wait 400ms — enough for ≥2 ticks even on slow CI
            TimeUnit.MILLISECONDS.sleep(400)

            val events = listAppender.list
            events.size shouldBeGreaterThanOrEqualTo 2

            // Inspect the first event's structured arguments for event=heartbeat and buffer_used=7
            val firstEvent = events[0]
            val args = firstEvent.argumentArray

            // Find the StructuredArgument with key "event" and value "heartbeat"
            val eventArg = args.filterIsInstance<net.logstash.logback.argument.StructuredArgument>()
                .firstOrNull { it.toString().startsWith("event=") }
            eventArg?.toString() shouldBe "event=heartbeat"

            // Find the StructuredArgument with key "buffer_used" and value 7
            val bufferUsedArg = args.filterIsInstance<net.logstash.logback.argument.StructuredArgument>()
                .firstOrNull { it.toString().startsWith("buffer_used=") }
            bufferUsedArg?.toString() shouldBe "buffer_used=7"
        } finally {
            logger.detachAppender(listAppender)
            listAppender.stop()
        }
    }
}
