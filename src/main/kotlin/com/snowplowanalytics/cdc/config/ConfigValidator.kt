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

/**
 * Post-binding semantic validation of a fully-constructed [Config]. Called by [ConfigLoader]
 * after Jackson has successfully bound the YAML to Kotlin types.
 *
 * All errors are accumulated before returning so the operator sees every problem at once.
 * Returns an empty list on success.
 */
class ConfigValidator {

    // Iglu schema URIs have the form: iglu:<vendor>/<name>/jsonschema/<MODEL>-<REVISION>-<ADDITION>
    // Example: iglu:com.example/orders_change/jsonschema/1-0-0
    private val igluUriRegex = Regex("""^iglu:[^/]+/[^/]+/jsonschema/\d+-\d+-\d+$""")
    private val validSnapshotModes = setOf("never", "initial", "initial_only", "when_needed")
    private val validAutocreateModes = setOf("filtered", "all_tables", "disabled")

    fun validate(config: Config): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()

        if (config.source.connector != "postgres") {
            errors += ConfigError.InvalidValue(
                path = "source.connector",
                message = "expected \"postgres\", got \"${config.source.connector}\"",
            )
        }

        if (config.tables.isEmpty()) {
            errors += ConfigError.InvalidValue(
                path = "tables",
                message = "must contain at least one table",
            )
        }

        // `forEachIndexed` provides both the index (for dotted-path error messages like
        // "tables[0].primary_key") and the element in one pass.
        config.tables.forEachIndexed { i, t ->
            if (t.name.isBlank()) {
                errors += ConfigError.InvalidValue("tables[$i].name", "must not be blank")
            }
            if (t.schema.isBlank()) {
                errors += ConfigError.InvalidValue("tables[$i].schema", "must not be blank")
            }
            if (t.primaryKey.isEmpty()) {
                errors += ConfigError.InvalidValue(
                    "tables[$i].primary_key",
                    "must contain at least one column name",
                )
            }
            // Empty list AND all-excluded list both produce zero properties in the emitted
            // payload — almost certainly a misconfiguration.
            if (t.columns.none { !it.exclude }) {
                val msg = if (t.columns.isEmpty()) "must contain at least one column"
                          else "must contain at least one non-excluded column"
                errors += ConfigError.InvalidValue("tables[$i].columns", msg)
            } else {
                // Rename collisions: group non-excluded columns by their outputName; any group
                // of size > 1 is a collision. Captures both rename→rename and rename→natural-name.
                val collisions = t.columns
                    .filter { !it.exclude }
                    .groupBy { it.outputName }
                    .filterValues { it.size > 1 }
                for ((outputName, conflicting) in collisions) {
                    val sources = conflicting.map { it.name }
                    errors += ConfigError.InvalidValue(
                        "tables[$i].columns",
                        "output property name \"$outputName\" is produced by multiple columns: $sources",
                    )
                }
            }
            if (!igluUriRegex.matches(t.igluSchema)) {
                errors += ConfigError.InvalidValue(
                    "tables[$i].iglu_schema",
                    "must be a well-formed iglu URI (iglu:<vendor>/<name>/jsonschema/<MODEL-REVISION-ADDITION>)",
                )
            }
            // Reject transforms on primary-key columns. PK values feed the deterministic event_id
            // from the un-projected map in PayloadAssembler; a transformed PK would split payload
            // semantics from event-id semantics.
            val pkSet = t.primaryKey.toSet()
            for (spec in t.columns) {
                if (spec.name in pkSet && spec.transforms.isNotEmpty()) {
                    errors += ConfigError.InvalidValue(
                        "tables[$i].columns",
                        "primary-key column \"${spec.name}\" cannot declare transforms — PK values feed the deterministic event_id",
                    )
                }
            }
            // Reject transforms on excluded columns. The column is verified at startup but never
            // emitted; a transform there is dead config.
            for (spec in t.columns) {
                if (spec.exclude && spec.transforms.isNotEmpty()) {
                    errors += ConfigError.InvalidValue(
                        "tables[$i].columns",
                        "column \"${spec.name}\" is excluded; transforms would never run",
                    )
                }
            }
        }

