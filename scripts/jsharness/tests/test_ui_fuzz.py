"""Tests for the GENERATE fuzzer's variant transform + anomaly detection (615 §11)."""
from __future__ import annotations

import json

from jsharness import ui_fixtures, ui_fuzz


class TestVariants:
    def test_empty_variant_zeros_the_search_results(self):
        body = json.loads(ui_fixtures.fixture_body("http://x/api/knowledge/search", variant="empty"))
        assert body["results"] == []
        assert body["totalHits"] == 0 and body["matchCount"] == 0

    def test_default_variant_keeps_results(self):
        body = json.loads(ui_fixtures.fixture_body("http://x/api/knowledge/search", variant="default"))
        assert len(body["results"]) > 0

    def test_variant_does_not_affect_other_endpoints(self):
        assert ui_fixtures.fixture_body("http://x/api/status", variant="empty") == \
            ui_fixtures.fixture_body("http://x/api/status", variant="default")


class TestCellAnomalies:
    def _summary(self, tmp_path, rule_ids, overflow=None, console_real=0):
        mp = tmp_path / "cell.measure.json"
        mp.write_text(json.dumps({"axe": {"violations": [{"id": r} for r in rule_ids]}}), encoding="utf-8")
        return {"measure_path": str(mp), "overflow": overflow, "console_real": console_real}

    def test_known_axe_is_not_flagged(self, tmp_path):
        s = self._summary(tmp_path, ["aria-valid-attr-value"])
        assert ui_fuzz.cell_anomalies(s, ["aria-valid-attr-value"]) == []

    def test_new_axe_flagged(self, tmp_path):
        s = self._summary(tmp_path, ["color-contrast"])
        a = ui_fuzz.cell_anomalies(s, ["aria-valid-attr-value"])
        assert a == ["axe-NEW:color-contrast"]

    def test_overflow_flagged(self, tmp_path):
        s = self._summary(tmp_path, [], overflow=["x"])
        assert "overflow:x" in ui_fuzz.cell_anomalies(s, [])

    def test_console_real_flagged(self, tmp_path):
        s = self._summary(tmp_path, [], console_real=2)
        assert "console-real:2" in ui_fuzz.cell_anomalies(s, [])
