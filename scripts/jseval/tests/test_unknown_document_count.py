"""Follow-up B1, FB2a: jseval's timeline and ingest keep an unknown document count unknown.

The Engine publishes ``indexedDocuments: 0`` with ``indexedDocumentsUnknown: true`` while it has no
count (Worker not connected yet, a failed status RPC). The timeline writes such a count as a blank
cell, its form for a value it does not have, and its summary and the ingest floor never take the
placeholder 0 for a measured count. A counted 0 stays 0.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from jsharness.readiness import flatten_status
from jsharness.types import ReadinessResult
from jseval.ingest import _get_indexed_doc_count, ingest_and_wait
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
    with patch("jseval.ingest.httpx.Client", side_effect=_engine_status(*[_UNKNOWN] * 20)):
        with pytest.raises(RuntimeError, match="does not know its indexed document count"):
            ingest_and_wait(IngestConfig(base_url="http://localhost:33221"), tmp_path, corpus_doc_count=198)
    mock_add_root.assert_not_called()
    mock_wait_idle.assert_not_called()
