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
 * Operator-facing exception thrown when the YAML config cannot be loaded or is semantically
 * invalid. The message is a pre-formatted multi-line block suitable for printing directly to
 * stderr — no stack trace, no JSON wrapping. Callers catch this type specifically and call
 * System.err.println(e.message) rather than letting it propagate to a top-level handler.
 *
 * The [errors] list contains all accumulated problems so the operator can fix them in one pass.
 */
class ConfigException(
    val errors: List<ConfigError>,
    val source: String? = null,
) : RuntimeException(formatMessage(source, errors)) {

    // Override toString() so that logging frameworks that call toString() instead of getMessage()
    // still produce the formatted block rather than "ConfigException: ..." noise.
    override fun toString(): String = message ?: super.toString()

    // `companion object` is Kotlin's per-class static namespace — think of it as a module-level
    // object that happens to live inside the class. Members declared here are callable as
    // ConfigException.formatMessage(...) from Kotlin, or as ConfigException.Companion.formatMessage(...)
    // from Java (unless annotated @JvmStatic, which makes them true Java statics).
    companion object {
        private fun formatMessage(source: String?, errors: List<ConfigError>): String {
            val header = if (source != null) {
                "Configuration error(s) in $source:"
            } else {
                "Configuration error(s):"
            }
            val body = errors.mapIndexed { idx, err -> formatOne(idx + 1, err) }
                .joinToString("\n")
            return "$header\n$body"
        }

        private fun formatOne(n: Int, e: ConfigError): String {
            // `?.takeIf { it > 0 }?.let { ... }` is a null-safe call chain:
            //   - `?.takeIf { }` returns null if the condition is false (filters out line == 0)
            //   - `?.let { ... }` maps a non-null value; returns null if the receiver is null
            // The `?: ""` Elvis operator provides the fallback when the whole chain is null.
            val lineSuffix = e.line?.takeIf { it > 0 }?.let { " (line $it)" } ?: ""
            // `when` with `is` checks is Kotlin's exhaustive switch — the compiler knows all
            // ConfigError subtypes (sealed class) and will warn if a branch is missing.
            // After an `is` check, the compiler smart-casts `e` to that subtype automatically,
            // so e.g. `e.column` is accessible inside the YamlSyntax branch without a cast.
            return when (e) {
                is ConfigError.FileError -> "  $n. ${e.message}"
                is ConfigError.YamlSyntax -> {
                    val col = e.column?.takeIf { it > 0 }?.let { ", col $it" } ?: ""
                    val loc = e.line?.takeIf { it > 0 }?.let { " (line $it$col)" } ?: ""
                    "  $n. yaml syntax: ${e.message}$loc"
                }
                is ConfigError.UnsetEnvVar -> {
                    val ref = if (e.line != null) "(referenced from ${e.path}, line ${e.line})"
                              else "(referenced from ${e.path})"
                    "  $n. environment variable ${e.varName} is unset $ref"
                }
                else -> "  $n. ${e.path}: ${e.message}$lineSuffix"
            }
        }
    }
}
