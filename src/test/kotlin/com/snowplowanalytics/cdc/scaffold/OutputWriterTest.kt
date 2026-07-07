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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class OutputWriterTest {
    private val files = listOf(
        ScaffoldFile("config.yaml", "hello: world"),
        ScaffoldFile("schemas/com.acme/orders_change/jsonschema/1-0-0", "{}"),
    )

    @Test fun `creates a non-existent directory and writes files`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        OutputWriter.write(out, files)
        out.resolve("config.yaml").readText() shouldBe "hello: world"
        out.resolve("schemas/com.acme/orders_change/jsonschema/1-0-0").readText() shouldBe "{}"
    }

    @Test fun `writes into a pre-existing empty directory`(@TempDir tmp: Path) {
        OutputWriter.write(tmp, files)
        tmp.resolve("config.yaml").exists() shouldBe true
    }

    @Test fun `refuses a non-empty directory and writes nothing`(@TempDir tmp: Path) {
        tmp.resolve("preexisting.txt").writeText("keep me")
        shouldThrow<ScaffoldOutputError> { OutputWriter.write(tmp, files) }.message!! shouldContain "not empty"
        tmp.resolve("config.yaml").exists() shouldBe false
    }

    @Test fun `refuses when output path is a file`(@TempDir tmp: Path) {
        val f = tmp.resolve("afile").also { it.createFile() }
        shouldThrow<ScaffoldOutputError> { OutputWriter.write(f, files) }.message!! shouldContain "not a directory"
    }

    @Test fun `rollback removes created root when a write fails`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        val bad = listOf(
            ScaffoldFile("config.yaml", "ok"),
            ScaffoldFile("config.yaml/nested", "boom"), // config.yaml is a file, can't be a dir
        )
        shouldThrow<ScaffoldOutputError> { OutputWriter.write(out, bad) }
        out.exists() shouldBe false   // created root rolled back entirely
    }

    @Test fun `rollback leaves a pre-existing empty dir in place but empty`(@TempDir tmp: Path) {
        val bad = listOf(
            ScaffoldFile("config.yaml", "ok"),
            ScaffoldFile("config.yaml/nested", "boom"),
        )
        shouldThrow<ScaffoldOutputError> { OutputWriter.write(tmp, bad) }
        tmp.exists() shouldBe true
        Files.list(tmp).use { it.count() shouldBe 0L }
    }

    @Test fun `validateState throws on non-empty dir without creating anything`(@TempDir tmp: Path) {
        tmp.resolve("keep.txt").writeText("x")
        shouldThrow<ScaffoldOutputError> { OutputWriter.validateState(tmp) }.message!! shouldContain "not empty"
    }

    @Test fun `validateState passes for an absent path and creates nothing`(@TempDir tmp: Path) {
        val out = tmp.resolve("nope")
        OutputWriter.validateState(out)   // no throw
        out.exists() shouldBe false       // did not create it
    }
}
