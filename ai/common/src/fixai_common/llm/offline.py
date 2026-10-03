"""Deterministic offline provider: same inputs, same outputs, zero cost. Used by CI, tests and eval baselines."""

from __future__ import annotations

import inspect
import time

from pydantic import BaseModel

from fixai_common.llm.base import LLMRequest, LLMUsage, Synthesizer


class OfflineProvider:
    name = "offline"
    model = "deterministic-rules-v1"

    def __init__(self, synthesizers: dict[str, Synthesizer]) -> None:
        self._synthesizers = dict(synthesizers)

    async def generate(self, request: LLMRequest) -> tuple[BaseModel, LLMUsage]:
        synthesizer = self._synthesizers.get(request.task)
        if synthesizer is None:
            raise KeyError(f"No offline synthesizer registered for task {request.task!r}")
        started = time.perf_counter()
        result = synthesizer(request)
        if inspect.isawaitable(result):
            result = await result
        output = request.output_model.model_validate(result.model_dump())
        return output, LLMUsage(provider=self.name, model=self.model,
                                latency_ms=int((time.perf_counter() - started) * 1000))
