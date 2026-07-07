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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.testutil.StubCollector
import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class SnowplowEmitterTest {

    private fun makeEvent(i: Int): SelfDescribing =
        SelfDescribing.builder()
            .eventData(
                SelfDescribingJson(
                    "iglu:com.example/test/jsonschema/1-0-0",
                    mapOf("i" to i),
                )
            )
            .build()

    @Test fun `emit blocks when buffer is full`() {
        StubCollector().use { stub ->
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 2,
                counters = Counters(bufferCapacity = 2),
            )
            try {
                // Fill the buffer.
                emitter.emit(makeEvent(1))
                emitter.emit(makeEvent(2))

                // Third call must block — the stub is parked and never ACKs.
                val producer = Thread { emitter.emit(makeEvent(3)) }.apply { start() }
                producer.join(500)
                producer.isAlive shouldBe true

                stub.unblock()
                producer.join(2000)
                producer.isAlive shouldBe false
            } finally {
                emitter.close()
            }
        }
    }

    @Test fun `close releases a blocked emit with EmitterClosedException`() {
        StubCollector().use { stub ->
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 1,
                counters = Counters(bufferCapacity = 1),
            )
            emitter.emit(makeEvent(1))

            val thrown = AtomicReference<Throwable?>(null)
            val producer = Thread {
                try {
                    emitter.emit(makeEvent(2))
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }.apply { start() }

            // Give the producer time to enter acquire().
            producer.join(200)
            producer.isAlive shouldBe true

            emitter.close()

            producer.join(2000)
            producer.isAlive shouldBe false
            thrown.get() should { it is EmitterClosedException }
        }
    }

    @Test fun `emit after close throws EmitterClosedException without contacting tracker`() {
        StubCollector().use { stub ->
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 4,
                counters = Counters(bufferCapacity = 4),
            )
            emitter.close()

            assertThrows<EmitterClosedException> { emitter.emit(makeEvent(1)) }
        }
    }

    @Test fun `interrupt during acquire surfaces as EmitterClosedException with interrupt flag preserved`() {
        StubCollector().use { stub ->
            val emitter = SnowplowEmitter(
                collectorUrl = stub.url,
                namespace = "test",
                appId = "test",
                batchSize = 1,
                bufferCapacity = 1,
                counters = Counters(bufferCapacity = 1),
            )
            try {
                emitter.emit(makeEvent(1))

                val thrown = AtomicReference<Throwable?>(null)
                val wasInterrupted = AtomicReference<Boolean?>(null)
                val producer = Thread {
                    try {
                        emitter.emit(makeEvent(2))
                    } catch (t: Throwable) {
                        thrown.set(t)
                        wasInterrupted.set(Thread.currentThread().isInterrupted)
                    }
                }.apply { start() }

                producer.join(200)
                producer.isAlive shouldBe true

                producer.interrupt()
                producer.join(2000)

                thrown.get() should { it is EmitterClosedException }
                wasInterrupted.get() shouldBe true
            } finally {
                emitter.close()
            }
        }
    }

    @Test fun `WARN logged on first backpressure engagement, INFO on release`() {
        val (logger, appender) = attachListAppender("com.snowplowanalytics.cdc.emitter.SnowplowEmitter")
        try {
            StubCollector().use { stub ->
                val emitter = SnowplowEmitter(
                    collectorUrl = stub.url,
                    namespace = "test",
                    appId = "test",
                    batchSize = 1,
                    bufferCapacity = 2,
                    counters = Counters(bufferCapacity = 2),
                )
                try {
                    emitter.emit(makeEvent(1))
                    emitter.emit(makeEvent(2))

                    val producer = Thread { emitter.emit(makeEvent(3)) }.apply { start() }
                    producer.join(500)
                    producer.isAlive shouldBe true

                    val warns = appender.list.filter { it.level == Level.WARN }
                    warns shouldHaveSize 1
                    warns.first().formattedMessage shouldContain "backpressure engaged"

                    stub.unblock()
                    producer.join(2000)

                    // Wait for permits to drain — give tracker callback time to fire.
                    Thread.sleep(500)

                    val infos = appender.list.filter { it.level == Level.INFO }
                    infos.any { it.formattedMessage.contains("backpressure released") } shouldBe true
                } finally {
                    emitter.close()
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ---- helpers ----

    private fun attachListAppender(loggerName: String): Pair<Logger, ListAppender<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        logger.level = Level.TRACE
        return logger to appender
    }
}

// kotest's `should` infix with a lambda predicate
private infix fun <T> T.should(block: (T) -> Boolean): T = also {
    require(block(it)) { "Expected condition to hold for: $it" }
}
