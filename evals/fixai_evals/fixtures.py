"""Generates fix-agent evaluation fixtures from REAL certification runs against the simulator's defect profiles.

Ground truth comes from the defect each profile injects (documented in fix-simulator), never from a model. Evidence
is retrieved through the production MCP tool so fixtures have exactly the shape agents see at run time.

Usage: python -m fixai_evals.fixtures --certification-url http://localhost:8083 --out evals/datasets/fix_agent/v1
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from pathlib import Path

from mcp.client import Client

from fixai_certification_mcp import server as cert_mcp
from fixai_common.identity import Principal, ServiceIdentity, current_principal
from fixai_common.platform_client import PlatformClient, PlatformUrls

# (profile, scenario) -> accepted categories. Labelled from the injected defect, independently of any agent.
LABELS: dict[tuple[str, str], list[str]] = {
    ("WRONG_EXEC_TYPE_ON_FILL", "ORD-002"): ["INCORRECT_FIELD_VALUE"],
    ("MISSING_EXEC_ID", "ORD-001"): ["MISSING_REQUIRED_FIELD"],
    ("MISSING_EXEC_ID", "ORD-002"): ["MISSING_REQUIRED_FIELD"],
    ("DUPLICATE_EXEC_ID", "ORD-003"): ["DUPLICATE_IDENTIFIER"],
    ("INCORRECT_CUM_QTY", "ORD-003"): ["QUANTITY_INCONSISTENCY"],
    ("WRONG_AVG_PX", "ORD-002"): ["INCORRECT_FIELD_VALUE"],
    ("NO_CANCEL_RESPONSE", "ORD-004"): ["MISSING_RESPONSE"],
    ("ACCEPT_UNKNOWN_CANCEL", "NEG-003"): ["UNEXPECTED_ACCEPTANCE"],
    ("MISSING_ORIG_CLORDID", "ORD-005"): ["MISSING_REQUIRED_FIELD"],
    ("ACCEPT_DUPLICATE_CLORDID", "NEG-004"): ["UNEXPECTED_ACCEPTANCE"],
    ("REJECT_ALL_ORDERS", "ORD-001"): ["UNEXPECTED_REJECTION"],
    ("HEARTBEAT_WITHOUT_TEST_REQ_ID", "SES-002"): ["SESSION_PROTOCOL_VIOLATION"],
    ("GAP_FILL_WITHOUT_FLAG", "SES-005"): ["SEQUENCE_RECOVERY_FAILURE"],
    ("SLOW_ACK", "ORD-001"): ["LATENCY_SLA_BREACH", "MISSING_RESPONSE"],
}
VERSIONS = ["FIX44", "FIX42", "FIX50SP2"]


def split_for(case_id: str) -> str:
    """Stable 70/30 dev/test split by hash; test cases are never used for prompt tuning."""
    return "test" if int(hashlib.sha256(case_id.encode()).hexdigest(), 16) % 10 < 3 else "dev"


async def call(tool: str, args: dict) -> dict:
    token = current_principal.set(Principal("fixture-generator", frozenset({"CERTIFICATION_ENGINEER"}), "agent-orchestrator"))
    try:
        async with Client(cert_mcp.server) as client:
            result = await client.call_tool(tool, args)
    finally:
        current_principal.reset(token)
    if result.is_error:
        raise RuntimeError(f"{tool} failed: {result.content}")
    return result.structured_content


async def run_and_wait(profile: str, version: str, scenario_ids: list[str]) -> str:
    key = f"fixtures-{profile}-{version}-" + "-".join(scenario_ids)
    # Offline generator only: reset the per-minute start limit (production limits are unchanged).
    cert_mcp.registry._calls.clear()
    started = await call("start_simulated_certification", {
        "fix_version": version, "scenario_ids": scenario_ids, "simulator_profile": profile,
        "idempotency_key": hashlib.sha256(key.encode()).hexdigest()[:32]})
    for _ in range(240):
        status = await call("get_certification_status", {"run_id": started["run_id"]})
        if status["status"] in ("COMPLETED", "CANCELLED", "ERROR"):
            return started["run_id"]
        await asyncio.sleep(0.5)
    raise TimeoutError(started["run_id"])


async def generate(certification_url: str, out: Path) -> None:
    cert_mcp.use_client(PlatformClient(PlatformUrls(certification_url, "", "", ""),
                                       ServiceIdentity("fixture-generator", ("CERTIFICATION_ENGINEER",)), timeout=30))
    fixtures = out / "fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    cases = []
    by_profile: dict[str, list[str]] = {}
    for (profile, scenario), _ in LABELS.items():
        by_profile.setdefault(profile, []).append(scenario)
    for version in VERSIONS:
        for profile, scenarios in by_profile.items():
            run_id = await run_and_wait(profile, version, scenarios)
            evidence = await call("retrieve_certification_evidence", {"run_id": run_id, "max_evidence_per_scenario": 40})
            for scenario in scenarios:
                section = [s for s in evidence["scenarios"] if s["scenario_id"] == scenario]
                if not section:
                    raise RuntimeError(f"{profile}/{scenario}/{version} unexpectedly passed")
                case_id = f"fix-{profile.lower()}-{scenario.lower()}-{version.lower()}"
                fixture = {"run_id": run_id, "verdict": evidence["verdict"], "scenarios": section}
                (fixtures / f"{case_id}.json").write_text(json.dumps(fixture, indent=1, sort_keys=True))
                cases.append({"id": case_id, "split": split_for(case_id), "kind": "typical", "profile": profile,
                              "fix_version": version, "scenario_id": scenario, "run_id": run_id,
                              "fixture": f"fixtures/{case_id}.json", "expected_categories": LABELS[(profile, scenario)],
                              "expect_abstain": False, "label_source": "simulator-defect-profile"})
        # Compliant counterparty: nothing to diagnose; the agent must abstain.
        run_id = await run_and_wait("COMPLIANT", version, ["ORD-001", "ORD-002", "SES-002"])
        evidence = await call("retrieve_certification_evidence", {"run_id": run_id})
        case_id = f"fix-compliant-{version.lower()}"
        (fixtures / f"{case_id}.json").write_text(json.dumps(evidence, indent=1, sort_keys=True))
        cases.append({"id": case_id, "split": split_for(case_id), "kind": "negative", "profile": "COMPLIANT",
                      "fix_version": version, "scenario_id": None, "run_id": run_id, "fixture": f"fixtures/{case_id}.json",
                      "expected_categories": [], "expect_abstain": True, "label_source": "simulator-defect-profile"})
    (out / "cases.jsonl").write_text("\n".join(json.dumps(c, sort_keys=True) for c in cases) + "\n")
    print(f"wrote {len(cases)} cases to {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--certification-url", default="http://localhost:8083")
    parser.add_argument("--out", type=Path, default=Path("evals/datasets/fix_agent/v1"))
    args = parser.parse_args()
    asyncio.run(generate(args.certification_url, args.out))


if __name__ == "__main__":
    main()
