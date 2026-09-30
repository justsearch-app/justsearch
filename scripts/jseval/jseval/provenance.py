"""Extract per-hit and per-run evidence from raw search responses."""

from __future__ import annotations

from collections import Counter

_COMPONENTS = (
    "splade", "dense", "hybrid", "chunk_merge", "branch_fusion",
    "lambdamart", "cross_encoder", "expansion", "query_classification",
)

# Tempdoc 549 Phase D: jseval component name -> unified-trace stage wireId. Names that already
# match (chunk_merge->chunk-merge etc. differ only by separator) are mapped explicitly.
_COMPONENT_TO_STAGE = {
    "splade": "splade-retrieval",
    "dense": "dense-retrieval",
    "hybrid": "fusion",
    "chunk_merge": "chunk-merge",
    "branch_fusion": "branch-fusion",
    "lambdamart": "lambdamart",
    "cross_encoder": "cross-encoder",
    "expansion": "expansion",
    "query_classification": "query-understanding",
}


def extract_hit_evidence(hit: dict, splade_executed: bool = False) -> dict:
    """Extract per-hit evidence from a single search hit.

    Tempdoc 549 Phase E1/E2: both the stringly-typed ``debugScores`` map and the leg-keyed
    ``provenance`` are retired. Per-hit evidence is read solely from the unified ``hit.trace``
    slice (the per-doc slice of the stage vocabulary — covers the BM25 sparse-only shortcut via
    its always-on structural sparse stage).
    """
    trace = hit.get("trace") or []
    if trace:
        return _extract_from_trace(trace, splade_executed)
    return _empty_hit_evidence()


def extract_query_evidence(response: dict) -> dict:
    """Extract per-query evidence from a raw search response dict.

    Tempdoc 549 Phase E4: query-level signals are read SOLELY from the unified ``searchTrace``
    (the single canonical source) — the legacy ``introspection`` aggregate, ``pipelineExecution``,
    and flat degradation fields were retired. The trace carries effectiveMode/decisionKind/qpp/
    degradation as trace-level fields plus the per-stage nodes.
    """
    trace = response.get("searchTrace") or {}
    tdegr = trace.get("degradation") or {}
    tstages = {s.get("id"): s for s in (trace.get("stages") or [])}
    chunk_stage = tstages.get("chunk-merge") or {}

    splade_executed = bool(tdegr.get("spladeExecuted"))

    hits = response.get("results") or []
    hit_evidences = [extract_hit_evidence(h, splade_executed) for h in hits]

    # Component statuses from the unified trace's stages (the single source).
    components: dict[str, dict[str, str | None]] = {}
    for comp in _COMPONENTS:
        stage = tstages.get(_COMPONENT_TO_STAGE.get(comp, comp))
        if stage:
            components[comp] = {"status": stage.get("status"), "reason": stage.get("reason")}

    return {
        "effective_mode": trace.get("effectiveMode"),
        "vector_blocked": bool(tdegr.get("vectorBlocked")),
        "vector_blocked_reason": tdegr.get("vectorBlockedReason"),
        "hybrid_fallback": bool(tdegr.get("hybridFallback")),
        "hybrid_fallback_reason": tdegr.get("hybridFallbackReason"),
        "chunk_merge_applied": chunk_stage.get("status") == "executed",
        "chunk_merge_reason": chunk_stage.get("reason"),
        "took_ms": response.get("tookMs"),
        "total_hits": response.get("totalHits"),
        "components": components,
        "hit_evidences": hit_evidences,
        "has_vector_evidence": any(
            _has_any_evidence(he) for he in hit_evidences
        ),
        "has_sparse_evidence": any(
            _has_sparse_evidence(he) for he in hit_evidences
        ),
        "has_dense_evidence": any(
            _has_dense_evidence(he) for he in hit_evidences
        ),
        "error": response.get("error"),
        "identity_resolution_errors": response.get("identity_resolution_errors", 0),
        "stage_timing": _extract_stage_timing(response),
    }


# Tempdoc 549 Phase E3: stage timing now comes from the unified trace's per-stage `ms`
# (pipelineExecution retired). FUSION.ms carries the retrieval-phase elapsed (Phase D0).
_STAGE_TIMING_MAP = {
    "fusion": "retrieval_ms",
    "chunk-merge": "chunk_merge_ms",
    "cross-encoder": "cross_encoder_ms",
    "lambdamart": "lambdamart_ms",
    "branch-fusion": "branch_fusion_ms",
}
_STAGE_TIMING_PY_KEYS = tuple(_STAGE_TIMING_MAP.values())


