plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api(project(":durable-streams-server-spi"))
    api(project(":durable-streams-client"))
    compileOnly("org.springframework:spring-webflux:6.2.1")
    compileOnly("io.projectreactor:reactor-core:3.7.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
