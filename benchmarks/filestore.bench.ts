/**
 * Benchmark suite for Quarkus with BlockingFileStreamStore
 *
 * Prerequisites:
 * 1. Modify example-quarkus DurableStreamsResource to use BlockingFileStreamStore
 * 2. Start the Quarkus server: ./gradlew :example-quarkus:quarkusRun
 * 3. Run benchmarks: npm run bench:filestore
 */

import { writeFileSync } from "node:fs"

import { BackoffDefaults, DurableStream } from "@durable-streams/client"
import { afterAll, bench, describe } from "vitest"

function intEnv(name: string, defaultValue: number): number {
  const raw = process.env[name]
  if (raw === undefined || raw === "") return defaultValue
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) ? parsed : defaultValue
}

function boolEnv(name: string, defaultValue: boolean): boolean {
  const raw = process.env[name]
  if (raw === undefined || raw === "") return defaultValue
  if (["1", "true", "yes", "y", "on"].includes(raw.toLowerCase())) return true
  if (["0", "false", "no", "n", "off"].includes(raw.toLowerCase())) return false
  return defaultValue
}

type AbortSignalWithTimeout = typeof AbortSignal & {
  timeout?: (ms: number) => AbortSignal
}

function timeoutSignal(timeoutMs: number): AbortSignal {
  const abortSignal = AbortSignal as AbortSignalWithTimeout
  if (typeof abortSignal.timeout === "function") {
    return abortSignal.timeout(timeoutMs)
  }

  const controller = new AbortController()
  const timeout = setTimeout(
    () => controller.abort(new Error(`timeout after ${timeoutMs}ms`)),
    timeoutMs
  )
  ;(timeout as unknown as { unref?: () => void }).unref?.()
  return controller.signal
}

const baseUrl = (() => {
  const raw = process.env.BENCH_BASE_URL ?? process.env.BASE_URL
  return raw && raw !== "/" ? raw : "http://127.0.0.1:4432"
})()

const environment =
  process.env.BENCH_ENVIRONMENT ?? process.env.ENVIRONMENT ?? "quarkus-filestore"

const benchDebug = boolEnv("BENCH_DEBUG", false)
const requestTimeoutMs = intEnv("BENCH_REQUEST_TIMEOUT_MS", 120_000)
const maxRetries = intEnv("BENCH_MAX_RETRIES", 5)
const maxDelayMs = intEnv("BENCH_MAX_DELAY_MS", 5_000)
const batching = boolEnv("BENCH_BATCHING", true)

const smallMessageCount = intEnv("BENCH_SMALL_MESSAGE_COUNT", 1000)
const smallConcurrency = intEnv("BENCH_SMALL_CONCURRENCY", 75)

const largeMessageCount = intEnv("BENCH_LARGE_MESSAGE_COUNT", 50)
const largeConcurrency = intEnv("BENCH_LARGE_CONCURRENCY", 15)

console.log(`\n🚀 Running benchmarks against: ${baseUrl}`)
console.log(`📊 Environment: ${environment}`)
console.log(`⚙️  Storage: BlockingFileStreamStore`)
if (benchDebug) {
  console.log(
    `🧪 Bench config: timeout=${requestTimeoutMs}ms retries=${maxRetries} maxDelay=${maxDelayMs}ms batching=${batching}`
  )
}
console.log("")

const results: Map<string, { values: Array<number>; unit: string }> = new Map()

function recordResult(name: string, value: number, unit: string) {
  if (!results.has(name)) {
    results.set(name, { values: [], unit })
  }
  results.get(name)!.values.push(value)
}

function calculateStats(values: Array<number>) {
  const sorted = [...values].sort((a, b) => a - b)
  const min = sorted[0]!
  const max = sorted[sorted.length - 1]!
  const mean = values.reduce((a, b) => a + b, 0) / values.length
  const p50 = sorted[Math.floor(sorted.length * 0.5)]!
  const p75 = sorted[Math.floor(sorted.length * 0.75)]!
  const p99 = sorted[Math.floor(sorted.length * 0.99)]!
  return { min, max, mean, p50, p75, p99 }
}

function printResults() {
  console.log(`\n=== RESULTS SO FAR ===`)

  const tableData: Record<string, unknown> = {}

  for (const [name, data] of results.entries()) {
    if (data.values.length === 0) continue
    const stats = calculateStats(data.values)

    tableData[name] = {
      Min: `${stats.min.toFixed(2)} ${data.unit}`,
      Max: `${stats.max.toFixed(2)} ${data.unit}`,
      Mean: `${stats.mean.toFixed(2)} ${data.unit}`,
      P50: `${stats.p50.toFixed(2)} ${data.unit}`,
      P75: `${stats.p75.toFixed(2)} ${data.unit}`,
      P99: `${stats.p99.toFixed(2)} ${data.unit}`,
      Iterations: data.values.length,
    }
  }

  console.table(tableData)
}

