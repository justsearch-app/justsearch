"""Tests for the DIFF perceptual changelog (tempdoc 615 §11 DIFF)."""
from __future__ import annotations

from jsharness.ui_diff import diff_measures


def _m(landmarks=None, elements=None, axe_ids=None, doc=None, console=None):
    return {
        "a11y_landmarks": landmarks or [],
        "geometry": {"elements": elements or {}, "document": doc or {}},
        "axe": {"violations": [{"id": i} for i in (axe_ids or [])]},
        "console_errors": console or [],
    }


class TestDiffMeasures:
    def test_identical_is_no_change(self):
        m = _m(landmarks=[{"tag": "h1", "role": None, "label": None, "text": "Search"}],
               elements={"h1": {"rect": {"x": 0, "y": 0, "w": 100, "h": 20}}},
               axe_ids=["color-contrast"])
        report = diff_measures(m, m)
        assert report["changed"] is False

    def test_new_axe_rule_surfaces(self):
        before = _m(axe_ids=["color-contrast"])
        after = _m(axe_ids=["color-contrast", "aria-valid-attr-value"])
        report = diff_measures(before, after)
        assert report["changed"] is True
        assert report["axe"]["new"] == ["aria-valid-attr-value"]
        assert report["axe"]["fixed"] == []

    def test_fixed_axe_rule(self):
        report = diff_measures(_m(axe_ids=["color-contrast"]), _m(axe_ids=[]))
        assert report["axe"]["fixed"] == ["color-contrast"]

    def test_geometry_move_over_threshold(self):
        before = _m(elements={"h1": {"rect": {"x": 0, "y": 0, "w": 100, "h": 20}}})
        after = _m(elements={"h1": {"rect": {"x": 0, "y": 8, "w": 100, "h": 20}}})
        report = diff_measures(before, after)
        assert report["changed"] is True
        assert report["geometry"]["moved"][0]["selector"] == "h1"
        assert report["geometry"]["moved"][0]["delta"]["y"] == 8

    def test_subthreshold_move_ignored(self):
        before = _m(elements={"h1": {"rect": {"x": 0, "y": 0, "w": 100, "h": 20}}})
        after = _m(elements={"h1": {"rect": {"x": 0, "y": 2, "w": 100, "h": 20}}})  # < 4px
        assert diff_measures(before, after)["changed"] is False

    def test_landmark_removed(self):
        before = _m(landmarks=[{"tag": "nav", "role": "navigation", "label": "Surfaces", "text": ""}])
        after = _m(landmarks=[])
        report = diff_measures(before, after)
        assert report["landmarks"]["removed"] and report["changed"]

    def test_overflow_flip(self):
        before = _m(doc={"overflowX": False})
        after = _m(doc={"overflowX": True})
        report = diff_measures(before, after)
        assert report["overflow"]["overflowX"] == [False, True]

    def test_real_console_delta_only_counts_app(self):
        before = _m(console=[{"category": "env-network"}])
        after = _m(console=[{"category": "env-network"}, {"category": "app"}])
        report = diff_measures(before, after)
        assert report["console_real_delta"] == 1
