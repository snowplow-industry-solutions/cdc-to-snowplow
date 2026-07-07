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
import com.snowplowanalytics.cdc.pg.DbColumn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TypeMapperTest {
    private val om = ObjectMapper()
    private fun col(
        dataType: String, udtName: String, nullable: Boolean = false,
        enumLabels: List<String>? = null, charMaxLength: Int? = null,
    ) = DbColumn(
        name = "c", ordinalPosition = 1, isNullable = nullable,
        dataType = dataType, udtName = udtName, enumLabels = enumLabels,
        characterMaximumLength = charMaxLength,
    )
    private fun mapJson(c: DbColumn) = om.writeValueAsString(TypeMapper.map(c).node)

    @Test fun `not null integer`() { mapJson(col("integer", "int4")) shouldBe """{"type":"integer"}""" }
    @Test fun `bigint maps to integer`() { mapJson(col("bigint", "int8")) shouldBe """{"type":"integer"}""" }
    @Test fun `nullable integer puts type first null last`() {
        mapJson(col("integer", "int4", nullable = true)) shouldBe """{"type":["integer","null"]}"""
    }
    @Test fun `numeric maps to string no pattern`() { mapJson(col("numeric", "numeric")) shouldBe """{"type":"string"}""" }
    @Test fun `double precision maps to number`() { mapJson(col("double precision", "float8")) shouldBe """{"type":"number"}""" }
    @Test fun `boolean`() { mapJson(col("boolean", "bool")) shouldBe """{"type":"boolean"}""" }
    @Test fun `timestamptz maps to date-time`() {
        mapJson(col("timestamp with time zone", "timestamptz")) shouldBe """{"type":"string","format":"date-time"}"""
    }
    @Test fun `date maps to date format`() { mapJson(col("date", "date")) shouldBe """{"type":"string","format":"date"}""" }
    @Test fun `uuid maps to uuid format`() { mapJson(col("uuid", "uuid")) shouldBe """{"type":"string","format":"uuid"}""" }
    @Test fun `varchar with length emits maxLength`() {
        mapJson(col("character varying", "varchar", charMaxLength = 255)) shouldBe """{"type":"string","maxLength":255}"""
    }
    @Test fun `bare text has no maxLength`() { mapJson(col("text", "text")) shouldBe """{"type":"string"}""" }
    @Test fun `char does not emit minLength`() {
        mapJson(col("character", "bpchar", charMaxLength = 10)) shouldBe """{"type":"string","maxLength":10}"""
    }
    @Test fun `not null enum`() {
        mapJson(col("USER-DEFINED", "order_status_enum", enumLabels = listOf("pending", "shipped")))
            .shouldBe("""{"type":"string","enum":["pending","shipped"]}""")
    }
    @Test fun `nullable enum widens type and appends null to enum array`() {
        mapJson(col("USER-DEFINED", "order_status_enum", nullable = true, enumLabels = listOf("pending", "shipped")))
            .shouldBe("""{"type":["string","null"],"enum":["pending","shipped",null]}""")
    }
    @Test fun `array of integer recurses element type`() {
        mapJson(col("ARRAY", "_int4")) shouldBe """{"type":"array","items":{"type":"integer"}}"""
    }
    @Test fun `not null array keeps bare array type`() {
        mapJson(col("ARRAY", "_int4")) shouldBe """{"type":"array","items":{"type":"integer"}}"""
    }
    @Test fun `nullable array widens type to array null`() {
        mapJson(col("ARRAY", "_int4", nullable = true)) shouldBe """{"type":["array","null"],"items":{"type":"integer"}}"""
    }
    @Test fun `array of text`() {
        mapJson(col("ARRAY", "_text")) shouldBe """{"type":"array","items":{"type":"string"}}"""
    }
    @Test fun `jsonb is unconstrained`() { mapJson(col("jsonb", "jsonb")) shouldBe "{}" }
    @Test fun `json is unconstrained even when nullable`() {
        mapJson(col("json", "json", nullable = true)) shouldBe "{}"
    }
    @Test fun `unmapped type degrades to empty schema and is flagged`() {
        val r = TypeMapper.map(col("inet", "inet"))
        om.writeValueAsString(r.node) shouldBe "{}"
        r.unmappedType shouldBe "inet"
    }
    @Test fun `mapped type reports no unmapped flag`() {
        TypeMapper.map(col("integer", "int4")).unmappedType shouldBe null
    }
}
