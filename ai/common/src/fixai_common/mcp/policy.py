"""Server-side governance for MCP tools.

Every tool declares a `ToolPolicy`: capability class, roles allowed, per-agent allow-list, timeout, rate limit and
idempotency behaviour. `governed_tool` enforces the policy on the server - an LLM instruction or a client-side check
is never the control - and emits an audit record for every call (allowed, denied or failed). Arguments are recorded
only as a SHA-256 digest.
"""

from __future__ import annotations

import functools
import hashlib
import json
import logging
import time
from collections import defaultdict, deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, ParamSpec, TypeVar

import anyio
from mcp.server.mcpserver.exceptions import ToolError

from fixai_common.identity import Principal, current_principal, principal_from_dev_headers, security_enabled

AUDIT = logging.getLogger("fixai.mcp.audit")
P = ParamSpec("P")
R = TypeVar("R")


class Capability(StrEnum):
    READ = "READ"
    SIMULATED_WRITE = "SIMULATED_WRITE"
    PRIVILEGED_WRITE = "PRIVILEGED_WRITE"


@dataclass(frozen=True)
class ToolPolicy:
    name: str
    capability: Capability
    roles: frozenset[str]
    timeout_seconds: float = 15.0
    rate_per_minute: int = 60
    idempotent: bool = True
    allowed_agents: frozenset[str] | None = None
    """None means any authenticated caller holding one of `roles`; otherwise only the listed agents."""

    def describe(self) -> str:
        agents = "any agent" if self.allowed_agents is None else ", ".join(sorted(self.allowed_agents))
        return (f"[capability={self.capability} roles={','.join(sorted(self.roles))} agents={agents} "
                f"timeout={self.timeout_seconds:g}s idempotent={self.idempotent}]")


@dataclass
class AuditRecord:
    tool: str
    capability: str
    subject: str
    agent: str | None
    outcome: str
    duration_ms: int
    arguments_sha256: str
    error: str | None = None


@dataclass
class ToolRegistry:
    """Holds policies for documentation/contract export and the in-memory audit trail used in tests."""

    server: str
    policies: dict[str, ToolPolicy] = field(default_factory=dict)
    audit_trail: deque[AuditRecord] = field(default_factory=lambda: deque(maxlen=5000))
    _calls: dict[tuple[str, str], deque[float]] = field(default_factory=lambda: defaultdict(deque))

    def check_rate(self, policy: ToolPolicy, subject: str) -> bool:
        window = self._calls[(policy.name, subject)]
        now = time.monotonic()
        while window and now - window[0] > 60:
            window.popleft()
        if len(window) >= policy.rate_per_minute:
            return False
        window.append(now)
        return True

    def record(self, record: AuditRecord) -> None:
        self.audit_trail.append(record)
        AUDIT.info("mcp_tool_call server=%s tool=%s capability=%s subject=%s agent=%s outcome=%s duration_ms=%d args_sha256=%s",
                   self.server, record.tool, record.capability, record.subject, record.agent, record.outcome,
                   record.duration_ms, record.arguments_sha256)


def digest(arguments: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps(arguments, sort_keys=True, default=str).encode()).hexdigest()


def principal_for(ctx: Any) -> Principal | None:
    """Identity of the current MCP call: from the HTTP request headers when served over HTTP, otherwise from the
    context variable set by an in-process host (tests, embedded use)."""
    headers = getattr(ctx, "headers", None) if ctx is not None else None
    if headers:
        lowered = {k.lower(): v for k, v in headers.items()}
        if security_enabled():
            from fixai_common.oidc import verify_bearer

            authorization = lowered.get("authorization", "")
            return verify_bearer(authorization) if authorization.lower().startswith("bearer ") else None
        return principal_from_dev_headers(lowered)
    return current_principal.get()


def governed_tool(registry: ToolRegistry, policy: ToolPolicy) -> Callable[[Callable[P, Awaitable[R]]], Callable[P, Awaitable[R]]]:
    """Wraps an async tool implementation with authorisation, allow-listing, rate limiting, timeout and auditing."""

    registry.policies[policy.name] = policy

    def decorator(func: Callable[P, Awaitable[R]]) -> Callable[P, Awaitable[R]]:
        @functools.wraps(func)
        async def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            principal = principal_for(kwargs.get("ctx"))
            arguments = {k: v for k, v in kwargs.items() if k != "ctx"}
            started = time.perf_counter()

            def audit(outcome: str, error: str | None = None) -> None:
                registry.record(AuditRecord(policy.name, policy.capability.value,
                                            principal.subject if principal else "anonymous",
                                            principal.agent if principal else None, outcome,
                                            int((time.perf_counter() - started) * 1000), digest(arguments), error))

            if principal is None:
                audit("denied", "unauthenticated")
                raise ToolError("Authentication required")
            if not principal.has_any(policy.roles):
                audit("denied", "role")
                raise ToolError(f"Caller is not permitted to use {policy.name}")
            if policy.allowed_agents is not None and principal.agent not in policy.allowed_agents:
                audit("denied", "agent_not_allowlisted")
                raise ToolError(f"Agent is not allow-listed for {policy.name}")
            if not registry.check_rate(policy, principal.subject):
                audit("denied", "rate_limited")
                raise ToolError(f"Rate limit exceeded for {policy.name}")
            try:
                with anyio.fail_after(policy.timeout_seconds):
                    result = await func(*args, **kwargs)
            except TimeoutError:
                audit("timeout")
                raise ToolError(f"{policy.name} timed out after {policy.timeout_seconds:g}s") from None
            except ToolError as error:
                audit("error", str(error)[:200])
                raise
            except Exception as error:  # noqa: BLE001 - never leak internals to the model
                audit("error", type(error).__name__)
                raise ToolError(f"{policy.name} failed") from None
            audit("ok")
            return result

        return wrapper

    return decorator
