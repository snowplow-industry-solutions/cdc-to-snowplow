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

/**
 * Thrown by [PayloadAssembler] when the change event's `op` is not one of c/u/d/r.
 * In practice this targets Debezium's `op=t` (TRUNCATE) and any future op character we don't
 * model. Caught in [com.snowplowanalytics.cdc.engine.EngineRunner.handleRecord] at WARN level,
 * carrying [op] and [table] for the log line.
 *
 * Distinct from [UnsupportedOperationException] (a JDK class with broader meaning) so the
 * engine's catch block targets exactly this case without accidentally swallowing other
 * unsupported-operation errors that bubble up from the tracker or Jackson.
 */
class UnsupportedOpException(val op: String, val table: TableKey) :
    RuntimeException("unsupported op '$op' for table $table")
