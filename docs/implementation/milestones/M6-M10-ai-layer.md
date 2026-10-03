# M6–M10 Completion Report: AI Layer

This report covers roadmap milestones M6 (AI foundations and evaluation harness), M7 (orchestrator), M8 (specialised agents), M9 (MCP servers) and M10 (knowledge retrieval and RAG evaluation). They landed together because each one's tests depend on the others.

## What was built

### Shared runtime (`ai/common`, package `fixai_common`)
- **Contracts:** versioned agent contracts (`Fact` is kept separate from `Hypothesis`, and evidence references are mandatory) and a stable failure taxonomy.
- **Tool governance:** governed MCP tools covering roles, agent allow-lists, rate limits, timeouts, auditing and idempotency (see [MCP_TOOLS.md](../../architecture/MCP_TOOLS.md)).
- **Agent tool access:** a client-side tool gateway that applies the allow-list and a budget, keeps a trace, and scans tool output for injection.
- **Untrusted text:** a prompt-injection guard that scans, sanitises and fences text, and understands negated guidance.
- **LLM providers:**
  - Offline (deterministic, the default).
  - Anthropic: structured outputs, refusal and max-tokens handling, a cached system prompt and cost tracking.
- **Validation:** every model output is validated, with a recorded fallback to deterministic output.
- **Identity:** OIDC or dev identity, correlation IDs and OpenTelemetry spans.

### MCP servers (`mcp/*`)
Four servers expose 18 tools: certification, fix, knowledge and operations. Their contracts are checked in under `docs/architecture/contracts/` and verified in CI.

### Agents (`ai/*`), each a FastAPI service exposing `/v1/invoke` and `/v1/contract`
- **fix-agent:** deterministic triage plus explained hypotheses grounded in evidence.
- **certification-agent:** maps an objective to a validated test plan from the catalogue, and starts runs on the simulator only.
- **knowledge-agent:** cited answers with local and server-side citation verification, abstention, and removal of injected sentences from the question.
- **log-analysis-agent:** 14 protocol anomaly rules over sanitized logs, and incident drafts when asked.
- **report-agent:** narrative that preserves the engine verdict, with a contradiction check and a check that every number appears in the engine facts.
- **human-review-agent:** policy checks, then a review packet, then the approval request. It has no ability to approve.

### Orchestrator (`ai/agent-orchestrator`)
- A LangGraph workflow: plan → start → bounded poll → diagnose and analyse logs → report → approval interrupt → resume and verify.
- The resume step verifies both the approval status and the payload hash.
- Workflows can be cancelled. Checkpoints are kept in memory or in SQLite.
- HTTP API with role checks (ADR-0008).

### Retrieval
- An in-process hybrid index (BM25 plus hashed vectors, fused by reciprocal rank).
- ACL, tenant filtering, quarantine and provenance (ADR-0007).
- A corpus of 12 synthetic documents.

### Evaluation harness (`evals/`)
- **Suites:**

  | Level | Suites |
  |---|---|
  | A | `rag-quality`, governance probes in `safety` |
  | B | one suite per agent |
  | C | adversarial cases in every dataset, aggregated by `safety` |
  | D/E | `workflow` |

  Plus `agent-smoke` and `agent-regression`.
- **Offline platform simulation:** recorded evidence from real simulator runs, behind tools that use the production tool policies.
- **Run records:** each run records the commit, dataset checksum, provider, model, prompt and tool-schema versions, retrieval configuration, environment and Wilson 95% intervals.
- **Regression comparison:** `cli compare` flags cases that flip between pass and fail.

## Datasets

| Dataset | Cases | dev / test | Adversarial | Label source |
|---|---|---|---|---|
| fix_agent/v1 | 52 | 36 / 16 | 7 | injected simulator defect |
| certification_agent/v1 | 36 | 24 / 12 | 4 | scenario catalogue tags |
| knowledge_agent/v1 | 36 | 26 / 10 | 4 | corpus headings |
| log_analysis_agent/v1 | 49 | 36 / 13 | 7 | protocol signature of the injected defect |
| report_agent/v1 | 52 | 38 / 14 | 7 | engine verdict and counts |
| human_review_agent/v1 | 36 | 23 / 13 | 9 | approval policy |

Splits are a stable 70/30 hash split on the case ID. Labels come from independent sources (`evals/fixai_evals/datasets_build.py`, `fixtures.py`), never from agent output.

## Results (executed 2026-10-03, offline provider, commit 082da2c plus the working tree of this change)

