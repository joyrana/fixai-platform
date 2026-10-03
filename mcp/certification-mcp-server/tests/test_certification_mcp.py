import httpx
import pytest
import respx
from mcp.client import Client

from fixai_certification_mcp import server as srv
from fixai_common.identity import Principal, ServiceIdentity, current_principal
from fixai_common.mcp.server import contract
from fixai_common.platform_client import PlatformClient, PlatformUrls

URLS = PlatformUrls("http://cert", "http://broker", "http://workflow", "http://sim")
RUN = "0b1e1f6a-0000-4000-8000-000000000001"
EXEC = "0b1e1f6a-0000-4000-8000-0000000000e1"


@pytest.fixture(autouse=True)
def platform():
    srv.use_client(PlatformClient(URLS, ServiceIdentity("certification-mcp", ("AI_AGENT",))))
    srv.registry.audit_trail.clear()
    srv.registry._calls.clear()
    with respx.mock(assert_all_called=False) as mock:
        yield mock


def as_agent(agent="certification-agent", roles=("AI_AGENT",)):
    return current_principal.set(Principal("svc-" + agent, frozenset(roles), agent))


def run_json(status="COMPLETED", verdict="FAILED"):
    return {"id": RUN, "status": status, "verdict": verdict, "fixVersion": "FIX44", "targetType": "SIMULATOR",
            "simulatorProfile": "WRONG_EXEC_TYPE_ON_FILL", "scenariosTotal": 2, "scenariosPassed": 1,
            "scenariosFailed": 1, "scenariosErrored": 0, "evidenceDigest": "a" * 64}


async def test_contract_exposes_schemas_and_governance():
    doc = await contract(srv.server, srv.registry)
    names = [t["name"] for t in doc["tools"]]
    assert names == sorted(["get_certification_status", "list_test_scenarios", "retrieve_certification_evidence",
                            "start_simulated_certification", "validate_test_plan"])
    start = next(t for t in doc["tools"] if t["name"] == "start_simulated_certification")
    assert start["capability"] == "SIMULATED_WRITE"
    assert start["allowedAgents"] == ["agent-orchestrator", "certification-agent"]
    assert "idempotency_key" in start["inputSchema"]["required"]
    assert "target" not in str(start["inputSchema"]["properties"])


async def test_start_forces_simulator_target_and_passes_idempotency_key(platform):
    route = platform.post("http://cert/api/v1/certification-runs").mock(
        return_value=httpx.Response(202, json=run_json("QUEUED", None)))
    token = as_agent()
    try:
        async with Client(srv.server) as client:
            result = await client.call_tool("start_simulated_certification", {
                "fix_version": "FIX44", "idempotency_key": "plan-0001-abcdef", "suite_id": "smoke",
                "simulator_profile": "WRONG_EXEC_TYPE_ON_FILL"})
    finally:
        current_principal.reset(token)
    assert not result.is_error
    request = route.calls.last.request
    assert request.headers["Idempotency-Key"] == "plan-0001-abcdef"
    assert b'"type":"SIMULATOR"' in request.content.replace(b" ", b"")
    assert result.structured_content["status"] == "QUEUED"


async def test_policy_denies_wrong_agent_wrong_role_and_rate():
    for principal, expected in [
        (Principal("svc", frozenset({"AI_AGENT"}), "knowledge-agent"), "not allow-listed"),
        (Principal("aud", frozenset({"AUDITOR"}), "certification-agent"), "not permitted"),
    ]:
        token = current_principal.set(principal)
        try:
            async with Client(srv.server) as client:
                result = await client.call_tool("start_simulated_certification",
                                                {"fix_version": "FIX44", "idempotency_key": "k-00000001", "suite_id": "smoke"})
        finally:
            current_principal.reset(token)
        assert result.is_error and expected in result.content[0].text
    outcomes = [r.outcome for r in srv.registry.audit_trail]
    assert outcomes == ["denied", "denied"]


async def test_evidence_is_bounded_and_references_are_stable(platform):
    platform.get(f"http://cert/api/v1/certification-runs/{RUN}").mock(return_value=httpx.Response(200, json=run_json()))
    platform.get(f"http://cert/api/v1/certification-runs/{RUN}/report").mock(return_value=httpx.Response(200, json={
        "scenarios": [
            {"executionId": EXEC, "scenarioId": "ORD-002", "status": "FAILED", "failureSummary": "ExecType wrong",
             "failedAssertions": [{"step": 4, "subject": "ExecType", "expected": "== F", "actual": "0", "evidenceOrdinal": 7}],
             "failedProtocolChecks": []},
            {"executionId": "x", "scenarioId": "ORD-001", "status": "PASSED", "failedAssertions": [], "failedProtocolChecks": []}]}))
    items = [{"ordinal": i, "kind": "MESSAGE", "direction": "INBOUND", "msgType": "8", "msgSeqNum": i,
              "raw": f"8=FIX.4.4|35=8|150={'0' if i == 7 else 'F'}|", "eventText": None} for i in range(60)]
    platform.get(f"http://cert/api/v1/certification-runs/{RUN}/scenarios/{EXEC}/evidence").mock(
        return_value=httpx.Response(200, json={"items": items, "total": 60}))
    token = as_agent("fix-agent")
    try:
        async with Client(srv.server) as client:
            result = await client.call_tool("retrieve_certification_evidence",
                                            {"run_id": RUN, "max_evidence_per_scenario": 10})
    finally:
        current_principal.reset(token)
    scenarios = result.structured_content["scenarios"]
    assert [s["scenario_id"] for s in scenarios] == ["ORD-002"]
    evidence = scenarios[0]["evidence"]
    assert len(evidence) == 10 and scenarios[0]["evidence_truncated"]
    assert any(e["ordinal"] == 7 for e in evidence), "cited evidence must always be included"
    assert scenarios[0]["failed_assertions"][0]["evidence_ref"] == f"run/{RUN}/exec/{EXEC}/evidence/7"


async def test_invalid_arguments_are_rejected_before_any_call(platform):
    token = as_agent("fix-agent")
    try:
        async with Client(srv.server) as client:
            result = await client.call_tool("get_certification_status", {"run_id": "../../etc/passwd"})
    finally:
        current_principal.reset(token)
    assert result.is_error
    assert not platform.calls
