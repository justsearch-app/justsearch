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
    assert out["pendingJobs"] == 0  # only the qualified counts change


def test_counted_zero_stays_zero():
    out = flatten_status({"worker": {"core": {**_UNCOUNTED_CORE, "indexState": "IDLE",
                                              "indexedDocumentsUnknown": False}}})
    assert out["indexedDocuments"] == 0
    assert out["searchableDocuments"] == 0


def test_idle_reader_does_not_measure_an_unknown_count_against_its_floor():
    reasons = _check_index_idle_conditions(flatten_status({"worker": {"core": dict(_UNCOUNTED_CORE)}}), 5)
    assert "indexed_doc_count_unknown" in reasons
    assert not any(reason.startswith("indexed_doc_count_below_floor") for reason in reasons), reasons
