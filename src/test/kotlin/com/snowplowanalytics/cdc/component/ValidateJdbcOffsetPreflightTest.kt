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
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText

@ExtendWith(RequiresDocker::class)
@Testcontainers
class ValidateJdbcOffsetPreflightTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer()
    }

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `validate passes when jdbc offset credentials connect`() {
        val (code, stderr) = runValidate(offsetUser = "cdc", offsetPassword = "cdc")
        code shouldBe 0
        stderr.shouldBeEmpty()
    }

    @Test
    fun `validate fails with exit 2 when jdbc offset credentials are wrong`() {
        val (code, stderr) = runValidate(offsetUser = "cdc", offsetPassword = "wrong-password")
        code shouldBe 2
        // Assert the diagnostic, not just the exit code: guards against a silent removal of the
        // offset-store error message (the sibling ValidateCommandComponentTest asserts stderr too).
        stderr shouldContain "validate: failed to connect to offset store"
    }

    private fun runValidate(offsetUser: String, offsetPassword: String): Pair<Int, String> {
        val configPath = tmp.resolve("config.yaml")
        configPath.writeText(
            """
            service:
              app_id: orders-cdc
            source:
              connector: postgres
              hostname: ${postgres.host}
              port: ${postgres.firstMappedPort}
              database: orders_db
              username: cdc
              password: cdc
              slot_name: snowplow_cdc
              publication_name: snowplow_cdc_pub
            debezium:
              snapshot_mode: never
              offset_store:
                type: jdbc
                jdbc:
                  username: $offsetUser
                  password: $offsetPassword
            snowplow:
              collector_url: http://unused
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
            """.trimIndent(),
        )
        val errBaos = ByteArrayOutputStream()
        val code = ValidateRunner.run(ConfigSource.File(configPath), PrintStream(ByteArrayOutputStream()), PrintStream(errBaos))
        return code to errBaos.toString(Charsets.UTF_8)
    }
}
