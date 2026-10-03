"""How the orchestrator reaches agents and tools.

In-process mode imports the agent packages and invokes them directly (the default for a single deployable and for
evaluation); HTTP mode calls each agent service's `/v1/invoke` with the orchestrator's service identity. Either way
the agents act only through their own MCP tool allow-lists. The orchestrator itself may call one tool: run status.
"""

from __future__ import annotations

import os
from collections.abc import Awaitable, Callable
from typing import Any

import httpx
from mcp.server.mcpserver import MCPServer

from fixai_common.agent_runtime import ToolGateway
from fixai_common.agent_service import AgentSpec, invoke, servers_from_env
from fixai_common.identity import ServiceIdentity, current_correlation_id
from fixai_common.llm.base import LLMProvider

NAME = "agent-orchestrator"
AgentRunner = Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]
ToolRunner = Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]
ORCHESTRATOR_TOOLS = frozenset({"get_certification_status"})
AGENT_PORTS = {"fix-agent": 8101, "certification-agent": 8102, "knowledge-agent": 8103, "log-analysis-agent": 8104,
               "report-agent": 8105, "human-review-agent": 8106}


def specs() -> dict[str, AgentSpec[Any, Any]]:
    from fixai_certification_agent.agent import SPEC as certification
    from fixai_fix_agent.agent import SPEC as fix
    from fixai_human_review_agent.agent import SPEC as review
    from fixai_log_analysis_agent.agent import SPEC as logs
    from fixai_report_agent.agent import SPEC as report

    return {s.name: s for s in (certification, fix, review, logs, report)}


def in_process_agents(servers: dict[str, MCPServer | str] | None = None,
                      llm_for: Callable[[AgentSpec[Any, Any]], LLMProvider | None] | None = None) -> AgentRunner:
    """`servers` maps tool name -> server for every tool any agent may use (None: URLs from the environment)."""
    registry = specs()

    async def run(agent: str, payload: dict[str, Any]) -> dict[str, Any]:
        spec = registry[agent]
        agent_servers = ({t: servers[t] for t in spec.allowlist if t in servers} if servers is not None
                         else servers_from_env(spec.allowlist))
        result = await invoke(spec, spec.input_model.model_validate(payload), agent_servers,
                              llm_for(spec) if llm_for else None)
        return result.model_dump(mode="json")

    return run


def http_agents(urls: dict[str, str] | None = None, timeout: float = 180.0) -> AgentRunner:
    identity = ServiceIdentity(NAME, ("SERVICE",))
    resolved = {name: (urls or {}).get(name) or os.environ.get(f"{name.upper().replace('-', '_')}_URL",
                                                                f"http://localhost:{port}")
                for name, port in AGENT_PORTS.items()}

    async def run(agent: str, payload: dict[str, Any]) -> dict[str, Any]:
        headers = {**identity.headers(), "X-Correlation-Id": current_correlation_id.get() or ""}
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(f"{resolved[agent]}/v1/invoke", json=payload, headers=headers)
            response.raise_for_status()
            return response.json()

    return run


def tool_runner(servers: dict[str, MCPServer | str] | None = None, max_calls: int = 1000) -> ToolRunner:
    def gateway() -> ToolGateway:
        hosted = ({t: servers[t] for t in ORCHESTRATOR_TOOLS if t in servers} if servers is not None
                  else servers_from_env(ORCHESTRATOR_TOOLS))
        return ToolGateway(agent=NAME, servers=hosted, allowlist=ORCHESTRATOR_TOOLS,
                           identity=ServiceIdentity(NAME, ("AI_AGENT",)), max_calls=max_calls)

    async def call(tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        # A fresh gateway per call: the graph's step budget bounds polling, and records are not kept across steps.
        return await gateway().call(tool, arguments)

    return call
