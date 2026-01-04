plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

val rocksdbVersion = extra["rocksdbVersion"] as String
val rocksdbClassifier = extra["rocksdbClassifier"] as String

dependencies {
    api(project(":durable-streams-core"))
    api(project(":durable-streams-server-spi"))
    implementation(project(":durable-streams-json-spi"))
    compileOnly(libs.rocksdb)
    testRuntimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")
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
