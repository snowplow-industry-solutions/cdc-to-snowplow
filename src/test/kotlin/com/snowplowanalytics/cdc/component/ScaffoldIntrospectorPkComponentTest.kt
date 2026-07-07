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
import com.snowplowanalytics.cdc.testutil.RequiresDocker
import io.kotest.matchers.collections.shouldContainExactly
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
class ScaffoldIntrospectorPkComponentTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withDatabaseName("scaffold_db")
                .withUsername("cdc")
                .withPassword("cdc")
                .withInitScript("init-scaffold-sample.sql")
    }

    private fun introspect(vararg tables: TableKey) =
        DriverManager.getConnection(postgres.jdbcUrl, "cdc", "cdc").use {
            PgIntrospector(it).introspect(tables.toList())
        }

    @Test
    fun `single-column primary key is detected`() {
        val md = introspect(TableKey("public", "orders"))
        md.forTable(TableKey("public", "orders"))!!.primaryKeyColumns shouldContainExactly listOf("id")
    }

    @Test
    fun `composite primary key preserves index column order`() {
        val md = introspect(TableKey("public", "line_items"))
        md.forTable(TableKey("public", "line_items"))!!.primaryKeyColumns shouldContainExactly listOf("tenant_id", "id")
    }

    @Test
    fun `table with no primary key reports empty pk and surfaces unique index`() {
        val md = introspect(TableKey("public", "contacts"))
        val t = md.forTable(TableKey("public", "contacts"))!!
        t.primaryKeyColumns shouldBe emptyList()
        t.uniqueIndexes shouldContainExactly listOf(listOf("email"))
    }

    @Test
    fun `table with no pk and no unique index reports both empty`() {
        val md = introspect(TableKey("public", "events_log"))
        val t = md.forTable(TableKey("public", "events_log"))!!
        t.primaryKeyColumns shouldBe emptyList()
        t.uniqueIndexes shouldBe emptyList()
    }
}
