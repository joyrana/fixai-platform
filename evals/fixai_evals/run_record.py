"""Reproducibility metadata recorded with every evaluation run."""

from __future__ import annotations

import hashlib
import importlib.metadata
import os
import platform
import subprocess
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

PACKAGES = ["fixai-common", "fixai-fix-agent", "fixai-certification-agent", "fixai-knowledge-agent",
            "fixai-log-analysis-agent", "fixai-report-agent", "fixai-human-review-agent", "fixai-agent-orchestrator",
            "fixai-certification-mcp", "fixai-fix-mcp", "fixai-knowledge-mcp", "fixai-operations-mcp",
            "pydantic", "mcp", "anthropic", "langgraph", "fastapi"]


def dataset_checksum(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def git_sha() -> str:
    try:
        sha = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()  # noqa: S603,S607
        dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True, check=True).stdout.strip()  # noqa: S603,S607
        return sha + ("-dirty" if dirty else "")
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def versions() -> dict[str, str]:
    found = {}
    for name in PACKAGES:
        try:
            found[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            continue
    return found


@dataclass
class RunRecord:
    suite: str
    dataset: str
    dataset_version: str
    dataset_checksum: str
    split: str
    code_commit: str
    provider: str
    model: str
    decoding: dict[str, Any]
    prompt_versions: dict[str, str]
    tool_schema_versions: dict[str, str]
    retrieval_config: dict[str, Any]
    evaluator_versions: dict[str, str]
    seed: int
    trials: int
    environment: dict[str, Any] = field(default_factory=lambda: {
        "python": platform.python_version(), "platform": platform.platform(), "packages": versions(),
        "llm_provider_env": os.environ.get("FIXAI_LLM_PROVIDER", "offline")})
    started_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    finished_at: str | None = None
    cases: list[dict[str, Any]] = field(default_factory=list)
    metrics: dict[str, Any] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
