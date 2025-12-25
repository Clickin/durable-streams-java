plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    api(project(":durable-streams-server-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
