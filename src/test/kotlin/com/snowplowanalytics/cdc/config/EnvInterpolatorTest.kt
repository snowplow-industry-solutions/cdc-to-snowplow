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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

// SystemStubsExtension intercepts the JVM's System.getenv() before each test and restores it
// after — no process environment leaks across tests. EnvInterpolator defaults to System::getenv,
// so the stub is transparently in effect without any code change in the production class.
@ExtendWith(SystemStubsExtension::class)
class EnvInterpolatorTest {

    @SystemStub
    private val env = EnvironmentVariables()

    private val mapper = ObjectMapper(YAMLFactory())
    private val interpolator = EnvInterpolator()

    @Test
    fun `single env var is substituted in a string scalar`() {
        env.set("HOST", "db.internal")
        val node = mapper.readTree("source:\n  hostname: \${HOST}\n")
        val errors = interpolator.interpolate(node)
        errors.shouldBeEmpty()
        node.path("source").path("hostname").asText() shouldBe "db.internal"
    }

    @Test
    fun `multiple env vars in one scalar are all substituted`() {
        env.set("HOST", "db.internal")
        env.set("PORT", "5432")
        val node = mapper.readTree("collector_url: \"http://\${HOST}:\${PORT}/track\"\n")
        interpolator.interpolate(node).shouldBeEmpty()
        node.path("collector_url").asText() shouldBe "http://db.internal:5432/track"
    }

    // Env-var interpolation happens post-YAML-parse (on the JsonNode tree) so the env value is
    // substituted as a raw string into the already-parsed node — YAML-active characters in the
    // value (`:`, `{`, `"`) cannot disturb the YAML structure.
    @Test
    fun `env var containing yaml-active characters is preserved verbatim`() {
        env.set("WEIRD", "pa\$\$:word!\"")
        val node = mapper.readTree("password: \${WEIRD}\n")
        interpolator.interpolate(node).shouldBeEmpty()
        node.path("password").asText() shouldBe "pa\$\$:word!\""
    }

    @Test
    fun `unset env var produces an UnsetEnvVar error with the JSON path`() {
        // POSTGRES_PASSWORD intentionally not set on env
        val node = mapper.readTree("source:\n  password: \${POSTGRES_PASSWORD}\n")
        val errors = interpolator.interpolate(node)
        errors shouldHaveSize 1
        val err = errors[0] as ConfigError.UnsetEnvVar
        err.varName shouldBe "POSTGRES_PASSWORD"
        err.path shouldBe "source.password"
    }

    @Test
    fun `multiple unset vars across the tree all accumulate`() {
        val node = mapper.readTree(
            """
            source:
              hostname: ${'$'}{MISSING_HOST}
            snowplow:
              collector_url: ${'$'}{MISSING_COLLECTOR}
            """.trimIndent()
        )
        val errors = interpolator.interpolate(node)
        errors.map { (it as ConfigError.UnsetEnvVar).varName }.toSet() shouldBe
            setOf("MISSING_HOST", "MISSING_COLLECTOR")
    }

    @Test
    fun `non-string nodes are not touched`() {
        val node = mapper.readTree("port: 5432\nflag: true\nthing: null\n")
        interpolator.interpolate(node).shouldBeEmpty()
        node.path("port").asInt() shouldBe 5432
        node.path("flag").asBoolean() shouldBe true
        node.path("thing").isNull shouldBe true
    }

    @Test
    fun `nested object and array traversal works`() {
        env.set("URI", "iglu:x/y/jsonschema/1-0-0")
        val node = mapper.readTree(
            """
            tables:
              - name: orders
                iglu_schema: ${'$'}{URI}
            """.trimIndent()
        )
        interpolator.interpolate(node).shouldBeEmpty()
        node.path("tables").get(0).path("iglu_schema").asText() shouldBe "iglu:x/y/jsonschema/1-0-0"
    }

    @Test
    fun `scalar with no tokens is unchanged`() {
        val node = mapper.readTree("name: orders\n")
        interpolator.interpolate(node).shouldBeEmpty()
        node.path("name").asText() shouldBe "orders"
    }
}
