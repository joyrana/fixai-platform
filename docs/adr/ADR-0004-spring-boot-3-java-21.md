# ADR-0004: Spring Boot 3 and Java 21

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

The platform needs a modern, well-supported backend foundation with strong observability, dependency management, configuration support, testing integration, and access to current JVM language/runtime features. It also needs a baseline that looks credible in enterprise environments and remains maintainable for open-source contributors.

## Decision

Standardize backend services on **Spring Boot 3** and **Java 21**.

Spring Boot provides the application framework and operational conventions. Java 21 provides the language and runtime baseline.

## Rationale

This pairing balances enterprise maturity with modern platform capabilities.

Key drivers:

1. **Long-term support**: Java 21 is an LTS release suitable for enterprise adoption.
2. **Modern language features**: records, pattern matching, improved concurrency options, and other contemporary JVM capabilities improve code clarity and maintainability.
3. **Spring ecosystem strength**: Spring Boot supplies mature autoconfiguration, actuator support, configuration binding, and testing utilities.
4. **Operational readiness**: the Spring ecosystem integrates naturally with health checks, metrics, and environment-specific configuration.
5. **Hiring and familiarity**: the combination is recognizable and credible for professional engineering teams and contributors.

## Consequences

### Positive

- strong default foundation for service development
- modern Java baseline without adopting experimental runtimes
- rich ecosystem for testing, observability, and integration
- consistent patterns across backend modules

### Negative

- framework conventions can encourage overreliance on Spring if boundaries are not enforced
- contributors must use a modern JDK toolchain
- dependency upgrades should be managed carefully to preserve compatibility

## Alternatives Considered

### Older Java and Spring baselines

Rejected because they would reduce access to current platform features and shorten the runway before another upgrade becomes necessary.

### Lightweight framework or plain Java approach

Rejected because it would trade away ecosystem maturity, operational tooling, and developer productivity that are valuable for a multi-module enterprise platform.
