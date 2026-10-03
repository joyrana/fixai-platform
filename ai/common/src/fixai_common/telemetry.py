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
