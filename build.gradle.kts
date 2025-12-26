
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

