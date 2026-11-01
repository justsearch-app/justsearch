"""Tests for run.py — orchestration (all dependencies mocked)."""

from __future__ import annotations

import json
import os
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from jseval.run import (
    METRIC_CONTRACT,
    _build_index_state_at_query,
    _build_summary,
    _compute_ce_coverage,
    _compute_chunk_completeness,
    _compute_latency_stats,
    _compute_pipeline_tracking,
    _compute_qrels_summary,
    _compute_score_stats,
    _get_corpus_identity,
    _settle_index,
    execute_run,
)
from jseval.types import AnnProofResult, ComparabilityResult, QueryRecord, ReadinessResult
from jseval import raw_corpus_manifest as rcm


@pytest.fixture(autouse=True)
def _offline_backend(monkeypatch):
    """Stub the three helpers in ``execute_run`` that open a socket.

    Nothing in this module runs against a live backend, but ``_MOCK_STACK`` does not
    cover ``_snapshot_models`` / ``_snapshot_search_config`` (module-private) nor
    ``manifest.capture_state_snapshots`` (``manifest_mod`` is unpatched). Un-stubbed
    each one waits out an httpx connect against ``http://localhost:8080`` — eight
    requests at ~4 s, ~34 s per ``execute_run`` test — for values every test ignores.

    The stubs return exactly what the real helpers return when the backend is absent:
    ``None`` from both snapshots (the ``except`` arms at ``run.py:98-100`` and
    ``run.py:161-163``) and a per-endpoint ``{"_error": ...}`` marker from the
    manifest capture (``manifest.py:157``). Production timeouts are untouched.
    """
    from jseval import manifest as manifest_mod
    from jseval import run as run_mod

    monkeypatch.setattr(run_mod, "_snapshot_models", lambda base_url: None)
    monkeypatch.setattr(run_mod, "_snapshot_search_config", lambda base_url: None)
    monkeypatch.setattr(
        manifest_mod, "capture_state_snapshots",
        lambda base_url, timeout=5.0: {
            ep: {"_error": "ConnectError"} for ep in manifest_mod._STATE_ENDPOINTS
        },
    )


def _setup_mocks(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp,
    queries=None, qrels=None,
):
    queries = queries or {
        "q1": QueryRecord(text="test query"),
        "q2": QueryRecord(text="another query"),
    }
    qrels = qrels or {"q1": {"d1": 1}, "q2": {"d2": 1}}
    mock_corpora.load.return_value = (
        queries, qrels,
        MagicMock(name="scifact", doc_count=100, query_count=len(queries)),
    )
    mock_readiness.check_search_ready.return_value = ReadinessResult(passed=True)
    mock_retriever.retrieve.return_value = (
        [MagicMock(query_id="q1", doc_id="d1", score=1.0)],
        [{"query_id": "q1", "tookMs": 15}],
    )
    mock_scoring.evaluate.return_value = {"nDCG@10": 0.731}
    mock_scoring.evaluate_per_query.return_value = {"q1": {"nDCG@10": 0.731}}
    mock_provenance.extract_query_evidence.return_value = {
        "effective_mode": "HYBRID", "error": None,
    }
    mock_provenance.aggregate_run_evidence.return_value = {
        "error_count": 0, "component_status_counts": {},
    }
    mock_ann.compute_ann_proof.return_value = AnnProofResult(status="PASS")
    mock_comp.determine_comparability.return_value = ComparabilityResult(comparable=True)


_MOCK_STACK = [
    "jseval.run.history_mod",
    "jseval.run.artifacts_mod",
    "jseval.run.comparability_mod",
    "jseval.run.ann_proof_mod",
    "jseval.run.provenance",
    "jseval.run.scoring",
    "jseval.run.retriever",
    "jseval.run.readiness",
    "jseval.run.corpora",
]


