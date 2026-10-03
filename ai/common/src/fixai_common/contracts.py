"""Versioned agent contracts (schema_version 1).

Facts and hypotheses are separate types on purpose: a Fact must cite persisted evidence; a Hypothesis is an
explanation candidate and is always presented as such. Certification verdicts never appear here - they come only
from the certification engine's executable assertions.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field

SCHEMA_VERSION = 1


class FailureCategory(StrEnum):
    """Stable failure taxonomy shared by agents, MCP tools and evaluation datasets."""

    MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD"
    INCORRECT_FIELD_VALUE = "INCORRECT_FIELD_VALUE"
    QUANTITY_INCONSISTENCY = "QUANTITY_INCONSISTENCY"
    DUPLICATE_IDENTIFIER = "DUPLICATE_IDENTIFIER"
    MISSING_RESPONSE = "MISSING_RESPONSE"
    UNEXPECTED_ACCEPTANCE = "UNEXPECTED_ACCEPTANCE"
    UNEXPECTED_REJECTION = "UNEXPECTED_REJECTION"
    SESSION_PROTOCOL_VIOLATION = "SESSION_PROTOCOL_VIOLATION"
    SEQUENCE_RECOVERY_FAILURE = "SEQUENCE_RECOVERY_FAILURE"
    LATENCY_SLA_BREACH = "LATENCY_SLA_BREACH"
    CONNECTIVITY = "CONNECTIVITY"
    UNKNOWN = "UNKNOWN"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class EvidenceRef(Strict):
    """Pointer to persisted platform state. `ref` is stable and resolvable through MCP tools."""

    kind: Literal["evidence", "assertion", "protocol_check", "step", "log", "document", "report", "run"]
    ref: str = Field(min_length=1, max_length=256, description="e.g. run/<id>/exec/<id>/evidence/<ordinal>")


class Citation(Strict):
    doc_id: str
    chunk_id: str
    title: str
    quote: str = Field(max_length=1200)
    score: float | None = None


class Fact(Strict):
    """A statement directly supported by persisted evidence."""

    statement: str = Field(min_length=1, max_length=2000)
    evidence: list[EvidenceRef] = Field(min_length=1)


class Hypothesis(Strict):
    """A ranked root-cause candidate. Never presented as verified."""

    title: str = Field(min_length=1, max_length=200)
    category: FailureCategory
    likelihood: Literal["high", "medium", "low"]
    explanation: str = Field(min_length=1, max_length=4000)
    evidence: list[EvidenceRef] = Field(min_length=1)
    remediation: list[str] = Field(default_factory=list, max_length=10)
    citations: list[Citation] = Field(default_factory=list, max_length=10)


class ToolCallRecord(Strict):
    tool: str
    status: Literal["ok", "error", "denied", "timeout"]
    duration_ms: int
    arguments_sha256: str
    error: str | None = None


class AgentRunMetadata(Strict):
    """Everything needed to reproduce and evaluate one agent invocation. No chain-of-thought is recorded."""

    schema_version: int = SCHEMA_VERSION
    agent: str
    agent_version: str
    prompt_version: str
    provider: str
    model: str
    correlation_id: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    estimated_cost_usd: float = 0.0
    latency_ms: int = 0
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    llm_fallback_used: bool = False
    decision_summary: str = Field(default="", max_length=1000)


T = TypeVar("T", bound=BaseModel)


class AgentResult(BaseModel, Generic[T]):
    model_config = ConfigDict(extra="forbid")

    output: T
    metadata: AgentRunMetadata
