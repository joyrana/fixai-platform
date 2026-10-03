"""Graph routing with fake agents and tools (no MCP); end-to-end behaviour over the simulated platform lives in the
workflow evaluation suite."""

import asyncio

import httpx
from langgraph.checkpoint.memory import InMemorySaver

from fixai_orchestrator.app import create_orchestrator_app
from fixai_orchestrator.graph import WorkflowRequest
from fixai_orchestrator.service import WorkflowService

RUN = "0b1e1f6a-0000-4000-8000-000000000001"
APPROVAL = "0b1e1f6a-0000-4000-8000-0000000000a1"
META = {"agent": "x", "agent_version": "1", "prompt_version": "p", "provider": "offline", "model": "m", "latency_ms": 1,
        "llm_fallback_used": False, "input_tokens": 0, "output_tokens": 0, "estimated_cost_usd": 0.0, "tool_calls": []}


class Platform:
    def __init__(self, verdict="PASSED", finish_after=1, approval_status="PENDING"):
        self.verdict, self.finish_after, self.approval_status = verdict, finish_after, approval_status
        self.polls, self.calls = 0, []

    async def agents(self, agent, payload):
        self.calls.append(agent)
        output = {
            "certification-agent": {"abstained": False, "abstain_reason": None, "scenario_ids": ["SES-001"],
                                    "run": {"run_id": RUN, "status": "QUEUED", "target_type": "SIMULATOR"}},
            "fix-agent": {"scenarios": [{"scenario_id": "SES-001", "execution_id": RUN,
                                         "hypotheses": [{"title": "t", "category": "UNKNOWN", "likelihood": "low"}]}]},
            "log-analysis-agent": {"anomalies": []},
            "report-agent": {"verdict": self.verdict},
        }.get(agent)
        if agent == "human-review-agent":
            output = {"filed": True, "approval_id": APPROVAL, "payload_hash": "h", "risk_level": "HIGH", "expires_at": "x",
                      "status": self.approval_status if payload.get("check_approval_id") else "PENDING",
                      "refusal_reasons": []}
        return {"output": output, "metadata": META}

    async def tools(self, tool, args):
        self.polls += 1
        done = self.polls >= self.finish_after
        return {"run_id": RUN, "status": "COMPLETED" if done else "RUNNING", "verdict": self.verdict if done else None,
                "evidence_digest": "d"}


async def nosleep(_):
    await asyncio.sleep(0)


def request(**kw):
    return WorkflowRequest(fix_version="FIX44", objective="smoke", poll_interval_seconds=0, **kw)


EXTERNAL = {"session_config_id": "7d4a8f40-0000-4000-8000-00000000c0f1",
            "justification": "Simulator passed and the broker agreed the window for testing."}


async def test_failed_run_is_diagnosed_then_reported():
    platform = Platform(verdict="FAILED", finish_after=3)
    service = WorkflowService(platform.agents, platform.tools, InMemorySaver(), nosleep)
    state = await service.get(await service.start(request(), "alice", wait=True))
    assert state["outcome"] == "REPORTED" and state["polls"] == 3
    assert platform.calls == ["certification-agent", "fix-agent", "log-analysis-agent", "report-agent"]


async def test_approval_gate_interrupts_and_only_completes_after_approval():
    platform = Platform()
    service = WorkflowService(platform.agents, platform.tools, InMemorySaver(), nosleep)
    workflow_id = await service.start(request(external_certification=EXTERNAL), "alice", wait=True)
    state = await service.get(workflow_id)
    assert state["phase"] == "WAITING_FOR_HUMAN" and state["interrupts"][0]["approval_id"] == APPROVAL
    await service.resume(workflow_id, wait=True)
    assert (await service.get(workflow_id))["phase"] == "WAITING_FOR_HUMAN"  # still pending
    platform.approval_status = "APPROVED"
    await service.resume(workflow_id, wait=True)
    state = await service.get(workflow_id)
    assert state["outcome"] == "APPROVED_FOR_HUMAN_EXECUTION" and state["phase"] == "DONE"


async def test_poll_budget_bounds_the_workflow():
    platform = Platform(finish_after=10_000)
    service = WorkflowService(platform.agents, platform.tools, InMemorySaver(), nosleep)
    state = await service.get(await service.start(request(max_polls=4), "alice", wait=True))
    assert state["outcome"] == "POLL_BUDGET_EXHAUSTED" and platform.polls == 4


async def test_api_enforces_roles_and_reports_workflows():
    platform = Platform()
    service = WorkflowService(platform.agents, platform.tools, InMemorySaver(), nosleep)
    app = create_orchestrator_app(service=service)
    body = {"fix_version": "FIX44", "objective": "smoke", "poll_interval_seconds": 0}
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            denied = await client.post("/v1/workflows", json=body, headers={"X-Dev-User": "r", "X-Dev-Roles": "REVIEWER"})
            assert denied.status_code == 403
            started = await client.post("/v1/workflows", json=body,
                                        headers={"X-Dev-User": "alice", "X-Dev-Roles": "CERTIFICATION_ENGINEER"})
            assert started.status_code == 202
            workflow_id = started.json()["workflow_id"]
            for _ in range(100):
                state = (await client.get(f"/v1/workflows/{workflow_id}", headers={"X-Dev-Roles": "AUDITOR"})).json()
                if state["phase"] == "DONE":
                    break
                await asyncio.sleep(0.01)
            assert state["outcome"] == "REPORTED" and state["requested_by"] == "alice"
            conflict = await client.post(f"/v1/workflows/{workflow_id}/resume", headers={"X-Dev-Roles": "ADMIN"})
            assert conflict.status_code == 409
            missing = await client.get("/v1/workflows/does-not-exist", headers={"X-Dev-Roles": "ADMIN"})
            assert missing.status_code == 404


async def test_assist_endpoints_check_roles_and_never_allow_incident_drafts():
    platform = Platform()
    calls = []

    async def agents(agent, payload):
        calls.append((agent, payload))
        return {"output": {"ok": True}, "metadata": META}

    service = WorkflowService(platform.agents, platform.tools, InMemorySaver(), nosleep)
    app = create_orchestrator_app(service=service, agents=agents)
    run = {"run_id": RUN, "execution_id": RUN}
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            engineer = {"X-Dev-User": "e", "X-Dev-Roles": "CERTIFICATION_ENGINEER"}
            assert (await client.post("/v1/assist/logs", json=run, headers=engineer)).status_code == 200
            assert calls[-1] == ("log-analysis-agent", run | {"create_incident": False})
            assert (await client.post("/v1/assist/diagnose", json={"run_id": RUN}, headers=engineer)).status_code == 200
            manager = {"X-Dev-User": "m", "X-Dev-Roles": "BROKER_MANAGER"}
            assert (await client.post("/v1/assist/diagnose", json={"run_id": RUN}, headers=manager)).status_code == 403
            assert (await client.post("/v1/assist/ask", json={"question": "How is a gap detected?"},
                                      headers=manager)).status_code == 200
            assert (await client.post("/v1/assist/diagnose", json={"run_id": "x"}, headers=engineer)).status_code == 422
            assert (await client.get("/v1/workflows", headers=engineer)).json() == []
