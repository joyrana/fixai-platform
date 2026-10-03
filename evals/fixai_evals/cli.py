"""Evaluation CLI.

  python -m fixai_evals.cli run --suite agent-smoke
  python -m fixai_evals.cli run --suite agent-regression --split test
  python -m fixai_evals.cli run --suite rag-quality
  python -m fixai_evals.cli benchmark --suite end-to-end --certification-url http://localhost:8083
  python -m fixai_evals.cli compare --baseline evals/reports/a.json --candidate evals/reports/b.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import UTC, datetime
from pathlib import Path

from fixai_evals import suites

REPORTS = Path(__file__).resolve().parents[1] / "reports"


def write(record_dict: dict, name: str, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}-{name}.json"
    path.write_text(json.dumps(record_dict, indent=1, sort_keys=True, default=str))
    return path


async def run_suite(name: str, split: str, trials: int) -> list[dict]:
    from fixai_evals import suites_extra

    registry = {
        "fix-agent": lambda: suites.fix_agent_suite(split, trials),
        "agent-smoke": lambda: suites_extra.agent_smoke(),
        "agent-regression": lambda: suites_extra.agent_regression(split, trials),
        "rag-quality": lambda: suites_extra.rag_quality(split),
        "safety": lambda: suites_extra.safety(),
        "certification-agent": lambda: suites_extra.certification_agent_suite(split, trials),
        "knowledge-agent": lambda: suites_extra.knowledge_agent_suite(split, trials),
        "log-analysis-agent": lambda: suites_extra.log_analysis_agent_suite(split, trials),
        "report-agent": lambda: suites_extra.report_agent_suite(split, trials),
        "human-review-agent": lambda: suites_extra.human_review_agent_suite(split, trials),
        "workflow": lambda: suites_extra.workflow_suite(),
    }
    if name not in registry:
        raise SystemExit(f"unknown suite {name}; choose from {sorted(registry)}")
    result = await registry[name]()
    records = result if isinstance(result, list) else [result]
    return [r.to_dict() for r in records]


def summary(record: dict) -> str:
    metrics = record["metrics"]
    headline = metrics.get("task_success") or next(iter(metrics.values()), {})
    value = headline.get("value") if isinstance(headline, dict) else headline
    return (f"{record['suite']:<28} split={record['split']:<5} n={len(record['cases']):<4} success={value} "
            f"provider={record['provider']}")


def compare(baseline: dict, candidate: dict, tolerance: float) -> tuple[list[str], list[str]]:
    regressions, improvements = [], []
    base_cases = {(c["id"], c.get("trial", 0)): c for c in baseline["cases"]}
    for case in candidate["cases"]:
        before = base_cases.get((case["id"], case.get("trial", 0)))
        if before and before["passed"] and not case["passed"]:
            regressions.append(f"case {case['id']}: passed -> failed")
        elif before and not before["passed"] and case["passed"]:
            improvements.append(f"case {case['id']}: failed -> passed")
    b = baseline["metrics"].get("task_success", {}).get("value", 0)
    c = candidate["metrics"].get("task_success", {}).get("value", 0)
    if c + tolerance < b:
        regressions.append(f"task_success {b} -> {c}")
    return regressions, improvements


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fixai-evals")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--suite", required=True)
    run.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    run.add_argument("--trials", type=int, default=1)
    run.add_argument("--out", type=Path, default=REPORTS)
    run.add_argument("--min-success", type=float, default=None, help="exit non-zero below this task success")
    bench = sub.add_parser("benchmark")
    bench.add_argument("--suite", required=True, choices=["end-to-end", "certification-throughput"])
    bench.add_argument("--certification-url", default="http://localhost:8083")
    bench.add_argument("--concurrency", type=int, default=4)
    bench.add_argument("--runs", type=int, default=8)
    bench.add_argument("--run-suite", default="smoke", help="certification suite each run executes")
    bench.add_argument("--profile", default="COMPLIANT", help="simulator profile")
    bench.add_argument("--out", type=Path, default=REPORTS)
    cmp = sub.add_parser("compare")
    cmp.add_argument("--baseline", type=Path, required=True)
    cmp.add_argument("--candidate", type=Path, required=True)
    cmp.add_argument("--tolerance", type=float, default=0.0)
    args = parser.parse_args(argv)

    if args.command == "run":
        records = asyncio.run(run_suite(args.suite, args.split, args.trials))
        failed = False
        for record in records:
            path = write(record, record["suite"], args.out)
            print(summary(record), "->", path)
            success = record["metrics"].get("task_success", {}).get("value")
            if args.min_success is not None and success is not None and success < args.min_success:
                failed = True
        return 1 if failed else 0
    if args.command == "benchmark":
        import logging

        from fixai_evals import benchmark

        logging.getLogger("httpx").setLevel(logging.WARNING)

        record = asyncio.run(benchmark.run(args.suite, args.certification_url, args.concurrency, args.runs,
                                           run_suite=args.run_suite, profile=args.profile))
        path = write(record, f"benchmark-{args.suite}", args.out)
        print(json.dumps(record["metrics"], indent=1), "->", path)
        return 0
    baseline = json.loads(args.baseline.read_text())
    candidate = json.loads(args.candidate.read_text())
    regressions, improvements = compare(baseline, candidate, args.tolerance)
    print(json.dumps({"regressions": regressions, "improvements": improvements}, indent=1))
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
