import { useState } from "react";
import { api } from "../api";
import { Badge, Card, Empty, ErrorBox, Hash, Loading, When } from "../components/ui";
import { useAction, useAsync } from "../hooks";

export function AuditPage() {
  const events = useAsync(() => api.audit(0), []);
  const verify = useAction(api.verifyAudit);
  const [verification, setVerification] = useState<Awaited<ReturnType<typeof api.verifyAudit>>>();
  return (
    <>
      <header className="page-head">
        <div>
          <h1>Audit log</h1>
          <p className="muted">Append-only and SHA-256 hash-chained. Verification recomputes the chain and reports the first inconsistency.</p>
        </div>
        <button
          onClick={async () => {
            const result = await verify.run();
            if (result) setVerification(result);
          }}
          disabled={verify.pending}
        >
          Verify chain
        </button>
      </header>
      <ErrorBox error={events.error ?? verify.error} />
      {verification && (
        <p className={verification.valid ? "ok" : "alert"}>
          {verification.valid
            ? `Chain intact: ${verification.eventsChecked} events verified.`
            : `Chain broken at sequence ${verification.firstInvalidSequence}.`}
        </p>
      )}
      <Card>
        <Loading when={events.loading && !events.data} />
        {events.data?.items.length === 0 && <Empty>No audit events.</Empty>}
        {events.data && events.data.items.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>When</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Resource</th>
                <th>Outcome</th>
                <th>Hash</th>
              </tr>
            </thead>
            <tbody>
              {events.data.items.map((e) => (
                <tr key={e.id}>
                  <td>{e.sequence}</td>
                  <td>
                    <When at={e.occurredAt} />
                  </td>
                  <td>
                    {e.actor} <span className="muted small">({e.actorType.toLowerCase()})</span>
                  </td>
                  <td>{e.action}</td>
                  <td className="mono small">
                    {e.resourceType}/{e.resourceId.slice(0, 13)}
                  </td>
                  <td>
                    <Badge value={e.outcome} />
                  </td>
                  <td>
                    <Hash value={e.hash} />
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
