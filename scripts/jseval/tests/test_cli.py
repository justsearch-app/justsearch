"""Tests for cli.py — CLI entry points."""

from __future__ import annotations

import json

from click.testing import CliRunner

from jseval.cli import main


def test_main_help():
    runner = CliRunner()
    result = runner.invoke(main, ["--help"])
    assert result.exit_code == 0
    assert "JustSearch search evaluation toolkit" in result.output


def test_run_help():
    runner = CliRunner()
    result = runner.invoke(main, ["run", "--help"])
    assert result.exit_code == 0
    assert "--dataset" in result.output
    assert "--modes" in result.output


def test_run_help_includes_warmup_flag():
    """E-J-N10: --warmup N flag must be exposed in run --help."""
    runner = CliRunner()
    result = runner.invoke(main, ["run", "--help"])
    assert result.exit_code == 0
    assert "--warmup" in result.output
    assert "warmup iterations" in result.output.lower()


def test_run_rejects_negative_warmup():
    """--warmup must be non-negative."""
    runner = CliRunner()
    result = runner.invoke(main, ["run", "--dataset", "scifact", "--warmup", "-1"])
    assert result.exit_code != 0
    assert "warmup" in result.output.lower()


def test_requery_help():
    runner = CliRunner()
    result = runner.invoke(main, ["requery", "--help"])
    assert result.exit_code == 0
    assert "--dataset" in result.output


def test_compare_help():
    runner = CliRunner()
    result = runner.invoke(main, ["compare", "--help"])
    assert result.exit_code == 0
    # Tempdoc 525: the --bucket-by flag MUST appear in the help text (the
    # CLI is the named consumer for the per-decision-kind stratification).
    assert "--bucket-by" in result.output
    assert "decision_kind" in result.output


def test_compare_by_decision_kind_buckets_metrics():
    """Tempdoc 525: --bucket-by decision_kind aggregates by SearchIntrospection.decision.kind."""
    from jseval.commands.analysis import _compare_by_decision_kind

    a_data = {
        "per_query_entries": [
            {"qid": "q1", "decision_kind": "sparse_shortcut",
             "ndcgAtK": 0.8, "apAtK": 0.7},
            {"qid": "q2", "decision_kind": "sparse_shortcut",
             "ndcgAtK": 0.6, "apAtK": 0.5},
            {"qid": "q3", "decision_kind": "multi_leg",
             "ndcgAtK": 0.4, "apAtK": 0.3},
            {"qid": "q4", "decision_kind": None,
             "ndcgAtK": 0.2, "apAtK": 0.1},
        ]
    }
    b_data = {
        "per_query_entries": [
            {"qid": "q1", "decision_kind": "sparse_shortcut",
             "ndcgAtK": 0.85, "apAtK": 0.75},
            {"qid": "q2", "decision_kind": "sparse_shortcut",
             "ndcgAtK": 0.55, "apAtK": 0.45},
            {"qid": "q3", "decision_kind": "multi_leg",
             "ndcgAtK": 0.5, "apAtK": 0.4},
            {"qid": "q4", "decision_kind": None,
             "ndcgAtK": 0.25, "apAtK": 0.15},
        ]
    }
    result = _compare_by_decision_kind(a_data, b_data, {})
    assert set(result.keys()) == {"sparse_shortcut", "multi_leg", "unknown"}
    assert result["sparse_shortcut"]["count_a"] == 2
    assert result["sparse_shortcut"]["count_b"] == 2
    # Delta direction is correct.
    ndcg = result["sparse_shortcut"]["metrics"]["nDCG@10"]
    assert ndcg["mean_a"] == 0.7  # (0.8 + 0.6) / 2
    assert ndcg["mean_b"] == 0.7  # (0.85 + 0.55) / 2
    assert ndcg["delta"] == 0.0
    # multi_leg shows positive delta.
    ml = result["multi_leg"]["metrics"]["nDCG@10"]
    assert ml["mean_a"] == 0.4
    assert ml["mean_b"] == 0.5
    assert abs(ml["delta"] - 0.1) < 1e-9
    # None decision_kind lands in unknown bucket.
    assert result["unknown"]["count_a"] == 1


def test_compare_by_decision_kind_empty_when_no_per_query_entries():
    """No per-query entries → no bucketing (graceful fallback)."""
    from jseval.commands.analysis import _compare_by_decision_kind
    assert _compare_by_decision_kind({}, {}, {}) == {}
    assert _compare_by_decision_kind(
        {"per_query_entries": []}, {"per_query_entries": []}, {}
    ) == {}


def test_trend_help():
    runner = CliRunner()
    result = runner.invoke(main, ["trend", "--help"])
    assert result.exit_code == 0
    assert "--dataset" in result.output


def test_materialize_help():
    runner = CliRunner()
    result = runner.invoke(main, ["materialize", "--help"])
    assert result.exit_code == 0
    assert "--dataset" in result.output


def _ui_check_result(*, ok: bool) -> dict:
    return {
        "schema": "ui-check.v1",
        "ok": ok,
        "elapsed_ms": 12.0,
        "output_dir": "captures",
        "total_shots": 1,
        "total_passed": 1 if ok else 0,
        "shots": [{
            "name": "settings",
            "ok": ok,
            "required": True,
            "elapsed_ms": 12.0,
            **({} if ok else {"error": "required setup failed"}),
        }],
    }


def test_ui_check_required_failure_preserves_output_and_exits_nonzero(monkeypatch, tmp_path):
    from jseval import ui_check

    monkeypatch.setattr(ui_check, "execute_ui_check", lambda **_kwargs: _ui_check_result(ok=False))
    result = CliRunner().invoke(main, ["ui-check", "--output-dir", str(tmp_path)])

    assert result.exit_code == 1
    assert "UI Check: FAIL (0/1 screenshots)" in result.output
    artifact = json.loads((tmp_path / "ui-check.json").read_text(encoding="utf-8"))
    assert artifact["ok"] is False
    assert artifact["shots"][0]["required"] is True


def test_ui_check_success_preserves_zero_exit(monkeypatch, tmp_path):
    from jseval import ui_check

    monkeypatch.setattr(ui_check, "execute_ui_check", lambda **_kwargs: _ui_check_result(ok=True))
    result = CliRunner().invoke(main, ["ui-check", "--output-dir", str(tmp_path)])

    assert result.exit_code == 0
    assert "UI Check: PASS (1/1 screenshots)" in result.output
    assert json.loads((tmp_path / "ui-check.json").read_text(encoding="utf-8"))["ok"] is True
