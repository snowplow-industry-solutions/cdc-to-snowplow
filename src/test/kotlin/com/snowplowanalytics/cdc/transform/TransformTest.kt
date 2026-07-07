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

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.regex.Pattern

class TransformTest {

    private val col = "public.t.c"

    // ---- Lowercase ----

    @Test fun `lowercase converts ASCII`() {
        Transform.Lowercase.apply("ABC", col) shouldBe "abc"
    }

    @Test fun `lowercase preserves already-lowercase`() {
        Transform.Lowercase.apply("abc", col) shouldBe "abc"
    }

    @Test fun `lowercase handles empty string`() {
        Transform.Lowercase.apply("", col) shouldBe ""
    }

    @Test fun `lowercase preserves null`() {
        Transform.Lowercase.apply(null, col) shouldBe null
    }

    @Test fun `lowercase throws on non-String non-null cell`() {
        val ex = assertThrows<IllegalStateException> {
            Transform.Lowercase.apply(42, col)
        }
        ex.message!! shouldContain col
        ex.message!! shouldContain "Int"
    }

    // ---- Uppercase ----

    @Test fun `uppercase converts ASCII`() {
        Transform.Uppercase.apply("abc", col) shouldBe "ABC"
    }

    @Test fun `uppercase preserves null`() {
        Transform.Uppercase.apply(null, col) shouldBe null
    }

    @Test fun `uppercase throws on non-String non-null cell`() {
        assertThrows<IllegalStateException> { Transform.Uppercase.apply(1.5, col) }
    }

    // ---- Trim ----

    @Test fun `trim removes leading and trailing whitespace`() {
        Transform.Trim.apply("  abc  ", col) shouldBe "abc"
    }

    @Test fun `trim leaves internal whitespace`() {
        Transform.Trim.apply("a b c", col) shouldBe "a b c"
    }

    @Test fun `trim of whitespace-only string returns empty`() {
        Transform.Trim.apply("   ", col) shouldBe ""
    }

    @Test fun `trim preserves null`() {
        Transform.Trim.apply(null, col) shouldBe null
    }

    // ---- RegexExtract ----

    @Test fun `regex_extract returns null on no match`() {
        val t = Transform.RegexExtract(Pattern.compile("\\d+"), group = 0)
        t.apply("no digits here", col) shouldBe null
    }

    @Test fun `regex_extract group 0 returns whole match`() {
        val t = Transform.RegexExtract(Pattern.compile("\\d+"), group = 0)
        t.apply("abc 123 def", col) shouldBe "123"
    }

    @Test fun `regex_extract group 1 returns first capture`() {
        val t = Transform.RegexExtract(Pattern.compile("^(\\w+)@"), group = 1)
        t.apply("alice@example.com", col) shouldBe "alice"
    }

    @Test fun `regex_extract group 2 returns second capture`() {
        val t = Transform.RegexExtract(Pattern.compile("^([a-z]+)-(\\d+)$"), group = 2)
        t.apply("order-42", col) shouldBe "42"
    }

    @Test fun `regex_extract returns first match on multi-match`() {
        val t = Transform.RegexExtract(Pattern.compile("\\d+"), group = 0)
        t.apply("a1 b22 c333", col) shouldBe "1"
    }

    @Test fun `regex_extract preserves null`() {
        val t = Transform.RegexExtract(Pattern.compile("."), group = 0)
        t.apply(null, col) shouldBe null
    }

    // ---- RegexReplace ----

    @Test fun `regex_replace literal substitution`() {
        val t = Transform.RegexReplace(Pattern.compile("\\s+"), replacement = "_")
        t.apply("hello  world", col) shouldBe "hello_world"
    }

    @Test fun `regex_replace replaces all matches`() {
        val t = Transform.RegexReplace(Pattern.compile("a"), replacement = "X")
        t.apply("banana", col) shouldBe "bXnXnX"
    }

    @Test fun `regex_replace supports backreferences`() {
        val t = Transform.RegexReplace(Pattern.compile("^[^@]+@(.*)$"), replacement = "***@$1")
        t.apply("alice@example.com", col) shouldBe "***@example.com"
    }

    @Test fun `regex_replace on no match returns input unchanged`() {
        val t = Transform.RegexReplace(Pattern.compile("z"), replacement = "X")
        t.apply("abc", col) shouldBe "abc"
    }

    @Test fun `regex_replace preserves null`() {
        val t = Transform.RegexReplace(Pattern.compile("."), replacement = "")
        t.apply(null, col) shouldBe null
    }

    // ---- name property ----

    @Test fun `each case exposes its YAML keyword as name`() {
        Transform.Lowercase.name shouldBe "lowercase"
        Transform.Uppercase.name shouldBe "uppercase"
        Transform.Trim.name shouldBe "trim"
        Transform.RegexExtract(Pattern.compile("."), 0).name shouldBe "regex_extract"
        Transform.RegexReplace(Pattern.compile("."), "").name shouldBe "regex_replace"
    }
}
