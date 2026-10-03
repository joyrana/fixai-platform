"""Typed async client for the Java platform APIs (certification, broker, workflow, simulator)."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from fixai_common.identity import ServiceIdentity, current_correlation_id


@dataclass(frozen=True)
class PlatformUrls:
    certification: str
    broker: str
    workflow: str
    simulator: str

    @classmethod
    def from_env(cls) -> PlatformUrls:
        return cls(
            certification=os.environ.get("CERTIFICATION_SERVICE_URL", "http://localhost:8083"),
            broker=os.environ.get("BROKER_SERVICE_URL", "http://localhost:8081"),
            workflow=os.environ.get("WORKFLOW_SERVICE_URL", "http://localhost:8084"),
            simulator=os.environ.get("FIX_SIMULATOR_ADMIN_URL", "http://localhost:8085"),
        )


class PlatformError(Exception):
    def __init__(self, status: int, detail: str, codes: list[str] | None = None) -> None:
        super().__init__(f"{status}: {detail}")
        self.status = status
        self.detail = detail
        self.codes = codes or []


class PlatformClient:
    def __init__(self, urls: PlatformUrls, identity: ServiceIdentity, timeout: float = 10.0,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.urls = urls
        self._identity = identity
        self._client = httpx.AsyncClient(timeout=timeout, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    def _headers(self, idempotency_key: str | None = None) -> dict[str, str]:
        headers = {"X-Correlation-Id": current_correlation_id.get() or str(uuid.uuid4()), **self._identity.headers()}
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    async def get(self, base: str, path: str, params: dict[str, Any] | None = None) -> Any:
        response = await self._client.get(base + path, params=params, headers=self._headers())
        return self._handle(response)

    async def post(self, base: str, path: str, body: Any | None = None, idempotency_key: str | None = None) -> Any:
        response = await self._client.post(base + path, json=body, headers=self._headers(idempotency_key))
        return self._handle(response)

    async def text(self, base: str, path: str) -> str:
        response = await self._client.get(base + path, headers=self._headers())
        if response.status_code >= 400:
            self._handle(response)
        return response.text

    @staticmethod
    def _handle(response: httpx.Response) -> Any:
        if response.status_code >= 400:
            try:
                problem = response.json()
            except ValueError:
                problem = {}
            raise PlatformError(response.status_code, str(problem.get("detail", response.reason_phrase)),
                                list(problem.get("codes", []) or problem.get("problems", [])))
        return response.json() if response.content else None