def _extract_stage_timing(response: dict) -> dict:
    """Extract pipeline stage timing from the unified trace's per-stage ms."""
    trace = response.get("searchTrace") or {}
    by_id = {s.get("id"): s for s in (trace.get("stages") or [])}
    return {
        py_key: (by_id.get(wire_id) or {}).get("ms")
        for wire_id, py_key in _STAGE_TIMING_MAP.items()
    }


def aggregate_run_evidence(query_evidences: list[dict]) -> dict:
    """Aggregate per-query evidence into per-run evidence rates."""
    total = len(query_evidences)
    if total == 0:
        return {
            "query_count": 0,
            "vector_evidence_available_rate": 0.0,
            "sparse_evidence_available_rate": 0.0,
            "dense_vector_evidence_available_rate": 0.0,
            "effective_mode_counts": {},
            "hybrid_fallback_reason_counts": {},
            "component_status_counts": {},
            "error_count": 0,
            "zero_hit_query_count": 0,
            "zero_hit_query_rate": 0.0,
            "chunk_merge_applied_count": 0,
            "chunk_merge_applied_rate": 0.0,
            "chunk_merge_skip_reason_counts": {},
            "identity_resolution_error_count": 0,
        }

    mode_counter: Counter = Counter()
    fallback_counter: Counter = Counter()
    component_counters: dict[str, Counter] = {}
    chunk_merge_skip_reason_counter: Counter = Counter()
    vector_count = 0
    sparse_count = 0
    dense_count = 0
    error_count = 0
    zero_hit_count = 0
    chunk_merge_count = 0
    id_resolution_errors = 0

    for qe in query_evidences:
        if qe.get("has_vector_evidence"):
            vector_count += 1
        if qe.get("has_sparse_evidence"):
            sparse_count += 1
        if qe.get("has_dense_evidence"):
            dense_count += 1
        if qe.get("error"):
            error_count += 1
        if (qe.get("total_hits") or 0) == 0:
            zero_hit_count += 1
        if qe.get("chunk_merge_applied"):
            chunk_merge_count += 1
        else:
            # tempdoc 715 defect 1: the wire "reason" on a skipped chunk-merge stage is the
            # SearchReasonCode name (SearchTraceProjector.chunkMergeStage) -- e.g.
            # SKIPPED_SHORT_CORPUS / SKIPPED_NO_CHUNK_DOCS for an engine-declared corpus-shape
            # skip (SearchPlanner.java:267/265), vs. SKIPPED_VECTOR_BLOCKED for a real failure.
            # Aggregating these lets a caller (run._compute_chunk_completeness) tell a
            # legitimate skip apart from a degeneracy without re-deriving it per query.
            skip_reason = qe.get("chunk_merge_reason")
            if skip_reason:
                chunk_merge_skip_reason_counter[skip_reason] += 1
        id_resolution_errors += qe.get("identity_resolution_errors", 0)

        em = qe.get("effective_mode")
        if em:
            mode_counter[em] += 1

        fb_reason = qe.get("hybrid_fallback_reason")
        if fb_reason:
            fallback_counter[fb_reason] += 1

        for comp, comp_data in qe.get("components", {}).items():
            if comp not in component_counters:
                component_counters[comp] = Counter()
            status = comp_data.get("status")
            if status:
                component_counters[comp][status] += 1

    # Aggregate pipeline stage timing
    stage_timing_stats = _aggregate_stage_timing(query_evidences)

    return {
        "query_count": total,
        "vector_evidence_available_rate": vector_count / total,
        "sparse_evidence_available_rate": sparse_count / total,
        "dense_vector_evidence_available_rate": dense_count / total,
        "effective_mode_counts": dict(mode_counter),
        "hybrid_fallback_reason_counts": dict(fallback_counter),
        "component_status_counts": {k: dict(v) for k, v in component_counters.items()},
        "error_count": error_count,
        "zero_hit_query_count": zero_hit_count,
        "zero_hit_query_rate": zero_hit_count / total,
        "chunk_merge_applied_count": chunk_merge_count,
        "chunk_merge_applied_rate": chunk_merge_count / total,
        "chunk_merge_skip_reason_counts": dict(chunk_merge_skip_reason_counter),
        "identity_resolution_error_count": id_resolution_errors,
        "stage_timing_stats": stage_timing_stats,
    }


