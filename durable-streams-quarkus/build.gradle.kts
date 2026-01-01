plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api(project(":durable-streams-client"))
    compileOnly("io.quarkus:quarkus-rest:3.18.0")
    compileOnly("io.quarkus:quarkus-rest-jackson:3.18.0")
    compileOnly("io.smallrye.reactive:mutiny:2.7.0")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
