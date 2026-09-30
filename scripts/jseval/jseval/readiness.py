"""Backend readiness checks (post-ingest wait and pre-query gate)."""

from __future__ import annotations

import json as json_mod
import logging
import sys
import time

import httpx

from typing import Callable, Optional

from .types import ReadinessResult

log = logging.getLogger(__name__)

# Callback type for snapshot recording (timeline, etc.).
_SnapshotCallback = Callable[[float, dict], None]

# [-1d] Removed NEW_INDEX_NO_FINGERPRINT — it's a transient state that
# indicates "fingerprint not yet stamped."  After 330 §1, ECC transitions
# to FINGERPRINT_MATCH after the first commit.  jseval should wait for
# that, not accept the transient state.
_VALID_EMBEDDING_COMPAT = {"", "OK", "COMPATIBLE", "FINGERPRINT_MATCH"}

# [-1b] Consecutive fetch failures before aborting.
_MAX_CONSECUTIVE_FETCH_FAILURES = 5

# [item 0] Progress logging interval (in polls, not seconds).
_PROGRESS_LOG_INTERVAL = 15  # ~30s at default 2s poll interval


def check_search_ready(
    base_url: str,
    dense_enabled: bool = False,
    splade_enabled: bool = False,
    lambdamart_enabled: bool = False,
    timeout_sec: float = 30.0,
    poll_interval_sec: float = 2.0,
    stable_polls_required: int = 2,
    json_mode: bool = False,
) -> ReadinessResult:
    """Pre-query gate: is the backend ready for search?"""

    def check_fn(snapshot: dict) -> list[str]:
        return _check_search_conditions(
            snapshot, dense_enabled, splade_enabled, lambdamart_enabled, base_url,
        )

    return _poll_until_stable(
        base_url, check_fn, timeout_sec, poll_interval_sec, stable_polls_required,
        json_mode=json_mode, emit_stage_completions=False,
    )


def wait_index_idle(
    base_url: str,
    expected_doc_count_min: int = -1,
    dense_enabled: bool = False,
    splade_enabled: bool = False,
    timeout_sec: float = 7200.0,
    poll_interval_sec: float = 2.0,
    stable_polls_required: int = 2,
    on_snapshot: _SnapshotCallback | None = None,
    json_mode: bool = False,
    process_check: Optional[Callable[[], bool]] = None,
) -> ReadinessResult:
    """Post-ingest wait: is indexing complete?"""

    def check_fn(snapshot: dict) -> list[str]:
        reasons = _check_search_conditions(
            snapshot, dense_enabled, splade_enabled, False, base_url,
        )
        reasons.extend(_check_index_idle_conditions(snapshot, expected_doc_count_min))
        return reasons

    return _poll_until_stable(
        base_url, check_fn, timeout_sec, poll_interval_sec, stable_polls_required,
        on_snapshot=on_snapshot, json_mode=json_mode, process_check=process_check,
    )


def wait_pipeline_complete(
    base_url: str,
    expected_doc_count_min: int = -1,
    timeout_sec: float = 7200.0,
    poll_interval_sec: float = 2.0,
    stable_polls_required: int = 2,
    on_snapshot: _SnapshotCallback | None = None,
    json_mode: bool = False,
    process_check: Optional[Callable[[], bool]] = None,
) -> ReadinessResult:
    """Wait for ALL enabled enrichment stages to complete.

    Checks (gated on per-stage enabled flags read from the status snapshot):
    index idle, embedding 100%, SPLADE 100%, chunks 100%, NER complete
    (pending=0 and completed>0).

    Per-stage enabled state is read from ``embeddingEnabled`` / ``spladeEnabled``
    / ``nerEnabled`` on the ``/api/status`` snapshot. The Worker publishes these
    from its resolved config at wire time. When a stage is absent / False, the
    corresponding check is skipped — coverage of a disabled stage stays at 0
    forever and would otherwise hang the poll (tempdoc 394).

    Older backends that don't publish the enabled flags default to True for
    each stage (matches the production all-enabled case).
    """

    def check_fn(snapshot: dict) -> list[str]:
        return _check_pipeline_complete_conditions(snapshot, expected_doc_count_min)

    return _poll_until_stable(
        base_url, check_fn, timeout_sec, poll_interval_sec, stable_polls_required,
        on_snapshot=on_snapshot, json_mode=json_mode, process_check=process_check,
    )


