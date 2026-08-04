# ADR-0002: Hexagonal Architecture

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

FIXAI Platform must integrate with external protocols and infrastructure such as FIX engines, web APIs, persistence layers, and certification tooling. These concerns are volatile and technology-specific, while core platform behavior should remain stable, testable, and portable.

The project also aims to present enterprise-grade engineering standards. That requires clear separation between business/application logic and the frameworks or transport mechanisms used to expose it.

## Decision

Adopt **Hexagonal Architecture** (Ports and Adapters) as the default structural pattern for backend modules.

Each module should organize code around:

- **domain/application core** for business rules and orchestration
- **inbound ports** for use cases and domain-facing inputs
- **outbound ports** for dependencies on external systems
- **adapters** that connect frameworks, protocols, persistence, and infrastructure to those ports

## Rationale

Hexagonal Architecture is a strong fit for FIXAI because the platform sits at the boundary between business workflows and integration-heavy infrastructure.

Key drivers:

1. **Testability**: application services can be unit tested without bringing up transport or infrastructure stacks.
2. **Technology isolation**: QuickFIX/J, Spring Boot, REST, persistence, and messaging can evolve independently of the core logic.
3. **Replacement flexibility**: adapters can change without forcing broad rewrites of the application core.
4. **Clear boundaries**: contributors can see which code expresses policy versus which code handles delivery mechanisms.

## Consequences

### Positive

- improved maintainability as the platform grows
- easier mocking and contract-based testing
- better control of dependency direction
- cleaner separation of enterprise infrastructure from domain behavior

### Negative

- more initial structure and more classes than a layered shortcut
- requires discipline to keep framework code out of the core
- naming and packaging conventions must be applied consistently

## Alternatives Considered

### Traditional layered architecture

Rejected because layers often allow framework and persistence concerns to bleed into the core, especially in Spring applications.

### Framework-first package-by-feature with direct infrastructure access

Rejected because it optimizes for short-term speed at the cost of long-term flexibility and architectural clarity.
