#!/usr/bin/env bash
# Client conformance adapter for Unix/Linux/macOS
# Runs the pre-built fat JAR for client conformance testing

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAR_PATH="${ROOT_DIR}/durable-streams-conformance-runner/build/libs/client-adapter.jar"

if [ ! -f "${JAR_PATH}" ]; then
    echo "ERROR: Client adapter JAR not found at ${JAR_PATH}" >&2
    echo "Please build it first: ./gradlew :durable-streams-conformance-runner:clientAdapterJar" >&2
    exit 1
fi

exec java -jar "${JAR_PATH}"
