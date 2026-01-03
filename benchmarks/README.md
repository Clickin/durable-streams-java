# Performance Benchmarks

Benchmark suite for durable-streams-java using the official upstream [@durable-streams/benchmarks](https://github.com/durable-streams/durable-streams/tree/main/packages/benchmarks).

## Prerequisites

- JDK 21+ (for Virtual Threads optimal performance)
- Node.js 18+
- Quarkus server running

**First time setup:**
```bash
cd benchmarks
npm install
```

## Storage Backends

### InMemoryStreamStore (Default)

Fast in-memory storage for testing and benchmarking raw protocol performance.

**Start server:**
```bash
cd ..
./gradlew :example-quarkus:quarkusRun
```

**Run benchmarks:**
```bash
cd benchmarks
npm install
npm run bench:inmemory
```

### BlockingFileStreamStore

Persistent file-based storage using Virtual Threads + blocking I/O.

**Start server:**
```bash
cd ..
./gradlew :example-quarkus:quarkusRun --args='-Dquarkus.profile=filestore'
```

**Run benchmarks:**
```bash
cd benchmarks
npm run bench:filestore
```

## Benchmark Metrics

The upstream benchmark suite measures:

### 1. Latency (Round-Trip Time)
- **Baseline Ping**: Network latency only
- **Total RTT**: Full append + long-poll cycle
- **Overhead**: Durable Streams protocol overhead

**Success Criteria**: < 10ms overhead

### 2. Message Throughput
- **Small Messages** (100 bytes): Target 100+ msg/sec
- **Large Messages** (1MB): Measure with high concurrency

### 3. Byte Throughput
- **Streaming**: Target 100+ MB/sec

## Results

Results are saved to `benchmark-results.json` with statistics (min/max/mean/p50/p75/p99).

Example output:
```
=== BENCHMARK RESULTS ===
Environment: quarkus-inmemory
Base URL: http://127.0.0.1:4432

┌──────────────────────────┬────────────┬────────────┬────────────┬────────────┬────────────┬────────────┬────────────┐
│ (index)                  │ Min        │ Max        │ Mean       │ P50        │ P75        │ P99        │ Iterations │
├──────────────────────────┼────────────┼────────────┼────────────┼────────────┼────────────┼────────────┼────────────┤
│ Latency - Total RTT      │ '5.23 ms'  │ '12.45 ms' │ '7.82 ms'  │ '7.50 ms'  │ '8.90 ms'  │ '11.20 ms' │ 10         │
│ Latency - Overhead       │ '2.10 ms'  │ '8.30 ms'  │ '4.15 ms'  │ '3.80 ms'  │ '5.20 ms'  │ '7.50 ms'  │ 10         │
│ Throughput - Small       │ '350 msg/s'│ '520 msg/s'│ '425 msg/s'│ '420 msg/s'│ '480 msg/s'│ '510 msg/s'│ 3          │
│ Throughput - Large       │ '12 msg/s' │ '18 msg/s' │ '15 msg/s' │ '15 msg/s' │ '17 msg/s' │ '18 msg/s' │ 2          │
└──────────────────────────┴────────────┴────────────┴────────────┴────────────┴────────────┴────────────┴────────────┘
```

## Comparing Implementations

Run both storage backends and compare results:

```bash
npm run bench:all
```

This runs:
1. `bench:inmemory` - Tests raw protocol performance
2. `bench:filestore` - Tests real-world persistent storage performance

Compare the two `benchmark-results.json` outputs to see the performance difference between in-memory and file-based storage.

## Custom Configuration

Override benchmark settings via environment variables:

```bash
BASE_URL=http://localhost:8080 ENVIRONMENT=my-custom-env npm run bench:inmemory
```

## Troubleshooting

**Server not responding?**
- Ensure server is running on correct port (default: 4432)
- Check `curl http://127.0.0.1:4432/health` (add health endpoint if needed)

**Benchmarks timing out?**
- Increase Vitest timeout in test files
- Reduce concurrency/message counts for slower systems
