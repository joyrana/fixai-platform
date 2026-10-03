# M3 Completion Report – Deterministic Certification Engine

## Delivered
- **Scenario catalogue:** 24 versioned scenarios covering SESSION, POSITIVE, NEGATIVE, BOUNDARY and RECOVERY on FIX 4.2, FIX 4.4 and FIX 5.0SP2 (FIXT.1.1), plus 4 suites.
  - Session layer: Logon, TestRequest/Heartbeat, unsolicited heartbeats, gap detection, ResendRequest handling, Logout, invalid-CompID logon refusal, and reconnect with sequence continuity.
  - Order lifecycle: ack, fill, partial fills, cancel, replace, status, rejects, duplicates, unsupported messages, missing required fields, quantity and price boundaries.
- **Strict YAML DSL:** steps are logon, logonRejected, send, expect (inbound or outbound), expectNone, skipOutboundSequence, logout, disconnect, reconnect and pause. Checks are equals (optionally version-specific), notEquals, present, oneOf, matches and numeric comparisons. Arithmetic invariants are supported, and captures feed variables. JSON Schema: `evals/schemas/scenario.schema.json`.
- **Linter:** dictionary validation of message types and field names per version, invariant parsing, and capture-before-use checks for variables.
- **Engine:** `ScenarioExecutor` with bounded waits, scenario deadline and cooperative cancellation. Evaluation is pure in `ExpectationEvaluator` and `Invariant`. `ProtocolChecks` cover platform-side rejects, counterparty session rejects, ExecID uniqueness and the evidence cap.
- **Evidence:** captured at the QuickFIX/J log layer and redacted, with a SHA-256 per message and a run-level evidence digest.
- **Replay:** `ReplayVerifier` re-derives every expectation result and protocol check from stored evidence.
- **Persistence:** PostgreSQL schema `certification` (Flyway V1) holding runs, scenario executions, step results and evidence. Writes use explicit JDBC with batched evidence inserts.
- **REST API v1:**
  - Scenario catalogue: scenarios, suites, test-plan validation.
  - Runs: start (async 202, Idempotency-Key), list, get, cancel, scenarios, steps, evidence, replay verification, and JSON or HTML report.
  - Cross-cutting: correlation IDs, RFC 7807 errors, OpenAPI.
- **Safety:** simulator by default; external targets refused unless configured, approved and in TEST/UAT.
- **Operations:** Micrometer timers (scenario/run p50/p95/p99), Prometheus endpoint, health probes, recovery of runs interrupted by a restart, bounded run queue.

## Tests executed (`mvn -B clean verify`, all modules)
92 tests, 0 failures, 0 errors, 0 skipped. Wall time 2m18s on a 4-vCPU container.
- `CertificationEngineIntegrationTest` (7):
  - All 24 scenarios pass against the compliant simulator on FIX 4.2, 4.4 and 5.0SP2.
  - Every one of the 13 defect profiles is detected by its target scenarios, while unrelated scenarios still pass.
  - Replay reproduces the stored results, including for a failing run.
  - Evidence is redacted and hashed; cancellation is honoured.
- `CertificationApiIntegrationTest` (4, PostgreSQL via Testcontainers): catalogue and plan validation; a compliant smoke run passes with report, replay, digest and evidence; a defective counterparty fails with an evidence-referenced assertion (`ExecType expected F, actual 0`); invalid, unsafe and conflicting requests are refused with problem details.
- Unit tests: evaluator, invariants, variables, verdict rules, evidence cap, strict parser, linter, HTML escaping.

## Measured performance (single run, simulator, same container)
- The full 24-scenario suite completes in about 7–8 s per FIX version with 8-way scenario parallelism, measured by `everyScenarioPassesAgainstCompliantCounterparty`.
- This is an engineering measurement from the test suite, not a benchmark. A reproducible benchmark harness is planned with the evaluation milestone.

## Known limitations
- `SESSION_CONFIG` targets need broker-service integration (M4). Until then they are refused.
- Audit events go to a structured log until the workflow service (M5) is available.
- Authentication is not yet enforced. The acting identity is recorded as `local-developer` (M12 adds OIDC).
- A run interrupted by a restart is marked ERROR, not resumed; mid-session FIX state cannot be recovered safely.
