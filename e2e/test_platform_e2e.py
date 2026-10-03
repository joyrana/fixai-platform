"""Cross-service end-to-end test against a running stack (docker compose --profile platform).

    FIXAI_E2E_BASE_URL=http://localhost:3000 uv run pytest e2e -v

Everything goes through the UI gateway exactly as the browser does: certification-service, broker-service,
workflow-service, the fix simulator, the MCP servers over streamable HTTP and the agent orchestrator. The "broker" in
the external-certification journey is the synthetic simulator registered as a TEST session; nothing connects to a real
venue.
"""

from __future__ import annotations

import os
import time
import uuid
from typing import Any

import httpx
import pytest

BASE = os.environ.get("FIXAI_E2E_BASE_URL")
pytestmark = pytest.mark.skipif(not BASE, reason="set FIXAI_E2E_BASE_URL to run against a running stack")

ENGINEER = {"X-Dev-User": "alice.engineer", "X-Dev-Roles": "CERTIFICATION_ENGINEER"}
MANAGER = {"X-Dev-User": "bob.manager", "X-Dev-Roles": "BROKER_MANAGER"}
REVIEWER = {"X-Dev-User": "carol.reviewer", "X-Dev-Roles": "REVIEWER"}
AUDITOR = {"X-Dev-User": "dave.auditor", "X-Dev-Roles": "AUDITOR"}
AGENT = {"X-Dev-User": "certification-agent", "X-Dev-Roles": "AI_AGENT"}
FINISHED = {"COMPLETED", "CANCELLED", "ERROR"}


@pytest.fixture(scope="module")
def client() -> httpx.Client:
    with httpx.Client(base_url=BASE or "", timeout=120) as c:
        yield c


def ok(response: httpx.Response, *codes: int) -> Any:
    assert response.status_code in (codes or (200,)), f"{response.request.method} {response.request.url}: {response.status_code} {response.text[:500]}"
    return response.json() if response.content else None


def wait_for(fetch, done, timeout: float = 120, interval: float = 1.0) -> Any:
    deadline = time.monotonic() + timeout
    while True:
        value = fetch()
        if done(value):
            return value
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out; last value: {str(value)[:500]}")
        time.sleep(interval)