_REBUILDING_COMPAT_STATE = "REBUILDING"
DEFAULT_COMPAT_SETTLE_TIMEOUT_SEC = 90.0
DEFAULT_COMPAT_SETTLE_POLL_INTERVAL_SEC = 2.0


def wait_embed_compat_settled(
    base_url: str,
    poll_interval_sec: float = DEFAULT_COMPAT_SETTLE_POLL_INTERVAL_SEC,
    timeout_sec: float = DEFAULT_COMPAT_SETTLE_TIMEOUT_SEC,
) -> str | None:
    """Bounded settle-wait for ``embeddingCompatState`` to leave REBUILDING (tempdoc 715 defect 2).

    A fresh ingest can return from :func:`wait_pipeline_complete` / :func:`wait_index_idle`
    while the Worker's ``EmbeddingCompatibilityController`` is still mid-transition:
    ``/api/status``'s ``worker.compatibility.embeddingCompatState`` (flattened to top-level
    ``embeddingCompatState`` by :func:`flatten_status`) briefly still reads ``REBUILDING`` even
    though indexing and enrichment have both quiesced. Empirically reproduced twice on
    ``mixed/legal-clerc-200`` (198 docs, ingest+enrich ~= 60s, 2026-07-16): the run manifest's
    ``model_fingerprints.embed_compat_state`` captured ``"REBUILDING"``, splitting the release
    cohort key from every other run of the same corpus/model that happened to finish ingest a
    little slower. Proof the state genuinely settles (this is not a permanent block): rebooting
    a backend over the same on-disk index reads ``COMPATIBLE`` immediately at t=0 (probe
    transcript, 2026-07-16) -- the fingerprint IS compatible, the controller just hadn't
    finished stamping it yet.

    Polls ``/api/status`` every ``poll_interval_sec`` (default 2s) until the state is no longer
    ``REBUILDING``, up to ``timeout_sec`` (default 90s). On timeout (or if the backend stays
    unreachable throughout), logs a warning and returns whatever was last observed rather than
    raising -- the manifest then honestly records that state instead of this helper masking a
    genuinely-stuck REBUILDING behind a silent/infinite wait. Never raises: a status-fetch
    failure is logged and retried like every other readiness poll in this module.

    Returns the final observed ``embeddingCompatState`` (``None`` if every fetch failed).
    """
    deadline = time.monotonic() + timeout_sec
    last_state: str | None = None
    with httpx.Client(base_url=base_url, timeout=10) as client:
        while True:
            try:
                snapshot = _fetch_status(client)
            except Exception as e:
                log.debug("wait_embed_compat_settled: status fetch failed: %s", e)
            else:
                last_state = snapshot.get("embeddingCompatState")
                if last_state != _REBUILDING_COMPAT_STATE:
                    return last_state

            if time.monotonic() >= deadline:
                log.warning(
                    "wait_embed_compat_settled: embeddingCompatState still %r after %.0fs "
                    "-- proceeding; the manifest will honestly record this state",
                    last_state, timeout_sec,
                )
                return last_state

            time.sleep(poll_interval_sec)


