import { runConformanceTests } from "@durable-streams/client-conformance-tests";
import { dirname, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { test } from "vitest";

const currentDir = dirname(fileURLToPath(import.meta.url));

const isWindows = process.platform === "win32";
const defaultAdapter = isWindows
  ? resolve(currentDir, "client-conformance-adapter.cmd")
  : resolve(currentDir, "client-conformance-adapter.sh");

const adapterPath = process.env.CLIENT_ADAPTER ?? defaultAdapter;

let clientAdapter = adapterPath;
let clientArgs;

if (isWindows && /\.cmd$|\.bat$/i.test(adapterPath)) {
  clientAdapter = "cmd.exe";
  clientArgs = ["/c", adapterPath];
}

const portEnv = process.env.CLIENT_CONFORMANCE_PORT;
const serverPort = portEnv ? Number(portEnv) : 4438;

test("client conformance tests", { timeout: 120000 }, async () => {
  const result = await runConformanceTests({
    clientAdapter,
    clientArgs,
    serverPort,
    verbose: true,
  });

  if (result && result.failed > 0) {
    throw new Error(`${result.failed} conformance test(s) failed`);
  }
});
