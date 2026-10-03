"""Workflow service: starts, inspects, resumes and cancels checkpointed certification workflows."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.types import Command

from fixai_common import telemetry
from fixai_orchestrator.agents import AgentRunner, ToolRunner
from fixai_orchestrator.graph import WorkflowRequest, build_graph, recursion_limit


class WorkflowNotFound(Exception):
    pass


class WorkflowConflict(Exception):
    pass


class WorkflowService:
    def __init__(self, agents: AgentRunner, tools: ToolRunner, checkpointer: BaseCheckpointSaver,
                 sleep: Callable[[float], Awaitable[None]] = asyncio.sleep) -> None:
        self._cancelled: set[str] = set()
        self.graph = build_graph(agents, tools, sleep, self._cancelled.__contains__).compile(checkpointer=checkpointer)
        self._tasks: dict[str, asyncio.Task[Any]] = {}
        self._index: dict[str, dict[str, Any]] = {}
        """Workflows started by this process (listing); state itself lives in the checkpointer."""

    def _config(self, workflow_id: str, request: WorkflowRequest) -> dict[str, Any]:
        return {"configurable": {"thread_id": workflow_id}, "recursion_limit": recursion_limit(request)}

    async def start(self, request: WorkflowRequest, requested_by: str, wait: bool = False) -> str:
        workflow_id = str(uuid.uuid4())
        self._index[workflow_id] = {"workflow_id": workflow_id, "requested_by": requested_by,
                                    "objective": request.objective, "fix_version": request.fix_version,
                                    "external": request.external_certification is not None,
                                    "created_at": datetime.now(UTC).isoformat()}
        state = {"workflow_id": workflow_id, "requested_by": requested_by, "request": request.model_dump(mode="json"),
                 "cancel_requested": False, "outcome": None, "events": [f"workflow started by {requested_by}"],
                 "agent_runs": []}
        await self._run(workflow_id, state, self._config(workflow_id, request), wait)
        return workflow_id

    async def _run(self, workflow_id: str, payload: Any, config: dict[str, Any], wait: bool) -> None:
        async def execute() -> None:
            with telemetry.span("fixai.workflow.step", **{"fixai.workflow.id": workflow_id}):
                await self.graph.ainvoke(payload, config)

        task = asyncio.create_task(execute(), name=f"workflow-{workflow_id}")
        self._tasks[workflow_id] = task
        if wait:
            await task

    async def get(self, workflow_id: str) -> dict[str, Any]:
        snapshot = await self.graph.aget_state({"configurable": {"thread_id": workflow_id}})
        task = self._tasks.get(workflow_id)
        if not snapshot.values:
            if task is not None and not task.done():
                return {"workflow_id": workflow_id, "phase": "STARTING", "next": [], "interrupts": [], "error": None}
            raise WorkflowNotFound(workflow_id)
        interrupts = [i.value for t in snapshot.tasks for i in t.interrupts]
        error = None
        if task is not None and task.done() and not task.cancelled() and task.exception() is not None:
            error = f"{type(task.exception()).__name__}: {str(task.exception())[:300]}"
        values = dict(snapshot.values)
        phase = ("FAILED" if error else "RUNNING" if task is not None and not task.done()
                 else "WAITING_FOR_HUMAN" if interrupts else "DONE" if not snapshot.next else "PAUSED")
        return {"workflow_id": workflow_id, "phase": phase, "next": list(snapshot.next), "interrupts": interrupts,
                "error": error, **values}

    async def list(self, limit: int = 50) -> list[dict[str, Any]]:
        rows = []
        for entry in sorted(self._index.values(), key=lambda e: e["created_at"], reverse=True)[:limit]:
            state = await self.get(entry["workflow_id"])
            rows.append(entry | {"phase": state["phase"], "outcome": state.get("outcome"), "run_id": state.get("run_id")})
        return rows

    async def resume(self, workflow_id: str, wait: bool = False) -> None:
        """Re-checks the approval after a reviewer has acted. Resuming never approves anything."""
        state = await self.get(workflow_id)
        if state["phase"] != "WAITING_FOR_HUMAN":
            raise WorkflowConflict(f"workflow is {state['phase']}, not waiting for a human decision")
        request = WorkflowRequest.model_validate(state["request"])
        await self._run(workflow_id, Command(resume={"action": "check"}), self._config(workflow_id, request), wait)

    async def cancel(self, workflow_id: str, wait: bool = False) -> None:
        state = await self.get(workflow_id)
        if state["phase"] == "DONE":
            raise WorkflowConflict("workflow already finished")
        request = WorkflowRequest.model_validate(state["request"])
        config = self._config(workflow_id, request)
        if state["phase"] == "WAITING_FOR_HUMAN":
            await self._run(workflow_id, Command(resume={"action": "cancel"}), config, wait)
            return
        if state["phase"] in ("RUNNING", "STARTING"):
            # The router reads the flag before the next step.
            self._cancelled.add(workflow_id)
            if wait and (task := self._tasks.get(workflow_id)) is not None:
                await task
            return
        # Paused or failed with nothing executing: record the cancellation directly.
        await self.graph.aupdate_state(config, {"cancel_requested": True, "outcome": "CANCELLED",
                                                "events": ["workflow cancelled"]})
