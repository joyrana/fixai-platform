import { Link } from "react-router-dom";
import { api } from "../api";
import { Badge, Card, Empty, ErrorBox, Loading, When } from "../components/ui";
import { useAsync } from "../hooks";

export function OverviewPage() {
  const runs = useAsync(() => api.runs(0, 50), []);
  const approvals = useAsync(() => api.approvals("PENDING").catch(() => undefined), []);
  const items = runs.data?.items ?? [];
  const finished = items.filter((r) => r.verdict);
  const passed = finished.filter((r) => r.verdict === "PASSED").length;
  const active = items.filter((r) => r.status === "QUEUED" || r.status === "RUNNING").length;

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Overview</h1>
          <p className="muted">Verdicts come only from the certification engine's executable assertions over persisted evidence.</p>
        </div>
        <Link className="button primary" to="/runs?new=1">
          New certification run
        </Link>
      </header>
      <ErrorBox error={runs.error} />
      <div className="kpis">
        <Kpi label="Runs (last 50)" value={items.length} />
        <Kpi label="Pass rate" value={finished.length ? `${Math.round((passed / finished.length) * 100)}%` : "—"} />
        <Kpi label="In progress" value={active} />
        <Kpi label="Pending approvals" value={approvals.data ? approvals.data.total : "—"} />
      </div>
      <Card title="Recent runs" actions={<Link to="/runs">All runs</Link>}>
        <Loading when={runs.loading && !runs.data} />
        {items.length === 0 && !runs.loading ? (
          <Empty>No certification runs yet.</Empty>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Run</th>
                <th>FIX</th>
                <th>Target</th>
                <th>Status</th>
                <th>Verdict</th>
                <th>Scenarios</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {items.slice(0, 8).map((run) => (
                <tr key={run.id}>
                  <td>
                    <Link to={`/runs/${run.id}`}>{run.id.slice(0, 8)}</Link>
                  </td>
                  <td>{run.fixVersion}</td>
                  <td>{run.targetType === "SIMULATOR" ? `Simulator · ${run.simulatorProfile}` : `Session · ${run.environment}`}</td>
                  <td>
                    <Badge value={run.status} />
                  </td>
                  <td>
                    <Badge value={run.verdict} />
                  </td>
                  <td>
                    {run.scenariosPassed}/{run.scenariosTotal}
                  </td>
                  <td>
                    <When at={run.createdAt} />
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

function Kpi({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="kpi">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
