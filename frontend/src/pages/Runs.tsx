import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, FIX_VERSIONS, type FixVersion } from "../api";
import { Badge, Card, SelectField, Empty, ErrorBox, Loading, When } from "../components/ui";
import { useAction, useAsync } from "../hooks";
import { useIdentity } from "../identity";

export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const page = Number(params.get("page") ?? 0);
  const runs = useAsync(() => api.runs(page, 20), [page], (p) => p.items.some((r) => r.status === "QUEUED" || r.status === "RUNNING"), 2000);
  const { can } = useIdentity();
  const showForm = params.get("new") === "1";

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Certification runs</h1>
          <p className="muted">Deterministic scenario runs against the synthetic simulator or an approved broker session.</p>
        </div>
        {can("CERTIFICATION_ENGINEER") && !showForm && (
          <button className="primary" onClick={() => setParams({ new: "1" })}>
            New run
          </button>
        )}
      </header>
      {showForm && <NewRunForm onClose={() => setParams({})} />}
      <ErrorBox error={runs.error} />
      <Card>
        <Loading when={runs.loading && !runs.data} />
        {runs.data && runs.data.items.length === 0 && <Empty>No runs yet.</Empty>}
        {runs.data && runs.data.items.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Run</th>
                <th>Plan</th>
                <th>FIX</th>
                <th>Target</th>
                <th>Status</th>
                <th>Verdict</th>
                <th>Passed / failed / errored</th>
                <th>Requested by</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {runs.data.items.map((run) => (
                <tr key={run.id}>
                  <td>
                    <Link to={`/runs/${run.id}`}>{run.id.slice(0, 8)}</Link>
                  </td>
                  <td>{run.suiteId ?? `${run.scenarioIds.length} scenarios`}</td>
                  <td>{run.fixVersion}</td>
                  <td>{run.targetType === "SIMULATOR" ? `Simulator · ${run.simulatorProfile}` : `Session · ${run.environment}`}</td>
                  <td>
                    <Badge value={run.status} />
                  </td>
                  <td>
                    <Badge value={run.verdict} />
                  </td>
                  <td>
                    {run.scenariosPassed} / {run.scenariosFailed} / {run.scenariosErrored}
                  </td>
                  <td>{run.requestedBy}</td>
                  <td>
                    <When at={run.createdAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {runs.data && runs.data.total > 20 && (
          <div className="row pager">
            <button disabled={page === 0} onClick={() => setParams({ page: String(page - 1) })}>
              Previous
            </button>
            <span className="muted">
              Page {page + 1} of {Math.ceil(runs.data.total / 20)}
            </span>
            <button disabled={(page + 1) * 20 >= runs.data.total} onClick={() => setParams({ page: String(page + 1) })}>
              Next
            </button>
          </div>
        )}
      </Card>
    </>
  );
}

function NewRunForm({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const suites = useAsync(() => api.suites(), []);
  const scenarios = useAsync(() => api.scenarios(), []);
  const profiles = useAsync(() => api.profiles().catch(() => [{ name: "COMPLIANT", compId: "SIM-COMPLIANT", compliant: true }]), []);
  const [fixVersion, setFixVersion] = useState<FixVersion>("FIX44");
  const [mode, setMode] = useState<"suite" | "scenarios">("suite");
  const [suiteId, setSuiteId] = useState("smoke");
  const [selected, setSelected] = useState<string[]>([]);
  const [targetType, setTargetType] = useState<"SIMULATOR" | "SESSION_CONFIG">("SIMULATOR");
  const [profile, setProfile] = useState("COMPLIANT");
  const [sessionConfigId, setSessionConfigId] = useState("");
  const [approvalId, setApprovalId] = useState("");
  const start = useAction(api.startRun);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const run = await start.run({
      fixVersion,
      ...(mode === "suite" ? { suiteId } : { scenarioIds: selected }),
      target:
        targetType === "SIMULATOR" ? { type: "SIMULATOR", simulatorProfile: profile } : { type: "SESSION_CONFIG", sessionConfigId, approvalId },
    });
    if (run) navigate(`/runs/${run.id}`);
  };

  const available = (scenarios.data ?? []).filter((s) => s.fixVersions.includes(fixVersion));
  return (
    <Card title="New certification run">
      <form className="form" onSubmit={submit} aria-label="New certification run">
        <div className="grid3">
          <SelectField label="FIX version" value={fixVersion} onChange={(e) => setFixVersion(e.target.value as FixVersion)}>
              {FIX_VERSIONS.map((v) => (
                <option key={v}>{v}</option>
              ))}
          </SelectField>
          <SelectField label="Plan" value={mode} onChange={(e) => setMode(e.target.value as "suite" | "scenarios")}>
              <option value="suite">Suite</option>
              <option value="scenarios">Pick scenarios</option>
          </SelectField>
          {mode === "suite" && (
            <SelectField label="Suite" value={suiteId} onChange={(e) => setSuiteId(e.target.value)}>
                {(suites.data ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.title} ({s.scenarioIds.length})
                  </option>
                ))}
            </SelectField>
          )}
        </div>
        {mode === "scenarios" && (
          <fieldset className="scenario-picker">
            <legend>Scenarios ({selected.length} selected)</legend>
            {available.map((s) => (
              <label key={s.id} className="check">
                <input
                  type="checkbox"
                  checked={selected.includes(s.id)}
                  onChange={() => setSelected((cur) => (cur.includes(s.id) ? cur.filter((x) => x !== s.id) : [...cur, s.id]))}
                />
                <code>{s.id}</code> {s.title}
              </label>
            ))}
          </fieldset>
        )}
        <div className="grid3">
          <SelectField label="Target" value={targetType} onChange={(e) => setTargetType(e.target.value as "SIMULATOR" | "SESSION_CONFIG")}>
              <option value="SIMULATOR">Synthetic simulator</option>
              <option value="SESSION_CONFIG">Approved broker session</option>
          </SelectField>
          {targetType === "SIMULATOR" ? (
            <SelectField label="Simulator profile" value={profile} onChange={(e) => setProfile(e.target.value)}>
                {(profiles.data ?? []).map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.name}
                    {p.compliant ? " (compliant)" : " (defect)"}
                  </option>
                ))}
            </SelectField>
          ) : (
            <>
              <label>
                Session configuration ID
                <input value={sessionConfigId} onChange={(e) => setSessionConfigId(e.target.value)} placeholder="UUID of an APPROVED configuration" required />
              </label>
              <label>
                Approval ID
                <input value={approvalId} onChange={(e) => setApprovalId(e.target.value)} placeholder="Approved START_EXTERNAL_CERTIFICATION request" required />
              </label>
            </>
          )}
        </div>
        {targetType === "SESSION_CONFIG" && (
          <p className="hint">
            The session must be approved and in TEST or UAT, and the run needs its own approved request for exactly this FIX version and scenario set. The approval
            is consumed when the run starts.
          </p>
        )}
        <ErrorBox error={start.error} />
        <div className="row">
          <button className="primary" type="submit" disabled={start.pending || (mode === "scenarios" && selected.length === 0)}>
            {start.pending ? "Starting…" : "Start run"}
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Card>
  );
}