| Suite | Split | n | Task success (95% CI) | Latency p50 / p95 ms |
|---|---|---|---|---|
| fix-agent | test | 16 | 1.00 (0.81–1.00) | 27 / 121 |
| certification-agent | test | 12 | 1.00 (0.76–1.00) | 15 / 89 |
| knowledge-agent | test | 10 | 1.00 (0.72–1.00) | 16 / 18 |
| log-analysis-agent | test | 13 | 1.00 (0.77–1.00) | 7 / 12 |
| report-agent | test | 14 | 1.00 (0.78–1.00) | 27 / 43 |
| human-review-agent | test | 13 | 1.00 (0.77–1.00) | 0 / 8 |
| all agents | dev | 183 | 1.00 per agent | – |
| safety (7 probes + 38 adversarial cases, all splits) | all | 45 | 1.00 (0.92–1.00) | 14 / 33 |
| workflow (14 end-to-end scenarios) | – | 14 | 1.00 (0.78–1.00) | 106 / 306 |
| rag-quality (hybrid recall@5) | all | 29 | 1.00 (0.88–1.00) | 5 / 6 |

- **Retrieval, all splits:**

  | Mode | recall@1 | MRR |
  |---|---|---|
  | hybrid | 0.83 | 0.90 |
  | lexical | 0.76 | 0.85 |
  | vector | 0.69 | 0.81 |

  ACL leaks: 0 of 36 queries. Quarantine enforced.
- **Other measures:** LLM fallback rate 0; cost $0 (offline). Tool calls per success: fix 2.0, certification 1.9, knowledge 1.6, log 1.2, report 1.9, review 0.5.
- **Other tests:** Python unit and integration tests: 79 passed. Ruff: clean. Contract check: 10 contracts up to date. The certification API test for the new `POST /api/v1/fix/inspect` endpoint ran: 6/6 passed.

## How to read these numbers (limitations)

- **They do not measure an LLM.** These are offline-provider results, so they measure the deterministic triage, planning, retrieval, anomaly rules, synthesizers and every guardrail end to end. Running with `FIXAI_LLM_PROVIDER=anthropic` exercises Claude behind the same validators; no Anthropic run has been executed or reported here.
- **The intervals are wide.** Test splits hold 10–16 cases per agent; a 100% score on 10 cases is consistent with a true rate as low as 72%.
- **Labels and agents share an author.** Datasets were written alongside the agents. Labels come from independent sources (catalogue tags, defect profiles, corpus headings, policy), but blind spots shared with the rules are possible. Growing the datasets with real anonymised failures is the most valuable next step.
- **Tuning disclosure:**
  - The knowledge-agent's relevance gate was changed (stemming, title coverage, dropping injected sentences) after its **dev** split scored 0.58; the test split was not inspected until the final run.
  - The earlier fix-agent change (correlating late responses by ClOrdID) was informed by misses across all splits, including test, before the split discipline was in place. Its fix-agent test result is therefore not a clean held-out estimate.
- **Evals run on simulated services.** The workflow and agent suites use the simulated platform (recorded evidence, a simulated approval store). Server-side approval enforcement (four-eyes, expiry, single-use consumption, hash comparison) is tested in workflow-service (M5), not here. A compose-based end-to-end test across real services is planned for M12.

## Security properties exercised by tests or evals
- **Out of reach of agents and tools:**
  - AI cannot approve: no tool exists for it, and evals check that approvals stay PENDING until a simulated human decides.
  - AI cannot target a broker: there is no target parameter, the server refuses, and workflows end in a human handoff.
  - A verdict cannot change: report and diagnosis evals assert the engine verdict is preserved, including under injected Text(58).
- **Tool access control:** denials on the agent allow-list, roles and rate limits are probed against the real policies.
- **Retrieval access control:** restricted and quarantined documents are never retrieved.
- **No credentials:** the review policy refuses credential arguments, and session configurations accept only secret-store references (M4).

## Not done yet (next milestones)
- **M11 / M12 – UI and full stack:**
  - React UI.
  - Dockerfiles and the full compose stack, with a compose-based E2E test across services and a Playwright smoke test.
  - OpenTelemetry collector, Prometheus and Grafana.
  - Load tests.
- **Retrieval:** pgvector and a semantic embedding model (ADR-0007 triggers).
- **Orchestration at scale:** Postgres checkpointer and a distributed cancel flag for multi-replica orchestration (ADR-0008).
- **LLM baseline:** an Anthropic-provider evaluation run with cost and latency reporting, compared against the offline baseline using `cli compare`.
