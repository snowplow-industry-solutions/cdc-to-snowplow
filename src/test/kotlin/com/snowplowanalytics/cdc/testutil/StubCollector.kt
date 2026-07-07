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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-process stub Snowplow collector for tests. Bound to 127.0.0.1:0 (OS-assigned port) and
 * backed by [com.sun.net.httpserver.HttpServer]. Two handler phases:
 *
 *   - [stall]   — the default. Each incoming request blocks on a CountDownLatch and never sends
 *                 a response. The TCP socket stays open; from the tracker's perspective the
 *                 request is in-flight indefinitely.
 *   - [unblock] — swap the handler to one that returns HTTP 200 with an empty body. Count down
 *                 the latch so any request parked in the phase-1 handler also completes (with
 *                 a 200) instead of staying open. Bodies received in either phase are appended
 *                 to [received] under a lock so tests can assert how many events the tracker
 *                 has managed to send.
 *
 * The server uses a fixed thread pool of 2 so a parked phase-1 request never blocks a phase-2
 * request from being served on the same socket.
 *
 * Implements AutoCloseable so tests can wrap usage in Kotlin's `.use { }`.
 */
class StubCollector : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newFixedThreadPool(2)
    }
    private val latch = CountDownLatch(1)
    private val _received: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    val url: String get() = "http://127.0.0.1:${server.address.port}"
    val received: List<ByteArray> get() = _received.toList()

    init {
        stall()
        server.start()
    }

    /**
     * Install the stalling handler: read and record the body, then block on the latch and
     * never write a response. The tracker sees an in-flight request that never returns.
     */
    fun stall() {
        try { server.removeContext("/") } catch (_: IllegalArgumentException) { /* not yet registered */ }
        server.createContext("/", StallHandler())
    }

    /**
     * Install the responsive handler and free any parked phase-1 request. Phase-1 handlers
     * still active when this is called write a 200 after the latch counts down.
     */
    fun unblock() {
        try { server.removeContext("/") } catch (_: IllegalArgumentException) { /* not yet registered */ }
        server.createContext("/", ResponseHandler())
        latch.countDown()
    }

    override fun close() {
        // stop(0) means "do not wait for in-flight handlers". The fixed thread pool is shut
        // down separately because HttpServer.stop does not own it explicitly.
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    private inner class StallHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                val body = exchange.requestBody.readAllBytes()
                _received.add(body)
                // Park the handler thread until either (a) the test calls unblock() and counts
                // down the latch, or (b) the server is stopped. We bound this with a long
                // timeout so a runaway test cannot leak a thread forever.
                if (latch.await(30, TimeUnit.SECONDS)) {
                    // unblock() reached us first — answer the parked request with a 200.
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.close()
                }
                // Timed out — let the handler exit; the server will close the socket on stop().
            } finally {
                exchange.close()
            }
        }
    }

    private inner class ResponseHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                val body = exchange.requestBody.readAllBytes()
                _received.add(body)
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
            } finally {
                exchange.close()
            }
        }
    }
}
