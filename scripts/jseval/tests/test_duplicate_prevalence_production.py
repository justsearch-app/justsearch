from __future__ import annotations

import hashlib
import hmac
import json
import os
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from jseval import duplicate_prevalence_production as production
from jseval import readiness
from jseval import result_identity
from jseval.raw_corpus_manifest import RawCorpusContext, build_raw_manifest


def _analysis() -> dict:
    return {
        "shingle_width": 1,
        "simhash_bits": 64,
        "max_hamming": 3,
        "jaccard_thresholds": [0.8],
        "exhaustive_slice_size": 100,
        "bootstrap_draws": 10,
        "seed": 7,
        "max_candidate_pairs": 10_000,
    }


def _worker_id(path: Path) -> str:
    value = os.path.normpath(str(path.resolve()))
    return value.lower() if os.name == "nt" else value


def _utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _safe_utf16_page(value: str, offset_units: int, max_units: int) -> tuple[str, int]:
    consumed = 0
    start = 0
    for index, character in enumerate(value):
        if consumed == offset_units:
            start = index
            break
        consumed += _utf16_units(character)
        if consumed > offset_units:
            raise AssertionError("test client received an offset inside a Unicode scalar")
    else:
        if consumed != offset_units:
            raise AssertionError("test client received an out-of-range UTF-16 offset")
        start = len(value)

    budget = min(max_units, 7)
    end = start
    page_units = 0
    while end < len(value):
        scalar_units = _utf16_units(value[end])
        if page_units and page_units + scalar_units > budget:
            break
        if not page_units and scalar_units > budget:
            page_units = scalar_units
            end += 1
            break
        page_units += scalar_units
        end += 1
    return value[start:end], offset_units + page_units


def _request(root: Path) -> production.ProductionAnalysisRequest:
    return production.ProductionAnalysisRequest(
        production.ProductionSourceSpec(root, "http://127.0.0.1:33221"),
        production.prevalence.AnalysisConfig(
            shingle_width=1,
            max_hamming=3,
            jaccard_thresholds=(0.8,),
            exhaustive_slice_size=100,
            bootstrap_draws=10,
            seed=7,
            max_candidate_pairs=10_000,
        ),
    )


def _request_with_exclusion(
    root: Path, exclusion: production.TerminalExclusionSpec
) -> production.ProductionAnalysisRequest:
    base = _request(root)
    return production.ProductionAnalysisRequest(
        production.ProductionSourceSpec(
            root,
            base.source.base_url,
            (exclusion,),
        ),
        base.config,
    )


def _write_input_spec(path: Path, root: Path) -> Path:
    path.write_text(
        json.dumps({
            "schema": production.request_schema.INPUT_SCHEMA,
            "source": {
                "kind": production.SOURCE_KIND,
                "raw_root": str(root),
                "base_url": "http://127.0.0.1:33221",
            },
            "analysis": _analysis(),
        }),
        encoding="utf-8",
    )
    return path


def _write_corpus(root: Path) -> dict[str, str]:
    files = {
        "a/report.txt": "raw-alpha",
        "b/report.txt": "raw-beta",
        "b/report.pdf": "raw-gamma",
    }
    for relative, content in files.items():
        path = root.joinpath(*relative.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    return {
        _worker_id(root / "a" / "report.txt"): "duplicate extracted words",
        _worker_id(root / "b" / "report.txt"): "unique second document",
        _worker_id(root / "b" / "report.pdf"): "duplicate extracted words",
    }


def _status(count: int) -> dict:
    return {
        "indexAvailable": True,
        "worker": {
            "buildStamp": "worker@test-897",
            "core": {
                "indexedDocuments": count,
                "pendingJobs": 0,
                "indexState": "IDLE",
                "pendingVduCount": 0,
                "writerQueueDepth": 0,
                "writerPendingDocs": 0,
                "commitCount": 4,
            },
            "failure": {"failedJobs": 0},
            "migration": {
                "activeGenerationId": "generation-1",
                "migrationState": "IDLE",
                "servingSearchGenerationId": "generation-1",
                "servingIngestGenerationId": "generation-1",
                "buildingIndexedDocuments": 0,
                "switchBufferDepth": 0,
                "pendingJobsCount": 0,
                "processingJobsCount": 0,
                "pendingReadyJobsCount": 0,
                "pendingBackoffJobsCount": 0,
            },
            "queueDb": {"queueDbHealthy": True},
            "visualExtraction": {"vduProcessing": False},
        },
    }


def _debug(count: int) -> dict:
    return {
        "is_healthy": True,
        "active_doc_count": count,
        "building_doc_count": 0,
        "doc_count": count,
        "last_commit_timestamp": 123,
        "pending_backoff_jobs_count": 0,
        "pending_jobs_count": 0,
        "pending_ready_jobs_count": 0,
        "processing_jobs_count": 0,
        "queue_depth": 0,
        "serving_ingest_generation_id": "generation-1",
        "serving_search_generation_id": "generation-1",
        "switch_buffer_depth": 0,
    }


class FakeClient:
    def __init__(self, texts: dict[str, str]) -> None:
        self.texts = texts
        self.doc_ids = list(reversed(texts))
        self.status_payloads = [_status(len(texts)), _status(len(texts))]
        self.debug_payloads = [_debug(len(texts)), _debug(len(texts))]
        self.status_reads = 0
        self.debug_reads = 0
        self.preview_calls: list[tuple[str, int, int]] = []
        self.failed_job_rows: list[dict] = []
        self.content_truncated = False
        self.extraction_status = "SUCCESS_FULL"
        self.source_sha256 = {
            doc_id: hashlib.sha256(Path(doc_id).read_bytes()).hexdigest() for doc_id in texts
        }
        self.health_payloads = [
            {
                "lifecycle": {"state": "LIFECYCLE_STATE_READY"},
                "components": {
                    "api": {"state": "READY"},
                    "index": {"state": "READY"},
                },
            },
            {
                "lifecycle": {"state": "LIFECYCLE_STATE_READY"},
                "components": {
                    "api": {"state": "READY"},
                    "index": {"state": "READY"},
                },
            },
        ]
        self.health_reads = 0

    def health(self):
        value = self.health_payloads[min(self.health_reads, len(self.health_payloads) - 1)]
        self.health_reads += 1
        return deepcopy(value)

    def status(self):
        value = self.status_payloads[min(self.status_reads, len(self.status_payloads) - 1)]
        self.status_reads += 1
        return deepcopy(value)

    def debug_state(self):
        value = self.debug_payloads[min(self.debug_reads, len(self.debug_payloads) - 1)]
        self.debug_reads += 1
        return deepcopy(value)

    def list_document_ids(self, offset: int, limit: int):
        assert (offset, limit) == (0, production.MAX_DOCUMENTS)
        return {"docIds": self.doc_ids, "totalCount": len(self.doc_ids), "tookMs": 1}

    def failed_jobs(self, limit: int):
        assert limit == production.MAX_DOCUMENTS
        return {"jobs": deepcopy(self.failed_job_rows), "count": len(self.failed_job_rows)}

    def preview(self, doc_id: str, offset_chars: int, max_chars: int):
        self.preview_calls.append((doc_id, offset_chars, max_chars))
        complete = self.texts[doc_id]
        content, next_offset = _safe_utf16_page(complete, offset_chars, max_chars)
        total_chars = _utf16_units(complete)
        return {
            "docId": doc_id,
            "requestedDocId": doc_id,
            "offsetChars": offset_chars,
            "nextOffsetChars": next_offset,
            "totalChars": total_chars,
            "truncated": next_offset < total_chars,
            "content": content,
            "mime": "text/plain",
            "path": doc_id,
            "source": "stored",
            "extractionStatus": self.extraction_status,
            "contentTruncated": self.content_truncated,
            "extractionPolicyId": "tika-policy-v1",
            "extractionParserId": "fixture-parser-v1",
            "sourceSha256": self.source_sha256[doc_id],
            "contentSha256": hashlib.sha256(complete.encode("utf-8")).hexdigest(),
        }


def _production_ready_status(count: int, *, failed_jobs: int = 0) -> dict:
    status = production.flatten_status(_status(count))
    status.update({
        "failedJobs": failed_jobs,
        "pendingVduCount": 0,
        "vduProcessing": False,
        "visualTextNeededCount": 0,
        "visualEnrichmentNeededCount": 0,
        "embeddingCoveragePercent": 100.0,
        "embeddingPendingCount": 0,
        "embeddingFailedCount": 0,
        "spladeCoveragePercent": 100.0,
        "spladePendingCount": 0,
        "spladeFailedCount": 0,
        "chunkDocCount": count,
        "chunkVectorCoveragePercent": 100.0,
        "chunkEmbeddingPendingCount": 0,
        "chunkEmbeddingFailedCount": 0,
        "chunkSpladeEnabled": True,
        "chunkSpladePendingCount": 0,
        "chunkSpladeCoveragePercent": 100.0,
        "chunkSpladeCompletedCount": count,
        "pendingNerCount": 0,
        "completedNerCount": count,
        "failedNerCount": 0,
        "writerQueueDepth": 0,
        "writerPendingDocs": 0,
    })
    return status


def test_production_readiness_requires_vdu_and_enrichment_quiescence():
    status = _production_ready_status(3)
    status["pendingVduCount"] = 1
    status["embeddingCoveragePercent"] = 80.0
    status["writerPendingDocs"] = 1

    reasons = production._required_production_readiness_reasons(
        status,
        expected_indexed_count=3,
        expected_failed_count=0,
    )

    assert "vdu_not_quiescent" in reasons
    assert "writerPendingDocs_not_zero" in reasons
    assert "embedding_not_complete" in reasons


def test_production_readiness_allows_only_exact_declared_failure_disposition():
    declared = _production_ready_status(2, failed_jobs=1)
    declared["indexState"] = "ERROR"
    reasons = production._required_production_readiness_reasons(
        declared,
        expected_indexed_count=2,
        expected_failed_count=1,
    )
    assert "index_not_idle" not in reasons
    assert not any("failed_job_count" in reason for reason in reasons)

    undeclared = dict(declared)
    undeclared["failedJobs"] = 2
    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="exceeds declared"):
        production._required_production_readiness_reasons(
            undeclared, expected_indexed_count=2, expected_failed_count=1,
        )


