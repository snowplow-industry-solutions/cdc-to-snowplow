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

import ch.qos.logback.classic.Level
import com.snowplowanalytics.cdc.config.ConfigLoader
import com.snowplowanalytics.cdc.emitter.RecordingEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import com.snowplowanalytics.cdc.testutil.ordersReplicationContainer
import com.snowplowanalytics.cdc.testutil.withCapturedLogs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Boots EngineRunner against a Postgres database where one captured table is FULL and the other
 * is left at the default — the ReplicaIdentityCheck preflight should emit exactly one WARN line
 * for the non-FULL table.
 *
 * Captures logback events via [withCapturedLogs], which guarantees the appender is detached even
 * if the captured block throws — so other test classes aren't polluted.
 */
@ExtendWith(RequiresDocker::class)
@Testcontainers
class ReplicaIdentityWarningTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = ordersReplicationContainer("init-no-replica-identity.sql")
    }

    @TempDir lateinit var tmp: Path
    private lateinit var runner: EngineRunner
    private lateinit var emitter: RecordingEmitter

    @BeforeEach
    fun setUp() {
        emitter = RecordingEmitter()
        val offsetFile = Files.createTempFile("cdc-offsets", ".dat").toFile()
        offsetFile.delete()

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
                type: file
                file_path: ${offsetFile.absolutePath}
              heartbeat_interval_ms: 0
              publication_autocreate_mode: filtered
              provide_transaction_metadata: false
            snowplow:
              collector_url: http://unused
              cdc_source_schema: iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0
            tables:
              - name: orders
                schema: public
                iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
                primary_key: [id]
                columns: [id, customer_id, status, total]
              - name: customers
                schema: public
                iglu_schema: iglu:com.example/customers_change/jsonschema/1-0-0
                primary_key: [id]
                columns: [id, email, full_name]
            """.trimIndent()
        )

        val config = ConfigLoader.load(configPath)
        runner = EngineRunner(config, emitter, Counters(bufferCapacity = 1000), ReadinessProbe())
    }

    @AfterEach
    fun tearDown() {
        runner.stop()
    }

    @Test
    fun `WARN log line names the non-FULL captured table by schema dot table`() {
        // The ReplicaIdentityCheck preflight emits its WARN during runner.start(), so capture
        // around the start call. withCapturedLogs detaches the appender even if start() throws.
        val warnings = withCapturedLogs { appender ->
            runner.start()
            appender.list.filter { it.level == Level.WARN }
                .map { it.formattedMessage }
                .filter { it.contains("REPLICA IDENTITY") }
        }

        warnings shouldHaveSize 1
        warnings[0] shouldContain "public.customers"
        warnings[0] shouldContain "default"
        warnings[0] shouldContain "ALTER TABLE public.customers REPLICA IDENTITY FULL"
    }
}
