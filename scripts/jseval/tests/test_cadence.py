"""Tempdoc 885 item 19: NRT/commit cadence block + first-search-after-indexing probe."""

from __future__ import annotations

import json

import pytest

from jseval import cadence


# -- probe spec resolution ---------------------------------------------------

def test_resolve_probe_spec_off_by_default():
    assert cadence.resolve_probe_spec(False, cadence.DEFAULT_BATCH_MIN_FILES) is None


def test_resolve_probe_spec_uses_the_given_threshold():
    spec = cadence.resolve_probe_spec(True, 25)
    assert spec is not None
    assert spec.min_new_files == 25


def test_resolve_probe_spec_default_threshold():
    assert cadence.resolve_probe_spec(True, cadence.DEFAULT_BATCH_MIN_FILES).min_new_files == 50


@pytest.mark.parametrize("files", [0, -10])
def test_resolve_probe_spec_rejects_non_positive(files):
    with pytest.raises(ValueError, match="must be positive"):
        cadence.resolve_probe_spec(True, files)


# -- (a) percentile / aggregation math for the probe -------------------------

def test_summarize_first_search_percentiles():
    """Known sample: nearest-rank p50/p95 plus max and count."""
    block = cadence.summarize_first_search(
        [float(v) for v in range(1, 21)],  # 1..20
        errors=0, min_new_files=50, batches_fired=20,
    )
    assert block["probes_ok"] == 20
    assert block["batches_fired"] == 20
    assert block["min_new_files"] == 50
    assert block["request_timeout_sec"] == cadence.search_load.REQUEST_TIMEOUT_SEC
    assert block["errors"] == 0
    assert block["latency_ms"] == {"p50": 10.0, "p95": 19.0, "max": 20.0}


def test_summarize_first_search_sorts_before_aggregating():
    block = cadence.summarize_first_search(
        [300.0, 10.0, 50.0, 20.0], errors=1, min_new_files=10, batches_fired=5,
    )
    assert block["probes_ok"] == 4
    assert block["errors"] == 1
    assert block["latency_ms"] == {"p50": 20.0, "p95": 300.0, "max": 300.0}


def test_summarize_first_search_single_sample():
    block = cadence.summarize_first_search(
        [42.5], errors=0, min_new_files=50, batches_fired=1,
    )
    assert block["latency_ms"] == {"p50": 42.5, "p95": 42.5, "max": 42.5}


def test_summarize_first_search_no_successful_probe_has_no_latency_stats():
    block = cadence.summarize_first_search(
        [], errors=3, min_new_files=50, batches_fired=3,
    )
    assert block["probes_ok"] == 0
    assert block["errors"] == 3
    assert block["batches_fired"] == 3
    assert block["latency_ms"] is None


# -- (b) cadence block builder ----------------------------------------------

def test_build_block_with_present_metrics():
    block = cadence.build_block({
        cadence.REOPEN_TOTAL: 128.0,
        cadence.COMMIT_COUNT: 42.0,
        cadence.SEGMENTS_SINCE_REOPEN: 3.0,
    })
    assert block == {
        "reopen_total": 128,
        "commit_total": 42,
        "segments_since_reopen": 3,
        "commit_by_reason": None,
        "commit_by_reason_total": None,
        "first_search_after_indexing": None,
    }


def test_build_block_absent_metrics_are_null_not_a_crash():
    for source in (None, {}, {cadence.REOPEN_TOTAL: None}):
        block = cadence.build_block(source)
        assert block["reopen_total"] is None
        assert block["commit_total"] is None
        assert block["segments_since_reopen"] is None


def test_build_block_partial_metrics():
    block = cadence.build_block({cadence.COMMIT_COUNT: 7})
    assert block["commit_total"] == 7
    assert block["reopen_total"] is None


@pytest.mark.parametrize("bad", ["nope", [], {}, object(), True, False])
def test_build_block_malformed_values_degrade_to_null(bad):
    block = cadence.build_block({cadence.REOPEN_TOTAL: bad})
    assert block["reopen_total"] is None


def test_build_block_keeps_a_non_integral_gauge_as_float():
    block = cadence.build_block({cadence.SEGMENTS_SINCE_REOPEN: 2.5})
    assert block["segments_since_reopen"] == 2.5


def test_build_block_carries_the_probe_sub_block():
    probe = cadence.summarize_first_search([5.0], 0, 50, 1)
    block = cadence.build_block({}, first_search=probe)
    assert block["first_search_after_indexing"] is probe
    # The item-19 comparison column.
    assert block["first_search_after_indexing"]["latency_ms"]["p95"] == 5.0


def test_build_block_is_json_serializable():
    doc = cadence.build_block(
        {cadence.REOPEN_TOTAL: 4.0}, first_search=cadence.summarize_first_search([1.0], 0, 50, 1),
    )
    assert json.loads(json.dumps(doc))["reopen_total"] == 4


