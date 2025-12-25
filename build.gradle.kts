import com.github.gradle.node.npm.task.NpxTask

plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("20.11.1")
    download.set(true)
}

allprojects {
    group = "io.durablestreams"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register<NpxTask>("serverConformanceTest") {
    group = "verification"
    description = "Run durable-streams server conformance tests (server must be running)."
    command.set("@durable-streams/server-conformance-tests")
    args.set(listOf("--run", "http://localhost:4437"))
    dependsOn(tasks.named("nodeSetup"))
}
