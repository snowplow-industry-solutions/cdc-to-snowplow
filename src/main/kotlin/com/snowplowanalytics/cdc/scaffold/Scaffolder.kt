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

package com.snowplowanalytics.cdc.scaffold

import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.PgIntrospector
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Orchestrates scaffold in strict phases (spec §4): validate output dir -> connect + introspect
 * -> verify all requested tables present -> generate in memory -> write (with rollback).
 * Throws [ScaffoldInputError] / [ScaffoldOutputError] with named messages; the CLI maps them to
 * a non-zero exit.
 */
class Scaffolder(
    private val connection: ScaffoldConnection,
    private val vendor: String,
    private val tables: List<TableKey>,
    private val outputRoot: Path,
) {
    private val log = LoggerFactory.getLogger(Scaffolder::class.java)

    fun run() {
        // Fail fast on a doomed output dir before any DB round-trip (spec §4 phase ordering).
        OutputWriter.validateState(outputRoot)

        if (connection.passwordFromUri) {
            log.info("password supplied via --connection URI; prefer PGPASSWORD to avoid leaking it to process listings")
        }
        val md = DriverManager.getConnection(connection.jdbcUrl, connection.coords.username, connection.password)
            .use { conn -> PgIntrospector(conn).introspect(tables) }

        val present = md.tables.map { it.key }.toSet()
        val missing = tables.filter { it !in present }
        if (missing.isNotEmpty()) {
            throw ScaffoldInputError(
                "table(s) not found in database ${connection.coords.database}: ${missing.joinToString(", ")}",
            )
        }

        val files = generateFiles(md)
        OutputWriter.write(outputRoot, files)
    }

    private fun generateFiles(md: DbMetadata): List<ScaffoldFile> {
        val files = mutableListOf<ScaffoldFile>()
        files.add(ScaffoldFile("config.yaml", ConfigYamlGenerator.generate(md, vendor, connection.coords)))
        files.add(ScaffoldFile(ConfigSchemaResource.FILE_NAME, ConfigSchemaResource.read()))
        files.add(ScaffoldFile("schemas/${CdcSourceSchemaResource.RELATIVE_PATH}", CdcSourceSchemaResource.read()))
        md.tables.forEach { t ->
            files.add(
                ScaffoldFile(
                    "schemas/$vendor/${t.key.name}_change/jsonschema/1-0-0",
                    IgluSchemaGenerator.generate(t, vendor),
                ),
            )
        }
        return files
    }
}
