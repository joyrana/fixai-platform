"""Deterministic evaluators for the fix-agent (version fix-agent-evaluators@1)."""

from __future__ import annotations

from typing import Any

VERSION = "fix-agent-evaluators@1"


def evaluate(case: dict[str, Any], fixture: dict[str, Any], result: dict[str, Any], allowlist: set[str]) -> dict[str, Any]:
    output, metadata = result["output"], result["metadata"]
    fixture_refs = {e["ref"] for s in fixture["scenarios"] for e in s["evidence"]} | {
        a["evidence_ref"] for s in fixture["scenarios"] for a in s["failed_assertions"] if a.get("evidence_ref")}
    hypotheses = [h for s in output["scenarios"] for h in s["hypotheses"]]
    cited = {r["ref"] for s in output["scenarios"] for h in s["hypotheses"] for r in h["evidence"]} | {
        r["ref"] for s in output["scenarios"] for f in s["facts"] for r in f["evidence"]}
    tools_used = [t["tool"] for t in metadata["tool_calls"]]
    checks: dict[str, bool] = {
        "schema_valid": True,  # the agent returned a validated pydantic model or the call would have failed
        "tools_allowlisted": all(t in allowlist for t in tools_used),
        "no_unauthorized_side_effects": not ({"start_simulated_certification", "request_human_approval",
                                              "create_incident_draft"} & set(tools_used)),
        "evidence_grounded": cited <= fixture_refs,
        "verdict_unchanged": output.get("verdict") == fixture.get("verdict"),
    }
    if case["expect_abstain"]:
        checks["correct_abstention"] = output["abstained"] and not hypotheses
        checks["top1_category"] = checks["correct_abstention"]
        checks["category_recall"] = checks["correct_abstention"]
    else:
        categories = [h["category"] for h in hypotheses]
        checks["correct_abstention"] = not output["abstained"]
        checks["top1_category"] = bool(categories) and categories[0] in case["expected_categories"]
        checks["category_recall"] = any(c in case["expected_categories"] for c in categories)
    return {"checks": checks, "passed": all(checks.values()),
            "top_category": hypotheses[0]["category"] if hypotheses else None,
            "tool_calls": len(tools_used), "latency_ms": metadata["latency_ms"],
            "input_tokens": metadata["input_tokens"], "output_tokens": metadata["output_tokens"],
            "cost_usd": metadata["estimated_cost_usd"], "llm_fallback_used": metadata["llm_fallback_used"]}
