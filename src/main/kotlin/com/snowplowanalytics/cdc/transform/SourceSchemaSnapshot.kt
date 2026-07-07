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
 * Snapshot of a source table's column schema as observed in a single change event. Pairs the
 * fingerprint (used for equality / change detection) with the ordered column-name list (used for
 * human-readable DDL diff logging).
 */
data class SourceSchemaSnapshot(
    val table: TableKey,
    val fingerprint: String,
    val columnNames: List<String>,
)
