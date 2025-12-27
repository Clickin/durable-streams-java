plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-http-spi"))
    api("org.springframework:spring-web:6.2.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
