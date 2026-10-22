"""Tests for ui_perf.py — UI latency measurement."""

from __future__ import annotations

import httpx
import pytest

from jseval import readiness, ui_perf
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


@pytest.mark.parametrize("state", ["WAITING", "INDEXING", "FAILED"])
def test_setup_refuses_an_unsettled_queue_before_measuring(monkeypatch, tmp_path, state):
    _run_setup_queue(monkeypatch, tmp_path, [state], must_settle=False)


def test_setup_waits_through_retry_and_processing_before_measuring(monkeypatch, tmp_path):
    _run_setup_queue(monkeypatch, tmp_path, ["WAITING", "INDEXING", "IDLE"], must_settle=True)


def _run_setup_queue(monkeypatch, tmp_path, states, *, must_settle):
    clock = [0.0]
    observed = []
    searched = []

    def handle(request):
        if request.url.path == "/api/debug/effective-config":
            return httpx.Response(200, json={"justsearch.data.dir": str(tmp_path)})
        if request.url.path == "/api/knowledge/ingest":
            return httpx.Response(200, json={"success": True, "structuredData": {"operationKey": "perf"}})
        if request.url.path == "/api/status":
            state = states[min(len(observed), len(states) - 1)]
            observed.append(state)
            pending = 0 if state == "IDLE" else 2
            return httpx.Response(200, json={"worker": {
                "core": {"indexState": state, "pendingJobs": pending, "indexedDocuments": 2},
                "migration": {"pendingBackoffJobsCount": pending if state == "WAITING" else 0},
            }})
        assert request.url.path == "/api/knowledge/search"
        searched.append(clock[0])
        return httpx.Response(200, json={"totalHits": 2})

    client = httpx.Client
    monkeypatch.setattr(ui_perf.httpx, "Client", lambda **kwargs: client(
        **kwargs, transport=httpx.MockTransport(handle),
    ))
    monkeypatch.setattr(readiness.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(readiness.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    if must_settle:
        ui_perf._setup_test_corpus("http://127.0.0.1:12345")
        assert observed[:3] == states
        assert observed.count("IDLE") >= 2
        assert len(searched) == 1 and searched[0] < 10, "nested status must settle without exhausting the budget"
    else:
        with pytest.raises(RuntimeError, match="indexing did not settle"):
            ui_perf._setup_test_corpus("http://127.0.0.1:12345")
        assert not searched, "unfinished indexing must not enter performance measurement"
