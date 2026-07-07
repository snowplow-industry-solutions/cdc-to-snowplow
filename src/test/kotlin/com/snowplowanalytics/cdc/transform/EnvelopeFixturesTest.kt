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

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EnvelopeFixturesTest {

    private val mapper = ObjectMapper()

    @Test
    fun `wraps payload under schema and payload top-level keys`() {
        val payload = """{"op":"c","after":{"id":1}}"""
        val out = envelopeWithSchema(payload, listOf(ColSpec("id", "int32")))

        val node = mapper.readTree(out)
        node.has("schema") shouldBe true
        node.has("payload") shouldBe true
        node.path("payload").path("op").asText() shouldBe "c"
    }

    @Test
    fun `produces a schema fields entry for after with one entry per ColSpec`() {
        val out = envelopeWithSchema(
            """{"op":"c"}""",
            listOf(
                ColSpec("id", "int32"),
                ColSpec("email", "string", optional = true),
            )
        )
        val afterFields = mapper.readTree(out)
            .path("schema").path("fields")
            .first { it.path("field").asText() == "after" }
            .path("fields")

        afterFields.size() shouldBe 2
        afterFields[0].path("field").asText() shouldBe "id"
        afterFields[0].path("type").asText() shouldBe "int32"
        afterFields[0].path("optional").asBoolean() shouldBe false
        afterFields[1].path("field").asText() shouldBe "email"
        afterFields[1].path("optional").asBoolean() shouldBe true
    }

    @Test
    fun `omits logical name when ColSpec logicalName is null`() {
        val out = envelopeWithSchema(
            """{"op":"c"}""",
            listOf(ColSpec("id", "int32"))
        )
        val firstAfterField = mapper.readTree(out)
            .path("schema").path("fields")
            .first { it.path("field").asText() == "after" }
            .path("fields")[0]

        firstAfterField.has("name") shouldBe false
    }

    @Test
    fun `includes logical name when ColSpec logicalName is set`() {
        val out = envelopeWithSchema(
            """{"op":"c"}""",
            listOf(ColSpec("created_at", "int64", logicalName = "io.debezium.time.MicroTimestamp"))
        )
        val firstAfterField = mapper.readTree(out)
            .path("schema").path("fields")
            .first { it.path("field").asText() == "after" }
            .path("fields")[0]

        firstAfterField.path("name").asText() shouldBe "io.debezium.time.MicroTimestamp"
    }
}
