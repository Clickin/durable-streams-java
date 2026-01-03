plugins {
    kotlin("jvm") version "2.2.20"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api(project(":durable-streams-client"))
    compileOnly("io.ktor:ktor-server-core:3.3.2")
    compileOnly("io.ktor:ktor-server-sse:3.3.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
