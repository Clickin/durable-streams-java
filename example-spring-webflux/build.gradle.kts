plugins {
	java
	id("org.springframework.boot") version "3.5.9"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "Durable streams Demo project for Spring Boot "

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

val rocksdbVersion = extra["rocksdbVersion"] as String
val rocksdbClassifier = extra["rocksdbClassifier"] as String

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation(project(":durable-streams-spring-webflux"))
	implementation(project(":durable-streams-json-jackson"))

	runtimeOnly("org.rocksdb:rocksdbjni:$rocksdbVersion:$rocksdbClassifier")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
