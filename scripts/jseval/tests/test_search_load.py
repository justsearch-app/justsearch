"""Tempdoc 885: background search-load scheduler arithmetic + summary block."""

from __future__ import annotations

import pytest

from jseval import search_load


# -- spec resolution ---------------------------------------------------------

def test_resolve_spec_absent_is_none():
    assert search_load.resolve_spec(None, False) is None


def test_resolve_spec_qpm():
    spec = search_load.resolve_spec(10, False)
    assert spec is not None
    assert spec.mode == "qpm"
    assert spec.qpm == 10


def test_resolve_spec_continuous_has_no_qpm():
    spec = search_load.resolve_spec(None, True)
    assert spec is not None
    assert spec.mode == "continuous"
    assert spec.qpm is None


def test_resolve_spec_rejects_both():
    with pytest.raises(ValueError, match="mutually exclusive"):
        search_load.resolve_spec(10, True)


@pytest.mark.parametrize("qpm", [0, -5])
def test_resolve_spec_rejects_non_positive_qpm(qpm):
    with pytest.raises(ValueError, match="must be positive"):
        search_load.resolve_spec(qpm, False)


# -- scheduler arithmetic ----------------------------------------------------

@pytest.mark.parametrize(
    ("qpm", "expected"),
    [(1, 60.0), (10, 6.0), (60, 1.0), (120, 0.5)],
)
def test_interval_sec(qpm, expected):
    assert search_load.interval_sec(qpm) == pytest.approx(expected)


def test_due_at_is_evenly_spaced():
    start = 1000.0
    due = [search_load.due_at(start, i, 10) for i in range(4)]
    assert due == pytest.approx([1000.0, 1006.0, 1012.0, 1018.0])


def test_due_at_rejects_negative_index():
    with pytest.raises(ValueError):
        search_load.due_at(0.0, -1, 10)


def test_wait_sec_counts_down_to_the_deadline():
    start = 100.0
    # query 2 of a 10 qpm schedule is due at 112.0
    assert search_load.wait_sec(100.0, start, 2, 10) == pytest.approx(12.0)
    assert search_load.wait_sec(111.5, start, 2, 10) == pytest.approx(0.5)


def test_wait_sec_is_zero_when_behind_schedule():
    """A slow request must not push later queries out; the schedule is absolute."""
    assert search_load.wait_sec(200.0, 100.0, 2, 10) == 0.0


# -- percentiles -------------------------------------------------------------

def test_percentile_nearest_rank():
    values = [float(v) for v in range(1, 21)]  # 1..20
    assert search_load.percentile(values, 50) == 10.0
    assert search_load.percentile(values, 95) == 19.0
    assert search_load.percentile(values, 100) == 20.0


def test_percentile_single_sample():
    assert search_load.percentile([7.0], 50) == 7.0
    assert search_load.percentile([7.0], 95) == 7.0


def test_percentile_rejects_empty():
    with pytest.raises(ValueError):
        search_load.percentile([], 50)


# -- summary block -----------------------------------------------------------

def test_summarize_shape_and_values():
    spec = search_load.resolve_spec(10, False)
    block = search_load.summarize(
        spec,
        latencies_ms=[30.0, 10.0, 20.0, 40.0],
        errors=2,
        started_at="2026-09-02T00:00:00+00:00",
        ended_at="2026-09-02T00:01:00+00:00",
        duration_s=60.0,
        query_pool_size=300,
    )
    assert block["mode"] == "qpm"
    assert block["qpm"] == 10
    assert block["search_mode"] == "hybrid"
    assert block["queries_issued"] == 6  # 4 ok + 2 errors
    assert block["queries_ok"] == 4
    assert block["errors"] == 2
    assert block["request_timeout_sec"] == search_load.REQUEST_TIMEOUT_SEC
    assert block["query_pool_size"] == 300
    assert block["duration_s"] == 60.0
    assert block["started_at"] == "2026-09-02T00:00:00+00:00"
    assert block["ended_at"] == "2026-09-02T00:01:00+00:00"
    assert block["latency_ms"] == {"p50": 20.0, "p95": 40.0, "max": 40.0}


def test_summarize_all_errors_has_no_latency_stats():
    spec = search_load.resolve_spec(None, True)
    block = search_load.summarize(
        spec, latencies_ms=[], errors=3,
        started_at="a", ended_at="b", duration_s=1.0, query_pool_size=5,
    )
    assert block["mode"] == "continuous"
    assert block["qpm"] is None
    assert block["queries_issued"] == 3
    assert block["queries_ok"] == 0
    assert block["latency_ms"] is None


