plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    api(project(":durable-streams-core"))
    api(project(":durable-streams-server-spi"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<JavaCompile>("compileJava").configure {
    options.release.set(17)
}

tasks.named<JavaCompile>("compileTestJava").configure {
    options.release.set(17)
}

sourceSets {
    test {
        java {
            exclude("**/StorageBenchmark.java")
        }
    }
}
