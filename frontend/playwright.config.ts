import { defineConfig, devices } from "@playwright/test";

// Browser smoke test against a running stack (docker compose --profile platform): FIXAI_UI_URL=http://localhost:3000
export default defineConfig({
  testDir: "e2e",
  timeout: 120_000,
  expect: { timeout: 60_000 },
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.FIXAI_UI_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
