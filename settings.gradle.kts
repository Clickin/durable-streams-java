plugins {
    id("com.gradleup.nmcp.settings") version "1.4.3"
}

rootProject.name = "durable-streams-java"

include(
  "durable-streams-core",
  "durable-streams-client",
  "durable-streams-json-spi",
  "durable-streams-json-jackson",
  "durable-streams-server-spi",
  "durable-streams-server-core",
  "durable-streams-servlet",
  "durable-streams-spring-webflux",
  "durable-streams-micronaut",
  "durable-streams-quarkus",
  "durable-streams-ktor",
  "durable-streams-conformance-runner",
  "example-quarkus",
  "example-micronaut",
  "example-spring-webflux",
  "example-spring-webmvc",
  "example-ktor"
)

nmcpSettings {
    centralPortal {
        username.set(System.getenv("CENTRAL_PORTAL_USERNAME"))
        password.set(System.getenv("CENTRAL_PORTAL_PASSWORD"))
        publishingType.set("USER_MANAGED")
    }
}