def run_to_completion(client: httpx.Client, body: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
    run = ok(client.post("/api/cert/api/v1/certification-runs", json=body,
                         headers={**headers, "Idempotency-Key": f"e2e-{uuid.uuid4().hex}"}), 202)
    return wait_for(lambda: ok(client.get(f"/api/cert/api/v1/certification-runs/{run['id']}", headers=headers)),
                    lambda r: r["status"] in FINISHED)


def test_simulator_smoke_run_passes_with_report_and_replay(client):
    run = run_to_completion(client, {"suiteId": "smoke", "fixVersion": "FIX44",
                                     "target": {"type": "SIMULATOR", "simulatorProfile": "COMPLIANT"}}, ENGINEER)
    assert (run["status"], run["verdict"], run["scenariosPassed"]) == ("COMPLETED", "PASSED", 4)
    report = ok(client.get(f"/api/cert/api/v1/certification-runs/{run['id']}/report", headers=AUDITOR))
    assert report["run"]["verdict"] == "PASSED" and report["evidenceDigest"] == run["evidenceDigest"]
    replay = ok(client.post(f"/api/cert/api/v1/certification-runs/{run['id']}/replay-verification", headers=ENGINEER))
    assert replay["consistent"] and replay["evidenceDigestMatches"]


def test_defect_is_caught_by_the_engine_and_diagnosed_by_agents_over_mcp(client):
    run = run_to_completion(client, {"scenarioIds": ["SES-002"], "fixVersion": "FIX44",
                                     "target": {"type": "SIMULATOR", "simulatorProfile": "HEARTBEAT_WITHOUT_TEST_REQ_ID"}}, ENGINEER)
    assert run["verdict"] == "FAILED"
    diagnosis = ok(client.post("/api/ai/v1/assist/diagnose", json={"run_id": run["id"]}, headers=ENGINEER))
    output = diagnosis["output"]
    assert output["verdict"] == "FAILED" and not output["abstained"]
    top = output["scenarios"][0]["hypotheses"][0]
    assert top["category"] == "SESSION_PROTOCOL_VIOLATION"
    assert all(ref["ref"].startswith(f"run/{run['id']}/") for ref in top["evidence"])
    assert [t["tool"] for t in diagnosis["metadata"]["tool_calls"]][0] == "retrieve_certification_evidence"

    execution = ok(client.get(f"/api/cert/api/v1/certification-runs/{run['id']}/scenarios", headers=ENGINEER))[0]
    logs = ok(client.post("/api/ai/v1/assist/logs", json={"run_id": run["id"], "execution_id": execution["id"]}, headers=ENGINEER))
    assert "UNANSWERED_TEST_REQUEST" in {a["kind"] for a in logs["output"]["anomalies"]}


def test_ai_identities_cannot_target_broker_sessions(client):
    response = client.post("/api/cert/api/v1/certification-runs", headers=AGENT, json={
        "suiteId": "smoke", "fixVersion": "FIX44", "target": {"type": "SESSION_CONFIG", "sessionConfigId": str(uuid.uuid4())}})
    assert response.status_code == 403


def test_knowledge_answers_with_citations(client):
    answer = ok(client.post("/api/ai/v1/assist/ask", json={"question": "How should a ResendRequest be answered?"}, headers=ENGINEER))
    assert not answer["output"]["abstained"]
    assert answer["output"]["citations"][0]["doc_id"] == "KB-SES-RECOVERY"


def onboard_session(client: httpx.Client) -> str:
    """Broker onboarding: a manager configures a TEST session, a different person approves it, the manager activates it."""
    code = f"E2E-{uuid.uuid4().hex[:8].upper()}"
    broker = ok(client.post("/api/broker/api/v1/brokers", headers=MANAGER, json={
        "brokerCode": code, "name": f"E2E Broker {code}", "endpoint": "fix://fix-simulator:9880", "status": "ACTIVE"}), 201)
    config = ok(client.post(f"/api/broker/api/v1/brokers/{broker['id']}/session-configs", headers=MANAGER, json={
        "name": "E2E test session", "environment": "TEST", "fixVersion": "FIX44", "senderCompId": f"FIXAI-{code}",
        "targetCompId": "SIM", "host": "fix-simulator", "port": 9880, "heartbeatIntervalSeconds": 30,
        "reconnectIntervalSeconds": 5, "resetOnLogon": True, "credentialRef": "vault:fix/e2e/test"}), 201)
    submitted = ok(client.post(f"/api/broker/api/v1/session-configs/{config['id']}/submit", headers=MANAGER,
                               json={"justification": "E2E onboarding of the synthetic TEST counterparty"}))
    approval_id = submitted["approvalRequestId"]

    # Four-eyes: the requester cannot approve their own request, even with the reviewer role.
    own = client.post(f"/api/workflow/api/v1/approvals/{approval_id}/decision",
                      headers={"X-Dev-User": "bob.manager", "X-Dev-Roles": "REVIEWER"},
                      json={"decision": "APPROVE", "rationale": "approving my own request"})
    assert own.status_code in (403, 409), own.text
    # Activation before approval is refused.
    assert client.post(f"/api/broker/api/v1/session-configs/{config['id']}/activate", headers=MANAGER).status_code == 409

    ok(client.post(f"/api/workflow/api/v1/approvals/{approval_id}/decision", headers=REVIEWER,
                   json={"decision": "APPROVE", "rationale": "Matches the onboarding record"}))
    active = ok(client.post(f"/api/broker/api/v1/session-configs/{config['id']}/activate", headers=MANAGER))
    assert active["status"] == "APPROVED" and active["activatedBy"] == "bob.manager"
    return config["id"]


def test_external_certification_requires_a_human_approved_plan_end_to_end(client):
    session_id = onboard_session(client)

    workflow = ok(client.post("/api/ai/v1/workflows", headers=ENGINEER, json={
        "fix_version": "FIX44", "objective": "Run a smoke test", "simulator_profile": "COMPLIANT", "poll_interval_seconds": 1,
        "external_certification": {"session_config_id": session_id, "environment": "TEST",
                                   "justification": "Simulator smoke passed; certify the TEST session with the same plan."}}), 202)
    state = wait_for(lambda: ok(client.get(f"/api/ai/v1/workflows/{workflow['workflow_id']}", headers=ENGINEER)),
                     lambda s: s["phase"] in ("WAITING_FOR_HUMAN", "DONE", "FAILED"), timeout=180)
    assert state["phase"] == "WAITING_FOR_HUMAN", state
    assert state["report"]["verdict"] == "PASSED"
    approval_id = state["interrupts"][0]["approval_id"]

    pending = ok(client.get(f"/api/workflow/api/v1/approvals/{approval_id}", headers=REVIEWER))["approval"]
    assert pending["status"] == "PENDING" and pending["action"] == "START_EXTERNAL_CERTIFICATION"
    assert pending["requestedBy"] == "alice.engineer"  # filed by the agent on behalf of the engineer

    # Resuming before a decision changes nothing; the AI cannot approve.
    ok(client.post(f"/api/ai/v1/workflows/{workflow['workflow_id']}/resume", headers=ENGINEER), 202)
    state = wait_for(lambda: ok(client.get(f"/api/ai/v1/workflows/{workflow['workflow_id']}", headers=ENGINEER)),
                     lambda s: s["phase"] != "RUNNING")
    assert state["phase"] == "WAITING_FOR_HUMAN"

    ok(client.post(f"/api/workflow/api/v1/approvals/{approval_id}/decision", headers=REVIEWER,
                   json={"decision": "APPROVE", "rationale": "Simulator evidence reviewed; TEST window agreed"}))
    ok(client.post(f"/api/ai/v1/workflows/{workflow['workflow_id']}/resume", headers=ENGINEER), 202)
    state = wait_for(lambda: ok(client.get(f"/api/ai/v1/workflows/{workflow['workflow_id']}", headers=ENGINEER)),
                     lambda s: s["phase"] == "DONE")
    assert state["outcome"] == "APPROVED_FOR_HUMAN_EXECUTION"

    plan = sorted(state["plan"]["scenario_ids"])
    target = {"type": "SESSION_CONFIG", "sessionConfigId": session_id, "approvalId": approval_id}
    # A different plan does not match the approved payload.
    mismatch = client.post("/api/cert/api/v1/certification-runs", headers=ENGINEER,
                           json={"scenarioIds": plan[:1], "fixVersion": "FIX44", "target": target})
    assert mismatch.status_code == 409, mismatch.text
    # The approved plan runs once against the TEST session.
    run = run_to_completion(client, {"scenarioIds": plan, "fixVersion": "FIX44", "target": target}, ENGINEER)
    assert (run["targetType"], run["environment"], run["verdict"]) == ("SESSION_CONFIG", "TEST", "PASSED")
    reused = client.post("/api/cert/api/v1/certification-runs", headers=ENGINEER,
                         json={"scenarioIds": plan, "fixVersion": "FIX44", "target": target})
    assert reused.status_code == 409, reused.text
    assert ok(client.get(f"/api/workflow/api/v1/approvals/{approval_id}", headers=AUDITOR))["approval"]["status"] == "CONSUMED"


def test_audit_chain_is_intact(client):
    verification = ok(client.get("/api/workflow/api/v1/audit-events/verify", headers=AUDITOR))
    assert verification["valid"] and verification["eventsChecked"] > 0
    actions = {e["action"] for e in ok(client.get("/api/workflow/api/v1/audit-events?size=500", headers=AUDITOR))["items"]}
    assert {"CERTIFICATION_RUN_REQUESTED", "CERTIFICATION_RUN_COMPLETED"} <= actions
