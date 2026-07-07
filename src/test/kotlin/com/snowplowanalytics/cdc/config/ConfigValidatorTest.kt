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

import com.snowplowanalytics.cdc.transform.Transform
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

// ConfigValidatorTest exercises the validator in isolation, bypassing the YAML parser and env
// interpolator. `data class` copy() makes it easy to produce one-mutation variants of a valid
// baseline — this is the Kotlin idiomatic substitute for builder-style test fixtures.
class ConfigValidatorTest {

    private val validator = ConfigValidator()

    @Test
    fun `valid config produces no errors`() {
        validator.validate(validConfig()).shouldBeEmpty()
    }

    @Test
    fun `connector other than postgres is rejected`() {
        // `.copy(source = ...)` returns a new Config with everything unchanged except `source`.
        // The nested `.copy(connector = "mysql")` works the same way on the inner data class.
        val cfg = validConfig().copy(source = validConfig().source.copy(connector = "mysql"))
        val errors = validator.validate(cfg)
        errors shouldHaveSize 1
        errors[0].path shouldBe "source.connector"
    }

    @Test
    fun `empty tables list is rejected`() {
        val cfg = validConfig().copy(tables = emptyList())
        val errors = validator.validate(cfg)
        errors shouldHaveSize 1
        errors[0].path shouldBe "tables"
    }

