import { useState, type FormEvent } from "react";
import { api, type AgentEnvelope, type KnowledgeAnswer } from "../api";
import { Card, ErrorBox } from "../components/ui";
import { useAction } from "../hooks";

const EXAMPLES = [
  "How must a counterparty answer a TestRequest?",
  "How should a ResendRequest be answered?",
  "How should a duplicate ClOrdID be handled?",
];

export function KnowledgePage() {
  const [question, setQuestion] = useState("");
  const [result, setResult] = useState<AgentEnvelope<KnowledgeAnswer>>();
  const ask = useAction(api.ask);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const answer = await ask.run(question);
    if (answer) setResult(answer);
  };
  return (
    <>
      <header className="page-head">
        <div>
          <h1>Knowledge</h1>
          <p className="muted">Answers come only from approved documentation, with verified quotes. The agent says so when the documents do not answer.</p>
        </div>
      </header>
      <Card>
        <form className="form" onSubmit={submit} aria-label="Ask">
          <label>
            Question
            <input value={question} onChange={(e) => setQuestion(e.target.value)} minLength={3} maxLength={1000} required />
          </label>
          <div className="row">
            <button className="primary" disabled={ask.pending}>
              {ask.pending ? "Searching…" : "Ask"}
            </button>
            {EXAMPLES.map((q) => (
              <button key={q} type="button" className="link" onClick={() => setQuestion(q)}>
                {q}
              </button>
            ))}
          </div>
        </form>
        <ErrorBox error={ask.error} />
      </Card>
      {result && (
        <Card title={result.output.abstained ? "No supported answer" : "Answer"}>
          {result.output.abstained ? <p className="muted">{result.output.abstain_reason}</p> : <p>{result.output.answer}</p>}
          {result.output.citations.map((c, i) => (
            <blockquote key={i}>
              {c.quote}
              <cite>
                {" "}
                — {c.title} <code>{c.doc_id}</code>
              </cite>
            </blockquote>
          ))}
          <p className="hint">
            {result.metadata.agent} · {result.metadata.provider}/{result.metadata.model} · {result.metadata.latency_ms} ms
          </p>
        </Card>
      )}
    </>
  );
}