        if (config.debezium.snapshotMode !in validSnapshotModes) {
            errors += ConfigError.InvalidValue(
                "debezium.snapshot_mode",
                "must be one of $validSnapshotModes; got \"${config.debezium.snapshotMode}\"",
            )
        }
        if (config.debezium.publicationAutocreateMode !in validAutocreateModes) {
            errors += ConfigError.InvalidValue(
                "debezium.publication_autocreate_mode",
                "must be one of $validAutocreateModes; got \"${config.debezium.publicationAutocreateMode}\"",
            )
        }
        if (config.debezium.heartbeatIntervalMs < 0) {
            errors += ConfigError.InvalidValue(
                "debezium.heartbeat_interval_ms",
                "must be >= 0; got ${config.debezium.heartbeatIntervalMs}",
            )
        }

        val offsetStore = config.debezium.offsetStore
        when (offsetStore.type) {
            "file" ->
                if (offsetStore.filePath.isNullOrBlank()) {
                    errors += ConfigError.InvalidValue(
                        "debezium.offset_store.file_path",
                        "must be non-blank when offset_store.type is \"file\"",
                    )
                }
            "jdbc" -> {
                val jdbc = offsetStore.jdbc
                if (jdbc == null) {
                    errors += ConfigError.InvalidValue(
                        "debezium.offset_store.jdbc",
                        "must be provided when offset_store.type is \"jdbc\"",
                    )
                } else {
                    if (jdbc.username.isBlank()) {
                        errors += ConfigError.InvalidValue(
                            "debezium.offset_store.jdbc.username", "must be non-blank",
                        )
                    }
                    if (jdbc.password.isBlank()) {
                        errors += ConfigError.InvalidValue(
                            "debezium.offset_store.jdbc.password", "must be non-blank",
                        )
                    }
                    // Defaults to "cdc_offset_storage"; an explicit blank/whitespace value would
                    // reach Debezium's offset.storage.jdbc.offset.table.name and fail with a cryptic
                    // SQL error. Caught here to match the schema's table_name minLength constraint.
                    if (jdbc.tableName.isBlank()) {
                        errors += ConfigError.InvalidValue(
                            "debezium.offset_store.jdbc.table_name", "must be non-blank",
                        )
                    }
                }
            }
            else ->
                errors += ConfigError.InvalidValue(
                    "debezium.offset_store.type",
                    "must be one of [file, jdbc]; got \"${offsetStore.type}\"",
                )
        }

        val port = config.observability.http.port
        if (port <= 0 || port > 65535) {
            errors += ConfigError.InvalidValue(
                "observability.http.port",
                "must be in [1, 65535]; got $port",
            )
        }
        if (config.observability.heartbeat.intervalMs <= 0) {
            errors += ConfigError.InvalidValue(
                "observability.heartbeat.interval_ms",
                "must be > 0; got ${config.observability.heartbeat.intervalMs}",
            )
        }

        if (!igluUriRegex.matches(config.snowplow.cdcSourceSchema)) {
            errors += ConfigError.InvalidValue(
                "snowplow.cdc_source_schema",
                "must be a well-formed iglu URI",
            )
        }

        // Detect duplicate (schema, name) pairs — Debezium's include list would silently
        // de-duplicate them but we'd end up routing both to the same TableConfig by accident.
        val duplicates = config.tables
            .groupBy { TableKey(it.schema, it.name) }
            .filterValues { it.size > 1 }
        duplicates.forEach { (key, _) ->
            val indices = config.tables.mapIndexedNotNull { i, t ->
                if (t.schema == key.schema && t.name == key.name) i else null
            }
            errors += ConfigError.InvalidValue(
                "tables",
                "duplicate table $key at indices $indices",
            )
        }

        return errors
    }
}
