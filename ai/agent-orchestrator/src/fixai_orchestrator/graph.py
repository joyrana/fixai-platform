"""Certification workflow graph.

    plan ──► poll ◄──┐                  (bounded polling; each poll is a checkpointed step)
              │      └── wait
              ▼
          diagnose (only when the engine verdict is not PASSED: fix-agent, then log-analysis on the first failure)
              ▼
           report
              ▼
      request_approval ──► await_approval ◄─┐   (interrupt: resumes only to re-check the approval status)
              │                  │          └── still pending
              ▼                  ▼
            finish ◄─────────────┘

The verdict always comes from the certification engine; the graph only routes on it. Approval of an external
certification is decided by a human reviewer in the workflow service; the graph verifies the decision and the bound
payload hash, and even then only hands off: an AI workflow never starts a run against a broker endpoint.
Cancellation is honoured between steps (the run itself is bounded by the engine's own timeouts).
"""

from __future__ import annotations

import asyncio
import operator
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt
from pydantic import BaseModel, Field

from fixai_common import telemetry
from fixai_orchestrator.agents import AgentRunner, ToolRunner

FINISHED = ("COMPLETED", "CANCELLED", "ERROR")
Outcome = Literal["PLAN_REJECTED", "RUN_NOT_STARTED", "POLL_BUDGET_EXHAUSTED", "REPORTED", "NOT_ELIGIBLE_FOR_EXTERNAL",
                  "APPROVAL_NOT_FILED", "APPROVED_FOR_HUMAN_EXECUTION", "APPROVAL_DENIED", "APPROVAL_CHECKS_EXHAUSTED",
                  "CANCELLED", "FAILED"]


class ExternalCertification(BaseModel):
    """Request to certify a real broker session after a passing simulator run. Requires human approval."""

    session_config_id: str = Field(pattern=r"^[0-9a-f-]{36}$")
    environment: Literal["TEST", "UAT"] = "TEST"
    justification: str = Field(min_length=30, max_length=4000)


class WorkflowRequest(BaseModel):
    fix_version: Literal["FIX42", "FIX44", "FIX50SP2"]
    objective: str = Field(min_length=3, max_length=1000)
    simulator_profile: str = Field(default="COMPLIANT", pattern=r"^[A-Z_]{1,64}$")
    external_certification: ExternalCertification | None = None
    max_polls: int = Field(default=120, ge=1, le=600)
    poll_interval_seconds: float = Field(default=2.0, ge=0.0, le=60.0)
    max_approval_checks: int = Field(default=20, ge=1, le=200)


class WorkflowState(TypedDict, total=False):
    workflow_id: str
    requested_by: str
    request: dict[str, Any]
    plan: dict[str, Any] | None
    run_id: str | None
    run_status: dict[str, Any] | None
    polls: int
    diagnosis: dict[str, Any] | None
    log_analysis: dict[str, Any] | None
    report: dict[str, Any] | None
    approval: dict[str, Any] | None
    approval_checks: int
    cancel_requested: bool
    outcome: Outcome | None
    events: Annotated[list[str], operator.add]
    agent_runs: Annotated[list[dict[str, Any]], operator.add]


def _meta(result: dict[str, Any]) -> list[dict[str, Any]]:
    m = result["metadata"]
    return [{k: m[k] for k in ("agent", "agent_version", "prompt_version", "provider", "model", "latency_ms",
                               "llm_fallback_used", "input_tokens", "output_tokens", "estimated_cost_usd")}
            | {"tool_calls": len(m["tool_calls"])}]


