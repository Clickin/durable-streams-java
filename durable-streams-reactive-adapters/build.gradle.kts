plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-core"))
    // For FlowAdapters
    api("org.reactivestreams:reactive-streams-flow-adapters:1.0.4")
    api("org.reactivestreams:reactive-streams:1.0.4")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
