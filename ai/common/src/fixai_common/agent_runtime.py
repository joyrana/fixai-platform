"""Client-side runtime for agents: allow-listed MCP tool calls with budgets, records and untrusted-output scanning.

Agents never call platform APIs directly; they act only through MCP tools, and only through the tools on their own
allow-list. Server-side policies remain the authoritative control - this layer adds least-privilege on the client and
produces the tool-call trace that evaluation and audit consume.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

from mcp.client import Client
from mcp.client.streamable_http import streamable_http_client
from mcp.server.mcpserver import MCPServer
from mcp.shared._httpx_utils import create_mcp_http_client

from fixai_common import guard
from fixai_common.contracts import ToolCallRecord
from fixai_common.identity import Principal, ServiceIdentity, current_correlation_id, current_principal
from fixai_common.mcp.policy import digest


class ToolCallFailed(Exception):
    def __init__(self, tool: str, message: str) -> None:
        super().__init__(f"{tool}: {message}")
        self.tool = tool
        self.message = message


class ToolBudgetExceeded(ToolCallFailed):
    pass


@dataclass
class ToolGateway:
    """Routes tool calls to MCP servers (by URL, or in-process server instances for tests)."""

    agent: str
    servers: dict[str, MCPServer | str]
    """Tool name -> server hosting it."""
    allowlist: frozenset[str]
    identity: ServiceIdentity
    max_calls: int = 20
    timeout_seconds: float = 30.0
    records: list[ToolCallRecord] = field(default_factory=list)
    injection_findings: list[str] = field(default_factory=list)

    async def call(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if tool not in self.allowlist:
            self._record(tool, arguments, "denied", 0, "not on agent allow-list")
            raise ToolCallFailed(tool, "tool is not on this agent's allow-list")
        if len(self.records) >= self.max_calls:
            self._record(tool, arguments, "denied", 0, "tool-call budget exhausted")
            raise ToolBudgetExceeded(tool, f"tool-call budget of {self.max_calls} exhausted")
        target = self.servers.get(tool)
        if target is None:
            self._record(tool, arguments, "error", 0, "no server for tool")
            raise ToolCallFailed(tool, "no MCP server hosts this tool")
        started = time.perf_counter()
        try:
            result = await self._invoke(target, tool, arguments)
        except TimeoutError:
            self._record(tool, arguments, "timeout", started, "timeout")
            raise ToolCallFailed(tool, "timed out") from None
        if result.is_error:
            message = " ".join(getattr(c, "text", "") for c in result.content)[:300]
            self._record(tool, arguments, "error", started, message)
            raise ToolCallFailed(tool, message or "tool error")
        self._record(tool, arguments, "ok", started, None)
        payload = result.structured_content or {}
        self._scan(tool, payload)
        return payload

    async def _invoke(self, target: MCPServer | str, tool: str, arguments: dict[str, Any]) -> Any:
        if isinstance(target, MCPServer):
            # In-process: identity flows through the context variable.
            token = current_principal.set(Principal(self.identity.name, frozenset(self.identity.roles), self.agent))
            try:
                async with Client(target, read_timeout_seconds=self.timeout_seconds) as client:
                    return await client.call_tool(tool, arguments)
            finally:
                current_principal.reset(token)
        headers = {**self.identity.headers(), "X-Fixai-Agent": self.agent,
                   "X-Correlation-Id": current_correlation_id.get() or ""}
        transport = streamable_http_client(target, http_client=create_mcp_http_client(headers=headers))
        async with Client(transport, read_timeout_seconds=self.timeout_seconds) as client:
            return await client.call_tool(tool, arguments)

    def _record(self, tool: str, arguments: dict[str, Any], status: str, started: float, error: str | None) -> None:
        duration = 0 if not started else int((time.perf_counter() - started) * 1000)
        self.records.append(ToolCallRecord(tool=tool, status=status, duration_ms=duration,  # type: ignore[arg-type]
                                           arguments_sha256=digest(arguments), error=error))

    def _scan(self, tool: str, payload: dict[str, Any]) -> None:
        """Tool outputs are untrusted (they can carry counterparty-controlled FIX text or document content)."""
        for finding in guard.scan(str(payload)):
            self.injection_findings.append(f"{tool}:{finding.pattern}")
