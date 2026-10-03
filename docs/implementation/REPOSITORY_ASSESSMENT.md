# Repository Assessment

- **Assessed commit:** `7f364cf` (merge of PR #3, `feature/fix-gateway-core`)
- **Branch for this work:** `claude/fixai-platform-ue7mkd`
- **Date:** 2026-10-03

## 1. Toolchain observed

| Tool | Version | Notes |
|---|---|---|
| JDK | OpenJDK 21.0.11 | matches ADR-0004 |
| Maven | 3.9.11 | no Maven wrapper in the repository |
| Spring Boot | 3.5.0 (parent POM) | patch releases up to 3.5.16 available |
| QuickFIX/J | 2.3.0 | patch releases up to 2.3.3 available; 3.x exists but is a major upgrade |
| springdoc-openapi | 2.8.8 | |
| Python / Node / Docker | 3.11 / 22 / 29 | present in the dev container, unused by the repository |

## 2. Repository tree at assessment time

```
pom.xml                         root aggregator -> backend/parent-pom
backend/parent-pom/pom.xml      Spring Boot parent, dependency management, module list
backend/shared/                 pom.xml only (no sources)
backend/api-gateway/            pom.xml only
backend/certification-service/  pom.xml only
backend/workflow-service/       pom.xml only
backend/report-service/         pom.xml only
backend/broker-service/         REST CRUD for brokers, in-memory storage, unwired QuickFIX/J adapter
backend/fix-gateway/            QuickFIX/J single-initiator gateway, actuator health, tests
docs/adr/ADR-0001..0004         monorepo, hexagonal architecture, QuickFIX/J, Spring Boot 3 + Java 21
.run/                           IntelliJ run configurations for broker-service and fix-gateway
```

There is no `.github/` directory (no CI), no Docker/Compose files, no database, no frontend and no Python code.

## 3. Baseline build

Command: `mvn -B verify` from the repository root.

| Module | Tests | Result |
|---|---|---|
| broker-service | 16 (controller, service, FIX adapter) | pass |
| fix-gateway | 17 (config, lifecycle, session manager, adapter, health, lookup, logging) | pass |
| all others | none | pass (empty modules) |

**Result: BUILD SUCCESS, 33 tests, 0 failures.** The one stack trace in the output (`IllegalStateException: boom`) is intentionally thrown by a test.

## 4. Module-by-module findings

### fix-gateway (implemented, partial)

Strengths:
- Clean hexagonal layout (`adapter/in`, `adapter/out`, `application/port`, `application/service`).
- Typed `@ConfigurationProperties` with validation; QuickFIX/J settings built in code.
- `SmartLifecycle` start/stop and an Actuator health indicator.

Gaps and defects:
1. **Credential leakage (security, high).** `LoggingFixGatewayEventService` logs complete FIX messages, including outbound and inbound `Logon(A)`. Logon can carry `Username(553)`, `Password(554)`, `NewPassword(925)` and `RawData(96)`. Inbound rejects, application messages and processing errors are also logged in full.
2. Only **one hard-wired initiator session**. There is no per-session configuration, no acceptor support and no runtime add/update of approved sessions.
3. Only **FIX 4.4** message classes are on the classpath. There is no FIX 4.2 or FIXT.1.1 support.
4. `ScreenLogFactory` is used although a `log-path` is configured. Message logs are discarded rather than persisted as evidence.
5. There are no session event history, sequence diagnostics or REST/API surface beyond `/actuator/health`.
6. The health indicator reports `DOWN` whenever the counterparty is not logged on. If that indicator is used for liveness, an orchestrator would restart a healthy process that is only waiting for its counterparty.
7. The application ports leak QuickFIX/J types (`quickfix.Message`, `SessionID`) into the application layer, which conflicts with ADR-0002/0003.

### broker-service (implemented, partial)

Strengths:
- CRUD REST API with validation, RFC 7807 problem details and OpenAPI.

Gaps and defects:
1. **In-memory persistence only.** Data is lost on restart, there is no database or migrations, and duplicate checking is subject to a race (check-then-act on a `ConcurrentHashMap`).
2. **No FIX session configuration model.** `endpoint` is a free-text string, and there are no CompIDs, FIX version, dictionary, heartbeat or environment fields.
3. **Duplicate FIX responsibility.** `adapter/in/fix/QuickFixApplicationAdapter` duplicates the gateway's role (ADR boundary violation), is not wired to any engine, and throws `IllegalArgumentException` for `Logon`, `Logout` and `Reject` admin messages. That would break a real session if it were ever wired.
4. The same full-message logging problem as fix-gateway (`LoggingFixSessionEventAdapter`).
5. The main class sits in the root package `com.fixai.platform`. Its component scan would pick up any other module's beans that share the root package if the modules were ever co-located.
6. `lombok` is declared but unused.
7. There are no authentication, authorization, audit, idempotency keys or correlation IDs.

### Empty modules

`shared`, `api-gateway`, `certification-service`, `workflow-service` and `report-service` contain only a POM. They compile to empty jars and add build time without value. Per the implementation brief, placeholder services must not be kept just to fill the target layout. Each one is either implemented with a documented responsibility or removed from the reactor; see the roadmap.

### Cross-cutting

| Area | State |
|---|---|
| CI | none |
| Coverage / static analysis | none |
| Persistence / migrations | none |
| Security (authN/Z, CORS, secrets) | none |
| Observability | Actuator health only; no metrics export, no tracing |
| Local infrastructure | none |
| AI / MCP / RAG / evals | none |
| Frontend | none |

## 5. Contract boundaries identified

| Boundary | Today | Target |
|---|---|---|
| Broker profile and FIX session config | broker-service REST (CRUD only) | broker-service REST v1, Postgres schema `broker` |
| FIX transport and session state | fix-gateway in-process only | `fix-core` library + fix-gateway runtime API |
| Certification scenarios, runs, evidence | missing | certification-service REST v1, schema `certification` |
| Approvals and audit | missing | workflow-service REST v1, schema `workflow` |
| AI reasoning | missing | Python services behind the orchestrator, consuming the Java APIs through MCP tools only |

See `docs/architecture/ARCHITECTURE.md` for the dependency map and `docs/architecture/CONTRACTS.md` for contract rules.

## 6. Risks carried forward

- **No Maven wrapper.** CI pins the Maven version through `setup-java` and the runner image.
- **QuickFIX/J uses a static session registry (`Session.lookupSession`).** Multiple engines in one JVM must use unique SessionIDs. Certification runs therefore allocate unique CompIDs per run.
- **Docker in the dev container required manual daemon start.** Testcontainers-based tests are annotated to skip when Docker is unavailable, and they run in CI.
