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

package com.snowplowanalytics.cdc.emitter

import com.snowplowanalytics.snowplow.tracker.events.Event
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [Emitter] that records every emitted event for inspection in tests and demos.
 * Not suitable for production — nothing is sent to a collector.
 *
 * [CopyOnWriteArrayList] is used so that [events] can be read from one thread (the test thread)
 * while [emit] is called from another (the Debezium engine thread) without locking.
 */
class RecordingEmitter : Emitter {
    private val recorded: MutableList<Event> = CopyOnWriteArrayList()

    // Returns a snapshot of the list at the moment of the call — safe for the test thread to
    // iterate over while the engine thread continues to add events.
    val events: List<Event>
        get() = recorded.toList()

    override fun emit(event: Event) {
        recorded.add(event)
    }

    override fun close() {
        // no-op — nothing to flush or disconnect
    }

    // RecordingEmitter has no real buffer — it appends directly to an in-memory list with no
    // back-pressure. Always returns 0 so callers have a well-defined, safe value to read.
    override fun bufferUsed(): Int = 0
}
