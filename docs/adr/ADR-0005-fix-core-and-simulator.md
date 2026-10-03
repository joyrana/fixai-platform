# ADR-0005: Shared `fix-core` Library and Deterministic FIX Simulator

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Three components need the same FIX knowledge:
- fix-gateway runs long-lived sessions.
- certification-service drives short-lived, per-run test sessions.
- The simulator plays the counterparty.

All three need the same version handling, dictionaries, session-settings construction and, critically, the same redaction rules. Duplicating that code would let redaction rules drift, which is a security risk.

Certification must also run without a real broker. A deterministic reference counterparty is needed both as the default target and as the system-under-test in automated tests and evaluation datasets.

Certification runs need message-level control (send this order, expect that execution report within N ms). Routing every step through fix-gateway over HTTP would add latency and a second state machine for no isolation benefit, because each run already uses its own QuickFIX/J session with its own CompIDs.

## Decision

1. Create `backend/fix-core`, a plain Java library that depends on QuickFIX/J and not on Spring. It owns FIX versions, dictionaries, settings building, inspection/validation and redaction.
2. Create `backend/fix-simulator`, a Spring Boot application and library. It provides a QuickFIX/J dynamic acceptor with deterministic behaviour profiles.
3. certification-service uses `fix-core` to run **ephemeral, run-scoped** sessions in-process. fix-gateway remains the owner of **long-lived, operational** sessions. Neither service manages sequence numbers manually; QuickFIX/J does.

## Consequences

- Redaction and validation have exactly one implementation.
- Services still do not depend on each other's jars, only on `fix-core`.
- QuickFIX/J's static session registry requires globally unique SessionIDs per JVM. The certification engine generates a run-scoped SenderCompID for simulator targets and serialises runs per configured SessionID for external targets.
