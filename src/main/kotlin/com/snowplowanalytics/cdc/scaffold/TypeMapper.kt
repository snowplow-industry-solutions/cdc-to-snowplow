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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.snowplowanalytics.cdc.pg.DbColumn

/** Result of mapping one column: the JSON-Schema fragment, plus the raw type name if unmapped. */
data class MappedType(val node: ObjectNode, val unmappedType: String? = null)

/**
 * Translates a [DbColumn] to a JSON-Schema fragment per design doc §3 / spec §6.2.
 * Pure. Nullability widens the `type` to a [T, "null"] union; the caller decides `required`.
 */
object TypeMapper {
    private val nf = JsonNodeFactory.instance

    fun map(col: DbColumn): MappedType {
        if (col.udtName == "jsonb" || col.udtName == "json") return MappedType(nf.objectNode())

        if (col.dataType == "USER-DEFINED" && col.enumLabels != null) {
            val labels = col.enumLabels
            return MappedType(enumNode(labels, col.isNullable))
        }

        if (col.dataType == "ARRAY") {
            val elem = mapPrimitive(col.udtName.removePrefix("_"))
            val node = nf.objectNode()
            if (col.isNullable) {
                val arr = node.putArray("type"); arr.add("array"); arr.add("null")
            } else {
                node.put("type", "array")
            }
            node.set<ObjectNode>("items", elem ?: nf.objectNode())
            return MappedType(node)
        }

        val primitive = mapPrimitive(col.udtName)
            ?: return MappedType(nf.objectNode(), unmappedType = col.udtName)

        return MappedType(decorate(primitive, col))
    }

    private fun mapPrimitive(udt: String): ObjectNode? = when (udt) {
        "int2", "int4", "int8" -> typed("integer")
        "numeric" -> typed("string")
        "float4", "float8" -> typed("number")
        "bool" -> typed("boolean")
        "text", "varchar", "bpchar" -> typed("string")
        "timestamp", "timestamptz" -> typedFmt("string", "date-time")
        "date" -> typedFmt("string", "date")
        "uuid" -> typedFmt("string", "uuid")
        else -> null
    }

    private fun typed(t: String) = nf.objectNode().also { it.put("type", t) }
    private fun typedFmt(t: String, fmt: String) = typed(t).also { it.put("format", fmt) }

    private fun decorate(scalar: ObjectNode, col: DbColumn): ObjectNode {
        val out = nf.objectNode()
        val baseType = scalar.get("type").asText()
        if (col.isNullable) {
            val arr = out.putArray("type"); arr.add(baseType); arr.add("null")
        } else {
            out.put("type", baseType)
        }
        scalar.get("format")?.let { out.set<JsonNode>("format", it) }
        if (baseType == "string" && col.characterMaximumLength != null) {
            out.put("maxLength", col.characterMaximumLength)
        }
        return out
    }

    private fun enumNode(labels: List<String>, nullable: Boolean): ObjectNode {
        val out = nf.objectNode()
        if (nullable) { val arr = out.putArray("type"); arr.add("string"); arr.add("null") }
        else out.put("type", "string")
        val enumArr = out.putArray("enum")
        labels.forEach { enumArr.add(it) }
        if (nullable) enumArr.addNull()
        return out
    }
}
