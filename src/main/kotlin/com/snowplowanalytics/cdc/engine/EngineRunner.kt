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

package com.snowplowanalytics.cdc.engine

import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.TableKey
import com.snowplowanalytics.cdc.emitter.Emitter
import com.snowplowanalytics.cdc.emitter.EmitterClosedException
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import com.snowplowanalytics.cdc.pg.PgIntrospector
import com.snowplowanalytics.cdc.preflight.ColumnVerification
import com.snowplowanalytics.cdc.preflight.EmitterSelfTest
import com.snowplowanalytics.cdc.preflight.ReplicaIdentityCheck
import com.snowplowanalytics.cdc.preflight.StartupException
import com.snowplowanalytics.cdc.preflight.TransformTypeCheck
import com.snowplowanalytics.cdc.pg.postgresJdbcUrl
import com.snowplowanalytics.cdc.transform.PayloadAssembler
import com.snowplowanalytics.cdc.transform.SourceSchemaFingerprinter
import com.snowplowanalytics.cdc.transform.UnsupportedOpException
import io.debezium.engine.ChangeEvent
import io.debezium.engine.DebeziumEngine
import io.debezium.engine.format.Json
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wraps the Debezium embedded engine lifecycle: builds the Properties, creates the engine,
 * runs it on a dedicated thread, and provides start/stop/await primitives to the caller.
 *
 * Debezium's embedded engine is a blocking run() call that streams change events from the
 * source database until closed. It must run on its own thread so the main thread remains free.
 */