# -- metric collection from the telemetry NDJSON -----------------------------

def _write_worker_metrics(data_dir, records):
    path = data_dir / "telemetry" / "metrics-worker.ndjson"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(r) for r in records) + "\n", encoding="utf-8",
    )


def test_collect_worker_metrics_counters_take_the_end_of_run_total(tmp_path):
    """Counters are cumulative: the last (largest) export is the run total."""
    _write_worker_metrics(tmp_path, [
        {"t": "2026-09-02T00:00:00Z", "name": cadence.REOPEN_TOTAL, "type": "counter", "value": 5},
        {"t": "2026-09-02T00:00:10Z", "name": cadence.REOPEN_TOTAL, "type": "counter", "value": 11},
        {"t": "2026-09-02T00:00:10Z", "name": cadence.COMMIT_COUNT, "type": "counter", "value": 2},
    ])
    values = cadence.collect_worker_metrics(tmp_path)
    assert values[cadence.REOPEN_TOTAL] == 11
    assert values[cadence.COMMIT_COUNT] == 2
    assert values[cadence.SEGMENTS_SINCE_REOPEN] is None


def test_collect_worker_metrics_gauge_takes_the_last_observation(tmp_path):
    _write_worker_metrics(tmp_path, [
        {"t": "2026-09-02T00:00:20Z", "name": cadence.SEGMENTS_SINCE_REOPEN,
         "type": "gauge", "value": 9},
        {"t": "2026-09-02T00:00:00Z", "name": cadence.SEGMENTS_SINCE_REOPEN,
         "type": "gauge", "value": 1},
    ])
    # read_merged orders by timestamp, so the 00:00:20 sample wins regardless of file order.
    assert cadence.collect_worker_metrics(tmp_path)[cadence.SEGMENTS_SINCE_REOPEN] == 9


def test_collect_worker_metrics_missing_telemetry_is_all_null(tmp_path):
    values = cadence.collect_worker_metrics(tmp_path / "does-not-exist")
    assert set(values) == {
        cadence.REOPEN_TOTAL, cadence.COMMIT_COUNT, cadence.SEGMENTS_SINCE_REOPEN,
        cadence.BY_REASON_KEY,
    }
    assert all(v is None for v in values.values())


def test_collect_worker_metrics_skips_malformed_and_foreign_records(tmp_path):
    _write_worker_metrics(tmp_path, [
        {"t": "2026-09-02T00:00:00Z", "name": cadence.COMMIT_COUNT, "value": "not-a-number"},
        {"t": "2026-09-02T00:00:01Z", "name": "worker.index.something_else", "value": 999},
        {"t": "2026-09-02T00:00:02Z", "name": cadence.COMMIT_COUNT, "value": 6},
    ])
    values = cadence.collect_worker_metrics(tmp_path)
    assert values[cadence.COMMIT_COUNT] == 6


# -- (c) batch-trigger predicate --------------------------------------------

def test_trigger_first_observation_only_baselines():
    """A run against an already-populated index must not fire on the first poll."""
    trigger = cadence.BatchTrigger(50)
    assert trigger.observe(1000) is False
    assert trigger.fired == 0


def test_trigger_fires_at_the_threshold():
    trigger = cadence.BatchTrigger(50)
    trigger.observe(0)
    assert trigger.observe(49) is False
    assert trigger.observe(50) is True
    assert trigger.fired == 1


def test_trigger_does_not_fire_below_the_threshold():
    trigger = cadence.BatchTrigger(50)
    trigger.observe(0)
    for count in (1, 10, 30, 49):
        assert trigger.observe(count) is False
    assert trigger.fired == 0


def test_trigger_does_not_double_fire_for_the_same_batch():
    trigger = cadence.BatchTrigger(50)
    trigger.observe(0)
    assert trigger.observe(60) is True
    assert trigger.observe(60) is False
    assert trigger.observe(100) is False  # only 40 more since the fire
    assert trigger.observe(110) is True   # 50 more since the fire
    assert trigger.fired == 2


def test_trigger_rebaselines_when_the_index_shrinks():
    trigger = cadence.BatchTrigger(10)
    trigger.observe(100)
    assert trigger.observe(0) is False  # index reset: re-baseline, don't fire
    assert trigger.observe(9) is False
    assert trigger.observe(10) is True


@pytest.mark.parametrize("bad", [0, -1])
def test_trigger_rejects_non_positive_threshold(bad):
    with pytest.raises(ValueError, match="must be positive"):
        cadence.BatchTrigger(bad)


# -- probe runner (stubbed IO, no network) -----------------------------------

