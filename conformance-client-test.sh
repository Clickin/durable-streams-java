#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building client adapter JAR..."
"${SCRIPT_DIR}/gradlew" :durable-streams-conformance-runner:clientAdapterJar

JAR_PATH="${SCRIPT_DIR}/durable-streams-conformance-runner/build/libs/client-adapter.jar"
if [ ! -f "${JAR_PATH}" ]; then
  echo "ERROR: JAR not found at: ${JAR_PATH}" >&2
  exit 1
fi
echo "JAR built successfully: ${JAR_PATH}"

cd "${SCRIPT_DIR}/conformance-node"
npm run test:client -- --reporter verbose
