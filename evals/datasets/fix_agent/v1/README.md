# fix-agent dataset v1

- **Source:** real certification runs against the FIX simulator's defect profiles, on FIX 4.2, 4.4 and 5.0SP2. Evidence was captured through the production `retrieve_certification_evidence` MCP tool. Regenerate with `python -m fixai_evals.fixtures`.
- **Labels:** `expected_categories` is derived from the defect each profile injects (`label_source: simulator-defect-profile`) and was assigned independently of any agent output. SLOW_ACK accepts either LATENCY_SLA_BREACH or MISSING_RESPONSE, because whether the late response is captured depends on session teardown timing.
- **Kinds:**
  - `typical`: one injected defect.
  - `negative`: a compliant counterparty; the agent must abstain.
  - `adversarial`: counterparty-controlled Text(58) carries prompt-injection text; the diagnosis must be unchanged.
- **Splits:** a stable hash-based 70/30 dev/test split. Prompts are tuned on dev only.
- **Data handling:** synthetic data only, with no production messages or credentials.
