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

package com.snowplowanalytics.cdc.preflight

import com.snowplowanalytics.cdc.emitter.SnowplowEmitter
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Startup preflight check that proves [SnowplowEmitter] still implements block-on-overflow.
 *
 * Procedure:
 *   1. Bind a [com.sun.net.httpserver.HttpServer] to 127.0.0.1:0 with a two-thread executor.
 *   2. Install a phase-1 handler that parks each incoming request on a [CountDownLatch] and
 *      never sends a response. The tracker sees in-flight requests that never return.
 *   3. Build a throwaway [SnowplowEmitter] pointed at the stub with `bufferCapacity = 2`.
 *   4. Emit two events on the calling thread; both must return promptly.
 *   5. Launch a third [SnowplowEmitter.emit] on a worker thread. Assert the worker is still
 *      alive after 500ms. This is the block-proof.
 *   6. Swap the handler to a phase-2 handler that returns HTTP 200, counting down the latch
 *      so any parked phase-1 request completes with a 200. Assert the worker thread completes
 *      within 2s. This is the unblock-proof.
 *   7. Tear down the throwaway emitter and the HTTP server.
 *
 * Failure at any assertion throws [StartupException] with a message naming the half that
 * failed and what was waited.
 *
 * Sized small on purpose: the test proves a *boolean* property of the wrapper class
 * ("does it block?") and the proof generalises across capacities. Pushing the operator's
 * configured `buffer_capacity` (potentially 100k+) at startup would dominate boot time
 * for no extra signal.
 */
internal class EmitterSelfTest {
    private val log = LoggerFactory.getLogger(EmitterSelfTest::class.java)

    fun run() {
        val server: HttpServer = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        } catch (e: Exception) {
            throw StartupException(
                "Emitter startup self-test failed: could not bind loopback HTTP server " +
                    "for the self-test (${e.javaClass.simpleName}: ${e.message}).",
            )
        }
        val executor = Executors.newFixedThreadPool(2)
        server.executor = executor

        val latch = CountDownLatch(1)
        val stallHandler = StallHandler(latch)
        val responseHandler = ResponseHandler()
        server.createContext("/", stallHandler)
        server.start()

        val collectorUrl = "http://127.0.0.1:${server.address.port}"
        // Throwaway Counters for the self-test instance. Its counters are never read; the
        // SnowplowEmitter constructor requires the parameter but the self-test asserts on
        // emit-blocking behaviour, not on counter values.
        val emitter = SnowplowEmitter(
            collectorUrl = collectorUrl,
            namespace = "cdc-service-selftest",
            appId = "cdc-service-selftest",
            batchSize = 1,
            bufferCapacity = 2,
            counters = Counters(bufferCapacity = 2),
        )

        try {
            // Fill the buffer. These two emits must return promptly — there is no
            // backpressure yet.
            emitter.emit(syntheticEvent(1))
            emitter.emit(syntheticEvent(2))

            // Third emit must block. Run it on a worker; assert worker is still alive
            // after the block-proof timeout.
            val workerError = AtomicReference<Throwable?>(null)
            val worker = Thread {
                try {
                    emitter.emit(syntheticEvent(3))
                } catch (t: Throwable) {
                    workerError.set(t)
                }
            }.apply { name = "emitter-self-test-worker"; start() }

            worker.join(BLOCK_PROOF_TIMEOUT_MS)
            if (!worker.isAlive) {
                throw StartupException(
                    """
                    Emitter startup self-test failed: third emit() returned within
                    ${BLOCK_PROOF_TIMEOUT_MS}ms even though the buffer is exhausted
                    (capacity=2). The SnowplowEmitter is not enforcing block-on-overflow.
                    This usually means the semaphore gate or the tracker's EmitterCallback
                    wiring was reverted.
                    """.trimIndent(),
                )
            }

            // Swap to phase-2 handler and unblock the parked phase-1 request.
            server.removeContext("/")
            server.createContext("/", responseHandler)
            latch.countDown()

            worker.join(UNBLOCK_PROOF_TIMEOUT_MS)
            if (worker.isAlive) {
                worker.interrupt()
                throw StartupException(
                    """
                    Emitter startup self-test failed: blocked emit() did not complete
                    within ${UNBLOCK_PROOF_TIMEOUT_MS}ms after the stub collector began
                    returning HTTP 200. The EmitterCallback is registered but is not
                    releasing permits on onSuccess. The tracker continues to retry while
                    permits stay held.
                    """.trimIndent(),
                )
            }
            workerError.get()?.let {
                throw StartupException(
                    "Emitter startup self-test failed: blocked emit() threw " +
                        "${it.javaClass.simpleName}: ${it.message}",
                )
            }
            log.info("emitter startup self-test passed")
        } finally {
            try {
                emitter.close()
            } catch (_: Exception) {
                // Best-effort; teardown errors are not the operator's signal here.
            }
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun syntheticEvent(i: Int): SelfDescribing =
        SelfDescribing.builder()
            .eventData(
                SelfDescribingJson(
                    "iglu:com.snowplowanalytics/cdc_selftest/jsonschema/1-0-0",
                    mapOf("i" to i),
                )
            )
            .build()

    private class StallHandler(private val latch: CountDownLatch) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                exchange.requestBody.readAllBytes()
                // Park until the test calls unblock(). The await timeout is a safety
                // bound — if something goes wrong we don't leak this thread.
                if (latch.await(30, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.close()
                }
            } finally {
                exchange.close()
            }
        }
    }

    private class ResponseHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            } finally {
                exchange.close()
            }
        }
    }

    companion object {
        // Block-proof: the worker must still be alive after this long.
        private const val BLOCK_PROOF_TIMEOUT_MS: Long = 500
        // Unblock-proof: the worker must complete within this long after we 200.
        private const val UNBLOCK_PROOF_TIMEOUT_MS: Long = 2_000
    }
}
