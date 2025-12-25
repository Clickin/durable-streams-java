plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(project(":durable-streams-server-core"))
    implementation(libs.javalin)
}

application {
    mainClass.set("io.durablestreams.conformance.ConformanceServer")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
