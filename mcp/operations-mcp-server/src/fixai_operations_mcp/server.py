"""Operations MCP server: service health, sanitized session logs, incident drafts and human-approval requests.

`request_human_approval` only creates a request; nothing executes until a human reviewer approves it in the workflow
service and a platform service consumes the approval with the exact payload. No tool here can perform a privileged
or production-affecting action.
"""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from typing import Any, Literal

from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import BaseModel, Field

from fixai_common import guard
from fixai_common.identity import ServiceIdentity
from fixai_common.mcp.policy import Capability, ToolPolicy, governed_tool, principal_for
from fixai_common.mcp.server import build_server, serve
from fixai_common.platform_client import PlatformClient, PlatformError, PlatformUrls

VERSION = "1.0.0"
READERS = frozenset({"AI_AGENT", "CERTIFICATION_ENGINEER", "ADMIN", "SERVICE", "AUDITOR"})
WRITERS = frozenset({"AI_AGENT", "CERTIFICATION_ENGINEER", "ADMIN"})
UUID = r"^[0-9a-f-]{36}$"
APPROVABLE_ACTIONS = ("START_EXTERNAL_CERTIFICATION", "APPLY_REMEDIATION", "ACTIVATE_SESSION_CONFIG")

server, registry = build_server("fixai-operations", VERSION,
                                "Operational tools. Approval requests never execute actions; humans decide.")
_client: PlatformClient | None = None
_incidents: dict[str, dict[str, Any]] = {}


def client() -> PlatformClient:
    global _client
    if _client is None:
        _client = PlatformClient(PlatformUrls.from_env(), ServiceIdentity("operations-mcp", ("AI_AGENT",)))
    return _client


def use_client(value: PlatformClient) -> None:
    global _client
    _client = value
    _incidents.clear()


class ServiceHealth(BaseModel):
    service: str
    status: Literal["UP", "DOWN", "UNREACHABLE"]
    latency_ms: int


class HealthReport(BaseModel):
    services: list[ServiceHealth]


class LogLine(BaseModel):
    ref: str
    text: str
    injection_flags: list[str]


class SanitizedLogs(BaseModel):
    run_id: str
    execution_id: str
    lines: list[LogLine]


class IncidentDraft(BaseModel):
    draft_id: str
    title: str
    severity: str
    summary: str
    evidence_refs: list[str]
    created_by: str
    status: Literal["DRAFT"] = "DRAFT"


class ApprovalTicket(BaseModel):
    approval_id: str
    status: str
    payload_hash: str
    risk_level: str
    expires_at: str


@server.tool(description="Health of platform services (actuator readiness). "
             + ToolPolicy("inspect_service_health", Capability.READ, READERS).describe())
@governed_tool(registry, ToolPolicy("inspect_service_health", Capability.READ, READERS))
async def inspect_service_health(ctx: Context) -> HealthReport:
    urls = client().urls
    results = []
    for name, base in [("certification-service", urls.certification), ("broker-service", urls.broker),
                       ("workflow-service", urls.workflow), ("fix-simulator", urls.simulator)]:
        started = time.perf_counter()
        try:
            body = await client().get(base, "/actuator/health/readiness")
            status = "UP" if body and body.get("status") == "UP" else "DOWN"
        except PlatformError:
            status = "DOWN"
        except Exception:  # noqa: BLE001 - connectivity failures are a health result, not an error
            status = "UNREACHABLE"
        results.append(ServiceHealth(service=name, status=status, latency_ms=int((time.perf_counter() - started) * 1000)))
    return HealthReport(services=results)


@server.tool(description="Sanitized session log lines (FIX engine events and session messages) for one scenario "
             "execution, with prompt-injection flags. "
             + ToolPolicy("retrieve_sanitized_logs", Capability.READ, READERS).describe())
@governed_tool(registry, ToolPolicy("retrieve_sanitized_logs", Capability.READ, READERS))
async def retrieve_sanitized_logs(ctx: Context, run_id: str = Field(pattern=UUID), execution_id: str = Field(pattern=UUID),
                                  max_lines: int = Field(default=200, ge=1, le=1000)) -> SanitizedLogs:
    page = await client().get(client().urls.certification,
                              f"/api/v1/certification-runs/{run_id}/scenarios/{execution_id}/evidence", {"size": 1000})
    lines = []
    for item in page["items"][:max_lines]:
        if item["kind"] == "EVENT":
            text = f"EVENT {item.get('eventText') or ''}"
        else:
            text = f"{item.get('direction')} {item.get('msgType')} seq={item.get('msgSeqNum')} {item.get('raw') or ''}"
        text = guard.sanitize(text, 2000)
        lines.append(LogLine(ref=f"run/{run_id}/exec/{execution_id}/evidence/{item['ordinal']}", text=text,
                             injection_flags=[f.pattern for f in guard.scan(text)]))
    return SanitizedLogs(run_id=run_id, execution_id=execution_id, lines=lines)


