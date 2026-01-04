import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

val rocksdbVersion = extra["rocksdbVersion"] as String
val rocksdbClassifier = extra["rocksdbClassifier"] as String

dependencies {
    implementation(project(":durable-streams-server-core"))
    implementation(project(":durable-streams-client"))
    implementation(project(":durable-streams-json-spi"))
    implementation(project(":durable-streams-json-jackson"))
    implementation(libs.javalin)
    implementation("org.slf4j:slf4j-simple:2.0.16")

    runtimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")
}

application {
    mainClass.set("io.durablestreams.conformance.ConformanceServer")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.register<JavaExec>("runConformanceServer") {
    group = "application"
    description = "Start the Javalin conformance server on port 4437."
    dependsOn(tasks.named("classes"))
    mainClass.set("io.durablestreams.conformance.ConformanceServer")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runFileConformanceServer") {
    group = "application"
    description = "Start the Javalin conformance server on port 4437."
    dependsOn(tasks.named("classes"))
    mainClass.set("io.durablestreams.conformance.FileConformanceServer")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runClientAdapter") {
    group = "application"
    description = "Run the client conformance adapter via Gradle."
    dependsOn(tasks.named("classes"))
    mainClass.set("io.durablestreams.conformance.ClientConformanceAdapter")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<Jar>("clientAdapterJar") {
    group = "build"
    description = "Build a standalone jar for the client conformance adapter."
    archiveBaseName.set("client-adapter")
    archiveVersion.set("")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "io.durablestreams.conformance.ClientConformanceAdapter"
    }
    from(sourceSets.main.get().output)
    val runtimeClasspath = configurations.runtimeClasspath.get()
    dependsOn(runtimeClasspath)
    from(runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("serverConformanceTest") {
    group = "verification"
    description = "Run durable-streams server conformance tests (server must be running)."
    dependsOn(tasks.named("classes"))

    val marker = layout.buildDirectory.file("conformance/server-tests.marker")
    outputs.file(marker)
    outputs.upToDateWhen { false }

    doLast {
        val npxCommand = mutableListOf<String>().apply {
            add(npxExecutable())
            add("@durable-streams/server-conformance-tests")
            add("--run")
            add("http://localhost:4437")
        }
        val npxProcess = ProcessBuilder(npxCommand)
            .inheritIO()
            .start()
        val exitCode = npxProcess.waitFor()
        if (exitCode != 0) {
            error("Conformance tests failed with exit code $exitCode")
        }
        val markerFile = marker.get().asFile
        markerFile.parentFile.mkdirs()
        markerFile.writeText("ok")
    }
}

fun npxExecutable(): String {
    val osName = System.getProperty("os.name").lowercase()
    return if (osName.contains("windows")) "npx.cmd" else "npx"
}
