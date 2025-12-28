$ErrorActionPreference = "Stop"

Write-Host "Building client adapter JAR..."
& "$PSScriptRoot\gradlew.bat" :durable-streams-conformance-runner:clientAdapterJar
if ($LASTEXITCODE -ne 0) {
  Write-Error "Failed to build client adapter JAR"
  exit 1
}

$jarPath = "$PSScriptRoot\durable-streams-conformance-runner\build\libs\client-adapter.jar"
if (-not (Test-Path $jarPath)) {
  Write-Error "JAR not found at: $jarPath"
  exit 1
}
Write-Host "JAR built successfully: $jarPath"

Push-Location "$PSScriptRoot\conformance-node"
try {
  npm run test:client -- --reporter verbose
} finally {
  Pop-Location
}
