# Implementation Roadmap

Milestones follow dependency order. Each milestone is a runnable increment with tests and is a reviewable PR boundary. The status column is updated as milestones land.

| # | Milestone | Depends on | Status |
|---|---|---|---|
| M0 | Repository assessment, architecture and contracts | - | done |
| M1 | Build consistency, CI, developer setup, logging redaction fix | M0 | done |
| M2 | `fix-core` library and deterministic FIX simulator | M1 | planned |
| M3 | Deterministic certification engine (scenarios, runner, evidence, replay, report) | M2 | planned |
| M4 | Broker persistence and FIX session configuration | M1 | planned |
| M5 | Workflow service: approvals and hash-chained audit | M1 | planned |
| M6 | Python AI foundations: shared contracts, telemetry, evaluation harness | M3 | planned |
| M7 | Agent orchestrator (LangGraph) | M6 | planned |
| M8 | Specialised agents, one at a time (FIX, certification, log-analysis, knowledge, report, human-review) | M7 | planned |
| M9 | MCP servers with authorisation | M3–M5 | planned |
| M10 | Knowledge ingestion and RAG evaluation (pgvector) | M6 | planned |
| M11 | React UI | M3–M5 | planned |
| M12 | End-to-end integration, load tests, security hardening, OIDC | M4–M11 | planned |
| M13 | Kubernetes and Helm | M12 | planned |

## Module decisions

| Module | Decision | Reason |
|---|---|---|
| `backend/shared` | **Removed** | Empty. Shared FIX code lives in `fix-core`. Generic cross-service code is avoided on purpose (ADR-0005). |
| `backend/api-gateway` | **Removed until needed** | No routing, auth offload or rate limiting is needed yet. The UI dev server proxies to services (ADR-0006). |
| `backend/report-service` | **Removed; reports live in certification-service** | Reports are a pure projection of certification evidence. A separate service would only add a network hop and data duplication (ADR-0006). |
| `backend/workflow-service` | **Implemented in M5** | Approvals and audit have independent data ownership and consumers. |
| `backend/certification-service` | **Implemented in M3** | Core product capability. |
| `backend/fix-core`, `backend/fix-simulator` | **New in M2** | ADR-0005 |
| `notification-service`, `audit-service` | **Not created** | Notifications have no consumer yet. Audit is a bounded context inside workflow-service until volume or isolation justifies extraction. |

## Milestone details

### M1 – Build consistency and developer setup
- Spring Boot 3.5.0 → 3.5.16 and QuickFIX/J 2.3.0 → 2.3.3 (patch releases only: security and bug fixes, no API changes).
- JaCoCo coverage reports. GitHub Actions for Java (build, test, coverage artifact) and dependency review.
- Remove the empty modules listed above from the reactor.
- Fix credential and payload logging in fix-gateway and broker-service.
- Remove the unwired, defective QuickFIX/J adapter from broker-service, because FIX transport is owned by fix-gateway.
- `.editorconfig`, `.env.example`, `infra/docker/docker-compose.yml` (PostgreSQL).

### M2 – fix-core and simulator
- `FixVersion` (FIX.4.2, FIX.4.4, FIXT.1.1 with FIX.5.0SP2 application version), dictionary registry.
- `FixMessageRedactor`, `FixMessageInspector` (parse, dictionary validation, structured errors).
- `SessionSettingsBuilder` for initiator and acceptor with per-session settings.
- Simulator: dynamic acceptor (one session per client CompID), order lifecycle (`D`, `F`, `G`), business rejects, and defect profiles selected by the simulator CompID (`SIM`, `SIM-<PROFILE>`).

### M3 – Certification engine
- Versioned YAML scenario catalogue: positive, negative, boundary and recovery scenarios.
- Runner that drives QuickFIX/J against the simulator or an approved target, with bounded timeouts and cancellation.
- Pure assertion evaluator shared by the live run and offline replay.
- PostgreSQL and Flyway persistence; REST API; JSON and HTML report generated from evidence.
- Testcontainers integration tests and full end-to-end runs against the in-process simulator.

### M4–M13
See the brief in the repository root history. Each milestone appends a completion report to `docs/implementation/milestones/`.
