/**
 * Benchmark suite for Quarkus with BlockingFileStreamStore
 * 
 * Prerequisites:
 * 1. Modify example-quarkus DurableStreamsResource to use BlockingFileStreamStore
 * 2. Start the Quarkus server: ./gradlew :example-quarkus:quarkusRun
 * 3. Run benchmarks: npm run bench:filestore
 */

import { runBenchmarks } from "@durable-streams/benchmarks"

const baseUrl = process.env.BASE_URL || "http://127.0.0.1:4432"
const environment = process.env.ENVIRONMENT || "quarkus-filestore"

console.log(`\n🚀 Running benchmarks against: ${baseUrl}`)
console.log(`📊 Environment: ${environment}`)
console.log(`⚙️  Storage: BlockingFileStreamStore\n`)

runBenchmarks({
  baseUrl,
  environment,
})
