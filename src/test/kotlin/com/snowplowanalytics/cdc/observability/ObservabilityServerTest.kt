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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ObservabilityServerTest {

    private lateinit var probe: ReadinessProbe
    private lateinit var server: ObservabilityServer
    private var port: Int = 0

    private val client = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        probe = ReadinessProbe()
        server = ObservabilityServer(port = 0, readinessProbe = probe)
        port = server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `GET health returns 200 with status ok regardless of probe state`() {
        val resp = get("/health")
        resp.statusCode() shouldBe 200
        val body = mapper.readValue<Map<String, String>>(resp.body())
        body["status"] shouldBe "ok"
    }

    @Test
    fun `GET ready with default probe state returns 503 with reason engine_not_started`() {
        val resp = get("/ready")
        resp.statusCode() shouldBe 503
        val body = mapper.readValue<Map<String, String>>(resp.body())
        body["reason"] shouldBe "engine_not_started"
    }

    @Test
    fun `GET ready after markEmitterReady and markEngineReady returns 200 with status ready`() {
        probe.markEmitterReady()
        probe.markEngineReady()

        val resp = get("/ready")
        resp.statusCode() shouldBe 200
        val body = mapper.readValue<Map<String, String>>(resp.body())
        body["status"] shouldBe "ready"
    }

    @Test
    fun `GET ready after markEngineFailed returns 503 with reason engine_failed`() {
        probe.markEmitterReady()
        probe.markEngineReady()
        probe.markEngineFailed()

        val resp = get("/ready")
        resp.statusCode() shouldBe 503
        val body = mapper.readValue<Map<String, String>>(resp.body())
        body["reason"] shouldBe "engine_failed"
    }

    @Test
    fun `close makes subsequent requests fail to connect`() {
        // Confirm the server is up before closing
        get("/health").statusCode() shouldBe 200

        server.close()

        var threw = false
        try {
            get("/health")
        } catch (e: Throwable) {
            // ConnectException is typically wrapped in an IOException by the JDK HTTP client
            threw = true
        }
        threw shouldBe true
    }

    // ---- helpers ----

    private fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
