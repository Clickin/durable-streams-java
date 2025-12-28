import { runConformanceTests } from "@durable-streams/server-conformance-tests";

runConformanceTests({
  baseUrl: process.env.STREAM_URL ?? "http://localhost:4437",
});