def test_raw_ingest_only_summary_and_manifest_share_strict_identity(tmp_path, monkeypatch):
    base = tmp_path / "datasets"
    root = base / "mixed" / "raw"
    corpus = root / "corpus-dir"
    corpus.mkdir(parents=True)
    (corpus / "one.pdf").write_bytes(b"one")
    (root / "metadata.json").write_text(json.dumps({"raw_files": True}), encoding="utf-8")
    context = rcm.resolve_raw_corpus_context("mixed/raw", base_dir=base)
    monkeypatch.setenv("JUSTSEARCH_CORPUS_SIGNATURE", "lower-precedence-host-value")

    with patch("jseval.run._snapshot_models", return_value={}), \
            patch("jseval.run._capture_env_fingerprint", return_value={}), \
            patch("jseval.manifest.capture_state_snapshots", return_value={}):
        summary = execute_run(
            "mixed/raw", "http://localhost:33221", [], base_dir=base,
            env_overrides={"JUSTSEARCH_CORPUS_SIGNATURE": context.identity.digest},
        )

    expected = context.to_corpus_identity()
    assert summary["doc_count"] == context.identity.file_count
    assert summary["corpus_identity"] == expected
    assert summary["manifest"]["doc_count"] == context.identity.file_count
    assert summary["manifest"]["corpus_identity"] == expected


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_basic_flow(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert summary["dataset"] == "scifact"
    assert "hybrid" in summary["per_mode"]
    assert summary["per_mode"]["hybrid"]["comparable"] is True
    assert "metric_contract" in summary
    assert "qrels_summary" in summary
    assert "corpus_identity" in summary
    assert "pipeline_tracking" in summary["per_mode"]["hybrid"]
    mock_corpora.load.assert_called_once()
    mock_retriever.retrieve.assert_called_once()


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_skip_readiness(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    execute_run("scifact", "http://localhost:8080", ["lexical"], skip_readiness=True)
    mock_readiness.check_search_ready.assert_not_called()


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_max_queries(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    # 50 queries in dataset, cap at 5
    queries = {f"q{i}": QueryRecord(text=f"query {i}") for i in range(50)}
    qrels = {f"q{i}": {f"d{i}": 1} for i in range(50)}
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp, queries=queries, qrels=qrels)

    execute_run("scifact", "http://localhost:8080", ["lexical"], max_queries=5)

    # Retriever should receive only 5 queries
    call_args = mock_retriever.retrieve.call_args
    passed_queries = call_args[0][0]
    assert len(passed_queries) == 5


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_qrels_filter(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    # Queries has keys not in qrels — should be filtered
    queries = {
        "q1": QueryRecord(text="query 1"),
        "q2": QueryRecord(text="query 2"),
        "q_no_qrels": QueryRecord(text="no judgments"),
    }
    qrels = {"q1": {"d1": 1}, "q2": {"d2": 1}}
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp, queries=queries, qrels=qrels)

    execute_run("scifact", "http://localhost:8080", ["lexical"])

    call_args = mock_retriever.retrieve.call_args
    passed_queries = call_args[0][0]
    assert "q_no_qrels" not in passed_queries
    assert len(passed_queries) == 2


# ---------------------------------------------------------------------------
# Q-020 / F-046: query_syntax threading + provenance
# ---------------------------------------------------------------------------

@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_forwards_query_syntax_to_retriever(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    execute_run("scifact", "http://localhost:8080", ["hybrid"], query_syntax="lucene")

    _, kwargs = mock_retriever.retrieve.call_args
    assert kwargs["query_syntax"] == "lucene"


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_default_query_syntax_forwards_none(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    """Default (no --query-syntax) forwards `query_syntax=None` to the retriever, which
    is what makes the wire request byte-identical to a pre-Q-020 call (test_retriever.py
    pins that `_build_request(..., query_syntax=None)` omits the field)."""
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    execute_run("scifact", "http://localhost:8080", ["hybrid"])

    _, kwargs = mock_retriever.retrieve.call_args
    assert kwargs["query_syntax"] is None


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_records_query_syntax_in_summary_provenance(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"], query_syntax="lucene")
    assert summary["query_syntax"] == "lucene"


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_default_query_syntax_recorded_as_simple(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    """Run provenance is self-documenting even on the default path: absent `query_syntax`
    is recorded as "simple" (the Head's own documented server-side default), not omitted
    or left null, per api-contract-map.md."""
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])
    assert summary["query_syntax"] == "simple"


# ---------------------------------------------------------------------------
# Unit tests for helper functions
# ---------------------------------------------------------------------------

class TestComputeQrelsSummary:
    def test_binary(self):
        qrels = {"q1": {"d1": 1, "d2": 0}, "q2": {"d3": 1}}
        s = _compute_qrels_summary(qrels)
        assert s["relevance_mode"] == "binary"
        assert s["query_count"] == 2
        assert s["max_relevance"] == 1
        assert s["queries_with_relevant"] == 2

    def test_graded(self):
        qrels = {"q1": {"d1": 2, "d2": 1}, "q2": {"d3": 0}}
        s = _compute_qrels_summary(qrels)
        assert s["relevance_mode"] == "graded"
        assert s["max_relevance"] == 2
        assert s["queries_with_relevant"] == 1  # q2 has no relevant docs

    def test_empty(self):
        s = _compute_qrels_summary({})
        assert s["query_count"] == 0
        assert s["queries_with_relevant"] == 0


class TestComputePipelineTracking:
    def test_lexical_all_observed(self):
        # BM25 (sparse) is implicit — no components expected or observed
        evidence = {"component_status_counts": {}}
        pt = _compute_pipeline_tracking("lexical", evidence)
        assert pt["requested"] == []
        assert pt["mismatch_reasons"] == []

    def test_hybrid_dense_missing(self):
        # hybrid expects dense; sparse is implicit and not tracked
        evidence = {"component_status_counts": {}}
        pt = _compute_pipeline_tracking("hybrid", evidence)
        assert "requested_dense_but_not_observed" in pt["mismatch_reasons"]

    def test_unexpected_component(self):
        evidence = {"component_status_counts": {
            "splade": {"executed": 10},
        }}
        pt = _compute_pipeline_tracking("lexical", evidence)
        assert "unexpected_splade_observed" in pt["mismatch_reasons"]

    def test_empty_evidence(self):
        pt = _compute_pipeline_tracking("hybrid", {"component_status_counts": {}})
        assert len(pt["mismatch_reasons"]) == 1  # only dense missing


class TestGetCorpusIdentity:
    def test_reads_env_vars(self):
        with patch.dict(os.environ, {
            "JUSTSEARCH_CORPUS_PROFILE_ID": "stub-jaccard",
            "JUSTSEARCH_CORPUS_SIGNATURE": "abc123",
        }):
            ci = _get_corpus_identity()
            assert ci["profile_id"] == "stub-jaccard"
            assert ci["signature"] == "abc123"

    def test_missing_env_vars(self):
        with patch.dict(os.environ, {}, clear=True):
            ci = _get_corpus_identity()
            assert ci["profile_id"] is None
            assert ci["signature"] is None

    def test_computes_mixed_signature_from_files(self, tmp_path):
        # tempdoc 623 ③: a mixed corpus is pinned by sha256(corpus.jsonl + qrels/test.tsv).
        from types import SimpleNamespace
        ds = "mixed/demo-200"
        ddir = tmp_path / ds
        (ddir / "qrels").mkdir(parents=True)
        (ddir / "corpus.jsonl").write_text('{"_id":"d1","text":"hello"}\n', encoding="utf-8")
        (ddir / "qrels" / "test.tsv").write_text("q1\td1\t1\n", encoding="utf-8")
        meta = SimpleNamespace(source="mixed", name=ds)
        with patch.dict(os.environ, {}, clear=True):
            sig = _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"]
        assert isinstance(sig, str) and len(sig) == 64
        # deterministic: same inputs → same signature
        with patch.dict(os.environ, {}, clear=True):
            sig2 = _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"]
        assert sig == sig2
        # content change → different signature
        (ddir / "corpus.jsonl").write_text('{"_id":"d1","text":"CHANGED"}\n', encoding="utf-8")
        with patch.dict(os.environ, {}, clear=True):
            assert _get_corpus_identity(ds, meta, {}, base_dir=tmp_path)["signature"] != sig

    def test_computes_beir_signature_from_id_and_qrels(self):
        # tempdoc 623 ③: BEIR is pinned by sha256({ir_datasets_id, qrels}) — .txt files
        # materialize only after ingest, so hash the stable id + binary qrels.
        from types import SimpleNamespace
        meta = SimpleNamespace(source="beir", name="scifact")
        with patch.dict(os.environ, {}, clear=True):
            sig = _get_corpus_identity("scifact", meta, {"q1": {"d1": 1}})["signature"]
        assert isinstance(sig, str) and len(sig) == 64
        # different qrels → different signature
        with patch.dict(os.environ, {}, clear=True):
            assert _get_corpus_identity("scifact", meta, {"q1": {"d2": 1}})["signature"] != sig

    def test_env_signature_overrides_computed(self):
        from types import SimpleNamespace
        meta = SimpleNamespace(source="beir", name="scifact")
        with patch.dict(os.environ, {"JUSTSEARCH_CORPUS_SIGNATURE": "operator-pin"}):
            assert _get_corpus_identity("scifact", meta, {"q1": {"d1": 1}})["signature"] == "operator-pin"


class TestComputeLatencyStats:
    def test_basic(self):
        responses = [
            {"query_id": "q1", "tookMs": 10},
            {"query_id": "q2", "tookMs": 20},
            {"query_id": "q3", "tookMs": 30},
            {"query_id": "q4", "tookMs": 40},
            {"query_id": "q5", "tookMs": 50},
        ]
        s = _compute_latency_stats(responses)
        assert s["query_count"] == 5
        assert s["mean_ms"] == 30.0
        assert s["p50_ms"] == 30
        assert s["min_ms"] == 10
        assert s["max_ms"] == 50

    def test_skips_errors(self):
        responses = [
            {"query_id": "q1", "tookMs": 10},
            {"query_id": "q2", "error": "failed"},
            {"query_id": "q3", "tookMs": 30},
        ]
        s = _compute_latency_stats(responses)
        assert s["query_count"] == 2
        assert s["mean_ms"] == 20.0

    def test_empty(self):
        s = _compute_latency_stats([])
        assert s["query_count"] == 0

    def test_single_query(self):
        s = _compute_latency_stats([{"query_id": "q1", "tookMs": 42}])
        assert s["query_count"] == 1
        assert s["p50_ms"] == 42
        assert s["p95_ms"] == 42
        assert s["p99_ms"] == 42


class TestComputeScoreStats:
    def test_basic(self):
        responses = [
            {"query_id": "q1", "results": [
                {"score": 0.9}, {"score": 0.5}, {"score": 0.3},
            ]},
            {"query_id": "q2", "results": [
                {"score": 0.7}, {"score": 0.4},
            ]},
        ]
        s = _compute_score_stats(responses, top_k=10)
        assert s["mean_top1_score"] == round((0.9 + 0.7) / 2, 4)
        assert s["mean_topk_score"] is not None

    def test_skips_errors(self):
        responses = [
            {"query_id": "q1", "results": [{"score": 0.8}]},
            {"query_id": "q2", "error": "failed"},
        ]
        s = _compute_score_stats(responses, top_k=10)
        assert s["mean_top1_score"] == 0.8

    def test_empty(self):
        s = _compute_score_stats([], top_k=10)
        assert s["mean_top1_score"] is None

    def test_respects_top_k(self):
        responses = [
            {"query_id": "q1", "results": [
                {"score": 1.0}, {"score": 0.8}, {"score": 0.6}, {"score": 0.1},
            ]},
        ]
        s = _compute_score_stats(responses, top_k=2)
        assert s["mean_topk_score"] == round((1.0 + 0.8) / 2, 4)


class TestMetricContract:
    def test_static_values(self):
        assert METRIC_CONTRACT["gain_function"] == "linear"
        assert METRIC_CONTRACT["unjudged_policy"] == "not_relevant"


# ---------------------------------------------------------------------------
# tempdoc 718: chunk-completeness block (_compute_chunk_completeness / _build_summary)
# ---------------------------------------------------------------------------

def _write_golden_corpus(tmp_path, dataset_name, docs):
    ddir = tmp_path / dataset_name
    ddir.mkdir(parents=True, exist_ok=True)
    with (ddir / "corpus.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")
    return ddir


def _status_snapshot(chunk_doc_count, coverage_pct, chunk_min_chars=2000):
    # tempdoc 821 §3-C3: the worker's enrichment auditor publishes the chunk threshold, and
    # `_compute_chunk_completeness` computes its offline expectation against THAT number instead
    # of a jseval-side mirror. `chunk_min_chars=None` models a backend predating that field.
    enrichment = {"chunk": {
        "chunkDocCount": chunk_doc_count,
        "chunkVectorCoveragePercent": coverage_pct,
    }}
    if chunk_min_chars is not None:
        enrichment["chunkMinChars"] = chunk_min_chars
    return {"worker": {"enrichment": enrichment}}


def _mode_result(pipeline_tracking_observed=(), run_evidence=None):
    return {
        "pipeline_tracking": {"observed": list(pipeline_tracking_observed)},
        "run_evidence": run_evidence or {},
    }


class TestComputeChunkCompleteness:
    def test_healthy_run_is_ok(self, tmp_path):
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense", "chunk_merge"])},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "ok"
        assert block["expected"] == 1
        assert block["observed"] == 48

    def test_degenerate_run_is_flagged(self, tmp_path):
        # The tempdoc-717 case: long docs present, but the index shipped with 0 chunk docs.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense"])},  # no chunk_merge
            _status_snapshot(0, 0.0),
            tmp_path,
        )
        assert block["verdict"] == "degenerate"
        assert block["expected"] == 1
        assert block["observed"] == 0
        assert block["reasons"]

    def test_short_doc_corpus_is_chunk_free(self, tmp_path):
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": "short doc"},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense"])},  # no chunk_merge -- must not matter here
            _status_snapshot(0, 0.0),
            tmp_path,
        )
        assert block["verdict"] == "chunk-free"

    def test_vector_mode_not_run_does_not_penalize_missing_chunk_merge(self, tmp_path):
        # chunk_merge is a query-time corroborator only meaningful when `vector` mode ran;
        # a lexical-only run must not be flagged degenerate for lacking it.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"lexical": _mode_result([])},  # no "vector" key at all
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "ok"

    def test_missing_status_snapshot_is_unevaluable_not_a_clean_pass(self, tmp_path):
        # Pre-821 this read `degenerate`, because a missing snapshot made observed=0 while the
        # expectation came from a jseval-side threshold mirror. With the mirror retired (821
        # §3-C3) the threshold comes from that same snapshot, so a run with NO snapshot has no
        # expectation to compare against. It must NOT borrow `chunk-free`: that verdict asserts
        # "no corpus doc reaches the threshold", which this path never computed and which is
        # affirmatively wrong here (the corpus has a 2500-char doc).
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo", {"vector": _mode_result(["chunk_merge"])}, None, tmp_path,
        )
        assert block["verdict"] == "unevaluable"
        assert block["observed"] == 0
        assert block["threshold_chars"] is None
        assert any("chunkMinChars" in r for r in block["reasons"])
        # No stale reason survives alongside it: the stand-down REPLACES the reason list rather
        # than prepending to a `chunk-free` explanation that was never computed.
        assert not any("no corpus doc reaches" in r for r in block["reasons"])

    def test_backend_publishing_zero_threshold_stands_down(self, tmp_path):
        # chunkMinChars is a proto3 scalar, so an old backend does not OMIT it — it reports 0.
        # This is the real-world shape, and it must stand down exactly like an absent field.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense"])},  # no chunk_merge -- would be degenerate if gated
            _status_snapshot(0, 0.0, chunk_min_chars=0),
            tmp_path,
        )
        assert block["verdict"] == "unevaluable"
        assert block["threshold_chars"] is None
        assert block["expected"] == 0

    def test_backend_omitting_the_threshold_stands_down(self, tmp_path):
        # Belt-and-braces for a payload that genuinely lacks the key (a hand-built snapshot, or a
        # projection that drops zero-valued fields).
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense"])},
            _status_snapshot(0, 0.0, chunk_min_chars=None),
            tmp_path,
        )
        assert block["verdict"] == "unevaluable"
        assert block["threshold_chars"] is None

    def test_expectation_follows_the_published_threshold(self, tmp_path):
        # Discriminating: the SAME corpus expects chunks under a 2000-char threshold and expects
        # none under a 5000-char one. A surviving hard-coded mirror could not produce both.
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": "x" * 2500},
        ])
        healthy = {"vector": _mode_result(["dense", "chunk_merge"])}
        low = _compute_chunk_completeness(
            "golden/demo", healthy, _status_snapshot(48, 100.0, chunk_min_chars=2000), tmp_path,
        )
        high = _compute_chunk_completeness(
            "golden/demo", healthy, _status_snapshot(48, 100.0, chunk_min_chars=5000), tmp_path,
        )
        assert (low["expected"], low["threshold_chars"]) == (1, 2000)
        assert (high["expected"], high["threshold_chars"], high["verdict"]) == (0, 5000, "chunk-free")

    def test_beir_dataset_with_no_local_corpus_jsonl_is_chunk_free(self, tmp_path):
        # BEIR datasets materialize via ir_datasets; there is no local corpus.jsonl for
        # `_compute_chunk_completeness` to read, so it must degrade gracefully, never crash.
        block = _compute_chunk_completeness(
            "scifact", {"vector": _mode_result([])}, _status_snapshot(0, 0.0), tmp_path,
        )
        assert block["verdict"] == "chunk-free"

    # -- tempdoc 715 defect 1: engine-declared short-corpus skip is not a strike ------------

    def test_short_corpus_skip_declared_is_not_a_strike(self, tmp_path):
        # mixed/miracl-de-2k reproduction (scaled down): SearchPlanner.java:267 skips
        # chunk-merge with SKIPPED_SHORT_CORPUS by engine design
        # (CorpusCapabilities.corpusSupportsChunks() false, median-token-count short corpus)
        # even though a handful of docs individually reach the chunk threshold. This is
        # legitimate, not a degeneracy -- the corroborator must not fire just because
        # "chunk_merge" is absent from the observed component set, PROVIDED the corpus really
        # is shaped short (chunk-eligible-doc rate < 0.05, mirroring
        # CorpusProfile.chunkRate() < 0.05): 1 long doc among 40 short ones is rate 1/40 = 0.025.
        long_text = "x" * 2500
        docs = [{"_id": "d1", "title": "", "text": long_text}]
        docs += [{"_id": f"s{i}", "title": "", "text": "short doc"} for i in range(39)]
        _write_golden_corpus(tmp_path, "golden/demo", docs)
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(
                ["dense"],  # chunk_merge absent from observed
                run_evidence={
                    "chunk_merge_skip_reason_counts": {"SKIPPED_SHORT_CORPUS": 305},
                },
            )},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "ok"

    def test_no_chunk_docs_skip_declared_is_not_a_strike(self, tmp_path):
        long_text = "x" * 2500
        docs = [{"_id": "d1", "title": "", "text": long_text}]
        docs += [{"_id": f"s{i}", "title": "", "text": "short doc"} for i in range(39)]
        _write_golden_corpus(tmp_path, "golden/demo", docs)
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(
                ["dense"],
                run_evidence={
                    "chunk_merge_skip_reason_counts": {"SKIPPED_NO_CHUNK_DOCS": 305},
                },
            )},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "ok"

    def test_717_class_regression_with_high_chunk_rate_still_strikes(self, tmp_path):
        # tempdoc 717/718 review: a 717-class regression -- a healthy, chunked index whose
        # chunk_merge leg is nonetheless skipped SKIPPED_SHORT_CORPUS at query time due to the
        # same corpus-shape misclassification 717 fixed -- must NOT be silently waived just
        # because the skip reason is in the engine-declared corpus-shape set. legal-clerc-200
        # regression shape: ~194 of 198 docs reach the chunk threshold (rate ~0.98, nowhere near
        # short). The observed signals look otherwise healthy (chunk docs present, full
        # coverage), so `chunk_merge_observed` staying False is the ONLY thing that can catch
        # this -- and it must degrade the verdict.
        long_text = "x" * 2500
        docs = [{"_id": f"d{i}", "title": "", "text": long_text} for i in range(194)]
        docs += [{"_id": f"s{i}", "title": "", "text": "short doc"} for i in range(4)]
        _write_golden_corpus(tmp_path, "golden/demo", docs)
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(
                ["dense"],  # chunk_merge absent from observed -- the 717-class symptom
                run_evidence={
                    "chunk_merge_skip_reason_counts": {"SKIPPED_SHORT_CORPUS": 50},
                },
            )},
            _status_snapshot(9700, 100.0),  # observed counts look healthy on their own
            tmp_path,
        )
        assert block["verdict"] == "degenerate"
        assert any("chunk_merge" in r for r in block["reasons"])

    def test_beir_dataset_with_short_corpus_skip_reason_stays_waived(self, tmp_path):
        # No local corpus.jsonl (a BEIR dataset) means there is no offline shape to check the
        # skip reason against -- the waiver must fall back to its prior unconditional behavior
        # (corpus_total_docs <= 0) rather than refuse to waive for lack of evidence.
        block = _compute_chunk_completeness(
            "scifact",
            {"vector": _mode_result(
                [],  # chunk_merge absent from observed
                run_evidence={
                    "chunk_merge_skip_reason_counts": {"SKIPPED_SHORT_CORPUS": 12},
                },
            )},
            _status_snapshot(0, 0.0),
            tmp_path,
        )
        assert block["verdict"] == "chunk-free"

    def test_chunk_merge_absent_without_any_skip_reason_stays_degenerate(self, tmp_path):
        # No run_evidence skip-reason info at all (e.g. an older caller/shape) must not be
        # silently treated as "not applicable" -- absence of evidence is not evidence of a
        # legitimate skip.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(["dense"], run_evidence={})},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "degenerate"
        assert any("chunk_merge" in r for r in block["reasons"])

    def test_chunk_merge_absent_with_non_corpus_shape_reason_stays_degenerate(self, tmp_path):
        # A real failure (e.g. vector-blocked) must still read as a strike, even when it's
        # mixed with a legitimate corpus-shape skip reason on other queries.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(
                ["dense"],
                run_evidence={
                    "chunk_merge_skip_reason_counts": {
                        "SKIPPED_SHORT_CORPUS": 200, "SKIPPED_VECTOR_BLOCKED": 5,
                    },
                },
            )},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "degenerate"

    def test_healthy_run_with_chunk_merge_present_unaffected_by_skip_reasons_key(self, tmp_path):
        # Existing "ok" path (chunk_merge present in observed) must stay unaffected regardless
        # of whatever (irrelevant) skip-reason data happens to be present too.
        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        block = _compute_chunk_completeness(
            "golden/demo",
            {"vector": _mode_result(
                ["dense", "chunk_merge"],
                run_evidence={"chunk_merge_skip_reason_counts": {"SKIPPED_SHORT_CORPUS": 1}},
            )},
            _status_snapshot(48, 100.0),
            tmp_path,
        )
        assert block["verdict"] == "ok"


