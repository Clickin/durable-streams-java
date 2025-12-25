plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-server-core"))
    api("org.springframework:spring-webmvc:6.2.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
