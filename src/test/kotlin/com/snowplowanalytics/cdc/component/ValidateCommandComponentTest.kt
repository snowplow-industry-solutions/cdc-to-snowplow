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

import com.snowplowanalytics.cdc.config.ConfigSource
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.validate.ValidateRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText

@ExtendWith(RequiresDocker::class)
@Testcontainers
class ValidateCommandComponentTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("cdc")
                .withUsername("cdc")
                .withPassword("cdc")
                .withInitScript("init-validate-drift.sql")
    }

    @Test
    fun `validate emits a drift report covering all four severity categories and exits 1`(@TempDir tmp: Path) {
        val configFile = tmp.resolve("config.yaml")
        configFile.writeText(buildAllTablesConfig())

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = ValidateRunner.run(ConfigSource.File(configFile), PrintStream(out), PrintStream(err))

        val stdout = out.toString(Charsets.UTF_8)
        val stderr = err.toString(Charsets.UTF_8)

        code shouldBe 1
        stderr.shouldBeEmpty()

        // Spot-check one line per severity category; the exhaustive line-by-line contract is
        // pinned by DriftReportFormatterTest. The component test proves the pieces wire up.
        stdout shouldContain "[OK]    public.clean_table"
        stdout shouldContain "[ERROR] public.missing_and_extra.user_email — configured column not found"
        stdout shouldContain "[WARN]  public.missing_and_extra.created_at — present in database, not in config"
        stdout shouldContain "[WARN]  public.replica_and_type — REPLICA IDENTITY 'default'"
        stdout shouldContain "[ERROR] public.replica_and_type.amount_cents — transform 'lowercase'"
        stdout shouldContain "validate: 2 errors, 2 warnings across 3 tables"
    }

    @Test
    fun `validate exits 0 with no errors when only WARN-level drift exists`(@TempDir tmp: Path) {
        // Only replica_and_type is configured, both columns listed correctly, no transforms.
        // The only finding should be the REPLICA IDENTITY default WARN.
        val configFile = tmp.resolve("config.yaml")
        configFile.writeText(buildWarnOnlyConfig())

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = ValidateRunner.run(ConfigSource.File(configFile), PrintStream(out), PrintStream(err))

        val stdout = out.toString(Charsets.UTF_8)
        val stderr = err.toString(Charsets.UTF_8)

        code shouldBe 0
        stderr.shouldBeEmpty()
        stdout shouldContain "[WARN]  public.replica_and_type — REPLICA IDENTITY 'default'"
        stdout shouldContain "validate: 0 errors, 1 warnings across 1 tables"
    }

    @Test
    fun `validate exits 2 when the config file cannot be loaded`(@TempDir tmp: Path) {
        val bogus = tmp.resolve("nope.yaml").also { it.writeText("not: valid: yaml: at: all") }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = ValidateRunner.run(ConfigSource.File(bogus), PrintStream(out), PrintStream(err))

        code shouldBe 2
        out.toString(Charsets.UTF_8).shouldBeEmpty()
        // ConfigException's message starts with "Configuration error(s) in <path>:" — pick a
        // substring that's robust to the exact YAML parser wording.
        err.toString(Charsets.UTF_8) shouldContain "Configuration error"
    }

    private fun buildAllTablesConfig(): String = """
        service:
          app_id: cdc
        source:
          connector: postgres
          hostname: ${postgres.host}
          port: ${postgres.firstMappedPort}
          database: cdc
          username: cdc
          password: cdc
          slot_name: unused
          publication_name: unused
        debezium:
          snapshot_mode: never
          offset_store:
            type: file
            file_path: /tmp/unused.dat
          heartbeat_interval_ms: 0
          publication_autocreate_mode: filtered
          provide_transaction_metadata: false
        snowplow:
          collector_url: http://unused
          cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
        tables:
          - name: clean_table
            schema: public
            iglu_schema: iglu:com.example/clean/jsonschema/1-0-0
            primary_key: [id]
            columns: [id, name]
          - name: missing_and_extra
            schema: public
            iglu_schema: iglu:com.example/me/jsonschema/1-0-0
            primary_key: [id]
            columns:
              - id
              - email
              - user_email
          - name: replica_and_type
            schema: public
            iglu_schema: iglu:com.example/rt/jsonschema/1-0-0
            primary_key: [id]
            columns:
              - id
              - amount_cents: { transforms: [lowercase] }
    """.trimIndent()

    private fun buildWarnOnlyConfig(): String = """
        service:
          app_id: cdc
        source:
          connector: postgres
          hostname: ${postgres.host}
          port: ${postgres.firstMappedPort}
          database: cdc
          username: cdc
          password: cdc
          slot_name: unused
          publication_name: unused
        debezium:
          snapshot_mode: never
          offset_store:
            type: file
            file_path: /tmp/unused.dat
          heartbeat_interval_ms: 0
          publication_autocreate_mode: filtered
          provide_transaction_metadata: false
        snowplow:
          collector_url: http://unused
          cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
        tables:
          - name: replica_and_type
            schema: public
            iglu_schema: iglu:com.example/rt/jsonschema/1-0-0
            primary_key: [id]
            columns: [id, amount_cents]
    """.trimIndent()
}
