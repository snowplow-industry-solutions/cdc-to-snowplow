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

import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.pg.PgIntrospector
import com.snowplowanalytics.cdc.pg.ReplicaIdentity
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@ExtendWith(RequiresDocker::class)
@Testcontainers
class PgIntrospectorComponentTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("introspector_db")
                .withUsername("cdc")
                .withPassword("cdc")
                .withInitScript("init-introspector-sample.sql")
    }

    private fun connect() =
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc")

    @Test
    fun `introspect returns column descriptors in ordinal order`() {
        connect().use { conn ->
            val md = PgIntrospector(conn).introspect(
                listOf(TableKey("public", "orders"), TableKey("public", "customers"))
            )
            val orders = md.forTable(TableKey("public", "orders"))
            orders.shouldNotBeNull()
            orders.columns.map { it.name } shouldContainExactly listOf("id", "customer_id", "status", "total")
        }
    }

    @Test
    fun `introspect surfaces REPLICA IDENTITY FULL vs DEFAULT`() {
        connect().use { conn ->
            val md = PgIntrospector(conn).introspect(
                listOf(TableKey("public", "orders"), TableKey("public", "customers"))
            )
            md.forTable(TableKey("public", "orders"))!!.replicaIdentity shouldBe ReplicaIdentity.FULL
            md.forTable(TableKey("public", "customers"))!!.replicaIdentity shouldBe ReplicaIdentity.DEFAULT
        }
    }

    @Test
    fun `introspect stitches enum labels onto the matching column`() {
        connect().use { conn ->
            val md = PgIntrospector(conn).introspect(listOf(TableKey("public", "orders")))
            val statusCol = md.forTable(TableKey("public", "orders"))!!
                .columns.single { it.name == "status" }
            statusCol.dataType shouldBe "USER-DEFINED"
            statusCol.udtName shouldBe "order_status_enum"
            statusCol.enumLabels shouldContainExactly listOf("pending", "shipped", "delivered")
        }
    }

    @Test
    fun `nullability is reported correctly`() {
        connect().use { conn ->
            val md = PgIntrospector(conn).introspect(listOf(TableKey("public", "orders")))
            val cols = md.forTable(TableKey("public", "orders"))!!.columns.associateBy { it.name }
            cols["id"]!!.isNullable shouldBe false
            cols["customer_id"]!!.isNullable shouldBe false
            cols["total"]!!.isNullable shouldBe true  // not declared NOT NULL
        }
    }

    @Test
    fun `IN filter scopes the snapshot to configured tables`() {
        connect().use { conn ->
            val md = PgIntrospector(conn).introspect(listOf(TableKey("public", "orders")))
            md.tables.map { it.key } shouldContainExactly listOf(TableKey("public", "orders"))
        }
    }
}
