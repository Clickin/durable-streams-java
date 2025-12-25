plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-client-jdk"))
    api("io.micronaut:micronaut-http-client:4.7.0")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
