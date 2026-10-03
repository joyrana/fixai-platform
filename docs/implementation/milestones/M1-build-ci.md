# M1 Completion Report – Build Consistency, CI, Developer Setup

## Changes
- **Dependencies:** Spring Boot 3.5.0 → 3.5.16, QuickFIX/J 2.3.0 → 2.3.3, springdoc 2.8.8 → 2.8.17. All are patch releases within the same minor version and need no source changes.
- **Coverage:** JaCoCo report bound to `verify` in the parent POM. The parent POM also manages CycloneDX SBOM generation and `-Xlint` compiler warnings.
- **Removed empty modules:** `shared`, `api-gateway` and `report-service`, per ADR-0006 and the roadmap.
- **broker-service clean-up:**
  - Moved to package `com.fixai.platform.broker`, with main class `BrokerServiceApplication`.
  - Removed the unwired QuickFIX/J adapter, which duplicated fix-gateway ownership and threw on Logon, Logout and Reject.
  - Removed the unused Lombok and QuickFIX/J dependencies.
- **Security fix:** fix-gateway event logging no longer writes FIX message bodies, which previously exposed Logon credentials (553/554/925/96) and order data. Logs now carry `msgType` and `msgSeqNum` only. A regression test asserts that the password does not appear.
- **Developer setup:** `.editorconfig`, `.env.example`, `infra/docker/docker-compose.yml` (PostgreSQL 16 with one schema per service).
- **CI:** `.github/workflows/ci.yml` runs the Java build, tests, coverage artifact and SBOM, plus dependency review on pull requests.

## Verification (executed)
- `mvn -B clean verify` passed. 29 tests, 0 failures. (The baseline was 33; 5 tests were removed with the deleted duplicate adapter, and 1 redaction regression test was added.)
- `docker compose -f infra/docker/docker-compose.yml up -d --wait` brought Postgres up healthy with schemas `broker`, `certification`, `workflow` and `knowledge`.
- `mvn -f backend/parent-pom/pom.xml org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom` produced `backend/parent-pom/target/bom.json`.

## Known limitations
- No Maven wrapper; CI provides Maven through the runner image.
- The GitHub Actions workflow has not yet run on GitHub; it runs on the first push or PR.
