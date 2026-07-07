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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.snowplowanalytics.cdc.pg.TableMetadata

/**
 * Builds a starter per-table Iglu self-describing JSON Schema from [TableMetadata].
 * The schema describes the CDC envelope (op/before/after); column shapes come from [TypeMapper].
 * Output is a starting point — it carries an auto-generated header in `description` (spec §5.2).
 */
object IgluSchemaGenerator {
    private val nf = JsonNodeFactory.instance
    private val om = ObjectMapper().writerWithDefaultPrettyPrinter()
    private const val META = "http://iglucentral.com/schemas/com.snowplowanalytics.self-desc/schema/jsonschema/1-0-0#"

    fun generate(table: TableMetadata, vendor: String): String {
        val root = nf.objectNode()
        root.put("\$schema", META)
        root.put(
            "description",
            "auto-generated starting point for ${table.key} by cdc-service scaffold. " +
                "Review before deploying: add business descriptions, constraints, and confirm types. " +
                "Multi-dimensional arrays are mapped single-level (Postgres catalog limitation).",
        )
        val self = root.putObject("self")
        self.put("vendor", vendor)
        self.put("name", "${table.key.name}_change")
        self.put("format", "jsonschema")
        self.put("version", "1-0-0")

        root.put("type", "object")
        val props = root.putObject("properties")
        val op = props.putObject("op")
        op.put("type", "string")
        op.putArray("enum").apply { add("c"); add("u"); add("d"); add("r") }
        props.set<ObjectNode>("before", rowObject(table))
        props.set<ObjectNode>("after", rowObject(table))
        root.putArray("required").add("op")
        root.put("additionalProperties", true)
        return om.writeValueAsString(root)
    }

    /** A nullable object describing the table's row shape, reused for both before and after. */
    private fun rowObject(table: TableMetadata): ObjectNode {
        val row = nf.objectNode()
        row.putArray("type").apply { add("object"); add("null") }
        val props = row.putObject("properties")
        val required = mutableListOf<String>()
        for (col in table.columns) {
            val mapped = TypeMapper.map(col)
            val node = mapped.node
            if (mapped.unmappedType != null) {
                node.put("description", "unmapped Postgres type '${mapped.unmappedType}' — refine manually")
            }
            props.set<ObjectNode>(col.name, node)
            if (!col.isNullable) required += col.name
        }
        if (required.isNotEmpty()) row.putArray("required").apply { required.forEach { add(it) } }
        return row
    }
}
