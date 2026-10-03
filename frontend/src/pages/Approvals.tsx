import { useState } from "react";
import { api, type Approval } from "../api";
import { Badge, Card, Empty, ErrorBox, Hash, Loading, When } from "../components/ui";
import { useAction, useAsync } from "../hooks";
import { useIdentity } from "../identity";

const FILTERS = ["PENDING", "APPROVED", "CONSUMED", "REJECTED", "CHANGES_REQUESTED", "EXPIRED", "CANCELLED"];

export function ApprovalsPage() {
  const [status, setStatus] = useState("PENDING");
  const list = useAsync(() => api.approvals(status), [status]);
  const [selected, setSelected] = useState<string>();

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Approvals</h1>
          <p className="muted">
            Each request is bound to the SHA-256 of its exact payload. Requesters cannot decide their own requests, and an approval can be consumed once.
          </p>
        </div>
        <select aria-label="Status filter" value={status} onChange={(e) => (setStatus(e.target.value), setSelected(undefined))}>
          {FILTERS.map((f) => (
            <option key={f} value={f}>
              {f.replaceAll("_", " ").toLowerCase()}
            </option>
          ))}
        </select>
      </header>
      <ErrorBox error={list.error} />
      <div className="split">
        <Card title={`${status.replaceAll("_", " ").toLowerCase()} requests`}>
          <Loading when={list.loading && !list.data} />
          {list.data?.items.length === 0 && <Empty>Nothing here.</Empty>}
          <ul className="list">
            {(list.data?.items ?? []).map((a) => (
              <li key={a.id}>
                <button className={`list-item ${selected === a.id ? "active" : ""}`} onClick={() => setSelected(a.id)}>
                  <span>
                    <strong>{a.action.replaceAll("_", " ").toLowerCase()}</strong>
                    <span className="muted small">
                      {" "}
                      {a.targetType} · {a.environment} · by {a.requestedBy}
                    </span>
                  </span>
                  <Badge value={a.riskLevel} />
                </button>
              </li>
            ))}
          </ul>
        </Card>
        {selected ? <ApprovalDetail id={selected} onDecided={list.reload} /> : <Card><Empty>Select a request to review it.</Empty></Card>}
      </div>
    </>
  );
}

function ApprovalDetail({ id, onDecided }: { id: string; onDecided: () => void }) {
  const detail = useAsync(() => api.approval(id), [id]);
  const { can, identity } = useIdentity();
  const [rationale, setRationale] = useState("");
  const decide = useAction(api.decide);
  const a: Approval | undefined = detail.data?.approval;
  const own = a?.requestedBy === identity.user;

  const submit = async (decision: "APPROVE" | "REJECT" | "REQUEST_CHANGES") => {
    if (await decide.run(id, decision, rationale)) {
      setRationale("");
      detail.reload();
      onDecided();
    }
  };

  return (
    <Card title="Review">
      <ErrorBox error={detail.error} />
      {a && (
        <>
          <dl className="facts">
            <div>
              <dt>Action</dt>
              <dd>{a.action}</dd>
            </div>
            <div>
              <dt>Target</dt>
              <dd className="mono">
                {a.targetType}/{a.targetId}
              </dd>
            </div>
            <div>
              <dt>Environment</dt>
              <dd>{a.environment}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>
                <Badge value={a.status} />
              </dd>
            </div>
            <div>
              <dt>Requested by</dt>
              <dd>
                {a.requestedBy} ({a.requesterType.toLowerCase()})
              </dd>
            </div>
            <div>
              <dt>Expires</dt>
              <dd>
                <When at={a.expiresAt} />
              </dd>
            </div>
            <div>
              <dt>Payload hash</dt>
              <dd>
                <Hash value={a.payloadHash} />
              </dd>
            </div>
            <div>
              <dt>Policy</dt>
              <dd>{a.policyVersion}</dd>
            </div>
          </dl>
          <h3>Justification</h3>
          <p className="wrap quote">{a.justification}</p>
          <h3>Exact payload</h3>
          <pre className="payload">{JSON.stringify(a.arguments, null, 2)}</pre>
          {a.evidenceRefs.length > 0 && (
            <>
              <h3>Evidence</h3>
              <ul className="small mono">
                {a.evidenceRefs.map((r) => (
                  <li key={r}>{r}</li>
                ))}
              </ul>
            </>
          )}
          {(detail.data?.history ?? []).length > 0 && (
            <>
              <h3>History</h3>
              <ul className="small">
                {detail.data!.history.map((h, i) => (
                  <li key={i}>
                    <When at={h.at} /> · {h.decision} by {h.actor}
                    {h.rationale ? ` — ${h.rationale}` : ""}
                  </li>
                ))}
              </ul>
            </>
          )}
          {a.status === "PENDING" && can("REVIEWER") && (
            <div className="form decision">
              {own && <p className="alert">You requested this; a different reviewer must decide it (four-eyes).</p>}
              <label>
                Rationale (recorded in the audit log)
                <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} rows={3} minLength={10} />
              </label>
              <ErrorBox error={decide.error} />
              <div className="row">
                <button className="primary" disabled={decide.pending || rationale.trim().length < 10} onClick={() => submit("APPROVE")}>
                  Approve
                </button>
                <button disabled={decide.pending || rationale.trim().length < 10} onClick={() => submit("REQUEST_CHANGES")}>
                  Request changes
                </button>
                <button className="danger" disabled={decide.pending || rationale.trim().length < 10} onClick={() => submit("REJECT")}>
                  Reject
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </Card>
  );
}