@pytest.mark.parametrize("index_state,passed", [("ERROR", True), ("FAILED", False)])
def test_production_poll_distinguishes_declared_document_error_from_fatal_loop(
    monkeypatch, index_state, passed,
):
    status = _production_ready_status(2, failed_jobs=1)
    status["indexState"] = index_state
    status["meta"] = {"workerRpcStale": False}
    fetch = Mock(return_value=status)
    sleep = Mock()
    monkeypatch.setattr(readiness, "_fetch_status", fetch)
    monkeypatch.setattr(readiness.time, "sleep", sleep)
    result = readiness._poll_until_stable(
        "http://127.0.0.1:33221",
        lambda snapshot: production._required_production_readiness_reasons(
            snapshot, expected_indexed_count=2, expected_failed_count=1,
        ),
        600, 1, 1,
    )
    assert result.passed is passed
    assert result.failure_reasons == ([] if passed else ["indexing_loop_failed"])
    fetch.assert_called_once()
    sleep.assert_not_called()


def test_production_readiness_structural_schema_error_is_not_swallowed():
    status = _production_ready_status(2)
    status["embeddingCoveragePercent"] = "100"

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="schema type"):
        production._required_production_readiness_reasons(
            status,
            expected_indexed_count=2,
            expected_failed_count=0,
        )


@pytest.fixture
def existing_index_client(monkeypatch):
    client = Mock(spec=production.ProductionHttpClient)
    client.list_document_ids.return_value = {"docIds": [], "totalCount": 0, "tookMs": 0}
    monkeypatch.setattr(production, "ProductionHttpClient", Mock(return_value=client))
    return client


def test_prepare_request_mutates_only_when_explicit_ingest_requested(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    request = _request(root)
    roots: list[Path] = []

    def add_root(path: Path) -> None:
        roots.append(path)

    monkeypatch.setattr(production.ingest_module, "add_watched_root", lambda _base, path, **kwargs: add_root(path))
    monkeypatch.setattr(
        production.readiness,
        "_poll_until_stable",
        lambda _base, check_fn, *_args, **_kwargs: (
            pytest.fail("readiness must not run for a standalone preparation")
        ),
    )

    assert production.prepare_request(request) == request
    assert roots == []
    production.ProductionHttpClient.assert_not_called()

    def ready_wait(_base, check_fn, *_args, **kwargs):
        snapshot = _production_ready_status(3)
        assert check_fn(snapshot) == []
        if kwargs.get("on_snapshot"):
            kwargs["on_snapshot"](0.1, snapshot)
        return production.readiness.ReadinessResult(passed=True, snapshot=snapshot)

    monkeypatch.setattr(production.readiness, "_poll_until_stable", ready_wait)
    prepared = production.prepare_request(
        request,
        ingest=True,
        timeout_seconds=1.0,
    )
    assert prepared == request
    assert roots == [root.resolve()]
    production.prepare_request(request, timeout_seconds=1.0)
    assert roots == [root.resolve()]  # Waiting on existing data never ingests again.


def test_ingest_and_wait_timeout_reports_blocking_reason(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    monkeypatch.setattr(production.ingest_module, "add_watched_root", lambda _base, _path, **kwargs: None)

    monkeypatch.setattr(
        production.readiness,
        "_poll_until_stable",
        lambda *_args, **_kwargs: production.readiness.ReadinessResult(
            passed=False,
            failure_reasons=["vdu_not_quiescent"],
        ),
    )

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="timed out.*vdu_not_quiescent",
    ):
        production.ingest_and_wait_for_snapshot(
            _request(root), timeout_seconds=0.1
        )


def test_ingest_wait_keeps_polling_through_pending_vdu_and_reenrichment(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    monkeypatch.setattr(production.ingest_module, "add_watched_root", lambda _base, _path, **kwargs: None)
    pending = _production_ready_status(3)
    pending["pendingVduCount"] = 1
    pending["vduProcessing"] = True
    pending["embeddingPendingCount"] = 1
    ready = _production_ready_status(3)
    seen: list[dict] = []

    def poll(_base, check_fn, *_args, **kwargs):
        assert check_fn(pending)
        assert check_fn(ready) == []
        if kwargs.get("on_snapshot"):
            kwargs["on_snapshot"](0.1, pending)
            kwargs["on_snapshot"](0.2, ready)
        return production.readiness.ReadinessResult(passed=True, snapshot=ready)

    monkeypatch.setattr(production.readiness, "_poll_until_stable", poll)
    production.ingest_and_wait_for_snapshot(
        _request(root), timeout_seconds=1.0, on_snapshot=lambda _t, value: seen.append(value)
    )
    assert [snapshot["pendingVduCount"] for snapshot in seen] == [1, 0]


def test_ingest_rejects_foreign_index_document_before_registering_root(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    foreign = _worker_id(tmp_path / "SSOT" / "docs" / "help" / "guide.md")
    existing_index_client.list_document_ids.return_value = {
        "docIds": [foreign], "totalCount": 1, "tookMs": 0,
    }
    add_root = Mock()
    wait = Mock(return_value=production.readiness.ReadinessResult(passed=True))
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)
    monkeypatch.setattr(production, "wait_for_snapshot_ready", wait)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="outside the declared corpus") as error:
        production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5)

    assert foreign not in str(error.value)
    add_root.assert_not_called()
    wait.assert_not_called()
    existing_index_client.close.assert_called_once()


