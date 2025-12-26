plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    api(project(":durable-streams-spring-webflux"))
    api("org.springframework.boot:spring-boot-autoconfigure:3.4.1")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.1")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:3.4.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
