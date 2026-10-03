"""Test support: run an agent's logic against stubbed tool responses, with the real AgentContext and offline provider.

Integration with real MCP servers and governance is covered by the evaluation suites; these stubs keep unit tests of
agent decision logic fast and independent of any server.
"""

from __future__ import annotations

import inspect
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from fixai_common.agent_runtime import ToolCallFailed, ToolGateway
from fixai_common.agent_service import AgentContext, AgentSpec
from fixai_common.identity import ServiceIdentity
from fixai_common.llm.base import LLMProvider
from fixai_common.llm.offline import OfflineProvider

ToolStub = Callable[[dict[str, Any]], dict[str, Any] | Awaitable[dict[str, Any]]] | dict[str, Any] | Exception


@dataclass
class StubGateway(ToolGateway):
    stubs: dict[str, ToolStub] = field(default_factory=dict)
    calls: list[tuple[str, dict[str, Any]]] = field(default_factory=list)

    async def call(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if tool not in self.allowlist:
            raise ToolCallFailed(tool, "tool is not on this agent's allow-list")
        self.calls.append((tool, arguments))
        stub = self.stubs.get(tool)
        if stub is None:
            raise ToolCallFailed(tool, "no stub")
        if isinstance(stub, Exception):
            raise ToolCallFailed(tool, str(stub))
        result = stub(arguments) if callable(stub) else stub
        if inspect.isawaitable(result):
            result = await result
        return result  # type: ignore[return-value]


def context_for(spec: AgentSpec[Any, Any], stubs: dict[str, ToolStub],
                llm: LLMProvider | None = None) -> tuple[AgentContext, StubGateway]:
    gateway = StubGateway(agent=spec.name, servers={}, allowlist=spec.allowlist,
                          identity=ServiceIdentity(spec.name, ("AI_AGENT",)), stubs=stubs)
    context = AgentContext(spec=spec, tools=gateway, llm=llm or OfflineProvider(spec.synthesizers),
                           deadline=time.monotonic() + spec.timeout_seconds)
    return context, gateway