class EngineRunner(
    private val config: Config,
    private val emitter: Emitter,
    private val counters: Counters,
    private val probe: ReadinessProbe,
) {
    private val log = LoggerFactory.getLogger(EngineRunner::class.java)

    // Single-thread executor with a named, non-daemon thread. Non-daemon so the JVM doesn't
    // kill it before the shutdown hook fires on SIGTERM.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "debezium-engine").apply { isDaemon = false }
    }

    // CountDownLatch(1) is a one-shot "gate" — await() blocks until countDown() is called once.
    // Used here to let the main thread block on awaitShutdown() until the engine finishes.
    private val shutdownLatch = CountDownLatch(1)
    private val assembler = PayloadAssembler(
        tablesByKey = config.tablesByKey,
        defaultAppId = config.service.appId,
        connectorName = config.source.connector,
        cdcSourceSchemaUri = config.snowplow.cdcSourceSchema,
    )
    private val detector = DdlChangeDetector()

    // @Volatile ensures the `engine` field write in start() is immediately visible to the thread
    // calling stop(). Without it, the JVM could cache the null value in a CPU register and
    // stop() would see null even after start() wrote the reference.
    @Volatile private var engine: DebeziumEngine<ChangeEvent<String, String>>? = null

    fun start() {
        // Postgres-only startup preflight. One JDBC connection serves all three checks; the
        // connection closes when the .use { } block exits — well before the Debezium engine
        // starts. Non-Postgres connectors skip this block entirely (Slice 5 targets Postgres).
        if (config.source.connector == "postgres") {
            val jdbcUrl = postgresJdbcUrl(config.source)
            DriverManager.getConnection(jdbcUrl, config.source.username, config.source.password).use { conn ->
                // 1. Catalog snapshot. Used by every downstream check (and previously by the replica-identity
                //    check via a separate, now-removed query against pg_class.relreplident).
                val tableKeys = config.tables.map { TableKey(it.schema, it.name) }
                val metadata = PgIntrospector(conn).introspect(tableKeys)
                // 2. Replica-identity warnings (non-fatal).
                ReplicaIdentityCheck(metadata, config).check().forEach { finding ->
                    log.warn(
                        "Table {}.{} has REPLICA IDENTITY '{}' (not FULL); UPDATE/DELETE 'before' " +
                            "fields will contain only the primary key. To fix: " +
                            "ALTER TABLE {}.{} REPLICA IDENTITY FULL;",
                        finding.schema, finding.table, finding.identity, finding.schema, finding.table,
                    )
                }
                // 3. Column existence check (fatal).
                val misses = ColumnVerification(metadata, config).verify()
                if (misses.isNotEmpty()) {
                    throw StartupException(ColumnVerification.formatMessage(misses))
                }
                // 4. Transform type-compatibility check (fatal). Runs after column existence so
                // a missing column is reported by ColumnVerification (not duplicated here).
                val typeMisses = TransformTypeCheck(metadata, config).verify()
                if (typeMisses.isNotEmpty()) {
                    throw StartupException(TransformTypeCheck.formatMessage(typeMisses))
                }
            }
        }
        // Egress preflight: prove the emitter wrapper still implements block-on-overflow.
        // Runs after the DB preflight so cheaper failures surface first; runs before the
        // Debezium engine starts so a failure means we never accept WAL events.
        EmitterSelfTest().run()
        val props = buildProperties()
        // Debezium's builder pattern: create() sets the output format (JSON here),
        // using() supplies the connector properties, notifying() registers the event handler,
        // build() constructs the engine instance without starting it.
        val built: DebeziumEngine<ChangeEvent<String, String>> =
            DebeziumEngine.create(Json::class.java)
                .using(props)
                .notifying { record -> handleRecord(record) }
                .using(object : DebeziumEngine.ConnectorCallback {
                    override fun connectorStarted() { probe.markEngineReady() }
                    // connectorStopped(), taskStarted(), taskStopped() have default no-op
                    // implementations in Debezium 3.0.7.Final — no need to override them.
                })
                .build()
        engine = built
        executor.submit {
            try {
                // `use { }` is Kotlin's try-with-resources for AutoCloseable — equivalent to
                // Java's `try (var e = built) { e.run(); }`. Closes the engine even on exception.
                built.use { it.run() }
            } catch (t: Throwable) {
                log.error("Debezium engine terminated abnormally", t)
            } finally {
                shutdownLatch.countDown()
                // Race window: if the engine thread reaches this finally before stop() can call
                // noteGracefulStop(), the probe flips to engine_failed during shutdown. Cosmetic
                // only — the service is going down anyway. markEngineFailed() is a no-op if
                // noteGracefulStop() was called first.
                probe.markEngineFailed()
            }
        }
    }

    fun stop() {
        // Signal that shutdown is intentional FIRST so the engine-thread finally block, which
        // calls probe.markEngineFailed(), is suppressed even if the engine winds down before
        // we close it explicitly below.
        probe.noteGracefulStop()

        // Close the emitter next. This wakes any thread blocked in emit() (semaphore
        // flood-release + closed flag); handleRecord catches the resulting
        // EmitterClosedException, logs INFO, and returns. Without this ordering, a stalled
        // collector would leave the engine thread parked in acquire() and the executor's
        // awaitTermination(10s) below would time out.
        //
        // Note: emitter.close() itself may block up to ~10s waiting for OkHttp's socket
        // timeout on stalled in-flight requests inside tracker.close(). Total worst-case
        // stop() duration is therefore ~20s (tracker socket timeout + awaitTermination
        // budget). Tune Kubernetes terminationGracePeriodSeconds accordingly — the default
        // 30s is safe; values under ~25s risk SIGKILL.
        try {
            emitter.close()
        } catch (e: Exception) {
            log.warn("Error while closing emitter", e)
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            log.warn("Error while closing Debezium engine", e)
        }
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    fun awaitShutdown() {
        shutdownLatch.await()
    }

    // ChangeEvent<K, V> is Debezium's envelope type. key() is the row identity (JSON object
    // with the PK columns); value() is the full change payload (before/after/source/op fields).
    // A null value() means a tombstone — a Kafka-level delete marker irrelevant in embedded mode.
    //
    // `internal` rather than `private` so that EngineRunnerInstrumentationTest in the same
    // module's test source set can call it directly without a full Debezium engine.
    internal fun handleRecord(record: ChangeEvent<String, String>) {
        val value = record.value() ?: return
        // Debezium emits periodic heartbeat control records (when heartbeat.interval.ms > 0) onto a
        // dedicated topic named with the `__debezium-heartbeat` prefix. Their value carries a
        // ts_ms-only schema with no `after` field, so they are not table change events and must
        // never reach the fingerprinter — otherwise every heartbeat throws "missing
        // schema.fields[after]", is swallowed by the catch-all below, logged as a spurious ERROR,
        // and counted as `dropped`. They are pure liveness signals, so ignore them entirely (not
        // counted as received): `received`/`emitted`/`dropped` stay restricted to data changes.
        //
        // Heartbeats are the only non-data record reaching handleRecord in this service's current
        // config. Transaction-boundary records (topic `<prefix>.transaction`) are a known sibling
        // case that would hit the same catch-all — but provide.transaction.metadata is false, so
        // they are never produced. If that is ever enabled, extend the skip here.
        if (record.destination()?.startsWith(HEARTBEAT_TOPIC_PREFIX) == true) return
        counters.received.incrementAndGet()
        try {
            // Run DDL detection before assemble/emit so the WARN reaches the log even if the
            // emitter blocks downstream — operators get the drift signal ahead of any backpressure.
            val snapshot = SourceSchemaFingerprinter.snapshot(value)
            detector.observe(snapshot.table, snapshot)?.let { change ->
                log.warn("source_schema_change", StructuredArguments.entries(change.toLogMap()))
            }
            val event = assembler.assemble(value)
            counters.incPerTableOp(event.tableKey, event.op)
            emitter.emit(event)
            counters.emitted.incrementAndGet()
        } catch (e: UnsupportedOpException) {
            log.warn("skipping unsupported op '{}' for table {}", e.op, e.table)
            counters.dropped.incrementAndGet()
        } catch (e: EmitterClosedException) {
            // The emitter was closed mid-call by stop(). Treat this as a normal shutdown
            // signal — not an error and not counted as `dropped` (the event was in flight
            // and is lost only because the service is going down).
            log.info("emitter closed; ignoring event during shutdown")
        } catch (e: Exception) {
            log.error("failed to process change event; skipping", e)
            counters.dropped.incrementAndGet()
        }
    }

    private fun buildProperties(): Properties {
        val src = config.source
        val deb = config.debezium
        val tableIncludeList = config.tables
            .joinToString(",") { "${it.schema}.${it.name}" }

        // `Properties().apply { ... }` is Kotlin's create-and-configure idiom.
        // `apply` calls the lambda with `this` bound to the new Properties instance and
        // returns the instance — equivalent to `val p = Properties(); p.setProperty(...); ...; p`
        // but without the temporary variable.
        return Properties().apply {
            setProperty("name", "cdc-service")
            setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector")
            setProperty("topic.prefix", "cdc-service")

            // Hardcoded values — single right answer for this service:
            // pgoutput is the logical decoding plugin built into PostgreSQL >= 10 — no server
            // extension required (unlike decoderbufs or wal2json).
            setProperty("plugin.name", "pgoutput")
            // tombstones.on.delete is a Kafka Connect concept — in embedded mode it has no
            // consumer; hardcoded false to suppress the null-value records entirely.
            setProperty("tombstones.on.delete", "false")
            // string mode avoids IEEE-754 float precision loss for NUMERIC/DECIMAL columns.
            setProperty("decimal.handling.mode", "string")

            // Config-driven values:
            setProperty("slot.name", src.slotName)
            setProperty("publication.name", src.publicationName)
            setProperty("publication.autocreate.mode", deb.publicationAutocreateMode)
            setProperty("snapshot.mode", deb.snapshotMode)
            setProperty("provide.transaction.metadata", deb.provideTransactionMetadata.toString())
            if (deb.heartbeatIntervalMs > 0) {
                setProperty("heartbeat.interval.ms", deb.heartbeatIntervalMs.toString())
            }

            // Source connection:
            setProperty("database.hostname", src.hostname)
            setProperty("database.port", src.port.toString())
            setProperty("database.dbname", src.database)
            setProperty("database.user", src.username)
            setProperty("database.password", src.password)
            setProperty("table.include.list", tableIncludeList)

            // Offset store: file (default) or jdbc (durable, survives ephemeral filesystems).
            val offsetStore = deb.offsetStore
            when (offsetStore.type) {
                "jdbc" -> {
                    val jdbc = offsetStore.jdbc
                        ?: error("offset_store.type=jdbc requires a jdbc block (validated upstream)")
                    setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore")
                    // URL derived from source.* — the offset table lives in the source database.
                    setProperty("offset.storage.jdbc.url", postgresJdbcUrl(src))
                    setProperty("offset.storage.jdbc.user", jdbc.username)
                    setProperty("offset.storage.jdbc.password", jdbc.password)
                    setProperty("offset.storage.jdbc.offset.table.name", jdbc.tableName)
                }
                else -> {
                    setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
                    setProperty(
                        "offset.storage.file.filename",
                        offsetStore.filePath
                            ?: error("offset_store.type=file requires file_path (validated upstream)"),
                    )
                }
            }
            setProperty("offset.flush.interval.ms", "1000")
            setProperty("key.converter.schemas.enable", "false")
            // schemas.enable=true so the value envelope carries Connect's struct schema; the
            // cdc_source fingerprint helper reads field types from it. Key schemas remain off —
            // we don't fingerprint keys.
            setProperty("value.converter.schemas.enable", "true")
        }
    }

    companion object {
        // Debezium's default heartbeat topic prefix (io.debezium.heartbeat.Heartbeat
        // #HEARTBEAT_TOPICS_PREFIX, default "__debezium-heartbeat"). We never override
        // heartbeat.topics.prefix, so heartbeat records land on `__debezium-heartbeat.<topic.prefix>`.
        private const val HEARTBEAT_TOPIC_PREFIX = "__debezium-heartbeat"
    }
}