# -- runner ------------------------------------------------------------------

def test_runner_rejects_empty_query_pool():
    spec = search_load.resolve_spec(None, True)
    with pytest.raises(ValueError):
        search_load.SearchLoadRunner("http://127.0.0.1:1", [], spec)


def _stub_client(monkeypatch, issued, on_post):
    class _Resp:
        def raise_for_status(self):
            return None

    class _FakeClient:
        def __init__(self, *_a, **_kw):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *_a):
            return False

        def post(self, path, json):
            issued.append({"path": path, "body": json})
            return on_post(len(issued)) or _Resp()

    monkeypatch.setattr(search_load.httpx, "Client", _FakeClient)


def test_runner_issues_queries_and_reports_block(monkeypatch):
    """End-to-end over a stubbed httpx.Client: cycles the pool, records latencies."""
    issued: list[dict] = []
    runner_ref: dict = {}

    def on_post(count):
        if count >= 3:
            runner_ref["r"]._stop.set()
        return None

    _stub_client(monkeypatch, issued, on_post)
    spec = search_load.resolve_spec(None, True)
    runner = search_load.SearchLoadRunner("http://127.0.0.1:1", ["alpha", "beta"], spec)
    runner_ref["r"] = runner
    runner.start()
    runner._thread.join(timeout=5.0)
    assert not runner._thread.is_alive(), "fixture must finish its finite request sequence"
    block = runner.stop()

    assert [i["path"] for i in issued] == ["/api/knowledge/search"] * 3
    assert [i["body"]["query"] for i in issued] == ["alpha", "beta", "alpha"]
    assert issued[0]["body"]["mode"] == "hybrid"
    assert issued[0]["body"]["limit"] == 10
    assert block["queries_issued"] == 3
    assert block["queries_ok"] == 3
    assert block["errors"] == 0
    assert block["query_pool_size"] == 2
    assert block["latency_ms"] is not None


def test_runner_counts_failed_requests_as_errors(monkeypatch):
    issued: list[dict] = []
    runner_ref: dict = {}

    def on_post(count):
        if count >= 2:
            runner_ref["r"]._stop.set()
        raise RuntimeError("boom")

    _stub_client(monkeypatch, issued, on_post)
    spec = search_load.resolve_spec(None, True)
    runner = search_load.SearchLoadRunner("http://127.0.0.1:1", ["alpha"], spec)
    runner_ref["r"] = runner
    runner.start()
    runner._thread.join(timeout=5.0)
    assert not runner._thread.is_alive(), "fixture must finish its finite request sequence"
    block = runner.stop()

    assert block["queries_issued"] == 2
    assert block["queries_ok"] == 0
    assert block["errors"] == 2
    assert block["latency_ms"] is None


def test_load_client_covers_the_existing_retrieval_budget(monkeypatch):
    """A CPU response within the normal retriever budget must not become overlapping load."""
    import inspect
    import httpx
    from jseval import retriever

    original_client = httpx.Client
    attempts = []

    def response(request):
        attempts.append(request)
        # Recorded primary423 response: 45.941s, inside the existing CPU RPC allowance.
        if request.extensions["timeout"]["read"] < 45.941:
            raise httpx.ReadTimeout("response still executing at the client deadline", request=request)
        return httpx.Response(200, json={})

    def client_with_transport(**kwargs):
        return original_client(transport=httpx.MockTransport(response), **kwargs)

    monkeypatch.setattr(search_load.httpx, "Client", client_with_transport)
    with search_load.open_client("http://test") as client:
        assert search_load.issue_search(client, search_load.search_body("query")) is not None
    assert len(attempts) == 1, "load traffic must never retry a sample"
    assert search_load.REQUEST_TIMEOUT_SEC == inspect.signature(retriever.retrieve).parameters["timeout"].default


def test_load_timeout_is_still_a_failure_with_a_visible_type(caplog):
    import httpx

    attempts = []

    def response(request):
        attempts.append(request)
        raise httpx.ReadTimeout("timed out", request=request)

    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(response)) as client:
        assert search_load.issue_search(client, search_load.search_body("query")) is None
    assert len(attempts) == 1
    assert "ReadTimeout" in caplog.text
