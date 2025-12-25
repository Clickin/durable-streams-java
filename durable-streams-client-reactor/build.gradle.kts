plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-client-jdk"))
    api(project(":durable-streams-reactive-adapters"))
    api("io.projectreactor:reactor-core:3.7.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