@pytest.mark.parametrize("existing_count", [0, 1, 3])
def test_ingest_accepts_empty_or_matching_partial_index(tmp_path, monkeypatch, existing_index_client, existing_count):
    root = tmp_path / "raw"
    documents = _write_corpus(root)
    existing_index_client.list_document_ids.return_value = {
        "docIds": list(documents)[:existing_count], "totalCount": existing_count, "tookMs": 0,
    }
    add_root = Mock()
    wait = Mock(return_value=production.readiness.ReadinessResult(passed=True))
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)
    monkeypatch.setattr(production, "wait_for_snapshot_ready", wait)

    assert production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5).passed
    existing_index_client.list_document_ids.assert_called_once_with(0, production.MAX_DOCUMENTS)
    existing_index_client.close.assert_called_once()
    add_root.assert_called_once()
    wait.assert_called_once()


@pytest.mark.parametrize("payload", [
    {"docIds": [], "totalCount": True, "tookMs": 0},
    {"docIds": [], "totalCount": 1, "tookMs": 0},
    {"docIds": [], "totalCount": 0},
    {"docIds": [], "totalCount": 50001, "tookMs": 0},
])
def test_ingest_rejects_incomplete_existing_index_export(tmp_path, monkeypatch, existing_index_client, payload):
    root = tmp_path / "raw"
    _write_corpus(root)
    existing_index_client.list_document_ids.return_value = payload
    add_root = Mock()
    wait = Mock(return_value=production.readiness.ReadinessResult(passed=True))
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)
    monkeypatch.setattr(production, "wait_for_snapshot_ready", wait)

    with pytest.raises(production.ProductionDuplicatePrevalenceError):
        production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5)
    add_root.assert_not_called()
    wait.assert_not_called()
    existing_index_client.close.assert_called_once()


def test_ingest_rejects_normalized_aliases_before_registering(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    doc_id = next(iter(_write_corpus(root)))
    alias = str(Path(doc_id).parent) + os.sep + "." + os.sep + Path(doc_id).name
    assert alias != doc_id
    existing_index_client.list_document_ids.return_value = {
        "docIds": [doc_id, alias], "totalCount": 2, "tookMs": 0,
    }
    add_root = Mock()
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="collide"):
        production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5)
    add_root.assert_not_called()
    existing_index_client.close.assert_called_once()


def test_ingest_rejects_declared_terminal_identity_still_in_index(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    doc_id = next(iter(_write_corpus(root)))
    excluded = Path(doc_id)
    declaration = production.TerminalExclusionSpec(
        excluded.relative_to(root.resolve()).as_posix(),
        hashlib.sha256(excluded.read_bytes()).hexdigest(),
        "FAILED", "Sandbox parser failed", "corrupt-or-unsupported-parser-input",
    )
    existing_index_client.list_document_ids.return_value = {
        "docIds": [doc_id], "totalCount": 1, "tookMs": 0,
    }
    add_root = Mock()
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="outside the declared corpus"):
        production.ingest_and_wait_for_snapshot(_request_with_exclusion(root, declaration), timeout_seconds=5)
    add_root.assert_not_called()
    existing_index_client.close.assert_called_once()


def test_ingest_precheck_transport_failure_prevents_registration(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    existing_index_client.list_document_ids.side_effect = production.httpx.ConnectError("unavailable")
    add_root = Mock()
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="production ingest HTTP request failed"):
        production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5)
    add_root.assert_not_called()
    existing_index_client.close.assert_called_once()


def test_ingest_precheck_exhausting_deadline_prevents_registration(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    clock = [0.0]
    monkeypatch.setattr(production.time, "monotonic", lambda: clock[0])

    def export(*_args):
        clock[0] = 5.0
        return {"docIds": [], "totalCount": 0, "tookMs": 0}

    existing_index_client.list_document_ids.side_effect = export
    add_root = Mock()
    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="positive"):
        production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5)
    add_root.assert_not_called()
    existing_index_client.close.assert_called_once()


@pytest.mark.parametrize("stale_revision", [False, True])
def test_capture_rejects_same_source_content_change_between_pages(tmp_path, stale_revision):
    root = tmp_path / "PRIVATE-corpus"
    client = FakeClient(_write_corpus(root))
    preview = client.preview
    original_hashes = {k: hashlib.sha256(v.encode("utf-8")).hexdigest() for k, v in client.texts.items()}

    def changed_preview(doc_id, offset, count):
        if offset:
            client.texts[doc_id] = "X" * len(client.texts[doc_id])
        page = preview(doc_id, offset, count)
        if stale_revision:
            page["contentSha256"] = original_hashes[doc_id]
        return page

    client.preview = changed_preview
    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="metadata changed|stored-text revision"):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_reconciles_nested_collision_safe_ids_and_emits_only_aggregate(tmp_path):
    root = tmp_path / "PRIVATE-corpus"
    texts = _write_corpus(root)
    client = FakeClient(texts)

    snapshot = production.capture_snapshot(_request(root), client=client, env={})
    artifact = production.prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=_request(root).config,
    )

    assert len(snapshot.observations) == 3
    assert len(snapshot.aliases_by_opaque_id) == 3
    assert snapshot.extraction_identity["reconciliation"] == {
        "status": "matched-with-disposition-accounting",
        "expected_count": 3,
        "exported_count": 3,
        "unique_opaque_ids": 3,
        "indexed_count": 3,
        "terminal_excluded_count": 0,
        "partial_success_count": 0,
        "terminal_exclusion_reasons": {},
    }
    assert len(snapshot.result_alias_commitment_key) == 32
    assert len(snapshot.result_mapping_signing_key) == 32
    assert artifact["input"]["source_kind"] == "production-extracted"
    assert artifact["content_exact"]["duplicate_documents"] == 2
    assert artifact["byte_exact"]["duplicate_documents"] == 0
    assert artifact["privacy"] == {
        "mode": "aggregate-only",
        "document_ids_emitted": False,
        "paths_emitted": False,
        "text_emitted": False,
    }
    assert any(offset > 0 for _, offset, _ in client.preview_calls)
    serialized = json.dumps(artifact, sort_keys=True)
    raw_identity = build_raw_manifest(root)
    assert raw_identity.digest not in serialized
    assert snapshot.corpus_identity["signature"] == hmac.new(
        snapshot.result_alias_commitment_key,
        b"jseval.private-raw-manifest.v1\0" + raw_identity.digest.encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    for forbidden in (
        str(root),
        "PRIVATE-corpus",
        "a/report.txt",
        "b/report.txt",
        "b/report.pdf",
        "duplicate extracted words",
        "unique second document",
    ):
        assert forbidden not in serialized


def test_analysis_with_observations_captures_production_snapshot_once(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)

    artifact, observations = production.analyze_request_with_observations(
        _request(root), client=client, env={}
    )

    assert len(observations) == len(texts)
    assert artifact["input"]["observations_digest"] == production.prevalence.observation_commitment(
        observations
    )
    assert artifact["content_exact"]["duplicate_documents"] == 2
    assert client.health_reads == 2
    assert client.status_reads == 2
    assert client.debug_reads == 2


def test_snapshot_private_bindings_decorate_p5_result_sidecar(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    request = _request(root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})
    artifact = production.prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=request.config,
    )
    duplicate_paths = sorted(path for path, text in texts.items() if text == "duplicate extracted words")
    raw_hits = [
        {"id": path, "fields": {"path": path, "filename": Path(path).name}}
        for path in duplicate_paths
    ]
    mode_result = {
        "scored_docs": [
            SimpleNamespace(
                query_id="q1",
                doc_id=Path(path).stem.lower(),
                score=1.0,
            )
            for path in duplicate_paths
        ],
        "raw_responses": [{"query_id": "q1", "results": raw_hits}],
        "per_query_metrics": {"q1": {"R@10": 0.0}},
    }

    sidecar = result_identity.build_content_exact_result_identity_sidecar(
        {"hybrid": mode_result},
        observations=snapshot.observations,
        aliases_by_opaque_id=snapshot.aliases_by_opaque_id,
        result_alias_commitment_key=snapshot.result_alias_commitment_key,
        result_mapping_signing_key=snapshot.result_mapping_signing_key,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        analysis_artifact=artifact,
        config=request.config,
    )

    result_identity.verify_result_mapping_attestation(sidecar, artifact)
    assignments = sidecar["cluster_assignments"]["assignments"]
    assert len(assignments) == 2
    assert len({row["cluster_id"] for row in assignments}) == 1


