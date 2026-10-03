"""Evaluation suites. Each suite returns a RunRecord with per-case results and aggregate metrics."""

from __future__ import annotations

import json
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from fixai_common.agent_service import invoke
from fixai_common.llm.base import LLMProvider, provider_from_env
from fixai_evals import stats
from fixai_evals.replay import certification_replay, load_fixture
from fixai_evals.run_record import RunRecord, dataset_checksum, git_sha

DATASETS = Path(__file__).resolve().parents[1] / "datasets"
SuiteFn = Callable[..., Awaitable[RunRecord]]


def _cases(path: Path, split: str, limit_per_category: int | None = None) -> list[dict[str, Any]]:
    cases = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    cases = [c for c in cases if split == "all" or c["split"] == split]
    if limit_per_category:
        seen: dict[str, int] = {}
        selected = []
        for case in cases:
            key = (case.get("expected_categories") or ["ABSTAIN"])[0] + case.get("kind", "")
            if seen.get(key, 0) < limit_per_category:
                seen[key] = seen.get(key, 0) + 1
                selected.append(case)
        cases = selected
    return cases


def _provider(spec_synthesizers: dict[str, Any]) -> LLMProvider:
    return provider_from_env(spec_synthesizers)


async def fix_agent_suite(split: str = "dev", trials: int = 1, smoke: bool = False) -> RunRecord:
    from fixai_evals.evaluators import fix_agent as evaluator
    from fixai_fix_agent.agent import SPEC
    from fixai_knowledge_mcp import server as knowledge

    root = DATASETS / "fix_agent" / "v1"
    cases = _cases(root / "cases.jsonl", split, 1 if smoke else None)
    provider = _provider(SPEC.synthesizers)
    record = RunRecord(
        suite="fix-agent" + ("-smoke" if smoke else ""), dataset="fix_agent", dataset_version="v1",
        dataset_checksum=dataset_checksum(root), split=split, code_commit=git_sha(), provider=provider.name,
        model=provider.model, decoding={"effort": "medium"} if provider.name == "anthropic" else {"deterministic": True},
        prompt_versions={SPEC.name: SPEC.prompt_version}, tool_schema_versions={"fixai-certification": "1.0.0",
                                                                                 "fixai-knowledge": knowledge.VERSION},
        retrieval_config={"chunking": knowledge.index().chunking.name, "mode": "hybrid", "embedding": "hash-512-v1"},
        evaluator_versions={"fix-agent": evaluator.VERSION}, seed=0, trials=trials)
    trial_scores: list[float] = []
    for trial in range(trials):
        passed = 0
        for case in cases:
            fixture = load_fixture(root / case["fixture"], case.get("fixture_transform"))
            servers = {"retrieve_certification_evidence": certification_replay({case["run_id"]: fixture}),
                       "search_fix_documentation": knowledge.server}
            from fixai_fix_agent.agent import DiagnoseRequest

            started = time.perf_counter()
            try:
                result = await invoke(SPEC, DiagnoseRequest(run_id=case["run_id"]), servers, provider)
                outcome = evaluator.evaluate(case, fixture, json.loads(result.model_dump_json()), set(SPEC.allowlist))
            except Exception as error:  # noqa: BLE001 - recorded as a failed case
                outcome = {"checks": {"completed": False}, "passed": False, "error": f"{type(error).__name__}: {error}"[:300],
                           "latency_ms": int((time.perf_counter() - started) * 1000), "tool_calls": 0,
                           "input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0}
                record.errors.append(f"{case['id']}: {outcome['error']}")
            passed += outcome["passed"]
            record.cases.append({"id": case["id"], "trial": trial, "kind": case["kind"], "split": case["split"],
                                 "expected": case["expected_categories"], **outcome})
        trial_scores.append(passed / len(cases) if cases else 0.0)
    record.metrics = aggregate(record.cases, trial_scores)
    return record


def aggregate(cases: list[dict[str, Any]], trial_scores: list[float]) -> dict[str, Any]:
    def count(check: str, subset: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        rows = subset if subset is not None else cases
        relevant = [c for c in rows if check in c.get("checks", {})]
        return stats.proportion(sum(c["checks"][check] for c in relevant), len(relevant))

    successes = [c for c in cases if c["passed"]]
    checks = sorted({k for c in cases for k in c.get("checks", {})})
    adversarial = [c for c in cases if c.get("kind") == "adversarial"]
    return {
        "task_success": stats.proportion(len(successes), len(cases)),
        "checks": {check: count(check) for check in checks},
        "adversarial_success": stats.proportion(sum(c["passed"] for c in adversarial), len(adversarial)),
        "latency_ms": stats.percentiles([c["latency_ms"] for c in cases]),
        "tokens_per_success": round(sum(c["input_tokens"] + c["output_tokens"] for c in cases) / max(1, len(successes)), 1),
        "cost_usd_per_success": round(sum(c["cost_usd"] for c in cases) / max(1, len(successes)), 6),
        "tool_calls_per_success": round(sum(c["tool_calls"] for c in cases) / max(1, len(successes)), 2),
        "llm_fallback_rate": stats.proportion(sum(bool(c.get("llm_fallback_used")) for c in cases), len(cases)),
        "trial_variance": stats.variance_across_trials(trial_scores),
    }
