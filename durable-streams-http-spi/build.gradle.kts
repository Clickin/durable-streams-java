plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    // Optional HTTP client implementations
    compileOnly(libs.apache.httpclient5)
    compileOnly(libs.okhttp)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
