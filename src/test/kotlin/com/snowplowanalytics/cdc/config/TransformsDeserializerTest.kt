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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.snowplowanalytics.cdc.transform.Transform
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TransformsDeserializerTest {

    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private fun parse(yamlList: String, column: String = "public.t.c"): List<Transform> {
        val node = mapper.readTree(yamlList)
        return TransformsDeserializer.parseList(node, column)
    }

    // ---- bare-string shape ----

    @Test fun `bare lowercase parses`() {
        parse("- lowercase").single() shouldBe Transform.Lowercase
    }

    @Test fun `bare uppercase parses`() {
        parse("- uppercase").single() shouldBe Transform.Uppercase
    }

    @Test fun `bare trim parses`() {
        parse("- trim").single() shouldBe Transform.Trim
    }

    @Test fun `multiple parameterless transforms preserve order`() {
        val out = parse(
            """
            |- trim
            |- lowercase
            """.trimMargin()
        )
        out shouldBe listOf(Transform.Trim, Transform.Lowercase)
    }

    // ---- regex_extract ----

    @Test fun `regex_extract with explicit group parses`() {
        val out = parse("- regex_extract: { pattern: '^(\\w+)@', group: 1 }").single()
        out.shouldBeInstanceOf<Transform.RegexExtract>()
        out.pattern.pattern() shouldBe "^(\\w+)@"
        out.group shouldBe 1
    }

    @Test fun `regex_extract defaults group to 1`() {
        val out = parse("- regex_extract: { pattern: '^(\\w+)@' }").single()
        (out as Transform.RegexExtract).group shouldBe 1
    }

    @Test fun `regex_extract group 0 is allowed`() {
        val out = parse("- regex_extract: { pattern: '\\d+', group: 0 }").single()
        (out as Transform.RegexExtract).group shouldBe 0
    }

    @Test fun `regex_extract OOB group rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: '\\d+', group: 2 }")
        }
        ex.message!! shouldContain "regex_extract"
        ex.message!! shouldContain "group 2"
        ex.message!! shouldContain "0 capturing groups"
    }

    @Test fun `regex_extract negative group rejected`() {
        assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: '\\d+', group: -1 }")
        }
    }

    @Test fun `regex_extract missing pattern rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { group: 1 }")
        }
        ex.message!! shouldContain "pattern"
    }

    @Test fun `regex_extract invalid pattern rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: '[unclosed' }")
        }
        ex.message!! shouldContain "regex_extract"
        ex.message!! shouldContain "pattern"
    }

    @Test fun `regex_extract non-string pattern rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: 42 }")
        }
        ex.message!! shouldContain "pattern"
    }

    @Test fun `regex_extract non-int group rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: '.', group: 'one' }")
        }
        ex.message!! shouldContain "group"
    }

    // ---- regex_replace ----

    @Test fun `regex_replace parses pattern and replacement`() {
        val out = parse("- regex_replace: { pattern: '\\s+', replacement: '_' }").single()
        out.shouldBeInstanceOf<Transform.RegexReplace>()
        out.pattern.pattern() shouldBe "\\s+"
        out.replacement shouldBe "_"
    }

    @Test fun `regex_replace allows backref replacement`() {
        val out = parse("- regex_replace: { pattern: '^([^@]+)@(.*)$', replacement: '***@\$2' }").single()
        (out as Transform.RegexReplace).replacement shouldBe "***@\$2"
    }

    @Test fun `regex_replace missing replacement rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_replace: { pattern: '\\s+' }")
        }
        ex.message!! shouldContain "replacement"
    }

    @Test fun `regex_replace missing pattern rejected`() {
        assertThrows<JsonMappingException> {
            parse("- regex_replace: { replacement: 'X' }")
        }
    }

    @Test fun `regex_replace non-string replacement rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_replace: { pattern: '.', replacement: 42 }")
        }
        ex.message!! shouldContain "replacement"
    }

    // ---- closed-set + shape errors ----

    @Test fun `unknown name rejected`() {
        val ex = assertThrows<JsonMappingException> { parse("- titlecase") }
        ex.message!! shouldContain "unknown transform"
        ex.message!! shouldContain "titlecase"
        ex.message!! shouldContain "lowercase"
    }

    @Test fun `parameterless transform with non-empty params rejected`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- lowercase: { foo: bar }")
        }
        ex.message!! shouldContain "lowercase"
        ex.message!! shouldContain "no parameters"
    }

    @Test fun `parameterless transform with empty params bag allowed`() {
        parse("- trim: {}").single() shouldBe Transform.Trim
    }

    @Test fun `column name appears in error message`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- titlecase", column = "public.orders.foo")
        }
        ex.message!! shouldContain "public.orders.foo"
    }

    @Test fun `empty list parses to empty list`() {
        parse("[]") shouldBe emptyList()
    }

    @Test fun `explicit null at the directive level is rejected`() {
        // YAML `transforms: null` (or bare `transforms:` with no value) parses to a NullNode,
        // not an array. Slice 6 rejects rather than silently treating it as empty — the
        // operator's intent is ambiguous and a loud error catches typos.
        val ex = assertThrows<JsonMappingException> { parse("null") }
        ex.message!! shouldContain "transforms must be a YAML list"
        ex.message!! shouldContain "NULL"
    }

    @Test fun `regex_extract rejects unknown param keys`() {
        // A typo of `group` (e.g. `grup: 0`) would otherwise be silently ignored and `group`
        // would default to 1, masking the misconfiguration. Closed-set on names should extend
        // to closed-set on parameter keys. Pattern uses a capture group so the OOB check (which
        // fires before the unknown-key check) doesn't shadow this assertion.
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_extract: { pattern: '(.)', grup: 0 }")
        }
        ex.message!! shouldContain "regex_extract"
        ex.message!! shouldContain "unknown parameter"
        ex.message!! shouldContain "grup"
        ex.message!! shouldContain "pattern, group"
    }

    @Test fun `regex_replace rejects unknown param keys`() {
        val ex = assertThrows<JsonMappingException> {
            parse("- regex_replace: { pattern: '.', replacement: '', extra: 1 }")
        }
        ex.message!! shouldContain "regex_replace"
        ex.message!! shouldContain "unknown parameter"
        ex.message!! shouldContain "extra"
    }

    @Test fun `multi-key map entry rejected`() {
        // Each list entry is bare or a single-key map. Two keys is ambiguous.
        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val node = mapper.readTree(
            "- {regex_extract: {pattern: '.'}, regex_replace: {pattern: '.', replacement: ''}}"
        )
        val ex = assertThrows<JsonMappingException> {
            TransformsDeserializer.parseList(node, "public.t.c")
        }
        ex.message!! shouldContain "single-key map"
        ex.message!! shouldContain "2 keys"
    }
}
