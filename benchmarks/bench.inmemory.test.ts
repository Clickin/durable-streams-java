/**
 * Benchmark suite for Quarkus with InMemoryStreamStore
 * 
 * Prerequisites:
 * 1. Start the Quarkus server with InMemoryStreamStore (default configuration)
 *    From repo root: ./gradlew :example-quarkus:quarkusRun
 * 
 * 2. Run benchmarks:
 *    npm run bench:inmemory
 */

import { runBenchmarks } from "@durable-streams/benchmarks"

const baseUrl = process.env.BASE_URL || "http://127.0.0.1:4432"
const environment = process.env.ENVIRONMENT || "quarkus-inmemory"

console.log(`\n🚀 Running benchmarks against: ${baseUrl}`)
console.log(`📊 Environment: ${environment}`)
console.log(`⚙️  Storage: InMemoryStreamStore\n`)

runBenchmarks({
  baseUrl,
  environment,
})