class TestBuildSummaryEmbedsChunkCompleteness:
    def test_run_shape_carries_chunk_completeness_block(self, tmp_path):
        from types import SimpleNamespace

        long_text = "x" * 2500
        _write_golden_corpus(tmp_path, "golden/demo", [
            {"_id": "d1", "title": "", "text": long_text},
        ])
        meta = SimpleNamespace(source="golden", name="golden/demo", doc_count=1, query_count=0)
        mode_results = {
            "vector": {
                "aggregate_metrics": {}, "ann_proof": AnnProofResult(status="PASS"),
                "comparability": ComparabilityResult(comparable=True), "run_evidence": {},
                "pipeline_tracking": {"observed": ["dense", "chunk_merge"]},
                "latency_stats": {}, "score_stats": {},
            },
        }
        summary = _build_summary(
            "golden/demo", ["vector"], mode_results, meta, {},
            base_dir=tmp_path, status_snapshot=_status_snapshot(48, 100.0),
        )
        assert "chunk_completeness" in summary
        assert summary["chunk_completeness"]["verdict"] == "ok"
        assert set(summary["chunk_completeness"]) == {
            "expected", "observed", "verdict", "reasons", "threshold_chars",
        }
        # 821 §3-C3 provenance: the expectation was computed against the PUBLISHED threshold.
        assert summary["chunk_completeness"]["threshold_chars"] == 2000


