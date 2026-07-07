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

import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.snowplow.tracker.Subject
import com.snowplowanalytics.snowplow.tracker.events.Event
import com.snowplowanalytics.snowplow.tracker.payload.Payload
import com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson

/**
 * Wraps a [DeterministicEvent] (or any [Event]) and surfaces the [tableKey] and [op] that
 * produced it, so downstream observability counters can record per-table, per-operation metrics
 * without re-parsing the envelope.
 *
 * Implements [Event] by delegating all four tracker interface methods to [inner], so every
 * caller that reads `.context`, `.payload`, `.trueTimestamp`, or `.subject` sees the same values
 * as if they held a plain [DeterministicEvent].
 */
class AssembledEvent(
    private val inner: Event,
    val tableKey: TableKey,
    val op: String,
) : Event {
    override fun getContext(): List<SelfDescribingJson> = inner.context
    override fun getTrueTimestamp(): Long? = inner.trueTimestamp
    override fun getSubject(): Subject? = inner.subject
    override fun getPayload(): Payload = inner.payload
}
