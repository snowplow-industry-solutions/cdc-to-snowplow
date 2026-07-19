plugins {
    kotlin("jvm") version "2.1.10"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("com.google.cloud.tools.jib") version "3.4.4"
}

group = "com.snowplowanalytics"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.debezium:debezium-api:3.6.0.Final")
    implementation("io.debezium:debezium-embedded:3.6.0.Final")
    implementation("io.debezium:debezium-connector-postgres:3.6.0.Final")
    implementation("io.debezium:debezium-storage-jdbc:3.6.0.Final")
    implementation("com.snowplowanalytics:snowplow-java-tracker:2.1.0")
    // snowplow-java-tracker 2.1.0 declares okhttp as provided/optional; it is required at
    // runtime by BatchEmitter's OkHttpClientAdapter. Without this the fat JAR's `run` mode
    // throws NoClassDefFoundError on OkHttpClient. Tests use RecordingEmitter so don't hit it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.github.ajalt.clikt:clikt:5.0.2")
    implementation("io.javalin:javalin:6.4.0")
    runtimeOnly("org.codehaus.janino:janino:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    implementation("org.postgresql:postgresql:42.7.5")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.1.7")
}

// Kafka Connect's REST-server stack (Jersey / HK2 / JAX-RS / JAXB) is dragged onto the classpath
// transitively via debezium-embedded -> org.apache.kafka:connect-runtime, but is never exercised:
// the embedded engine runs the connector in-process and does NOT start Connect's REST server
// (confirmed — EngineRunner only touches io.debezium.engine.* and the connect.storage offset
// stores; grep finds zero Jersey/JAX-RS references in src/). connect-runtime itself is KEPT because
// FileOffsetBackingStore / JdbcOffsetBackingStore live there. Excluding these leaves drops all 14
// GPL-2.0-with-classpath-exception ("restrictive license") Wiz findings plus their CVEs, and shrinks
// the fat JAR and image. Applied at the configuration level so no alternate resolution path
// (e.g. connect-api's own javax.ws.rs-api) can re-add them. See issue #2.
configurations.all {
    exclude(group = "org.glassfish.jersey.containers")
    exclude(group = "org.glassfish.jersey.core")
    exclude(group = "org.glassfish.jersey.inject")
    exclude(group = "org.glassfish.hk2")
    exclude(group = "org.glassfish.hk2.external")
    exclude(group = "jakarta.ws.rs")
    exclude(group = "javax.ws.rs")
    exclude(group = "javax.xml.bind", module = "jaxb-api")
    // jaxb-api's only live consumer was jackson-module-jaxb-annotations (dragged in by
    // connect-runtime for Connect's REST JSON provider). It is NOT used by JsonConverter or the
    // offset stores, but logstash-logback-encoder's Jackson findAndRegisterModules() auto-registers
    // it via ServiceLoader on startup — which calls JaxbAnnotationIntrospector -> javax.xml.bind
    // and NoClassDefFounds once jaxb-api is gone (the okhttp precedent, issue #2 / build.gradle:21).
    // Excluding the module removes that auto-registration so jaxb-api genuinely becomes dead weight.
    exclude(group = "com.fasterxml.jackson.module", module = "jackson-module-jaxb-annotations")
}

application {
    mainClass.set("com.snowplowanalytics.cdc.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // Docker Desktop on macOS enforces API version >= 1.40, but Testcontainers' shaded
    // docker-java defaults to 1.32 when no version is configured.  The shaded config reads
    // the JVM system property "api.version" (not the env-var DOCKER_API_VERSION).
    systemProperty("api.version", "1.41")
    // Disable Ryuk resource reaper — it requires container-in-container, which the Docker
    // Desktop sandbox does not support, and container cleanup is unnecessary in CI/test.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}

tasks.processResources {
    from("schemas/com.snowplowanalytics/cdc_source/jsonschema") {
        into("iglu/com.snowplowanalytics/cdc_source/jsonschema")
    }
    from("schemas") {
        include("cdc-service-config.schema.json")
        into("iglu-config")
    }
}

tasks.shadowJar {
    archiveBaseName.set("cdc-service")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.snowplowanalytics.cdc.MainKt"
    }
}

// Packaging-path guard: proves the shaded JAR is actually runnable (catches the
// okhttp-style NoClassDefFoundError where an optional transitive dep is missing from the
// fat-JAR runtime classpath). Not wired into `check` — it launches a JVM and is a
// distribution smoke test, invoked explicitly in verification/CI.
//
// Uses the Gradle-resolved JDK 21 toolchain launcher, NOT `java` from PATH — the fat JAR is
// compiled for Java 21 (jvmToolchain(21)), and a stale PATH `java` (e.g. 15) would throw
// UnsupportedClassVersionError, failing the smoke test for the wrong reason.
tasks.register<Exec>("smokeJar") {
    dependsOn(tasks.shadowJar)
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    executable(launcher.get().executablePath)
    args("-jar", "build/libs/cdc-service.jar", "--help")
}

jib {
    from {
        // Jib's own default base for a JDK-21 project; matches the deleted Dockerfile and
        // carries a shell (`docker exec -it cdc-service sh` works for demo debugging).
        image = "eclipse-temurin:21-jre"
    }
    to {
        // Stable, version-independent tag; docker-compose.yml references it directly.
        // Each build overwrites :local — fine for a single-laptop prototype that doesn't publish.
        image = "cdc-service:local"
    }
    container {
        mainClass = "com.snowplowanalytics.cdc.MainKt"
        // Default subcommand, mirroring the old Dockerfile's ENTRYPOINT+CMD split:
        // bare `docker run` / `docker compose up` starts the service; passing args
        // (e.g. `validate --config ...`, `scaffold --connection ...`) overrides this default,
        // so all three subcommands route through the same clikt-dispatcher entrypoint.
        args = listOf("run", "--config", "/etc/cdc-service/config.yaml")
    }
    extraDirectories {
        // Bakes docker/jib-root/var/lib/cdc into the image (empty /var/lib/cdc), replacing
        // the deleted Dockerfile's `mkdir -p` — Jib cannot run shell commands.
        setPaths("docker/jib-root")
    }
}

// Prints the project version (and nothing else, under -q) so the release workflow can assert
// the git tag / dispatch input matches build.gradle.kts before it publishes an image.
tasks.register("printVersion") {
    doLast { println(project.version) }
}
