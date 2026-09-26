"""Tests for ui_perf.py — UI latency measurement."""

from __future__ import annotations

import httpx
import pytest

from jseval import ui_perf
from jseval.ui_perf import (
    SLO_CLICK_MS,
    SLO_KEYSTROKE_MS,
    _build_result,
    format_console,
)


@pytest.mark.parametrize("invocation", [
    {"success": False, "message": "refused"},
    {"success": True, "structuredData": {}},
])
def test_setup_refuses_failed_or_unidentified_ingest(monkeypatch, tmp_path, invocation):
    requests = []

    def handle(request):
        requests.append(request.url.path)
        if request.url.path == "/api/debug/effective-config":
            return httpx.Response(200, json={"justsearch.data.dir": str(tmp_path)})
        assert request.url.path == "/api/knowledge/ingest"
        return httpx.Response(200, json=invocation)

    client = httpx.Client
    monkeypatch.setattr(ui_perf.httpx, "Client", lambda **kwargs: client(
        **kwargs, transport=httpx.MockTransport(handle),
    ))
    with pytest.raises(RuntimeError, match="Ingest operation was not accepted"):
        ui_perf._setup_test_corpus("http://127.0.0.1:12345")
    assert requests == ["/api/debug/effective-config", "/api/knowledge/ingest"]


class TestBuildResult:
    def test_basic(self):
        measurements = [
            {"kind": "keystroke_to_results_paint", "duration_ms": 200, "iteration": 0},
            {"kind": "keystroke_to_results_paint", "duration_ms": 250, "iteration": 1},
            {"kind": "click_to_preview_visible", "duration_ms": 60, "iteration": 0},
            {"kind": "click_to_preview_visible", "duration_ms": 70, "iteration": 1},
        ]
        r = _build_result(measurements, iterations=2, warmup=1)
        assert r["schema"] == "ui-perf.v1"
        ks = r["summary"]["keystroke_to_results_paint"]
        assert ks["count"] == 2
        assert ks["p50_ms"] is not None
        assert ks["slo_ms"] == SLO_KEYSTROKE_MS
        assert ks["slo_pass_rate"] == 1.0  # both under 300ms

        click = r["summary"]["click_to_preview_visible"]
        assert click["count"] == 2
        assert click["slo_ms"] == SLO_CLICK_MS
        assert click["slo_pass_rate"] == 1.0  # both under 200ms

    def test_slo_failure(self):
        measurements = [
            {"kind": "keystroke_to_results_paint", "duration_ms": 400, "iteration": 0},
        ]
        r = _build_result(measurements, iterations=1, warmup=0)
        ks = r["summary"]["keystroke_to_results_paint"]
        assert ks["slo_pass_rate"] == 0.0  # 400ms > 300ms SLO

    def test_empty_measurements(self):
        r = _build_result([], iterations=0, warmup=0)
        assert r["summary"]["keystroke_to_results_paint"] == {}
        assert r["summary"]["click_to_preview_visible"] == {}

    def test_none_durations_excluded(self):
        measurements = [
            {"kind": "keystroke_to_results_paint", "duration_ms": None, "iteration": 0},
            {"kind": "keystroke_to_results_paint", "duration_ms": 200, "iteration": 1},
        ]
        r = _build_result(measurements, iterations=2, warmup=0)
        assert r["summary"]["keystroke_to_results_paint"]["count"] == 1


class TestFormatConsole:
    def test_output_with_data(self):
        result = {
            "iterations": 4, "warmup": 1,
            "measurements": [
                {"kind": "keystroke_to_results_paint", "duration_ms": 220,
                 "iteration": 0, "breakdown_ms": {
                     "event_to_response_ms": 160,
                     "response_to_dom_visible_ms": 30,
                     "dom_visible_to_next_paint_ms": 25,
                 }},
            ],
            "summary": {
                "keystroke_to_results_paint": {
                    "count": 4, "p50_ms": 220, "p95_ms": 270, "p99_ms": 280,
                    "mean_ms": 235, "slo_ms": 300, "slo_pass_rate": 1.0,
                },
                "click_to_preview_visible": {
                    "count": 4, "p50_ms": 62, "p95_ms": 65, "p99_ms": 68,
                    "mean_ms": 63, "slo_ms": 200, "slo_pass_rate": 1.0,
                },
            },
        }
        output = format_console(result)
        assert "Keystroke" in output
        assert "p50=220ms" in output
        assert "PASS" in output
        assert "event→response=160ms" in output

    def test_empty_data(self):
        result = {
            "iterations": 0, "warmup": 0,
            "measurements": [],
            "summary": {
                "keystroke_to_results_paint": {},
                "click_to_preview_visible": {},
            },
        }
        output = format_console(result)
        assert "no data" in output
