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

/**
 * Structured representation of a single configuration problem. Surfaced by
 * the loader pipeline; rendered by [ConfigException.toString].
 *
 * `path` uses dotted JSON-pointer-ish notation: `tables[0].primary_key`,
 * `source.connector`, etc. `line` is the YAML line number when known.
 */
// `sealed class` is Kotlin's closed discriminated union — like a TypeScript union type where
// every member is known at compile time. All subclasses must be in the same file/package.
// A `when` expression over a sealed class is exhaustively checked by the compiler:
// if you add a new subclass and forget to handle it in a `when`, you get a compile error.
sealed class ConfigError {
    abstract val path: String
    abstract val message: String
    abstract val line: Int?  // null when the source line cannot be determined

    // Each subclass is also a `data class` — structural equality and copy() come for free.

    data class FileError(
        val filePath: String,
        val reason: String,
    ) : ConfigError() {
        override val path: String = filePath
        override val message: String = "could not read $filePath: $reason"
        override val line: Int? = null
    }

    data class YamlSyntax(
        override val message: String,
        override val line: Int?,
        val column: Int?,
    ) : ConfigError() {
        override val path: String = "<yaml>"
    }

    data class MissingField(
        override val path: String,
        override val line: Int?,
    ) : ConfigError() {
        override val message: String = "required field is missing"
    }

    data class UnknownKey(
        override val path: String,
        override val line: Int?,
    ) : ConfigError() {
        override val message: String = "unknown configuration key"
    }

    data class InvalidValue(
        override val path: String,
        override val message: String,
        override val line: Int? = null,
    ) : ConfigError()

    data class UnsetEnvVar(
        val varName: String,
        override val path: String,
        override val line: Int? = null,
    ) : ConfigError() {
        override val message: String =
            "environment variable $varName is unset (referenced from $path)"
    }
}
