"""Tests for readiness.py — gate condition logic."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from jseval.readiness import (
    _MAX_CONSECUTIVE_FETCH_FAILURES,
    _check_index_idle_conditions,
    _check_pipeline_complete_conditions,
    _check_search_conditions,
    _poll_until_stable,
    flatten_status,
    wait_embed_compat_settled,
)


class TestFlattenStatus:
    """tempdoc 644: data-driven flatten of worker sub-records (no hand-maintained allow-list)."""

    def test_flattens_direct_fields_and_subrecords(self):
        out = flatten_status({"worker": {
            "buildStamp": "abc",
            "core": {"indexedDocuments": 5},
            "gpu": {"rerankerModelPath": "/m/r", "embedOrtCuda": {"available": True}},
        }})
        assert out["buildStamp"] == "abc"          # non-dict direct field
        assert out["indexedDocuments"] == 5         # core sub-record child
        assert out["rerankerModelPath"] == "/m/r"   # gpu sub-record child
        # dict-valued leaf is copied through as a dict (one-level, NOT recursed) so preflight can
        # read it via `.get("embedOrtCuda").get("available")`.
        assert out["embedOrtCuda"] == {"available": True}

    def test_data_driven_surfaces_formerly_omitted_subrecord(self):
        # `visualExtraction` was absent from the old hardcoded allow-list — the data-driven
        # flatten surfaces it automatically (the omission class that once hid `gpu`).
        out = flatten_status({"worker": {"visualExtraction": {"ocrEnabled": True}}})
        assert out["ocrEnabled"] is True

    def test_sibling_collision_raises(self):
        # Two sub-records claiming the same top-level key would silently drop one — fail loud.
        with pytest.raises(ValueError, match="both define top-level key 'dup'"):
            flatten_status({"worker": {"a": {"dup": 1}, "b": {"dup": 2}}})

    def test_no_worker_returns_unchanged(self):
        assert flatten_status({"foo": 1}) == {"foo": 1}


def _good_snapshot(**overrides) -> dict:
    """A snapshot where all conditions pass."""
    base = {
        "indexState": "IDLE",
        "pendingJobs": 0,
        "pendingJobsCount": 0,
        "processingJobsCount": 0,
        "chunkVectorsReady": True,
        "embeddingCompatState": "OK",
        "embeddingCoveragePercent": 100.0,
        "spladeDocCount": 100,
        "spladePendingCount": 0,
        "spladeFailedCount": 0,
        "spladeCoveragePercent": 100.0,
        "pendingReadyJobsCount": 0,
        "pendingBackoffJobsCount": 0,
        "buildingIndexedDocuments": 0,
        "indexedDocuments": 500,
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# check_search_conditions
# ---------------------------------------------------------------------------

class TestCheckSearchConditions:
    def test_all_good(self):
        reasons = _check_search_conditions(
            _good_snapshot(), dense=True, splade=True, lambdamart=False,
            base_url="http://localhost",
        )
        assert reasons == []

    def test_index_not_idle(self):
        reasons = _check_search_conditions(
            _good_snapshot(indexState="INDEXING"),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_not_idle" in reasons

    def test_queue_not_quiescent_pending_jobs(self):
        reasons = _check_search_conditions(
            _good_snapshot(pendingJobs=5),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_queue_not_quiescent" in reasons

    def test_queue_not_quiescent_processing(self):
        reasons = _check_search_conditions(
            _good_snapshot(processingJobsCount=2),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_queue_not_quiescent" in reasons

    def test_dense_not_ready_vectors(self):
        reasons = _check_search_conditions(
            _good_snapshot(chunkVectorsReady=False),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert "dense_requested_but_chunk_vectors_not_ready" in reasons

    def test_dense_not_ready_when_not_requested(self):
        reasons = _check_search_conditions(
            _good_snapshot(chunkVectorsReady=False),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("dense" in r for r in reasons)

    def test_embedding_compat_blocked(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState="INCOMPATIBLE"),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        blocked = [r for r in reasons if "embedding_compat_blocked" in r]
        assert len(blocked) == 1
        assert "INCOMPATIBLE" in blocked[0]

    # [-1d] NEW_INDEX_NO_FINGERPRINT is no longer accepted — it's transient.
    @pytest.mark.parametrize("state", ["", "OK", "COMPATIBLE", "FINGERPRINT_MATCH"])
    def test_embedding_compat_ok_values(self, state):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState=state),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_compat" in r for r in reasons)

    def test_embedding_compat_new_index_no_fingerprint_rejected(self):
        """NEW_INDEX_NO_FINGERPRINT is transient and should not be accepted."""
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState="NEW_INDEX_NO_FINGERPRINT"),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert any("embedding_compat_blocked" in r for r in reasons)

    # [-1a] Doc-level embedding coverage check.
    def test_dense_embedding_coverage_low(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=30.0),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert "dense_requested_but_embedding_coverage_low" in reasons

    def test_dense_embedding_coverage_high(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=99.95),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_coverage" in r for r in reasons)

    def test_dense_embedding_coverage_not_checked_when_disabled(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=0.0),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_coverage" in r for r in reasons)

    def test_splade_not_ready_pending(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladePendingCount=5),
            dense=False, splade=True, lambdamart=False, base_url="",
        )
        assert "splade_requested_but_splade_features_not_ready" in reasons

    def test_splade_coverage_low(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladeCoveragePercent=95.0),
            dense=False, splade=True, lambdamart=False, base_url="",
        )
        assert "splade_requested_but_splade_features_not_ready" in reasons

    def test_splade_not_checked_when_disabled(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladePendingCount=100),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("splade" in r for r in reasons)

    # -- chunk-SPLADE (tempdoc 931 §E item 9) --------------------------------
    # Checked off the backend-published chunkSpladeEnabled flag, independent
    # of the caller's dense/splade request flags.

    def test_chunk_splade_enabled_low_coverage_blocks(self):
        reasons = _check_search_conditions(
            _good_snapshot(
                chunkDocCount=200, chunkSpladeEnabled=True,
                chunkSpladeCoveragePercent=10.0,
            ),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "chunk_splade_not_complete" in reasons

    def test_chunk_splade_flag_absent_adds_nothing(self):
        reasons = _check_search_conditions(
            _good_snapshot(chunkDocCount=200, chunkSpladeCoveragePercent=0.0),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("chunk_splade" in r for r in reasons)

    def test_chunk_splade_full_coverage_does_not_block(self):
        reasons = _check_search_conditions(
            _good_snapshot(
                chunkDocCount=200, chunkSpladeEnabled=True,
                chunkSpladeCoveragePercent=100.0,
            ),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("chunk_splade" in r for r in reasons)


# ---------------------------------------------------------------------------
# check_index_idle_conditions
# ---------------------------------------------------------------------------

class TestCheckIndexIdleConditions:
    def test_all_good(self):
        reasons = _check_index_idle_conditions(_good_snapshot(), -1)
        assert reasons == []

    def test_pending_ready_jobs(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(pendingReadyJobsCount=3), -1,
        )
        assert "index_queue_not_quiescent" in reasons

    def test_pending_backoff_jobs(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(pendingBackoffJobsCount=1), -1,
        )
        assert "index_queue_not_quiescent" in reasons

    def test_still_building(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(buildingIndexedDocuments=10), -1,
        )
        assert "index_still_building" in reasons

    def test_doc_count_below_floor(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=50), 100,
        )
        floor_reasons = [r for r in reasons if "below_floor" in r]
        assert len(floor_reasons) == 1
        assert "50/100" in floor_reasons[0]

    def test_doc_count_floor_disabled(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=0), -1,
        )
        assert not any("below_floor" in r for r in reasons)

    def test_doc_count_meets_floor(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=100), 100,
        )
        assert not any("below_floor" in r for r in reasons)


# ---------------------------------------------------------------------------
# _check_pipeline_complete_conditions — stage-enabled gating
# ---------------------------------------------------------------------------

def _pipeline_good_snapshot(**overrides) -> dict:
    """All pipeline-completion conditions satisfied."""
    base = _good_snapshot(
        completedNerCount=500,
        pendingNerCount=0,
        chunkDocCount=200,
        chunkVectorCoveragePercent=100.0,
    )
    base.update(overrides)
    return base


class TestCheckPipelineCompleteConditions:
    def test_all_good_all_enabled(self):
        reasons = _check_pipeline_complete_conditions(_pipeline_good_snapshot(), -1)
        assert reasons == []

    def test_splade_disabled_flag_in_snapshot_skips_splade_check(self):
        # SPLADE coverage at 0% (as it would be if the stage never ran).
        # With spladeEnabled absent or True, the check fires.
        # With spladeEnabled=False on the snapshot, the check is skipped.
        snap = _pipeline_good_snapshot(spladeCoveragePercent=0.0)
        blocked = _check_pipeline_complete_conditions(snap, -1)
        assert "splade_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(spladeCoveragePercent=0.0, spladeEnabled=False), -1,
        )
        assert "splade_not_complete" not in ok

    def test_ner_disabled_flag_in_snapshot_skips_ner_check(self):
        # NER completion would flag when pending > 0 OR done == 0.
        blocked = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(completedNerCount=0, pendingNerCount=0), -1,
        )
        assert "ner_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                completedNerCount=0, pendingNerCount=0, nerEnabled=False,
            ),
            -1,
        )
        assert "ner_not_complete" not in ok

    def test_embedding_disabled_flag_skips_embed_and_chunk_checks(self):
        # Both embed coverage and chunk-vector coverage ride on embeddingEnabled.
        blocked = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                embeddingCoveragePercent=0.0, chunkVectorCoveragePercent=0.0,
            ),
            -1,
        )
        assert "embedding_not_complete" in blocked
        assert "chunk_vectors_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                embeddingCoveragePercent=0.0,
                chunkVectorCoveragePercent=0.0,
                embeddingEnabled=False,
            ),
            -1,
        )
        assert "embedding_not_complete" not in ok
        assert "chunk_vectors_not_complete" not in ok

    def test_all_stages_disabled_still_checks_index(self):
        # Disabling stages should not bypass the index_not_idle check —
        # the index itself must still reach IDLE regardless of enrichment.
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                indexState="BUILDING",
                embeddingEnabled=False,
                spladeEnabled=False,
                nerEnabled=False,
            ),
            -1,
        )
        assert "index_not_idle" in ok

    def test_missing_enabled_fields_default_to_true(self):
        # Backward compat: older backends that don't publish the enabled flags
        # should behave as they did pre-394-follow-up (all stages checked).
        snap = _pipeline_good_snapshot(spladeCoveragePercent=0.0)
        # Explicitly remove any enabled keys to simulate absent fields.
        snap.pop("spladeEnabled", None)
        snap.pop("embeddingEnabled", None)
        snap.pop("nerEnabled", None)
        reasons = _check_pipeline_complete_conditions(snap, -1)
        assert "splade_not_complete" in reasons

    # -- chunk-SPLADE (tempdoc 931 §E item 9) --------------------------------
    # Unlike embedding/splade/ner above, chunkSpladeEnabled defaults to False
    # when absent (a genuinely new, optional field an older backend never
    # publishes) rather than True (the back-compat default for pre-existing
    # fields whose absence means "an old backend that always ran this stage").

    def test_chunk_splade_enabled_low_coverage_blocks(self):
        blocked = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                chunkSpladeEnabled=True, chunkSpladeCoveragePercent=42.0,
            ),
            -1,
        )
        assert "chunk_splade_not_complete" in blocked

    def test_chunk_splade_flag_absent_adds_nothing(self):
        snap = _pipeline_good_snapshot(chunkSpladeCoveragePercent=0.0)
        snap.pop("chunkSpladeEnabled", None)
        ok = _check_pipeline_complete_conditions(snap, -1)
        assert "chunk_splade_not_complete" not in ok

    def test_chunk_splade_full_coverage_does_not_block(self):
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                chunkSpladeEnabled=True, chunkSpladeCoveragePercent=100.0,
            ),
            -1,
        )
        assert "chunk_splade_not_complete" not in ok


# ---------------------------------------------------------------------------
# _poll_until_stable — fail-fast and progress
# ---------------------------------------------------------------------------

class TestPollUntilStable:
    """Tests for [-1b] fail-fast on consecutive failures and workerRpcStale."""

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_fresh_fatal_loop_aborts_before_predicate_or_sleep(self, mock_fetch, mock_sleep):
        snapshot = _good_snapshot(indexState="FAILED", meta={"workerRpcStale": False})
        mock_fetch.return_value = snapshot
        predicate = MagicMock(return_value=[])
        callback = MagicMock()
        result = _poll_until_stable(
            "http://localhost:33221", predicate, 600, 1, 1, on_snapshot=callback,
        )
        assert not result.passed
        assert result.failure_reasons == ["indexing_loop_failed"]
        assert result.snapshot is snapshot
        mock_fetch.assert_called_once()
        callback.assert_called_once()
        predicate.assert_not_called()
        mock_sleep.assert_not_called()

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_stale_fatal_state_does_not_override_fresh_recovery(self, mock_fetch, mock_sleep):
        mock_fetch.side_effect = [
            _good_snapshot(indexState="FAILED", meta={"workerRpcStale": True}),
            _good_snapshot(indexState="IDLE", meta={"workerRpcStale": False}),
        ]
        result = _poll_until_stable("http://localhost:33221", lambda s: [], 600, 1, 1)
        assert result.passed
        assert result.snapshot["indexState"] == "IDLE"
        assert mock_fetch.call_count == 2

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_document_error_is_left_to_corpus_predicate(self, mock_fetch, mock_sleep):
        mock_fetch.return_value = _good_snapshot(indexState="ERROR", failedJobs=1)
        result = _poll_until_stable(
            "http://localhost:33221",
            lambda s: [] if s["indexState"] == "ERROR" and s["failedJobs"] == 1 else ["pending"],
            600, 1, 1,
        )
        assert result.passed
        assert result.snapshot["failedJobs"] == 1
        mock_sleep.assert_not_called()

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_fail_fast_on_consecutive_failures(self, mock_fetch, mock_sleep):
        """5 consecutive fetch failures should abort immediately."""
        mock_fetch.side_effect = ConnectionError("refused")

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert not result.passed
        assert any("backend_unreachable" in r for r in result.failure_reasons)
        assert mock_fetch.call_count == _MAX_CONSECUTIVE_FETCH_FAILURES

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_failure_counter_resets_on_success(self, mock_fetch, mock_sleep):
        """Interspersed successes should reset the failure counter."""
        # Fail 3 times, succeed once (with blocking reasons), fail 3 more,
        # succeed with pass conditions twice.
        blocking_snapshot = _good_snapshot(indexState="INDEXING")
        passing_snapshot = _good_snapshot()

        mock_fetch.side_effect = [
            ConnectionError("1"), ConnectionError("2"), ConnectionError("3"),
            blocking_snapshot,
            ConnectionError("4"), ConnectionError("5"), ConnectionError("6"),
            passing_snapshot, passing_snapshot,
        ]

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: ["index_not_idle"] if s.get("indexState") != "IDLE" else [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert result.passed

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_worker_rpc_stale_blocks_readiness(self, mock_fetch, mock_sleep):
        """workerRpcStale prevents readiness even if check_fn passes."""
        stale_snapshot = _good_snapshot(meta={"workerRpcStale": True})
        fresh_snapshot = _good_snapshot(meta={"workerRpcStale": False})

        mock_fetch.side_effect = [
            stale_snapshot, stale_snapshot,
            fresh_snapshot, fresh_snapshot,
        ]

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert result.passed
        # Should have taken 4 polls (2 stale + 2 fresh/passing)
        assert mock_fetch.call_count == 4


class TestWaitEmbedCompatSettled:
    """Tests for wait_embed_compat_settled (tempdoc 715 defect 2).

    A fast small-corpus run can return from wait_pipeline_complete/wait_index_idle while
    embeddingCompatState still reads REBUILDING -- this bounded settle-wait polls /api/status
    until the state moves off REBUILDING (or times out and honestly returns whatever was last
    observed), so the manifest snapshot captured right after doesn't split the release cohort
    key on a transient state that would have read COMPATIBLE a few seconds later.
    """

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_already_compatible_returns_immediately(self, mock_fetch, mock_sleep):
        mock_fetch.return_value = {"embeddingCompatState": "COMPATIBLE"}

        state = wait_embed_compat_settled("http://localhost:33221")

        assert state == "COMPATIBLE"
        assert mock_fetch.call_count == 1
        mock_sleep.assert_not_called()

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_rebuilding_then_compatible_flips(self, mock_fetch, mock_sleep):
        mock_fetch.side_effect = [
            {"embeddingCompatState": "REBUILDING"},
            {"embeddingCompatState": "REBUILDING"},
            {"embeddingCompatState": "COMPATIBLE"},
        ]

        state = wait_embed_compat_settled(
            "http://localhost:33221", poll_interval_sec=1, timeout_sec=600,
        )

        assert state == "COMPATIBLE"
        assert mock_fetch.call_count == 3
        assert mock_sleep.call_count == 2

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_non_rebuilding_state_other_than_compatible_also_settles(self, mock_fetch, mock_sleep):
        # Any state other than REBUILDING is "settled" for this helper's purpose -- it isn't
        # re-implementing _VALID_EMBEDDING_COMPAT's allowlist, just detecting the transient.
        mock_fetch.return_value = {"embeddingCompatState": "FINGERPRINT_MATCH"}

        state = wait_embed_compat_settled("http://localhost:33221")

        assert state == "FINGERPRINT_MATCH"
        mock_sleep.assert_not_called()

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_timeout_warns_and_returns_last_observed_state(self, mock_fetch, mock_sleep, caplog):
        mock_fetch.return_value = {"embeddingCompatState": "REBUILDING"}

        with caplog.at_level("WARNING", logger="jseval.readiness"):
            state = wait_embed_compat_settled(
                "http://localhost:33221", poll_interval_sec=0.01, timeout_sec=0,
            )

        assert state == "REBUILDING"
        assert any("embeddingCompatState" in r.message for r in caplog.records)

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_all_fetches_fail_returns_none_and_warns(self, mock_fetch, mock_sleep, caplog):
        mock_fetch.side_effect = ConnectionError("refused")

        with caplog.at_level("WARNING", logger="jseval.readiness"):
            state = wait_embed_compat_settled(
                "http://localhost:33221", poll_interval_sec=0.01, timeout_sec=0,
            )

        assert state is None
        assert any("None" in r.message for r in caplog.records)


def test_readiness_poll_caps_request_and_sleep_to_remaining_deadline(monkeypatch):
    from jseval import readiness
    clock = [0.0]
    sleeps = []
    monkeypatch.setattr(readiness.time, "monotonic", lambda: clock[0])

    def fetch(client):
        assert 0 < client.timeout.read <= 0.5
        clock[0] += 0.4
        return _good_snapshot()

    def sleep(seconds):
        assert seconds <= 0.1 + 1e-9
        sleeps.append(seconds)
        clock[0] += seconds

    monkeypatch.setattr(readiness, "_fetch_status", fetch)
    monkeypatch.setattr(readiness.time, "sleep", sleep)
    result = readiness._poll_until_stable("http://127.0.0.1:33221", lambda s: ["pending"], 0.5, 2, 2)
    assert not result.passed
    assert len(sleeps) == 1 and clock[0] == 0.5


@pytest.mark.parametrize("field", ["embeddingPendingCount", "spladePendingCount", "pendingNerCount", "chunkDocCount"])
def test_unknown_enrichment_cannot_qualify_as_pipeline_or_search_ready(field):
    snapshot = _pipeline_good_snapshot()
    snapshot[field] = -1
    assert any("enrichment_unknown" in reason for reason in _check_pipeline_complete_conditions(snapshot, -1))
    assert any("enrichment_unknown" in reason for reason in _check_search_conditions(snapshot, False, False, False, ""))


def test_unreadable_document_count_is_not_a_known_empty_index():
    assert "indexed_doc_count_unknown" in _check_index_idle_conditions({"indexedDocumentsUnknown": True}, -1)


def test_dense_short_only_library_qualifies_only_with_declaration():
    snapshot = _good_snapshot(chunkVectorsReady=False, chunkDocCount=0, chunkingNotRequired=True)
    assert _check_search_conditions(snapshot, True, False, False, "unused") == []
    for overrides in ({"chunkingNotRequired": False}, {"chunkDocCount": -1}, {"chunkDocCount": 1}):
        assert "dense_requested_but_chunk_vectors_not_ready" in _check_search_conditions(
            {**snapshot, **overrides}, True, False, False, "unused")


def test_completeness_read_failure_prevents_readiness():
    snapshot = _good_snapshot(completenessKnown=False)
    assert "enrichment_unknown(completeness)" in _check_search_conditions(snapshot, True, False, False, "unused")


def test_sparse_chunks_use_their_own_applicable_population():
    snapshot = _good_snapshot(embeddingEnabled=False, spladeEnabled=False, nerEnabled=False,
                              chunkSpladeEnabled=True, chunkDocCount=200,
                              chunkSpladeDocCount=4, chunkSpladeCoveragePercent=75)
    assert "chunk_splade_not_complete" in _check_pipeline_complete_conditions(snapshot, -1)
    snapshot["chunkSpladeCoveragePercent"] = 100
    assert _check_pipeline_complete_conditions(snapshot, -1) == []
    snapshot.update(chunkSpladeDocCount=0, chunkSpladeCoveragePercent=0)
    assert _check_pipeline_complete_conditions(snapshot, -1) == []


def test_stale_count_does_not_qualify_as_a_current_observation():
    snapshot = _good_snapshot(countObservations={"active": {"state": "stale", "observedAtMs": 1234}})
    assert "index_count_stale(active)" in _check_index_idle_conditions(snapshot, 1)
    snapshot["countObservations"]["active"]["state"] = "fresh"
    assert _check_index_idle_conditions(snapshot, 1) == []


def test_zero_chunks_require_an_explicit_non_applicability_proof_when_reported():
    snapshot = _good_snapshot(embeddingEnabled=True, spladeEnabled=False, nerEnabled=False,
                              chunkDocCount=0, chunkingNotRequired=False)
    assert "chunk_vectors_not_complete" in _check_pipeline_complete_conditions(snapshot, -1)
    snapshot["chunkingNotRequired"] = True
    assert _check_pipeline_complete_conditions(snapshot, -1) == []


@pytest.mark.parametrize("coverage,ready", [(99.89, False), (99.9, True), (99.91, True)])
def test_evaluation_keeps_its_distinct_completion_threshold(coverage, ready):
    snapshot = _good_snapshot(embeddingEnabled=True, spladeEnabled=False, nerEnabled=False,
                              chunkDocCount=10000, chunkVectorCoveragePercent=coverage)
    reasons = _check_pipeline_complete_conditions(snapshot, -1)
    assert ("chunk_vectors_not_complete" not in reasons) is ready


def test_unreadable_building_count_is_only_required_during_a_rebuild():
    snapshot = _good_snapshot(countObservations={"building": {"state": "unknown", "observedAtMs": 0}},
                              migrationState="MIGRATING")
    assert "index_count_unknown(building)" in _check_index_idle_conditions(snapshot, -1)
    snapshot["migrationState"] = "IDLE"
    assert _check_index_idle_conditions(snapshot, -1) == []


def test_count_provenance_is_preserved_as_a_map_through_the_real_status_flatten():
    from jseval.readiness import flatten_status
    readings = {"active": {"state": "stale", "observedAtMs": 1234},
                "searchable": {"state": "fresh", "observedAtMs": 5678}}
    snapshot = flatten_status({"worker": {"core": {"indexedDocuments": 12}, "countObservations": readings}})
    assert snapshot["countObservations"] == readings
    assert "active" not in snapshot
    assert "index_count_stale(active)" in _check_index_idle_conditions(snapshot, 1)
