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

package com.snowplowanalytics.cdc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.snowplowanalytics.cdc.config.Config
import com.snowplowanalytics.cdc.config.ConfigException
import com.snowplowanalytics.cdc.config.ConfigSource
import com.snowplowanalytics.cdc.scaffold.ScaffoldInputError
import com.snowplowanalytics.cdc.scaffold.ScaffoldInputs
import com.snowplowanalytics.cdc.scaffold.ScaffoldOutputError
import com.snowplowanalytics.cdc.scaffold.Scaffolder
import com.snowplowanalytics.cdc.validate.ValidateRunner
import com.snowplowanalytics.cdc.emitter.SnowplowEmitter
import com.snowplowanalytics.cdc.engine.EngineRunner
import com.snowplowanalytics.cdc.observability.Counters
import com.snowplowanalytics.cdc.observability.HeartbeatScheduler
import com.snowplowanalytics.cdc.observability.ObservabilityServer
import com.snowplowanalytics.cdc.observability.ReadinessProbe
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

// Top-level function — Kotlin allows functions outside any class. This one is the SLF4J logger
// for this file; "Main" is an arbitrary name shown in log output.
private val log = LoggerFactory.getLogger("Main")

// Shared by `run` and `validate`: exactly one of --config / --config-env. .single() enforces the
// "at most one" rule (both given → MutuallyExclusiveGroupException, a UsageError subtype) and
// .required() (at each call site) enforces "at least one" (neither given → UsageError). Together
// they guarantee exactly one is supplied at parse time.
private fun ParameterHolder.configSourceOption() =
    mutuallyExclusiveOptions(
        option("--config", help = "Path to the YAML config file")
            .path(mustExist = true, canBeDir = false, mustBeReadable = true)
            .convert { ConfigSource.File(it) },
        option("--config-env", help = "Name of an env var holding the YAML config body (alternative to --config)")
            .convert { ConfigSource.Env(it) },
        name = "Config source",
    ).single()

// NoOpCliktCommand is a clikt parent that does nothing by itself — it exists only to host
// subcommands. Without it you'd need an explicit "do nothing" run() on the root command.
class CdcServiceCli : NoOpCliktCommand(name = "cdc-service")

class RunCommand : CliktCommand(name = "run") {
    private val configSource: ConfigSource by configSourceOption().required()
    override fun run() = runService(configSource)
}

class ScaffoldCommand : CliktCommand(name = "scaffold") {
    private val connection: String by option(
        "--connection",
        help = "Postgres URI: postgres://, postgresql://, or jdbc:postgresql:// (password via PGPASSWORD env var)",
    ).required()
    private val vendor: String by option(
        "--vendor",
        help = "Iglu vendor namespace for generated per-table schemas, e.g. com.acme.cdc",
    ).required()
    private val tables: String by option(
        "--tables",
        help = "Comma-separated tables; entries may be schema-qualified (defaults to public)",
    ).required()
    private val output: Path by option(
        "--output",
        help = "Fresh output directory; refuses to write into a non-empty one",
    ).path(canBeFile = false).required()

    override fun run() {
        if (!vendor.matches(Regex("^[a-zA-Z0-9.-]+$"))) {
            echo("invalid --vendor '$vendor': must match ^[a-zA-Z0-9.-]+\$", err = true)
            throw ProgramResult(1)
        }
        try {
            val conn = ScaffoldInputs.parseConnection(connection) { System.getenv(it) }
            val tableKeys = ScaffoldInputs.parseTables(tables)
            Scaffolder(conn, vendor, tableKeys, output).run()
            echo("Scaffold complete: $output (review before deploying)")
        } catch (e: ScaffoldInputError) {
            echo(e.message, err = true); throw ProgramResult(1)
        } catch (e: ScaffoldOutputError) {
            echo(e.message, err = true); throw ProgramResult(1)
        } catch (e: Exception) {
            echo("scaffold failed: ${e.message}", err = true); throw ProgramResult(1)
        }
    }
}

class ValidateCommand : CliktCommand(name = "validate") {
    private val configSource: ConfigSource by configSourceOption().required()
    override fun run() {
        val code = ValidateRunner.run(configSource, System.out, System.err)
        if (code != 0) throw ProgramResult(code)
    }
}

