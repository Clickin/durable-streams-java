import org.gradle.jvm.toolchain.JvmVendorSpec

rootProject.name = "durable-streams-java"

toolchainManagement {
  jvm {
    javaRepositories {
      repository("adoptium") {
        vendor = JvmVendorSpec.ADOPTIUM
      }
    }
  }
}

include(
  "durable-streams-core",
  "durable-streams-http-spi",
  "durable-streams-http-jdk",
  "durable-streams-http-apache5",
  "durable-streams-http-okhttp",
  "durable-streams-http-spring",
  "durable-streams-client-jdk",
  "durable-streams-json-spi",
  "durable-streams-json-jackson",
  "durable-streams-reactive-adapters",
  "durable-streams-client-reactor",
  "durable-streams-client-rxjava3",
  "durable-streams-kotlin",
  "durable-streams-server-spi",
  "durable-streams-server-core",
  "durable-streams-spring-webflux",
  "durable-streams-spring-webmvc",
  "durable-streams-spring-webflux-starter",
  "durable-streams-spring-webmvc-starter",
  "durable-streams-spring-boot-starter",
  "durable-streams-micronaut-server",
  "durable-streams-micronaut-client",
  "durable-streams-quarkus-server",
  "durable-streams-quarkus-client",
  "durable-streams-conformance-runner"
)
