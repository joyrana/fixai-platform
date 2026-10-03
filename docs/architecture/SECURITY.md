# Security Controls and Threat Model

This document lists the security controls a bank or broker's security review will look for. For each control it says where the control is enforced and how it is tested. "Tested" means an automated test or evaluation in this repository exercises the control.

## Assets and trust boundaries

| Asset | Where it lives | Notes |
|---|---|---|
| Certification verdicts and evidence | certification-service (PostgreSQL, schema `certification`) | Verdicts come only from executable assertions. Evidence is redacted, hashed per message and digested per run. |
| Broker session configurations | broker-service (`broker`) | TEST/UAT only. Credentials are stored as secret-store references, never as values. |
| Approvals and audit log | workflow-service (`workflow`) | Approvals are bound to payload hashes. The audit log is append-only and hash-chained. |
| Documentation corpus | knowledge MCP server | Has ACLs, a tenant label and quarantine. |
| Model prompts and outputs | agent services (in memory only) | Never persisted. Run metadata keeps hashes, counts and versions only. |

The trust boundaries are:
- the browser and the UI gateway;
- the platform services;
- the MCP servers (which hold the AI tool surface);
- the agents and orchestrator;
- the FIX counterparty, which is untrusted input;
- the documentation, which is untrusted input;
- the LLM provider.

## Controls

