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

package com.snowplowanalytics.cdc.config

import java.nio.file.Path

/**
 * Where the YAML config body comes from. [File] is the canonical path-based input; [Env] reads
 * the whole YAML body from a named environment variable (for containerised/cloud delivery where
 * mounting a file is awkward). Both resolve through [ConfigLoader], so env-var interpolation and
 * validation behave identically.
 */
sealed interface ConfigSource {
    fun load(): Config

    data class File(val path: Path) : ConfigSource {
        override fun load(): Config = ConfigLoader.load(path)
    }

    data class Env(val varName: String) : ConfigSource {
        override fun load(): Config {
            val raw = System.getenv(varName)
            if (raw.isNullOrBlank()) {
                throw ConfigException(
                    listOf(ConfigError.FileError("env:$varName", "environment variable is unset or blank")),
                    source = "env:$varName",
                )
            }
            return ConfigLoader.loadFromString(raw, "env:$varName")
        }
    }
}