# ---------------------------------------------------------------------------
# register F-052: cross-encoder-coverage block (_compute_ce_coverage / _build_summary)
# ---------------------------------------------------------------------------

def _ce_response(*, ce_score, status, reason=None, error=None):
    """A raw search response shaped like `retriever.retrieve` returns it, with the head's
    always-emitted `cross-encoder` trace stage (SearchTraceMapper.buildHeadStages)."""
    return {
        "error": error,
        "results": [{
            "id": "d1",
            "trace": [
                {"id": "fusion", "rank": 1, "score": 0.3},
                *([{"id": "cross-encoder", "rank": 1, "score": ce_score}]
                  if ce_score is not None else []),
            ],
        }],
        "searchTrace": {"stages": [
            {"id": "fusion", "status": "executed"},
            {"id": "cross-encoder", "status": status, "reason": reason},
        ]},
    }


def _ce_mode_result(responses, observed=("dense", "cross_encoder")):
    return {"raw_responses": list(responses), "pipeline_tracking": {"observed": list(observed)}}


class TestComputeCeCoverage:
    def test_healthy_run_is_ok(self):
        block = _compute_ce_coverage({"hybrid": _ce_mode_result(
            [_ce_response(ce_score=-0.2, status="executed") for _ in range(100)])})
        assert block["verdict"] == "ok"
        assert block["per_mode"]["hybrid"]["applied"] == 100
        assert block["tolerance"] == 0.02

    def test_deadline_dropped_run_is_degraded(self):
        responses = (
            [_ce_response(ce_score=-0.2, status="executed") for _ in range(98)]
            + [_ce_response(ce_score=None, status="skipped", reason="DEADLINE_EXCEEDED")
               for _ in range(102)]
        )
        block = _compute_ce_coverage({"hybrid": _ce_mode_result(responses)})
        assert block["verdict"] == "degraded-ce"
        assert block["per_mode"]["hybrid"]["silent_drops"] == 102
        assert block["per_mode"]["hybrid"]["silent_drop_reason_counts"] == {
            "DEADLINE_EXCEEDED": 102}

    def test_deterministic_skips_do_not_strike(self):
        responses = (
            [_ce_response(ce_score=-0.2, status="executed") for _ in range(283)]
            + [_ce_response(ce_score=None, status="skipped", reason="FUSION_CONFIDENT")
               for _ in range(17)]
        )
        block = _compute_ce_coverage({"hybrid": _ce_mode_result(responses)})
        assert block["verdict"] == "ok"
        assert block["per_mode"]["hybrid"]["legitimate_skips"] == 17

    def test_lexical_only_run_is_not_applicable(self):
        # The leg-isolation preset carries crossEncoderEnabled:false, so every query records
        # PIPELINE_NOT_ELIGIBLE. Such a run must never be struck for not reranking.
        responses = [
            _ce_response(ce_score=None, status="skipped", reason="PIPELINE_NOT_ELIGIBLE")
            for _ in range(50)
        ]
        block = _compute_ce_coverage({"lexical": _ce_mode_result(responses, observed=["sparse"])})
        assert block["verdict"] == "not-applicable"

    def test_errored_queries_are_excluded(self):
        # An errored query has no trace to read and is already counted by error_count; counting it
        # as a CE-less query would strike a run for a failure another signal already reports.
        responses = (
            [_ce_response(ce_score=-0.2, status="executed") for _ in range(50)]
            + [{"error": "timeout", "results": []} for _ in range(50)]
        )
        block = _compute_ce_coverage({"hybrid": _ce_mode_result(responses)})
        assert block["verdict"] == "ok"
        assert block["per_mode"]["hybrid"]["eligible"] == 50

    def test_worst_mode_decides_the_run_verdict(self):
        block = _compute_ce_coverage({
            "hybrid": _ce_mode_result(
                [_ce_response(ce_score=None, status="skipped", reason="DEADLINE_EXCEEDED")]),
            "lexical": _ce_mode_result(
                [_ce_response(ce_score=None, status="skipped", reason="DISABLED")],
                observed=["sparse"]),
        })
        assert block["verdict"] == "degraded-ce"
        assert block["per_mode"]["lexical"]["verdict"] == "not-applicable"

    def test_mode_result_without_raw_responses_is_graceful(self):
        block = _compute_ce_coverage({"hybrid": {}})
        assert block["verdict"] == "not-applicable"
        assert block["per_mode"]["hybrid"]["coverage"] is None


