"""Helpers to build and serve MCP servers consistently (streamable HTTP, health endpoint, tool-contract export)."""

from __future__ import annotations

import json
import os
from typing import Any

import uvicorn
from mcp.server.mcpserver import MCPServer
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from fixai_common import telemetry
from fixai_common.mcp.policy import ToolRegistry


def build_server(name: str, version: str, instructions: str) -> tuple[MCPServer, ToolRegistry]:
    server = MCPServer(name=name, version=version, instructions=instructions)
    registry = ToolRegistry(server=name)

    @server.custom_route("/health/live", methods=["GET"])
    async def live(_: Request) -> JSONResponse:
        return JSONResponse({"status": "UP"})

    @server.custom_route("/metrics", methods=["GET"])
    async def metrics(_: Request) -> Response:
        body, content_type = telemetry.metrics_response()
        return Response(body, media_type=content_type)

    @server.custom_route("/health/ready", methods=["GET"])
    async def ready(_: Request) -> JSONResponse:
        return JSONResponse({"status": "UP", "tools": len(registry.policies)})

    return server, registry


async def contract(server: MCPServer, registry: ToolRegistry) -> dict[str, Any]:
    """Machine-readable tool contract: schemas plus governance metadata. Checked into docs and verified in CI."""
    tools = await server.list_tools()
    entries = []
    for tool in sorted(tools, key=lambda t: t.name):
        policy = registry.policies[tool.name]
        entries.append({
            "name": tool.name,
            "description": tool.description,
            "capability": policy.capability.value,
            "roles": sorted(policy.roles),
            "allowedAgents": None if policy.allowed_agents is None else sorted(policy.allowed_agents),
            "timeoutSeconds": policy.timeout_seconds,
            "ratePerMinute": policy.rate_per_minute,
            "idempotent": policy.idempotent,
            "inputSchema": tool.input_schema if hasattr(tool, "input_schema") else tool.inputSchema,
            "outputSchema": getattr(tool, "output_schema", None) or getattr(tool, "outputSchema", None),
        })
    return {"server": registry.server, "tools": entries}


def serve(server: MCPServer, default_port: int) -> None:
    telemetry.configure(server.name)
    host = os.environ.get("MCP_HOST", "0.0.0.0")  # noqa: S104 - container binding
    port = int(os.environ.get("MCP_PORT", default_port))
    app = server.streamable_http_app(stateless_http=True, json_response=True, host=host)
    uvicorn.run(app, host=host, port=port, log_level=os.environ.get("LOG_LEVEL", "info"))


def dump_contract(server: MCPServer, registry: ToolRegistry) -> str:
    import anyio

    return json.dumps(anyio.run(contract, server, registry), indent=2, sort_keys=True)
