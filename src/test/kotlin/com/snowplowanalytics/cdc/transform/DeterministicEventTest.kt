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

package com.snowplowanalytics.cdc.transform

import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

class DeterministicEventTest {

    private fun innerSdj(uri: String, data: Map<String, Any?>): SelfDescribingJson =
        SelfDescribingJson(uri, data)

    private fun buildInner(): SelfDescribing =
        SelfDescribing.builder()
            .eventData(innerSdj("iglu:com.example/x/jsonschema/1-0-0", mapOf("op" to "c")))
            .trueTimestamp(1714400000000L)
            .build()

    @Test
    fun `getPayload eid equals the constructor-supplied UUID, not a random one`() {
        val expectedId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val event = DeterministicEvent(buildInner(), expectedId)

        val payloadMap = event.payload.map
        payloadMap["eid"] shouldBe expectedId.toString()
    }

    @Test
    fun `same wrapper called twice produces the same eid (no per-call randomness)`() {
        val expectedId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val event = DeterministicEvent(buildInner(), expectedId)

        val first = event.payload.map["eid"]
        val second = event.payload.map["eid"]
        first shouldBe second
        first shouldBe expectedId.toString()
    }

    @Test
    fun `delegates trueTimestamp and context to the inner SelfDescribing`() {
        val inner = SelfDescribing.builder()
            .eventData(innerSdj("iglu:com.example/x/jsonschema/1-0-0", mapOf("op" to "c")))
            .trueTimestamp(1714400000000L)
            .customContext(listOf(innerSdj("iglu:com.example/ctx/jsonschema/1-0-0", mapOf("k" to "v"))))
            .build()
        val event = DeterministicEvent(inner, UUID.randomUUID())

        event.trueTimestamp shouldBe 1714400000000L
        event.context.size shouldBe 1
        event.context[0].map["schema"] shouldBe "iglu:com.example/ctx/jsonschema/1-0-0"
    }

    @Test
    fun `wrapping a fresh inner SelfDescribing changes its eid away from the random one Snowplow stamps`() {
        val pinnedId = UUID.fromString("00000000-0000-5000-8000-000000000001")
        val inner = buildInner()

        // The unwrapped inner has a random eid stamped at TrackerPayload construction time.
        val randomEid = inner.payload.map["eid"]
        randomEid shouldNotBe null
        randomEid shouldNotBe pinnedId.toString()

        val event = DeterministicEvent(inner, pinnedId)
        event.payload.map["eid"] shouldBe pinnedId.toString()
    }
}
