import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type Diagnosis, type Evidence, type ReplayReport } from "../api";
import { Badge, Card, Empty, ErrorBox, Hash, Loading, When } from "../components/ui";
import { useAction, useAsync } from "../hooks";
import { useIdentity } from "../identity";

const ACTIVE = ["QUEUED", "RUNNING"];

export function RunDetailPage() {
  const { runId = "" } = useParams();
  const { can } = useIdentity();
  const run = useAsync(() => api.run(runId), [runId], (r) => ACTIVE.includes(r.status));
  const executions = useAsync(() => api.executions(runId), [runId, run.data?.status, run.data?.scenariosPassed, run.data?.scenariosFailed]);
  const [openExecution, setOpenExecution] = useState<string>();
  const cancel = useAction(api.cancelRun);
  const replay = useAction(api.replay);
  const [replayReport, setReplayReport] = useState<ReplayReport>();
  const diagnose = useAction(api.diagnose);
  const [diagnosis, setDiagnosis] = useState<{ output: Diagnosis; provider: string; fallback: boolean }>();

  const r = run.data;
  const finished = r && !ACTIVE.includes(r.status);
  return (
    <>
      <header className="page-head">
        <div>
          <p className="eyebrow">
            <Link to="/runs">Certification runs</Link> /
          </p>
          <h1>Run {runId.slice(0, 8)}</h1>
        </div>
        <div className="row">
          {r && ACTIVE.includes(r.status) && can("CERTIFICATION_ENGINEER") && (
            <button onClick={async () => (await cancel.run(runId)) && run.reload()} disabled={cancel.pending}>
              Cancel run
            </button>
          )}
          {finished && (
            <a className="button" href={api.reportHtmlUrl(runId)} target="_blank" rel="noreferrer">
              Report (HTML)
            </a>
          )}
        </div>
      </header>
      <ErrorBox error={run.error ?? cancel.error} />
      <Loading when={run.loading && !r} />
      {r && (
        <Card>
          <dl className="facts">
            <div>
              <dt>Engine verdict</dt>
              <dd>
                <Badge value={r.verdict} />
              </dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>
                <Badge value={r.status} />
              </dd>
            </div>
            <div>
              <dt>FIX version</dt>
              <dd>{r.fixVersion}</dd>
            </div>
            <div>
              <dt>Target</dt>
              <dd>{r.targetType === "SIMULATOR" ? `Simulator · ${r.simulatorProfile}` : `Session ${r.sessionConfigId} · ${r.environment}`}</dd>
            </div>
            <div>
              <dt>Scenarios</dt>
              <dd>
                {r.scenariosPassed} passed · {r.scenariosFailed} failed · {r.scenariosErrored} errored of {r.scenariosTotal}
              </dd>
            </div>
            <div>
              <dt>Evidence digest</dt>
              <dd>
                <Hash value={r.evidenceDigest} />
              </dd>
            </div>
            <div>
              <dt>Engine / catalogue</dt>
              <dd>
                {r.engineVersion} · <Hash value={r.catalogueHash} />
              </dd>
            </div>
            <div>
              <dt>Requested by</dt>
              <dd>{r.requestedBy}</dd>
            </div>
            <div>
              <dt>Completed</dt>
              <dd>
                <When at={r.completedAt} />
              </dd>
            </div>
          </dl>
          {r.errorDetail && <p className="alert">{r.errorDetail}</p>}
        </Card>
      )}

      {finished && (
        <Card
          title="Verification and analysis"
          actions={
            <>
              <button
                onClick={async () => {
                  const report = await replay.run(runId);
                  if (report) setReplayReport(report);
                }}
                disabled={replay.pending}
              >
                {replay.pending ? "Replaying…" : "Replay-verify verdict"}
              </button>
              {r?.verdict !== "PASSED" && can("CERTIFICATION_ENGINEER", "REVIEWER", "AUDITOR") && (
                <button
                  className="primary"
                  onClick={async () => {
                    const result = await diagnose.run(runId);
                    if (result) setDiagnosis({ output: result.output, provider: result.metadata.provider, fallback: result.metadata.llm_fallback_used });
                  }}
                  disabled={diagnose.pending}
                >
                  {diagnose.pending ? "Diagnosing…" : "AI diagnosis"}
                </button>
              )}
            </>
          }
        >
          <ErrorBox error={replay.error ?? diagnose.error} />
          {replayReport && (
            <p className={replayReport.consistent && replayReport.evidenceDigestMatches ? "ok" : "alert"}>
              Offline re-evaluation {replayReport.consistent ? "reproduced every verdict" : "found mismatches"}; evidence digest{" "}
              {replayReport.evidenceDigestMatches ? "matches" : "does NOT match"}.
            </p>
          )}
          {diagnosis && <DiagnosisView diagnosis={diagnosis.output} provider={diagnosis.provider} fallback={diagnosis.fallback} />}
          {!replayReport && !diagnosis && (
            <p className="muted">
              Replay re-evaluates every assertion from persisted evidence. AI diagnosis proposes hypotheses that cite that evidence; it never changes the
              verdict.
            </p>
          )}
        </Card>
      )}

      <Card title="Scenarios">
        <ErrorBox error={executions.error} />
        {executions.data?.length === 0 && <Empty>No scenarios have started yet.</Empty>}
        {executions.data && executions.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Scenario</th>
                <th>Category</th>
                <th>Status</th>
                <th>Failure</th>
                <th>Evidence</th>
              </tr>
            </thead>
            <tbody>
              {executions.data.map((e) => (
                <tr key={e.id} className={openExecution === e.id ? "selected" : ""}>
                  <td>{e.position + 1}</td>
                  <td>
                    <button className="link" onClick={() => setOpenExecution(openExecution === e.id ? undefined : e.id)}>
                      <code>{e.scenarioId}</code> {e.title}
                    </button>
                    {!e.mandatory && <span className="muted"> (optional)</span>}
                  </td>
                  <td>{e.category}</td>
                  <td>
                    <Badge value={e.status} />
                  </td>
                  <td className="wrap">{e.failureSummary ?? ""}</td>
                  <td>{e.evidenceCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {openExecution && <ExecutionDetail runId={runId} executionId={openExecution} />}
    </>
  );
}

function ExecutionDetail({ runId, executionId }: { runId: string; executionId: string }) {
  const detail = useAsync(() => api.execution(runId, executionId), [runId, executionId]);
  const evidence = useAsync(() => api.evidence(runId, executionId), [runId, executionId]);
  const [selected, setSelected] = useState<Evidence>();
  const cited = new Set(
    (detail.data?.steps ?? []).flatMap((s) => [s.matchedOrdinal, ...s.assertions.map((a) => a.evidenceOrdinal)]).filter((x): x is number => x != null),
  );
  return (
    <div className="split">
      <Card title={`Steps · ${detail.data?.execution.scenarioId ?? ""}`}>
        <ErrorBox error={detail.error} />
        <ol className="steps">
          {(detail.data?.steps ?? []).map((step) => (
            <li key={step.index} className={step.status.toLowerCase()}>
              <div className="row between">
                <span>
                  <strong>{step.type}</strong> {step.description}
                </span>
                <Badge value={step.status} />
              </div>
              {step.detail && <p className="muted wrap">{step.detail}</p>}
              {step.assertions
                .filter((a) => !a.passed)
                .map((a, i) => (
                  <p key={i} className="assertion">
                    {a.subject}: expected <code>{a.expected}</code>, actual <code>{a.actual}</code>
                    {a.evidenceOrdinal != null && <span className="muted"> · evidence #{a.evidenceOrdinal}</span>}
                  </p>
                ))}
            </li>
          ))}
        </ol>
      </Card>
      <Card title="Evidence (redacted)">
        <ErrorBox error={evidence.error} />
        <div className="evidence">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Dir</th>
                <th>Type</th>
                <th>Seq</th>
                <th>Content</th>
              </tr>
            </thead>
            <tbody>
              {(evidence.data?.items ?? []).map((item) => (
                <tr
                  key={item.ordinal}
                  className={`${cited.has(item.ordinal) ? "cited" : ""} ${selected?.ordinal === item.ordinal ? "selected" : ""}`}
                  onClick={() => setSelected(item)}
                >
                  <td>{item.ordinal}</td>
                  <td>{item.direction === "INBOUND" ? "← in" : item.direction === "OUTBOUND" ? "out →" : ""}</td>
                  <td>{item.msgType ?? item.kind}</td>
                  <td>{item.msgSeqNum ?? ""}</td>
                  <td className="mono truncate">{item.raw ?? item.eventText}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {selected && selected.fields.length > 0 && (
          <table className="fields">
            <caption>
              Message #{selected.ordinal} · sha256 <Hash value={selected.sha256} />
            </caption>
            <tbody>
              {selected.fields.map((f, i) => (
                <tr key={i}>
                  <td>{f.tag}</td>
                  <td>{f.name}</td>
                  <td className="mono">{f.value}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}

function DiagnosisView({ diagnosis, provider, fallback }: { diagnosis: Diagnosis; provider: string; fallback: boolean }) {
  if (diagnosis.abstained) return <p className="muted">No diagnosis: {diagnosis.abstain_reason}</p>;
  return (
    <div className="diagnosis">
      <p className="hint">
        Hypotheses generated by the fix-agent ({provider}
        {fallback ? ", deterministic fallback" : ""}). Each one cites persisted evidence; the engine verdict ({diagnosis.verdict}) is unchanged.
      </p>
      {diagnosis.scenarios.map((s) => (
        <div key={s.scenario_id} className="hypotheses">
          <h3>
            <code>{s.scenario_id}</code> <Badge value={s.status} />
          </h3>
          {s.facts.map((f, i) => (
            <p key={i} className="fact">
              Fact: {f.statement}
            </p>
          ))}
          {s.hypotheses.length === 0 && <p className="muted">No rule matched; inspect the evidence manually.</p>}
          {s.hypotheses.map((h, i) => (
            <details key={i} open={i === 0}>
              <summary>
                <Badge value={h.likelihood.toUpperCase()} /> {h.title} <span className="muted">· {h.category.replaceAll("_", " ").toLowerCase()}</span>
              </summary>
              <p>{h.explanation}</p>
              {h.remediation.length > 0 && (
                <ul>
                  {h.remediation.map((step, j) => (
                    <li key={j}>{step}</li>
                  ))}
                </ul>
              )}
              <p className="muted small">Evidence: {h.evidence.map((e) => e.ref.split("/evidence/")[1] ?? e.ref).map((o) => `#${o}`).join(", ")}</p>
              {h.citations.map((c, j) => (
                <blockquote key={j}>
                  {c.quote} <cite>— {c.title}</cite>
                </blockquote>
              ))}
            </details>
          ))}
        </div>
      ))}
    </div>
  );
}
