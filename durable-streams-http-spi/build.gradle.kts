plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    // Optional HTTP client implementations - detected at runtime
    compileOnly(libs.apache.httpclient5)
    compileOnly(libs.okhttp)
    compileOnly("org.springframework:spring-web:6.2.1")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