class TestBuildSummaryEmbedsCeCoverage:
    def test_run_shape_carries_ce_coverage_block(self, tmp_path):
        from types import SimpleNamespace

        meta = SimpleNamespace(source="golden", name="golden/demo", doc_count=1, query_count=0)
        mode_results = {
            "hybrid": {
                "aggregate_metrics": {}, "ann_proof": AnnProofResult(status="PASS"),
                "comparability": ComparabilityResult(comparable=True), "run_evidence": {},
                "pipeline_tracking": {"observed": ["dense", "cross_encoder"]},
                "latency_stats": {}, "score_stats": {},
                "raw_responses": (
                    [_ce_response(ce_score=-0.2, status="executed") for _ in range(98)]
                    + [_ce_response(ce_score=None, status="skipped", reason="DEADLINE_EXCEEDED")
                       for _ in range(102)]
                ),
            },
        }
        summary = _build_summary(
            "golden/demo", ["hybrid"], mode_results, meta, {},
            base_dir=tmp_path, status_snapshot=_status_snapshot(48, 100.0),
        )
        assert summary["ce_coverage"]["verdict"] == "degraded-ce"
        assert set(summary["ce_coverage"]) == {"verdict", "reasons", "tolerance", "per_mode"}
        # The pre-existing signals stay green on exactly this run -- the F-052 hole.
        assert summary["per_mode"]["hybrid"]["comparable"] is True
        assert summary["per_mode"]["hybrid"]["comparability_reasons"] == []
        assert summary["per_mode"]["hybrid"]["ann_proof_status"] == "PASS"
        assert summary["per_mode"]["hybrid"]["error_count"] == 0


