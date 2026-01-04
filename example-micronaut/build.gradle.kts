plugins {
    //id("com.gradleup.shadow") version "9.3.0"
    id("io.micronaut.application") version "4.4.4"
    id("io.micronaut.aot") version "4.4.4"
}

version = "0.1"
group = "example.micronaut.durable"

repositories {
    mavenCentral()
}

val rocksdbVersion = extra["rocksdbVersion"] as String
val rocksdbClassifier = extra["rocksdbClassifier"] as String

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation(project(":durable-streams-micronaut"))
    implementation(project(":durable-streams-json-jackson"))
    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")
    testImplementation("io.micronaut:micronaut-http-client")
}


application {
    mainClass = "example.micronaut.durable.Application"
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}


graalvmNative.toolchainDetection = false

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("example.micronaut.durable.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}


tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "17"
}