function backoffOptions() {
  return {
    ...BackoffDefaults,
    maxRetries,
    maxDelay: maxDelayMs,
    debug: benchDebug,
  }
}

afterAll(() => {
  const statsOutput: Record<string, unknown> = {}

  for (const [name, data] of results.entries()) {
    const stats = calculateStats(data.values)
    statsOutput[name] = {
      ...stats,
      unit: data.unit,
      iterations: data.values.length,
    }
  }

  const output = {
    environment,
    baseUrl,
    timestamp: new Date().toISOString(),
    results: statsOutput,
  }

  writeFileSync(`benchmark-results.json`, JSON.stringify(output, null, 2), `utf-8`)

  console.log(`\n\n=== BENCHMARK RESULTS ===`)
  console.log(`Environment: ${output.environment}`)
  console.log(`Base URL: ${output.baseUrl}`)
  console.log(``)

  const finalTableData: Record<string, unknown> = {}
  for (const [name, stats] of Object.entries(statsOutput)) {
    const typed = stats as {
      min: number
      max: number
      mean: number
      p50: number
      p75: number
      p99: number
      unit: string
      iterations: number
    }

    finalTableData[name] = {
      Min: `${typed.min.toFixed(2)} ${typed.unit}`,
      Max: `${typed.max.toFixed(2)} ${typed.unit}`,
      Mean: `${typed.mean.toFixed(2)} ${typed.unit}`,
      P50: `${typed.p50.toFixed(2)} ${typed.unit}`,
      P75: `${typed.p75.toFixed(2)} ${typed.unit}`,
      P99: `${typed.p99.toFixed(2)} ${typed.unit}`,
      Iterations: typed.iterations,
    }
  }

  console.table(finalTableData)
  console.log(`\n\nResults saved to benchmark-results.json`)
})

describe(`Latency - Round-trip Time`, () => {
  bench(
    `baseline ping (round-trip network latency)`,
    async () => {
      const startTime = performance.now()
      await fetch(`${baseUrl}/health`, {
        signal: timeoutSignal(requestTimeoutMs),
      })
      const endTime = performance.now()

      const pingTime = endTime - startTime
      recordResult(`Baseline Ping`, pingTime, `ms`)
    },
    { iterations: 5, time: 5000 }
  )

  bench(
    `append and receive via long-poll (100 bytes)`,
    async () => {
      const streamPath = `/v1/stream/latency-bench-${Date.now()}-${Math.random()}`
      const stream = await DurableStream.create({
        url: `${baseUrl}${streamPath}`,
        contentType: `application/octet-stream`,
        batching,
        signal: timeoutSignal(requestTimeoutMs),
        backoffOptions: backoffOptions(),
      })

      const message = new Uint8Array(100).fill(42)
      let offset = (await stream.head()).offset

      const pingStart = performance.now()
      await fetch(`${baseUrl}/health`, {
        signal: timeoutSignal(requestTimeoutMs),
      })
      const pingEnd = performance.now()
      const pingTime = pingEnd - pingStart

      const warmupPromise = (async () => {
        const res = await stream.stream({
          offset,
          live: `long-poll`,
          signal: timeoutSignal(requestTimeoutMs),
        })

        await new Promise<void>((resolve) => {
          const unsubscribe = res.subscribeBytes(async (chunk) => {
            if (chunk.data.length > 0) {
              offset = chunk.offset
              unsubscribe()
              res.cancel()
              resolve()
            }
          })
        })
      })()

      await stream.append(message)
      await warmupPromise

      const readPromise = (async () => {
        const res = await stream.stream({
          offset,
          live: `long-poll`,
          signal: timeoutSignal(requestTimeoutMs),
        })

        await new Promise<void>((resolve) => {
          const unsubscribe = res.subscribeBytes(async (chunk) => {
            if (chunk.data.length > 0) {
              unsubscribe()
              res.cancel()
              resolve()
            }
          })
        })
      })()

      const startTime = performance.now()
      await stream.append(message)
      await readPromise
      const endTime = performance.now()

      try {
        await stream.delete({ signal: timeoutSignal(requestTimeoutMs) })
      } catch (err) {
        console.warn(`[bench] failed to delete latency stream:`, err)
      }

      const totalLatency = endTime - startTime
      const overhead = totalLatency - pingTime

      recordResult(`Latency - Total RTT`, totalLatency, `ms`)
      recordResult(`Latency - Ping`, pingTime, `ms`)
      recordResult(`Latency - Overhead`, overhead, `ms`)
    },
    { iterations: 10, time: 15000 }
  )

  afterAll(() => {
    printResults()
  })
})