# ---------------------------------------------------------------------------
# tempdoc 931 §E item 10: merge-state snapshot at query-phase start
# ---------------------------------------------------------------------------

class TestBuildIndexStateAtQuery:
    @pytest.mark.parametrize("max_doc,num_docs", [(-1, -1), (-1, 50), (50, -1)])
    def test_unreadable_merge_counters_are_not_zero_debt(self, max_doc, num_docs):
        block = _build_index_state_at_query(
            {"indexMaxDoc": max_doc, "indexNumDocs": num_docs}, "2026-10-31T00:00:00+00:00",
        )
        assert block["max_doc"] == (None if max_doc < 0 else max_doc)
        assert block["num_docs"] == (None if num_docs < 0 else num_docs)
        assert block["deleted_docs"] is None

    def test_empty_index_has_known_zero_merge_debt(self):
        block = _build_index_state_at_query(
            {"indexMaxDoc": 0, "indexNumDocs": 0}, "2026-10-31T00:00:00+00:00",
        )
        assert block["max_doc"] == 0
        assert block["num_docs"] == 0
        assert block["deleted_docs"] == 0

    def test_full_snapshot_computes_deleted_docs(self):
        block = _build_index_state_at_query(
            {
                "indexMaxDoc": 2851,
                "indexNumDocs": 222,
                "chunkSpladeCoveragePercent": 100.0,
                "spladeCoveragePercent": 99.95,
                "chunkVectorCoveragePercent": 100.0,
            },
            "2026-09-05T00:00:00+00:00",
        )
        assert block == {
            "max_doc": 2851,
            "num_docs": 222,
            "deleted_docs": 2629,
            "chunk_splade_coverage_percent": 100.0,
            "splade_coverage_percent": 99.95,
            "chunk_vector_coverage_percent": 100.0,
            "settled": False,
            "readiness_passed_at": "2026-09-05T00:00:00+00:00",
        }

    def test_missing_optional_fields_degrade_to_null(self):
        # An empty snapshot (skip_readiness, or an older backend that publishes none of
        # these fields) must not raise -- every field degrades to None, including the
        # derived deleted_docs (which cannot be computed without both operands).
        block = _build_index_state_at_query({}, "2026-09-05T00:00:00+00:00")
        assert block == {
            "max_doc": None,
            "num_docs": None,
            "deleted_docs": None,
            "chunk_splade_coverage_percent": None,
            "splade_coverage_percent": None,
            "chunk_vector_coverage_percent": None,
            "settled": False,
            "readiness_passed_at": "2026-09-05T00:00:00+00:00",
        }

    def test_partial_snapshot_only_max_doc_still_nulls_deleted_docs(self):
        # deleted_docs requires BOTH operands -- a snapshot with only one of the two
        # doc-count fields must not silently compute a wrong (e.g. max_doc - 0) delta.
        block = _build_index_state_at_query(
            {"indexMaxDoc": 500}, "2026-09-05T00:00:00+00:00",
        )
        assert block["max_doc"] == 500
        assert block["num_docs"] is None
        assert block["deleted_docs"] is None


