# M11–M13 Completion Report: UI, Integration, Observability and Deployment

## What was built

### M11 – UI (`frontend/`)
- **Stack:** React 19, Vite 8 and strict TypeScript, using the router and a small typed API client. There is no UI kit, and both light and dark themes are supported.
- **Screens:**
  - Overview.
  - Certification runs: start a run; view the engine verdict, scenarios, step results and assertion failures; browse redacted evidence with a field table; replay verification; AI diagnosis shown as labelled hypotheses with evidence references.
  - AI workflows: start a workflow, view its plan, report draft and event timeline, and the approval gate with re-check and cancel actions.
  - Brokers and sessions: onboarding, session configuration, validate, submit, activate and retire.
  - Approvals: queue, exact payload, hash, history, and decisions with rationale. A four-eyes warning appears when the reviewer is the requester.
  - Knowledge: answers with verified quotes, or an explicit statement that the documents don't answer.
  - Audit log: chain verification.
- **Roles in the UI:** actions are hidden for roles that can't perform them, but every service enforces roles server-side. A dev identity switcher sends `X-Dev-User`/`X-Dev-Roles` and exists only in dev auth mode.
- **Accessibility:** select fields have explicitly associated labels. The browser test found that wrapping labels folded every option into the accessible name.

### M12 – Integration, security hardening and observability
- **Container images:**
  - Spring Boot runtime image (non-root).
  - One locked Python image for all MCP servers, agents and the orchestrator (non-root, venv built at its final path).
  - UI gateway on unprivileged nginx with CSP and security headers.
  - An optional `corp_ca` build secret for TLS-intercepting corporate proxies; the CA never enters the image.
- **Compose:** a `platform` profile with 11 services behind health checks and loopback-only ports. An `observability` profile adds Prometheus (with 6 alert rules) and Grafana with a provisioned dashboard.
- **Metrics:**
  - Agents: invocations, latency, LLM fallbacks, tokens, cost and injection findings.
  - MCP: tool calls by outcome, and latency.
  - Workflow outcomes.
  - Spring timers now publish histograms, and HTTP server histograms are on. fix-simulator now exposes Prometheus metrics.
- **Benchmark CLI** (`fixai_evals.cli benchmark`): certification throughput and end-to-end AI workflow latency.

### M13 – Kubernetes (`infra/helm/fixai-platform`)
- **Workloads:** Deployments, Services and PDBs for every component, a PVC for orchestrator checkpoints, and an optional Ingress and ServiceMonitor.
- **Security:** OIDC is on by default and the chart fails to render without an issuer. Secrets are referenced by name. Pods are hardened.
- **NetworkPolicies:** default deny. The UI talks to platform services and the orchestrator. The orchestrator reaches the MCP servers, which reach platform services. Platform services reach the database, the simulator and opt-in broker CIDRs. The AI tiers have no route to brokers.

## Security defects found and fixed during this milestone
Integration testing surfaced four real defects, and each now has a regression test:
1. **broker-service write endpoints had no role check.** Any authenticated identity, including AUDITOR and AI_AGENT, could create or delete brokers. Writes now require ADMIN or BROKER_MANAGER.
2. **External runs did not require a run-level approval.** A run against a broker session only checked that the session configuration was approved. certification-service now consumes a START_EXTERNAL_CERTIFICATION approval bound to the exact plan, once.
3. **Four-eyes could be bypassed through an agent.** Approvals filed by an agent on behalf of an engineer recorded the agent as requester, so the engineer could approve their own request. Fixed with `onBehalfOf` for agents plus a recorded `filedBy` (V2 migration).
4. **The browser UI could not start runs (CORS).** Same-origin browser POSTs carry an `Origin` header that the services rejected (403) because only the Vite dev origin was allow-listed. API-level tests could not catch this; the Playwright test did. The UI origin is now allow-listed explicitly.

## Executed results (2026-10-03, this environment)

| Check | Result |
|---|---|
| `mvn -B verify` (all Java modules) | BUILD SUCCESS: 119 tests, 0 failures. New: external-approval integration test, agent-filed four-eyes test, broker role checks |
| Python `pytest` (unit and integration; E2E skipped without a stack) | 81 passed, 6 skipped |
| `ruff check ai mcp evals e2e` | clean |
| Contract snapshot check | 10 contracts up to date |
| Frontend: `tsc -b`, `vitest`, `vite build` | clean, 4/4 tests, 307 kB JS (94 kB gzip) |
| Compose E2E (`e2e/`, real stack through the UI gateway) | 6/6 passed (≈27 s) |
| Playwright browser smoke (real stack) | 3/3 passed |
| Evaluation suites (offline provider) | workflow 14/14, agent-smoke all 1.0 (see the M6–M10 report for full results) |
| `helm lint` / `helm template` | lint clean; renders 31 resources; refuses to render with security on and no issuer |
| Prometheus | 9/9 scrape targets up (4 Spring, 4 MCP, 1 orchestrator); 6 alert rules loaded |

Benchmarks are recorded in `evals/benchmarks/` and run against the simulator on one 4-CPU host:

| Workload | p50 time to verdict | Throughput |
|---|---|---|
| Full 24-scenario certification, 8 runs at concurrency 4 | 9.5 s | 585 scenarios/min |
| Smoke suite, 12 runs at concurrency 4 | 3.2 s | 290 scenarios/min |
| End-to-end AI workflow (plan, run, report), 6 at concurrency 3 | 3.8 s | 46 workflows/min |

## Not verified here (limitations)
- **CI has not run on GitHub.** The GitHub Actions jobs (`frontend`, `helm`, `e2e`) are defined, but their equivalents were only run locally in this container.
- **The chart has not been deployed.** It was linted and rendered only; it was not installed into a Kubernetes cluster, and the NetworkPolicies have not been exercised by a CNI. kubeconform was unavailable (egress blocked).
- **OIDC is not exercised end to end.** The compose stack runs in dev identity mode. OIDC is covered by unit tests (JWT role mapping) only; an identity-provider-backed E2E (e.g. Keycloak in compose) is the next step.
- **Benchmark scope.** The benchmarks measure platform overhead against a co-located simulator, not a broker's network or pacing.
- **One approval agent check is missing.** The human-review agent does not yet verify that the referenced session configuration exists before filing. certification-service would still refuse the run later. Follow-up: call `inspect_session` before filing.
- **Locally, images were built with the environment's CA as a build secret** (TLS interception). A plain `docker compose --build` works where there is no interception.

## Next steps
1. A Keycloak-backed compose profile with OIDC E2E, and signed delegation for agent-filed approvals (token exchange).
2. A Postgres checkpointer and a distributed cancel flag for orchestrator high availability; Redis-backed MCP rate limits (ADR-0006 trigger).
3. An Anthropic-provider evaluation baseline with cost and latency, compared against the offline baseline with `cli compare`.
4. pgvector with a semantic embedding model once the corpus grows (ADR-0007 triggers).
5. Deploy the chart to a staging cluster with a policy-enforcing CNI and run the E2E suite there.
