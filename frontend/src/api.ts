// Typed client for the platform APIs. All calls are same-origin (/api/<service>/...) and proxied to the services.

export type Role =
  | "ADMIN"
  | "BROKER_MANAGER"
  | "CERTIFICATION_ENGINEER"
  | "REVIEWER"
  | "AUDITOR";

export const ROLES: Role[] = ["CERTIFICATION_ENGINEER", "BROKER_MANAGER", "REVIEWER", "AUDITOR", "ADMIN"];

export interface Identity {
  user: string;
  roles: Role[];
}

export type FixVersion = "FIX42" | "FIX44" | "FIX50SP2";
export const FIX_VERSIONS: FixVersion[] = ["FIX44", "FIX42", "FIX50SP2"];

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Run {
  id: string;
  status: string;
  verdict: string | null;
  suiteId: string | null;
  scenarioIds: string[];
  fixVersion: FixVersion;
  targetType: string;
  environment: string | null;
  simulatorProfile: string | null;
  sessionConfigId: string | null;
  scenariosTotal: number;
  scenariosPassed: number;
  scenariosFailed: number;
  scenariosErrored: number;
  errorDetail: string | null;
  requestedBy: string;
  evidenceDigest: string | null;
  engineVersion: string;
  catalogueHash: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface AssertionResult {
  subject: string;
  expected: string;
  actual: string;
  passed: boolean;
  evidenceOrdinal: number | null;
}

export interface ScenarioExecution {
  id: string;
  position: number;
  scenarioId: string;
  title: string;
  category: string;
  mandatory: boolean;
  status: string;
  failureSummary: string | null;
  protocolChecks: AssertionResult[];
  evidenceCount: number;
}

export interface Step {
  index: number;
  type: string;
  description: string;
  status: string;
  matchedOrdinal: number | null;
  assertions: AssertionResult[];
  detail: string | null;
}

export interface Evidence {
  ordinal: number;
  kind: string;
  direction: string | null;
  msgType: string | null;
  msgSeqNum: number | null;
  occurredAt: string;
  raw: string | null;
  fields: { tag: number; name: string; value: string }[];
  sha256: string | null;
  eventText: string | null;
}

export interface Scenario {
  id: string;
  title: string;
  category: string;
  fixVersions: FixVersion[];
  tags: string[];
  mandatory: boolean;
}

export interface Suite {
  id: string;
  title: string;
  scenarioIds: string[];
}

export interface Profile {
  name: string;
  compId: string;
  compliant: boolean;
}

export interface ReplayReport {
  runId: string;
  consistent: boolean;
  evidenceDigestMatches: boolean;
  scenarios: { scenarioId: string; consistent: boolean; stepsReevaluated: number; mismatches: string[] }[];
}

export interface Broker {
  id: string;
  brokerCode: string;
  name: string;
  endpoint: string;
  status: string;
  createdAt: string;
}

export interface SessionConfig {
  id: string;
  brokerId: string;
  name: string;
  environment: "TEST" | "UAT";
  fixVersion: FixVersion;
  role: string | null;
  senderCompId: string;
  targetCompId: string;
  host: string;
  port: number;
  heartbeatIntervalSeconds: number;
  reconnectIntervalSeconds: number;
  resetOnLogon: boolean;
  credentialRef: string | null;
  status: string;
  approvalRequestId: string | null;
  approvedPayloadHash: string | null;
  activatedBy: string | null;
  version: number;
}

export interface Approval {
  id: string;
  action: string;
  targetType: string;
  targetId: string;
  environment: string;
  arguments: Record<string, unknown>;
  payloadHash: string;
  justification: string;
  requestedBy: string;
  requesterType: string;
  riskLevel: string;
  evidenceRefs: string[];
  status: string;
  policyVersion: string;
  expiresAt: string;
  createdAt: string;
  decidedBy: string | null;
  decisionRationale: string | null;
  consumedBy: string | null;
}

export interface AuditEvent {
  sequence: number;
  id: string;
  occurredAt: string;
  actor: string;
  actorType: string;
  recordedBy: string;
  action: string;
  resourceType: string;
  resourceId: string;
  correlationId: string | null;
  outcome: string;
  details: Record<string, string>;
  previousHash: string;
  hash: string;
}

export interface WorkflowSummary {
  workflow_id: string;
  requested_by: string;
  objective: string;
  fix_version: FixVersion;
  external: boolean;
  created_at: string;
  phase: string;
  outcome: string | null;
  run_id: string | null;
}

export interface WorkflowState extends Partial<WorkflowSummary> {
  workflow_id: string;
  phase: string;
  outcome?: string | null;
  events?: string[];
  interrupts: { approval_id: string; payload_hash: string; risk_level: string; expires_at: string; message: string }[];
  plan?: { scenario_ids: string[]; rationale: string; warnings: string[] } | null;
  report?: { verdict: string | null; executive_summary: string; next_steps: string[] } | null;
  approval?: { approval_id: string; status: string; payload_hash: string } | null;
  error?: string | null;
}

export interface AgentEnvelope<T> {
  output: T;
  metadata: { agent: string; provider: string; model: string; latency_ms: number; llm_fallback_used: boolean; decision_summary: string };
}

export interface EvidenceRef {
  kind: string;
  ref: string;
}

export interface Hypothesis {
  title: string;
  category: string;
  likelihood: string;
  explanation: string;
  evidence: EvidenceRef[];
  remediation: string[];
  citations: { doc_id: string; title: string; quote: string }[];
}

export interface Diagnosis {
  run_id: string;
  verdict: string | null;
  abstained: boolean;
  abstain_reason: string | null;
  scenarios: { scenario_id: string; status: string; facts: { statement: string; evidence: EvidenceRef[] }[]; hypotheses: Hypothesis[] }[];
}

export interface KnowledgeAnswer {
  answer: string;
  abstained: boolean;
  abstain_reason: string | null;
  citations: { doc_id: string; chunk_id: string; title: string; quote: string }[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly codes: string[] = [],
  ) {
    super(detail);
  }
}

const IDENTITY_KEY = "fixai.identity";
export const AUTH_MODE: "dev" | "proxy" = import.meta.env.VITE_AUTH_MODE === "proxy" ? "proxy" : "dev";

export function loadIdentity(): Identity {
  try {
    const stored = localStorage.getItem(IDENTITY_KEY);
    if (stored) {
      const parsed = JSON.parse(stored) as Identity;
      if (parsed.user && Array.isArray(parsed.roles)) return parsed;
    }
  } catch {
    // ignore unreadable storage
  }
  return { user: "alice.engineer", roles: ["CERTIFICATION_ENGINEER"] };
}

export function saveIdentity(identity: Identity): void {
  try {
    localStorage.setItem(IDENTITY_KEY, JSON.stringify(identity));
  } catch {
    // storage unavailable: identity lasts for this page only
  }
}

let currentIdentity: Identity = loadIdentity();
export function setRequestIdentity(identity: Identity): void {
  currentIdentity = identity;
}

async function request<T>(path: string, init: RequestInit & { idempotencyKey?: string } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  if (init.idempotencyKey) headers.set("Idempotency-Key", init.idempotencyKey);
  if (AUTH_MODE === "dev") {
    // Local development identity, accepted only when the services run with security disabled.
    headers.set("X-Dev-User", currentIdentity.user);
    headers.set("X-Dev-Roles", currentIdentity.roles.join(","));
  }
  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  const text = await response.text();
  const body = text ? safeJson(text) : null;
  if (!response.ok) {
    const problem = (body ?? {}) as { detail?: unknown; title?: string; codes?: string[] };
    const detail = typeof problem.detail === "string" ? problem.detail : problem.title ?? `${response.status} ${response.statusText}`;
    throw new ApiError(response.status, detail, problem.codes ?? []);
  }
  return body as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text.slice(0, 300) };
  }
}

