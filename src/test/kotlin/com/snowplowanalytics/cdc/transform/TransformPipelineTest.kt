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

import com.snowplowanalytics.cdc.config.ColumnSpec
import com.snowplowanalytics.cdc.config.TableConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class TransformPipelineTest {

    private fun table(vararg columns: ColumnSpec): TableConfig = TableConfig(
        name = "t", schema = "public",
        igluSchema = "iglu:com.example/t/jsonschema/1-0-0",
        primaryKey = listOf("id"),
        columns = columns.toList(),
    )

    @Test
    fun `null input map returns null`() {
        val pipeline = TransformPipeline(table(ColumnSpec("id")))
        pipeline.apply(null) shouldBe null
    }

    @Test
    fun `pipeline with no transforms returns input unchanged`() {
        val pipeline = TransformPipeline(table(
            ColumnSpec("id"),
            ColumnSpec("email"),
        ))
        val input = linkedMapOf<String, Any?>("id" to 1, "email" to "a@b")
        pipeline.apply(input) shouldBe input
    }

    @Test
    fun `single transform applies to its column`() {
        val pipeline = TransformPipeline(table(
            ColumnSpec("id"),
            ColumnSpec("email", transforms = listOf(Transform.Lowercase)),
        ))
        val out = pipeline.apply(linkedMapOf("id" to 1, "email" to "ABC"))
        out!!["email"] shouldBe "abc"
        out["id"] shouldBe 1
    }

    @Test
    fun `chain applies in declared order`() {
        // Order-divergent fixture on input "AbA":
        // [regex_replace { A -> X }, lowercase] -> "XbX" -> "xbx"
        // [lowercase, regex_replace { A -> X }] -> "aba" -> "aba"  (pattern misses lowercase 'a')
        val order1 = TransformPipeline(table(
            ColumnSpec("c", transforms = listOf(
                Transform.RegexReplace(Pattern.compile("A"), "X"),
                Transform.Lowercase,
            )),
        ))
        val order2 = TransformPipeline(table(
            ColumnSpec("c", transforms = listOf(
                Transform.Lowercase,
                Transform.RegexReplace(Pattern.compile("A"), "X"),
            )),
        ))
        order1.apply(linkedMapOf("c" to "AbA"))!!["c"] shouldBe "xbx"
        order2.apply(linkedMapOf("c" to "AbA"))!!["c"] shouldBe "aba"
    }

    @Test
    fun `chain on renamed column is keyed by outputName`() {
        // Pipeline runs after ColumnProjector, which has already renamed `email` -> `emailAddress`.
        val pipeline = TransformPipeline(table(
            ColumnSpec("email", rename = "emailAddress",
                       transforms = listOf(Transform.Lowercase)),
        ))
        val projected = linkedMapOf<String, Any?>("emailAddress" to "ABC")
        pipeline.apply(projected)!!["emailAddress"] shouldBe "abc"
    }

    @Test
    fun `excluded column is not in the chain map`() {
        // Excluded columns never appear in the projected map; the pipeline shouldn't have a
        // chain entry for them either.
        val pipeline = TransformPipeline(table(
            ColumnSpec("id"),
            ColumnSpec("password_hash", exclude = true),
        ))
        val projected = linkedMapOf<String, Any?>("id" to 1)
        pipeline.apply(projected) shouldBe linkedMapOf<String, Any?>("id" to 1)
    }

    @Test
    fun `preserves key order of projected map`() {
        val pipeline = TransformPipeline(table(
            ColumnSpec("a", transforms = listOf(Transform.Uppercase)),
            ColumnSpec("b"),
            ColumnSpec("c", transforms = listOf(Transform.Trim)),
        ))
        val input = linkedMapOf<String, Any?>("a" to "x", "b" to "y", "c" to "  z  ")
        val out = pipeline.apply(input)!!
        out.keys.toList() shouldBe listOf("a", "b", "c")
        out["a"] shouldBe "X"
        out["b"] shouldBe "y"
        out["c"] shouldBe "z"
    }

    @Test
    fun `null cell propagates through chain`() {
        val pipeline = TransformPipeline(table(
            ColumnSpec("c", transforms = listOf(Transform.Trim, Transform.Lowercase)),
        ))
        pipeline.apply(linkedMapOf("c" to null))!!["c"] shouldBe null
    }

    @Test
    fun `non-String cell at runtime propagates IllegalStateException through the pipeline`() {
        // DDL-drift safety net (spec §6 last error-table row). Startup type-checking rejects
        // non-string columns, so this path is only reachable mid-stream — but when it fires the
        // pipeline must not swallow it: the throw lands in EngineRunner.handleRecord's catch.
        val pipeline = TransformPipeline(table(
            ColumnSpec("c", transforms = listOf(Transform.Lowercase)),
        ))
        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            pipeline.apply(linkedMapOf("c" to 42))
        }
        ex.message!! shouldContain "c"
        ex.message!! shouldContain "Int"
    }
}
