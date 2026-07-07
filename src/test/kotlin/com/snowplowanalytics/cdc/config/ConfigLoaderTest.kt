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

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

// @ExtendWith(SystemStubsExtension::class) registers the JUnit 5 extension that activates
// fields annotated @SystemStub before each test and restores them after — hermetic env-var
// stubbing without any mocking framework.
@ExtendWith(SystemStubsExtension::class)
class ConfigLoaderTest {

    // @SystemStub marks this field as managed by SystemStubsExtension. The extension calls
    // env.setup() before each test and env.teardown() after, so env vars set in one test
    // never leak into the next.
    @SystemStub
    private val env = EnvironmentVariables()

    // @TempDir is a JUnit 5 annotation that creates a fresh temporary directory for each test
    // and deletes it (and all its contents) after the test completes. `lateinit var` defers
    // initialization — JUnit injects the value just before the test runs.
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `valid YAML produces a fully bound Config`() {
        env.set("PG_PASSWORD", "secret")
        val cfg = load(validBaseYaml())
        cfg.service.appId shouldBe "orders-cdc"
        cfg.source.connector shouldBe "postgres"
        cfg.source.password shouldBe "secret"
        cfg.tables shouldHaveSize 1
        cfg.tables[0].name shouldBe "orders"
        cfg.tables[0].columns.map { it.name } shouldBe listOf("id", "customer_id", "status", "total")
    }

    @Test
    fun `column entry as a single-key map yields just the column name`() {
        val yaml = validBaseYaml().replace(
            "      - id\n      - customer_id\n      - status\n      - total",
            "      - id\n      - email: { transforms: [lowercase, trim] }\n      - status",
        ).replace(
            "primary_key: [id]",
            "primary_key: [id]"
        )
        env.set("PG_PASSWORD", "secret")
        val cfg = load(yaml)
        cfg.tables[0].columns.map { it.name } shouldBe listOf("id", "email", "status")
    }

    @Test
    fun `yaml syntax error produces a YamlSyntax error`() {
        val errors = loadAndCollectErrors(": this is :: not yaml :::\n")
        errors[0].shouldBeInstanceOf<ConfigError.YamlSyntax>()
    }

    @Test
    fun `unknown top-level key produces an UnknownKey error`() {
        val yaml = validBaseYaml() + "\nfoobar: 42\n"
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.UnknownKey>()
        errors[0].path shouldBe "foobar"
    }

    @Test
    fun `unknown nested key produces an UnknownKey error`() {
        val yaml = validBaseYaml().replace(
            "primary_key: [id]",
            "primary_key: [id]\n    extra_key: 1",
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.UnknownKey>()
    }

    @Test
    fun `missing required field produces a MissingField error`() {
        val yaml = validBaseYaml().replace("primary_key: [id]\n    ", "")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.MissingField>()
    }

    @Test
    fun `wrong type produces an InvalidValue error`() {
        val yaml = validBaseYaml().replace("port: 5432", "port: \"not-a-number\"")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.InvalidValue>()
    }

    @Test
    fun `unset env var produces an UnsetEnvVar error`() {
        // PG_PASSWORD deliberately not set
        val errors = loadAndCollectErrors(validBaseYaml())
        errors[0].shouldBeInstanceOf<ConfigError.UnsetEnvVar>()
        (errors[0] as ConfigError.UnsetEnvVar).varName shouldBe "PG_PASSWORD"
    }

    @Test
    fun `empty tables list is rejected`() {
        val yaml = validBaseYaml().replaceAfter("tables:", "\n  []\n").trim()
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "tables" } shouldBe true
    }

