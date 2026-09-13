"""Tests for ingest.py — ingestion orchestration."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, call, patch

import pytest

from jseval.ingest import (
    HIGH_WATERMARK,
    LOW_WATERMARK,
    _SIDECAR,
    _ensure_materialized,
    _get_indexed_doc_count,
    _iter_corpus_jsonl,
    _materialize_into,
    _raw_corpus_dir,
    _root_already_watched,
    _source_signature,
    _wait_for_backpressure,
    _watcher_settle_timeout,
    add_watched_root,
    ingest_and_wait,
    prepare_corpus,
)
from jseval.types import IngestConfig, ReadinessResult
from jseval.raw_corpus_manifest import RawCorpusManifestError


# ---------------------------------------------------------------------------
# _watcher_settle_timeout [-1c]
# ---------------------------------------------------------------------------

class TestWatcherSettleTimeout:
    def test_zero_corpus(self):
        assert _watcher_settle_timeout(0) == 30.0

    def test_small_corpus(self):
        assert _watcher_settle_timeout(1000) == 30.0  # 1000/100 = 10 < 30

    def test_medium_corpus(self):
        assert _watcher_settle_timeout(5000) == 50.0  # 5000/100 = 50

    def test_large_corpus(self):
        assert _watcher_settle_timeout(50000) == 300.0  # capped at max

    def test_negative_corpus(self):
        assert _watcher_settle_timeout(-1) == 30.0


# ---------------------------------------------------------------------------
# add_watched_root
# ---------------------------------------------------------------------------

@patch("jseval.ingest.httpx.Client")
def test_add_watched_root(MockClient, tmp_path):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = MagicMock(status_code=200)
    mock_client.post.return_value.raise_for_status = MagicMock()

    add_watched_root("http://localhost:8080", tmp_path)

    mock_client.post.assert_called_once()
    args, kwargs = mock_client.post.call_args
    assert args[0] == "/api/indexing/roots"
    assert "path" in kwargs["json"]


@patch("jseval.ingest.httpx.Client")
def test_add_watched_root_carries_production_session_token(MockClient, tmp_path):
    add_watched_root("http://127.0.0.1:33221", tmp_path, timeout_sec=7, session_token="test-boot-token")
    MockClient.assert_called_once_with(
        base_url="http://127.0.0.1:33221", timeout=7,
        headers={"X-JustSearch-Session": "test-boot-token"},
    )


# ---------------------------------------------------------------------------
# _get_indexed_doc_count
# ---------------------------------------------------------------------------

@patch("jseval.ingest.httpx.Client")
def test_get_indexed_doc_count(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    resp = MagicMock()
    resp.json.return_value = {"indexedDocuments": 500}
    resp.raise_for_status = MagicMock()
    mock_client.get.return_value = resp

    count = _get_indexed_doc_count("http://localhost:8080")
    assert count == 500


@patch("jseval.ingest.httpx.Client")
def test_get_indexed_doc_count_on_error(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.get.side_effect = Exception("connection refused")

    count = _get_indexed_doc_count("http://localhost:8080")
    assert count == 0


# ---------------------------------------------------------------------------
# _wait_for_backpressure
# ---------------------------------------------------------------------------

def test_backpressure_no_wait():
    """Queue below high watermark → no waiting."""
    client = MagicMock()
    resp = MagicMock()
    resp.json.return_value = {"pendingJobs": 100}
    resp.raise_for_status = MagicMock()
    client.get.return_value = resp

    _wait_for_backpressure(client, HIGH_WATERMARK, LOW_WATERMARK, 0.01)
    # Should return immediately after one check
    assert client.get.call_count == 1


@patch("jseval.ingest.time.sleep")
def test_backpressure_waits_then_resumes(mock_sleep):
    """Queue above high watermark → wait until below low watermark."""
    client = MagicMock()

    responses = [
        {"pendingJobs": 95_000},  # Above high watermark
        {"pendingJobs": 80_000},  # Still above low watermark
        {"pendingJobs": 60_000},  # Below low watermark → resume
    ]
    resp_mocks = []
    for r in responses:
        m = MagicMock()
        m.json.return_value = r
        m.raise_for_status = MagicMock()
        resp_mocks.append(m)

    client.get.side_effect = resp_mocks

    _wait_for_backpressure(client, HIGH_WATERMARK, LOW_WATERMARK, 0.01)
    assert client.get.call_count == 3


# ---------------------------------------------------------------------------
# _iter_corpus_jsonl
# ---------------------------------------------------------------------------

def test_iter_corpus_jsonl(tmp_path):
    jsonl = tmp_path / "corpus.jsonl"
    jsonl.write_text(
        '{"_id": "d1", "text": "hello", "title": "T1"}\n'
        '{"_id": "d2", "text": "world"}\n'
        '\n',
        encoding="utf-8",
    )
    docs = list(_iter_corpus_jsonl(jsonl))
    assert len(docs) == 2
    assert docs[0]["_id"] == "d1"
    assert docs[1]["text"] == "world"


# ---------------------------------------------------------------------------
# prepare_corpus
# ---------------------------------------------------------------------------

@patch("jseval.ingest.ingest_and_wait")
@patch("jseval.materialize.materialize")
def test_prepare_corpus_beir_materializes(mock_materialize, mock_ingest, tmp_path, monkeypatch):
    """BEIR dataset with no corpus_dir triggers materialization to default path."""
    # Redirect default corpus dir to tmp_path so the directory doesn't pre-exist.
    monkeypatch.setattr(
        "jseval._paths.default_corpus_dir",
        lambda name: tmp_path / "tmp" / "eval-corpora" / name,
    )

    mock_materialize.return_value = 1
    mock_ingest.return_value = {"readiness_passed": True, "docs_indexed": 1}

    mock_ds = MagicMock()
    mock_ds.docs_iter.return_value = iter([])
    mock_ir = MagicMock()
    mock_ir.load.return_value = mock_ds

    import sys
    sys.modules["ir_datasets"] = mock_ir
    try:
        result = prepare_corpus(
            "scifact", IngestConfig(base_url="http://localhost:33221"),
            # No corpus_dir — uses default path, triggers materialization
        )
    finally:
        del sys.modules["ir_datasets"]

    assert mock_materialize.called
    assert mock_ingest.called
    assert result["readiness_passed"] is True


def test_prepare_corpus_explicit_dir_empty_raises(tmp_path):
    """Explicit --corpus-dir with no .txt files raises FileNotFoundError."""
    corpus_dir = tmp_path / "empty"
    corpus_dir.mkdir()

    with pytest.raises(FileNotFoundError, match=r"no \.txt/\.png files"):
        prepare_corpus("scifact", IngestConfig(base_url="http://localhost:33221"), corpus_dir=corpus_dir)


@patch("jseval.ingest.ingest_and_wait")
def test_prepare_corpus_explicit_dir_uses_existing(mock_ingest, tmp_path):
    """Explicit --corpus-dir with .txt files uses them without materializing."""
    corpus_dir = tmp_path / "existing"
    corpus_dir.mkdir()
    (corpus_dir / "doc1.txt").write_text("content")
    (corpus_dir / "doc2.txt").write_text("content")

    mock_ingest.return_value = {"readiness_passed": True, "docs_indexed": 0}

    result = prepare_corpus(
        "scifact", IngestConfig(base_url="http://localhost:33221"), corpus_dir=corpus_dir,
    )

    assert mock_ingest.called
    call_kwargs = mock_ingest.call_args
    # corpus_doc_count is now a keyword arg to ingest_and_wait
    assert call_kwargs.kwargs["corpus_doc_count"] == 2


# ---------------------------------------------------------------------------
# ingest_and_wait — embed-compat settle-wait (tempdoc 715 defect 2)
#
# A fast small-corpus run (e.g. mixed/legal-clerc-200, 198 docs, ~60s ingest+enrich) can
# satisfy wait_index_idle/wait_pipeline_complete while embeddingCompatState still reads
# REBUILDING. ingest_and_wait must settle-wait BEFORE returning to its caller (which proceeds
# straight to eval + manifest snapshot) -- but only when readiness actually passed, so a
# genuinely-failed run doesn't additionally block for up to 90s with nothing to gain.
# ---------------------------------------------------------------------------

@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity")
@patch("jseval.ingest.add_watched_root")
@patch("jseval.ingest._get_indexed_doc_count")
def test_ingest_and_wait_settles_embed_compat_after_readiness_passes(
    mock_doc_count, mock_add_root, mock_watcher, mock_wait_idle, mock_settle, tmp_path,
):
    mock_doc_count.return_value = 0
    mock_watcher.return_value = True
    mock_wait_idle.return_value = ReadinessResult(
        passed=True, snapshot={"indexedDocuments": 198, "indexSizeBytes": 1000},
    )
    mock_settle.return_value = "COMPATIBLE"

    config = IngestConfig(base_url="http://localhost:33221")
    summary = ingest_and_wait(config, tmp_path, corpus_doc_count=198)

    mock_settle.assert_called_once_with("http://localhost:33221")
    assert summary["embed_compat_state_settled"] == "COMPATIBLE"


@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_index_idle")
@patch("jseval.ingest._wait_for_watcher_activity")
@patch("jseval.ingest.add_watched_root")
@patch("jseval.ingest._get_indexed_doc_count")
def test_ingest_and_wait_skips_settle_wait_when_readiness_failed(
    mock_doc_count, mock_add_root, mock_watcher, mock_wait_idle, mock_settle, tmp_path,
):
    mock_doc_count.return_value = 0
    mock_watcher.return_value = True
    mock_wait_idle.return_value = ReadinessResult(
        passed=False, failure_reasons=["index_not_idle"], snapshot={},
    )

    config = IngestConfig(base_url="http://localhost:33221")
    summary = ingest_and_wait(config, tmp_path, corpus_doc_count=198)

    mock_settle.assert_not_called()
    assert "embed_compat_state_settled" not in summary


@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_pipeline_complete")
@patch("jseval.ingest._wait_for_watcher_activity")
@patch("jseval.ingest.add_watched_root")
@patch("jseval.ingest._get_indexed_doc_count")
def test_ingest_and_wait_settles_embed_compat_on_pipeline_path_too(
    mock_doc_count, mock_add_root, mock_watcher, mock_wait_pipeline, mock_settle, tmp_path,
):
    mock_doc_count.return_value = 0
    mock_watcher.return_value = True
    mock_wait_pipeline.return_value = ReadinessResult(
        passed=True, snapshot={"indexedDocuments": 198, "indexSizeBytes": 1000},
    )
    mock_settle.return_value = "REBUILDING"  # timed out, but proceeds honestly

    config = IngestConfig(base_url="http://localhost:33221", pipeline=True)
    summary = ingest_and_wait(config, tmp_path, corpus_doc_count=198)

    mock_settle.assert_called_once_with("http://localhost:33221")
    assert summary["embed_compat_state_settled"] == "REBUILDING"


# ---------------------------------------------------------------------------
# ingest_and_wait — idempotent readiness floor for repeated same-root ingest
# (tempdoc 751 sec Q sub-bug a: the 1001+1001=2002 cumulative-floor wedge)
#
# When the SAME root is re-ingested into a backend that already watches it (cache
# adoption, chain re-entry), path-dedup means 0 net new docs -- the additive floor
# (initial + corpus) becomes an unmeetable 2x wall. The floor must reflect the
# UNION of watched content (max), NOT the sum of ingest requests. A genuinely new
# / larger root must still raise the floor additively (no weakening).
# ---------------------------------------------------------------------------

@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_pipeline_complete")
@patch("jseval.ingest._wait_for_watcher_activity")
@patch("jseval.ingest.add_watched_root")
@patch("jseval.ingest._root_already_watched")
@patch("jseval.ingest._get_indexed_doc_count")
def test_floor_is_union_not_sum_when_root_already_watched(
    mock_doc_count, mock_already, mock_add_root, mock_watcher,
    mock_wait_pipeline, mock_settle, tmp_path,
):
    """Re-ingesting an already-watched root of 1001 docs into an index that
    already holds them uses a floor of 1001 (union), NOT 2002 (sum) -- the
    exact wedge from sec Q."""
    mock_doc_count.return_value = 1001       # index already has the corpus
    mock_already.return_value = True          # same root already watched
    mock_watcher.return_value = True
    mock_wait_pipeline.return_value = ReadinessResult(
        passed=True, snapshot={"indexedDocuments": 1001, "indexSizeBytes": 1000},
    )
    mock_settle.return_value = "COMPATIBLE"

    config = IngestConfig(base_url="http://localhost:33221", pipeline=True)
    summary = ingest_and_wait(config, tmp_path, corpus_doc_count=1001)

    passed_floor = mock_wait_pipeline.call_args.kwargs["expected_doc_count_min"]
    assert passed_floor == 1001, f"expected union floor 1001, got {passed_floor}"
    assert passed_floor != 2002, "additive floor 2002 is the unmeetable wedge"
    assert summary["readiness_passed"] is True


@patch("jseval.ingest.wait_embed_compat_settled")
@patch("jseval.ingest.wait_pipeline_complete")
@patch("jseval.ingest._wait_for_watcher_activity")
@patch("jseval.ingest.add_watched_root")
@patch("jseval.ingest._root_already_watched")
@patch("jseval.ingest._get_indexed_doc_count")
@pytest.mark.parametrize("watcher_active", [True, False])
def test_floor_stays_additive_for_a_genuinely_new_larger_root(
    mock_doc_count, mock_already, mock_add_root, mock_watcher,
    mock_wait_pipeline, mock_settle, tmp_path, watcher_active,
):
    """A DIFFERENT root (not yet watched) added on top of an existing index
    keeps the additive floor -- the union fix must not weaken the gate for a
    genuinely larger corpus (a partially-built index cannot pass early)."""
    mock_doc_count.return_value = 1001        # index already holds corpus A (1001)
    mock_already.return_value = False          # corpus B is a NEW root
    mock_watcher.return_value = watcher_active
    mock_wait_pipeline.return_value = ReadinessResult(
        passed=True, snapshot={"indexedDocuments": 3001, "indexSizeBytes": 1000},
    )
    mock_settle.return_value = "COMPATIBLE"

    config = IngestConfig(base_url="http://localhost:33221", pipeline=True)
    ingest_and_wait(config, tmp_path, corpus_doc_count=2000)  # corpus B = 2000

    passed_floor = mock_wait_pipeline.call_args.kwargs["expected_doc_count_min"]
    assert passed_floor == 3001, f"expected additive floor 3001, got {passed_floor}"


# _root_already_watched — path-normalized membership against GET /api/indexing/roots

@patch("jseval.ingest.httpx.Client")
def test_root_already_watched_true_for_recorded_path(MockClient, tmp_path):
    client = MockClient.return_value.__enter__.return_value
    resp = MagicMock()
    resp.json.return_value = {"roots": [{"path": str(tmp_path.resolve())}]}
    client.get.return_value = resp

    assert _root_already_watched("http://localhost:33221", tmp_path) is True
    assert client.get.call_args[0][0] == "/api/indexing/roots"


@patch("jseval.ingest.httpx.Client")
def test_root_already_watched_false_for_other_path(MockClient, tmp_path):
    client = MockClient.return_value.__enter__.return_value
    resp = MagicMock()
    resp.json.return_value = {"roots": [{"path": str(tmp_path / "somewhere-else")}]}
    client.get.return_value = resp

    assert _root_already_watched("http://localhost:33221", tmp_path) is False


@patch("jseval.ingest.httpx.Client")
def test_root_already_watched_false_on_error_is_failsafe(MockClient, tmp_path):
    """A lost roots signal reports 'not watched' -> caller falls back to the
    additive floor. Fail-safe: can only over-count the floor, never under-count."""
    MockClient.return_value.__enter__.return_value.get.side_effect = RuntimeError("boom")
    assert _root_already_watched("http://localhost:33221", tmp_path) is False


# ---------------------------------------------------------------------------
# _ensure_materialized — the cache is a VERIFIED PROJECTION of the source
# (tempdoc 635 verification-binding; regression for the stale-cache nDCG-0.0 class)
# ---------------------------------------------------------------------------

def _seed_source(base: Path, name: str, ids: list[str]) -> None:
    import json
    src = base / name
    (src / "qrels").mkdir(parents=True, exist_ok=True)
    (src / "corpus.jsonl").write_text(
        "\n".join(json.dumps({"_id": i, "title": i, "text": f"body {i}"}) for i in ids),
        encoding="utf-8")
    (src / "qrels" / "test.tsv").write_text(
        "query-id\tcorpus-id\tscore\nq1\t" + ids[0] + "\t1\n", encoding="utf-8")


def test_ensure_materialized_reverifies_on_source_change(tmp_path, monkeypatch):
    """A regenerated source (new signature) must trigger re-materialization, and an unchanged
    source must reuse the cache — closing the stale-cache class structurally."""
    from jseval import corpora, materialize as mat
    from jseval.materialize import doc_id_to_filename

    base = tmp_path / "datasets"
    _seed_source(base, "golden/probe-x", ["a", "b"])
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)
    cache = tmp_path / "cache" / "golden" / "probe-x"

    # 1) empty cache → materialize + write sidecar (2 docs + sentinel)
    assert _ensure_materialized("golden/probe-x", cache, None) == 3
    sidecar = cache / _SIDECAR
    assert sidecar.is_file()
    sig1 = sidecar.read_text(encoding="utf-8").strip()

    # 2) unchanged source → REUSE (materialize not called again)
    calls: list[int] = []
    real = mat.materialize
    monkeypatch.setattr(mat, "materialize", lambda *a, **k: (calls.append(1), real(*a, **k))[1])
    assert _ensure_materialized("golden/probe-x", cache, None) == 3
    assert calls == []  # verified-identity reuse, no re-materialize

    # 3) mutate source (new ids → new signature) → RE-MATERIALIZE, stale .txt gone
    _seed_source(base, "golden/probe-x", ["c", "d", "e"])
    assert _ensure_materialized("golden/probe-x", cache, None) == 4  # 3 docs + sentinel
    assert calls == [1]  # re-materialized exactly once
    names = {p.name for p in cache.glob("*.txt")}
    assert doc_id_to_filename("a") not in names  # stale doc removed
    assert doc_id_to_filename("c") in names      # fresh doc present
    assert sidecar.read_text(encoding="utf-8").strip() != sig1  # sidecar updated


def test_source_signature_none_for_beir(monkeypatch):
    """BEIR/unknown datasets have no local source that can go stale → no sidecar, plain
    materialize-if-empty behaviour preserved."""
    assert _source_signature("beir/scifact") is None
    assert _source_signature("anything-else") is None


# ---------------------------------------------------------------------------
# _ensure_materialized / _materialize_into — scan-axis regression (tempdoc 624 follow-up)
#
# `corpus_build.build_golden` already renders `axis="scan"` docs as `.png` in its OWN
# `corpus-dir/` (covered by test_corpus_governance.py's `test_build_golden_materializes_
# scan_docs_as_png`). That coverage did NOT catch this bug: `jseval run`'s real ingestion
# path is `prepare_corpus` -> `_ensure_materialized` -> `_materialize_into`, which reads
# `corpus.jsonl` directly and used to call `materialize.materialize()` without ever
# checking `type_axis` — silently substituting plain ground-truth `.txt` files for the
# degraded `.png` scans the dataset exists to test. This test exercises THAT path.
# ---------------------------------------------------------------------------

def test_ensure_materialized_renders_scan_docs_as_png(tmp_path, monkeypatch):
    """A golden `axis="scan"` dataset materialized via `_ensure_materialized` (the function
    `prepare_corpus`/`jseval run` actually calls) must produce real `.png` files, not `.txt`
    ground-truth substitutes, for every document."""
    pytest.importorskip("PIL")
    import json

    from jseval import corpora, corpus_build
    from jseval import corpus_generate as cg
    from jseval.materialize import doc_id_to_filename

    src = tmp_path / "src"
    cg.generate(src, axis="scan", n_chains=2, hops=1, distractor_ratio=1, doc_words=40, seed=5,
                entity_bank=Path(__file__).resolve().parent / "fixtures" / "entity-bank-fixture")
    docs = [json.loads(line) for line in (src / "docs.jsonl").read_text(encoding="utf-8").splitlines()]

    base = tmp_path / "datasets"
    ds = base / "golden" / "scan-ingest-x"
    corpus_build.build_golden(src, ds, now="2026-07-02")
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

    cache = tmp_path / "cache" / "golden" / "scan-ingest-x"
    n = _ensure_materialized("golden/scan-ingest-x", cache, None)

    assert n == len(docs) + 1  # + sentinel
    for d in docs:
        png_path = cache / doc_id_to_filename(d["_id"], ext="png")
        assert png_path.is_file(), f"{d['_id']} was materialized as .txt, not .png — scan axis was dropped"
        assert png_path.read_bytes().startswith(b"\x89PNG"), f"{d['_id']}.png is not a real PNG"
        assert not (cache / doc_id_to_filename(d["_id"])).exists()  # no stray .txt

    sentinel = cache / doc_id_to_filename("__jseval_sentinel__")
    assert sentinel.is_file()  # sentinel is unaffected — always plain .txt


def test_materialize_into_unknown_dataset_lists_valid_names(tmp_path):
    """Tempdoc 787 item 4a: the unknown-dataset error names the valid forms (bare BEIR name,
    `beir/` catalog slug, and the `golden/`/`mixed/` prefixes) instead of a bare repr."""
    from jseval.corpora import BEIR_DATASETS

    with pytest.raises(ValueError) as exc:
        _materialize_into("totally-bogus", tmp_path)
    msg = str(exc.value)
    assert "totally-bogus" in msg
    assert "beir/scifact" in msg  # catalog slug form is mentioned
    assert "golden/" in msg and "mixed/" in msg
    for name in BEIR_DATASETS:
        assert name in msg


def test_ensure_materialized_plain_axis_unaffected(tmp_path, monkeypatch):
    """A `type_axis` other than "scan" (or missing metadata.json entirely) must keep the
    pre-existing plain-.txt materialization — this fix is additive, not axis-blind."""
    from jseval import corpora

    base = tmp_path / "datasets"
    _seed_source(base, "golden/plain-x", ["a", "b"])
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

    cache = tmp_path / "cache" / "golden" / "plain-x"
    n = _ensure_materialized("golden/plain-x", cache, None)

    assert n == 3  # 2 docs + sentinel
    assert len(list(cache.glob("*.txt"))) == 3
    assert not list(cache.glob("*.png"))


# ---------------------------------------------------------------------------
# _raw_corpus_dir / prepare_corpus raw-files branch (tempdoc 686)
#
# A local (golden/mixed) dataset whose metadata.json carries {"raw_files": true} has
# no corpus.jsonl and is never materialized — ingest points the watched root directly
# at the dataset's own corpus-dir/, counting files of ANY extension.
# ---------------------------------------------------------------------------

def _seed_raw_dataset(base: Path, name: str, raw_files: bool | None = True) -> Path:
    """Seed a local dataset dir with a metadata.json and an (empty) corpus-dir/.

    `raw_files=None` omits the key entirely (as opposed to writing it `false`).
    Returns the corpus-dir path.
    """
    import json
    root = base / name
    root.mkdir(parents=True, exist_ok=True)
    meta: dict = {} if raw_files is None else {"raw_files": raw_files}
    (root / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    corpus_dir = root / "corpus-dir"
    corpus_dir.mkdir(parents=True, exist_ok=True)
    return corpus_dir


class TestRawCorpusDir:
    """Unit coverage for `_raw_corpus_dir` in isolation from `prepare_corpus`."""

    def test_raw_files_true_returns_corpus_dir(self, tmp_path, monkeypatch):
        from jseval import corpora
        base = tmp_path / "datasets"
        corpus_dir = _seed_raw_dataset(base, "mixed/raw-test-x", raw_files=True)
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        assert _raw_corpus_dir("mixed/raw-test-x") == corpus_dir

    def test_raw_files_false_returns_none(self, tmp_path, monkeypatch):
        """metadata.json present but raw_files explicitly false → non-raw path."""
        from jseval import corpora
        base = tmp_path / "datasets"
        _seed_raw_dataset(base, "golden/not-raw-x", raw_files=False)
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        assert _raw_corpus_dir("golden/not-raw-x") is None

    def test_raw_files_key_absent_returns_none(self, tmp_path, monkeypatch):
        """metadata.json present but without a raw_files key at all → non-raw path."""
        from jseval import corpora
        base = tmp_path / "datasets"
        _seed_raw_dataset(base, "golden/no-key-x", raw_files=None)
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        assert _raw_corpus_dir("golden/no-key-x") is None

    def test_no_metadata_json_returns_none(self, tmp_path, monkeypatch):
        from jseval import corpora
        base = tmp_path / "datasets"
        (base / "golden" / "plain-x").mkdir(parents=True, exist_ok=True)
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        assert _raw_corpus_dir("golden/plain-x") is None

    def test_non_golden_mixed_dataset_returns_none(self, tmp_path, monkeypatch):
        """BEIR / unknown dataset names are never raw — short-circuits on prefix before
        touching disk at all (base dir is patched but never consulted)."""
        from jseval import corpora
        base = tmp_path / "datasets"
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        assert _raw_corpus_dir("beir/scifact") is None
        assert _raw_corpus_dir("scifact") is None


class TestPrepareCorpusRawBranch:
    """`prepare_corpus` behavior for raw_files=true datasets."""

    def test_raw_dataset_ingests_without_materializing(self, tmp_path, monkeypatch):
        """2 pdfs + 1 nested docx → corpus_doc_count=3, ingest_and_wait pointed at the
        dataset's corpus-dir, materialization never invoked."""
        from jseval import corpora
        base = tmp_path / "datasets"
        corpus_dir = _seed_raw_dataset(base, "mixed/raw-test-x", raw_files=True)
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        (corpus_dir / "a.pdf").write_bytes(b"%PDF-fake-a")
        (corpus_dir / "b.pdf").write_bytes(b"%PDF-fake-b")
        nested = corpus_dir / "sub"
        nested.mkdir()
        (nested / "c.docx").write_bytes(b"fake-docx-c")

        with patch("jseval.ingest.ingest_and_wait") as mock_ingest, \
                patch("jseval.ingest._ensure_materialized") as mock_materialize:
            mock_ingest.return_value = {"readiness_passed": True, "docs_indexed": 3}

            result = prepare_corpus(
                "mixed/raw-test-x", IngestConfig(base_url="http://localhost:33221"),
            )

        mock_materialize.assert_not_called()
        mock_ingest.assert_called_once()
        args, kwargs = mock_ingest.call_args
        assert args[1] == corpus_dir
        assert kwargs["corpus_doc_count"] == 3
        assert result["readiness_passed"] is True
        assert result["corpus_doc_count"] == 3
        assert result["corpus_identity"]["kind"] == "raw-files"
        assert result["corpus_identity"]["file_count"] == 3

    def test_raw_dataset_empty_corpus_dir_raises(self, tmp_path, monkeypatch):
        """corpus-dir exists but has zero files → FileNotFoundError, never reaches ingest."""
        from jseval import corpora
        base = tmp_path / "datasets"
        _seed_raw_dataset(base, "mixed/raw-test-empty", raw_files=True)  # corpus-dir stays empty
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        with patch("jseval.ingest.ingest_and_wait") as mock_ingest:
            with pytest.raises(RawCorpusManifestError, match="at least one"):
                prepare_corpus(
                    "mixed/raw-test-empty", IngestConfig(base_url="http://localhost:33221"),
                )
        mock_ingest.assert_not_called()

    def test_raw_dataset_missing_corpus_dir_raises(self, tmp_path, monkeypatch):
        """corpus-dir does not exist at all (never created) → also FileNotFoundError, not a
        crash on rglob() against a missing path."""
        import json
        from jseval import corpora
        base = tmp_path / "datasets"
        root = base / "mixed" / "raw-test-missing"
        root.mkdir(parents=True, exist_ok=True)
        (root / "metadata.json").write_text(json.dumps({"raw_files": True}), encoding="utf-8")
        # corpus-dir intentionally NOT created.
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        with pytest.raises(RawCorpusManifestError, match="unavailable"):
            prepare_corpus(
                "mixed/raw-test-missing", IngestConfig(base_url="http://localhost:33221"),
            )

    def test_raw_dataset_explicit_corpus_dir_must_be_declared_root(self, tmp_path, monkeypatch):
        """A raw dataset cannot substitute a different explicit source directory."""
        from jseval import corpora
        base = tmp_path / "datasets"
        corpus_dir = _seed_raw_dataset(base, "mixed/raw-test-x", raw_files=True)
        (corpus_dir / "a.pdf").write_bytes(b"%PDF-fake-a")  # would count as 1 via raw branch
        monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

        explicit_dir = tmp_path / "explicit"
        explicit_dir.mkdir()
        (explicit_dir / "doc1.txt").write_text("content")
        (explicit_dir / "doc2.txt").write_text("content")

        with patch("jseval.ingest.ingest_and_wait") as mock_ingest, \
                pytest.raises(RawCorpusManifestError, match="declared corpus-dir"):
            prepare_corpus(
                "mixed/raw-test-x", IngestConfig(base_url="http://localhost:33221"),
                corpus_dir=explicit_dir,
            )

        mock_ingest.assert_not_called()