def _check_pipeline_complete_conditions(
    snapshot: dict,
    expected_doc_count_min: int,
) -> list[str]:
    """Pipeline-completion predicate: returns a list of outstanding reasons,
    empty list when complete. Per-stage enabled state is read from the snapshot
    itself — stages reporting ``*Enabled=false`` have their coverage/completion
    checks skipped, because a disabled stage's coverage stays at 0 forever and
    would otherwise hang the poll indefinitely."""
    reasons: list[str] = []

    if snapshot.get("indexState") != "IDLE":
        reasons.append("index_not_idle")

    if snapshot.get("pendingJobs", 0) != 0 or \
       snapshot.get("pendingJobsCount", 0) != 0 or \
       snapshot.get("processingJobsCount", 0) != 0:
        reasons.append("index_queue_not_quiescent")

    reasons.extend(_check_index_idle_conditions(snapshot, expected_doc_count_min))

    # Per-stage enabled state is published by the Worker on the status snapshot
    # (EnrichmentProgressView.{embeddingEnabled,spladeEnabled,nerEnabled}).
    # Default to True when the field is absent — an older backend without
    # those fields should behave as it did before (all stages checked).
    embedding_enabled = bool(snapshot.get("embeddingEnabled", True))
    splade_enabled = bool(snapshot.get("spladeEnabled", True))
    ner_enabled = bool(snapshot.get("nerEnabled", True))

    # All enrichment stages (99.9% threshold matches _check_search_conditions).
    if embedding_enabled and snapshot.get("embeddingCoveragePercent", 0) < 99.9:
        reasons.append("embedding_not_complete")
    if splade_enabled and snapshot.get("spladeCoveragePercent", 0) < 99.9:
        reasons.append("splade_not_complete")

    # Chunk vectors: only check if chunks exist (short docs produce no chunks).
    # Gated on embedding_enabled because chunk vectors are produced by the
    # embedding pipeline — disabling embed disables chunk vector coverage too.
    if embedding_enabled:
        chunk_doc_count = snapshot.get("chunkDocCount", 0)
        if chunk_doc_count > 0 and snapshot.get("chunkVectorCoveragePercent", 0) < 99.9:
            reasons.append("chunk_vectors_not_complete")

    # Chunk SPLADE: parallel to chunk vectors above, but gated on its own
    # ``chunkSpladeEnabled`` flag rather than embedding_enabled — chunk-level
    # sparse encoding is a separate stage from dense embedding (tempdoc 931
    # §E item 9). Older backends that don't publish the flag default to
    # False here (unlike embedding/splade/ner above, which default to True) —
    # the whole point is that an older backend must behave exactly as today
    # when the field is absent, not gate on a coverage percent it never
    # populates.
    if snapshot.get("chunkSpladeEnabled", False):
        chunk_splade_doc_count = snapshot.get("chunkDocCount", 0)
        if chunk_splade_doc_count > 0 and snapshot.get("chunkSpladeCoveragePercent", 0) < 99.9:
            reasons.append("chunk_splade_not_complete")

    if ner_enabled:
        ner_done = snapshot.get("completedNerCount", 0)
        ner_pending = snapshot.get("pendingNerCount", 0)
        if ner_pending > 0 or ner_done == 0:
            reasons.append("ner_not_complete")

    return reasons


# ---------------------------------------------------------------------------
# Polling loop
# ---------------------------------------------------------------------------

