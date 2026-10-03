import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Identity } from "../api";
import { IdentityProvider } from "../identity";
import { mockFetch } from "../test/mockFetch";
import { ApprovalsPage } from "./Approvals";
import { RunDetailPage } from "./RunDetail";

afterEach(() => vi.unstubAllGlobals());

const RUN = "0b1e1f6a-0000-4000-8000-000000000001";

function renderAt(path: string, element: React.ReactElement, route: string, identity: Identity) {
  return render(
    <IdentityProvider initial={identity}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={route} element={element} />
        </Routes>
      </MemoryRouter>
    </IdentityProvider>,
  );
}

const approval = {
  id: "a1", action: "ACTIVATE_SESSION_CONFIG", targetType: "session-config", targetId: "cfg-1", environment: "UAT",
  arguments: { host: "uat.broker.example", port: 9876 }, payloadHash: "f".repeat(64), justification: "UAT onboarding for certification",
  requestedBy: "bob", requesterType: "USER", riskLevel: "MEDIUM", evidenceRefs: [], status: "PENDING", policyVersion: "approval-policy/2026-10-03.1",
  expiresAt: "2026-10-04T10:00:00Z", createdAt: "2026-10-03T10:00:00Z", decidedBy: null, decisionRationale: null, consumedBy: null,
};

describe("approvals", () => {
  it("warns requesters about four-eyes and submits a reviewer's decision with rationale", async () => {
    const calls = mockFetch([
      ["GET", /approvals\?/, () => ({ body: { items: [approval], page: 0, size: 100, total: 1 } })],
      ["GET", /approvals\/a1$/, () => ({ body: { approval, history: [] } })],
      ["POST", /approvals\/a1\/decision$/, () => ({ body: { ...approval, status: "APPROVED" } })],
    ]);
    renderAt("/approvals", <ApprovalsPage />, "/approvals", { user: "bob", roles: ["REVIEWER"] });
    await userEvent.click(await screen.findByRole("button", { name: /activate session config/i }));
    expect(await screen.findByText(/a different reviewer must decide it/i)).toBeInTheDocument();
    expect(screen.getByText(/"host": "uat.broker.example"/)).toBeInTheDocument();

    const approve = screen.getByRole("button", { name: "Approve" });
    expect(approve).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/rationale/i), "Checked against the onboarding record");
    await userEvent.click(approve);
    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ decision: "APPROVE", rationale: "Checked against the onboarding record" });
  });
});

describe("run detail", () => {
  it("shows the engine verdict and AI hypotheses as hypotheses that cite evidence", async () => {
    const run = {
      id: RUN, status: "COMPLETED", verdict: "FAILED", suiteId: "smoke", scenarioIds: [], fixVersion: "FIX44", targetType: "SIMULATOR",
      environment: null, simulatorProfile: "MISSING_EXEC_ID", sessionConfigId: null, scenariosTotal: 4, scenariosPassed: 3, scenariosFailed: 1,
      scenariosErrored: 0, errorDetail: null, requestedBy: "alice", evidenceDigest: "d".repeat(64), engineVersion: "1.0.0", catalogueHash: "c".repeat(64),
      createdAt: "2026-10-03T10:00:00Z", startedAt: null, completedAt: "2026-10-03T10:01:00Z",
    };
    const execution = {
      id: "e1", position: 0, scenarioId: "ORD-001", title: "Resting limit order acknowledged", category: "POSITIVE", mandatory: true,
      status: "FAILED", failureSummary: "ExecID missing", protocolChecks: [], evidenceCount: 6,
    };
    mockFetch([
      ["GET", new RegExp(`certification-runs/${RUN}$`), () => ({ body: run })],
      ["GET", /scenarios$/, () => ({ body: [execution] })],
      ["POST", /assist\/diagnose$/, () => ({
        body: {
          output: {
            run_id: RUN, verdict: "FAILED", abstained: false, abstain_reason: null,
            scenarios: [{ scenario_id: "ORD-001", status: "FAILED", facts: [{ statement: "ExecID: expected present, actual missing", evidence: [] }],
              hypotheses: [{ title: "ExecID omitted from ExecutionReport", category: "MISSING_REQUIRED_FIELD", likelihood: "high",
                explanation: "The counterparty omitted ExecID(17).", evidence: [{ kind: "evidence", ref: `run/${RUN}/exec/e1/evidence/10` }],
                remediation: ["Populate ExecID on every ExecutionReport."], citations: [] }] }],
          },
          metadata: { agent: "fix-agent", provider: "offline", model: "deterministic-rules-v1", latency_ms: 20, llm_fallback_used: false, decision_summary: "" },
        },
      })],
    ]);
    renderAt(`/runs/${RUN}`, <RunDetailPage />, "/runs/:runId", { user: "alice", roles: ["CERTIFICATION_ENGINEER"] });
    const facts = await screen.findByText("Engine verdict");
    expect(within(facts.parentElement!).getByText("FAILED")).toBeInTheDocument();
    expect(await screen.findByText("ExecID missing")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "AI diagnosis" }));
    expect(await screen.findByText(/ExecID omitted from ExecutionReport/)).toBeInTheDocument();
    expect(screen.getByText(/the engine verdict \(FAILED\) is unchanged/i)).toBeInTheDocument();
    expect(screen.getByText("Evidence: #10")).toBeInTheDocument();
  });
});
