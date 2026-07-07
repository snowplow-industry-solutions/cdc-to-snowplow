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

import com.snowplowanalytics.cdc.config.ConfigException
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.scaffold.ScaffoldInputs
import com.snowplowanalytics.cdc.scaffold.Scaffolder
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@ExtendWith(RequiresDocker::class, SystemStubsExtension::class)
@Testcontainers
class ScaffoldEndToEndComponentTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("scaffold_db")
                .withUsername("cdc")
                .withPassword("cdc")
                .withInitScript("init-scaffold-sample.sql")
    }

    // ConfigLoader's EnvInterpolator resolves ${POSTGRES_PASSWORD} and ${COLLECTOR_URL} from the
    // process environment. These stubs are injected before each test and restored after, so they
    // never leak across test runs.
    @SystemStub
    private val env = EnvironmentVariables(
        "POSTGRES_PASSWORD", "cdc",
        "COLLECTOR_URL", "http://collector:9090",
    )

    private fun scaffold(out: Path, vararg tables: String) {
        val conn = ScaffoldInputs.parseConnection(postgres.jdbcUrl) { name ->
            when (name) { "PGUSER" -> "cdc"; "PGPASSWORD" -> "cdc"; else -> null }
        }
        Scaffolder(conn, "com.acme.cdc", ScaffoldInputs.parseTables(tables.joinToString(",")), out).run()
    }

    @Test
    fun `generated config loads cleanly through the real ConfigLoader`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "orders", "line_items")

        val config = ConfigLoader.load(out.resolve("config.yaml"))
        config.tables.map { TableKey(it.schema, it.name) }
            .toSet() shouldBe setOf(TableKey("public", "orders"), TableKey("public", "line_items"))
        config.tablesByKey[TableKey("public", "line_items")]!!.primaryKey shouldBe listOf("tenant_id", "id")
    }

    @Test
    fun `generates cdc_source copy and per-table schemas in iglu layout`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "orders")
        out.resolve("schemas/com.snowplowanalytics/cdc_source/jsonschema/1-0-0").exists() shouldBe true
        val ordersSchema = out.resolve("schemas/com.acme.cdc/orders_change/jsonschema/1-0-0")
        ordersSchema.exists() shouldBe true
        ordersSchema.readText() shouldContain "orders_change"
    }

    @Test
    fun `pk-less table scaffolds an empty primary_key`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "events_log")
        out.resolve("config.yaml").readText() shouldContain "primary_key: []"
    }

    // ConfigValidator rejects empty primary_key (line 44 of ConfigValidator.kt), so loading a
    // pk-less scaffold through ConfigLoader should throw ConfigException. This confirms the
    // validator is properly wired end-to-end and the scaffold correctly surfaces the TODO placeholder.
    @Test
    fun `pk-less generated config is rejected by ConfigLoader`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "events_log")
        assertThrows<ConfigException> {
            ConfigLoader.load(out.resolve("config.yaml"))
        }
    }

    @Test
    fun `refuses a non-empty output directory`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "orders")
        runCatching { scaffold(out, "orders") }
            .exceptionOrNull()!!.message!! shouldContain "not empty"
    }

    @Test
    fun `writes config schema so the lsp hint resolves`(@TempDir tmp: Path) {
        val out = tmp.resolve("generated")
        scaffold(out, "orders")
        val schemaFile = out.resolve("cdc-service-config.schema.json")
        schemaFile.exists() shouldBe true
        out.resolve("config.yaml").readText() shouldContain "\$schema=./cdc-service-config.schema.json"
    }
}
