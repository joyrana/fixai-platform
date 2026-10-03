# certification-service

The deterministic FIX certification engine. It runs versioned scenarios against a counterparty over real QuickFIX/J sessions and records redacted, hashed message-level evidence. Verdicts come from executable assertions only.

## Run locally

```bash
docker compose -f infra/docker/docker-compose.yml up -d postgres
mvn -pl backend/certification-service -am install -DskipTests
FIX_SIMULATOR_EMBEDDED=true java -jar backend/certification-service/target/certification-service-0.0.1-SNAPSHOT.jar
# Swagger UI: http://localhost:8083/swagger-ui.html
```

```bash
curl -s -X POST localhost:8083/api/v1/certification-runs \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-run-000001' \
  -d '{"suiteId":"full-certification","fixVersion":"FIX44","target":{"type":"SIMULATOR","simulatorProfile":"COMPLIANT"}}'
curl -s localhost:8083/api/v1/certification-runs/<runId>
curl -s localhost:8083/api/v1/certification-runs/<runId>/report.html > report.html
curl -s -X POST localhost:8083/api/v1/certification-runs/<runId>/replay-verification
```

To see failures detected, use `simulatorProfile` values such as `WRONG_EXEC_TYPE_ON_FILL`, `NO_CANCEL_RESPONSE` or `GAP_FILL_WITHOUT_FLAG`. `GET /api/v1/simulator/profiles` on the simulator lists them all.

## How a verdict is produced

1. **Catalogue loading.** YAML scenarios in `src/main/resources/scenarios` are parsed strictly; unknown keys are errors. They are linted against every declared FIX version's dictionary, and an invalid catalogue stops startup.
2. **Isolated sessions.** Each scenario gets a fresh QuickFIX/J initiator. Simulator runs use run-scoped CompIDs, so scenarios execute in parallel. External targets use their fixed CompIDs and run serially.
3. **Evidence capture.** Every wire message in either direction is captured at the QuickFIX/J log layer. That includes counterparty messages our engine rejects as invalid. Each message is stored redacted, with an ordinal and the SHA-256 of the original bytes.
4. **Evaluation.** `ExpectationEvaluator` is a pure function. It selects the first unconsumed matching message in the evidence window `[anchor, windowEnd)` and evaluates field assertions and invariants on it. Automatic `ProtocolChecks` add session-reject detection and ExecID uniqueness.
5. **Verdict.** `PASSED` only if every mandatory scenario passed, `FAILED` if any mandatory scenario failed, `INCONCLUSIVE` otherwise (errors, cancellation).
6. **Replay.** `POST /replay-verification` re-runs step 4 offline on the stored evidence and windows, and recomputes the run's evidence digest. Any difference is reported.

## Safety
- The default target is the synthetic simulator.
- `SESSION_CONFIG` targets are refused unless broker-service is configured, and even then only `APPROVED` configurations in `TEST` or `UAT` are accepted. `PRODUCTION` is always refused.
- No FIX message content reaches logs. API errors carry no stack traces.
