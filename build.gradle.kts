plugins {
    // Root intentionally minimal; per-module plugins are applied in each subproject.
}

allprojects {
    group = "io.durablestreams"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Make every submodule buildable (even placeholders).
    apply(plugin = "java-library")
/*
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
        withJavadocJar()
    }
*/
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