| # | Control | Enforced in | Tested by |
|---|---|---|---|
| 1 | **Verdicts come only from the engine.** Agents cannot write verdicts, and report narratives that contradict the verdict are rejected. | `Verdict.of`, report-agent validator, `ReportDraft` filled by code | `EvaluationTest`, report-agent eval `verdict_preserved` / `narrative_consistent` (including injected Text(58)), workflow eval `engine_verdict_preserved` |
| 2 | **Replay verification.** Every verdict can be re-derived offline from stored evidence, and the evidence digest is checked. | `ReplayVerifier` | engine tests, API test, e2e `test_simulator_smoke_run_passes_with_report_and_replay` |
| 3 | **AI identities can run only against the simulator.** The certification MCP tool has no target parameter, and the service refuses SESSION_CONFIG targets for AI_AGENT callers. | certification MCP server, `CertificationRunService.start` | API test (403), e2e `test_ai_identities_cannot_target_broker_sessions`, safety probes |
| 4 | **External runs need a human-approved plan.** A SESSION_CONFIG run requires an approval of exactly that run (environment, FIX version, sorted scenarios), consumed once. | `CertificationRunService.consumeExternalApproval`, workflow-service consume | `ExternalCertificationApprovalIntegrationTest`, e2e `test_external_certification_requires_a_human_approved_plan_end_to_end` |
| 5 | **Approvals bind to the payload hash.** An approval binds SHA-256 of the canonical payload, needs four-eyes review, expires, and is consumed once. Agents and services that file on behalf of a person keep four-eyes on that person. | workflow-service (`ApprovalPolicy`, DB CHECK constraint, compare-and-set transitions) | `WorkflowApiIntegrationTest` (9 tests, including concurrent decisions and agent-filed requests), e2e onboarding |
| 6 | **Session activation requires approval.** Activation consumes the approval, and any edit invalidates it. | broker-service | `BrokerServiceIntegrationTest` |
| 7 | **Role-based access on every endpoint.** Roles: ADMIN, BROKER_MANAGER, CERTIFICATION_ENGINEER, REVIEWER, AUDITOR, SERVICE, AI_AGENT. | platform-web (`@PreAuthorize`), Python `require_roles`, MCP `ToolPolicy.roles` | role tests in each service, orchestrator API tests, e2e |
| 8 | **Least privilege for agents.** Each agent has a client-side tool allow-list and budget. On the server, side-effecting tools have agent allow-lists and rate limits. | `ToolGateway`, `governed_tool` | safety probes (unlisted tool, unlisted agent, role, rate limit) |
| 9 | **No general-purpose tools.** There is no shell, SQL, filesystem, URL-fetch, approve or verdict-write tool. | tool inventory (`docs/architecture/contracts`, checked in CI) | `fixai_evals.contracts --check` |
| 10 | **Untrusted text handling.** FIX text, logs, documents and tool outputs are untrusted. They are scanned for injection patterns, fenced for the model, never followed, and recorded as findings. | `guard`, `fence_untrusted`, `ToolGateway._scan`, knowledge quarantine | guard unit tests, adversarial slices of every agent dataset (38 cases), `rag-quality` quarantine check, metric `fixai_agent_injection_findings_total` |
| 11 | **Model output validation.** All model output is validated against the evidence it was given. Fabricated evidence references, unsupported categories, uncited answers and contradictions all trigger the deterministic fallback, which is recorded. | per-agent validators, `AgentContext.generate` | fix-agent validator unit test, eval check `evidence_grounded` |
| 12 | **No credentials in data or logs.** Credential fields are redacted from evidence and logs. Session configurations accept only secret references. Raw password fields are refused. | `FixMessageRedactor`, gateway logging, broker validation, review-agent policy | redaction tests, `inspectsFixMessagesWithoutEchoingCredentials`, fix-gateway logging test |
| 13 | **No production by construction.** TEST/UAT only, enforced by enum and DB CHECK. Approval policy blocks PRODUCTION. | broker-service, workflow-service | broker and workflow tests |
| 14 | **Tamper-evident audit.** Every state change is audited through a transactional outbox into an append-only, hash-chained log, with verification. | platform-web outbox, workflow-service triggers and `verify` | `auditLogIsHashChained...`, `tamperingThatBypassesTriggersIsDetected...`, e2e `test_audit_chain_is_intact` |
| 15 | **Authentication.** OIDC resource server (issuer and audience validated) and client credentials between services. User tokens are never forwarded. | platform-web, `fixai_common.oidc` | `PlatformWebTest` (JWT role mapping); OIDC is not exercised end to end in compose (see limitations) |
| 16 | **Browser hardening.** Content-Security-Policy, nosniff, frame and referrer policies, and a same-origin API through the gateway. CORS is an explicit allow-list. | `frontend/nginx.conf.template`, platform-web | Playwright smoke run through the gateway |
| 17 | **Hardened runtime.** Non-root images, no secrets in images (the corporate CA is a build secret only), read-only root filesystem, all capabilities dropped. | Dockerfiles, Helm chart | image inspection, chart render checks in CI |
| 18 | **Network segmentation.** Default-deny policies; the AI tiers have no route to brokers. | Helm `networkpolicies.yaml` | rendered and linted only; not deployed to a cluster here |
| 19 | **Supply chain.** Locked dependencies (`uv.lock`, `package-lock.json`, pinned Maven versions), CycloneDX SBOM and dependency review in CI. | CI | CI configuration |

## Residual risks and planned work

- **Delegation is trusted, not proven.** The `onBehalfOf` identity on agent-filed approvals is set by the orchestrator from the authenticated user, not by the model. A compromised orchestrator could still name someone else. Planned fix: signed delegation tokens (OAuth token exchange, RFC 8693).
- **Dev identity mode exists.** It must never be enabled outside a developer machine. The compose file binds every port to loopback, and the Helm chart defaults to OIDC.
- **Rate limits are per replica.** Distributed rate limiting (Redis) is deferred per ADR-0006.
- **Checkpoints are local.** Workflow checkpoints use SQLite with a single orchestrator replica; a Postgres checkpointer is needed for high availability (ADR-0008).
- **Secrets at rest are external.** Encryption at rest and database credentials depend on the bank's PostgreSQL and secret-management platform.
- **Prompt-injection detection is pattern-based.** The structural defences carry the weight: fencing, validators, the tool allow-list, and the absence of privileged tools. Detection is defence in depth.
