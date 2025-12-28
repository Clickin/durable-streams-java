@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
set "JAR_PATH=%ROOT_DIR%\durable-streams-conformance-runner\build\libs\client-adapter.jar"

if not exist "%JAR_PATH%" (
    echo ERROR: Client adapter JAR not found at %JAR_PATH% >&2
    echo Please build it first: gradlew :durable-streams-conformance-runner:clientAdapterJar >&2
    exit /b 1
)

java -jar "%JAR_PATH%"
exit /b %ERRORLEVEL%
