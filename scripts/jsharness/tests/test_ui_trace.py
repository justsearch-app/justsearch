"""Tests for TRACE interaction-trajectory measurement (tempdoc 615 §11 TRACE).

_write_trace reuses the DIFF engine over the {pre, post} measure captures a step
produces, writing <name>.trace.json with the trajectory delta.
"""
from __future__ import annotations

import json

from jsharness.ui_check import _write_trace


def _measure(landmarks, url):
    return {
        "url": url,
        "a11y_landmarks": [{"tag": t, "role": r, "label": None, "text": ""} for t, r in landmarks],
        "geometry": {"elements": {}, "document": {}},
        "axe": {"violations": []},
        "console_errors": [],
    }


def test_trace_records_the_pre_post_delta(tmp_path):
    # pre: search results, no inspector. post: inspector pane appeared.
    (tmp_path / "inspector-open.pre.measure.json").write_text(
        json.dumps(_measure([("main", "main")], "http://x/#search")), encoding="utf-8")
    (tmp_path / "inspector-open.measure.json").write_text(
        json.dumps(_measure([("main", "main"), ("aside", "complementary")], "http://x/#search")),
        encoding="utf-8")

    _write_trace("inspector-open", tmp_path)

    trace = json.loads((tmp_path / "inspector-open.trace.json").read_text(encoding="utf-8"))
    assert trace["schema"] == "ui-trace.v1"
    assert trace["delta"]["changed"] is True
    # the inspector (aside/complementary) landmark appeared across the trajectory
    added = trace["delta"]["landmarks"]["added"]
    assert any("complementary" in row for row in added)


def test_no_trace_when_a_capture_is_missing(tmp_path):
    # Only the post capture exists -> no trace written (never crashes).
    (tmp_path / "x.measure.json").write_text(json.dumps(_measure([("main", "main")], "u")), encoding="utf-8")
    _write_trace("x", tmp_path)
    assert not (tmp_path / "x.trace.json").exists()