const json = (body: unknown) => JSON.stringify(body);
const newKey = () => `ui-${crypto.randomUUID()}`;

export const api = {
  // certification-service
  runs: (page = 0, size = 20) => request<Page<Run>>(`/api/cert/api/v1/certification-runs?page=${page}&size=${size}`),
  run: (id: string) => request<Run>(`/api/cert/api/v1/certification-runs/${id}`),
  startRun: (body: {
    fixVersion: FixVersion;
    suiteId?: string;
    scenarioIds?: string[];
    target: { type: "SIMULATOR" | "SESSION_CONFIG"; simulatorProfile?: string; sessionConfigId?: string; approvalId?: string };
  }) => request<Run>("/api/cert/api/v1/certification-runs", { method: "POST", body: json(body), idempotencyKey: newKey() }),
  cancelRun: (id: string) => request<Run>(`/api/cert/api/v1/certification-runs/${id}/cancel`, { method: "POST" }),
  replay: (id: string) =>
    request<ReplayReport>(`/api/cert/api/v1/certification-runs/${id}/replay-verification`, { method: "POST" }),
  executions: (id: string) => request<ScenarioExecution[]>(`/api/cert/api/v1/certification-runs/${id}/scenarios`),
  execution: (runId: string, executionId: string) =>
    request<{ execution: ScenarioExecution; steps: Step[] }>(`/api/cert/api/v1/certification-runs/${runId}/scenarios/${executionId}`),
  evidence: (runId: string, executionId: string) =>
    request<Page<Evidence>>(`/api/cert/api/v1/certification-runs/${runId}/scenarios/${executionId}/evidence?size=1000`),
  reportHtmlUrl: (id: string) => `/api/cert/api/v1/certification-runs/${id}/report.html`,
  scenarios: () => request<Scenario[]>("/api/cert/api/v1/scenarios"),
  suites: () => request<Suite[]>("/api/cert/api/v1/suites"),
  profiles: () => request<Profile[]>("/api/sim/api/v1/simulator/profiles"),

  // broker-service
  brokers: () => request<Broker[]>("/api/broker/api/v1/brokers"),
  createBroker: (body: { brokerCode: string; name: string; endpoint: string; status: "ACTIVE" }) =>
    request<Broker>("/api/broker/api/v1/brokers", { method: "POST", body: json(body) }),
  sessionConfigs: (brokerId: string) => request<SessionConfig[]>(`/api/broker/api/v1/brokers/${brokerId}/session-configs`),
  createSessionConfig: (brokerId: string, body: Record<string, unknown>) =>
    request<SessionConfig>(`/api/broker/api/v1/brokers/${brokerId}/session-configs`, { method: "POST", body: json(body) }),
  validateSessionConfig: (id: string) =>
    request<{ valid: boolean; violations: { code: string; field: string; message: string }[] }>(
      `/api/broker/api/v1/session-configs/${id}/validate`,
      { method: "POST" },
    ),
  submitSessionConfig: (id: string, justification: string) =>
    request<SessionConfig>(`/api/broker/api/v1/session-configs/${id}/submit`, { method: "POST", body: json({ justification }) }),
  activateSessionConfig: (id: string) =>
    request<SessionConfig>(`/api/broker/api/v1/session-configs/${id}/activate`, { method: "POST" }),
  retireSessionConfig: (id: string) => request<SessionConfig>(`/api/broker/api/v1/session-configs/${id}/retire`, { method: "POST" }),

  // workflow-service
  approvals: (status?: string) =>
    request<Page<Approval>>(`/api/workflow/api/v1/approvals?size=100${status ? `&status=${status}` : ""}`),
  approval: (id: string) =>
    request<{ approval: Approval; history: { decision: string; actor: string; rationale: string | null; policyVersion: string; at: string }[] }>(
      `/api/workflow/api/v1/approvals/${id}`,
    ),
  decide: (id: string, decision: "APPROVE" | "REJECT" | "REQUEST_CHANGES", rationale: string) =>
    request<Approval>(`/api/workflow/api/v1/approvals/${id}/decision`, { method: "POST", body: json({ decision, rationale }) }),
  audit: (page = 0) => request<Page<AuditEvent>>(`/api/workflow/api/v1/audit-events?page=${page}&size=100`),
  verifyAudit: () => request<{ valid: boolean; eventsChecked: number; firstInvalidSequence: number | null; headHash: string }>(
    "/api/workflow/api/v1/audit-events/verify",
  ),

  // agent-orchestrator
  workflows: () => request<WorkflowSummary[]>("/api/ai/v1/workflows"),
  workflow: (id: string) => request<WorkflowState>(`/api/ai/v1/workflows/${id}`),
  startWorkflow: (body: {
    fix_version: FixVersion;
    objective: string;
    simulator_profile: string;
    external_certification?: { session_config_id: string; environment: "TEST" | "UAT"; justification: string };
  }) => request<{ workflow_id: string }>("/api/ai/v1/workflows", { method: "POST", body: json(body) }),
  resumeWorkflow: (id: string) => request<unknown>(`/api/ai/v1/workflows/${id}/resume`, { method: "POST" }),
  cancelWorkflow: (id: string) => request<unknown>(`/api/ai/v1/workflows/${id}/cancel`, { method: "POST" }),
  diagnose: (runId: string) =>
    request<AgentEnvelope<Diagnosis>>("/api/ai/v1/assist/diagnose", { method: "POST", body: json({ run_id: runId }) }),
  ask: (question: string) =>
    request<AgentEnvelope<KnowledgeAnswer>>("/api/ai/v1/assist/ask", { method: "POST", body: json({ question }) }),
};
