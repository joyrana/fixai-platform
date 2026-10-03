"""OpenTelemetry setup and span helpers. Exporting is optional (OTEL_EXPORTER_OTLP_ENDPOINT); spans never carry
prompts, completions, FIX payloads or secrets - only identifiers, versions, counts and outcomes."""

from __future__ import annotations

import os
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

_configured = False


def configure(service_name: str) -> None:
    global _configured
    if _configured:
        return
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint:
        try:
            from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
            from opentelemetry.sdk.trace.export import BatchSpanProcessor

            provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces")))
        except ImportError:  # exporter is an optional dependency
            pass
    trace.set_tracer_provider(provider)
    _configured = True


ALLOWED_ATTRIBUTE_PREFIXES = ("fixai.", "gen_ai.", "mcp.", "http.", "workflow.")


@contextmanager
def span(name: str, **attributes: Any) -> Iterator[trace.Span]:
    tracer = trace.get_tracer("fixai")
    with tracer.start_as_current_span(name) as current:
        for key, value in attributes.items():
            if key.startswith(ALLOWED_ATTRIBUTE_PREFIXES) and value is not None:
                current.set_attribute(key, value if isinstance(value, str | int | float | bool) else str(value))
        yield current


# Prometheus metrics. Labels are bounded (agent, tool and outcome names come from code, never from callers).
LATENCY_BUCKETS = (0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120)
AGENT_INVOCATIONS = Counter("fixai_agent_invocations_total", "Agent invocations", ["agent", "outcome"])
AGENT_LATENCY = Histogram("fixai_agent_latency_seconds", "Agent invocation latency", ["agent"], buckets=LATENCY_BUCKETS)
AGENT_LLM_FALLBACKS = Counter("fixai_agent_llm_fallbacks_total", "Invocations that used deterministic fallback output", ["agent"])
AGENT_TOKENS = Counter("fixai_agent_tokens_total", "LLM tokens", ["agent", "direction"])
AGENT_COST = Counter("fixai_agent_cost_usd_total", "Estimated LLM cost in USD", ["agent"])
AGENT_INJECTION_FINDINGS = Counter("fixai_agent_injection_findings_total", "Instruction-like text in tool outputs", ["agent"])
MCP_TOOL_CALLS = Counter("fixai_mcp_tool_calls_total", "MCP tool calls", ["server", "tool", "outcome"])
MCP_TOOL_LATENCY = Histogram("fixai_mcp_tool_latency_seconds", "MCP tool latency", ["server", "tool"], buckets=LATENCY_BUCKETS)
WORKFLOW_OUTCOMES = Counter("fixai_workflow_outcomes_total", "Finished certification workflows", ["outcome"])


def metrics_response() -> tuple[bytes, str]:
    return generate_latest(), CONTENT_TYPE_LATEST
