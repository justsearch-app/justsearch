"""Tests for preflight.py — backend health and model identity checks."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from jseval.preflight import (
    assert_capabilities,
    derive_intended_engines,
    project_realized_capability,
    realized_engine_set,
    execute_preflight,
    format_console,
)


class TestExecutePreflight:
    @patch("jseval.preflight._fetch_endpoint")
    def test_healthy_backend(self, mock_fetch):
        mock_fetch.side_effect = [
            # /api/status
            {
                "indexAvailable": True,
                "uptimeMs": 60000,
                "indexState": "IDLE",
                "indexedDocuments": 500,
                "indexBasePath": "/data/index/default",
                "throughputWindowState": "OK",
                "embedBackend": "onnx",
                "embeddingFingerprintCurrent": "abc123",
                "embeddingFingerprintStored": "abc123",
                "embeddingCompatState": "FINGERPRINT_MATCH",
                "spladeModelPath": "/models/splade.onnx",
                "rerankerModelPath": "/models/reranker.onnx",
                "embedOrtCuda": {"available": True, "attempted": True},
                "spladeOrtCuda": {"available": False, "configured": False},
                "rerankerOrtCuda": {"available": False, "configured": False},
                "embedGpuLayers": 32,
                "embeddingCoveragePercent": 100.0,
                "spladeCoveragePercent": 100.0,
                "chunkVectorCoveragePercent": 100.0,
                "completedNerCount": 500,
                "pendingNerCount": 0,
                "meta": {"workerRpcStale": False},
            },
            # /api/debug/commit-metadata
            {
                "embedding_model_sha256": "sha256abcdef",
                "field_catalog_hash": "hash123",
                "schema_fp": "fp456",
                "index_fingerprint": "ifp789",
            },
        ]

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "ok"
        assert result["errors"] == []
        assert result["models"]["embed_backend"] == "onnx"
        assert result["gpu"]["embed_cuda_available"] is True
        assert result["index"]["commit_metadata"]["embedding_model_sha256"] == "sha256abcdef"

    @patch("jseval.preflight._fetch_endpoint")
    def test_unreachable_backend(self, mock_fetch):
        mock_fetch.return_value = None

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "unreachable"
        assert any("backend_unreachable" in e for e in result["errors"])

    @patch("jseval.preflight._fetch_endpoint")
    def test_degraded_worker(self, mock_fetch):
        mock_fetch.side_effect = [
            {
                "indexAvailable": False,
                "indexStatusReason": "Index component not ready",
                "meta": {"workerRpcStale": True},
            },
            None,  # commit-metadata fails
        ]

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "degraded"
        assert any("worker_unavailable" in e for e in result["errors"])
        assert any("worker_rpc_stale" in e for e in result["errors"])


class TestFormatConsole:
    def test_formats_ok(self):
        result = {
            "status": "ok",
            "errors": [],
            "backend": {"index_available": True, "index_state": "IDLE",
                         "indexed_documents": 500, "index_base_path": "/data"},
            "models": {"embed_backend": "onnx", "embed_compat_state": "OK",
                        "splade_model_path": None, "reranker_model_path": None},
            "gpu": {"embed_cuda_available": True, "embed_gpu_layers": 32,
                     "splade_cuda_available": False, "reranker_cuda_available": False,
                     "embed_cuda_failure": None},
            "index": {"coverage": {"embedding_pct": 100.0, "splade_pct": 100.0,
                                     "chunk_vector_pct": 100.0,
                                     "ner_completed": 500, "ner_pending": 0}},
        }
        output = format_console(result)
        assert "Preflight: OK" in output
        assert "embed_backend: onnx" in output


# ===================================================================================
# Tempdoc 644 Axis 2 — instrument-integrity guard
# ===================================================================================


class TestDeriveIntendedEngines:
    def test_ce_flag_intends_reranker(self):
        assert "reranker" in derive_intended_engines("lexical", cross_encoder=True)

    def test_hybrid_is_ce_bearing_server_mode(self):
        # hybrid runs CE on by server default even without --ce (tempdoc 644 U5).
        assert "reranker" in derive_intended_engines("hybrid")

    def test_leg_modes_do_not_intend_reranker(self):
        # vector/lexical/splade carry explicit crossEncoderEnabled:false → never flagged.
        assert derive_intended_engines("vector,lexical,splade") == set()

    def test_scoped_to_reranker_only(self):
        # tempdoc 644 live-debug: dense/splade are NOT hard-gated by the pre-run guard — their
        # model-presence isn't startup-observable (embedBackend/spladeModelPath populate during
        # enrichment), so gating them pre-ingest caused false refusals even when present. Only
        # the reranker (startup-stable signal + documented silent-off trap) is gated.
        assert derive_intended_engines("hybrid") == {"reranker"}
        assert derive_intended_engines("vector") == set()   # dense leg — not gated
        assert derive_intended_engines("splade") == set()   # splade leg — not gated
        assert derive_intended_engines("hybrid", cross_encoder=True) == {"reranker"}

    def test_list_modes_accepted(self):
        assert "reranker" in derive_intended_engines(["hybrid", "lexical"])


class TestAssertCapabilities:
    def _status(self, **over):
        base = {
            "rerankerModelPath": "/models/onnx/reranker",
            "embedBackend": "onnx",
            "spladeModelPath": "/models/splade",
            "rerankerOrtCuda": {"available": True, "configured": True},
        }
        base.update(over)
        return base

    def test_intended_and_present_is_ok(self):
        v = assert_capabilities(self._status(), {"reranker"})
        assert v["ok"] is True
        assert v["refusals"] == []

    def test_ce_intended_but_absent_refuses(self):
        # The worktree silent-CE-off trap: reranker intended, rerankerModelPath empty.
        v = assert_capabilities(self._status(rerankerModelPath=""), {"reranker"})
        assert v["ok"] is False
        assert any("reranker_intended_but_absent" in r for r in v["refusals"])

    def test_allow_degraded_downgrades_refusal_to_warning(self):
        v = assert_capabilities(
            self._status(rerankerModelPath=""), {"reranker"}, allow_degraded=True
        )
        assert v["ok"] is True
        assert v["refusals"] == []
        assert any("reranker_intended_but_absent" in w for w in v["warnings"])

    def test_no_intent_never_refuses(self):
        # A pure leg run intends nothing CE-bearing; absent reranker is fine.
        v = assert_capabilities(self._status(rerankerModelPath=""), set())
        assert v["ok"] is True
        assert v["refusals"] == []

    def test_cpu_only_is_warning_not_refusal(self):
        # Reranker present but on CPU → device warning, still ok (GPU deferred, T3-6).
        v = assert_capabilities(
            self._status(rerankerOrtCuda={"available": False}), {"reranker"}
        )
        assert v["ok"] is True
        assert any("reranker_cpu_only" in w for w in v["warnings"])


class TestProjectRealizedCapability:
    """tempdoc 644: the one realized-capability projector the guard + cohort both read."""

    def _status(self, **over):
        base = {
            "rerankerModelPath": "/models/onnx/reranker",
            "embedBackend": "onnx",
            "spladeModelPath": "/models/splade",
            "rerankerOrtCuda": {"available": True},
            "embedOrtCuda": {"available": False},
            "spladeOrtCuda": {"available": None, "failureReason": ""},
        }
        base.update(over)
        return base

    def test_present_and_device_facets(self):
        proj = project_realized_capability(self._status())
        assert proj["reranker"]["present"] is True
        assert proj["reranker"]["device"] == "gpu"           # available True
        assert proj["dense"]["present"] is True
        assert proj["dense"]["device"] == "cpu"              # available False
        assert proj["splade"]["present"] is True
        assert proj["splade"]["device"] is None              # available None (lazy/unprobed)

    def test_absent_engine_present_false_device_none(self):
        proj = project_realized_capability(self._status(rerankerModelPath=""))
        assert proj["reranker"]["present"] is False
        assert proj["reranker"]["device"] is None

    def test_failure_reason_in_detail(self):
        proj = project_realized_capability(
            self._status(rerankerOrtCuda={"available": False,
                                          "failureReason": "missing cuDNN dll"})
        )
        assert proj["reranker"]["detail"]["failure_reason"] == "missing cuDNN dll"

    def test_realized_engine_set_is_sorted_present_engines(self):
        assert realized_engine_set(self._status()) == ["dense", "reranker", "splade"]
        # CE silently off (the worktree trap) → distinct engine set.
        assert realized_engine_set(self._status(rerankerModelPath="")) == ["dense", "splade"]

    def test_none_status_is_all_absent(self):
        proj = project_realized_capability(None)
        assert all(not proj[e]["present"] for e in ("reranker", "dense", "splade"))
        assert realized_engine_set(None) == []

    def test_splade_intended_but_absent_refuses(self):
        v = assert_capabilities(self._status(spladeModelPath=""), {"splade"})
        assert v["ok"] is False
        assert any("splade_intended_but_absent" in r for r in v["refusals"])

    def test_unreachable_status_refuses(self):
        v = assert_capabilities(None, {"reranker"})
        assert v["ok"] is False
        assert any("backend_unreachable" in r for r in v["refusals"])

    def test_unreachable_with_allow_degraded_is_ok(self):
        v = assert_capabilities(None, {"reranker"}, allow_degraded=True)
        assert v["ok"] is True

    def test_reads_nested_worker_gpu_status(self):
        # Regression (tempdoc 644 live-debug): the realized signals live under worker.gpu.* in
        # the real /api/status — NOT at top level. assert_capabilities must read them through
        # flatten_status. This nested shape is what the live backend actually returns; the
        # earlier flat-dict tests masked the flatten_status `gpu` omission that left the guard
        # blind (it refused even when the reranker had loaded).
        nested = {
            "indexAvailable": True,
            "worker": {"gpu": {
                "rerankerModelPath": "/main/models/onnx/reranker",
                "rerankerOrtCuda": {"available": False, "configured": True},
            }},
        }
        v = assert_capabilities(nested, {"reranker"})
        assert v["ok"] is True, v
        assert v["realized"]["reranker"]["realized"] is True

    def test_nested_absent_reranker_refuses(self):
        nested = {"indexAvailable": True, "worker": {"gpu": {"rerankerModelPath": ""}}}
        v = assert_capabilities(nested, {"reranker"})
        assert v["ok"] is False
        assert any("reranker_intended_but_absent" in r for r in v["refusals"])
