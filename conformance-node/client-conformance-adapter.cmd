@echo off
setlocal
set "JAR=%~dp0..\durable-streams-conformance-runner\build\libs\durable-streams-conformance-runner-client-adapter.jar"
if not exist "%JAR%" (
  echo Client adapter jar not found: %JAR% 1>&2
  echo Build it with: ..\gradlew :durable-streams-conformance-runner:clientAdapterJar 1>&2
  exit /b 1
)
if "%JAVA_CMD%"=="" set "JAVA_CMD=java"
"%JAVA_CMD%" -jar "%JAR%"
endlocal
