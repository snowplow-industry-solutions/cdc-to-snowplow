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

/**
 * Loads the tool's canonical `cdc_source` Iglu schema, bundled into the JAR from the repo's
 * `schemas/` directory by the `processResources` build step. [RELATIVE_PATH] is the path under
 * the generated `schemas/` output directory where scaffold writes the copy.
 */
object CdcSourceSchemaResource {
    const val RELATIVE_PATH = "com.snowplowanalytics/cdc_source/jsonschema/1-0-0"
    private const val CLASSPATH = "iglu/$RELATIVE_PATH"

    fun read(): String =
        javaClass.classLoader.getResourceAsStream(CLASSPATH)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("bundled cdc_source schema not found on classpath at $CLASSPATH")
}
