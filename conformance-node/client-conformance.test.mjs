import { runConformanceTests } from "@durable-streams/client-conformance-tests";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { test } from "vitest";

const currentDir = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(currentDir, "..");

const isWindows = process.platform === "win32";
const gradlew = isWindows ? resolve(rootDir, "gradlew.bat") : resolve(rootDir, "gradlew");

process.chdir(rootDir);

const portEnv = process.env.CLIENT_CONFORMANCE_PORT;
const serverPort = portEnv ? Number(portEnv) : 4438;

function gradleAdapterCommand() {
  if (isWindows) {
    return { clientAdapter: "cmd.exe", clientArgs: ["/c", gradlew, ":durable-streams-conformance-runner:runClientAdapter"] };
  }
  return { clientAdapter: gradlew, clientArgs: [":durable-streams-conformance-runner:runClientAdapter"] };
}

function jarAdapterCommand() {
  const jarPath = resolve(rootDir, "durable-streams-conformance-runner", "build", "libs", "client-adapter.jar");
  if (isWindows) {
    return { clientAdapter: "cmd.exe", clientArgs: ["/c", "java", "-jar", jarPath] };
  }
  return { clientAdapter: "java", clientArgs: ["-jar", jarPath] };
}

function buildClientAdapterJar() {
  const command = gradleAdapterCommand();
  const buildArgs = isWindows
    ? ["/c", gradlew, ":durable-streams-conformance-runner:clientAdapterJar"]
    : [":durable-streams-conformance-runner:clientAdapterJar"];
  const buildCommand = isWindows ? "cmd.exe" : gradlew;
  const result = spawnSync(buildCommand, buildArgs, { stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error("Failed to build client adapter JAR");
  }
}

test("client conformance tests", { timeout: 180000 }, async () => {
  const primary = gradleAdapterCommand();
  try {
    const result = await runConformanceTests({
      clientAdapter: primary.clientAdapter,
      clientArgs: primary.clientArgs,
      serverPort,
      verbose: true,
    });

    if (result && result.failed > 0) {
      throw new Error(`${result.failed} conformance test(s) failed`);
    }
  } catch (error) {
    buildClientAdapterJar();
    const fallback = jarAdapterCommand();
    const result = await runConformanceTests({
      clientAdapter: fallback.clientAdapter,
      clientArgs: fallback.clientArgs,
      serverPort,
      verbose: true,
    });

    if (result && result.failed > 0) {
      throw new Error(`${result.failed} conformance test(s) failed`);
    }
  }
});