    @Test
    fun `blank table name is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable().copy(name = "  "))
        )
        validator.validate(cfg).map { it.path } shouldContain "tables[0].name"
    }

    @Test
    fun `blank table schema is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable().copy(schema = ""))
        )
        validator.validate(cfg).map { it.path } shouldContain "tables[0].schema"
    }

    @Test
    fun `empty primary_key is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable().copy(primaryKey = emptyList()))
        )
        validator.validate(cfg).map { it.path } shouldContain "tables[0].primary_key"
    }

    @Test
    fun `empty columns list is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable().copy(columns = emptyList()))
        )
        validator.validate(cfg).map { it.path } shouldContain "tables[0].columns"
    }

    @Test
    fun `malformed iglu URI on a table is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable().copy(igluSchema = "not-an-iglu-uri"))
        )
        validator.validate(cfg).map { it.path } shouldContain "tables[0].iglu_schema"
    }

    @Test
    fun `invalid snapshot_mode is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(snapshotMode = "hourly")
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.snapshot_mode"
    }

    @Test
    fun `invalid publication_autocreate_mode is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(publicationAutocreateMode = "maybe")
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.publication_autocreate_mode"
    }

    @Test
    fun `negative heartbeat_interval_ms is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(heartbeatIntervalMs = -1)
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.heartbeat_interval_ms"
    }

    @Test
    fun `malformed cdc_source_schema is rejected`() {
        val cfg = validConfig().copy(
            snowplow = validConfig().snowplow.copy(cdcSourceSchema = "iglu:bad")
        )
        validator.validate(cfg).map { it.path } shouldContain "snowplow.cdc_source_schema"
    }

    @Test
    fun `duplicate table schema and name is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(validTable(), validTable())
        )
        validator.validate(cfg).map { it.path } shouldContain "tables"
    }

    @Test
    fun `multiple violations all accumulate`() {
        val cfg = validConfig().copy(
            source = validConfig().source.copy(connector = "mysql"),
            debezium = validConfig().debezium.copy(snapshotMode = "hourly"),
        )
        val errors = validator.validate(cfg)
        errors.map { it.path } shouldContain "source.connector"
        errors.map { it.path } shouldContain "debezium.snapshot_mode"
    }

    @Test
    fun `all-excluded columns list is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    columns = listOf(
                        ColumnSpec("id", exclude = true),
                        ColumnSpec("status", exclude = true),
                    )
                )
            )
        )
        val errors = validator.validate(cfg)
        errors.map { it.path } shouldContain "tables[0].columns"
        (errors.first { it.path == "tables[0].columns" }.message) shouldBe
            "must contain at least one non-excluded column"
    }

    @Test
    fun `rename collision between two columns is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    columns = listOf(
                        ColumnSpec("a", rename = "x"),
                        ColumnSpec("b", rename = "x"),
                        ColumnSpec("id"),
                    )
                )
            )
        )
        val errors = validator.validate(cfg)
        val collisionErr = errors.firstOrNull { it.path == "tables[0].columns" && "x" in it.message }
        collisionErr shouldNotBe null
        collisionErr!!.message shouldBe
            "output property name \"x\" is produced by multiple columns: [a, b]"
    }

    @Test
    fun `rename target matching another columns natural name is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    columns = listOf(
                        ColumnSpec("a", rename = "b"),
                        ColumnSpec("b"),
                        ColumnSpec("id"),
                    )
                )
            )
        )
        val errors = validator.validate(cfg)
        val collisionErr = errors.firstOrNull { it.path == "tables[0].columns" && "b" in it.message }
        collisionErr shouldNotBe null
        collisionErr!!.message shouldBe
            "output property name \"b\" is produced by multiple columns: [a, b]"
    }

    @Test
    fun `excluded columns do not participate in collision detection`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    columns = listOf(
                        ColumnSpec("a", rename = "password_hash"),
                        ColumnSpec("password_hash", exclude = true),
                        ColumnSpec("id"),
                    )
                )
            )
        )
        val errors = validator.validate(cfg)
        errors.shouldBeEmpty()
    }

    @Test
    fun `primary-key column with transforms is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    primaryKey = listOf("id"),
                    columns = listOf(
                        ColumnSpec("id", transforms = listOf(Transform.Lowercase)),
                        ColumnSpec("status"),
                    ),
                ),
            ),
        )
        val errors = validator.validate(cfg)
        errors.any {
            it is ConfigError.InvalidValue
                && it.message.contains("primary-key column \"id\"")
                && it.message.contains("event_id")
        } shouldBe true
    }

    @Test
    fun `excluded column with transforms is rejected`() {
        val cfg = validConfig().copy(
            tables = listOf(
                validTable().copy(
                    columns = listOf(
                        ColumnSpec("id"),
                        ColumnSpec("status"),
                        ColumnSpec("password_hash", exclude = true,
                                   transforms = listOf(Transform.Trim)),
                    ),
                ),
            ),
        )
        val errors = validator.validate(cfg)
        errors.any {
            it is ConfigError.InvalidValue
                && it.message.contains("column \"password_hash\" is excluded")
                && it.message.contains("transforms would never run")
        } shouldBe true
    }

    @Test
    fun `offset_store type=file with blank file_path is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(type = "file", filePath = "  "),
            ),
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.offset_store.file_path"
    }

    @Test
    fun `offset_store type=jdbc without a jdbc block is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(type = "jdbc", jdbc = null),
            ),
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.offset_store.jdbc"
    }

    @Test
    fun `offset_store type=jdbc with blank credentials is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(
                    type = "jdbc",
                    jdbc = JdbcOffsetConfig(username = "", password = ""),
                ),
            ),
        )
        val paths = validator.validate(cfg).map { it.path }
        paths shouldContain "debezium.offset_store.jdbc.username"
        paths shouldContain "debezium.offset_store.jdbc.password"
    }

    @Test
    fun `offset_store type=jdbc with blank table_name is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(
                    type = "jdbc",
                    jdbc = JdbcOffsetConfig(username = "cdc", password = "cdc", tableName = "  "),
                ),
            ),
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.offset_store.jdbc.table_name"
    }

    @Test
    fun `offset_store with unknown type is rejected`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(type = "redis"),
            ),
        )
        validator.validate(cfg).map { it.path } shouldContain "debezium.offset_store.type"
    }

    @Test
    fun `valid jdbc offset_store produces no errors`() {
        val cfg = validConfig().copy(
            debezium = validConfig().debezium.copy(
                offsetStore = OffsetStoreConfig(
                    type = "jdbc",
                    jdbc = JdbcOffsetConfig(username = "cdc_offset", password = "secret"),
                ),
            ),
        )
        validator.validate(cfg).shouldBeEmpty()
    }

    private fun validTable() = TableConfig(
        name = "orders",
        schema = "public",
        igluSchema = "iglu:com.example/orders_change/jsonschema/1-0-0",
        primaryKey = listOf("id"),
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("customer_id"),
            ColumnSpec("status"),
            ColumnSpec("total"),
        ),
    )

    private fun validConfig() = Config(
        service = ServiceConfig(appId = "orders-cdc"),
        source = SourceConfig(
            connector = "postgres",
            hostname = "localhost",
            database = "orders_db",
            username = "cdc",
            password = "cdc",
            slotName = "snowplow_cdc",
            publicationName = "snowplow_cdc_pub",
        ),
        debezium = DebeziumConfig(
            offsetStore = OffsetStoreConfig(type = "file", filePath = "/tmp/offsets.dat"),
        ),
        snowplow = SnowplowConfig(
            collectorUrl = "http://collector:9090",
            cdcSourceSchema = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0",
        ),
        tables = listOf(validTable()),
    )
}
