"""Tests for the ASSERT gate + the shared NEW-vs-known split (tempdoc 615 §11 ASSERT).

The gate's failure mode is a SILENT WRONG VERDICT (green when axe failed or a NEW,
serious or critical violation slipped), so its evaluate() + the shared split are
pinned here without needing a browser (capture_fn is injected).
"""
from __future__ import annotations

import json

import pytest

from jseval import ui_a11y_gate
from jseval.ui_measure import split_new_vs_known


class TestSplitNewVsKnown:
    def test_no_baseline_returns_none(self):
        assert split_new_vs_known([{"id": "x"}], None) == (None, None)

    def test_all_known(self):
        new, known = split_new_vs_known([{"id": "color-contrast"}], ["color-contrast"])
        assert new == [] and known == 1

    def test_new_violation_surfaces(self):
        new, known = split_new_vs_known(
            [{"id": "color-contrast"}, {"id": "aria-valid-attr-value"}], ["color-contrast"])
        assert new == ["aria-valid-attr-value"] and known == 1

    def test_empty_violations(self):
        assert split_new_vs_known([], ["color-contrast"]) == ([], 0)


def _measure_file(tmp_path, rule_ids, impact=None):
    """Write a minimal `<step>.measure.json` with the given axe rule ids; return its path."""
    p = tmp_path / "step.measure.json"
    p.write_text(json.dumps({"axe": {"violations": [
        {"id": r, "impact": impact} for r in rule_ids
    ]}}), encoding="utf-8")
    return str(p)


def _cap(measure_path, ok=True):
    return {"ok": ok, "measure": {"measure_path": measure_path}}


class TestGateEvaluate:
    @pytest.mark.parametrize("axe_result", [
        {}, {"axe": None}, {"axe": {"error": "axe.run failed"}},
        {"axe": {"error": "axe.run failed", "violations": []}},
        {"axe": {"error": None, "violations": []}},
        {"axe": {}}, {"axe": {"violations": None}},
    ])
    def test_missing_or_errored_axe_is_exit_2(self, monkeypatch, tmp_path, axe_result):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        mf = tmp_path / "home.measure.json"
        mf.write_text(json.dumps(axe_result), encoding="utf-8")
        report = ui_a11y_gate.evaluate(lambda step: _cap(str(mf)))
        assert report["exit_code"] == 2
        row = report["surfaces"][0]
        assert row["status"] == "ERROR"
        assert row["step"] == "home"
        assert "axe" in row["error"]

    @pytest.mark.parametrize("impact", ["serious", "critical"])
    @pytest.mark.parametrize("baselined", [False, True])
    def test_severe_violation_fails_even_when_baselined(self, monkeypatch, tmp_path, impact, baselined):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces", lambda: [{
            "surface": "search", "uiShotStep": "home",
            "knownRules": ["color-contrast"] if baselined else [],
        }])
        mf = tmp_path / "home.measure.json"
        violation = {"id": "color-contrast", "impact": impact}
        mf.write_text(json.dumps({"axe": {"violations": [violation]}}), encoding="utf-8")
        report = ui_a11y_gate.evaluate(lambda step: _cap(str(mf)))
        assert report["exit_code"] == 1
        row = report["surfaces"][0]
        assert row["step"] == "home"
        assert row["severe"] == [violation]
        assert row["known"] == int(baselined)
        assert row["new"] == ([] if baselined else ["color-contrast"])

    def test_clean_when_no_violations(self, monkeypatch, tmp_path):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: _cap(_measure_file(tmp_path, [])))
        assert report["exit_code"] == 0
        assert report["surfaces"][0]["status"] == "ok"

    @pytest.mark.parametrize("impact", [None, "minor", "moderate"])
    def test_clean_when_no_new(self, monkeypatch, tmp_path, impact):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": ["color-contrast"]}])
        mf = _measure_file(tmp_path, ["color-contrast"], impact)  # only low-impact accepted debt
        report = ui_a11y_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["surfaces"][0]["status"] == "ok"
        assert report["surfaces"][0]["known"] == 1

    @pytest.mark.parametrize("impact", [None, "minor", "moderate", "serious", "critical"])
    def test_fails_on_new(self, monkeypatch, tmp_path, impact):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        mf = _measure_file(tmp_path, ["aria-valid-attr-value"], impact)  # not accepted → NEW
        report = ui_a11y_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["surfaces"][0]["status"] == "NEW"
        assert "aria-valid-attr-value" in report["surfaces"][0]["new"]

    def test_capture_error_is_exit_2(self, monkeypatch):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: {"ok": False, "error": "boom"})
        assert report["exit_code"] == 2

    def test_missing_measure_file_is_exit_2(self, monkeypatch, tmp_path):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: _cap(str(tmp_path / "missing.measure.json")))
        assert report["exit_code"] == 2
        assert report["surfaces"][0]["step"] == "home"

    def test_missing_measure_is_exit_2(self, monkeypatch):
        # No measure_path on a registered surface = measurement was off → error, not a false pass.
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: {"ok": True, "measure": {}})
        assert report["exit_code"] == 2
