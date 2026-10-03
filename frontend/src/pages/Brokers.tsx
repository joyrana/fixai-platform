import { useState, type FormEvent } from "react";
import { api, FIX_VERSIONS, type Broker, type SessionConfig } from "../api";
import { Badge, Card, SelectField, Empty, ErrorBox, Hash, Loading } from "../components/ui";
import { useAction, useAsync } from "../hooks";
import { useIdentity } from "../identity";

export function BrokersPage() {
  const brokers = useAsync(() => api.brokers(), []);
  const [selected, setSelected] = useState<Broker>();
  const { can } = useIdentity();
  const manager = can("BROKER_MANAGER");

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Brokers & FIX sessions</h1>
          <p className="muted">Session configurations are TEST or UAT only, take credentials as secret references, and need approval before activation.</p>
        </div>
      </header>
      <ErrorBox error={brokers.error} />
      <div className="split">
        <Card title="Brokers">
          <Loading when={brokers.loading && !brokers.data} />
          {brokers.data?.length === 0 && <Empty>No brokers onboarded yet.</Empty>}
          <ul className="list">
            {(brokers.data ?? []).map((b) => (
              <li key={b.id}>
                <button className={`list-item ${selected?.id === b.id ? "active" : ""}`} onClick={() => setSelected(b)}>
                  <span>
                    <strong>{b.name}</strong> <code>{b.brokerCode}</code>
                  </span>
                  <Badge value={b.status} />
                </button>
              </li>
            ))}
          </ul>
          {manager && <NewBroker onCreated={(b) => (brokers.reload(), setSelected(b))} />}
        </Card>
        {selected ? <Sessions broker={selected} manager={manager} /> : <Card><Empty>Select a broker to see its FIX sessions.</Empty></Card>}
      </div>
    </>
  );
}

function NewBroker({ onCreated }: { onCreated: (b: Broker) => void }) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [endpoint, setEndpoint] = useState("fix://uat.broker.example:9876");
  const create = useAction(api.createBroker);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const broker = await create.run({ brokerCode: code, name, endpoint, status: "ACTIVE" });
    if (broker) {
      setCode("");
      setName("");
      onCreated(broker);
    }
  };
  return (
    <form className="form compact" onSubmit={submit} aria-label="Onboard broker">
      <h3>Onboard broker</h3>
      <label>
        Code
        <input value={code} onChange={(e) => setCode(e.target.value)} required maxLength={40} />
      </label>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} />
      </label>
      <label>
        Endpoint
        <input value={endpoint} onChange={(e) => setEndpoint(e.target.value)} required maxLength={255} />
      </label>
      <ErrorBox error={create.error} />
      <button className="primary" disabled={create.pending}>
        Create broker
      </button>
    </form>
  );
}

