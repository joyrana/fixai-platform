import httpx
import pytest
import respx
from mcp.client import Client

from fixai_common.identity import Principal, ServiceIdentity, current_principal
from fixai_common.platform_client import PlatformClient, PlatformUrls
from fixai_fix_mcp import server as srv

URLS = PlatformUrls("http://cert", "http://broker", "http://workflow", "http://sim")
CFG = "0b1e1f6a-0000-4000-8000-0000000000c1"


@pytest.fixture(autouse=True)
def platform():
    srv.use_client(PlatformClient(URLS, ServiceIdentity("fix-mcp", ("AI_AGENT",))))
    with respx.mock(assert_all_called=False) as mock:
        yield mock


async def call(tool, args, roles=("AI_AGENT",)):
    token = current_principal.set(Principal("agent", frozenset(roles), "fix-agent"))
    try:
        async with Client(srv.server) as client:
            return await client.call_tool(tool, args)
    finally:
        current_principal.reset(token)


async def test_inspect_message_delegates_to_platform_validator(platform):
    route = platform.post("http://cert/api/v1/fix/inspect").mock(return_value=httpx.Response(200, json={
        "valid": False, "version": "FIX44", "msgType": "D",
        "issues": [{"code": "REQUIRED_TAG_MISSING", "tag": 54, "detail": "Required tag missing"}],
        "view": {"fields": [{"tag": 35, "name": "MsgType", "value": "D", "section": "HEADER"}], "rawRedacted": "8=FIX.4.4|35=D|"}}))
    result = await call("inspect_message", {"raw": "8=FIX.4.4|35=D|10=000|", "expected_version": "FIX44"})
    assert route.called
    assert result.structured_content["issues"][0]["code"] == "REQUIRED_TAG_MISSING"


async def test_inspect_session_never_returns_secret_paths(platform):
    platform.get(f"http://broker/api/v1/session-configs/{CFG}").mock(return_value=httpx.Response(200, json={
        "id": CFG, "name": "UAT", "status": "APPROVED", "environment": "UAT", "fixVersion": "FIX44", "beginString": "FIX.4.4",
        "role": "INITIATOR", "senderCompId": "FIXAI", "targetCompId": "BRK", "host": "uat.example", "port": 9876,
        "heartbeatIntervalSeconds": 30, "resetOnLogon": True, "credentialRef": "vault:fix/brk/uat/password"}))
    result = await call("inspect_session", {"session_config_id": CFG})
    assert result.structured_content["credential_scheme"] == "vault"
    assert "fix/brk" not in str(result.structured_content)


async def test_session_events_filter_to_session_layer(platform):
    run, exec_id = CFG, "0b1e1f6a-0000-4000-8000-0000000000e2"
    items = [{"ordinal": 0, "kind": "EVENT", "eventText": "Initiated logon request"},
             {"ordinal": 1, "kind": "MESSAGE", "direction": "INBOUND", "msgType": "A", "msgSeqNum": 1, "raw": "35=A|"},
             {"ordinal": 2, "kind": "MESSAGE", "direction": "INBOUND", "msgType": "8", "msgSeqNum": 2, "raw": "35=8|"}]
    platform.get(f"http://cert/api/v1/certification-runs/{run}/scenarios/{exec_id}/evidence").mock(
        return_value=httpx.Response(200, json={"items": items}))
    result = await call("retrieve_session_events", {"run_id": run, "execution_id": exec_id})
    assert [e["ordinal"] for e in result.structured_content["events"]] == [0, 1]


async def test_reviewer_role_cannot_use_fix_tools():
    result = await call("inspect_message", {"raw": "8=FIX.4.4|"}, roles=("REVIEWER",))
    assert result.is_error
