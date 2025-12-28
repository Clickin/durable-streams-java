import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["server-conformance.test.mjs", "client-conformance.test.mjs"],
  },
});