def _poll_until_stable(
    base_url: str,
    check_fn,
    timeout_sec: float,
    poll_interval_sec: float,
    stable_polls_required: int,
    on_snapshot: _SnapshotCallback | None = None,
    json_mode: bool = False,
    process_check: Optional[Callable[[], bool]] = None,
    emit_stage_completions: bool = True,
) -> ReadinessResult:
    deadline = time.monotonic() + timeout_sec
    start = time.monotonic()
    stable_passes = 0
    last_snapshot: dict = {}
    last_reasons: list[str] = []
    consecutive_failures = 0  # [-1b]
    poll_count = 0  # [item 0]
    # [335 item 11] One-time stage completion logging (disabled for short readiness gates
    # like check_search_ready to avoid duplicate "100% at t=0s" lines — item 18)
    stage_logged: dict[str, bool] = {
        "embed": not emit_stage_completions,
        "splade": not emit_stage_completions,
        "chunk": not emit_stage_completions,
        "chunk_splade": not emit_stage_completions,
        "ner": not emit_stage_completions,
    }

    # [-1e] Reuse HTTP client across all polls.
    with httpx.Client(base_url=base_url, timeout=10) as client:
        while time.monotonic() < deadline:
            try:
                client.timeout = httpx.Timeout(min(10.0, max(0.001, deadline - time.monotonic())))
                snapshot = _fetch_status(client)
                consecutive_failures = 0
            except Exception as e:
                consecutive_failures += 1
                log.warning("Status fetch failed (%d/%d): %s",
                            consecutive_failures, _MAX_CONSECUTIVE_FETCH_FAILURES, e)
                stable_passes = 0
                last_reasons = [f"status_fetch_failed: {e}"]

                # [-1b] Fail fast on consecutive failures.
                if consecutive_failures >= _MAX_CONSECUTIVE_FETCH_FAILURES:
                    log.error(
                        "Backend unreachable after %d consecutive failures",
                        consecutive_failures,
                    )
                    return ReadinessResult(
                        passed=False,
                        failure_reasons=[
                            f"backend_unreachable: {consecutive_failures} "
                            f"consecutive fetch failures (last: {e})"
                        ],
                        snapshot=last_snapshot,
                    )

                time.sleep(min(poll_interval_sec, max(0.0, deadline - time.monotonic())))
                continue

            # [-1b] Check Worker RPC staleness (from 333 §5).
            meta = snapshot.get("meta") or {}
            if meta.get("workerRpcStale"):
                stable_passes = 0
                last_reasons = ["worker_rpc_stale: Head is responding but "
                                "Worker data is stale"]
                last_snapshot = snapshot
                poll_count += 1
                _maybe_log_progress(poll_count, start, snapshot, last_reasons, json_mode)
                time.sleep(min(poll_interval_sec, max(0.0, deadline - time.monotonic())))
                continue

            last_snapshot = snapshot

            # Timeline recording callback.
            elapsed = time.monotonic() - start
            if on_snapshot is not None:
                on_snapshot(elapsed, snapshot)

            # A fatal indexing-loop death is terminal even while the Engine and search
            # RPCs remain healthy. ERROR is deliberately distinct: declared document failures
            # can be acceptable to a corpus-specific predicate. Stale snapshots were excluded above.
            if snapshot.get("indexState") == "FAILED":
                return ReadinessResult(
                    passed=False,
                    failure_reasons=["indexing_loop_failed"],
                    snapshot=snapshot,
                )

            # [335 item 11] One-time per-stage completion logging.
            _check_stage_completions(snapshot, elapsed, stage_logged, json_mode)

            reasons = check_fn(snapshot)
            last_reasons = reasons

            if not reasons:
                stable_passes += 1
                if stable_passes >= stable_polls_required:
                    return ReadinessResult(passed=True, snapshot=snapshot)
            else:
                stable_passes = 0

            poll_count += 1
            # [item 0] Periodic progress logging.
            _maybe_log_progress(poll_count, start, snapshot, reasons, json_mode)

            # [335 item 16] Fast-fail if backend process died.
            if process_check is not None and not process_check():
                log.error("Backend process died during readiness wait")
                return ReadinessResult(
                    passed=False,
                    failure_reasons=["backend_process_died"],
                    snapshot=last_snapshot,
                )

            time.sleep(min(poll_interval_sec, max(0.0, deadline - time.monotonic())))

    return ReadinessResult(
        passed=False, failure_reasons=last_reasons, snapshot=last_snapshot,
    )


def _maybe_log_progress(
    poll_count: int,
    start: float,
    snapshot: dict,
    reasons: list[str],
    json_mode: bool = False,
) -> None:
    """Log progress at INFO level every ~30s, or as NDJSON to stderr."""
    if poll_count % _PROGRESS_LOG_INTERVAL != 0:
        return

    elapsed = int(time.monotonic() - start)

    embed_pct = snapshot.get("embeddingCoveragePercent", 0)
    splade_pct = snapshot.get("spladeCoveragePercent", 0)
    ner_done = snapshot.get("completedNerCount", 0)
    ner_total = ner_done + snapshot.get("pendingNerCount", 0)
    chunk_pct = snapshot.get("chunkVectorCoveragePercent", 0)
    throughput = snapshot.get("throughputDocsPerSec", 0)

    heap_used = snapshot.get("memoryUsedBytes", 0)
    heap_max = snapshot.get("memoryMaxBytes", 0)
    heap_used_gb = round(heap_used / (1024 ** 3), 1) if heap_used else 0
    heap_max_gb = round(heap_max / (1024 ** 3), 1) if heap_max else 0

    # GPU metrics (335 §9: nullable, from GpuStatusView)
    gpu = snapshot.get("gpu") or {}
    gpu_pct = gpu.get("gpuUtilizationPercent")
    vram_used = gpu.get("usedVramBytes")
    vram_total = gpu.get("totalVramBytes")

    if json_mode:
        obj: dict = {
            "type": "progress",
            "elapsed_s": elapsed,
            "embed_pct": round(embed_pct, 1),
            "splade_pct": round(splade_pct, 1),
            "ner_done": ner_done,
            "ner_total": ner_total,
            "chunk_pct": round(chunk_pct, 1),
            "heap_used_gb": heap_used_gb,
            "heap_max_gb": heap_max_gb,
            "throughput": round(throughput, 1),
            "blocking": reasons[:3] if reasons else [],
        }
        if gpu_pct is not None:
            obj["gpu_pct"] = gpu_pct
        if vram_used is not None and vram_total is not None:
            obj["vram_used_gb"] = round(vram_used / (1024 ** 3), 1)
            obj["vram_total_gb"] = round(vram_total / (1024 ** 3), 1)
        print(json_mod.dumps(obj), file=sys.stderr)
        return

    blocking = ", ".join(reasons[:3]) if reasons else "stable"

    gpu_str = ""
    if gpu_pct is not None and vram_used is not None and vram_total is not None:
        gpu_str = f" | GPU {gpu_pct}% VRAM {vram_used / (1024**3):.1f}/{vram_total / (1024**3):.1f}GB"

    log.info(
        "[%ds] e=%.0f%% s=%.0f%% n=%d/%d c=%.0f%% | "
        "heap=%.1f/%.1fGB | throughput=%.0fd/s%s | %s",
        elapsed, embed_pct, splade_pct, ner_done, ner_total, chunk_pct,
        heap_used_gb, heap_max_gb, throughput, gpu_str, blocking,
    )


