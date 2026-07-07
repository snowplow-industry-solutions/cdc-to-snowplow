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

import io.kotest.matchers.shouldBe
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

@ExtendWith(SystemStubsExtension::class)
class ObservabilityConfigTest {

    @SystemStub
    private val env = EnvironmentVariables()

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `no observability section yields defaults`() {
        env.set("PG_PASSWORD", "secret")
        val cfg = load(minimalYaml())
        cfg.observability.http.port shouldBe 8080
        cfg.observability.heartbeat.intervalMs shouldBe 60_000L
    }

    @Test
    fun `only observability http port set yields overridden port and default heartbeat`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: 9090\n"
        val cfg = load(yaml)
        cfg.observability.http.port shouldBe 9090
        cfg.observability.heartbeat.intervalMs shouldBe 60_000L
    }

    @Test
    fun `only observability heartbeat interval_ms set yields default port and overridden interval`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  heartbeat:\n    interval_ms: 5000\n"
        val cfg = load(yaml)
        cfg.observability.http.port shouldBe 8080
        cfg.observability.heartbeat.intervalMs shouldBe 5000L
    }

    @Test
    fun `both observability fields set are both honoured`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: 9090\n  heartbeat:\n    interval_ms: 5000\n"
        val cfg = load(yaml)
        cfg.observability.http.port shouldBe 9090
        cfg.observability.heartbeat.intervalMs shouldBe 5000L
    }

    @Test
    fun `observability http port 0 is rejected with ConfigException referencing path and rule`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: 0\n"
        val ex = assertThrows<ConfigException> { load(yaml) }
        val err = ex.errors.first { it.path == "observability.http.port" }
        err.message shouldBe "must be in [1, 65535]; got 0"
    }

    @Test
    fun `observability http port -1 is rejected with ConfigException naming the valid range`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: -1\n"
        val ex = assertThrows<ConfigException> { load(yaml) }
        val err = ex.errors.first { it.path == "observability.http.port" }
        err.message shouldBe "must be in [1, 65535]; got -1"
    }

    @Test
    fun `observability http port 65536 is rejected with ConfigException naming the valid range`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: 65536\n"
        val ex = assertThrows<ConfigException> { load(yaml) }
        val err = ex.errors.first { it.path == "observability.http.port" }
        err.message shouldBe "must be in [1, 65535]; got 65536"
    }

    @Test
    fun `observability heartbeat interval_ms 0 is rejected with ConfigException referencing path and rule`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  heartbeat:\n    interval_ms: 0\n"
        val ex = assertThrows<ConfigException> { load(yaml) }
        val err = ex.errors.first { it.path == "observability.heartbeat.interval_ms" }
        err.message shouldBe "must be > 0; got 0"
    }

    @Test
    fun `both observability violations accumulate`() {
        env.set("PG_PASSWORD", "secret")
        val yaml = minimalYaml() + "\nobservability:\n  http:\n    port: 0\n  heartbeat:\n    interval_ms: 0\n"
        val ex = assertThrows<ConfigException> { load(yaml) }
        ex.errors.any { it.path == "observability.http.port" } shouldBe true
        ex.errors.any { it.path == "observability.heartbeat.interval_ms" } shouldBe true
    }

    private fun minimalYaml(): String = """
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

    private fun load(yaml: String): Config {
        val path = Files.createTempFile(tmp, "config", ".yaml")
        path.writeText(yaml)
        return ConfigLoader.load(path)
    }
}
