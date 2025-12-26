#!/usr/bin/env bash
set -euo pipefail

./gradlew :durable-streams-conformance-runner:runConformanceServer &
SERVER_PID=$!

cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

sleep 2
npx @durable-streams/server-conformance-tests --run http://localhost:4437