def _check_stage_completions(
    snapshot: dict, elapsed: float, logged: dict[str, bool], json_mode: bool,
) -> None:
    """Emit one-time log when each enrichment stage first reaches 100%."""
    checks = [
        ("embed", "embeddingCoveragePercent", 99.9, "Embedding"),
        ("splade", "spladeCoveragePercent", 99.9, "SPLADE"),
        ("chunk", "chunkVectorCoveragePercent", 99.9, "Chunk vectors"),
        ("chunk_splade", "chunkSpladeCoveragePercent", 99.9, "Chunk SPLADE"),
    ]
    for key, field, threshold, label in checks:
        if not logged[key] and snapshot.get(field, 0) >= threshold:
            logged[key] = True
            if json_mode:
                print(json_mod.dumps({
                    "type": "stage_complete", "stage": key, "elapsed_s": int(elapsed),
                }), file=sys.stderr)
            else:
                log.info("%s 100%% at t=%ds", label, int(elapsed))

    if not logged["ner"]:
        ner_done = snapshot.get("completedNerCount", 0)
        ner_pending = snapshot.get("pendingNerCount", 0)
        if ner_done > 0 and ner_pending == 0:
            logged["ner"] = True
            if json_mode:
                print(json_mod.dumps({
                    "type": "stage_complete", "stage": "ner",
                    "elapsed_s": int(elapsed), "ner_total": ner_done,
                }), file=sys.stderr)
            else:
                log.info("NER complete at t=%ds (%d docs)", int(elapsed), ner_done)


# ---------------------------------------------------------------------------
# Check conditions
# ---------------------------------------------------------------------------

def _check_search_conditions(
    s: dict,
    dense: bool,
    splade: bool,
    lambdamart: bool,
    base_url: str,
) -> list[str]:
    reasons: list[str] = []

    if s.get("indexState") != "IDLE":
        reasons.append("index_not_idle")

    if s.get("pendingJobs", 0) != 0 or s.get("pendingJobsCount", 0) != 0 or \
       s.get("processingJobsCount", 0) != 0:
        reasons.append("index_queue_not_quiescent")

    if dense:
        if not s.get("chunkVectorsReady"):
            reasons.append("dense_requested_but_chunk_vectors_not_ready")
        compat = s.get("embeddingCompatState", "")
        if compat not in _VALID_EMBEDDING_COMPAT:
            reasons.append(f"dense_requested_but_embedding_compat_blocked({compat})")
        # [-1a] Check doc-level embedding coverage, matching SPLADE pattern.
        if s.get("embeddingCoveragePercent", 0) < 99.9:
            reasons.append("dense_requested_but_embedding_coverage_low")

    if splade:
        if s.get("spladeDocCount", 0) <= 0 or s.get("spladePendingCount", 0) > 0 or \
           s.get("spladeFailedCount", 0) > 0 or s.get("spladeCoveragePercent", 0) < 99.9:
            reasons.append("splade_requested_but_splade_features_not_ready")

    # Chunk SPLADE: a backend-published flag, checked regardless of the caller's
    # `splade`/`dense` request — parallel to the pipeline-completion gate in
    # _check_pipeline_complete_conditions (tempdoc 931 §E item 9). Absent/false
    # (older backend) adds nothing.
    if s.get("chunkSpladeEnabled", False):
        chunk_splade_doc_count = s.get("chunkDocCount", 0)
        if chunk_splade_doc_count > 0 and s.get("chunkSpladeCoveragePercent", 0) < 99.9:
            reasons.append("chunk_splade_not_complete")

    if lambdamart:
        if not _check_lambdamart_active(base_url):
            reasons.append("lambdamart_requested_but_not_active")

    return reasons


