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

import io.javalin.Javalin

class ObservabilityServer(
    private val port: Int,
    private val readinessProbe: ReadinessProbe,
) {
    private val app: Javalin = Javalin.create { config ->
        config.showJavalinBanner = false
    }

    fun start(): Int {
        app.get("/health") { ctx ->
            ctx.status(200).json(mapOf("status" to "ok"))
        }
        app.get("/ready") { ctx ->
            val r = readinessProbe.readyResponse()
            ctx.status(r.httpCode).json(r.body)
        }
        app.start(port)
        return app.port()
    }

    fun close() {
        app.stop()
    }
}