def _aggregate_stage_timing(query_evidences: list[dict]) -> dict:
    """Aggregate per-query stage timing into per-stage p50/mean/p95, and CLOSE the
    decomposition with an ``unaccounted_ms`` remainder + a per-entry ``share`` (tempdoc 647).

    ``unaccounted_ms`` = per-query ``took_ms − Σ present stage ms`` — Critical Path Tracing's
    "unaccounted" node (serialization / port dispatch / application glue not captured as a named
    stage). ``took_ms`` is the head-side end-to-end latency that encloses every stage
    (``KnowledgeSearchEngine.totalSearchMs``), so ``Σ stages ≤ took_ms`` by construction; tiny
    negatives from sub-millisecond rounding are clamped to 0 and counted (``clamped_negative_count``).
    ``share`` is each entry's fraction of total latency, computed from SUMMED totals (an absent stage
    counts 0) so the parts close to 1 by construction. The remainder + shares are REPORT-ONLY
    drill-down — never gated (a share is a zero-sum simplex; only absolute per-stage floors gate).
    Backward-compatible: when no query carries ``took_ms`` (e.g. legacy/unit inputs), neither
    ``unaccounted_ms`` nor ``share`` is emitted.
    """
    stage_keys = list(_STAGE_TIMING_PY_KEYS)
    collected: dict[str, list[float]] = {k: [] for k in stage_keys}
    unaccounted: list[float] = []
    clamped_negative = 0
    total_took = 0.0
    stage_totals: dict[str, float] = {k: 0.0 for k in stage_keys}
    unaccounted_total = 0.0

    for qe in query_evidences:
        st = qe.get("stage_timing") or {}
        present_sum = 0.0
        for key in stage_keys:
            val = st.get(key)
            if val is not None:
                collected[key].append(val)
                stage_totals[key] += val
                present_sum += val
        took = qe.get("took_ms")
        if isinstance(took, (int, float)):
            rem = took - present_sum
            if rem < 0:
                clamped_negative += 1
                rem = 0.0
            unaccounted.append(rem)
            total_took += took
            unaccounted_total += rem

    def _stats(values: list[float]) -> dict:
        values = sorted(values)
        n = len(values)
        return {
            "mean": round(sum(values) / n, 1),
            "p50": values[n // 2],
            "p95": values[min(int(n * 0.95 + 0.5), n - 1)],
        }

    result: dict[str, dict] = {}
    for key, values in collected.items():
        if values:
            result[key] = _stats(values)

    # Tempdoc 647: close the decomposition — the unaccounted remainder ...
    if unaccounted:
        entry = _stats(unaccounted)
        if clamped_negative:
            entry["clamped_negative_count"] = clamped_negative
        result["unaccounted_ms"] = entry

    # ... and a per-entry share of total latency (summed totals → closes to 1).
    if total_took > 0:
        for key in stage_keys:
            if key in result:
                result[key]["share"] = round(stage_totals[key] / total_took, 4)
        if "unaccounted_ms" in result:
            result["unaccounted_ms"]["share"] = round(unaccounted_total / total_took, 4)

    return result


# ---------------------------------------------------------------------------
# Provenance extraction (structured)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Unified-trace extraction (per-hit slice; tempdoc 549 — the sole per-hit evidence source)
# ---------------------------------------------------------------------------

def _extract_from_trace(trace: list, splade_executed: bool) -> dict:
    """Per-hit evidence from the unified ``hit.trace`` (list of HitStage {id, rank, score, detail}).

    Structural rank/score come from each stage directly; the chunk sub-ranks/scores and the fusion
    method live in the numeric ``detail`` map (present only when include_detail was requested).
    """
    by_id = {s.get("id"): s for s in trace if isinstance(s, dict)}
    sparse = by_id.get("sparse-retrieval") or {}
    splade = by_id.get("splade-retrieval") or {}
    dense = by_id.get("dense-retrieval") or {}
    fusion = by_id.get("fusion") or {}
    chunk_detail = (by_id.get("chunk-merge") or {}).get("detail") or {}
    fusion_detail = fusion.get("detail") or {}
    ce = by_id.get("cross-encoder") or {}

    sparse_source = splade if splade_executed else sparse
    fusion_method = "rrf" if "rrf" in fusion_detail else ("cc" if "cc" in fusion_detail else None)

    return {
        "sparse_rank": _to_int(sparse_source.get("rank")),
        "sparse_score": sparse_source.get("score"),
        "dense_rank": _to_int(dense.get("rank")),
        "dense_score": dense.get("score"),
        "chunk_sparse_rank": _to_int(chunk_detail.get("chunk_sparse_rank")),
        "chunk_sparse_score": chunk_detail.get("chunk_sparse"),
        "chunk_dense_rank": _to_int(chunk_detail.get("chunk_vector_rank")),
        "chunk_dense_score": chunk_detail.get("chunk_vector"),
        "fusion_score": fusion.get("score"),
        "fusion_method": fusion_method,
        "ce_score": ce.get("score"),
    }


def extract_judge_signals(hit: dict) -> dict:
    """Per-hit judge-arbitration signals (tempdoc 643): explicit per-leg rank/score.

    Unlike :func:`extract_hit_evidence`, BM25 and SPLADE are kept **separate** rather than
    collapsed via ``splade_executed`` — tempdoc 643's leg-agreement signal (do the legs agree on
    this hit?) needs both ranks distinctly when SPLADE runs alongside BM25 in the same hybrid
    query. Sibling reader of the same ``hit.trace`` slice; does not replace
    :func:`extract_hit_evidence`, which other consumers (component-status aggregation) still use.

    ``fusion_score`` prefers ``branch-fusion`` over ``fusion`` when both are present (tempdoc 643
    critical-analysis-pass correction, 2026-07-01): ``fusion`` alone is stale (pre-branch-merge)
    whenever chunk-branch fusion ran — the true final score there is on the ``branch-fusion``
    stage. Checked by value (``is not None``), not key-presence, so a ``branch-fusion`` stage that
    is present but score-less (`HitProvenanceProjector.attachBranchFusion` with no fused score)
    correctly falls through to ``fusion`` rather than reporting ``None``.
    """
    trace = hit.get("trace") or []
    by_id = {s.get("id"): s for s in trace if isinstance(s, dict)}
    sparse = by_id.get("sparse-retrieval") or {}
    splade = by_id.get("splade-retrieval") or {}
    dense = by_id.get("dense-retrieval") or {}
    branch_fusion = by_id.get("branch-fusion") or {}
    fusion = by_id.get("fusion") or {}
    ce = by_id.get("cross-encoder") or {}
    fusion_score = branch_fusion.get("score")
    if fusion_score is None:
        fusion_score = fusion.get("score")
    return {
        "bm25_rank": _to_int(sparse.get("rank")),
        "bm25_score": sparse.get("score"),
        "splade_rank": _to_int(splade.get("rank")),
        "splade_score": splade.get("score"),
        "dense_rank": _to_int(dense.get("rank")),
        "dense_score": dense.get("score"),
        "fusion_score": fusion_score,
        "ce_score": ce.get("score"),
    }


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _empty_hit_evidence() -> dict:
    return {
        "sparse_rank": None, "sparse_score": None,
        "dense_rank": None, "dense_score": None,
        "chunk_sparse_rank": None, "chunk_sparse_score": None,
        "chunk_dense_rank": None, "chunk_dense_score": None,
        "fusion_score": None, "fusion_method": None,
        "ce_score": None,
    }


def _has_any_evidence(he: dict) -> bool:
    return any(
        v is not None and v != 0
        for k, v in he.items()
        if k != "fusion_method"
    )


def _has_sparse_evidence(he: dict) -> bool:
    return any(
        (he.get(k) or 0) > 0
        for k in ("sparse_rank", "sparse_score", "chunk_sparse_rank", "chunk_sparse_score")
    )


def _has_dense_evidence(he: dict) -> bool:
    return any(
        (he.get(k) or 0) > 0
        for k in ("dense_rank", "dense_score", "chunk_dense_rank", "chunk_dense_score")
    )


def _to_int(val) -> int | None:
    if val is None:
        return None
    try:
        return int(val)
    except (ValueError, TypeError):
        return None
