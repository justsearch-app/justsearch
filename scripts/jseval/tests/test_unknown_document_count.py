"""Follow-up B1, FB2a: jseval's timeline and ingest keep an unknown document count unknown.

The Engine publishes ``indexedDocuments: 0`` with ``indexedDocumentsUnknown: true`` while it has no
count (Worker not connected yet, a failed status RPC). The timeline writes such a count as a blank
cell, its form for a value it does not have, and its summary and the ingest floor never take the
placeholder 0 for a measured count. A counted 0 stays 0.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import httpx
import pytest

from jsharness.readiness import flatten_status
from jsharness.types import ReadinessResult
from jseval.ingest import _get_indexed_doc_count, _wait_for_watcher_activity, ingest_and_wait
from jseval.timeline import compute_pipeline_summary, format_timeline_tsv, snapshot_to_row
from jseval.types import IngestConfig


def _status(indexed: int, pending: int, *, unknown: bool) -> dict:
    """An /api/status body as the Engine sends it, flattened as the readiness poll does."""
    return flatten_status({"gpu": {"gpuUtilizationPercent": 50, "usedVramBytes": 1 << 30}, "worker": {"core": {
        "indexState": "INDEXING" if pending else "IDLE", "indexedDocuments": indexed,
        "searchableDocuments": indexed, "pendingJobs": pending, "indexedDocumentsUnknown": unknown,
    }, "migration": {"pendingJobsCount": pending, "processingJobsCount": 0}}})


def _indexed_cell(rows: list[dict]) -> list[str]:
    lines = format_timeline_tsv(rows).splitlines()
    column = lines[0].split("\t").index("indexed")
    return [line.split("\t")[column] for line in lines[1:]]


def test_timeline_writes_an_unknown_document_count_blank():
    rows = [snapshot_to_row(0.0, _status(0, 0, unknown=True))]
    assert rows[0]["indexed"] is None
    assert _indexed_cell(rows) == [""]


def test_timeline_reads_the_unknown_flag_on_an_unflattened_snapshot():
    assert snapshot_to_row(0.0, {"indexedDocuments": 0, "indexedDocumentsUnknown": True})["indexed"] is None


def test_timeline_keeps_a_counted_zero():
    rows = [snapshot_to_row(0.0, _status(0, 0, unknown=False))]
    assert rows[0]["indexed"] == 0
    assert _indexed_cell(rows) == ["0"]


def test_pipeline_summary_does_not_count_from_an_unknown_placeholder():
    # 50 documents are already indexed; the first sample is taken before the Worker answers.
    rows = [snapshot_to_row(t, s) for t, s in [
        (0.0, _status(0, 0, unknown=True)),
        (2.0, _status(50, 10, unknown=False)),
        (4.0, _status(150, 0, unknown=False)),
    ]]
    summary = compute_pipeline_summary(rows)
    assert summary["primary_indexing"]["docs_indexed"] == 100


@patch("jseval.ingest.httpx.Client")
def test_ingest_initial_count_is_unknown_not_zero(MockClient):
    client = MockClient.return_value.__enter__.return_value
    client.get.return_value.json.return_value = {"worker": {"core": {
        "indexedDocuments": 0, "searchableDocuments": 0, "indexedDocumentsUnknown": True}}}
    assert _get_indexed_doc_count("http://localhost:8080") is None


def _engine_status(*cores: dict):
    """The Engine's /api/status bodies, one per read, as ingest's own HTTP reads receive them."""
    bodies = iter([{"worker": {"core": core}} for core in cores])

    def client(*_args, **_kwargs):
        mock = MagicMock()
        mock.__enter__.return_value.get.return_value.json.side_effect = lambda: next(bodies)
        return mock
    return client


_UNKNOWN = {"indexedDocuments": 0, "searchableDocuments": 0, "indexedDocumentsUnknown": True}
_FORTY = {"indexedDocuments": 40, "searchableDocuments": 40, "indexedDocumentsUnknown": False}


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity", return_value=True)
@patch("jseval.ingest.add_watched_root")
def test_ingest_floor_is_built_on_a_known_initial_count(
    mock_add_root, mock_watcher, mock_wait_idle, mock_settle, mock_watched, mock_sleep, tmp_path,
):
    # 40 documents are indexed; the Engine's first answer comes before it can count them.
    mock_wait_idle.return_value = ReadinessResult(passed=True, snapshot={"indexedDocuments": 238})
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(_UNKNOWN, _FORTY)):
        summary = ingest_and_wait(IngestConfig(base_url="http://localhost:33221"), tmp_path, corpus_doc_count=198)
    assert mock_wait_idle.call_args.kwargs["expected_doc_count_min"] == 40 + 198
    assert summary["initial_doc_count"] == 40
    assert summary["docs_indexed"] == 198


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest.time.monotonic", side_effect=[float(t) for t in range(0, 1000, 10)])
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest.add_watched_root")
def test_ingest_refuses_a_floor_on_a_count_that_stays_unknown(
    mock_add_root, mock_wait_idle, mock_watched, mock_monotonic, mock_sleep, tmp_path,
):
    # FB2c: it gives up only when its own indexing timeout runs out, and says so.
    config = IngestConfig(base_url="http://localhost:33221", index_timeout_sec=120)
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(*[_UNKNOWN] * 30)):
        with pytest.raises(RuntimeError, match=r"known indexed document count within the indexing timeout \(120 s\)"):
            ingest_and_wait(config, tmp_path, corpus_doc_count=198)
    mock_add_root.assert_not_called()
    mock_wait_idle.assert_not_called()



def _always_unknown(*_args, **_kwargs):
    mock = MagicMock()
    mock.__enter__.return_value.get.return_value.json.return_value = {"worker": {"core": dict(_UNKNOWN)}}
    return mock


@patch("jseval.ingest.time.monotonic", return_value=0.0)
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest.add_watched_root")
def test_ingest_wait_for_an_unknown_count_ends_even_when_the_clock_does_not_advance(
    mock_add_root, mock_wait_idle, mock_watched, mock_monotonic, tmp_path,
):
    # A frozen clock never reaches the deadline; the wait is bounded by its reads (120 s / 2 s).
    sleeps = []

    def sleep(seconds):
        sleeps.append(seconds)
        if len(sleeps) > 1000:
            raise AssertionError("the wait for a known count does not end while the clock stands still")

    config = IngestConfig(base_url="http://localhost:33221", index_timeout_sec=120)
    with patch("jseval.ingest.time.sleep", side_effect=sleep), \
            patch("jseval.ingest.httpx.Client", side_effect=_always_unknown):
        with pytest.raises(RuntimeError, match=r"known indexed document count within the indexing timeout \(120 s\)"):
            ingest_and_wait(config, tmp_path, corpus_doc_count=198, poll_interval_sec=2.0)
    assert len(sleeps) == 60
    mock_add_root.assert_not_called()
    mock_wait_idle.assert_not_called()

# FB2c: the initial count read. A failed read (HTTP 503, refused) is unknown, never 0; ingest waits
# for a known count within its own indexing timeout, not a separate 30 s; a known 0 is taken at once.

@patch("jseval.ingest.httpx.Client")
def test_ingest_initial_count_read_that_fails_with_503_is_unknown_not_zero(MockClient):
    response = MockClient.return_value.__enter__.return_value.get.return_value
    response.raise_for_status.side_effect = httpx.HTTPStatusError(
        "503 Service Unavailable", request=httpx.Request("GET", "http://localhost:8080/api/status"),
        response=httpx.Response(503))
    assert _get_indexed_doc_count("http://localhost:8080") is None


_ZERO = {"indexedDocuments": 0, "searchableDocuments": 0, "indexedDocumentsUnknown": False}


def _refused_then(*cores: dict):
    """The first read is refused (the Engine is not listening yet), the next ones answer."""
    answering = _engine_status(*cores)
    calls = iter(range(1000))

    def client(*args, **kwargs):
        if next(calls) == 0:
            mock = MagicMock()
            mock.__enter__.return_value.get.side_effect = httpx.ConnectError("Connection refused")
            return mock
        return answering(*args, **kwargs)
    return client


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest.time.monotonic", side_effect=[float(t) for t in range(0, 1000, 10)])
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity", return_value=True)
@patch("jseval.ingest.add_watched_root")
def test_ingest_waits_past_30_s_for_a_known_count_within_its_indexing_timeout(
    mock_add_root, mock_watcher, mock_wait_idle, mock_settle, mock_watched, mock_monotonic, mock_sleep, tmp_path,
):
    # The count stays unknown for the first 5 reads, past 30 s on this clock; the timeout is 120 s.
    mock_wait_idle.return_value = ReadinessResult(passed=True, snapshot={"indexedDocuments": 238})
    config = IngestConfig(base_url="http://localhost:33221", index_timeout_sec=120)
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(*[_UNKNOWN] * 5, _FORTY)):
        try:
            ingest_and_wait(config, tmp_path, corpus_doc_count=198)
        except RuntimeError as error:
            pytest.fail(f"gave up before its indexing timeout: {error}")
    assert mock_wait_idle.call_args.kwargs["expected_doc_count_min"] == 40 + 198


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity", return_value=True)
@patch("jseval.ingest.add_watched_root")
def test_ingest_initial_refused_read_is_not_a_floor_of_zero(
    mock_add_root, mock_watcher, mock_wait_idle, mock_settle, mock_watched, mock_sleep, tmp_path,
):
    # 40 documents are indexed; the first read is refused. The floor is 40 + 198, not 0 + 198.
    mock_wait_idle.return_value = ReadinessResult(passed=True, snapshot={"indexedDocuments": 238})
    with patch("jseval.ingest.httpx.Client", side_effect=_refused_then(_FORTY)):
        ingest_and_wait(IngestConfig(base_url="http://localhost:33221"), tmp_path, corpus_doc_count=198)
    assert mock_wait_idle.call_args.kwargs["expected_doc_count_min"] == 40 + 198


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest._root_already_watched", return_value=False)
@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity", return_value=True)
@patch("jseval.ingest.add_watched_root")
def test_ingest_accepts_a_known_zero_at_once(
    mock_add_root, mock_watcher, mock_wait_idle, mock_settle, mock_watched, mock_sleep, tmp_path,
):
    mock_wait_idle.return_value = ReadinessResult(passed=True, snapshot={"indexedDocuments": 198})
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(_ZERO)):
        summary = ingest_and_wait(IngestConfig(base_url="http://localhost:33221"), tmp_path, corpus_doc_count=198)
    mock_sleep.assert_not_called()
    assert mock_wait_idle.call_args.kwargs["expected_doc_count_min"] == 198
    assert summary["initial_doc_count"] == 0


# FB2b: every Worker count of a status that marks its counts unknown is unknown to jseval.

def test_timeline_writes_every_worker_count_of_an_unknown_status_blank():
    snapshot = flatten_status({"worker": {
        "core": {"indexState": "IDLE", "indexedDocuments": 0, "pendingJobs": 0, "writerQueueDepth": 0,
                 "writerPendingDocs": 0, "commitCount": 0, "refreshLagMs": 0, "pendingVduCount": 0,
                 "indexedDocumentsUnknown": True},
        "migration": {"pendingJobsCount": 0, "processingJobsCount": 0}}})
    row = snapshot_to_row(0.0, snapshot)
    for column in ("indexed", "pending", "writer_queue_depth", "writer_pending_docs", "commit_count",
                   "refresh_lag_ms", "vdu_pending"):
        assert row[column] is None, column
    lines = format_timeline_tsv([row]).splitlines()
    cells = dict(zip(lines[0].split("\t"), lines[1].split("\t")))
    assert (cells["pending"], cells["writer_queue_depth"]) == ("", "")


def test_timeline_keeps_counted_zero_worker_counts():
    row = snapshot_to_row(0.0, _status(0, 0, unknown=False))
    assert (row["indexed"], row["pending"]) == (0, 0)


@patch("jseval.ingest.time.sleep")
@patch("jseval.ingest.time.monotonic", side_effect=[float(t) for t in range(0, 1000, 10)])
def test_watcher_activity_is_not_read_from_a_status_without_worker_counts(mock_monotonic, mock_sleep):
    # The Engine's fallback while it has no Worker reading: not IDLE, but no Worker reading either.
    fallback = {"worker": {"core": {"indexState": "UNAVAILABLE", "indexedDocuments": 0, "pendingJobs": 0,
                                    "indexedDocumentsUnknown": True},
                           "migration": {"pendingJobsCount": 0, "processingJobsCount": 0}}}
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(*[fallback["worker"]["core"]] * 10)):
        assert _wait_for_watcher_activity("http://localhost:33221", 0, 2.0, timeout_sec=30) is False
