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

/**
 * Minimal interface for sending Snowplow events. Two implementations exist:
 *   - [SnowplowEmitter] — wraps the real Snowplow Java tracker for production use.
 *   - [RecordingEmitter] — collects events in memory for use in tests and demos.
 *
 * Accepts the Snowplow [Event] interface (not the concrete `SelfDescribing` type) so emitters can
 * receive both Slice 1's plain SelfDescribing instances and Slice 4's DeterministicEvent wrappers.
 * `Tracker.track(Event)` already accepts the interface, so [SnowplowEmitter] needs no body change.
 *
 * [SnowplowEmitter.emit] may block when the underlying buffer is full and may throw
 * [EmitterClosedException] if `close()` is called while a thread is blocked in `emit()`.
 * [RecordingEmitter] is synchronous and in-memory; neither condition applies to it.
 */
interface Emitter {
    fun emit(event: Event)
    fun close()
    fun bufferUsed(): Int
}
