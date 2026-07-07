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

package com.snowplowanalytics.cdc.testutil

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LogCaptureTest {

    private fun attachedAppenderCount(loggerName: String): Int {
        val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(loggerName)
        var count = 0
        val it = logger.iteratorForAppenders()
        while (it.hasNext()) { it.next(); count++ }
        return count
    }

    @Test
    fun `captures events emitted during the block`() {
        val loggerName = "log-capture-test-happy"
        val logger = LoggerFactory.getLogger(loggerName)

        val messages = withCapturedLogs(loggerName, Level.INFO) { appender ->
            logger.info("hello")
            logger.info("world")
            appender.list.map { it.formattedMessage }
        }

        messages shouldBe listOf("hello", "world")
    }

    @Test
    fun `detaches the appender even when the block throws`() {
        // This is the regression guard for issue #25: the previous attach-in-BeforeEach /
        // detach-in-AfterEach pattern leaked the appender if setup threw after addAppender. The
        // finally in withCapturedLogs must detach regardless of how the block exits.
        val loggerName = "log-capture-test-throw"
        val before = attachedAppenderCount(loggerName)

        shouldThrow<IllegalStateException> {
            withCapturedLogs(loggerName) {
                throw IllegalStateException("boom")
            }
        }

        attachedAppenderCount(loggerName) shouldBe before
    }

    @Test
    fun `restores the logger level after the block`() {
        val loggerName = "log-capture-test-level"
        val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(loggerName)
        logger.level = Level.WARN

        withCapturedLogs(loggerName, Level.INFO) { /* no-op */ }

        logger.level shouldBe Level.WARN
    }
}
