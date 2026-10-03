"""LLM provider abstraction.

Agents ask for a structured output (a Pydantic model) for a named task. The offline provider answers
deterministically from the task's registered synthesizer, which is what CI and evaluation baselines use; the Anthropic
provider asks Claude. Either way the caller validates the result against the evidence it was given before using it.
"""

from __future__ import annotations

import os
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Protocol, TypeVar

from pydantic import BaseModel

M = TypeVar("M", bound=BaseModel)


@dataclass(frozen=True)
class LLMRequest:
    """One structured-generation request.

    `task` names the agent capability (e.g. "fix_agent.diagnose"); `facts` is the deterministic context the offline
    synthesizer consumes; `untrusted` holds third-party text (FIX fields, logs, documents) that is fenced as data.
    """

    task: str
    system: str
    instructions: str
    facts: dict[str, Any]
    output_model: type[BaseModel]
    untrusted: list[str] = field(default_factory=list)
    max_output_tokens: int = 4000


@dataclass(frozen=True)
class LLMUsage:
    provider: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    latency_ms: int = 0
    estimated_cost_usd: float = 0.0


class LLMRefusal(Exception):
    """The model declined the request; callers fall back to deterministic output and record it."""


class LLMProvider(Protocol):
    name: str
    model: str

    async def generate(self, request: LLMRequest) -> tuple[BaseModel, LLMUsage]: ...


Synthesizer = Callable[[LLMRequest], Awaitable[BaseModel] | BaseModel]


def provider_from_env(synthesizers: dict[str, Synthesizer]) -> LLMProvider:
    """`FIXAI_LLM_PROVIDER=anthropic` selects Claude; anything else (default) the deterministic offline provider."""
    from fixai_common.llm.offline import OfflineProvider

    choice = os.environ.get("FIXAI_LLM_PROVIDER", "offline").lower()
    if choice == "anthropic":
        from fixai_common.llm.anthropic_provider import AnthropicProvider

        return AnthropicProvider(model=os.environ.get("FIXAI_LLM_MODEL", "claude-opus-5-5"),
                                 effort=os.environ.get("FIXAI_LLM_EFFORT", "medium"))
    return OfflineProvider(synthesizers)
