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

package com.snowplowanalytics.cdc.component

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.snowplowanalytics.cdc.RunCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class ConfigSourceCliTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `passing both --config and --config-env is a usage error`() {
        val cfg = Files.createTempFile(tmp, "config", ".yaml").apply { writeText("service: {}\n") }
        assertThrows<UsageError> {
            RunCommand().parse(listOf("--config", cfg.toString(), "--config-env", "CDC_CONFIG"))
        }
    }

    @Test
    fun `passing neither --config nor --config-env is a usage error`() {
        assertThrows<UsageError> { RunCommand().parse(emptyList()) }
    }
}
