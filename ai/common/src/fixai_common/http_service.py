"""FastAPI conventions shared by agent services: health/readiness probes, correlation IDs and caller identity."""

from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response

from fixai_common import telemetry
from fixai_common.identity import (
    Principal,
    current_correlation_id,
    current_principal,
    principal_from_dev_headers,
    security_enabled,
)

PUBLIC_PATHS = frozenset({"/health/live", "/health/ready", "/metrics", "/openapi.json", "/docs"})
"""Probes, metrics (bounded labels, no payloads) and API docs; everything else needs an authenticated caller."""


def create_app(name: str, version: str, readiness: Callable[[], Awaitable[bool]] | None = None) -> FastAPI:
    telemetry.configure(name)
    app = FastAPI(title=name, version=version, docs_url="/docs", openapi_url="/openapi.json")

    @app.middleware("http")
    async def context(request: Request, call_next):  # type: ignore[no-untyped-def]
        correlation = request.headers.get("x-correlation-id") or str(uuid.uuid4())
        if not correlation.replace("-", "").replace(".", "").replace("_", "").isalnum() or len(correlation) > 128:
            correlation = str(uuid.uuid4())
        token_c = current_correlation_id.set(correlation)
        principal = resolve_principal(request)
        token_p = current_principal.set(principal)
        try:
            if principal is None and request.url.path not in PUBLIC_PATHS:
                return JSONResponse({"detail": "Authentication required"}, status_code=401)
            response = await call_next(request)
        finally:
            current_principal.reset(token_p)
            current_correlation_id.reset(token_c)
        response.headers["X-Correlation-Id"] = correlation
        return response

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        body, content_type = telemetry.metrics_response()
        return Response(body, media_type=content_type)

    @app.get("/health/ready")
    async def ready() -> JSONResponse:
        ok = True if readiness is None else await readiness()
        return JSONResponse({"status": "UP" if ok else "DOWN"}, status_code=200 if ok else 503)

    return app


def resolve_principal(request: Request) -> Principal | None:
    if security_enabled():
        from fixai_common.oidc import verify_bearer

        authorization = request.headers.get("authorization", "")
        return verify_bearer(authorization) if authorization.lower().startswith("bearer ") else None
    return principal_from_dev_headers({k.lower(): v for k, v in request.headers.items()})


def require_roles(*roles: str) -> Principal:
    principal = current_principal.get()
    if principal is None:
        raise HTTPException(status_code=401, detail="Authentication required")
    if not principal.has_any(set(roles)):
        raise HTTPException(status_code=403, detail="Insufficient permissions")
    return principal