    @Test
    fun `empty primary_key is rejected`() {
        val yaml = validBaseYaml().replace("primary_key: [id]", "primary_key: []")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "tables[0].primary_key" } shouldBe true
    }

    @Test
    fun `empty columns list is rejected`() {
        val yaml = validBaseYaml().replace(
            "    columns:\n      - id\n      - customer_id\n      - status\n      - total",
            "    columns: []",
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "tables[0].columns" } shouldBe true
    }

    @Test
    fun `connector other than postgres is rejected`() {
        val yaml = validBaseYaml().replace("connector: postgres", "connector: mysql")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "source.connector" } shouldBe true
    }

    @Test
    fun `invalid snapshot_mode is rejected`() {
        val yaml = validBaseYaml().replace("snapshot_mode: never", "snapshot_mode: hourly")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "debezium.snapshot_mode" } shouldBe true
    }

    @Test
    fun `invalid publication_autocreate_mode is rejected`() {
        val yaml = validBaseYaml().replace(
            "publication_autocreate_mode: filtered",
            "publication_autocreate_mode: maybe",
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "debezium.publication_autocreate_mode" } shouldBe true
    }

    @Test
    fun `negative heartbeat_interval_ms is rejected`() {
        val yaml = validBaseYaml().replace("heartbeat_interval_ms: 30000", "heartbeat_interval_ms: -1")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "debezium.heartbeat_interval_ms" } shouldBe true
    }

    @Test
    fun `malformed iglu URI on table is rejected`() {
        val yaml = validBaseYaml().replace(
            "iglu:com.example/orders_change/jsonschema/1-0-0",
            "not-a-uri",
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "tables[0].iglu_schema" } shouldBe true
    }

    @Test
    fun `duplicate schema name pair is rejected`() {
        val yaml = validBaseYaml().replace(
            "  - name: orders\n    schema: public\n    iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0\n    primary_key: [id]\n    columns:\n      - id\n      - customer_id\n      - status\n      - total",
            // include the original entry plus a verbatim copy
            "  - name: orders\n    schema: public\n    iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0\n    primary_key: [id]\n    columns:\n      - id\n  - name: orders\n    schema: public\n    iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0\n    primary_key: [id]\n    columns:\n      - id",
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.any { it.path == "tables" && it.message.contains("duplicate") } shouldBe true
    }

    @Test
    fun `multiple semantic violations are accumulated`() {
        val yaml = validBaseYaml()
            .replace("connector: postgres", "connector: mysql")
            .replace("snapshot_mode: never", "snapshot_mode: hourly")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors.map { it.path }.toSet() shouldBe setOf("source.connector", "debezium.snapshot_mode")
    }

    @Test
    fun `non-list columns is rejected as InvalidValue`() {
        val yaml = validBaseYaml().replace(
            "    columns:\n      - id\n      - customer_id\n      - status\n      - total",
            "    columns: \"id\""
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.InvalidValue>()
    }

    @Test
    fun `non-string non-map column entry is rejected as InvalidValue`() {
        val yaml = validBaseYaml().replace(
            "    columns:\n      - id\n      - customer_id\n      - status\n      - total",
            "    columns:\n      - 42"
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.InvalidValue>()
    }

    @Test
    fun `multi-key map column entry is rejected as InvalidValue`() {
        val yaml = validBaseYaml().replace(
            "    columns:\n      - id\n      - customer_id\n      - status\n      - total",
            "    columns:\n      - foo: 1\n        bar: 2"
        )
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.InvalidValue>()
    }

    // Regression guard for issue #20: the shipped examples/config.yaml must continue to load
    // successfully. Catches accidental edits to the example file that break it at runtime —
    // e.g. introducing an under-indented directive while reproducing the original bug.
    @Test
    fun `shipped examples config yaml loads successfully`() {
        env.set("POSTGRES_PASSWORD", "secret")
        env.set("COLLECTOR_URL", "http://collector:9090")
        val cfg = ConfigLoader.load(Path.of("examples/config.yaml"))
        cfg.service.appId shouldBe "orders-cdc"
        cfg.tables shouldHaveSize 1
        cfg.tables[0].name shouldBe "orders"
    }

    // Regression test: an earlier version of translateBindingError() checked whether the
    // exception message contained "missing" to distinguish MissingField from InvalidValue.
    // A type error whose invalid value happened to contain "missing" was misclassified as
    // MissingField. The fix was to branch on exception TYPE (MissingKotlinParameterException),
    // not message text.
    @Test
    fun `wrong type with the word 'missing' in its value is still InvalidValue`() {
        val yaml = validBaseYaml().replace("port: 5432", "port: \"missing-value\"")
        env.set("PG_PASSWORD", "secret")
        val errors = loadAndCollectErrors(yaml)
        errors[0].shouldBeInstanceOf<ConfigError.InvalidValue>()
    }

    @Test
    fun `loadFromString binds a Config and labels errors with the given source`() {
        env.set("PG_PASSWORD", "secret")
        val cfg = ConfigLoader.loadFromString(validBaseYaml(), "env:CDC_CONFIG")
        cfg.service.appId shouldBe "orders-cdc"

        val ex = assertThrows<ConfigException> {
            ConfigLoader.loadFromString("foobar: 42\n", "env:CDC_CONFIG")
        }
        ex.source shouldBe "env:CDC_CONFIG"
    }

    @Test
    fun `loadFromString rejects a blank config body with a clear empty message`() {
        // A whitespace-only body parses to a Jackson MissingNode, which would otherwise surface
        // as an opaque "Cannot deserialize ... [Unavailable value]" binding error. Guard it so
        // the operator sees a plain "configuration is empty" instead.
        val ex = assertThrows<ConfigException> {
            ConfigLoader.loadFromString("   \n  ", "env:CDC_CONFIG")
        }
        ex.source shouldBe "env:CDC_CONFIG"
        ex.message shouldContain "empty"
    }

    @Test
    fun `jdbc offset_store block binds correctly`() {
        val yaml = """
            service:
              app_id: orders-cdc
            source:
              connector: postgres
              hostname: localhost
              port: 5432
              database: orders_db
              username: cdc
              password: ${'$'}{PG_PASSWORD}
              slot_name: snowplow_cdc
              publication_name: snowplow_cdc_pub
            debezium:
              snapshot_mode: never
              offset_store:
                type: jdbc
                jdbc:
                  username: cdc_offset
                  password: secret
                  table_name: cdc_offset_storage
              publication_autocreate_mode: filtered
            snowplow:
              collector_url: http://collector:9090
              cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
            tables:
              - name: orders
                schema: public
                iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
                primary_key: [id]
                columns:
                  - id
                  - customer_id
                  - status
                  - total
        """.trimIndent()
        env.set("PG_PASSWORD", "secret")
        val config = load(yaml)
        config.debezium.offsetStore.type shouldBe "jdbc"
        config.debezium.offsetStore.jdbc?.username shouldBe "cdc_offset"
    }

    private fun validBaseYaml(): String = """
        service:
          app_id: orders-cdc
        source:
          connector: postgres
          hostname: localhost
          port: 5432
          database: orders_db
          username: cdc
          password: ${'$'}{PG_PASSWORD}
          slot_name: snowplow_cdc
          publication_name: snowplow_cdc_pub
        debezium:
          snapshot_mode: never
          offset_store:
            type: file
            file_path: /tmp/offsets.dat
          heartbeat_interval_ms: 30000
          publication_autocreate_mode: filtered
          provide_transaction_metadata: false
        snowplow:
          collector_url: http://collector:9090
          cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
          emitter:
            batch_size: 1
            buffer_capacity: 1000
        tables:
          - name: orders
            schema: public
            iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
            primary_key: [id]
            columns:
              - id
              - customer_id
              - status
              - total
    """.trimIndent()

    // Writes YAML to a temp file and calls ConfigLoader.load() — the same code path as
    // production. Tests never call ConfigLoader internals directly.
    private fun load(yaml: String): Config {
        val path = Files.createTempFile(tmp, "config", ".yaml")
        path.writeText(yaml)
        return ConfigLoader.load(path)
    }

    // assertThrows<T> is JUnit 5's typed version of the classic try/catch assertion — returns
    // the caught exception so we can assert on its fields (e.g. ex.errors).
    private fun loadAndCollectErrors(yaml: String): List<ConfigError> {
        val ex = assertThrows<ConfigException> { load(yaml) }
        return ex.errors
    }
}
