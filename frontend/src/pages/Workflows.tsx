import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, FIX_VERSIONS, type FixVersion } from "../api";
import { Badge, Card, SelectField, Empty, ErrorBox, Hash, Loading, When } from "../components/ui";
import { useAction, useAsync } from "../hooks";
import { useIdentity } from "../identity";

export function WorkflowsPage() {
  const list = useAsync(() => api.workflows(), [], (rows) => rows.some((r) => r.phase === "RUNNING" || r.phase === "STARTING"), 2000);
  const { can } = useIdentity();
  return (
    <>
      <header className="page-head">
        <div>
          <h1>AI workflows</h1>
          <p className="muted">
            Agents plan a simulator run, wait for the engine's verdict, diagnose failures and draft the report. External broker certification stops at a human
            approval gate.
          </p>
        </div>
      </header>
      {can("CERTIFICATION_ENGINEER") && <StartWorkflow />}
      <Card title="Workflows">
        <ErrorBox error={list.error} />
        <Loading when={list.loading && !list.data} />
        {list.data?.length === 0 && <Empty>No workflows started by this orchestrator instance.</Empty>}
        {list.data && list.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Workflow</th>
                <th>Objective</th>
                <th>FIX</th>
                <th>Phase</th>
                <th>Outcome</th>
                <th>Run</th>
                <th>Started</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((w) => (
                <tr key={w.workflow_id}>
                  <td>
                    <Link to={`/workflows/${w.workflow_id}`}>{w.workflow_id.slice(0, 8)}</Link>
                  </td>
                  <td className="wrap">{w.objective}</td>
                  <td>{w.fix_version}</td>
                  <td>
                    <Badge value={w.phase} />
                  </td>
                  <td>{w.outcome?.replaceAll("_", " ").toLowerCase() ?? "—"}</td>
                  <td>{w.run_id ? <Link to={`/runs/${w.run_id}`}>{w.run_id.slice(0, 8)}</Link> : "—"}</td>
                  <td>
                    <When at={w.created_at} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}

function StartWorkflow() {
  const navigate = useNavigate();
  const [objective, setObjective] = useState("Certify heartbeat handling and sequence recovery");
  const [fixVersion, setFixVersion] = useState<FixVersion>("FIX44");
  const [profile, setProfile] = useState("COMPLIANT");
  const [external, setExternal] = useState(false);
  const [sessionConfigId, setSessionConfigId] = useState("");
  const [environment, setEnvironment] = useState<"TEST" | "UAT">("UAT");
  const [justification, setJustification] = useState("");
  const start = useAction(api.startWorkflow);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const result = await start.run({
      fix_version: fixVersion,
      objective,
      simulator_profile: profile,
      ...(external ? { external_certification: { session_config_id: sessionConfigId, environment, justification } } : {}),
    });
    if (result) navigate(`/workflows/${result.workflow_id}`);
  };
  return (
    <Card title="Start an AI-assisted certification">
      <form className="form" onSubmit={submit} aria-label="Start workflow">
        <label>
          Objective
          <input value={objective} onChange={(e) => setObjective(e.target.value)} required minLength={3} maxLength={1000} />
        </label>
        <div className="grid3">
          <SelectField label="FIX version" value={fixVersion} onChange={(e) => setFixVersion(e.target.value as FixVersion)}>
              {FIX_VERSIONS.map((v) => (
                <option key={v}>{v}</option>
              ))}
          </SelectField>
          <label>
            Simulator profile
            <input value={profile} onChange={(e) => setProfile(e.target.value.toUpperCase())} pattern="[A-Z_]{1,64}" />
          </label>
          <label className="check">
            <input type="checkbox" checked={external} onChange={(e) => setExternal(e.target.checked)} /> Then request external certification
          </label>
        </div>
        {external && (
          <div className="grid3">
            <label>
              Session configuration ID
              <input value={sessionConfigId} onChange={(e) => setSessionConfigId(e.target.value)} required pattern="[0-9a-f-]{36}" />
            </label>
            <SelectField label="Environment" value={environment} onChange={(e) => setEnvironment(e.target.value as "TEST" | "UAT")}>
                <option>UAT</option>
                <option>TEST</option>
            </SelectField>
            <label>
              Justification
              <input value={justification} onChange={(e) => setJustification(e.target.value)} required minLength={30} />
            </label>
          </div>
        )}
        <ErrorBox error={start.error} />
        <button className="primary" disabled={start.pending}>
          {start.pending ? "Starting…" : "Start workflow"}
        </button>
      </form>
    </Card>
  );
}

export function WorkflowDetailPage() {
  const { workflowId = "" } = useParams();
  const state = useAsync(() => api.workflow(workflowId), [workflowId], (s) => s.phase === "RUNNING" || s.phase === "STARTING", 1500);
  const resume = useAction(api.resumeWorkflow);
  const cancel = useAction(api.cancelWorkflow);
  const { can } = useIdentity();
  const s = state.data;
  const waiting = s?.phase === "WAITING_FOR_HUMAN";

  return (
    <>
      <header className="page-head">
        <div>
          <p className="eyebrow">
            <Link to="/workflows">AI workflows</Link> /
          </p>
          <h1>Workflow {workflowId.slice(0, 8)}</h1>
        </div>
        {s && s.phase !== "DONE" && can("CERTIFICATION_ENGINEER") && (
          <div className="row">
            {waiting && (
              <button className="primary" onClick={async () => (await resume.run(workflowId)) !== undefined && state.reload()} disabled={resume.pending}>
                Re-check approval
              </button>
            )}
            <button className="danger" onClick={async () => (await cancel.run(workflowId)) !== undefined && state.reload()} disabled={cancel.pending}>
              Cancel workflow
            </button>
          </div>
        )}
      </header>
      <ErrorBox error={state.error ?? resume.error ?? cancel.error} />
      <Loading when={state.loading && !s} />
      {s && (
        <>
          <Card>
            <dl className="facts">
              <div>
                <dt>Phase</dt>
                <dd>
                  <Badge value={s.phase} />
                </dd>
              </div>
              <div>
                <dt>Outcome</dt>
                <dd>{s.outcome?.replaceAll("_", " ").toLowerCase() ?? "—"}</dd>
              </div>
              <div>
                <dt>Simulator run</dt>
                <dd>{s.run_id ? <Link to={`/runs/${s.run_id}`}>{s.run_id.slice(0, 8)}</Link> : "—"}</dd>
              </div>
              <div>
                <dt>Engine verdict</dt>
                <dd>
                  <Badge value={s.report?.verdict ?? null} />
                </dd>
              </div>
            </dl>
            {s.error && <p className="alert">{s.error}</p>}
          </Card>
          {waiting && s.interrupts[0] && (
            <Card title="Waiting for a human decision">
              <p>
                Approval request <code>{s.interrupts[0].approval_id}</code> (risk {s.interrupts[0].risk_level}) is bound to payload hash{" "}
                <Hash value={s.interrupts[0].payload_hash} />. A reviewer must decide it under <Link to="/approvals">Approvals</Link>; then re-check here.
              </p>
              <p className="hint">
                Even when approved, the workflow does not start the external run. A certification engineer starts it from Certification runs with this approval
                ID, and the certification service consumes the approval for exactly the approved plan.
              </p>
            </Card>
          )}
          {s.outcome === "APPROVED_FOR_HUMAN_EXECUTION" && s.approval && (
            <Card title="Approved for external certification">
              <p>
                Start the external run under <Link to="/runs?new=1">Certification runs</Link> with approval <code>{s.approval.approval_id}</code>, the same FIX version
                and these scenarios: <span className="mono small">{[...(s.plan?.scenario_ids ?? [])].sort().join(", ")}</span>.
              </p>
            </Card>
          )}
          {s.plan && (
            <Card title="Plan">
              <p>{s.plan.rationale}</p>
              <p className="mono small">{s.plan.scenario_ids.join(" · ")}</p>
              {s.plan.warnings.map((w, i) => (
                <p key={i} className="alert">
                  {w}
                </p>
              ))}
            </Card>
          )}
          {s.report && (
            <Card title="Report draft (AI narrative; verdict from the engine)">
              <p>{s.report.executive_summary}</p>
              <ul>
                {s.report.next_steps.map((step, i) => (
                  <li key={i}>{step}</li>
                ))}
              </ul>
            </Card>
          )}
          <Card title="Events">
            <ol className="timeline">
              {(s.events ?? []).map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ol>
          </Card>
        </>
      )}
    </>
  );
}
