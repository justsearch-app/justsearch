"""Follow-up B1, FB2a: a document count the Engine does not know stays unknown through the flatten.

While the Engine has no Worker reading (not connected yet, a failed status RPC) or cannot count its
index, ``worker.core`` keeps ``indexedDocuments`` and ``searchableDocuments`` at 0 for older readers
and sets ``indexedDocumentsUnknown``. The flattened status the harness and jseval read must say
unknown (None) there, never a known 0; a counted 0 stays 0.
"""

from __future__ import annotations

from jsharness.readiness import _check_index_idle_conditions, flatten_status

# The core the Engine published at startup before its Worker answered (verifier capture, B1).
_UNCOUNTED_CORE = {"indexState": "PENDING", "indexedDocuments": 0, "searchableDocuments": 0,
                   "pendingJobs": 0, "indexedDocumentsUnknown": True}


def test_unknown_document_counts_flatten_to_none():
    out = flatten_status({"worker": {"core": dict(_UNCOUNTED_CORE)}})
    assert out["indexedDocuments"] is None
    assert out["searchableDocuments"] is None
    assert out["indexedDocumentsUnknown"] is True
    assert out["pendingJobs"] is None  # FB2b: the flag covers every Worker count


def test_counted_zero_stays_zero():
    out = flatten_status({"worker": {"core": {**_UNCOUNTED_CORE, "indexState": "IDLE",
                                              "indexedDocumentsUnknown": False}}})
    assert out["indexedDocuments"] == 0
    assert out["searchableDocuments"] == 0


def test_idle_reader_does_not_measure_an_unknown_count_against_its_floor():
    reasons = _check_index_idle_conditions(flatten_status({"worker": {"core": dict(_UNCOUNTED_CORE)}}), 5)
    assert "indexed_doc_count_unknown" in reasons
    assert not any(reason.startswith("indexed_doc_count_below_floor") for reason in reasons), reasons


# FB2b: a status that marks its counts unknown carries no Worker count at all (verifier replay of a
# failed-opening Engine: flattened pendingJobs and indexSizeBytes read 0 beside an unknown count).
_UNKNOWN_WORKER = {
    "core": {"indexState": "IDLE", "indexedDocuments": 0, "searchableDocuments": 0, "pendingJobs": 3,
             "indexSizeBytes": 4096, "writerQueueDepth": 2, "pendingVduCount": 1, "indexHealthy": True,
             "indexedDocumentsUnknown": True},
    "migration": {"migrationState": "IDLE", "pendingJobsCount": 3, "processingJobsCount": 1},
    "failure": {"failedJobs": 2, "lastError": ""},
}


def test_every_worker_count_of_an_unknown_status_flattens_to_none():
    out = flatten_status({"worker": {k: dict(v) for k, v in _UNKNOWN_WORKER.items()}})
    for key in ("indexedDocuments", "pendingJobs", "indexSizeBytes", "writerQueueDepth", "pendingVduCount",
                "pendingJobsCount", "processingJobsCount", "failedJobs"):
        assert out[key] is None, key
    # What is not a count is kept: states, flags, words.
    assert (out["indexState"], out["migrationState"], out["indexHealthy"], out["lastError"]) == ("IDLE", "IDLE", True, "")


def test_counted_zero_worker_counts_stay_zero():
    worker = {k: dict(v) for k, v in _UNKNOWN_WORKER.items()}
    worker["core"].update(indexedDocumentsUnknown=False, pendingJobs=0, indexSizeBytes=0)
    worker["migration"].update(pendingJobsCount=0, processingJobsCount=0)
    worker["failure"]["failedJobs"] = 0
    out = flatten_status({"worker": worker})
    assert [out[k] for k in ("pendingJobs", "indexSizeBytes", "pendingJobsCount", "failedJobs")] == [0, 0, 0, 0]
