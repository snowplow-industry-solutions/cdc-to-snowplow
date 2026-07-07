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

import com.snowplowanalytics.snowplow.tracker.Subject
import com.snowplowanalytics.snowplow.tracker.constants.Parameter
import com.snowplowanalytics.snowplow.tracker.events.Event
import com.snowplowanalytics.snowplow.tracker.events.SelfDescribing
import com.snowplowanalytics.snowplow.tracker.payload.Payload
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson
import com.snowplowanalytics.snowplow.tracker.payload.TrackerPayload
import java.util.UUID

/**
 * Wraps a [SelfDescribing] so that the rendered TrackerPayload's `eid` is the
 * deterministic [eventId] this wrapper carries, not the random UUID Snowplow's
 * [TrackerPayload] constructor stamps.
 *
 * Why this exists: the Snowplow Java tracker 2.1.0 does not expose a public API to override
 * `eid` on a [SelfDescribing.Builder]. [TrackerPayload]'s constructor calls `Utils.getEventId()`
 * (`= UUID.randomUUID()`) and adds `eid` to its internal LinkedHashMap before any builder method
 * runs. We let [SelfDescribing.getPayload] build the payload normally, then call
 * `TrackerPayload.add(EID, ...)` — which is a `LinkedHashMap.put` — to overwrite the random eid
 * with our deterministic value.
 *
 * Why we set base64Encode true in init: the tracker's [com.snowplowanalytics.snowplow.tracker.Tracker]
 * normally calls `setBase64Encode(parameters.getBase64Encoded())` on incoming SelfDescribing
 * events via a strict `eventClass.equals(SelfDescribing.class)` check. Our wrapper class would
 * not match that check, so the tracker would skip the call. Setting it ourselves preserves the
 * default (true) production behaviour — events go out under `ue_px` (base64) on the wire,
 * including in tests that read straight from RecordingEmitter (the flag is on the inner
 * SelfDescribing's payload, not the tracker pipeline). Test helpers must base64-decode `ue_px`.
 */
class DeterministicEvent(
    private val inner: SelfDescribing,
    private val eventId: UUID,
) : Event {

    init {
        inner.setBase64Encode(true)
    }

    override fun getContext(): List<SelfDescribingJson> = inner.context
    override fun getTrueTimestamp(): Long? = inner.trueTimestamp
    override fun getSubject(): Subject? = inner.subject

    override fun getPayload(): Payload {
        val payload = inner.payload as TrackerPayload
        payload.add(Parameter.EID, eventId.toString())
        return payload
    }
}
