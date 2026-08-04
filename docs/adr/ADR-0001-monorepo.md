# ADR-0001: Monorepo

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

FIXAI Platform spans multiple backend services, shared components, and front-end or automation surfaces that evolve together. The platform must support coordinated changes across FIX connectivity, certification workflows, reporting, and shared contracts without introducing heavy cross-repository release management.

The early-stage project also benefits from lower operational overhead. Splitting repositories too early would add friction around dependency versioning, shared CI/CD conventions, code ownership boundaries, and architectural visibility before those costs are justified by scale.

## Decision

Adopt a **monorepo** for `fixai-platform`, with technology-specific and domain-specific modules organized under a shared repository root.

The repository will:

- keep related platform capabilities versioned together
- allow shared parent build configuration and dependency management
- support cross-module refactors in a single change set
- centralize architecture documentation, including ADRs

## Rationale

This choice optimizes for platform coherence over repository isolation.

Key drivers:

1. **Atomic change management**: a single pull request can evolve shared models, service interfaces, infrastructure modules, and documentation together.
2. **Shared engineering standards**: build logic, testing practices, dependency baselines, and code quality gates can be applied consistently.
3. **Architecture visibility**: contributors can understand the full system in one place, which is especially valuable for a domain like FIX where infrastructure and business capabilities are tightly related.
4. **Lower coordination overhead**: teams avoid premature package publishing, compatibility matrices, and multi-repo orchestration.

## Consequences

### Positive

- simpler onboarding for new contributors
- easier end-to-end refactoring
- one source of truth for platform-level documentation
- consistent dependency and runtime baselines

### Negative

- repository size and CI scope will grow over time
- stricter discipline is required to prevent accidental coupling between modules
- build performance may eventually require targeted pipelines and caching

## Alternatives Considered

### Multi-repo by service

Rejected for now because it would introduce release and dependency coordination costs before the platform has enough scale or team separation to justify them.

### Monorepo with no module boundaries

Rejected because a monorepo without explicit module structure would weaken architectural boundaries and make later extraction harder.
