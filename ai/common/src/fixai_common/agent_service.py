"""Scaffolding shared by every agent service: typed invoke endpoint, contract endpoint, tool gateway wiring, LLM
provider selection, budgets, output validation with deterministic fallback, and run metadata.

Agent behaviour (what to look at and how to reason) lives in each agent package; this module only standardises the
envelope so that orchestration, evaluation and audit see every agent the same way.
"""

from __future__ import annotations

import os
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Generic, TypeVar

import anyio
from fastapi import FastAPI, HTTPException
from mcp.server.mcpserver import MCPServer
from pydantic import BaseModel

from fixai_common import telemetry
from fixai_common.agent_runtime import ToolCallFailed, ToolGateway
from fixai_common.contracts import AgentResult, AgentRunMetadata
from fixai_common.http_service import create_app, require_roles
from fixai_common.identity import ServiceIdentity, current_correlation_id
from fixai_common.llm.base import LLMProvider, LLMRefusal, LLMRequest, Synthesizer, provider_from_env

In = TypeVar("In", bound=BaseModel)
Out = TypeVar("Out", bound=BaseModel)

# Tool name -> MCP server environment variable holding its URL.
TOOL_SERVERS = {
    "inspect_message": "FIX_MCP_URL", "validate_fix_configuration": "FIX_MCP_URL", "inspect_session": "FIX_MCP_URL",
    "retrieve_session_events": "FIX_MCP_URL", "replay_test_fixture": "FIX_MCP_URL",
    "list_test_scenarios": "CERTIFICATION_MCP_URL", "validate_test_plan": "CERTIFICATION_MCP_URL",
    "start_simulated_certification": "CERTIFICATION_MCP_URL", "get_certification_status": "CERTIFICATION_MCP_URL",
    "retrieve_certification_evidence": "CERTIFICATION_MCP_URL",
    "search_fix_documentation": "KNOWLEDGE_MCP_URL", "retrieve_document": "KNOWLEDGE_MCP_URL",
    "inspect_citations": "KNOWLEDGE_MCP_URL",
    "inspect_service_health": "OPERATIONS_MCP_URL", "retrieve_sanitized_logs": "OPERATIONS_MCP_URL",
    "create_incident_draft": "OPERATIONS_MCP_URL", "request_human_approval": "OPERATIONS_MCP_URL",
    "get_approval_status": "OPERATIONS_MCP_URL",
}
DEFAULT_MCP_URLS = {"FIX_MCP_URL": "http://localhost:8202/mcp", "CERTIFICATION_MCP_URL": "http://localhost:8201/mcp",
                    "KNOWLEDGE_MCP_URL": "http://localhost:8203/mcp", "OPERATIONS_MCP_URL": "http://localhost:8204/mcp"}


def servers_from_env(allowlist: frozenset[str]) -> dict[str, MCPServer | str]:
    return {tool: os.environ.get(TOOL_SERVERS[tool], DEFAULT_MCP_URLS[TOOL_SERVERS[tool]]) for tool in allowlist}


@dataclass
class AgentContext:
    spec: AgentSpec[Any, Any]
    tools: ToolGateway
    llm: LLMProvider
    deadline: float
    usage: dict[str, float] = field(default_factory=lambda: {"input": 0, "output": 0, "cache": 0, "cost": 0.0})
    llm_fallback_used: bool = False
    notes: list[str] = field(default_factory=list)

    async def tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        return await self.tools.call(name, arguments)

    def note(self, text: str) -> None:
        """Concise decision summary entries (never raw model reasoning)."""
        self.notes.append(text[:200])

    async def generate(self, request: LLMRequest, validate: Callable[[BaseModel], list[str]]) -> BaseModel:
        """Asks the provider for structured output; if it refuses, fails or produces output that the validator rejects
        (e.g. cites evidence it was not given), returns the deterministic synthesizer output instead."""
        fallback = self.spec.synthesizers[request.task]
        try:
            with telemetry.span("gen_ai.generate", **{"gen_ai.system": self.llm.name, "gen_ai.request.model": self.llm.model,
                                                     "fixai.task": request.task}):
                output, usage = await self.llm.generate(request)
            self.usage["input"] += usage.input_tokens
            self.usage["output"] += usage.output_tokens
            self.usage["cache"] += usage.cache_read_tokens
            self.usage["cost"] += usage.estimated_cost_usd
            problems = validate(output)
            if not problems:
                return output
            self.note(f"model output rejected by validator: {'; '.join(problems)[:150]}")
        except LLMRefusal as refusal:
            self.note(f"model declined ({refusal}); deterministic output used")
        except Exception as error:  # noqa: BLE001 - provider outages degrade to deterministic output
            self.note(f"model unavailable ({type(error).__name__}); deterministic output used")
        self.llm_fallback_used = True
        result = fallback(request)
        if hasattr(result, "__await__"):
            result = await result  # type: ignore[misc]
        return request.output_model.model_validate(result.model_dump())