def test_revalidate_snapshot_rejects_post_query_index_drift(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    request = _request(root)
    client = FakeClient(texts)
    snapshot = production.capture_snapshot(request, client=client, env={})
    drifted = _status(len(texts))
    drifted["worker"]["core"]["commitCount"] = 5
    client.status_payloads.append(drifted)

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="index identity changed after production snapshot",
    ):
        production.revalidate_snapshot(snapshot, request, client=client, env={})


def test_revalidate_snapshot_rejects_request_root_mismatch(tmp_path):
    root = tmp_path / "raw"
    other = tmp_path / "other"
    texts = _write_corpus(root)
    other.mkdir()
    request = _request(root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="does not match the captured snapshot source",
    ):
        production.revalidate_snapshot(snapshot, _request(other), client=FakeClient(texts), env={})


def _content_exact_write_inputs(snapshot, request):
    analysis = production.prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=request.config,
    )
    return result_identity.ContentExactResultIdentityInputs(
        observations=snapshot.observations,
        aliases_by_opaque_id=snapshot.aliases_by_opaque_id,
        result_alias_commitment_key=snapshot.result_alias_commitment_key,
        result_mapping_signing_key=snapshot.result_mapping_signing_key,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        analysis_artifact=analysis,
        config=request.config,
    )


def _duplicate_mode_results(texts):
    duplicate_paths = sorted(
        path for path, text in texts.items() if text == "duplicate extracted words"
    )
    mode_result = {
        "aggregate_metrics": {"nDCG@10": 1.0},
        "per_query_metrics": {"q1": {"nDCG@10": 1.0, "R@10": 1.0}},
        "scored_docs": [
            SimpleNamespace(
                query_id="q1", doc_id=Path(path).stem.lower(), score=1.0
            )
            for path in duplicate_paths
        ],
        "raw_responses": [{
            "query_id": "q1",
            "results": [
                {"id": path, "fields": {"path": path, "filename": Path(path).name}}
                for path in duplicate_paths
            ],
        }],
    }
    return {"lexical": mode_result, "hybrid": mode_result}, duplicate_paths


def test_write_run_decorates_private_snapshot_without_leaking_source_material(tmp_path):
    from jseval.artifacts import write_run
    from jseval.projections.staged_recall_accounting import produce

    raw_root = tmp_path / "PRIVATE-raw-corpus"
    texts = _write_corpus(raw_root)
    request = _request(raw_root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})
    inputs = _content_exact_write_inputs(snapshot, request)
    modes, duplicate_paths = _duplicate_mode_results(texts)
    keyed_identity = dict(snapshot.corpus_identity)
    summary = {
        "timestamp": "2026-09-04T00:00:00Z",
        "git_sha": "test",
        "dataset": "mixed/private",
        "modes": list(modes),
        "doc_count": len(texts),
        "query_count": 1,
        "corpus_identity": keyed_identity,
        "manifest": {"corpus_identity": keyed_identity},
        "ingest": {"corpus_identity": keyed_identity},
        "per_mode": {},
    }

    telemetry_data = tmp_path / "private-worker-data"
    (telemetry_data / "telemetry").mkdir(parents=True)
    (telemetry_data / "telemetry" / "traces.ndjson").write_text(
        json.dumps({"attributes": {"doc.path": str(raw_root / "a/report.txt")}}),
        encoding="utf-8",
    )
    with pytest.raises(result_identity.ResultIdentityError, match="forbid telemetry"):
        write_run(
            summary,
            modes,
            {"q1": {Path(duplicate_paths[0]).stem.lower(): 1}},
            tmp_path / "out",
            data_dir=telemetry_data,
            content_exact_identity=inputs,
        )
    assert not (tmp_path / "out").exists()

    run_dir = write_run(
        summary,
        modes,
        {"q1": {Path(duplicate_paths[0]).stem.lower(): 1}},
        tmp_path / "out",
        content_exact_identity=inputs,
    )
    sidecar = json.loads((run_dir / "result_identity.v1.json").read_text())
    analysis = json.loads((run_dir / "duplicate_prevalence.v1.json").read_text())
    written_summary = json.loads((run_dir / "summary.json").read_text())
    projection = produce(run_dir)
    signature = keyed_identity["signature"]

    assert projection["result_redundancy"]["aggregate"]["redundant_hits_at_10"] == 1
    assert sidecar["corpus_signature"] == signature
    assert analysis["input"]["corpus_identity"]["signature"] == signature
    assert written_summary["corpus_identity"]["signature"] == signature
    assert written_summary["manifest"]["corpus_identity"]["signature"] == signature
    assert written_summary["ingest"]["corpus_identity"]["signature"] == signature
    assert written_summary["result_identity_anchor"]["analysis_artifact_sha256"] == (
        analysis["artifact_hash"]
    )

    serialized = "\n".join(
        path.read_text(encoding="utf-8")
        for path in run_dir.rglob("*")
        if path.is_file()
    )
    assert build_raw_manifest(raw_root).digest not in serialized
    for forbidden in (
        str(raw_root),
        "PRIVATE-raw-corpus",
        "a/report.txt",
        "b/report.pdf",
        "duplicate extracted words",
    ):
        assert forbidden not in serialized


