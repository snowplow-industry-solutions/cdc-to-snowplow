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

package com.snowplowanalytics.cdc.component

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ObservabilityServer
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.PostgresReplicationFixture
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

// Exercises the full observability lifecycle via real HTTP calls against ObservabilityServer.
// A real Postgres container is used so that EngineRunner can complete its preflight and launch
// the Debezium engine — the only path that triggers probe.markEngineReady().
@ExtendWith(RequiresDocker::class)
@Testcontainers
class ObservabilityEndpointsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    @TempDir
    lateinit var tmp: Path

    private val fixture = PostgresReplicationFixture(postgres)
    private lateinit var runner: EngineRunner
    private lateinit var probe: ReadinessProbe
    private lateinit var httpServer: ObservabilityServer
    private var httpPort: Int = 0

    private val client = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        fixture.dropReplicationSlotIfPresent()
        fixture.dropPublicationIfPresent()

        probe = ReadinessProbe()
        val emitter = RecordingEmitter()
        val offsetFile = Files.createTempFile("cdc-offsets", ".dat").toFile()
        offsetFile.delete()

        val configPath = tmp.resolve("config.yaml")
        configPath.writeText(
            """
            service:
              app_id: observability-test
            source:
              connector: postgres
              hostname: ${postgres.host}
              port: ${postgres.firstMappedPort}
              database: orders_db
              username: cdc
              password: cdc
              slot_name: snowplow_cdc
              publication_name: snowplow_cdc_pub
            debezium:
              snapshot_mode: never
              offset_store:
                type: file
                file_path: ${offsetFile.absolutePath}
              heartbeat_interval_ms: 0
              publication_autocreate_mode: filtered
              provide_transaction_metadata: false
            snowplow:
              collector_url: http://unused
              cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
            tables:
              - name: orders
                schema: public
                iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
                primary_key: [id]
                columns:
                  - id
                  - customer_id
                  - status
                  - total
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)
        runner = EngineRunner(config, emitter, Counters(bufferCapacity = 1000), probe)

        // Start the HTTP server on a random free port before starting the runner, so we can
        // verify the pre-start readiness state.
        httpServer = ObservabilityServer(port = 0, readinessProbe = probe)
        httpPort = httpServer.start()
    }

    @AfterEach
    fun tearDown() {
        // Best-effort cleanup in reverse order; individual failures are swallowed so all
        // cleanup steps run even if one throws.
        runCatching { runner.stop() }
        runCatching { httpServer.close() }
        runCatching { fixture.dropReplicationSlotIfPresent() }
        runCatching { fixture.dropPublicationIfPresent() }
    }

    @Test
    fun `health and ready transition through the full engine lifecycle`() {
        // --- Pre-start assertions ---
        // Health is always 200 (liveness) — server is up, engine hasn't started yet.
        val preHealth = get("/health")
        preHealth.statusCode() shouldBe 200
        mapper.readValue<Map<String, String>>(preHealth.body())["status"] shouldBe "ok"

        // Ready is 503 before the engine starts: emitter and engine both unready.
        val preReady = get("/ready")
        preReady.statusCode() shouldBe 503
        mapper.readValue<Map<String, String>>(preReady.body())["reason"] shouldBe "engine_not_started"

        // Mark the emitter ready manually (RecordingEmitter has no self-test; in production
        // the SnowplowEmitter calls this after a successful collector ping).
        probe.markEmitterReady()

        // After markEmitterReady only, still not ready: engine hasn't started yet.
        val afterEmitterReady = get("/ready")
        afterEmitterReady.statusCode() shouldBe 503
        mapper.readValue<Map<String, String>>(afterEmitterReady.body())["reason"] shouldBe "engine_not_started"

        // --- Start the engine ---
        // start() runs preflight synchronously then submits the Debezium engine to a background
        // executor. The ConnectorCallback.connectorStarted() fires asynchronously when Debezium
        // connects to Postgres — that's what flips engineReady and makes /ready return 200.
        runner.start()

        // --- Poll until /ready → 200 ---
        pollUntil(timeoutMs = 30_000) {
            get("/ready").statusCode() == 200
        }

        val steadyReady = get("/ready")
        steadyReady.statusCode() shouldBe 200
        mapper.readValue<Map<String, String>>(steadyReady.body())["status"] shouldBe "ready"

        // Health is still 200 during steady-state.
        val steadyHealth = get("/health")
        steadyHealth.statusCode() shouldBe 200

        // --- Stop the engine ---
        // stop() closes the Debezium engine and calls noteGracefulStop() so engineFailed is NOT set.
        // The HTTP server keeps running, so /health should remain 200 after stop() and before close().
        runner.stop()

        val postStopHealth = get("/health")
        postStopHealth.statusCode() shouldBe 200

        // --- Close the HTTP server ---
        httpServer.close()

        // After close(), connections must fail.
        var threw = false
        try {
            get("/health")
        } catch (_: Throwable) {
            threw = true
        }
        threw shouldBe true
    }

    // ---- helpers ----

    private fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("http://localhost:$httpPort$path")).GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // Busy-polls condition() every pollMs until it returns true or the timeout expires.
    private fun pollUntil(timeoutMs: Long, pollMs: Long = 100, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (_: Exception) {
                // swallow transient errors (e.g. connection refused during startup)
            }
            Thread.sleep(pollMs)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
