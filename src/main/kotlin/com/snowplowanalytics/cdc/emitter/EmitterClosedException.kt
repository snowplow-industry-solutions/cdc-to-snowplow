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

/**
 * Thrown by [Emitter.emit] when the emitter has been closed mid-call. Distinct from generic
 * runtime exceptions so [com.snowplowanalytics.cdc.engine.EngineRunner.handleRecord] can catch
 * it specifically, log at INFO ("emitter closed; ignoring event during shutdown"), and return
 * without raising an ERROR. Two paths produce this:
 *
 *   1. The thread was parked in `Semaphore.acquire()` when `close()` flood-released permits.
 *      `acquire()` returns, the volatile `closed` flag is observed true, and the wrapper throws
 *      this exception instead of calling `tracker.track()` on a closed tracker.
 *   2. The thread was parked in `Semaphore.acquire()` when an external interrupt arrived. The
 *      wrapper restores the interrupt flag and throws this exception so the engine sees the
 *      same shutdown signal regardless of whether close() or interrupt() initiated it.
 */
class EmitterClosedException(message: String = "emitter closed") : RuntimeException(message)
