$server = Start-Process -FilePath ".\gradlew.bat" `
  -ArgumentList ":durable-streams-conformance-runner:runConformanceServer" `
  -PassThru

try {
  Start-Sleep -Seconds 2
  npx @durable-streams/server-conformance-tests --run http://localhost:4437
} finally {
  if (!$server.HasExited) { $server.Kill() }
}