fun main(args: Array<String>) {
    CdcServiceCli()
        .subcommands(RunCommand(), ScaffoldCommand(), ValidateCommand())
        .main(args)  // clikt's entry point — parses argv, routes to the matching subcommand
}

private fun runService(configSource: ConfigSource) {
    val config: Config = try {
        configSource.load()
    } catch (e: ConfigException) {
        // Print the formatted block to stderr for the operator's eyes (multi-line, no JSON log
        // wrapping). Also log a one-line marker so the structured-log pipeline notices.
        System.err.println(e.message)
        log.error("configuration invalid; see stderr for details")
        exitProcess(1)
    }

    val counters = Counters(bufferCapacity = config.snowplow.emitter.bufferCapacity)
    val probe = ReadinessProbe()

    // Start the HTTP server first so orchestrators see /health=200 immediately, before any
    // potentially slow emitter/engine construction. /ready returns 503 until the engine connects.
    val httpServer = try {
        ObservabilityServer(config.observability.http.port, probe).also { it.start() }
    } catch (e: Exception) {
        System.err.println("failed to bind HTTP port ${config.observability.http.port}: ${e.message}")
        log.error("failed to bind HTTP port ${config.observability.http.port}", e)
        exitProcess(1)
    }

    // Register the shutdown hook immediately after the HTTP server starts. Capture nullable refs
    // for components not yet constructed so the hook can still close the HTTP server even if
    // SnowplowEmitter, EngineRunner, or HeartbeatScheduler construction subsequently throws.
    // JVM shutdown hooks run on SIGTERM / SIGINT and on normal process exit. Shutdown order:
    //   1. heartbeat — stop periodic logging first (no new ticks after shutdown begins)
    //   2. runner.stop() — stops the Debezium engine and closes the emitter
    //   3. httpServer.close() — keep /health=200 until the very last moment
    var heartbeat: HeartbeatScheduler? = null
    var runner: EngineRunner? = null
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutdown signal received; stopping observability + engine")
        try { heartbeat?.close() } catch (e: Exception) { log.warn("error closing heartbeat", e) }
        try { runner?.stop() } catch (e: Exception) { log.warn("error stopping engine", e) }
        try { httpServer.close() } catch (e: Exception) { log.warn("error closing HTTP server", e) }
    })

    // Mark the emitter ready immediately after construction. The block-on-overflow self-test
    // (Slice 7's preflight inside EngineRunner.start) validates the SnowplowEmitter *class*
    // contract via a throwaway instance and a stub collector — it does not validate this
    // production instance, so there is no later "self-test passed" point to gate readiness on.
    // Once the constructor returns, the underlying Snowplow tracker is up; the emitter is
    // ready for traffic.
    val emitter = SnowplowEmitter(
        collectorUrl = config.snowplow.collectorUrl,
        namespace = "cdc-service",
        appId = config.service.appId,
        batchSize = config.snowplow.emitter.batchSize,
        bufferCapacity = config.snowplow.emitter.bufferCapacity,
        counters = counters,
    ).also { probe.markEmitterReady() }

    runner = EngineRunner(config, emitter, counters, probe)

    // Heartbeat starts after runner is constructed; ticks will reflect ready=false until the
    // Debezium connector fires its first ConnectorCallback and markEngineReady() is called.
    heartbeat = HeartbeatScheduler(
        intervalMs = config.observability.heartbeat.intervalMs,
        counters = counters,
        probe = probe,
        emitter = emitter,
    ).also { it.start() }

    log.info("starting cdc-service against {}:{}", config.source.hostname, config.source.port)
    try {
        runner.start()
        runner.awaitShutdown()
        log.info("cdc-service stopped")
    } catch (t: Throwable) {
        // Without explicit exit, Jetty's non-daemon threads keep the JVM alive after the main
        // thread dies, so an unhandled startup error would hang the process. exitProcess(1)
        // both signals failure and triggers the shutdown hook above, which performs the actual
        // teardown — no need to duplicate the close calls here.
        log.error("service startup failed; exiting", t)
        exitProcess(1)
    }
}
