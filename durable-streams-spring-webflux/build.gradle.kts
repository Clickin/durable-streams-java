plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api(project(":durable-streams-client"))
    api("org.springframework:spring-webflux:6.2.1")
    api("io.projectreactor:reactor-core:3.7.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
