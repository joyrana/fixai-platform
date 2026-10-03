# MCP Tools and Agent Contracts

The machine-readable contracts live in [`contracts/`](contracts/). They hold the full input and output JSON schemas plus governance metadata, and are regenerated with `python -m fixai_evals.contracts`. CI fails when the checked-in contracts are stale (`--check`), so every schema or policy change is visible in review.

## Governance applied to every tool

Every tool is wrapped by `governed_tool` with a `ToolPolicy`. Checks run in this order:

1. **Authentication.** The caller is an OIDC bearer token (`FIXAI_SECURITY_ENABLED=true`) or, in local development only, the dev identity headers.
2. **Role check** against the policy's roles.
3. **Agent allow-list** for tools that cause side effects (`allowedAgents`). The agent is identified by `X-Fixai-Agent`; for in-process calls the identity is bound to the calling agent.
4. **Rate limit** per caller and tool.
5. **Timeout.**
6. **Audit record.** Every call, allowed or denied, records tool, capability, subject, agent, outcome, duration and the SHA-256 of the arguments. Raw arguments are not logged.

Agents add a client-side allow-list and a tool-call budget (`ToolGateway`). Every tool output is scanned for prompt-injection patterns before an agent uses it.

## Capability classes

- **READ:** no state change.
- **SIMULATED_WRITE:** creates state that cannot affect a counterparty or production: a simulator run, an incident *draft*, or an approval *request*.

There are no tools for:

- arbitrary shell, SQL, filesystem or URL access;
- approving anything;
- starting runs against broker endpoints;
- changing a verdict.

## Tools

| Server (port) | Tool | Capability | Roles | Agent allow-list | Timeout s | Rate/min |
|---|---|---|---|---|---|---|
| certification (8201) | `list_test_scenarios` | READ | readers¹ | – | 15 | 60 |
| | `validate_test_plan` | READ | readers¹ | – | 15 | 60 |
| | `start_simulated_certification` | SIMULATED_WRITE | AI_AGENT, CERTIFICATION_ENGINEER, ADMIN | certification-agent, agent-orchestrator | 20 | 10 |
| | `get_certification_status` | READ | readers¹ | – | 15 | 240 |
| | `retrieve_certification_evidence` | READ | readers¹ | – | 30 | 60 |
| fix (8202) | `inspect_message` | READ | readers¹ + BROKER_MANAGER | – | 15 | 60 |
| | `validate_fix_configuration` | READ | readers¹ + BROKER_MANAGER | – | 15 | 60 |
| | `inspect_session` | READ | readers¹ + BROKER_MANAGER | – | 15 | 60 |
| | `retrieve_session_events` | READ | readers¹ + BROKER_MANAGER | – | 15 | 60 |
| | `replay_test_fixture` | READ | readers¹ + BROKER_MANAGER | – | 60 | 10 |
| knowledge (8203) | `search_fix_documentation` | READ | readers¹ + BROKER_MANAGER, REVIEWER | – | 15 | 120 |
| | `retrieve_document` | READ | readers¹ + BROKER_MANAGER, REVIEWER | – | 15 | 60 |
| | `inspect_citations` | READ | readers¹ + BROKER_MANAGER, REVIEWER | – | 15 | 60 |
| operations (8204) | `inspect_service_health` | READ | readers¹ | – | 15 | 60 |
| | `retrieve_sanitized_logs` | READ | readers¹ | – | 15 | 60 |
| | `create_incident_draft` | SIMULATED_WRITE | AI_AGENT, CERTIFICATION_ENGINEER, ADMIN | log-analysis-agent, agent-orchestrator | 15 | 10 |
| | `request_human_approval` | SIMULATED_WRITE | AI_AGENT, CERTIFICATION_ENGINEER, ADMIN | human-review-agent, agent-orchestrator | 15 | 5 |
| | `get_approval_status` | READ | readers¹ | – | 15 | 240 |

¹ readers = AI_AGENT, CERTIFICATION_ENGINEER, ADMIN, SERVICE, AUDITOR.

Side-effecting tools take an `idempotency_key`; repeating a call with the same key returns the original result.

`start_simulated_certification` has no target parameter. The certification service independently refuses non-simulator targets for AI identities.

`request_human_approval` files a request with workflow-service, which binds it to the SHA-256 of the canonical payload. The executing service must later present the identical payload to consume the approval, once, after a different person has approved it.

## Agents

| Agent (port) | Allowed tools | Budget | What the model may do | What code guarantees |
|---|---|---|---|---|
| fix-agent (8101) | retrieve_certification_evidence, search_fix_documentation, inspect_message, retrieve_session_events | 16 calls | Explain ranked root-cause hypotheses | Categories come from deterministic triage; every evidence reference must exist in the retrieved evidence; the verdict is copied from the engine |
| certification-agent (8102) | list_test_scenarios, validate_test_plan, start_simulated_certification | 6 | Add relevant catalogue scenarios and write the rationale | The baseline selection cannot be dropped; IDs must be in the catalogue; the plan is validated by the service; runs target the simulator only |
| knowledge-agent (8103) | search_fix_documentation, retrieve_document, inspect_citations | 4 | Compose the answer | Quotes must appear verbatim in retrieved passages and pass the server-side check; flagged passages are excluded; the agent abstains below the coverage threshold |
| log-analysis-agent (8104) | retrieve_sanitized_logs, create_incident_draft | 3 | Write the summary | Anomalies come from deterministic protocol rules with log references; narratives that claim a verdict are rejected; drafts are created only on request |
| report-agent (8105) | get_certification_status, retrieve_certification_evidence | 3 | Write the executive summary and next steps | Verdict, counts and digest are filled in by code; narratives that contradict the verdict or contain numbers not in the engine facts are rejected |
| human-review-agent (8106) | request_human_approval, get_approval_status | 2 | Write the review packet | Policy checks run before filing (requester, justification, evidence, no injection, no credentials); the agent never approves |
| agent-orchestrator (8100) | get_certification_status | – | – | See ADR-0008 |

When the model's output fails validation, or the provider refuses or is unavailable, each agent falls back to its deterministic synthesizer. The fallback is recorded in `AgentRunMetadata.llm_fallback_used`. Metadata also records provider, model, prompt version, tokens, estimated cost, latency, the tool-call trace (argument hashes only) and a short decision summary. Chain-of-thought is never recorded.
