# M4 + M5 Completion Report – Broker Persistence, Session Configuration, Approvals and Audit

## M5 – workflow-service (approvals and audit)
- **Approval binding:** approval requests are bound to `SHA-256(canonical JSON of {action, targetType, targetId, environment, arguments})`. Only workflow-service computes hashes. At execution time the executor presents its current action, which is re-hashed and compared (`POST /approvals/{id}/consume`), and each approval can be consumed once.
- **Policy `approval-policy/2026-10-03.1`:**
  - Actions are allow-listed.
  - PRODUCTION is blocked.
  - A justification is required.
  - Risk is derived from action and environment.
  - TTL is at most 7 days (default 24 h).
  - Four-eyes: a requester cannot decide their own request; this is also enforced by a database CHECK constraint.
  - Reviewers decide; services consume.
  - Expired requests can never be approved or consumed.
- **Transitions:** approve, reject, request changes, cancel, expire (lazily and by a scheduled sweep) and consume. All are compare-and-set in SQL, so concurrent decisions have exactly one winner. All decisions are recorded with actor, rationale, timestamp and policy version.
- **Audit log:** an append-only, SHA-256 hash-chained log, serialised with an advisory lock. UPDATE and DELETE are blocked by database triggers, and `GET /audit-events/verify` detects tampering even if the triggers are bypassed.

## platform-web (shared library)
- **Authentication:** OIDC resource server with issuer and audience validation; roles come from the `roles` claim or `realm_access.roles`. A local development identity mode (`X-Dev-User`/`X-Dev-Roles`) applies only when `fixai.security.enabled=false` and logs a warning at startup.
- **Platform roles:** ADMIN, BROKER_MANAGER, CERTIFICATION_ENGINEER, REVIEWER, AUDITOR, SERVICE and AI_AGENT, enforced with `@PreAuthorize`.
- **Request handling:** correlation-ID filter, shared RFC 7807 handlers (no internal details), and explicit CORS allow-list.
- **Outbound calls:** client-credentials token provider for service-to-service calls. User tokens are never forwarded.
- **Transactional audit outbox:** audit rows commit with the change they describe and are relayed to workflow-service with retry and back-off.

## M4 – broker-service
- **Brokers:** moved from in-memory storage to PostgreSQL with Flyway. The duplicate-code race is closed by a unique index, and deleting a broker that still has configurations is refused.
- **FIX session configurations:**
  - Only TEST and UAT environments are representable, enforced by an enum and a DB CHECK.
  - Protocol validation comes from fix-core; credentials are accepted only as secret-store references; metadata and wildcard hosts are blocked; duplicate sessions are blocked.
  - Lifecycle: DRAFT → submit (approval created in workflow-service on behalf of the user) → activate. Activation re-validates the configuration and the broker, then consumes the approval.
  - Any edit returns the configuration to DRAFT; the content version is part of the approved payload.
  - Optimistic locking compares and sets on both content version and status.
- **Audit:** every change is audited through the outbox in the same transaction.

## certification-service integration
- Uses platform-web for security.
- **Roles:**
  - Starting runs: ADMIN, CERTIFICATION_ENGINEER or AI_AGENT. AI agents are refused for external targets and limited to simulator runs.
  - Cancelling runs: ADMIN or CERTIFICATION_ENGINEER.
- Resolves `SESSION_CONFIG` targets from broker-service when `fixai.certification.broker-service-url` is set. Such targets must be APPROVED and in TEST or UAT.
- Audit events go through the outbox (Flyway V2).

## Tests executed (`mvn -B clean verify`)
116 tests, 0 failures, 0 errors, 0 skipped (2m55s).
- workflow-service (12):
  - Lifecycle: roles, four-eyes, hash mismatch and single use.
  - Policy: production blocked, unknown action refused, justification required.
  - Reject, request-changes, cancel and visibility.
  - Expiry, using a controllable clock.
  - Concurrent decisions: 6 reviewers race and exactly 1 wins.
  - Idempotency.
  - Audit log: append-only triggers, role protection, and tamper detection with triggers disabled.
  - Canonical JSON, policy and hash-chain units.
- broker-service (17, including the original 11):
  - Full lifecycle with an approval fake that keeps the binding semantics.
  - Rejection and resubmission; validation; refusal of production and raw secrets.
  - Duplicates, roles, suspended brokers and referential integrity.
  - Workflow HTTP contract tests (MockRestServiceServer).
- platform-web (5): dev identity, role 403s, correlation-ID sanitising, no detail leakage, JWT role mapping.
- certification-service (24): adds role and agent restrictions, plus checks that audit rows are written to the outbox.

## Known limitations
- The outbox relay is at-least-once and not yet `SKIP LOCKED`-safe across multiple replicas; duplicates are possible but carry unique event IDs.
- If activation consumes an approval and the following local update fails, the configuration needs a new approval. This is safe but not seamless.
- Cross-service end-to-end flows (broker → workflow → certification over HTTP) are exercised in the compose-based E2E milestone. Unit and integration tests here use HTTP-contract tests and fakes at the boundary.
