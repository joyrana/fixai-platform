# Contract Boundaries

This document lists every inter-component contract. A contract change that is not backwards compatible requires a new major version: a new URL prefix or a new `schemaVersion`.

## HTTP APIs

| Provider | Base path | Consumers | Spec |
|---|---|---|---|
| broker-service | `/api/v1/brokers`, `/api/v1/session-configs` | UI, certification-service, MCP | `/v3/api-docs` |
| certification-service | `/api/v1/scenarios`, `/api/v1/certification-runs` | UI, MCP | `/v3/api-docs` |
| workflow-service | `/api/v1/approvals`, `/api/v1/audit-events` | broker-service, certification-service, UI, MCP | `/v3/api-docs` |
| fix-gateway | `/api/v1/fix-sessions` | UI, MCP | `/v3/api-docs` |
| fix-simulator | `/api/v1/simulator/profiles` | certification-service tests, MCP | `/v3/api-docs` |
| agent-orchestrator | `/v1/workflows` | UI | FastAPI `/openapi.json` |

### Required headers

| Header | Direction | Rule |
|---|---|---|
| `X-Correlation-Id` | request/response | Optional on request (generated if absent); always echoed |
| `Idempotency-Key` | request | Accepted on `POST` that creates state; 24 h retention |
| `Authorization: Bearer` | request | Required when `fixai.security.enabled=true` (OIDC JWT, audience checked) |

## Persisted evidence (certification-service)

`FixEvidence` (`schemaVersion: 1`):

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `runId`, `scenarioExecutionId` | UUID | |
| `ordinal` | long | Strictly increasing per scenario execution; the replay anchor |
| `direction` | `INBOUND` / `OUTBOUND` | Relative to the platform |
| `msgType`, `msgSeqNum` | string, int | |
| `occurredAt` | Instant | |
| `rawRedacted` | string | `|` delimited, sensitive tags masked |
| `fields` | list of `{tag, name, value}` | Redacted |
| `sha256` | hex | Of the original unredacted bytes |

## Scenario definitions

YAML in `certification-service/src/main/resources/scenarios/`. The schema is in `evals/schemas/scenario.schema.json`. Each scenario has an immutable `id` and `version`. Changing the steps requires bumping `version`, and runs reference `id@version`.

## Approval binding (workflow-service)

`payloadHash = SHA-256(canonical JSON of {action, targetType, targetId, environment, arguments})`, where canonical JSON has sorted keys and no insignificant whitespace. The executor recomputes the hash at execution time and must present it to `POST /api/v1/approvals/{id}/consume`. A mismatch, expiry, rejection or second consumption is refused.

## Audit events

`AuditEvent` (`schemaVersion: 1`): `id, sequence, occurredAt, actor, actorType (USER|SERVICE|AGENT), action, resourceType, resourceId, correlationId, outcome, details, previousHash, hash`. The `hash` covers all other fields plus `previousHash`, which forms a tamper-evident chain that `GET /api/v1/audit-events/verify` validates.

## MCP tools

Each tool declares an input/output JSON schema, a capability class (`READ`, `SIMULATED_WRITE`, `PRIVILEGED_WRITE`), required scope, timeout and idempotency behaviour. See `docs/architecture/MCP_TOOLS.md` (added with the MCP milestone).
