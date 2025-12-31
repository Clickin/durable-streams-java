$ErrorActionPreference = "Stop"

Push-Location "$PSScriptRoot\conformance-node"
try {
  npm run test:client -- --reporter verbose
} finally {
  Pop-Location
}
