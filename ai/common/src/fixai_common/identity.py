"""Caller identity for agents and MCP servers.

With `FIXAI_SECURITY_ENABLED=true` callers must present an OIDC bearer token (validated against the issuer's JWKS and
audience); otherwise the local development identity headers are used, mirroring the Java services.
"""

from __future__ import annotations

import contextvars
import os
from dataclasses import dataclass, field

KNOWN_ROLES = frozenset({"ADMIN", "BROKER_MANAGER", "CERTIFICATION_ENGINEER", "REVIEWER", "AUDITOR", "SERVICE", "AI_AGENT"})


@dataclass(frozen=True)
class Principal:
    subject: str
    roles: frozenset[str] = field(default_factory=frozenset)
    agent: str | None = None
    tenant: str = "platform"

    def has_any(self, roles: set[str] | frozenset[str]) -> bool:
        return bool(self.roles & roles)


current_principal: contextvars.ContextVar[Principal | None] = contextvars.ContextVar("fixai_principal", default=None)
current_correlation_id: contextvars.ContextVar[str | None] = contextvars.ContextVar("fixai_correlation", default=None)


def principal_from_dev_headers(headers: dict[str, str]) -> Principal:
    user = headers.get("x-dev-user") or "local-developer"
    roles_header = headers.get("x-dev-roles")
    roles = (frozenset(r.strip() for r in roles_header.split(",")) & KNOWN_ROLES) if roles_header else frozenset({"ADMIN"})
    return Principal(subject=user[:64], roles=roles, agent=headers.get("x-fixai-agent"),
                     tenant=(headers.get("x-dev-tenant") or "platform")[:64])


def security_enabled() -> bool:
    return os.environ.get("FIXAI_SECURITY_ENABLED", "false").lower() == "true"


@dataclass(frozen=True)
class ServiceIdentity:
    """Credentials this process uses towards platform services. User tokens are never forwarded."""

    name: str
    roles: tuple[str, ...]

    def headers(self) -> dict[str, str]:
        if security_enabled():
            token = os.environ.get("FIXAI_SERVICE_TOKEN")
            if not token:
                raise RuntimeError("FIXAI_SERVICE_TOKEN is required when security is enabled")
            return {"Authorization": f"Bearer {token}"}
        return {"X-Dev-User": self.name, "X-Dev-Roles": ",".join(self.roles)}