def build_graph(agents: AgentRunner, tools: ToolRunner,
                sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
                is_cancelled: Callable[[str], bool] = lambda workflow_id: False) -> StateGraph:
    """`is_cancelled(workflow_id)` is consulted by every router, so a cancel request takes effect before the next step
    even while a step is executing."""

    async def plan(state: WorkflowState) -> dict[str, Any]:
        request = WorkflowRequest.model_validate(state["request"])
        result = await agents("certification-agent", {
            "fix_version": request.fix_version, "objective": request.objective,
            "simulator_profile": request.simulator_profile, "start": True,
            "idempotency_key": f"wf-{state['workflow_id']}"})
        output = result["output"]
        update: dict[str, Any] = {"plan": output, "agent_runs": _meta(result)}
        if output["abstained"]:
            return update | {"outcome": "PLAN_REJECTED", "events": [f"plan rejected: {output['abstain_reason']}"]}
        if not output.get("run"):
            return update | {"outcome": "RUN_NOT_STARTED", "events": ["plan valid but the run was not started"]}
        return update | {"run_id": output["run"]["run_id"], "polls": 0,
                         "events": [f"simulator run {output['run']['run_id']} started with "
                                    f"{len(output['scenario_ids'])} scenarios"]}

    async def poll(state: WorkflowState) -> dict[str, Any]:
        status = await tools("get_certification_status", {"run_id": state["run_id"]})
        polls = state.get("polls", 0) + 1
        update: dict[str, Any] = {"run_status": status, "polls": polls}
        if status["status"] in FINISHED:
            update["events"] = [f"run finished: status {status['status']}, engine verdict {status['verdict']}"]
        elif polls >= WorkflowRequest.model_validate(state["request"]).max_polls:
            update |= {"outcome": "POLL_BUDGET_EXHAUSTED",
                       "events": [f"run still {status['status']} after {polls} polls; workflow stopped, run continues"]}
        return update

    async def wait(state: WorkflowState) -> dict[str, Any]:
        await sleep(WorkflowRequest.model_validate(state["request"]).poll_interval_seconds)
        return {}

    async def diagnose(state: WorkflowState) -> dict[str, Any]:
        result = await agents("fix-agent", {"run_id": state["run_id"]})
        update: dict[str, Any] = {"diagnosis": result["output"], "agent_runs": _meta(result),
                                  "events": [f"diagnosed {len(result['output']['scenarios'])} failed scenarios"]}
        scenarios = result["output"]["scenarios"]
        if scenarios:
            logs = await agents("log-analysis-agent", {"run_id": state["run_id"],
                                                       "execution_id": scenarios[0]["execution_id"]})
            update |= {"log_analysis": logs["output"], "agent_runs": update["agent_runs"] + _meta(logs)}
        return update

    async def report(state: WorkflowState) -> dict[str, Any]:
        hypotheses = [{"scenario_id": s["scenario_id"], "title": h["title"], "category": h["category"],
                       "likelihood": h["likelihood"]}
                      for s in (state.get("diagnosis") or {}).get("scenarios", []) for h in s["hypotheses"][:3]]
        result = await agents("report-agent", {"run_id": state["run_id"], "hypotheses": hypotheses[:50]})
        output = result["output"]
        update: dict[str, Any] = {"report": output, "agent_runs": _meta(result),
                                  "events": [f"report drafted (engine verdict {output['verdict']})"]}
        if WorkflowRequest.model_validate(state["request"]).external_certification is None:
            update["outcome"] = "REPORTED"
        elif output["verdict"] != "PASSED":
            update |= {"outcome": "NOT_ELIGIBLE_FOR_EXTERNAL",
                       "events": update["events"] + ["external certification not requested: simulator verdict is "
                                                     f"{output['verdict']}"]}
        return update

    async def request_approval(state: WorkflowState) -> dict[str, Any]:
        request = WorkflowRequest.model_validate(state["request"])
        external = request.external_certification
        assert external is not None  # routed here only when requested
        evidence = [f"run/{state['run_id']}"]
        if (state.get("run_status") or {}).get("evidence_digest"):
            evidence.append(f"run/{state['run_id']}/digest/{state['run_status']['evidence_digest']}")
        result = await agents("human-review-agent", {
            "action": "START_EXTERNAL_CERTIFICATION", "target_type": "session-config",
            "target_id": external.session_config_id, "environment": external.environment,
            # Exactly what certification-service will present when a certification engineer starts the external run;
            # the simulator run is evidence, not part of the approved action.
            "arguments": {"fixVersion": request.fix_version,
                          "scenarioIds": sorted(state["plan"]["scenario_ids"])},  # type: ignore[index]
            "justification": external.justification, "evidence_refs": evidence,
            "requested_by": state["requested_by"], "idempotency_key": f"wf-approval-{state['workflow_id']}"})
        output = result["output"]
        update: dict[str, Any] = {"approval": output, "approval_checks": 0, "agent_runs": _meta(result)}
        if not output["filed"]:
            return update | {"outcome": "APPROVAL_NOT_FILED",
                             "events": [f"approval request not filed: {'; '.join(output['refusal_reasons'])[:300]}"]}
        return update | {"events": [f"approval {output['approval_id']} requested; waiting for a human reviewer"]}

    async def await_approval(state: WorkflowState) -> dict[str, Any]:
        approval = state["approval"] or {}
        signal = interrupt({"type": "approval_required", "approval_id": approval.get("approval_id"),
                            "payload_hash": approval.get("payload_hash"), "risk_level": approval.get("risk_level"),
                            "expires_at": approval.get("expires_at"),
                            "message": "A reviewer must decide this request in the workflow service, then resume."})
        if isinstance(signal, dict) and signal.get("action") == "cancel":
            return {"cancel_requested": True, "events": ["cancel requested while waiting for approval"]}
        checks = state.get("approval_checks", 0) + 1
        result = await agents("human-review-agent", {"check_approval_id": approval["approval_id"]})
        current = result["output"]
        update: dict[str, Any] = {"approval_checks": checks, "agent_runs": _meta(result)}
        if current["status"] == "APPROVED" and current["payload_hash"] == approval["payload_hash"]:
            return update | {"approval": approval | {"status": "APPROVED"}, "outcome": "APPROVED_FOR_HUMAN_EXECUTION",
                             "events": ["approval granted for the exact requested payload; a certification engineer "
                                        "must start the external run with this approval"]}
        if current["status"] == "APPROVED":
            return update | {"outcome": "FAILED", "events": ["approval payload hash changed; refusing to proceed"]}
        if current["status"] in ("REJECTED", "EXPIRED", "CANCELLED", "CONSUMED"):
            return update | {"approval": approval | {"status": current["status"]}, "outcome": "APPROVAL_DENIED",
                             "events": [f"approval {current['status'].lower()}"]}
        if checks >= WorkflowRequest.model_validate(state["request"]).max_approval_checks:
            return update | {"outcome": "APPROVAL_CHECKS_EXHAUSTED", "events": ["approval still pending; stopped"]}
        return update | {"events": [f"approval still {current['status']}"]}

    async def cancelled(state: WorkflowState) -> dict[str, Any]:
        return {"outcome": "CANCELLED", "events": ["workflow cancelled"]}

    async def finish(state: WorkflowState) -> dict[str, Any]:
        telemetry.WORKFLOW_OUTCOMES.labels(state.get("outcome") or "UNKNOWN").inc()
        return {"events": [f"workflow finished: {state.get('outcome')}"]}

    def guard(next_step: Callable[[WorkflowState], str]) -> Callable[[WorkflowState], str]:
        def route(state: WorkflowState) -> str:
            if state.get("cancel_requested") or is_cancelled(state["workflow_id"]):
                return "cancelled"
            if state.get("outcome"):
                return "finish"
            return next_step(state)
        return route

    builder = StateGraph(WorkflowState)
    for name, fn in [("plan", plan), ("poll", poll), ("wait", wait), ("diagnose", diagnose), ("report", report),
                     ("request_approval", request_approval), ("await_approval", await_approval),
                     ("cancelled", cancelled), ("finish", finish)]:
        builder.add_node(name, fn)
    builder.add_edge(START, "plan")
    builder.add_conditional_edges("plan", guard(lambda s: "poll"))
    builder.add_conditional_edges("poll", guard(lambda s: (
        "wait" if s["run_status"]["status"] not in FINISHED
        else "report" if s["run_status"]["verdict"] == "PASSED" else "diagnose")))
    builder.add_conditional_edges("wait", guard(lambda s: "poll"))
    builder.add_conditional_edges("diagnose", guard(lambda s: "report"))
    builder.add_conditional_edges("report", guard(lambda s: "request_approval"))
    builder.add_conditional_edges("request_approval", guard(lambda s: "await_approval"))
    builder.add_conditional_edges("await_approval", guard(lambda s: "await_approval"))
    builder.add_edge("cancelled", "finish")
    builder.add_edge("finish", END)
    return builder


def recursion_limit(request: WorkflowRequest) -> int:
    return 2 * request.max_polls + 2 * request.max_approval_checks + 20
