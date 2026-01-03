#!/bin/bash
set -e

echo "🔨 Building project..."
./gradlew build -x test

echo ""
echo "📊 Running benchmarks against Quarkus + InMemoryStreamStore"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

./gradlew :example-quarkus:quarkusRun > /dev/null 2>&1 &
QUARKUS_PID=$!
echo "Started Quarkus server (PID: $QUARKUS_PID)"

sleep 5

echo "Waiting for server to be ready..."
for i in {1..30}; do
    if curl -s http://127.0.0.1:4432/ > /dev/null 2>&1; then
        echo "✓ Server is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "✗ Server failed to start"
        kill $QUARKUS_PID 2>/dev/null || true
        exit 1
    fi
    sleep 1
done

echo ""
echo "Running InMemoryStreamStore benchmarks..."
cd benchmarks
npm install --silent
npm run bench:inmemory

echo ""
echo "Stopping Quarkus server..."
kill $QUARKUS_PID 2>/dev/null || true
wait $QUARKUS_PID 2>/dev/null || true

echo ""
echo "✅ Benchmarks complete!"
echo "📄 Results saved to: benchmarks/benchmark-results.json"
