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
    implementation("io.debezium:debezium-api:3.0.7.Final")
    implementation("io.debezium:debezium-embedded:3.0.7.Final")
    implementation("io.debezium:debezium-connector-postgres:3.0.7.Final")
    implementation("io.debezium:debezium-storage-jdbc:3.0.7.Final")
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
