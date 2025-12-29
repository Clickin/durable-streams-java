plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    api(project(":durable-streams-core"))
    api(project(":durable-streams-server-spi"))
    implementation(libs.lmdbjava)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}


tasks.named<JavaCompile>("compileJava").configure {
    options.release.set(17)
}

tasks.named<JavaCompile>("compileTestJava").configure {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
    )
}

sourceSets {
    test {
        java {
            exclude("**/StorageBenchmark.java")
        }
    }
}

