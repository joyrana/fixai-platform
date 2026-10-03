"""The evaluation harness itself: suites run offline and deterministically, reports carry reproducibility metadata,
and regression comparison flags cases that flip from pass to fail."""

from fixai_evals import cli, stats, suites_extra


def test_wilson_interval_and_percentiles():
    assert stats.wilson(0, 0) == (0.0, 0.0)
    low, high = stats.wilson(45, 50)
    assert 0.78 < low < 0.9 < high <= 1.0
    assert stats.percentiles([1, 2, 3, 4, 100])["p50"] == 3


async def test_agent_smoke_and_workflow_suites_pass_offline():
    for record in await suites_extra.agent_smoke() + [await suites_extra.workflow_suite()]:
        assert record.errors == [], record.errors
        assert record.metrics["task_success"]["value"] == 1.0, record.suite
        assert record.provider == "offline" and record.dataset_checksum and record.code_commit


async def test_rag_quality_reports_acl_and_quarantine():
    record = await suites_extra.rag_quality("dev")
    assert record.metrics["acl_leaks"]["value"] == 0 and record.metrics["quarantine_enforced"] is True


def test_compare_flags_regressions():
    base = {"cases": [{"id": "a", "passed": True}, {"id": "b", "passed": False}], "metrics": {"task_success": {"value": 0.5}}}
    cand = {"cases": [{"id": "a", "passed": False}, {"id": "b", "passed": True}], "metrics": {"task_success": {"value": 0.5}}}
    regressions, improvements = cli.compare(base, cand, 0.0)
    assert regressions == ["case a: passed -> failed"] and improvements == ["case b: failed -> passed"]
