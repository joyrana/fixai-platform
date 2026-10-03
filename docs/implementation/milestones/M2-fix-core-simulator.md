# M2 Completion Report – fix-core and FIX Simulator

## Added
- **`backend/fix-core`** is a plain Java library (no Spring) that depends on QuickFIX/J only.
  - `FixVersion` covers FIX.4.2, FIX.4.4 and FIXT.1.1/FIX.5.0SP2, with bundled dictionaries and `DefaultApplVerID`.
  - `DictionaryRegistry` caches bundled and custom dictionaries.
  - `FixMessageRedactor` masks 91/95/96/553/554/925/1402/1404 plus any configured tags, and computes SHA-256 of the original bytes.
  - `FixMessageView` is the redacted, framework-neutral, wire-order message representation used for evidence.
  - `FixMessageInspector` validates structure, BodyLength, CheckSum, MsgType and the dictionary. It returns stable issue codes and never throws.
  - `FixMessageBuilder` builds messages from dictionary field names (including repeating groups) and rejects unknown names.
  - `FixSessionSpec`, `FixSessionSpecValidator` and `SessionSettingsBuilder` handle per-session initiator/acceptor settings and FIXT dictionaries.
- **`backend/fix-simulator`** is a library and Spring Boot app (admin on `:8085`, FIX on `:9880`).
  - One dynamic acceptor serves FIX 4.2, 4.4 and FIXT.1.1 with a session per client CompID.
  - Order lifecycle covers D, F, G and H, plus BusinessMessageReject for unsupported messages. Prices are fixed, partial fills are deterministic, and OrderID/ExecID counters run per session.
  - Business rules: duplicate ClOrdID, quantity ≤ 0, quantity above 1,000,000, unknown or halted symbol, unsupported OrdType or Side, limit without price, and price precision. Reject reasons are version-aware and fall back to codes the dictionary defines.
  - 13 defect profiles are selected by `TargetCompID=SIM-<PROFILE>`. Each has a ground-truth description that is not exposed through the API.
  - Logs contain session events only, never message content.

## Tests executed
`mvn -B verify -pl backend/fix-core,backend/fix-simulator`:
- fix-core: 23 tests, 0 failures. They cover the inspector (valid/invalid/structure/checksum/body length/enum/format/version/FIX42/FIXT), the redactor, the builder including groups, the validator and the settings builder.
- fix-simulator: 17 tests, 0 failures.
  - Order book: every compliant response validates against the dictionary for all three versions. Business rules, each defect profile and determinism are covered.
  - Socket integration: real QuickFIX/J initiators for FIX 4.2, 4.4 and FIXT complete Logon and a market order fill. An unknown CompID gets a logon reject. TestRequest/Heartbeat behaves correctly for the compliant profile and fails for the defect profile.
  - Spring context: the admin API and health endpoint respond.

## Known limitations
- Order state is per session and in memory; a simulator restart clears it, which is intended.
- Only the `GAP_FILL_WITHOUT_FLAG` and `HEARTBEAT_WITHOUT_TEST_REQ_ID` session-level defects are injected; other session behaviour is stock QuickFIX/J.
