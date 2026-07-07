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

import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.emitter.SnowplowEmitter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EmitterBufferUsedTest {

    @Test
    fun `RecordingEmitter bufferUsed is always 0`() {
        val emitter = RecordingEmitter()
        emitter.bufferUsed() shouldBe 0
    }

    @Test
    fun `SnowplowEmitter bufferUsed reflects emitterPending in Counters`() {
        val counters = Counters(bufferCapacity = 1_000)
        counters.emitterPending.set(42L)
        val emitter = SnowplowEmitter(
            collectorUrl = "http://localhost:9090",
            namespace = "test",
            appId = "test-app",
            batchSize = 1,
            bufferCapacity = 1_000,
            counters = counters,
        )
        emitter.bufferUsed() shouldBe 42
    }

    @Test
    fun `SnowplowEmitter bufferUsed returns 0 when emitterPending is negative`() {
        val counters = Counters(bufferCapacity = 1_000)
        counters.emitterPending.set(-5L)
        val emitter = SnowplowEmitter(
            collectorUrl = "http://localhost:9090",
            namespace = "test",
            appId = "test-app",
            batchSize = 1,
            bufferCapacity = 1_000,
            counters = counters,
        )
        emitter.bufferUsed() shouldBe 0
    }
}
