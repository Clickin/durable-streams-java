plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "example.ktor"
version = "0.1"

application {
    mainClass.set("example.ktor.ApplicationKt")
}

repositories {
    mavenCentral()
}

val rocksdbVersion = extra["rocksdbVersion"] as String
val rocksdbClassifier = extra["rocksdbClassifier"] as String

dependencies {
    implementation(project(":durable-streams-ktor"))
    implementation(project(":durable-streams-json-jackson"))
    compileOnly("io.ktor:ktor-server-core:3.3.2")
    compileOnly("io.ktor:ktor-server-netty:3.3.2")
    compileOnly("io.ktor:ktor-server-sse:3.3.2")
    runtimeOnly("io.ktor:ktor-server-core:3.3.2")
    runtimeOnly("io.ktor:ktor-server-netty:3.3.2")
    runtimeOnly("io.ktor:ktor-server-sse:3.3.2")
    runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
    runtimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
