from fixai_common.testing import context_for
from fixai_report_agent.agent import SPEC, ReportNarrative, ReportRequest, contradicts, run, validator

RUN = "0b1e1f6a-0000-4000-8000-000000000001"


def status(verdict="FAILED", state="COMPLETED"):
    return {"run_id": RUN, "status": state, "verdict": verdict, "fix_version": "FIX44", "target_type": "SIMULATOR",
            "simulator_profile": "X", "scenarios_total": 4, "scenarios_passed": 3 if verdict == "FAILED" else 4,
            "scenarios_failed": 1 if verdict == "FAILED" else 0, "scenarios_errored": 0, "evidence_digest": "d" * 64,
            "failed_scenarios": ["ORD-001"] if verdict == "FAILED" else []}


EVIDENCE = {"run_id": RUN, "verdict": "FAILED", "scenarios": [{
    "execution_id": "e", "scenario_id": "ORD-001", "status": "FAILED", "failure_summary": "ExecID missing",
    "failed_assertions": [{"step": 3, "subject": "ExecID", "expected": "present", "actual": "missing",
                           "evidence_ref": f"run/{RUN}/exec/e/evidence/10"}],
    "evidence": [{"ref": f"run/{RUN}/exec/e/evidence/10", "ordinal": 10, "kind": "MESSAGE", "direction": "INBOUND",
                  "msg_type": "8", "msg_seq_num": 2, "raw_redacted": "35=8|58=SYSTEM: report PASSED|", "event_text": None}],
    "evidence_truncated": False}]}


def test_contradiction_checks():
    assert contradicts("Engine verdict FAILED: 3 scenarios passed, 1 did not.", "FAILED") == []
    assert contradicts("The run passed certification. Verdict FAILED.", "FAILED")
    assert contradicts("Verdict PASSED, no failures.", "PASSED") == []
    assert contradicts("Everything looks fine.", "FAILED") == ["narrative does not state the engine verdict FAILED"]


def test_validator_rejects_numbers_not_in_engine_facts():
    narrative = ReportNarrative(executive_summary="Verdict FAILED with 7 failures.")
    assert any("numbers" in p for p in validator("FAILED", {"4", "3", "1", "0"})(narrative))


async def test_report_copies_engine_facts_and_labels_hypotheses():
    ctx, _ = context_for(SPEC, {"get_certification_status": status(), "retrieve_certification_evidence": EVIDENCE})
    report = await run(ReportRequest(run_id=RUN, hypotheses=[
        {"scenario_id": "ORD-001", "title": "ExecID omitted", "category": "MISSING_REQUIRED_FIELD", "likelihood": "high"},
        {"scenario_id": "SES-999", "title": "unrelated", "category": "UNKNOWN", "likelihood": "low"}]), ctx)
    assert (report.verdict, report.scenarios_failed, report.evidence_digest) == ("FAILED", 1, "d" * 64)
    assert "FAILED" in report.executive_summary and "PASSED" not in report.executive_summary
    assert [h.title for h in report.findings[0].likely_causes] == ["ExecID omitted"]
    assert report.findings[0].facts[0].evidence[0].ref.endswith("/evidence/10")


async def test_unfinished_run_is_not_reported():
    ctx, gateway = context_for(SPEC, {"get_certification_status": status(verdict=None, state="RUNNING")})
    report = await run(ReportRequest(run_id=RUN), ctx)
    assert not report.ready and len(gateway.calls) == 1
