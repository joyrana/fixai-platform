"""Benchmarks against a running stack (never against a broker: every run targets the synthetic simulator).

    python -m fixai_evals.cli benchmark --suite certification-throughput --certification-url http://localhost:8083 \
        --concurrency 8 --runs 40
    python -m fixai_evals.cli benchmark --suite end-to-end --certification-url http://localhost:8083 --runs 6

certification-throughput: N runs of a suite, at most `concurrency` in flight, measuring time from request to engine
verdict, throughput and failures. end-to-end: N AI workflows (plan -> run -> report) through the orchestrator,
measuring time to a drafted report. Results carry the same reproducibility metadata as evaluation runs.
"""

from __future__ import annotations

import asyncio
import os
import platform
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx

from fixai_evals import stats
from fixai_evals.run_record import git_sha

ENGINEER = {"X-Dev-User": "benchmark", "X-Dev-Roles": "CERTIFICATION_ENGINEER"}
FINISHED = {"COMPLETED", "CANCELLED", "ERROR"}


async def _certification_run(client: httpx.AsyncClient, suite: str, profile: str, budget_seconds: float) -> dict[str, Any]:
    started = time.perf_counter()
    headers = {**ENGINEER, "Idempotency-Key": f"bench-{uuid.uuid4().hex}"}
    response = await client.post("/api/v1/certification-runs", headers=headers,
                                 json={"suiteId": suite, "fixVersion": "FIX44",
                                       "target": {"type": "SIMULATOR", "simulatorProfile": profile}})
    if response.status_code not in (200, 202):
        return {"ok": False, "error": f"start {response.status_code}", "seconds": time.perf_counter() - started}
    run_id = response.json()["id"]
    deadline = started + budget_seconds
    while time.perf_counter() < deadline:
        run = (await client.get(f"/api/v1/certification-runs/{run_id}", headers=ENGINEER)).json()
        if run["status"] in FINISHED:
            return {"ok": run["status"] == "COMPLETED", "verdict": run["verdict"], "scenarios": run["scenariosTotal"],
                    "seconds": time.perf_counter() - started, "run_id": run_id}
        await asyncio.sleep(0.25)
    return {"ok": False, "error": "timeout", "seconds": time.perf_counter() - started, "run_id": run_id}


async def _workflow(client: httpx.AsyncClient, orchestrator: str, budget_seconds: float) -> dict[str, Any]:
    started = time.perf_counter()
    response = await client.post(f"{orchestrator}/v1/workflows", headers=ENGINEER, json={
        "fix_version": "FIX44", "objective": "Run a smoke test", "simulator_profile": "COMPLIANT", "poll_interval_seconds": 0.5})
    if response.status_code != 202:
        return {"ok": False, "error": f"start {response.status_code}", "seconds": time.perf_counter() - started}
    workflow_id = response.json()["workflow_id"]
    deadline = started + budget_seconds
    while time.perf_counter() < deadline:
        state = (await client.get(f"{orchestrator}/v1/workflows/{workflow_id}", headers=ENGINEER)).json()
        if state["phase"] in ("DONE", "FAILED"):
            return {"ok": state.get("outcome") == "REPORTED", "verdict": (state.get("report") or {}).get("verdict"),
                    "seconds": time.perf_counter() - started, "agent_calls": len(state.get("agent_runs", []))}
        await asyncio.sleep(0.25)
    return {"ok": False, "error": "timeout", "seconds": time.perf_counter() - started}


async def run(suite: str, certification_url: str, concurrency: int, runs: int, *, run_suite: str = "smoke",
              profile: str = "COMPLIANT", budget_seconds: float = 600.0) -> dict[str, Any]:
    orchestrator = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8100")
    limiter = asyncio.Semaphore(concurrency)
    started_at = datetime.now(UTC).isoformat()
    async with httpx.AsyncClient(base_url=certification_url, timeout=60) as client:
        async def one() -> dict[str, Any]:
            async with limiter:
                if suite == "certification-throughput":
                    return await _certification_run(client, run_suite, profile, budget_seconds)
                return await _workflow(client, orchestrator, budget_seconds)

        wall = time.perf_counter()
        results = await asyncio.gather(*(one() for _ in range(runs)))
        wall = time.perf_counter() - wall
    ok = [r for r in results if r["ok"]]
    seconds = [r["seconds"] for r in ok]
    metrics: dict[str, Any] = {
        "success": stats.proportion(len(ok), len(results)),
        "seconds_to_verdict" if suite == "certification-throughput" else "seconds_to_report": stats.percentiles(seconds),
        "wall_clock_seconds": round(wall, 2),
        "runs_per_minute": round(len(ok) / wall * 60, 2) if wall else 0.0,
        "verdicts": {v: sum(1 for r in ok if r.get("verdict") == v) for v in sorted({str(r.get("verdict")) for r in ok})},
        "errors": sorted({r["error"] for r in results if not r["ok"] and r.get("error")}),
    }
    if suite == "certification-throughput":
        scenarios = sum(r.get("scenarios", 0) for r in ok)
        metrics["scenarios_per_minute"] = round(scenarios / wall * 60, 2) if wall else 0.0
    return {"suite": suite, "started_at": started_at, "finished_at": datetime.now(UTC).isoformat(),
            "parameters": {"concurrency": concurrency, "runs": runs, "run_suite": run_suite, "profile": profile,
                           "certification_url": certification_url, "orchestrator_url": orchestrator},
            "environment": {"code_commit": git_sha(), "python": platform.python_version(), "platform": platform.platform(),
                            "cpu_count": os.cpu_count()},
            "metrics": metrics}
