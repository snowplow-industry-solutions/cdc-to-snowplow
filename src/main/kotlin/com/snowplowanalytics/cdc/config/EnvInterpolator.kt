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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Walks a Jackson [JsonNode] tree and replaces `${VAR}` tokens in string scalars with the
 * corresponding environment variable values. The tree is mutated in-place.
 *
 * The [env] parameter defaults to [System.getenv] but can be overridden in tests to inject a
 * hermetic environment without touching the real process environment.
 */
class EnvInterpolator(
    // Function type as a constructor parameter — Kotlin's idiomatic alternative to a SAM
    // interface. In tests, `EnvInterpolator { name -> mapOf("X" to "val")[name] }` overrides
    // this without any mock framework.
    private val env: (String) -> String? = System::getenv,
) {
    // Matches ${VARNAME} where VARNAME starts with a letter or underscore and contains only
    // alphanumerics and underscores — the POSIX shell identifier alphabet. Names starting
    // with a digit (e.g. ${1foo}) are intentionally excluded because they are not legal
    // environment variable names on any POSIX-compliant system.
    // Example matches: ${PG_PASSWORD}, ${COLLECTOR_URL}
    // Non-matches: ${123}, ${my-var}, $VAR (no braces)
    private val tokenRegex = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

    /**
     * Walks the tree in-place, replacing every `${VAR}` token inside string
     * scalars with the corresponding env value. Returns a list of UnsetEnvVar
     * errors for tokens whose variable was not set; on success the list is empty.
     */
    fun interpolate(root: JsonNode): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        walk(root, "", errors)
        return errors
    }

    private fun walk(node: JsonNode, path: String, errors: MutableList<ConfigError>) {
        // `when` without an argument acts like a chain of if/else-if on the conditions.
        // `is ObjectNode` / `is ArrayNode` are smart-cast checks — inside each branch,
        // `node` is automatically cast to that subtype without an explicit `node as ObjectNode`.
        when (node) {
            is ObjectNode -> {
                // node.fields() returns a Java MutableIterator<Map.Entry>. Iterating over an
                // ObjectNode while calling node.put() on the same key is safe: Jackson's
                // LinkedHashMap implementation does not increment modCount on a put-existing-key,
                // so the iterator remains valid throughout the loop.
                node.fields().forEach { (name, child) ->
                    val childPath = if (path.isEmpty()) name else "$path.$name"
                    if (child is TextNode) {
                        val replaced = substitute(child.asText(), childPath, errors)
                        if (replaced != null) node.put(name, replaced)
                    } else {
                        walk(child, childPath, errors)
                    }
                }
            }
            is ArrayNode -> {
                for (i in 0 until node.size()) {
                    val child = node.get(i)
                    val childPath = "$path[$i]"
                    if (child is TextNode) {
                        val replaced = substitute(child.asText(), childPath, errors)
                        // node.set() replaces the element at index i — ArrayNode is mutable
                        if (replaced != null) node.set(i, TextNode.valueOf(replaced))
                    } else {
                        walk(child, childPath, errors)
                    }
                }
            }
            else -> Unit // scalars (numbers, bools, null) are not interpolated
        }
    }

    private fun substitute(
        original: String,
        path: String,
        errors: MutableList<ConfigError>,
    ): String? {
        if (!tokenRegex.containsMatchIn(original)) return null  // fast path — no tokens
        return tokenRegex.replace(original) { match ->
            val name = match.groupValues[1]  // capture group 1 is the variable name
            val value = env(name)
            if (value == null) {
                errors += ConfigError.UnsetEnvVar(varName = name, path = path)
                match.value // leave the placeholder in the string; loader will throw before binding
            } else {
                value
            }
        }
    }
}
