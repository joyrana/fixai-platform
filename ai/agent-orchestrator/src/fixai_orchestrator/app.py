"""HTTP API for certification workflows.

POST /v1/workflows                  start (CERTIFICATION_ENGINEER, ADMIN)
GET  /v1/workflows/{id}             state, events, pending human decisions (+ AUDITOR)
POST /v1/workflows/{id}/resume      re-check approval after a reviewer acted (never approves)
POST /v1/workflows/{id}/cancel      stop before the next step
GET  /v1/workflows                  workflows started by this instance

POST /v1/assist/diagnose            fix-agent on a finished run (read-only)
POST /v1/assist/logs                log-analysis-agent on one scenario execution (read-only; no incident drafts)
POST /v1/assist/report-draft        report-agent narrative for a finished run (read-only)
POST /v1/assist/ask                 knowledge-agent answer with verified citations

Assist endpoints check the human caller's role; agents then act with their own service identity and tool
allow-lists, so document access is evaluated for the agent identity (never broader than AI_AGENT).

Checkpoints are kept in memory by default; set FIXAI_CHECKPOINT_PATH to persist them in SQLite so waiting workflows
survive restarts. Agents run in-process unless FIXAI_AGENT_MODE=http.
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import uvicorn
from fastapi import FastAPI, HTTPException
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import BaseModel, Field

from fixai_common.http_service import create_app, require_roles
from fixai_orchestrator.agents import AgentRunner, ToolRunner, http_agents, in_process_agents, tool_runner
from fixai_orchestrator.graph import WorkflowRequest
from fixai_orchestrator.service import WorkflowConflict, WorkflowNotFound, WorkflowService

VERSION = "1.0.0"
STARTERS = ("CERTIFICATION_ENGINEER", "ADMIN")
READERS = ("CERTIFICATION_ENGINEER", "ADMIN", "AUDITOR", "REVIEWER")
ASKERS = (*READERS, "BROKER_MANAGER")
RUN_ID = r"^[0-9a-f-]{36}$"


class DiagnoseBody(BaseModel):
    run_id: str = Field(pattern=RUN_ID)
    scenario_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9._-]{1,64}$")


class LogsBody(BaseModel):
    run_id: str = Field(pattern=RUN_ID)
    execution_id: str = Field(pattern=RUN_ID)


class ReportBody(BaseModel):
    run_id: str = Field(pattern=RUN_ID)


class AskBody(BaseModel):
    question: str = Field(min_length=3, max_length=1000)


def create_orchestrator_app(service: WorkflowService | None = None, agents: AgentRunner | None = None,
                            tools: ToolRunner | None = None) -> FastAPI:
    holder: dict[str, Any] = {}

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if service is not None:
            holder["service"] = service
            holder["agents"] = agents
            yield
            return
        runner = agents or (http_agents() if os.environ.get("FIXAI_AGENT_MODE") == "http" else in_process_agents())
        holder["agents"] = runner
        path = os.environ.get("FIXAI_CHECKPOINT_PATH")
        if path:
            from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

            async with AsyncSqliteSaver.from_conn_string(path) as saver:
                holder["service"] = WorkflowService(runner, tools or tool_runner(), saver)
                yield
        else:
            holder["service"] = WorkflowService(runner, tools or tool_runner(), InMemorySaver())
            yield

    app = create_app("agent-orchestrator", VERSION)
    app.router.lifespan_context = lifespan

    def svc() -> WorkflowService:
        return holder["service"]

    async def assist(agent: str, payload: dict[str, Any]) -> dict[str, Any]:
        runner: AgentRunner | None = holder.get("agents")
        if runner is None:
            raise HTTPException(status_code=503, detail="Agents are not configured")
        try:
            return await runner(agent, payload)
        except Exception as error:  # noqa: BLE001 - agent failures surface as a gateway error without internals
            raise HTTPException(status_code=502, detail=f"{agent} failed ({type(error).__name__})") from None

    @app.post("/v1/assist/diagnose")
    async def diagnose(body: DiagnoseBody) -> dict[str, Any]:
        require_roles(*READERS)
        return await assist("fix-agent", body.model_dump(exclude_none=True))

    @app.post("/v1/assist/logs")
    async def logs(body: LogsBody) -> dict[str, Any]:
        require_roles(*READERS)
        return await assist("log-analysis-agent", body.model_dump() | {"create_incident": False})

    @app.post("/v1/assist/report-draft")
    async def report_draft(body: ReportBody) -> dict[str, Any]:
        require_roles(*READERS)
        return await assist("report-agent", body.model_dump())

    @app.post("/v1/assist/ask")
    async def ask(body: AskBody) -> dict[str, Any]:
        require_roles(*ASKERS)
        return await assist("knowledge-agent", body.model_dump())

    @app.get("/v1/workflows")
    async def list_workflows() -> list[dict[str, Any]]:
        require_roles(*READERS)
        return await svc().list()

    @app.post("/v1/workflows", status_code=202)
    async def start(request: WorkflowRequest) -> dict[str, Any]:
        principal = require_roles(*STARTERS)
        workflow_id = await svc().start(request, principal.subject)
        return {"workflow_id": workflow_id}

    @app.get("/v1/workflows/{workflow_id}")
    async def get(workflow_id: str) -> dict[str, Any]:
        require_roles(*READERS)
        try:
            return await svc().get(workflow_id)
        except WorkflowNotFound:
            raise HTTPException(status_code=404, detail="Workflow not found") from None

    @app.post("/v1/workflows/{workflow_id}/resume", status_code=202)
    async def resume(workflow_id: str) -> dict[str, str]:
        require_roles(*STARTERS)
        try:
            await svc().resume(workflow_id)
        except WorkflowNotFound:
            raise HTTPException(status_code=404, detail="Workflow not found") from None
        except WorkflowConflict as conflict:
            raise HTTPException(status_code=409, detail=str(conflict)) from None
        return {"workflow_id": workflow_id, "status": "resumed"}

    @app.post("/v1/workflows/{workflow_id}/cancel", status_code=202)
    async def cancel(workflow_id: str) -> dict[str, str]:
        require_roles(*STARTERS)
        try:
            await svc().cancel(workflow_id)
        except WorkflowNotFound:
            raise HTTPException(status_code=404, detail="Workflow not found") from None
        except WorkflowConflict as conflict:
            raise HTTPException(status_code=409, detail=str(conflict)) from None
        return {"workflow_id": workflow_id, "status": "cancel-requested"}

    return app


app = create_orchestrator_app()


def main() -> None:
    uvicorn.run(app, host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8100")))  # noqa: S104
