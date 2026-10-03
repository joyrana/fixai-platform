# ADR-0006: Defer API Gateway, Report Service, Kafka and Redis

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

The target layout lists an API gateway, a report service, Kafka and Redis. None of the features in milestones M1–M5 needs them:

- Reports are a read-only projection of certification evidence.
- There are no asynchronous fan-out consumers yet.
- There is no cache whose correctness has been analysed.
- No edge concern (auth offload, rate limiting across services) exists while each service validates its own tokens.

## Decision

- Remove the empty `api-gateway` and `report-service` modules. Reports are produced by certification-service from persisted evidence.
- Do not add Kafka until an event has two or more independent consumers. The first candidate is certification run completion feeding the notification and analytics consumers.
- Do not add Redis until a cache or short-lived coordination need is measured. The first candidate is distributed rate limiting for MCP tools.

## Consequences

- Fewer moving parts locally and in CI.
- Synchronous HTTP between services is acceptable at current scale. Calls carry timeouts and correlation IDs so that moving to events later is mechanical.
- When any of these components is introduced, a new ADR records the triggering requirement.
