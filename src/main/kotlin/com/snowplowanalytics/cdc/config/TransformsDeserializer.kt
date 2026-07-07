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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.snowplowanalytics.cdc.transform.Transform
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Parses the value of a column's `transforms:` directive into a `List<Transform>`.
 *
 * Each YAML list entry is either:
 *   - a bare string scalar:                  `- lowercase`
 *   - a single-key map (inline or block):    `- regex_extract: { pattern: '\d+', group: 1 }`
 *
 * Closed-set names: `lowercase`, `uppercase`, `trim`, `regex_extract`, `regex_replace`.
 * Anything else throws [JsonMappingException] naming the offending column.
 *
 * Regex patterns are compiled here so [PatternSyntaxException] surfaces at config-load time
 * rather than per-event. `regex_extract` `group` is validated against the compiled pattern's
 * `groupCount()` so an out-of-range index fails startup rather than per-row.
 *
 * The entry point [parseList] is used by [ColumnsDeserializer] when it sees a `transforms:` key
 * inside a column's directive bag, and by unit tests passing arbitrary `JsonNode` shapes.
 */
object TransformsDeserializer {

    private val VALID_NAMES = setOf("lowercase", "uppercase", "trim", "regex_extract", "regex_replace")

    /**
     * @param node the YAML list node attached to a `transforms:` key.
     * @param columnPath identifies the column for error messages (e.g. `public.orders.email`).
     * @param p the JsonParser the host deserializer is reading from. When supplied, every
     *   [JsonMappingException] raised here carries the parser's current location (line +
     *   column), matching the line-aware errors produced for `rename:` / `exclude:`. Tests
     *   that drive `parseList` directly with a `JsonNode` pass `null` and accept line-less
     *   error messages.
     */
    fun parseList(node: JsonNode, columnPath: String, p: JsonParser? = null): List<Transform> {
        // MissingNode only reaches here through direct test wiring; ColumnsDeserializer guards
        // the absent-key case before calling. Explicit `transforms: null` is rejected by the
        // non-array branch below — the operator's intent is ambiguous, and Slice 6 prefers a
        // loud error over silently treating it as `transforms: []`.
        if (node.isMissingNode) return emptyList()
        if (!node.isArray) {
            throw err(p, columnPath, "transforms must be a YAML list; got ${node.nodeType}")
        }
        return node.map { parseEntry(it, columnPath, p) }
    }

    private fun parseEntry(entry: JsonNode, columnPath: String, p: JsonParser?): Transform = when {
        entry.isTextual -> buildTransform(entry.asText(), paramsNode = null, columnPath = columnPath, p = p)
        entry.isObject -> parseSingleKeyMap(entry, columnPath, p)
        else -> throw err(p, columnPath, "transform entry must be a string or single-key map")
    }

    private fun parseSingleKeyMap(entry: JsonNode, columnPath: String, p: JsonParser?): Transform {
        val keys = entry.fieldNames().asSequence().toList()
        when {
            keys.isEmpty() -> throw err(
                p, columnPath,
                "transform entry map is empty; expected a single-key map " +
                    "(e.g. `regex_extract: { pattern: '.' }`)",
            )
            keys.size > 1 -> throw err(
                p, columnPath,
                "transform entry must be a single-key map; got ${keys.size} keys: $keys",
            )
        }
        val name = keys.first()
        val params = entry.get(name)
        return buildTransform(name, paramsNode = params, columnPath = columnPath, p = p)
    }

    private fun buildTransform(name: String, paramsNode: JsonNode?, columnPath: String, p: JsonParser?): Transform {
        if (name !in VALID_NAMES) {
            throw err(p, columnPath, "unknown transform '$name' — valid: ${VALID_NAMES.joinToString(", ")}")
        }
        return when (name) {
            "lowercase" -> { rejectParams(name, paramsNode, columnPath, p); Transform.Lowercase }
            "uppercase" -> { rejectParams(name, paramsNode, columnPath, p); Transform.Uppercase }
            "trim"      -> { rejectParams(name, paramsNode, columnPath, p); Transform.Trim }
            "regex_extract" -> buildRegexExtract(paramsNode, columnPath, p)
            "regex_replace" -> buildRegexReplace(paramsNode, columnPath, p)
            else -> error("unreachable: $name not in VALID_NAMES check")
        }
    }