def test_write_run_content_exact_signature_mismatch_is_preflighted(tmp_path):
    from jseval.artifacts import write_run

    raw_root = tmp_path / "raw"
    texts = _write_corpus(raw_root)
    request = _request(raw_root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})
    inputs = _content_exact_write_inputs(snapshot, request)
    summary = {
        "dataset": "mixed/private",
        "corpus_identity": dict(snapshot.corpus_identity),
        "manifest": {"corpus_identity": {"signature": "f" * 64}},
    }

    with pytest.raises(result_identity.ResultIdentityError, match="persisted bindings: manifest"):
        write_run(
            summary,
            {"hybrid": _duplicate_mode_results(texts)[0]["hybrid"]},
            {},
            tmp_path / "out",
            content_exact_identity=inputs,
        )

    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("settle_index", [False, True])
def test_execute_run_carries_private_snapshot_through_artifacts_and_projection(
    tmp_path, monkeypatch, settle_index,
):
    from jseval import corpora, run as run_module
    from jseval.projections import staged_recall_accounting
    from jseval.types import (
        AnnProofResult,
        ComparabilityResult,
        QueryRecord,
        ReadinessResult,
    )

    raw_root = tmp_path / "PRIVATE-full-run"
    texts = _write_corpus(raw_root)
    request = _request(raw_root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})
    raw_identity = build_raw_manifest(raw_root)
    raw_context = RawCorpusContext(
        "mixed/private",
        raw_root.resolve(),
        raw_identity,
        admission_policy=production.effective_admission_policy({}),
    )
    mode_results, duplicate_paths = _duplicate_mode_results(texts)
    scored_docs = mode_results["hybrid"]["scored_docs"]
    raw_responses = mode_results["hybrid"]["raw_responses"]
    events: list[str] = []

    def settle(_base_url):
        events.append("settle")
        return {"max_doc_after": len(texts), "num_docs_after": len(texts), "segments_after": 1}

    monkeypatch.setattr(run_module, "_settle_index", settle)

    monkeypatch.setattr(
        corpora,
        "load",
        lambda *_args, **_kwargs: (
            {"q1": QueryRecord(text="duplicate")},
            {"q1": {Path(duplicate_paths[0]).stem.lower(): 1}},
            SimpleNamespace(name="private", doc_count=len(texts), query_count=1),
        ),
    )
    monkeypatch.setattr(
        run_module.readiness,
        "check_search_ready",
        lambda *_args, **_kwargs: ReadinessResult(passed=True),
    )

    def retrieve(*_args, **_kwargs):
        events.append("query")
        return scored_docs, deepcopy(raw_responses)

    monkeypatch.setattr(run_module.retriever, "retrieve", retrieve)
    monkeypatch.setattr(run_module.scoring, "evaluate", lambda *_args: {"nDCG@10": 1.0})
    monkeypatch.setattr(
        run_module.scoring,
        "evaluate_per_query",
        lambda *_args: {"q1": {"nDCG@10": 1.0, "R@10": 1.0}},
    )
    monkeypatch.setattr(
        run_module.provenance,
        "extract_query_evidence",
        lambda _response: {"effective_mode": "HYBRID", "error": None},
    )
    monkeypatch.setattr(
        run_module.provenance,
        "aggregate_run_evidence",
        lambda _values: {"error_count": 0, "component_status_counts": {}},
    )
    monkeypatch.setattr(
        run_module.ann_proof_mod,
        "compute_ann_proof",
        lambda *_args, **_kwargs: AnnProofResult(status="PASS"),
    )
    monkeypatch.setattr(
        run_module.comparability_mod,
        "determine_comparability",
        lambda *_args, **_kwargs: ComparabilityResult(comparable=True),
    )
    monkeypatch.setattr(run_module, "_snapshot_models", lambda *_args: {})
    monkeypatch.setattr(run_module, "_snapshot_search_config", lambda *_args: {})
    monkeypatch.setattr(run_module, "_capture_env_fingerprint", lambda: {})
    monkeypatch.setattr(
        run_module.manifest_mod,
        "capture_state_snapshots",
        lambda *_args: {"/api/status": {}},
    )
    monkeypatch.setattr(
        run_module.manifest_mod,
        "compute_manifest",
        lambda **kwargs: {"corpus_identity": kwargs["corpus_identity"]},
    )
    monkeypatch.setattr(
        production,
        "capture_snapshot",
        lambda *_args, **_kwargs: (events.append("capture") or snapshot),
    )
    monkeypatch.setattr(
        production,
        "revalidate_snapshot",
        lambda *_args, **_kwargs: events.append("revalidate"),
    )
    original_produce = staged_recall_accounting.produce

    def staged_preflight(run_dir):
        events.append("staged")
        return original_produce(run_dir)

    monkeypatch.setattr(staged_recall_accounting, "produce", staged_preflight)

    def append_history(*_args, **_kwargs):
        events.append("history")

    monkeypatch.setattr(run_module.history_mod, "append_run", append_history)

    def run_projections(run_dir, *, skip=frozenset()):
        assert "staged_recall_accounting" not in skip
        result = original_produce(run_dir)
        projection_dir = run_dir / "projections"
        projection_dir.mkdir()
        (projection_dir / "staged_recall_accounting.json").write_text(
            json.dumps(result), encoding="utf-8"
        )
        return {"staged_recall_accounting": {"status": "ok"}}

    import jseval.projections as projections

    monkeypatch.setattr(projections, "run_all_discovered", run_projections)
    ingest_summary = {"corpus_identity": raw_context.to_corpus_identity()}
    output_dir = tmp_path / "runs"
    worker_data_dir = tmp_path / "worker-data"
    telemetry_dir = worker_data_dir / "telemetry"
    telemetry_dir.mkdir(parents=True)
    (telemetry_dir / "traces.ndjson").write_text(
        json.dumps({"attributes": {"doc.path": str(raw_root / "a/report.txt")}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(worker_data_dir))

    summary = run_module.execute_run(
        "mixed/private",
        request.source.base_url,
        ["lexical", "hybrid"],
        output_dir=output_dir,
        ingest_summary=ingest_summary,
        raw_context=raw_context,
        duplicate_prevalence_request=request,
        settle_index=settle_index,
    )

    expected_order = (["settle"] if settle_index else []) + [
        "capture", "query", "query", "revalidate",
    ]
    assert events[:len(expected_order)] == expected_order
    assert summary["index_state_at_query"]["settled"] is settle_index
    assert events.index("staged") < events.index("history")
    run_dir = next(path for path in output_dir.iterdir() if path.is_dir())
    written_summary = json.loads((run_dir / "summary.json").read_text())
    signature = snapshot.corpus_identity["signature"]
    assert summary["corpus_identity"]["signature"] == signature
    assert written_summary["ingest"]["corpus_identity"]["signature"] == signature
    assert (run_dir / "duplicate_prevalence.v1.json").is_file()
    assert (run_dir / "projections" / "staged_recall_accounting.json").is_file()
    assert not (run_dir / "traces.ndjson").exists()
    serialized = "\n".join(
        path.read_text(encoding="utf-8")
        for path in run_dir.rglob("*")
        if path.is_file()
    )
    assert raw_identity.digest not in serialized
    assert str(raw_root) not in serialized
    assert "PRIVATE-full-run" not in serialized
    assert "a/report.txt" not in serialized


def test_private_snapshot_and_inputs_repr_hide_paths_text_and_keys(tmp_path):
    raw_root = tmp_path / "PRIVATE-repr"
    texts = _write_corpus(raw_root)
    request = _request(raw_root)
    snapshot = production.capture_snapshot(request, client=FakeClient(texts), env={})
    inputs = _content_exact_write_inputs(snapshot, request)

    for rendered in (repr(snapshot), repr(inputs)):
        assert str(raw_root) not in rendered
        assert "duplicate extracted words" not in rendered
        assert snapshot.result_alias_commitment_key.hex() not in rendered


def test_execute_run_private_path_rejects_cache_and_signature_override(
    tmp_path, monkeypatch,
):
    from jseval import run as run_module

    raw_root = tmp_path / "raw"
    _write_corpus(raw_root)
    identity = build_raw_manifest(raw_root)
    context = RawCorpusContext(
        "mixed/private",
        raw_root.resolve(),
        identity,
        admission_policy=production.effective_admission_policy({}),
    )
    request = _request(raw_root)
    kwargs = {
        "output_dir": tmp_path / "out",
        "raw_context": context,
        "duplicate_prevalence_request": request,
    }

    with pytest.raises(ValueError, match="fresh, uncached index"):
        run_module.execute_run(
            "mixed/private",
            request.source.base_url,
            ["hybrid"],
            index_cache={"mode": "adopted"},
            **kwargs,
        )

    monkeypatch.setenv("JUSTSEARCH_CORPUS_SIGNATURE", identity.digest)
    with pytest.raises(ValueError, match="forbids JUSTSEARCH_CORPUS_SIGNATURE"):
        run_module.execute_run(
            "mixed/private",
            request.source.base_url,
            ["hybrid"],
            **kwargs,
        )

    monkeypatch.delenv("JUSTSEARCH_CORPUS_SIGNATURE")
    monkeypatch.setenv("JUSTSEARCH_SKIP_PROJECTIONS", "staged_recall_accounting")
    with pytest.raises(ValueError, match="requires staged_recall_accounting"):
        run_module.execute_run(
            "mixed/private",
            request.source.base_url,
            ["hybrid"],
            **kwargs,
        )


def test_run_cli_private_flag_requires_fresh_owned_ingest(tmp_path, monkeypatch):
    from click.testing import CliRunner
    from jseval import corpora
    from jseval.cli import main

    base = tmp_path / "datasets"
    dataset_root = base / "mixed" / "private"
    raw_root = dataset_root / "corpus-dir"
    _write_corpus(raw_root)
    dataset_root.mkdir(parents=True, exist_ok=True)
    (dataset_root / "metadata.json").write_text(
        json.dumps({"raw_files": True}), encoding="utf-8"
    )
    spec = _write_input_spec(tmp_path / "private-input.json", raw_root)
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

    result = CliRunner().invoke(main, [
        "run",
        "--dataset", "mixed/private",
        "--modes", "hybrid",
        "--corpus-dir", str(raw_root),
        "--duplicate-prevalence-input-spec", str(spec),
    ])

    assert result.exit_code == 2
    assert "requires --start-backend --clean with ingestion enabled" in result.output


def test_run_cli_private_flag_rejects_cache_and_operator_signature(tmp_path, monkeypatch):
    from click.testing import CliRunner
    from jseval import corpora
    from jseval.cli import main

    base = tmp_path / "datasets"
    dataset_root = base / "mixed" / "private"
    raw_root = dataset_root / "corpus-dir"
    _write_corpus(raw_root)
    dataset_root.mkdir(parents=True, exist_ok=True)
    (dataset_root / "metadata.json").write_text(
        json.dumps({"raw_files": True}), encoding="utf-8"
    )
    spec = _write_input_spec(tmp_path / "private-input.json", raw_root)
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)
    base_args = [
        "run",
        "--dataset", "mixed/private",
        "--modes", "hybrid",
        "--corpus-dir", str(raw_root),
        "--duplicate-prevalence-input-spec", str(spec),
        "--start-backend",
        "--clean",
    ]

    cached = CliRunner().invoke(main, [*base_args, "--index-cache"])
    assert cached.exit_code == 2
    assert "requires --fresh-index" in cached.output

    monkeypatch.setenv(
        "JUSTSEARCH_CORPUS_SIGNATURE", build_raw_manifest(raw_root).digest
    )
    overridden = CliRunner().invoke(main, base_args)
    assert overridden.exit_code == 2
    assert "forbids JUSTSEARCH_CORPUS_SIGNATURE" in overridden.output


def test_capture_rejects_folder_style_omission_without_disclosing_paths(tmp_path):
    root = tmp_path / "PRIVATE-corpus"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.doc_ids.pop()

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="enumeration did not match expected count",
    ) as raised:
        production.capture_snapshot(_request(root), client=client, env={})

    assert str(root) not in str(raised.value)


