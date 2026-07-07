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

import java.util.regex.Pattern

/**
 * Closed-set library of per-column cell transforms applied in declared order by [TransformPipeline].
 *
 * Each case implements [apply] with the same contract:
 *   - null input  → null output (preserves DB null semantics through the chain).
 *   - String input → transformed String (or null for [RegexExtract] no-match).
 *   - non-null non-String input → [IllegalStateException] naming [columnPath] and the actual class.
 *
 * The non-String throw is a DDL-drift safety net: startup type-checking
 * ([com.snowplowanalytics.cdc.preflight.TransformTypeCheck]) already rejects non-string DB columns,
 * so in normal operation only Strings or nulls arrive. If a schema changes after startup, the throw
 * lands in EngineRunner.handleRecord's per-event catch and the event is logged-and-skipped.
 *
 * [name] is the YAML keyword for the case; used in error messages and deserializer lookup.
 */
sealed class Transform {
    abstract val name: String
    abstract fun apply(cell: Any?, columnPath: String): Any?

    object Lowercase : Transform() {
        override val name = "lowercase"
        override fun apply(cell: Any?, columnPath: String): Any? =
            stringOrThrow(cell, columnPath)?.lowercase()
    }

    object Uppercase : Transform() {
        override val name = "uppercase"
        override fun apply(cell: Any?, columnPath: String): Any? =
            stringOrThrow(cell, columnPath)?.uppercase()
    }

    object Trim : Transform() {
        override val name = "trim"
        override fun apply(cell: Any?, columnPath: String): Any? =
            stringOrThrow(cell, columnPath)?.trim()
    }

    data class RegexExtract(val pattern: Pattern, val group: Int) : Transform() {
        override val name = "regex_extract"
        override fun apply(cell: Any?, columnPath: String): Any? {
            val s = stringOrThrow(cell, columnPath) ?: return null
            val m = pattern.matcher(s)
            return if (m.find()) m.group(group) else null
        }
    }

    data class RegexReplace(val pattern: Pattern, val replacement: String) : Transform() {
        override val name = "regex_replace"
        override fun apply(cell: Any?, columnPath: String): Any? {
            val s = stringOrThrow(cell, columnPath) ?: return null
            return pattern.matcher(s).replaceAll(replacement)
        }
    }

    companion object {
        // Centralised so every case shares the same error message shape.
        private fun stringOrThrow(cell: Any?, columnPath: String): String? = when (cell) {
            null -> null
            is String -> cell
            else -> throw IllegalStateException(
                "column '$columnPath': expected String, got ${cell::class.simpleName}"
            )
        }
    }
}
