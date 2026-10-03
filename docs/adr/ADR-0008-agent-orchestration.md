# ADR-0008: Agent orchestration with LangGraph and a human approval gate

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

A certification workflow spans several agents:

1. plan and start a simulator run;
2. poll the run until the engine finishes;
3. diagnose failures and analyse logs;
4. draft the report;
5. for an external (broker) certification, obtain human approval.

The workflow must be durable across restarts while waiting for a human, bounded in time and steps, and cancellable. It must also be impossible for the workflow to approve anything or to start a run against a broker endpoint.

## Decision

- **State machine:** the workflow is a LangGraph `StateGraph`. Every step is checkpointed. The default checkpointer is in memory; `FIXAI_CHECKPOINT_PATH` selects SQLite so that waiting workflows survive restarts. Postgres can be added with the same interface.
- **Bounded polling:** each poll is one checkpointed step with an explicit budget (`max_polls`), and the recursion limit is derived from that budget.
- **Human gate:** the human-review agent files the approval request through MCP, and the graph then calls `interrupt()`. Resuming only re-checks the approval status: approval happens in workflow-service, by a different person (four-eyes), against the exact payload hash. The graph proceeds only if the status is APPROVED **and** the payload hash matches the one it filed.
- **Handoff, not execution:** an approved external certification ends as `APPROVED_FOR_HUMAN_EXECUTION`. A certification engineer starts the run, and certification-service consumes the approval. AI identities are refused SESSION_CONFIG targets server-side.
- **Cancellation:** every router checks the cancel flag before the next step. A workflow waiting on approval is cancelled by resuming it with a cancel signal.
- **Deployment:** agents run in-process by default (one deployable). `FIXAI_AGENT_MODE=http` calls each agent service instead. Either way, agents act only through their MCP tool allow-lists, and the orchestrator itself may call only `get_certification_status`.

## Consequences

- Durability is available without new infrastructure. Multi-replica orchestration needs a shared checkpointer (Postgres) and a distributed cancel flag; both are deferred until more than one replica is required.
- Each graph step stores its state, so polling intervals should stay coarse (default 2 s).
- The `workflow` evaluation suite covers every terminal outcome:
  - approval, rejection, still pending;
  - payload tampering;
  - cancellation while polling and while waiting;
  - poll budget exhausted;
  - an objective that cannot be planned;
  - an injected objective;
  - restart durability.
