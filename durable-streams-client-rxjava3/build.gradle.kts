plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-client-jdk"))
    api(project(":durable-streams-reactive-adapters"))
    api("io.reactivex.rxjava3:rxjava:3.1.9")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