    private fun rejectParams(name: String, paramsNode: JsonNode?, columnPath: String, p: JsonParser?) {
        if (paramsNode != null && paramsNode.isObject && paramsNode.size() > 0) {
            throw err(p, columnPath, "transform '$name' takes no parameters")
        }
    }

    private fun buildRegexExtract(paramsNode: JsonNode?, columnPath: String, p: JsonParser?): Transform.RegexExtract {
        val params = requireObjectParams("regex_extract", paramsNode, columnPath, p)
        val pattern = compilePattern("regex_extract", params, columnPath, p)
        val groupNode = params.get("group")
        val group = when {
            groupNode == null || groupNode.isNull -> 1
            groupNode.isInt -> groupNode.asInt()
            else -> throw err(p, columnPath, "transform 'regex_extract': group must be an integer; got ${groupNode.nodeType}")
        }
        if (group < 0) {
            throw err(p, columnPath, "transform 'regex_extract': group must be >= 0; got $group")
        }
        val capCount = pattern.matcher("").groupCount()
        if (group > capCount) {
            throw err(
                p, columnPath,
                "transform 'regex_extract': group $group is out of range for pattern '${pattern.pattern()}' ($capCount capturing groups)"
            )
        }
        rejectUnknownParams("regex_extract", params, KNOWN_REGEX_EXTRACT_KEYS, columnPath, p)
        return Transform.RegexExtract(pattern, group)
    }

    private fun buildRegexReplace(paramsNode: JsonNode?, columnPath: String, p: JsonParser?): Transform.RegexReplace {
        val params = requireObjectParams("regex_replace", paramsNode, columnPath, p)
        val pattern = compilePattern("regex_replace", params, columnPath, p)
        val replacementNode = params.get("replacement")
            ?: throw err(p, columnPath, "transform 'regex_replace': missing required parameter 'replacement'")
        if (!replacementNode.isTextual) {
            throw err(p, columnPath, "transform 'regex_replace': replacement must be a string; got ${replacementNode.nodeType}")
        }
        rejectUnknownParams("regex_replace", params, KNOWN_REGEX_REPLACE_KEYS, columnPath, p)
        return Transform.RegexReplace(pattern, replacementNode.asText())
    }

    /**
     * Rejects any key in [params] that is not in [known]. Closes the silent-typo gap inside a
     * transform's params bag: `regex_extract: { pattern: '.', grup: 0 }` (typo of `group`) would
     * otherwise be tolerated and `group` would default to 1. Loud-by-default to match the slice's
     * stance on closed-set transform names.
     */
    private fun rejectUnknownParams(
        name: String,
        params: JsonNode,
        known: Set<String>,
        columnPath: String,
        p: JsonParser?,
    ) {
        val unknown = params.fieldNames().asSequence().filter { it !in known }.toList()
        if (unknown.isNotEmpty()) {
            throw err(
                p, columnPath,
                "transform '$name': unknown parameter(s) $unknown — valid: ${known.joinToString(", ")}"
            )
        }
    }

    private fun requireObjectParams(name: String, paramsNode: JsonNode?, columnPath: String, p: JsonParser?): JsonNode {
        if (paramsNode == null || !paramsNode.isObject) {
            throw err(p, columnPath, "transform '$name': missing parameters")
        }
        return paramsNode
    }

    private fun compilePattern(name: String, params: JsonNode, columnPath: String, p: JsonParser?): Pattern {
        val patternNode = params.get("pattern")
            ?: throw err(p, columnPath, "transform '$name': missing required parameter 'pattern'")
        if (!patternNode.isTextual) {
            throw err(p, columnPath, "transform '$name': pattern must be a string; got ${patternNode.nodeType}")
        }
        val raw = patternNode.asText()
        return try {
            Pattern.compile(raw)
        } catch (e: PatternSyntaxException) {
            throw err(p, columnPath, "transform '$name': invalid pattern '$raw' — ${e.description}")
        }
    }

    private fun err(p: JsonParser?, columnPath: String, msg: String): JsonMappingException =
        JsonMappingException.from(p, "column '$columnPath': $msg")

    private val KNOWN_REGEX_EXTRACT_KEYS = setOf("pattern", "group")
    private val KNOWN_REGEX_REPLACE_KEYS = setOf("pattern", "replacement")
}
