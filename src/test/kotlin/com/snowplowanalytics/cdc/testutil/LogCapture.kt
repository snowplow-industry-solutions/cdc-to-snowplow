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
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Runs [block] with a fresh Logback [ListAppender] attached to [loggerName] (the root logger by
 * default), then ALWAYS detaches and stops it in a `finally` — even if [block] throws.
 *
 * This is the exception-safe replacement for the attach-in-`@BeforeEach` / detach-in-`@AfterEach`
 * pattern (issue #25): if setup threw after `addAppender` but before completion, JUnit never ran
 * `@AfterEach` and the appender leaked onto the logger for the rest of the JVM run, polluting later
 * test classes. Wrapping the capture window in this helper guarantees detach regardless of outcome.
 *
 * [level] optionally raises/lowers the logger's level for the duration and restores it afterwards —
 * use it when the captured logger is otherwise above the level you need to observe.
 */
fun <T> withCapturedLogs(
    loggerName: String = Logger.ROOT_LOGGER_NAME,
    level: Level? = null,
    block: (ListAppender<ILoggingEvent>) -> T,
): T {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val appender = ListAppender<ILoggingEvent>().apply {
        this.context = context
        start()
    }
    val logger = context.getLogger(loggerName)
    val previousLevel = logger.level
    logger.addAppender(appender)
    if (level != null) logger.level = level
    try {
        return block(appender)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
        if (level != null) logger.level = previousLevel
    }
}
