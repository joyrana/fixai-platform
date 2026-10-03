/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Same-origin API paths, proxied to each service (nginx does the same in the container image).
const target = (envName: string, fallback: string) => process.env[envName] ?? fallback;

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api/broker": { target: target("BROKER_URL", "http://localhost:8081"), rewrite: (p) => p.replace(/^\/api\/broker/, "") },
      "/api/cert": { target: target("CERTIFICATION_URL", "http://localhost:8083"), rewrite: (p) => p.replace(/^\/api\/cert/, "") },
      "/api/workflow": { target: target("WORKFLOW_URL", "http://localhost:8084"), rewrite: (p) => p.replace(/^\/api\/workflow/, "") },
      "/api/sim": { target: target("SIMULATOR_URL", "http://localhost:8085"), rewrite: (p) => p.replace(/^\/api\/sim/, "") },
      "/api/ai": { target: target("ORCHESTRATOR_URL", "http://localhost:8100"), rewrite: (p) => p.replace(/^\/api\/ai/, "") },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
