plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api(project(":durable-streams-client"))
    compileOnly("io.micronaut:micronaut-http:4.7.0")
    compileOnly("io.micronaut:micronaut-http-server:4.7.0")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-parameters"))
}
