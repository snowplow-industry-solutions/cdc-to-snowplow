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

import java.util.concurrent.atomic.AtomicBoolean

class ReadinessProbe {
    private val engineReady = AtomicBoolean(false)
    private val emitterReady = AtomicBoolean(false)
    private val engineFailed = AtomicBoolean(false)
    private val gracefulStop = AtomicBoolean(false)

    fun markEngineReady() { engineReady.set(true) }
    fun markEmitterReady() { emitterReady.set(true) }
    fun markEngineFailed() { if (!gracefulStop.get()) engineFailed.set(true) }
    fun noteGracefulStop() { gracefulStop.set(true) }

    fun isReady(): Boolean = engineReady.get() && emitterReady.get() && !engineFailed.get()

    fun readyResponse(): ReadinessResponse = when {
        engineFailed.get()    -> ReadinessResponse(503, mapOf("reason" to "engine_failed"))
        !engineReady.get()    -> ReadinessResponse(503, mapOf("reason" to "engine_not_started"))
        !emitterReady.get()   -> ReadinessResponse(503, mapOf("reason" to "emitter_not_started"))
        else                  -> ReadinessResponse(200, mapOf("status" to "ready"))
    }
}

data class ReadinessResponse(val httpCode: Int, val body: Map<String, String>)
