# ADR-0003: QuickFIX/J Selection

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

The platform requires a production-capable FIX engine for session management, message parsing, protocol validation, heartbeat handling, sequence management, and operational connectivity concerns. Building a FIX engine in-house would introduce significant correctness, maintenance, and certification risk.

The Java ecosystem offers a small number of realistic choices for enterprise FIX connectivity, and the platform standardizes on Java for backend services.

## Decision

Select **QuickFIX/J 2.3** as the FIX engine for FIXAI Platform.

QuickFIX/J will be used as infrastructure behind application-defined ports and adapters rather than as the architectural center of the platform.

## Rationale

QuickFIX/J provides mature FIX session infrastructure while allowing the platform team to focus on product capabilities instead of protocol engine implementation.

Key drivers:

1. **Protocol maturity**: QuickFIX/J is widely known, production-proven, and purpose-built for FIX connectivity concerns.
2. **Java alignment**: it integrates naturally with the platform’s JVM stack and operational tooling.
3. **Feature completeness**: it covers core FIX engine responsibilities such as session scheduling, sequence handling, message cracking, dictionaries, and initiator/acceptor patterns.
4. **Community familiarity**: many engineers working in capital markets recognize QuickFIX/J, reducing adoption friction.
5. **Architectural fit**: it can be encapsulated behind adapters so the rest of the system depends on platform abstractions rather than engine-specific APIs.

## Consequences

### Positive

- avoids the risk of implementing FIX protocol mechanics internally
- accelerates delivery of gateway and certification infrastructure
- benefits from an established ecosystem and known operational patterns

### Negative

- introduces a third-party API with its own design constraints
- requires careful encapsulation to prevent QuickFIX/J types from spreading across the codebase
- engine behavior and upgrade cadence must be managed deliberately

## Alternatives Considered

### Custom in-house FIX engine

Rejected because it would be high-risk, expensive to validate, and a poor use of engineering effort relative to the platform’s goals.

### Non-Java FIX engine or external gateway product

Rejected because it would add cross-runtime integration complexity or reduce control over how FIX infrastructure is embedded into the platform.