@dataclass
class AgentSpec(Generic[In, Out]):
    name: str
    version: str
    prompt_version: str
    input_model: type[In]
    output_model: type[Out]
    allowlist: frozenset[str]
    run: Callable[[In, AgentContext], Awaitable[Out]]
    synthesizers: dict[str, Synthesizer]
    caller_roles: frozenset[str] = frozenset({"AI_AGENT", "SERVICE", "CERTIFICATION_ENGINEER", "ADMIN"})
    max_tool_calls: int = 20
    timeout_seconds: float = 120.0
    description: str = ""


async def invoke(spec: AgentSpec[In, Out], payload: In, servers: dict[str, MCPServer | str] | None = None,
                 llm: LLMProvider | None = None) -> AgentResult[Out]:
    """Runs one agent invocation within its tool budget and wall-clock timeout."""
    started = time.perf_counter()
    gateway = ToolGateway(agent=spec.name, servers=servers if servers is not None else servers_from_env(spec.allowlist),
                          allowlist=spec.allowlist, identity=ServiceIdentity(spec.name, ("AI_AGENT",)),
                          max_calls=spec.max_tool_calls)
    provider = llm or provider_from_env(spec.synthesizers)
    context = AgentContext(spec=spec, tools=gateway, llm=provider, deadline=time.monotonic() + spec.timeout_seconds)
    outcome = "error"
    try:
        with telemetry.span("fixai.agent.invoke", **{"fixai.agent": spec.name, "fixai.agent.version": spec.version,
                                                    "fixai.prompt.version": spec.prompt_version}):
            with anyio.fail_after(spec.timeout_seconds):
                output = await spec.run(payload, context)
        outcome = "ok"
    except TimeoutError:
        outcome = "timeout"
        raise
    finally:
        telemetry.AGENT_INVOCATIONS.labels(spec.name, outcome).inc()
        telemetry.AGENT_LATENCY.labels(spec.name).observe(time.perf_counter() - started)
    telemetry.AGENT_TOKENS.labels(spec.name, "input").inc(context.usage["input"])
    telemetry.AGENT_TOKENS.labels(spec.name, "output").inc(context.usage["output"])
    telemetry.AGENT_COST.labels(spec.name).inc(context.usage["cost"])
    if context.llm_fallback_used:
        telemetry.AGENT_LLM_FALLBACKS.labels(spec.name).inc()
    if gateway.injection_findings:
        telemetry.AGENT_INJECTION_FINDINGS.labels(spec.name).inc(len(gateway.injection_findings))
    if gateway.injection_findings:
        context.note(f"untrusted tool output contained instruction-like text ({len(gateway.injection_findings)} "
                     "findings); treated as data")
    metadata = AgentRunMetadata(
        agent=spec.name, agent_version=spec.version, prompt_version=spec.prompt_version, provider=provider.name,
        model=provider.model, correlation_id=current_correlation_id.get() or "", input_tokens=int(context.usage["input"]),
        output_tokens=int(context.usage["output"]), cache_read_tokens=int(context.usage["cache"]),
        estimated_cost_usd=round(context.usage["cost"], 6), latency_ms=int((time.perf_counter() - started) * 1000),
        tool_calls=gateway.records, llm_fallback_used=context.llm_fallback_used,
        decision_summary="; ".join(context.notes)[:1000])
    return AgentResult[spec.output_model](output=output, metadata=metadata)  # type: ignore[name-defined]


def create_agent_app(spec: AgentSpec[In, Out], servers: dict[str, MCPServer | str] | None = None,
                     llm: LLMProvider | None = None) -> FastAPI:
    app = create_app(spec.name, spec.version)
    input_model, output_model = spec.input_model, spec.output_model

    @app.post("/v1/invoke", response_model=AgentResult[output_model], summary=spec.description or spec.name)  # type: ignore[valid-type]
    async def invoke_endpoint(payload: input_model) -> AgentResult[Out]:  # type: ignore[valid-type]
        require_roles(*spec.caller_roles)
        try:
            return await invoke(spec, payload, servers, llm)
        except ToolCallFailed as error:
            raise HTTPException(status_code=502, detail=f"Tool failure: {error.tool}") from None
        except TimeoutError:
            raise HTTPException(status_code=504, detail="Agent timed out") from None

    @app.get("/v1/contract")
    async def contract() -> dict[str, Any]:
        return {"agent": spec.name, "version": spec.version, "promptVersion": spec.prompt_version,
                "allowedTools": sorted(spec.allowlist), "maxToolCalls": spec.max_tool_calls,
                "timeoutSeconds": spec.timeout_seconds, "callerRoles": sorted(spec.caller_roles),
                "input": input_model.model_json_schema(), "output": output_model.model_json_schema()}

    return app


def evidence_refs_in(model: BaseModel) -> set[str]:
    """All EvidenceRef.ref values anywhere in a model (used by validators to reject fabricated references)."""
    found: set[str] = set()

    def walk(value: Any) -> None:
        if isinstance(value, BaseModel):
            if value.__class__.__name__ == "EvidenceRef":
                found.add(value.ref)  # type: ignore[attr-defined]
            for item in value.__dict__.values():
                walk(item)
        elif isinstance(value, list | tuple):
            for item in value:
                walk(item)

    walk(model)
    return found


__all__ = ["AgentContext", "AgentSpec", "create_agent_app", "evidence_refs_in", "invoke", "servers_from_env"]