INCIDENT_POLICY = ToolPolicy("create_incident_draft", Capability.SIMULATED_WRITE, WRITERS, rate_per_minute=10,
                             allowed_agents=frozenset({"log-analysis-agent", "agent-orchestrator"}))


@server.tool(description="Create an incident DRAFT (never filed externally) referencing evidence. Idempotent per key. "
             + INCIDENT_POLICY.describe())
@governed_tool(registry, INCIDENT_POLICY)
async def create_incident_draft(ctx: Context, title: str = Field(min_length=5, max_length=200),
                                severity: Literal["SEV1", "SEV2", "SEV3", "SEV4"] = "SEV3",
                                summary: str = Field(min_length=10, max_length=4000),
                                evidence_refs: list[str] = Field(min_length=1, max_length=50),
                                idempotency_key: str = Field(pattern=r"^[A-Za-z0-9._:-]{8,128}$")) -> IncidentDraft:
    principal = principal_for(ctx)
    draft_id = "INC-DRAFT-" + hashlib.sha256(f"{principal.subject}:{idempotency_key}".encode()).hexdigest()[:12]
    existing = _incidents.get(draft_id)
    if existing is not None:
        return IncidentDraft(**existing)
    draft = IncidentDraft(draft_id=draft_id, title=guard.sanitize(title, 200), severity=severity,
                          summary=guard.sanitize(summary, 4000), evidence_refs=evidence_refs,
                          created_by=principal.agent or principal.subject)
    _incidents[draft_id] = draft.model_dump()
    return draft


APPROVAL_POLICY = ToolPolicy("request_human_approval", Capability.SIMULATED_WRITE, WRITERS, rate_per_minute=5,
                             allowed_agents=frozenset({"human-review-agent", "agent-orchestrator"}))


@server.tool(description="File a human approval request in the workflow service (it does not execute anything). "
             "The server binds the approval to the exact payload hash. " + APPROVAL_POLICY.describe())
@governed_tool(registry, APPROVAL_POLICY)
async def request_human_approval(
        ctx: Context, action: Literal["START_EXTERNAL_CERTIFICATION", "APPLY_REMEDIATION", "ACTIVATE_SESSION_CONFIG"],
        target_type: str = Field(pattern=r"^[a-z0-9-]{2,64}$"), target_id: str = Field(min_length=1, max_length=128),
        environment: Literal["SIMULATOR", "TEST", "UAT"] = "TEST",
        arguments: dict[str, Any] = Field(default_factory=dict),
        justification: str = Field(min_length=10, max_length=4000),
        evidence_refs: list[str] = Field(default_factory=list, max_length=50),
        trace_ids: list[str] = Field(default_factory=list, max_length=50),
        on_behalf_of: str | None = Field(default=None, pattern=r"^[A-Za-z0-9._@-]{1,128}$"),
        idempotency_key: str = Field(pattern=r"^[A-Za-z0-9._:-]{8,128}$")) -> ApprovalTicket:
    if len(json.dumps(arguments, default=str)) > 16_000:
        raise ToolError("arguments too large")
    body = {"action": action, "targetType": target_type, "targetId": target_id, "environment": environment,
            "arguments": arguments, "justification": justification, "evidenceRefs": evidence_refs,
            "traceIds": [t for t in trace_ids if t], "onBehalfOf": on_behalf_of}
    try:
        result = await client().post(client().urls.workflow, "/api/v1/approvals",
                                     {k: v for k, v in body.items() if v is not None}, idempotency_key)
    except PlatformError as error:
        raise ToolError(f"Approval request refused: {error.detail} {error.codes}") from None
    return ApprovalTicket(approval_id=result["id"], status=result["status"], payload_hash=result["payloadHash"],
                          risk_level=result["riskLevel"], expires_at=str(result["expiresAt"]))


@server.tool(description="Status of an approval request. "
             + ToolPolicy("get_approval_status", Capability.READ, READERS).describe())
@governed_tool(registry, ToolPolicy("get_approval_status", Capability.READ, READERS, rate_per_minute=240))
async def get_approval_status(ctx: Context, approval_id: str = Field(pattern=UUID)) -> ApprovalTicket:
    result = (await client().get(client().urls.workflow, f"/api/v1/approvals/{approval_id}"))["approval"]
    return ApprovalTicket(approval_id=result["id"], status=result["status"], payload_hash=result["payloadHash"],
                          risk_level=result["riskLevel"], expires_at=str(result["expiresAt"]))


def new_idempotency_key(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:16]}"


def main() -> None:
    serve(server, default_port=8204)


if __name__ == "__main__":
    main()
