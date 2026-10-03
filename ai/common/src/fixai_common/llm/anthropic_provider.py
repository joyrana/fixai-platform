"""Claude provider using structured outputs (Pydantic) with server-side refusal fallback.

Prompt structure keeps the stable system prompt first (cacheable) and places untrusted third-party text inside clearly
delimited data blocks with an explicit instruction that it is data, never instructions.
"""

from __future__ import annotations

import json
import time

import anthropic
from pydantic import BaseModel

from fixai_common.guard import fence_untrusted
from fixai_common.llm.base import LLMRefusal, LLMRequest, LLMUsage

# USD per million tokens (input, output, cache read). Update with pricing changes; used for cost-per-task tracking.
PRICING = {
    "claude-opus-5-5": (4.00, 20.00, 0.20),
    "claude-sonnet-5-5": (2.00, 10.00, 0.20),
    "claude-haiku-4-5": (1.00, 5.00, 0.10),
}


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, model: str = "claude-opus-5-5", effort: str = "medium", client: anthropic.AsyncAnthropic | None = None,
                 timeout_seconds: float = 120.0) -> None:
        self.model = model
        self._effort = effort
        self._client = client or anthropic.AsyncAnthropic(timeout=timeout_seconds, max_retries=2)

    async def generate(self, request: LLMRequest) -> tuple[BaseModel, LLMUsage]:
        user_content = (
            f"{request.instructions}\n\n"
            f"<facts>\n{json.dumps(request.facts, sort_keys=True, default=str)}\n</facts>\n\n"
            + "\n".join(fence_untrusted(text, index) for index, text in enumerate(request.untrusted))
        )
        started = time.perf_counter()
        response = await self._client.beta.messages.parse(
            model=self.model,
            max_tokens=max(request.max_output_tokens, 1024),
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={"effort": self._effort},
            system=[{"type": "text", "text": request.system, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": user_content}],
            output_format=request.output_model,
        )
        if response.stop_reason == "refusal":
            raise LLMRefusal(getattr(response.stop_details, "category", None) or "refusal")
        if response.stop_reason == "max_tokens" or response.parsed_output is None:
            raise LLMRefusal("incomplete structured output")
        usage = response.usage
        input_tokens = usage.input_tokens or 0
        output_tokens = usage.output_tokens or 0
        cache_read = getattr(usage, "cache_read_input_tokens", 0) or 0
        price_in, price_out, price_cache = PRICING.get(self.model, (0.0, 0.0, 0.0))
        cost = (input_tokens * price_in + output_tokens * price_out + cache_read * price_cache) / 1_000_000
        return response.parsed_output, LLMUsage(
            provider=self.name, model=response.model, input_tokens=input_tokens, output_tokens=output_tokens,
            cache_read_tokens=cache_read, cache_write_tokens=getattr(usage, "cache_creation_input_tokens", 0) or 0,
            latency_ms=int((time.perf_counter() - started) * 1000), estimated_cost_usd=round(cost, 6))