class TestBuildSummaryEmbedsIndexStateAtQuery:
    def _mode_results(self):
        return {
            "hybrid": {
                "aggregate_metrics": {}, "ann_proof": AnnProofResult(status="PASS"),
                "comparability": ComparabilityResult(comparable=True), "run_evidence": {},
                "pipeline_tracking": {"observed": ["dense", "cross_encoder"]},
                "latency_stats": {}, "score_stats": {},
            },
        }

    def test_present_when_passed(self, tmp_path):
        from types import SimpleNamespace

        meta = SimpleNamespace(source="golden", name="golden/demo", doc_count=1, query_count=0)
        block = _build_index_state_at_query(
            {"indexMaxDoc": 100, "indexNumDocs": 90}, "2026-09-05T00:00:00+00:00",
        )
        summary = _build_summary(
            "golden/demo", ["hybrid"], self._mode_results(), meta, {},
            base_dir=tmp_path, status_snapshot=_status_snapshot(48, 100.0),
            index_state_at_query=block,
        )
        assert summary["index_state_at_query"] == block

    def test_absent_param_still_emits_null_block(self, tmp_path):
        # Additive-key contract (like `cadence`): the key is always present, even when the
        # caller doesn't pass one (existing call sites that predate this feature).
        from types import SimpleNamespace

        meta = SimpleNamespace(source="golden", name="golden/demo", doc_count=1, query_count=0)
        summary = _build_summary(
            "golden/demo", ["hybrid"], self._mode_results(), meta, {},
            base_dir=tmp_path, status_snapshot=_status_snapshot(48, 100.0),
        )
        assert "index_state_at_query" in summary
        assert summary["index_state_at_query"]["max_doc"] is None
        assert summary["index_state_at_query"]["readiness_passed_at"] is None


# ---------------------------------------------------------------------------
# tempdoc 931 §E item 10: --settle-index (equal merge state across paired arms)
# ---------------------------------------------------------------------------

