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

package com.snowplowanalytics.cdc.preflight

import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.DbMetadata
import com.snowplowanalytics.cdc.pg.ReplicaIdentity

/**
 * Startup check that verifies each captured Postgres table has `REPLICA IDENTITY FULL`.
 * Without `FULL`, the `before` field on UPDATE and DELETE change events contains only the
 * primary key (or nothing for `n`); analysts get a degraded view of pre-change state.
 *
 * The check consumes the [ReplicaIdentity] enum already populated by `PgIntrospector.introspect()`
 * rather than running its own query. This keeps every preflight drift check on the same
 * `(DbMetadata, Config) -> List<Finding>` shape and avoids a redundant round-trip to pg_class.
 *
 * The check is non-fatal by design (parent design §3): the operator may have a legitimate reason
 * to leave a table at PK-only `before` (e.g., wide rows where the full-row WAL volume is
 * prohibitive). The `engine` package is responsible for invoking this check and logging each
 * [Finding] at WARN before proceeding.
 *
 * Postgres-only. Future connectors (MySQL, SQL Server, MongoDB) will need their own preflight
 * checks for connector-specific row-image settings; this class returns an empty list for any
 * non-postgres `config.source.connector`. Note that the caller must still branch on connector
 * type to decide whether to open a Postgres-flavoured JDBC connection before constructing this check.
 */
class ReplicaIdentityCheck(
    private val metadata: DbMetadata,
    private val config: Config,
) {

    /**
     * Result of the check for a single non-FULL table. [identity] is the human-readable
     * translation of the [ReplicaIdentity] enum: "default" / "nothing" / "index" / "unknown" —
     * never "full", because FULL tables are filtered out before findings are produced.
     */
    data class Finding(val schema: String, val table: String, val identity: String)

    fun check(): List<Finding> {
        if (config.source.connector != "postgres") return emptyList()
        return config.tables.mapNotNull { table ->
            val tm = metadata.forTable(TableKey(table.schema, table.name)) ?: return@mapNotNull null
            when (tm.replicaIdentity) {
                ReplicaIdentity.FULL -> null
                ReplicaIdentity.DEFAULT -> Finding(table.schema, table.name, "default")
                ReplicaIdentity.NOTHING -> Finding(table.schema, table.name, "nothing")
                ReplicaIdentity.INDEX -> Finding(table.schema, table.name, "index")
                ReplicaIdentity.UNKNOWN -> Finding(table.schema, table.name, "unknown")
            }
        }
    }
}