def test_capture_rejects_concurrent_index_generation_change(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.status_payloads[1]["worker"]["core"]["commitCount"] = 5

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="index identity changed",
    ):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_rejects_lifecycle_change_during_snapshot(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.health_payloads[1]["lifecycle"]["state"] = "LIFECYCLE_STATE_DEGRADED"

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="ready API/index components",
    ):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_rejects_legacy_process_slots(tmp_path):
    """Lane F F-4 reads schema-2 components; old process slots are insufficient."""
    root = tmp_path / "raw"
    client = FakeClient(_write_corpus(root))
    client.health_payloads[0]["components"] = {
        "head": {"state": "LIFECYCLE_STATE_READY"},
        "worker": {"state": "LIFECYCLE_STATE_READY"},
    }
    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="ready API/index components"):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_accepts_exact_offline_inference_disposition(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    for health in client.health_payloads:
        health["lifecycle"] = {
            "state": "LIFECYCLE_STATE_DEGRADED",
            "reason_code": "inference.offline",
        }
        health["components"]["generative"] = {
            "state": "UNAVAILABLE",
            "reason_code": "inference.offline",
        }

    snapshot = production.capture_snapshot(_request(root), client=client, env={})

    assert snapshot.health_identity[:2] == (
        "LIFECYCLE_STATE_DEGRADED",
        "inference.offline",
    )


def test_capture_projects_worker_from_real_debug_state_envelope(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.debug_payloads = [
        {"system": {"pid": 1}, "worker": value} for value in client.debug_payloads
    ]

    snapshot = production.capture_snapshot(_request(root), client=client, env={})

    assert snapshot.debug_identity[:2] == (3, 3)


def test_http_cohort_settle_retries_only_count_mismatch(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    complete = list(client.doc_ids)
    client.doc_ids = complete[:-1]
    calls = 0
    original = client.list_document_ids

    def settling_list(offset, limit):
        nonlocal calls
        calls += 1
        if calls == 2:
            client.doc_ids = complete
        return original(offset, limit)

    client.list_document_ids = settling_list

    production._await_expected_document_cohort(
        client, len(complete), timeout_seconds=0.1, poll_seconds=0
    )

    assert calls == 2


def test_owned_http_capture_runs_cohort_settle_before_snapshot(tmp_path, monkeypatch):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.close = lambda: None
    monkeypatch.setattr(production, "ProductionHttpClient", lambda *args, **kwargs: client)

    snapshot = production.capture_snapshot(_request(root), env={})

    assert len(snapshot.observations) == 3
    assert client.health_reads == 2


def test_capture_rejects_same_path_same_count_stale_index(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    changed_path = root / "a" / "report.txt"
    changed_path.write_text("raw-ALPHA", encoding="utf-8")

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="source-byte provenance",
    ):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_accepts_and_discloses_policy_bounded_partial_extraction(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.content_truncated = True
    client.extraction_status = "SUCCESS_PARTIAL"

    snapshot = production.capture_snapshot(_request(root), client=client, env={})
    artifact = production.prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=_request(root).config,
    )

    assert snapshot.extraction_identity["reconciliation"]["partial_success_count"] == 3
    assert artifact["denominators"]["extraction_partial_successes"] == 3
    assert artifact["denominators"]["extraction_failures"] == 0


def test_capture_reconciles_one_declared_terminal_source_exclusion(tmp_path):
    root = tmp_path / "PRIVATE-corpus"
    texts = _write_corpus(root)
    excluded_path = root / "b" / "report.pdf"
    excluded_id = _worker_id(excluded_path)
    texts.pop(excluded_id)
    exclusion = production.TerminalExclusionSpec(
        "b/report.pdf",
        hashlib.sha256(excluded_path.read_bytes()).hexdigest(),
        "FAILED",
        "Sandbox parser failed",
        "corrupt-or-unsupported-parser-input",
    )
    client = FakeClient(texts)
    for status in client.status_payloads:
        status["worker"]["core"]["indexState"] = "ERROR"
        status["worker"]["failure"]["failedJobs"] = 1
    for debug in client.debug_payloads:
        debug["is_healthy"] = False
    excluded_hash = hashlib.sha256(
        production._worker_path_key(str(excluded_path.resolve())).encode("utf-8")
    ).hexdigest()
    client.failed_job_rows = [{
        "pathHash": excluded_hash,
        "state": "FAILED",
        "attempts": 1,
        "lastUpdatedMs": 1234,
        "errorMessage": "Sandbox parser failed",
        "retryAfterMs": 0,
        "collection": "default",
        "scanId": "",
    }]

    snapshot = production.capture_snapshot(
        _request_with_exclusion(root, exclusion), client=client, env={}
    )
    artifact = production.prevalence.analyze(
        snapshot.observations,
        corpus_identity=snapshot.corpus_identity,
        extraction_identity=snapshot.extraction_identity,
        config=_request(root).config,
    )

    assert len(snapshot.observations) == 3
    assert len(snapshot.aliases_by_opaque_id) == 3
    assert sum(item.extraction_status == "failed" for item in snapshot.observations) == 1
    assert artifact["denominators"]["manifest_files"] == 3
    assert artifact["denominators"]["extraction_successes"] == 2
    assert artifact["denominators"]["extraction_failures"] == 1
    assert artifact["denominators"]["terminal_excluded_documents"] == 1
    assert artifact["byte_exact"]["eligible_documents"] == 3
    assert artifact["content_exact"]["eligible_documents"] == 2
    assert artifact["denominators"]["terminal_exclusion_reasons"] == {
        "corrupt-or-unsupported-parser-input": 1
    }
    serialized = json.dumps(artifact, sort_keys=True)
    assert str(excluded_path) not in serialized
    assert excluded_hash not in serialized


def test_capture_rejects_undeclared_or_mismatched_failed_job(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.failed_job_rows = [{
        "pathHash": "f" * 64,
        "state": "FAILED",
        "attempts": 1,
        "lastUpdatedMs": 1234,
        "errorMessage": "Sandbox parser failed",
        "retryAfterMs": 0,
        "collection": "default",
        "scanId": "",
    }]

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="failed-job response does not match declared terminal exclusions",
    ):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_rejects_terminal_exclusion_with_stale_source_digest(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    exclusion = production.TerminalExclusionSpec(
        "b/report.pdf",
        "0" * 64,
        "FAILED",
        "Sandbox parser failed",
        "corrupt-or-unsupported-parser-input",
    )

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="digest does not match the raw manifest",
    ):
        production.capture_snapshot(
            _request_with_exclusion(root, exclusion),
            client=FakeClient(texts),
            env={},
        )


def test_capture_rejects_status_truncation_disagreement(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)
    client.content_truncated = True

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="status disagrees with its truncation state",
    ):
        production.capture_snapshot(_request(root), client=client, env={})


def test_capture_reconciles_java_utf16_character_offsets(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    target = next(iter(texts))
    texts[target] = "123456😀tail"
    client = FakeClient(texts)

    snapshot = production.capture_snapshot(_request(root), client=client, env={})

    assert any(item.extracted_text == "123456😀tail" for item in snapshot.observations)
    assert (target, 0, production.PREVIEW_PAGE_CHARS) in client.preview_calls
    assert (target, 6, production.PREVIEW_PAGE_CHARS) in client.preview_calls


@pytest.mark.parametrize(
    "base_url",
    [
        "https://127.0.0.1:33221",
        "http://example.test:33221",
        "http://127.0.0.1",
        "http://user:secret@127.0.0.1:33221",
        "http://127.0.0.1:33221/private",
    ],
)
def test_input_rejects_non_loopback_or_ambiguous_base_url(tmp_path, base_url):
    root = tmp_path / "raw"
    _write_corpus(root)
    spec = {
        "schema": production.request_schema.INPUT_SCHEMA,
        "source": {
            "kind": production.SOURCE_KIND,
            "raw_root": str(root),
            "base_url": base_url,
        },
        "analysis": _analysis(),
    }
    path = tmp_path / "input.json"
    path.write_text(json.dumps(spec), encoding="utf-8")

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="base_url"):
        production.load_input_spec(path)


def test_programmatic_request_rejects_non_loopback_before_http_client(
    tmp_path, monkeypatch
):
    root = tmp_path / "raw"
    _write_corpus(root)
    base = _request(root)
    request = production.ProductionAnalysisRequest(
        production.ProductionSourceSpec(root, "http://example.test:33221"),
        base.config,
    )
    constructed = False

    def unexpected_client(*_args, **_kwargs):
        nonlocal constructed
        constructed = True
        raise AssertionError("HTTP client must not receive an unvalidated origin")

    monkeypatch.setattr(production, "ProductionHttpClient", unexpected_client)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="base_url"):
        production.capture_snapshot(
            request,
            env={production.SESSION_TOKEN_ENV: "must-not-leave-loopback"},
        )

    assert constructed is False


def test_programmatic_revalidation_rejects_non_loopback_before_http_client(
    tmp_path, monkeypatch
):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    base = _request(root)
    snapshot = production.capture_snapshot(base, client=FakeClient(texts), env={})
    request = production.ProductionAnalysisRequest(
        production.ProductionSourceSpec(root, "http://example.test:33221"),
        base.config,
    )
    constructed = False

    def unexpected_client(*_args, **_kwargs):
        nonlocal constructed
        constructed = True
        raise AssertionError("HTTP client must not receive an unvalidated origin")

    monkeypatch.setattr(production, "ProductionHttpClient", unexpected_client)

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="base_url"):
        production.revalidate_snapshot(
            snapshot,
            request,
            env={production.SESSION_TOKEN_ENV: "must-not-leave-loopback"},
        )

    assert constructed is False


