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

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadinessProbeTest {

    @Test
    fun `initial state is not ready with reason engine_not_started`() {
        val probe = ReadinessProbe()

        probe.isReady() shouldBe false
        val response = probe.readyResponse()
        response.httpCode shouldBe 503
        response.body["reason"] shouldBe "engine_not_started"
    }

    @Test
    fun `after markEmitterReady only - still not ready with reason engine_not_started`() {
        val probe = ReadinessProbe()
        probe.markEmitterReady()

        probe.isReady() shouldBe false
        val response = probe.readyResponse()
        response.httpCode shouldBe 503
        response.body["reason"] shouldBe "engine_not_started"
    }

    @Test
    fun `after markEngineReady only - still not ready with reason emitter_not_started`() {
        val probe = ReadinessProbe()
        probe.markEngineReady()

        probe.isReady() shouldBe false
        val response = probe.readyResponse()
        response.httpCode shouldBe 503
        response.body["reason"] shouldBe "emitter_not_started"
    }

    @Test
    fun `after markEmitterReady and markEngineReady - ready with 200`() {
        val probe = ReadinessProbe()
        probe.markEmitterReady()
        probe.markEngineReady()

        probe.isReady() shouldBe true
        val response = probe.readyResponse()
        response.httpCode shouldBe 200
        response.body shouldContainExactly mapOf<String, String>("status" to "ready")
    }

    @Test
    fun `after ready then markEngineFailed - not ready with reason engine_failed`() {
        val probe = ReadinessProbe()
        probe.markEmitterReady()
        probe.markEngineReady()
        probe.markEngineFailed()

        probe.isReady() shouldBe false
        val response = probe.readyResponse()
        response.httpCode shouldBe 503
        response.body["reason"] shouldBe "engine_failed"
    }

    @Test
    fun `noteGracefulStop suppresses subsequent markEngineFailed transition`() {
        val probe = ReadinessProbe()
        probe.markEmitterReady()
        probe.markEngineReady()
        probe.noteGracefulStop()
        probe.markEngineFailed()

        // graceful-stop flag means engine failure is ignored — still ready
        probe.isReady() shouldBe true
        val response = probe.readyResponse()
        response.httpCode shouldBe 200
        response.body shouldContainExactly mapOf<String, String>("status" to "ready")
    }
}