def test_probe_rejects_empty_query_pool():
    spec = cadence.resolve_probe_spec(True, 10)
    with pytest.raises(ValueError):
        cadence.FirstSearchProbe("http://127.0.0.1:1", [], spec)


def test_probe_issues_one_search_per_batch(monkeypatch):
    """Doc counts 0 -> 10 -> 12 -> 20 fire exactly twice (at 10 and at 20)."""
    counts = iter([0, 10, 12, 20, 20, 20])
    issued: list[dict] = []
    probe_ref: dict = {}

    from jseval import ingest as ingest_mod

    def fake_count(_base_url):
        try:
            return next(counts)
        except StopIteration:
            probe_ref["p"]._stop.set()
            return 20

    class _FakeClient:
        def __enter__(self):
            return self

        def __exit__(self, *_a):
            return False

        def post(self, path, json):
            issued.append({"path": path, "body": json})

            class _Resp:
                def raise_for_status(self_inner):
                    return None

            return _Resp()

    monkeypatch.setattr(ingest_mod, "_get_indexed_doc_count", fake_count)
    monkeypatch.setattr(cadence.search_load, "open_client", lambda _url: _FakeClient())

    spec = cadence.resolve_probe_spec(True, 10)
    probe = cadence.FirstSearchProbe(
        "http://127.0.0.1:1", ["alpha", "beta"], spec, poll_sec=0.001,
    )
    probe_ref["p"] = probe
    probe.start()
    probe._thread.join(timeout=5.0)
    block = probe.stop()

    assert [i["path"] for i in issued] == ["/api/knowledge/search"] * 2
    assert [i["body"]["query"] for i in issued] == ["alpha", "beta"]
    assert issued[0]["body"]["mode"] == "hybrid"
    assert issued[0]["body"]["limit"] == 10
    assert block["batches_fired"] == 2
    assert block["probes_ok"] == 2
    assert block["errors"] == 0
    assert block["min_new_files"] == 10
    assert block["latency_ms"] is not None


def test_probe_never_started_reports_an_empty_block():
    spec = cadence.resolve_probe_spec(True, 50)
    probe = cadence.FirstSearchProbe("http://127.0.0.1:1", ["alpha"], spec)
    block = probe.stop()
    assert block == {
        "request_timeout_sec": cadence.search_load.REQUEST_TIMEOUT_SEC,
        "min_new_files": 50,
        "batches_fired": 0,
        "probes_ok": 0,
        "errors": 0,
        "latency_ms": None,
    }


def test_commit_by_reason_maxes_per_reason_not_across_reasons(tmp_path):
    """Tempdoc 912 item 2: the tagged commit counter is one cumulative series PER reason.

    The name-keyed max used for untagged counters would report the largest single reason as
    the total, which is the failure this breakdown exists to avoid.
    """
    _write_worker_metrics(tmp_path, [
        {"t": "2026-09-02T00:00:00Z", "name": cadence.COMMIT_TOTAL, "type": "counter",
         "value": 4, "tags": {"reason": "timer"}},
        {"t": "2026-09-02T00:00:10Z", "name": cadence.COMMIT_TOTAL, "type": "counter",
         "value": 9, "tags": {"reason": "timer"}},
        # A Worker restart resets the cumulative counter, so the LAST sample is not the run
        # total. Keeping the max per reason is what the untagged counters already do; a
        # last-wins read would report 2 here.
        {"t": "2026-09-02T00:00:20Z", "name": cadence.COMMIT_TOTAL, "type": "counter",
         "value": 2, "tags": {"reason": "timer"}},
        {"t": "2026-09-02T00:00:10Z", "name": cadence.COMMIT_TOTAL, "type": "counter",
         "value": 3, "tags": {"reason": "indexing-loop/idle"}},
        {"t": "2026-09-02T00:00:10Z", "name": cadence.COMMIT_TOTAL, "type": "counter",
         "value": 2},
    ])
    values = cadence.collect_worker_metrics(tmp_path)
    assert values[cadence.BY_REASON_KEY] == {
        "indexing-loop/idle": 3,
        "timer": 9,
        "unknown": 2,
    }

    block = cadence.build_block(values)
    assert block["commit_by_reason"] == {
        "indexing-loop/idle": 3,
        "timer": 9,
        "unknown": 2,
    }
    assert block["commit_by_reason_total"] == 14


def test_commit_by_reason_is_null_when_the_worker_publishes_none(tmp_path):
    _write_worker_metrics(tmp_path, [
        {"t": "2026-09-02T00:00:00Z", "name": cadence.COMMIT_COUNT, "type": "counter", "value": 6},
    ])
    values = cadence.collect_worker_metrics(tmp_path)
    assert values[cadence.BY_REASON_KEY] is None
    block = cadence.build_block(values)
    assert block["commit_by_reason"] is None
    assert block["commit_by_reason_total"] is None
    assert block["commit_total"] == 6
