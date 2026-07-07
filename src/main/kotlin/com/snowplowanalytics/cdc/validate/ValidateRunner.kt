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

package com.snowplowanalytics.cdc.validate

import com.snowplowanalytics.cdc.config.ConfigException
import com.snowplowanalytics.cdc.config.ConfigSource
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.PgIntrospector
import com.snowplowanalytics.cdc.pg.postgresJdbcUrl
import com.snowplowanalytics.cdc.preflight.ColumnVerification
import com.snowplowanalytics.cdc.preflight.ReplicaIdentityCheck
import com.snowplowanalytics.cdc.preflight.TransformTypeCheck
import com.snowplowanalytics.cdc.preflight.UnconfiguredColumnCheck
import java.io.PrintStream
import java.sql.DriverManager
import java.sql.SQLException

object ValidateRunner {
    /**
     * Exit codes:
     *   0 — clean (zero errors; warnings may exist)
     *   1 — drift detected (≥1 error)
     *   2 — could not run (config load failure, DB connect failure)
     */
    fun run(source: ConfigSource, out: PrintStream, err: PrintStream): Int {
        val config = try {
            source.load()
        } catch (e: ConfigException) {
            err.println(e.message)
            return 2
        }

        if (config.source.connector != "postgres") {
            err.println("validate: unsupported connector '${config.source.connector}' (postgres only)")
            return 2
        }

        val jdbcUrl = postgresJdbcUrl(config.source)
        val tableKeys = config.tables.map { TableKey(it.schema, it.name) }

        val metadata = try {
            DriverManager.getConnection(jdbcUrl, config.source.username, config.source.password).use { conn ->
                PgIntrospector(conn).introspect(tableKeys)
            }
        } catch (e: SQLException) {
            err.println("validate: failed to connect to ${config.source.hostname}:${config.source.port}: ${e.message}")
            return 2
        }

        // Offset-store preflight: when the offset store is jdbc, verify its credentials connect.
        // Connectivity only — a missing CREATE grant surfaces clearly at `run` via auto-create.
        val offsetStore = config.debezium.offsetStore
        if (offsetStore.type == "jdbc") {
            val jdbc = offsetStore.jdbc
                ?: error("offset_store.type=jdbc requires a jdbc block (validated upstream)")
            try {
                DriverManager.getConnection(jdbcUrl, jdbc.username, jdbc.password).use { /* connectivity only */ }
            } catch (e: SQLException) {
                err.println("validate: failed to connect to offset store as \"${jdbc.username}\": ${e.message}")
                return 2
            }
        }

        val report = DriftReport(
            missingColumns = ColumnVerification(metadata, config).verify(),
            typeMismatches = TransformTypeCheck(metadata, config).verify(),
            replicaIdentityFindings = ReplicaIdentityCheck(metadata, config).check(),
            extraColumns = UnconfiguredColumnCheck(metadata, config).verify(),
        )
        out.println(DriftReportFormatter.format(report, config, metadata))
        return if (report.errorCount > 0) 1 else 0
    }
}