describe(`Message Throughput`, () => {
  bench(
    `small messages (100 bytes)`,
    async () => {
      const streamPath = `/v1/stream/msg-small-${Date.now()}-${Math.random()}`
      const stream = await DurableStream.create({
        url: `${baseUrl}${streamPath}`,
        contentType: `application/octet-stream`,
        batching,
        signal: timeoutSignal(requestTimeoutMs),
        backoffOptions: backoffOptions(),
      })

      const message = new Uint8Array(100).fill(42)
      const messageCount = smallMessageCount
      const concurrency = smallConcurrency
      const batches = Math.ceil(messageCount / concurrency)

      const startTime = performance.now()

      for (let batch = 0; batch < batches; batch++) {
        const remaining = messageCount - batch * concurrency
        const batchSize = Math.min(concurrency, remaining)
        if (benchDebug) {
          console.log(`[bench] msg-small batch ${batch + 1}/${batches} (${batchSize} msgs)`)
        }

        await Promise.all(
          Array.from({ length: batchSize }, () => stream.append(message))
        )
      }

      const endTime = performance.now()
      const elapsedSeconds = (endTime - startTime) / 1000
      const messagesPerSecond = messageCount / elapsedSeconds

      try {
        await stream.delete({ signal: timeoutSignal(requestTimeoutMs) })
      } catch (err) {
        console.warn(`[bench] failed to delete msg-small stream:`, err)
      }

      recordResult(`Throughput - Small Messages`, messagesPerSecond, `msg/sec`)
    },
    { iterations: 3, time: 10000 }
  )

  bench(
    `large messages (1MB)`,
    async () => {
      const streamPath = `/v1/stream/msg-large-${Date.now()}-${Math.random()}`
      const stream = await DurableStream.create({
        url: `${baseUrl}${streamPath}`,
        contentType: `application/octet-stream`,
        batching,
        signal: timeoutSignal(requestTimeoutMs),
        backoffOptions: backoffOptions(),
      })

      const message = new Uint8Array(1024 * 1024).fill(42)
      const messageCount = largeMessageCount
      const concurrency = largeConcurrency
      const batches = Math.ceil(messageCount / concurrency)

      const startTime = performance.now()

      for (let batch = 0; batch < batches; batch++) {
        const remaining = messageCount - batch * concurrency
        const batchSize = Math.min(concurrency, remaining)
        if (benchDebug) {
          console.log(`[bench] msg-large batch ${batch + 1}/${batches} (${batchSize} msgs)`)
        }

        await Promise.all(
          Array.from({ length: batchSize }, () => stream.append(message))
        )
      }

      const endTime = performance.now()
      const elapsedSeconds = (endTime - startTime) / 1000
      const messagesPerSecond = messageCount / elapsedSeconds

      try {
        await stream.delete({ signal: timeoutSignal(requestTimeoutMs) })
      } catch (err) {
        console.warn(`[bench] failed to delete msg-large stream:`, err)
      }

      recordResult(`Throughput - Large Messages`, messagesPerSecond, `msg/sec`)
    },
    { iterations: 2, time: 10000 }
  )

  afterAll(() => {
    printResults()
  })
})

describe.skip(`Byte Throughput`, () => {
  bench(
    `streaming throughput - appendStream`,
    async () => {
      const streamPath = `/v1/stream/byte-stream-${Date.now()}-${Math.random()}`
      const stream = await DurableStream.create({
        url: `${baseUrl}${streamPath}`,
        contentType: `application/octet-stream`,
        batching,
        signal: timeoutSignal(requestTimeoutMs),
        backoffOptions: backoffOptions(),
      })

      const chunkSize = 64 * 1024
      const chunk = new Uint8Array(chunkSize).fill(42)
      const totalChunks = 100

      const startTime = performance.now()

      const appends = []
      for (let i = 0; i < totalChunks; i++) {
        appends.push(stream.append(chunk))
      }
      await Promise.all(appends)

      const endTime = performance.now()

      let bytesRead = 0
      const readRes = await stream.stream({
        live: false,
        signal: timeoutSignal(requestTimeoutMs),
      })
      const reader = readRes.bodyStream().getReader()
      let result = await reader.read()
      while (!result.done) {
        bytesRead += result.value.length
        result = await reader.read()
      }

      const elapsedSeconds = (endTime - startTime) / 1000
      const mbPerSecond = bytesRead / (1024 * 1024) / elapsedSeconds

      try {
        await stream.delete({ signal: timeoutSignal(requestTimeoutMs) })
      } catch (err) {
        console.warn(`[bench] failed to delete byte-stream:`, err)
      }

      recordResult(`Throughput - Streaming (appendStream)`, mbPerSecond, `MB/sec`)
    },
    { iterations: 3, time: 10000 }
  )

  afterAll(() => {
    printResults()
  })
})
