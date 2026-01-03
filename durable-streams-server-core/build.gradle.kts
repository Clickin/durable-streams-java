plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

fun detectRocksDbClassifier(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("win") -> "win64"
        osName.contains("mac") || osName.contains("darwin") -> "osx"
        else -> "linux64"
    }
}

val rocksdbVersion = libs.versions.rocksdb.get()
val rocksdbClassifier = providers.gradleProperty("rocksdbClassifier")
    .orElse(providers.environmentVariable("ROCKSDB_CLASSIFIER"))
    .orElse(provider { detectRocksDbClassifier() })
    .get()

dependencies {
    api(project(":durable-streams-core"))
    api(project(":durable-streams-server-spi"))
    implementation(project(":durable-streams-json-spi"))
    implementation(libs.rocksdb)
    runtimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")
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