def test_programmatic_request_rejects_unapproved_terminal_exclusion(tmp_path):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    path = root / "b" / "report.pdf"
    exclusion = production.TerminalExclusionSpec(
        "b/report.pdf",
        hashlib.sha256(path.read_bytes()).hexdigest(),
        "FAILED",
        "Sandbox parser failed",
        "manual-override",
    )

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="not a supported aggregate category",
    ):
        production.capture_snapshot(
            _request_with_exclusion(root, exclusion),
            client=FakeClient(texts),
            env={},
        )


def test_windows_worker_path_key_uses_lowercase_not_casefold(monkeypatch):
    value = f"Corpus{os.sep}\N{LATIN CAPITAL LETTER SHARP S}{os.sep}report.pdf"
    path = Path(value)
    monkeypatch.setattr(production.os, "name", "nt")
    normalized = os.path.normpath(value.replace("/", os.sep))

    assert production._worker_path_key(value) == normalized.lower()
    assert production._worker_path_key(value) != normalized.casefold()
    assert production._path_hash(path) == hashlib.sha256(
        normalized.lower().encode("utf-8")
    ).hexdigest()


def test_input_parses_exact_terminal_exclusion_declaration(tmp_path):
    root = tmp_path / "raw"
    _write_corpus(root)
    declaration = {
        "relative_path": "b/report.pdf",
        "sha256": hashlib.sha256((root / "b" / "report.pdf").read_bytes()).hexdigest(),
        "expected_state": "FAILED",
        "expected_error_message": "Sandbox parser failed",
        "reason": "corrupt-or-unsupported-parser-input",
    }
    spec = {
        "schema": production.request_schema.INPUT_SCHEMA,
        "source": {
            "kind": production.SOURCE_KIND,
            "raw_root": str(root),
            "base_url": "http://127.0.0.1:33221",
            "terminal_exclusions": [declaration],
        },
        "analysis": _analysis(),
    }
    path = tmp_path / "input.json"
    path.write_text(json.dumps(spec), encoding="utf-8")

    request = production.load_input_spec(path)

    assert request.source.terminal_exclusions == (
        production.TerminalExclusionSpec(
            "b/report.pdf",
            declaration["sha256"],
            "FAILED",
            "Sandbox parser failed",
            "corrupt-or-unsupported-parser-input",
        ),
    )


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("relative_path", "../report.pdf", "canonical and relative"),
        ("expected_state", "RETRY_WAIT", "only FAILED"),
        ("reason", "manual-override", "not a supported aggregate category"),
    ],
)
def test_input_rejects_unsafe_terminal_exclusion_declaration(
    tmp_path, field, value, message
):
    root = tmp_path / "raw"
    _write_corpus(root)
    declaration = {
        "relative_path": "b/report.pdf",
        "sha256": "a" * 64,
        "expected_state": "FAILED",
        "expected_error_message": "Sandbox parser failed",
        "reason": "corrupt-or-unsupported-parser-input",
    }
    declaration[field] = value
    spec = {
        "schema": production.request_schema.INPUT_SCHEMA,
        "source": {
            "kind": production.SOURCE_KIND,
            "raw_root": str(root),
            "base_url": "http://127.0.0.1:33221",
            "terminal_exclusions": [declaration],
        },
        "analysis": _analysis(),
    }
    path = tmp_path / "input.json"
    path.write_text(json.dumps(spec), encoding="utf-8")

    with pytest.raises(production.ProductionDuplicatePrevalenceError, match=message):
        production.load_input_spec(path)


