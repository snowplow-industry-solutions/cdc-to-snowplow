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
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

@ExtendWith(SystemStubsExtension::class)
class ConfigSourceTest {

    @SystemStub
    private val env = EnvironmentVariables()

    @Test
    fun `Env loads config from the named environment variable`() {
        env.set("PG_PASSWORD", "secret")
        env.set("CDC_CONFIG", validBaseYaml())
        val cfg = ConfigSource.Env("CDC_CONFIG").load()
        cfg.service.appId shouldBe "orders-cdc"
        cfg.source.password shouldBe "secret"
    }

    @Test
    fun `Env throws a clear ConfigException when the variable is unset`() {
        val ex = assertThrows<ConfigException> { ConfigSource.Env("CDC_CONFIG").load() }
        ex.message shouldContain "CDC_CONFIG"
    }

    @Test
    fun `Env throws when the variable is blank`() {
        env.set("CDC_CONFIG", "   ")
        val ex = assertThrows<ConfigException> { ConfigSource.Env("CDC_CONFIG").load() }
        ex.message shouldContain "unset or blank"
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
}