class _FakeResponse:
    def __init__(self, status_code, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload
        self.text = text

    def json(self):
        return self._payload


class _FakeClient:
    """Stands in for ``httpx.Client`` as a context manager; records the POSTs it saw."""

    def __init__(self, response, calls):
        self._response = response
        self._calls = calls

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def post(self, path, json=None):
        self._calls.append((path, json))
        if isinstance(self._response, Exception):
            raise self._response
        return self._response


def _patch_settle_client(response, calls):
    return patch(
        "jseval.run.httpx.Client",
        side_effect=lambda *a, **kw: _FakeClient(response, calls),
    )


_SETTLE_202 = {
    "status": "settle completed",
    "maxDocBefore": 2851,
    "numDocsBefore": 222,
    "maxDocAfter": 222,
    "numDocsAfter": 222,
    "segmentsAfter": 1,
    "elapsedMs": 4200,
}


class TestSettleIndexHelper:
    def test_accepted_normalizes_the_before_and_after_counts(self):
        calls = []
        with _patch_settle_client(_FakeResponse(202, _SETTLE_202), calls):
            block = _settle_index("http://localhost:8080")
        assert calls == [("/api/indexing/settle", {"expungeDeletesOnly": False, "maxSegments": 1})]
        assert block == {
            "max_doc_before": 2851,
            "num_docs_before": 222,
            "max_doc_after": 222,
            "num_docs_after": 222,
            "segments_after": 1,
            "elapsed_ms": 4200,
        }

    def test_404_from_an_older_backend_degrades_to_none(self):
        calls = []
        with _patch_settle_client(_FakeResponse(404, None, "Not found"), calls):
            assert _settle_index("http://localhost:8080") is None
        assert calls, "the 404 path must still have attempted the call"

    def test_409_refusal_degrades_to_none(self):
        with _patch_settle_client(
            _FakeResponse(409, None, "settle rejected by worker"), []
        ):
            assert _settle_index("http://localhost:8080") is None

    def test_transport_failure_degrades_to_none(self):
        with _patch_settle_client(RuntimeError("connection refused"), []):
            assert _settle_index("http://localhost:8080") is None


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_without_settle_flag_never_calls_the_endpoint(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    calls = []
    with _patch_settle_client(_FakeResponse(202, _SETTLE_202), calls):
        summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert calls == [], "the settle is opt-in; a routine run must not hold the writer"
    block = summary["index_state_at_query"]
    assert block["settled"] is False
    assert "settle" not in block
    assert mock_readiness.check_search_ready.call_count == 1


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_with_settle_flag_records_the_block_and_rechecks_readiness(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    calls = []
    with _patch_settle_client(_FakeResponse(202, _SETTLE_202), calls):
        summary = execute_run(
            "scifact", "http://localhost:8080", ["hybrid"], settle_index=True,
        )

    assert calls == [("/api/indexing/settle", {"expungeDeletesOnly": False, "maxSegments": 1})]
    block = summary["index_state_at_query"]
    assert block["settled"] is True
    assert block["settle"]["max_doc_before"] == 2851
    assert block["settle"]["max_doc_after"] == 222
    assert block["settle"]["segments_after"] == 1
    assert block["settle"]["elapsed_ms"] == 4200
    # The settle commits and reopens the searcher, so the recorded coverage percentages must
    # come from a post-settle readiness snapshot, not the pre-settle one.
    assert mock_readiness.check_search_ready.call_count == 2


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_with_settle_flag_on_an_older_backend_continues_unsettled(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    calls = []
    with _patch_settle_client(_FakeResponse(404, None, "Not found"), calls):
        summary = execute_run(
            "scifact", "http://localhost:8080", ["hybrid"], settle_index=True,
        )

    assert calls, "the 404 path must have attempted the call"
    block = summary["index_state_at_query"]
    assert block["settled"] is False, "a 404 must not claim the arm was settled"
    assert "settle" not in block
    assert summary["query_count"] == 2, "the run continues rather than failing"
    assert mock_readiness.check_search_ready.call_count == 1


# --- tempdoc 885: search_load block wiring ----------------------------------

@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_records_search_load_block(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    block = {"mode": "qpm", "qpm": 10, "queries_issued": 4, "errors": 0}

    summary = execute_run(
        "scifact", "http://localhost:8080", ["hybrid"], search_load=block,
    )

    assert summary["search_load"] == block


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_omits_search_load_when_absent(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert "search_load" not in summary


# --- tempdoc 885 item 19: cadence block wiring -------------------------------

@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_always_emits_a_cadence_block(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
    tmp_path, monkeypatch,
):
    """Absent Worker metrics degrade to nulls — the columns exist on every run."""
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path / "no-telemetry-here"))

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert summary["cadence"] == {
        "reopen_total": None,
        "commit_total": None,
        "segments_since_reopen": None,
        # tempdoc 912 item 2 (commit 33ffc3bb) added the reason-tagged pair; they degrade
        # to null on the same no-telemetry path as the three counters above.
        "commit_by_reason": None,
        "commit_by_reason_total": None,
        "first_search_after_indexing": None,
    }


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_reads_worker_cadence_metrics_and_carries_the_probe(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
    tmp_path, monkeypatch,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    telemetry = tmp_path / "telemetry"
    telemetry.mkdir(parents=True)
    (telemetry / "metrics-worker.ndjson").write_text(
        "\n".join(json.dumps(r) for r in [
            {"t": "2026-09-02T00:00:00Z", "name": "index.runtime.reopen_count",
             "type": "counter", "value": 17},
            {"t": "2026-09-02T00:00:01Z", "name": "index.runtime.commit_count",
             "type": "counter", "value": 4},
            {"t": "2026-09-02T00:00:01Z", "name": "index.runtime.segments_since_reopen",
             "type": "gauge", "value": 2},
        ]) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path))
    probe = {"min_new_files": 50, "batches_fired": 1, "probes_ok": 1,
             "errors": 0, "latency_ms": {"p50": 9.0, "p95": 9.0, "max": 9.0}}

    summary = execute_run(
        "scifact", "http://localhost:8080", ["hybrid"], first_search_probe=probe,
    )

    assert summary["cadence"]["reopen_total"] == 17
    assert summary["cadence"]["commit_total"] == 4
    assert summary["cadence"]["segments_since_reopen"] == 2
    assert summary["cadence"]["first_search_after_indexing"] == probe


# --- tempdoc 930 §18.1 row 7: encoder_latency block wiring --------------------

@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_always_emits_an_encoder_latency_block(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
    tmp_path, monkeypatch,
):
    """Absent encoder spans degrade to an empty mapping — the key exists on every run."""
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path / "no-telemetry-here"))

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert summary["encoder_latency"] == {"encoders": {}}


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_execute_run_reads_encoder_ort_run_spans_from_worker_telemetry(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
    tmp_path, monkeypatch,
):
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    telemetry = tmp_path / "telemetry"
    telemetry.mkdir(parents=True)
    (telemetry / "traces.ndjson").write_text(
        "\n".join(json.dumps({
            "name": "encoder.ort_run",
            "attrs": {"encoder.name": "BgeM3Encoder"},
            "duration_ms": d,
        }) for d in (2.0, 4.0, 6.0)) + "\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path))

    summary = execute_run("scifact", "http://localhost:8080", ["hybrid"])

    assert summary["encoder_latency"]["encoders"] == {
        "BgeM3Encoder": {"n": 3, "p50_ms": 4.0, "p95_ms": 6.0},
    }


@patch(*_MOCK_STACK[:1])
@patch(*_MOCK_STACK[1:2])
@patch(*_MOCK_STACK[2:3])
@patch(*_MOCK_STACK[3:4])
@patch(*_MOCK_STACK[4:5])
@patch(*_MOCK_STACK[5:6])
@patch(*_MOCK_STACK[6:7])
@patch(*_MOCK_STACK[7:8])
@patch(*_MOCK_STACK[8:9])
def test_ingest_only_run_also_carries_the_encoder_latency_block(
    mock_corpora, mock_readiness, mock_retriever, mock_scoring,
    mock_provenance, mock_ann, mock_comp, mock_artifacts, mock_history,
    tmp_path, monkeypatch,
):
    """The no-modes (ingest-only) summary branch composes its own dict — the key rides it too."""
    _setup_mocks(mock_corpora, mock_readiness, mock_retriever, mock_scoring,
                 mock_provenance, mock_ann, mock_comp)
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path / "no-telemetry-here"))

    summary = execute_run("scifact", "http://localhost:8080", [])

    assert summary["encoder_latency"] == {"encoders": {}}