def test_raw_corpus_change_after_capture_fails_closed(tmp_path, monkeypatch):
    root = tmp_path / "raw"
    texts = _write_corpus(root)
    client = FakeClient(texts)

    def fail_validation(_root, _manifest):
        raise production.RawCorpusManifestError("private-file-name")

    monkeypatch.setattr(production, "validate_raw_manifest", fail_validation)

    with pytest.raises(
        production.ProductionDuplicatePrevalenceError,
        match="raw corpus changed during production snapshot",
    ) as raised:
        production.capture_snapshot(_request(root), client=client, env={})

    assert "private-file-name" not in str(raised.value)


@pytest.mark.parametrize("field", ["chunkEmbeddingPendingCount", "chunkEmbeddingFailedCount", "chunkSpladePendingCount"])
def test_production_wait_rejects_pending_chunk_status_despite_present_vectors(field):
    status = _production_ready_status(3)
    status["chunkVectorCoveragePercent"] = 99.9
    status["chunkSpladeCoveragePercent"] = 99.9
    status[field] = 1
    assert f"{field}_not_zero" in production._required_production_readiness_reasons(
        status, expected_indexed_count=3, expected_failed_count=0,
    )


@pytest.mark.parametrize("field", ["chunkEmbeddingPendingCount", "chunkEmbeddingFailedCount", "chunkSpladePendingCount", "chunkSpladeEnabled", "chunkSpladeCoveragePercent"])
def test_production_wait_requires_current_chunk_schema(field):
    status = _production_ready_status(3)
    del status[field]
    assert f"missing_production_readiness_field:{field}" in production._required_production_readiness_reasons(
        status, expected_indexed_count=3, expected_failed_count=0,
    )


@pytest.mark.parametrize("field,value", [("chunkSpladeEnabled", 1), ("chunkSpladePendingCount", -1), ("chunkVectorCoveragePercent", float("nan")), ("chunkSpladeCoveragePercent", float("inf"))])
def test_production_wait_rejects_malformed_chunk_values(field, value):
    status = _production_ready_status(3)
    status[field] = value
    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="schema type"):
        production._required_production_readiness_reasons(status, expected_indexed_count=3, expected_failed_count=0)


def test_production_ingest_forwards_boot_token_and_remaining_wait_budget(tmp_path, monkeypatch, existing_index_client):
    root = tmp_path / "raw"
    _write_corpus(root)
    clock = [10.0]
    monkeypatch.setattr(production.time, "monotonic", lambda: clock[0])
    monkeypatch.setenv(production.SESSION_TOKEN_ENV, "test-boot-token")

    def list_existing(offset, limit):
        assert offset == 0 and limit == production.MAX_DOCUMENTS
        clock[0] += 1
        return {"docIds": [], "totalCount": 0, "tookMs": 0}

    existing_index_client.list_document_ids.side_effect = list_existing

    def add_root(base, path, *, timeout_sec, session_token):
        assert path == root.resolve() and timeout_sec == 4
        assert session_token == "test-boot-token"
        clock[0] += 2

    def wait(request, *, timeout_seconds, on_snapshot):
        assert timeout_seconds == 2
        return production.readiness.ReadinessResult(passed=True)

    monkeypatch.setattr(production.ingest_module, "add_watched_root", add_root)
    monkeypatch.setattr(production, "wait_for_snapshot_ready", wait)
    assert production.ingest_and_wait_for_snapshot(_request(root), timeout_seconds=5).passed
    production.ProductionHttpClient.assert_called_once_with(
        _request(root).source.base_url, session_token="test-boot-token", timeout_seconds=5,
    )
    existing_index_client.close.assert_called_once()


def test_production_wait_rejects_chunk_splade_failure_below_rounded_readiness_threshold():
    status = _production_ready_status(1000)
    status["chunkSpladeCompletedCount"] = 999
    status["chunkSpladeCoveragePercent"] = 99.9
    assert status["chunkSpladePendingCount"] == 0
    assert "chunk_splade_not_fully_complete" in production._required_production_readiness_reasons(
        status, expected_indexed_count=1000, expected_failed_count=0,
    )


def test_production_wait_early_backend_failure_is_not_reported_as_elapsed_timeout(tmp_path, monkeypatch):
    root = tmp_path / "raw"
    _write_corpus(root)
    monkeypatch.setattr(production.readiness, "_poll_until_stable", lambda *a, **kw:
        production.readiness.ReadinessResult(passed=False, failure_reasons=["backend_unreachable: 5 failures"]))
    with pytest.raises(production.ProductionDuplicatePrevalenceError, match="failed before its 7200s deadline"):
        production.wait_for_snapshot_ready(_request(root), timeout_seconds=7200)