def _check_index_idle_conditions(s: dict, expected_doc_count_min: int) -> list[str]:
    reasons: list[str] = []

    if s.get("pendingReadyJobsCount", 0) != 0 or \
       s.get("pendingBackoffJobsCount", 0) != 0:
        reasons.append("index_queue_not_quiescent")

    if s.get("buildingIndexedDocuments", 0) != 0:
        reasons.append("index_still_building")

    if expected_doc_count_min >= 0:
        count = s.get("indexedDocuments", 0)
        if count < expected_doc_count_min:
            reasons.append(
                f"indexed_doc_count_below_floor({count}/{expected_doc_count_min})"
            )

    return reasons


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _fetch_status(client: httpx.Client) -> dict:
    """Fetch /api/status and flatten nested worker fields."""
    resp = client.get("/api/status")
    resp.raise_for_status()
    return flatten_status(resp.json())


def flatten_status(data: dict) -> dict:
    """Flatten nested worker sub-records to the top level.

    Since change 384, WorkerOperationalView is nested under the "worker" key
    (was @JsonUnwrapped for flat compat). This merges every direct child of
    ``worker`` to the top level so existing ``.get()`` calls work unchanged.

    Data-driven (tempdoc 644): it iterates ``worker``'s children rather than a
    hand-maintained allow-list, so a newly-added sub-record (e.g. the formerly
    omitted ``visualExtraction``) is surfaced automatically — the same omission
    class that once hid ``gpu`` (and with it the per-encoder ``*OrtCuda``
    diagnostics the 644 capability guard reads) cannot recur. The merge is
    **one level only**: dict-valued *leaves* like ``embedOrtCuda`` are copied
    through as nested dicts (preflight reads them as dicts), never recursed.
    A sibling-collision assertion converts a future wire-contract change that
    makes two sub-records claim the same top-level key from silent
    first-writer-wins data loss into a loud failure.
    """
    worker = data.get("worker")
    if not isinstance(worker, dict):
        return data

    contributed: dict = {}  # child-key -> the sub-record that first contributed it
    for sub_key, sub in worker.items():
        if not isinstance(sub, dict):
            # Direct worker field (e.g. buildStamp) — copy the leaf.
            if sub_key not in data:
                data[sub_key] = sub
            continue
        # Sub-record — copy its children one level (dict-valued leaves stay dicts).
        for k, v in sub.items():
            if k in contributed and contributed[k] != sub_key:
                raise ValueError(
                    f"flatten_status: worker sub-records '{contributed[k]}' and '{sub_key}' both "
                    f"define top-level key '{k}'; a flatten would silently drop one. Disambiguate "
                    f"the wire contract (rename one) before flattening."
                )
            if k not in data:
                data[k] = v
                contributed[k] = sub_key
    # enrichment.chunk is one level deeper
    chunk = worker.get("enrichment", {}).get("chunk")
    if isinstance(chunk, dict):
        for k, v in chunk.items():
            if k not in data:
                data[k] = v

    return data


def _check_lambdamart_active(base_url: str) -> bool:
    try:
        with httpx.Client(base_url=base_url, timeout=10) as client:
            resp = client.get("/api/debug/state")
            resp.raise_for_status()
            data = resp.json()
            return bool(
                data.get("reranking", {}).get("lambdamart", {}).get("active")
            )
    except Exception:
        return False
