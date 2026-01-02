import { runConformanceTests } from "@durable-streams/server-conformance-tests";
import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll } from "vitest";

const currentDir = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(currentDir, "..");
const isWindows = process.platform === "win32";
const gradlew = isWindows ? resolve(rootDir, "gradlew.bat") : resolve(rootDir, "gradlew");
const baseUrl = process.env.STREAM_URL ?? "http://localhost:4437";

function startServer() {
  if (isWindows) {
    return spawn("cmd.exe", ["/c", gradlew, ":durable-streams-conformance-runner:runConformanceServer", "--no-daemon"], {
      stdio: "inherit",
      cwd: rootDir,
    });
  }
  return spawn(gradlew, [":durable-streams-conformance-runner:runConformanceServer", "--no-daemon"], {
    stdio: "inherit",
    cwd: rootDir,
  });
}

async function waitForServer(url) {
  for (let i = 0; i < 200; i += 1) {
    try {
      await fetch(url, { method: "HEAD" });
      return;
    } catch {
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
    }
  }
  throw new Error("Conformance server did not start in time");
}

async function stopServer(proc) {
  if (!proc || proc.exitCode !== null) {
    return;
  }
  if (isWindows) {
    await new Promise((resolvePromise) => {
      const killer = spawn("cmd.exe", ["/c", "taskkill", "/PID", String(proc.pid), "/T", "/F"], {
        stdio: "ignore",
      });
      killer.on("exit", () => resolvePromise());
    });
    return;
  }
  proc.kill("SIGTERM");
}

const serverProcess = startServer();

beforeAll(async () => {
  await waitForServer(baseUrl);
}, 80000);

afterAll(async () => {
  await stopServer(serverProcess);
});

runConformanceTests({ baseUrl });