function Sessions({ broker, manager }: { broker: Broker; manager: boolean }) {
  const configs = useAsync(() => api.sessionConfigs(broker.id), [broker.id]);
  const [creating, setCreating] = useState(false);
  const [message, setMessage] = useState<string>();
  const act = useAction(async (kind: "validate" | "submit" | "activate" | "retire", config: SessionConfig) => {
    if (kind === "validate") {
      const result = await api.validateSessionConfig(config.id);
      setMessage(result.valid ? `${config.name}: valid.` : `${config.name}: ${result.violations.map((v) => `${v.field} ${v.message}`).join("; ")}`);
      return;
    }
    if (kind === "submit") {
      const justification = window.prompt("Justification for the approver (10+ characters)");
      if (!justification) return;
      await api.submitSessionConfig(config.id, justification);
      setMessage(`${config.name} submitted for approval. A different person must approve it under Approvals.`);
    } else if (kind === "activate") {
      await api.activateSessionConfig(config.id);
      setMessage(`${config.name} activated; the approval was verified against this exact configuration and consumed.`);
    } else {
      await api.retireSessionConfig(config.id);
      setMessage(`${config.name} retired.`);
    }
    configs.reload();
  });

  return (
    <Card title={`${broker.name} · FIX sessions`} actions={manager && !creating && <button onClick={() => setCreating(true)}>New session</button>}>
      {creating && <NewSession brokerId={broker.id} onDone={() => (setCreating(false), configs.reload())} />}
      <ErrorBox error={configs.error ?? act.error} />
      {message && <p className="ok">{message}</p>}
      {configs.data?.length === 0 && !creating && <Empty>No session configurations.</Empty>}
      {configs.data && configs.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Env</th>
              <th>FIX</th>
              <th>CompIDs</th>
              <th>Endpoint</th>
              <th>Status</th>
              <th>Approved hash</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {configs.data.map((c) => (
              <tr key={c.id}>
                <td>
                  {c.name}
                  <div className="muted small mono">{c.id}</div>
                </td>
                <td>{c.environment}</td>
                <td>{c.fixVersion}</td>
                <td className="mono">
                  {c.senderCompId} → {c.targetCompId}
                </td>
                <td className="mono">
                  {c.host}:{c.port}
                </td>
                <td>
                  <Badge value={c.status} />
                </td>
                <td>
                  <Hash value={c.approvedPayloadHash} />
                </td>
                <td className="actions">
                  <button onClick={() => act.run("validate", c)}>Validate</button>
                  {manager && (c.status === "DRAFT" || c.status === "CHANGES_REQUESTED" || c.status === "REJECTED") && (
                    <button onClick={() => act.run("submit", c)}>Submit</button>
                  )}
                  {manager && c.status === "PENDING_APPROVAL" && (
                    <button className="primary" onClick={() => act.run("activate", c)}>
                      Activate
                    </button>
                  )}
                  {manager && c.status !== "RETIRED" && <button onClick={() => act.run("retire", c)}>Retire</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function NewSession({ brokerId, onDone }: { brokerId: string; onDone: () => void }) {
  const [form, setForm] = useState({
    name: "UAT order routing",
    environment: "UAT",
    fixVersion: "FIX44",
    senderCompId: "",
    targetCompId: "",
    host: "",
    port: 9876,
    heartbeatIntervalSeconds: 30,
    reconnectIntervalSeconds: 5,
    resetOnLogon: true,
    credentialRef: "",
  });
  const create = useAction((body: Record<string, unknown>) => api.createSessionConfig(brokerId, body));
  const set = (key: keyof typeof form) => (e: { target: { value: string; type?: string; checked?: boolean } }) =>
    setForm((f) => ({ ...f, [key]: e.target.type === "checkbox" ? e.target.checked : e.target.type === "number" ? Number(e.target.value) : e.target.value }));
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (await create.run({ ...form, credentialRef: form.credentialRef || null })) onDone();
  };
  return (
    <form className="form" onSubmit={submit} aria-label="New session configuration">
      <div className="grid3">
        <label>
          Name
          <input value={form.name} onChange={set("name")} required />
        </label>
        <SelectField label="Environment" value={form.environment} onChange={set("environment")}>
            <option>UAT</option>
            <option>TEST</option>
        </SelectField>
        <SelectField label="FIX version" value={form.fixVersion} onChange={set("fixVersion")}>
            {FIX_VERSIONS.map((v) => (
              <option key={v}>{v}</option>
            ))}
        </SelectField>
        <label>
          SenderCompID
          <input value={form.senderCompId} onChange={set("senderCompId")} required />
        </label>
        <label>
          TargetCompID
          <input value={form.targetCompId} onChange={set("targetCompId")} required />
        </label>
        <label>
          Host
          <input value={form.host} onChange={set("host")} required placeholder="uat.broker.example" />
        </label>
        <label>
          Port
          <input type="number" value={form.port} onChange={set("port")} min={1} max={65535} />
        </label>
        <label>
          Heartbeat (s)
          <input type="number" value={form.heartbeatIntervalSeconds} onChange={set("heartbeatIntervalSeconds")} min={1} max={300} />
        </label>
        <label>
          Credential reference
          <input value={form.credentialRef} onChange={set("credentialRef")} placeholder="vault:fix/broker/uat" />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={form.resetOnLogon} onChange={set("resetOnLogon")} /> ResetSeqNumFlag on logon
      </label>
      <p className="hint">Raw passwords are refused. Store secrets in the secret store and reference them here.</p>
      <ErrorBox error={create.error} />
      <div className="row">
        <button className="primary" disabled={create.pending}>
          Create draft
        </button>
        <button type="button" onClick={onDone}>
          Cancel
        </button>
      </div>
    </form>
  );
}
