"""Writes the machine-readable MCP tool and agent contracts that are checked into docs/architecture/contracts.

    python -m fixai_evals.contracts            # regenerate
    python -m fixai_evals.contracts --check    # CI: fail if the checked-in contracts are stale

A contract change is therefore always visible in review: schemas, capability class, roles, agent allow-lists,
timeouts, rate limits and idempotency for every tool, and input/output schemas plus tool allow-lists for every agent.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from typing import Any

from fixai_common.mcp.server import contract

OUT = Path(__file__).resolve().parents[2] / "docs" / "architecture" / "contracts"


async def documents() -> dict[str, dict[str, Any]]:
    from fixai_certification_mcp import server as certification
    from fixai_fix_mcp import server as fix
    from fixai_knowledge_agent.agent import SPEC as knowledge_agent
    from fixai_knowledge_mcp import server as knowledge
    from fixai_operations_mcp import server as operations
    from fixai_orchestrator.agents import specs

    docs: dict[str, dict[str, Any]] = {}
    for module in (certification, fix, knowledge, operations):
        doc = await contract(module.server, module.registry)
        docs[f"mcp-{doc['server']}.json"] = {"version": module.VERSION, **doc}
    for spec in [*specs().values(), knowledge_agent]:
        docs[f"agent-{spec.name}.json"] = {
            "agent": spec.name, "version": spec.version, "promptVersion": spec.prompt_version,
            "allowedTools": sorted(spec.allowlist), "maxToolCalls": spec.max_tool_calls,
            "timeoutSeconds": spec.timeout_seconds, "callerRoles": sorted(spec.caller_roles),
            "input": spec.input_model.model_json_schema(), "output": spec.output_model.model_json_schema()}
    return docs


def render(doc: dict[str, Any]) -> str:
    return json.dumps(doc, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    docs = asyncio.run(documents())
    if args.check:
        stale = [name for name, doc in docs.items() if not (OUT / name).exists() or (OUT / name).read_text() != render(doc)]
        extra = sorted(p.name for p in OUT.glob("*.json") if p.name not in docs)
        if stale or extra:
            print(f"stale contracts: {stale}; unexpected: {extra}. Run python -m fixai_evals.contracts", file=sys.stderr)
            return 1
        print(f"{len(docs)} contracts up to date")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for name, doc in docs.items():
        (OUT / name).write_text(render(doc))
    print(f"wrote {len(docs)} contracts to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
