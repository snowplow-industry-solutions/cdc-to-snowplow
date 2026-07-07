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

package com.snowplowanalytics.cdc.testutil

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * A `postgres:16` Testcontainers image configured for logical replication (Debezium pgoutput):
 * `wal_level=logical` with replication slots and WAL senders enabled. [initScript] runs from
 * `src/test/resources` before the container is declared ready.
 *
 * Component tests declare this as a shared `@Container @JvmStatic` field; only the init script
 * varies between them (`init-orders.sql` vs `init-no-replica-identity.sql`).
 */
fun ordersReplicationContainer(initScript: String = "init-orders.sql"): PostgreSQLContainer<*> =
    PostgreSQLContainer(DockerImageName.parse("postgres:16"))
        .withDatabaseName("orders_db")
        .withUsername("cdc")
        .withPassword("cdc")
        // wal_level=logical enables logical replication, required for Debezium pgoutput.
        // max_replication_slots and max_wal_senders must be > 0 for slots to be created.
        .withCommand(
            "postgres",
            "-c", "wal_level=logical",
            "-c", "max_replication_slots=4",
            "-c", "max_wal_senders=4",
        )
        .withInitScript(initScript)

/**
 * Shared replication-slot / publication lifecycle helpers for component tests that boot the real
 * Debezium engine against a shared Postgres container. Previously copy-pasted verbatim across five
 * component tests (issue #24); centralised here so a fix or timeout tweak lands in one place.
 *
 * The slot and publication names default to the values every test uses; pass overrides only if a
 * future test diverges.
 */
class PostgresReplicationFixture(
    private val container: PostgreSQLContainer<*>,
    private val slotName: String = DEFAULT_SLOT_NAME,
    private val publicationName: String = DEFAULT_PUBLICATION_NAME,
    private val username: String = "cdc",
    private val password: String = "cdc",
) {
    /**
     * Drops the replication slot if present. Polls briefly while the prior runner releases the
     * slot — `pg_drop_replication_slot` fails on an active slot. Returns quietly when the slot is
     * already absent, which is the desired no-op for a defensive `@BeforeEach` call.
     */
    fun dropReplicationSlotIfPresent() {
        val end = System.currentTimeMillis() + SLOT_DROP_TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            val active: Boolean? = DriverManager.getConnection(container.jdbcUrl, username, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT active FROM pg_replication_slots WHERE slot_name = '$slotName'"
                    ).use { rs -> if (rs.next()) rs.getBoolean(1) else null }
                }
            }
            when (active) {
                null -> return  // slot does not exist
                false -> {
                    DriverManager.getConnection(container.jdbcUrl, username, password).use { conn ->
                        conn.createStatement().use {
                            it.execute("SELECT pg_drop_replication_slot('$slotName')")
                        }
                    }
                    return
                }
                true -> Thread.sleep(100)  // still active — wait for the runner to release it
            }
        }
        error("Timed out waiting for replication slot '$slotName' to become inactive")
    }

    fun dropPublicationIfPresent() {
        DriverManager.getConnection(container.jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP PUBLICATION IF EXISTS $publicationName")
            }
        }
    }

    fun awaitReplicationSlotPresent(timeoutMs: Long = AWAIT_DEFAULT_TIMEOUT_MS) {
        awaitWith(timeoutMs, "replication slot '$slotName' to be created") {
            DriverManager.getConnection(container.jdbcUrl, username, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT count(*) FROM pg_replication_slots WHERE slot_name = '$slotName'"
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1) == 1
                    }
                }
            }
        }
    }

    /**
     * Busy-waits with 200ms polling until [condition] is true or [timeoutMs] expires. Transient
     * exceptions (e.g. JDBC connection refused before Postgres is ready) are swallowed so the wait
     * survives them; a timeout throws with [description].
     */
    fun awaitWith(timeoutMs: Long = AWAIT_DEFAULT_TIMEOUT_MS, description: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                if (condition()) return
            } catch (_: Exception) {
                // ignore transient errors
            }
            Thread.sleep(200)
        }
        error("Timed out after ${timeoutMs}ms waiting for: $description")
    }

    companion object {
        const val DEFAULT_SLOT_NAME = "snowplow_cdc"
        const val DEFAULT_PUBLICATION_NAME = "snowplow_cdc_pub"
        const val SLOT_DROP_TIMEOUT_MS = 10_000L
        const val AWAIT_DEFAULT_TIMEOUT_MS = 30_000L
    }
}
