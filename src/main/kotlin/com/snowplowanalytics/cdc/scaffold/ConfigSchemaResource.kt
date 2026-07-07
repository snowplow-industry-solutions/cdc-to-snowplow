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

package com.snowplowanalytics.cdc.scaffold

/** Loads the bundled cdc-service-config JSON Schema (the editor-time config contract). */
object ConfigSchemaResource {
    /** Filename scaffold writes into the output root; the generated config.yaml's LSP hint points here. */
    const val FILE_NAME = "cdc-service-config.schema.json"
    private const val CLASSPATH = "iglu-config/$FILE_NAME"

    fun read(): String =
        javaClass.classLoader.getResourceAsStream(CLASSPATH)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("bundled config schema not found on classpath at $CLASSPATH")
}
