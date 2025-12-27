plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-http-spi"))
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
