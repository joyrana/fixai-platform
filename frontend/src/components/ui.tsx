import { useId, type ChangeEvent, type ReactNode } from "react";
import { ApiError } from "../api";

const TONES: Record<string, string> = {
  PASSED: "good", COMPLETED: "info", APPROVED: "good", ACTIVE: "good", DONE: "info", UP: "good",
  FAILED: "bad", ERROR: "bad", REJECTED: "bad", INCONCLUSIVE: "warn", CANCELLED: "muted", EXPIRED: "muted",
  RETIRED: "muted", SUSPENDED: "warn", CONSUMED: "muted", RUNNING: "warn", QUEUED: "warn", PENDING: "warn",
  PENDING_APPROVAL: "warn", CHANGES_REQUESTED: "warn", WAITING_FOR_HUMAN: "warn", STARTING: "warn", DRAFT: "muted",
  HIGH: "bad", MEDIUM: "warn", LOW: "info", SKIPPED: "muted",
};

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="badge muted">—</span>;
  return <span className={`badge ${TONES[value] ?? "info"}`}>{value.replaceAll("_", " ")}</span>;
}

export function ErrorBox({ error }: { error: Error | undefined }) {
  if (!error) return null;
  const codes = error instanceof ApiError && error.codes.length ? ` (${error.codes.join(", ")})` : "";
  const status = error instanceof ApiError ? `${error.status}: ` : "";
  return (
    <div className="alert" role="alert">
      {status}
      {error.message}
      {codes}
    </div>
  );
}

export function Loading({ when }: { when: boolean }) {
  return when ? <p className="muted">Loading…</p> : null;
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="row">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export function When({ at }: { at: string | null | undefined }) {
  if (!at) return <span className="muted">—</span>;
  const date = new Date(at);
  return <time dateTime={at} title={date.toISOString()}>{date.toLocaleString()}</time>;
}

export function Hash({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="muted">—</span>;
  return <code title={value}>{value.slice(0, 12)}…</code>;
}

/** A select with an explicitly associated label (a wrapping label would fold every option into the accessible name). */
export function SelectField({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (event: ChangeEvent<HTMLSelectElement>) => void;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={onChange}>
        {children}
      </select>
    </div>
  );
}
