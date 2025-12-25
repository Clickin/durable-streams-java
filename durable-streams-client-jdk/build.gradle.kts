plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-core"))
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
