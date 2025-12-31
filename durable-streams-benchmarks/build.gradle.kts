plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

application {
    mainClass.set("io.durablestreams.server.core.StorageBenchmark")
}

dependencies {
    implementation(project(":durable-streams-server-core"))
}

tasks.named<JavaCompile>("compileJava").configure {
    options.release.set(21)
}

tasks.named<JavaExec>("run").configure {
    jvmArgs(
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
    )
}