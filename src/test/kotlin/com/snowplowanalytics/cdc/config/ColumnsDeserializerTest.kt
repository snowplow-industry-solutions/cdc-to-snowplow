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

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.snowplowanalytics.cdc.transform.Transform
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ColumnsDeserializerTest {

    data class Holder(
        @JsonDeserialize(using = ColumnsDeserializer::class)
        val columns: List<ColumnSpec>,
    )

    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    private fun parse(yaml: String): List<ColumnSpec> =
        mapper.readValue("columns:\n$yaml", Holder::class.java).columns

    @Test
    fun `bare string entry parses as plain ColumnSpec`() {
        parse("  - id") shouldBe listOf(ColumnSpec("id"))
    }

    @Test
    fun `inline map with rename parses`() {
        parse("  - customer_id: { rename: customerId }") shouldBe
            listOf(ColumnSpec("customer_id", rename = "customerId"))
    }

    @Test
    fun `inline map with exclude=true parses`() {
        parse("  - password_hash: { exclude: true }") shouldBe
            listOf(ColumnSpec("password_hash", exclude = true))
    }

    @Test
    fun `block map with rename parses`() {
        val yaml = """
            |  - email:
            |      rename: emailAddress
        """.trimMargin()
        parse(yaml) shouldBe listOf(ColumnSpec("email", rename = "emailAddress"))
    }

    @Test
    fun `transforms directive parses into ColumnSpec alongside rename`() {
        // Slice 5 previously tolerated `transforms:` as an unknown directive; Slice 6 parses it.
        val yaml = """
            |  - email:
            |      rename: emailAddress
            |      transforms: [lowercase, trim]
        """.trimMargin()
        parse(yaml) shouldBe listOf(
            ColumnSpec("email", rename = "emailAddress",
                       transforms = listOf(Transform.Lowercase, Transform.Trim))
        )
    }

    @Test
    fun `bare transforms list without other directives parses`() {
        val yaml = """
            |  - description:
            |      transforms:
            |        - trim
            |        - lowercase
        """.trimMargin()
        parse(yaml) shouldBe listOf(
            ColumnSpec("description", transforms = listOf(Transform.Trim, Transform.Lowercase))
        )
    }

    @Test
    fun `empty transforms list parses to empty list`() {
        val yaml = """
            |  - email:
            |      transforms: []
        """.trimMargin()
        parse(yaml) shouldBe listOf(ColumnSpec("email"))
    }

    @Test
    fun `unknown non-transforms directive keys are still tolerated`() {
        // The deserializer remains tolerant of unknown directive keys other than rename, exclude,
        // transforms — same convention as Slice 5.
        val yaml = """
            |  - email:
            |      rename: emailAddress
            |      future_directive: { foo: bar }
        """.trimMargin()
        parse(yaml) shouldBe listOf(ColumnSpec("email", rename = "emailAddress"))
    }

    @Test
    fun `mixed entries in one list parse independently`() {
        val yaml = """
            |  - id
            |  - password_hash: { exclude: true }
            |  - customer_id: { rename: customerId }
        """.trimMargin()
        parse(yaml) shouldBe listOf(
            ColumnSpec("id"),
            ColumnSpec("password_hash", exclude = true),
            ColumnSpec("customer_id", rename = "customerId"),
        )
    }

    @Test
    fun `non-list value throws a mapping error`() {
        assertThrows<JsonMappingException> { parse("  foo: bar") }
    }

    @Test
    fun `under-indented directive surfaces a did-you-mean hint naming the inferred column`() {
        // Reproduces the operator mistake from issue #20: `transforms:` written at the same
        // indent as `- status:` is parsed by YAML as two sibling keys at the list-item level.
        // The error message should call out the under-indent pattern and show the correct shape.
        val ex = assertThrows<JsonMappingException> {
            parse(
                """
                |  - status:
                |    transforms: [trim]
                """.trimMargin()
            )
        }
        val msg = ex.message ?: ""
        msg shouldContain "multi-key map [status, transforms]"
        msg shouldContain "directive was under-indented"
        msg shouldContain "- status:"
        msg shouldContain "transforms: <value>"
    }

    @Test
    fun `multi-key entry with no directive keys keeps the original message`() {
        // When neither key is a known directive (rename/exclude/transforms) the heuristic
        // can't infer the operator's intent, so we fall back to the original generic message.
        val ex = assertThrows<JsonMappingException> {
            parse("  - { foo: 1, bar: 2 }")
        }
        (ex.message ?: "") shouldContain "must be a single-key map"
    }

    @Test
    fun `non-string rename throws a mapping error`() {
        assertThrows<JsonMappingException> {
            parse("  - customer_id: { rename: 123 }")
        }
    }

    @Test
    fun `non-boolean exclude throws a mapping error`() {
        assertThrows<JsonMappingException> {
            parse("""  - password_hash: { exclude: "true" }""")
        }
    }
}
