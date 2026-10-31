"""Single evaluation run orchestration."""

from __future__ import annotations

import logging
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import httpx

from . import ann_proof as ann_proof_mod
from . import artifacts as artifacts_mod
from . import ce_coverage as ce_coverage_mod
from . import chunk_completeness as chunk_completeness_mod
from . import comparability as comparability_mod
from . import corpora
from . import history as history_mod
from . import manifest as manifest_mod
from . import provenance
from . import readiness
from . import retriever
from . import scoring
from .types import ReadinessResult

log = logging.getLogger(__name__)

METRIC_CONTRACT = {
    "gain_function": "linear",
    "aggregation": "mean_of_per_query",
    "unjudged_policy": "not_relevant",
}

EXPECTED_COMPONENTS: dict[str, set[str]] = {
    "lexical": set(),                    # BM25 is implicit, not a tracked component
    "hybrid": {"dense"},                 # sparse is implicit; only dense is tracked
    "vector": {"dense"},
    "splade": {"splade"},
    "bm25_splade": {"splade"},           # sparse is implicit
    "dense_splade": {"dense", "splade"},
    "full": {"dense", "splade"},         # sparse is implicit
}

# tempdoc 715 defect 1: SearchReasonCode names (modules/worker-services/.../SearchReasonCode.java)
# that mark an engine-declared, corpus-shape chunk-merge skip -- SearchPlanner.java:265/267 emits
# these when CorpusCapabilities.corpusSupportsChunks() is false (short corpus by median token
# count) or the corpus has no chunk docs at all. Legitimate, not a degeneracy -- PROVIDED the
# offline corpus shape actually agrees it's plausibly short (see
# _CHUNK_MERGE_SHORT_CORPUS_RATE_MAX below; tempdoc 717/718 review).
_CHUNK_MERGE_CORPUS_SHAPE_SKIP_REASONS = {"SKIPPED_SHORT_CORPUS", "SKIPPED_NO_CHUNK_DOCS"}

# tempdoc 717/718 review: the corpus-shape waiver above must not be unconditional. A 717-class
# regression (a healthy, chunked index whose chunk_merge leg is nonetheless skipped
# SKIPPED_SHORT_CORPUS at query time due to the same corpus-shape misclassification 717 fixed)
# produces EXACTLY the signature the waiver was built to excuse: chunks present + healthy,
# leg skipped with a corpus-shape reason. Gate the waiver on the OFFLINE expectation agreeing
# the corpus is plausibly short, mirroring the engine's own short-corpus rule
# (`CorpusProfile.chunkRate() < 0.05`,
# modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CorpusProfile.java)
# rather than an unscaled absolute `expected` count. Two anchor cases: miracl-de-2k
# false-positive (~19 expected of 3103 docs, rate ~0.006 -- must stay WAIVED) and the
# legal-clerc-200 regression shape (~194 expected of 198 docs, rate ~0.98 -- must STRIKE).
_CHUNK_MERGE_SHORT_CORPUS_RATE_MAX = 0.05


def _snapshot_models(base_url: str) -> dict | None:
    """Fetch model identity from /api/status at run start (335 item 10)."""
    try:
        with httpx.Client(base_url=base_url, timeout=5) as client:
            status = client.get("/api/status").json()
        # 384: GPU diagnostics and compatibility are now nested under worker
        w = status.get("worker", {})
        gpu_diag = w.get("gpu", {})
        compat = w.get("compatibility", {})
        embed_cuda = gpu_diag.get("embedOrtCuda") or {}
        splade_cuda = gpu_diag.get("spladeOrtCuda") or {}
        reranker_cuda = gpu_diag.get("rerankerOrtCuda") or {}
        # tempdoc 644: make the realized engine SET first-class in cohort identity so a
        # silently-degraded run (e.g. cross-encoder off in a worktree) forms a distinct cohort
        # and cannot be averaged with a CE-on baseline. `realized_engines` (presence) is
        # startup-stable identity; `reranker_gpu` (device) is recorded but stripped from the
        # comparison key (release._MODEL_EXECUTION_FLAGS), exactly like embed_gpu/splade_gpu.
        from .preflight import realized_engine_set
        return {
            "embed_backend": gpu_diag.get("embedBackend"),
            "embed_fingerprint": compat.get("embeddingFingerprintCurrent"),
            "embed_compat_state": compat.get("embeddingCompatState"),
            "embed_gpu": embed_cuda.get("available"),
            "splade_model_path": gpu_diag.get("spladeModelPath"),
            "splade_gpu": splade_cuda.get("available"),
            "reranker_model_path": gpu_diag.get("rerankerModelPath"),
            "reranker_gpu": reranker_cuda.get("available"),
            "ner_model_path": gpu_diag.get("nerModelPath"),
            "ner_gpu": gpu_diag.get("nerGpuEnabled"),
            "realized_engines": realized_engine_set(gpu_diag),
        }
    except Exception:
        log.debug("Failed to snapshot model identity from /api/status")
        return None


def _worker_data_dir() -> Path:
    """The Worker-owned data dir (its ``telemetry/`` NDJSON lives under here).

    Default to the eval-mode backend dir so a defaults-only ``run --start-backend``
    still finds its telemetry (readers skip missing files, so a foreign/dev data dir
    is harmless).
    """
    from ._paths import DEFAULT_BACKEND_DATA_DIR

    override = os.environ.get("JUSTSEARCH_DATA_DIR")
    return Path(override) if override else DEFAULT_BACKEND_DATA_DIR


def _cadence_block(first_search: dict | None) -> dict:
    """Tempdoc 885 item 19: end-of-run NRT/commit cadence counters + probe latencies."""
    from . import cadence as cadence_mod

    return cadence_mod.build_block(
        cadence_mod.collect_worker_metrics(_worker_data_dir()),
        first_search=first_search,
    )


def _encoder_latency_block() -> dict:
    """Tempdoc 930 §18.1 row 7: absolute per-encoder ONNX p50/p95 from the same
    ``<data_dir>/telemetry/`` stream the cadence block reads. Informational — no
    threshold and no baseline (that is the point; see :mod:`jseval.encoder_latency`)."""
    from . import encoder_latency as encoder_latency_mod

    return encoder_latency_mod.build_block(_worker_data_dir())


def _snapshot_search_config(base_url: str) -> dict | None:
    """Fetch active search config from /api/status at run start (343 item 0.4).

    Captures the search pipeline config that produced this run so it can be
    compared across runs (e.g., chunk-ON vs chunk-OFF, balanced vs BM25-dom).
    """
    try:
        with httpx.Client(base_url=base_url, timeout=5) as client:
            status = client.get("/api/status").json()
        sc = status.get("searchConfig", {})
        return {
            "chunk_aware_enabled": sc.get("chunkAwareEnabled"),
            "cc_weight_sparse": sc.get("ccWeightSparse"),
            "cc_weight_dense": sc.get("ccWeightDense"),
            "cc_weight_splade": sc.get("ccWeightSplade"),
            "branch_cc_weight_whole": sc.get("branchCcWeightWhole"),
            "branch_cc_weight_chunk": sc.get("branchCcWeightChunk"),
            "branch_chunk_min_weight_multiplier": sc.get(
                "branchChunkMinWeightMultiplier"
            ),
            "title_boost": sc.get("titleBoost"),
            "entity_boost": sc.get("entityBoost"),
            "query_classification_enabled": sc.get(
                "queryClassificationEnabled"
            ),
        }
    except Exception:
        log.debug("Failed to snapshot search config from /api/status")
        return None


# Full force-merge, not tombstone-only: Lucene's forceMergeDeletes skips segments whose deleted
# fraction is below its 10 % threshold, so an expunge-only settle left 181 tombstones and a
# multi-segment layout on scifact (7756 -> 6920 maxDoc for 6739 live docs, 931 C1 campaign).
# A paired comparison needs both arms at one segment with zero deletions, so request that.
_SETTLE_REQUEST = {"expungeDeletesOnly": False, "maxSegments": 1}


def _settle_index(base_url: str, timeout_sec: float = 900.0) -> dict | None:
    """Force-merge the active index to one tombstone-free segment (tempdoc 931 §E item 10).

    POSTs ``/api/indexing/settle`` and returns the normalized before/after counts, or ``None``
    when the settle did not happen -- an older backend without the route (404), a worker refusal
    (409: migration in flight, upgrade barrier held), or any transport failure. The caller records
    ``settled=False`` in that case rather than failing the run: an unsettled arm still produces
    valid numbers, it just cannot claim equal merge state with its pair.
    """
    try:
        with httpx.Client(base_url=base_url, timeout=timeout_sec) as client:
            resp = client.post("/api/indexing/settle", json=_SETTLE_REQUEST)
    except Exception as exc:
        log.warning("Index settle failed (%s); continuing unsettled", exc)
        return None
    if resp.status_code == 404:
        log.warning(
            "Index settle unavailable: backend has no POST /api/indexing/settle "
            "(pre-931 build); continuing unsettled",
        )
        return None
    if resp.status_code != 202:
        log.warning(
            "Index settle rejected (HTTP %d: %s); continuing unsettled",
            resp.status_code, resp.text[:200],
        )
        return None
    body = resp.json()
    log.info(
        "Index settled: maxDoc %s -> %s, numDocs %s -> %s (%s ms)",
        body.get("maxDocBefore"), body.get("maxDocAfter"),
        body.get("numDocsBefore"), body.get("numDocsAfter"), body.get("elapsedMs"),
    )
    return {
        "max_doc_before": body.get("maxDocBefore"),
        "num_docs_before": body.get("numDocsBefore"),
        "max_doc_after": body.get("maxDocAfter"),
        "num_docs_after": body.get("numDocsAfter"),
        "segments_after": body.get("segmentsAfter"),
        "elapsed_ms": body.get("elapsedMs"),
    }


def _build_index_state_at_query(
    snapshot: dict, readiness_passed_at: str, settle: dict | None = None,
) -> dict:
    """Merge/enrichment state at the moment the query phase starts (tempdoc 931 §E item 10).

    Two fresh indexes of the same corpus can carry very different tombstone (deleted-doc)
    counts -- a measured case moved 2,629 vs 222 -- which inflates BM25 collection statistics
    on one arm and moves hit counts 3-4% with no code cause. Recording this block at query-phase
    start lets a paired-arm comparison (see compare_runs.py) flag that divergence instead of
    silently attributing it to the code under test. Every field degrades to ``None`` when the
    backend snapshot doesn't publish it -- an older backend, or a ``skip_readiness`` run whose
    ``snapshot`` is the ``ReadinessResult`` default empty dict.

    ``settle`` is the result of :func:`_settle_index` when ``--settle-index`` was requested AND the
    settle succeeded. The derived ``settled`` flag says whether this arm's merge state was actually
    equalized, so a paired comparison can tell "equal by construction" from "equal by accident".
    """
    max_doc = snapshot.get("indexMaxDoc")
    num_docs = snapshot.get("indexNumDocs")
    if isinstance(max_doc, (int, float)) and max_doc < 0:
        max_doc = None
    if isinstance(num_docs, (int, float)) and num_docs < 0:
        num_docs = None
    deleted_docs = (
        max_doc - num_docs
        if isinstance(max_doc, (int, float)) and isinstance(num_docs, (int, float))
        else None
    )
    block = {
        "max_doc": max_doc,
        "num_docs": num_docs,
        "deleted_docs": deleted_docs,
        "chunk_splade_coverage_percent": snapshot.get("chunkSpladeCoveragePercent"),
        "splade_coverage_percent": snapshot.get("spladeCoveragePercent"),
        "chunk_vector_coverage_percent": snapshot.get("chunkVectorCoveragePercent"),
        "settled": settle is not None,
        "readiness_passed_at": readiness_passed_at,
    }
    if settle is not None:
        block["settle"] = settle
    return block


def execute_run(
    dataset_name: str,
    base_url: str,
    modes: list[str],
    *,
    base_dir: Path | None = None,
    top_k: int = 10,
    max_queries: int = 0,
    embedding_enabled: bool = False,
    splade_enabled: bool = False,
    lambdamart_enabled: bool = False,
    cross_encoder_enabled: bool = False,
    skip_readiness: bool = False,
    allow_errors: bool = False,
    output_dir: Path | None = None,
    context_coverage: bool = False,
    coverage_thresholds: list[float] | None = None,
    ingest_summary: dict | None = None,
    history_db: Path | None = None,
    pipeline_summary: dict | None = None,
    env_overrides: dict[str, str] | None = None,
    index_cache: dict | None = None,
    query_syntax: str | None = None,
    search_load: dict | None = None,
    first_search_probe: dict | None = None,
    settle_index: bool = False,
    raw_context=None,
    duplicate_prevalence_request=None,
) -> dict:
    """Execute a full evaluation run.

    Returns a summary dict with aggregate metrics, comparability, and timing.

    ``index_cache`` (tempdoc 751 WP3) is the adopt-side cache outcome from
    BackendInfo; when present it is projected into ``summary["index_cache"]`` as a
    top-level provenance block. It never enters the manifest dict (manifest_hash
    stability).

    ``query_syntax`` (Q-020 / F-046) is ``"simple"``, ``"lucene"``, or ``None``. ``None`` (the
    default) sends no ``querySyntax`` field on the wire — identical to every pre-Q-020 run — and
    is still recorded in ``summary["query_syntax"]`` as ``"simple"`` (the Head's documented
    server-side default, `docs/reference/api-contract-map.md`) so every run is self-documenting.

    ``settle_index`` (tempdoc 931 §E item 10) purges deleted-but-unmerged documents from the active
    index after the readiness gate and before the query phase, so two arms of a paired comparison
    query indexes with equal merge state. Off by default: it holds the writer for the duration of a
    force-merge, which a routine run should not pay for.
    """
    from .raw_corpus_manifest import (
        resolve_raw_corpus_context,
        validate_raw_corpus_context,
    )

    if raw_context is None:
        raw_context = resolve_raw_corpus_context(
            dataset_name, base_dir=base_dir, env_overrides=env_overrides,
        )
    if raw_context is not None:
        validate_raw_corpus_context(
            raw_context, env_overrides, expected_dataset=dataset_name,
        )
    raw_identity = raw_context.to_corpus_identity() if raw_context else None
    if duplicate_prevalence_request is not None:
        if raw_context is None:
            raise ValueError(
                "duplicate-prevalence decoration requires a resolved raw corpus"
            )
        if not modes:
            raise ValueError(
                "duplicate-prevalence decoration requires at least one query mode"
            )
        if output_dir is None:
            raise ValueError(
                "duplicate-prevalence decoration requires persisted run artifacts"
            )
        if index_cache:
            raise ValueError(
                "duplicate-prevalence decoration requires a fresh, uncached index"
            )
        if (env_overrides or {}).get("JUSTSEARCH_CORPUS_SIGNATURE") or os.environ.get(
            "JUSTSEARCH_CORPUS_SIGNATURE"
        ):
            raise ValueError(
                "duplicate-prevalence decoration forbids JUSTSEARCH_CORPUS_SIGNATURE"
            )
        requested_skips = {
            name.strip()
            for name in os.environ.get("JUSTSEARCH_SKIP_PROJECTIONS", "").split(",")
            if name.strip()
        }
        if "staged_recall_accounting" in requested_skips:
            raise ValueError(
                "duplicate-prevalence decoration requires staged_recall_accounting"
            )

    # E-J-N11: capture environment fingerprint once per run. Informational only
    # (never used as a comparability gate). Safe to run early — best-effort with
    # graceful degradation.
    env_fingerprint = _capture_env_fingerprint()

    # [335 item 20] Skip dataset loading for ingest-only runs (no modes = no queries).
    if not modes:
        models_snapshot = _snapshot_models(base_url)
        summary: dict = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "git_sha": _get_git_sha(),
            "dataset": dataset_name,
            "modes": [],
            "per_mode": {},
            "query_count": 0,
        }
        if raw_context is not None:
            from types import SimpleNamespace

            summary["doc_count"] = raw_context.identity.file_count
            summary["corpus_identity"] = raw_identity
            state_snapshots = manifest_mod.capture_state_snapshots(base_url)
            summary["manifest"] = manifest_mod.compute_manifest(
                dataset_name=dataset_name,
                meta=SimpleNamespace(doc_count=raw_context.identity.file_count, query_count=0),
                env_fingerprint=env_fingerprint,
                models_snapshot=models_snapshot,
                eval_protocol=METRIC_CONTRACT,
                state_snapshots=state_snapshots,
                corpus_identity=raw_identity,
            )
        if ingest_summary:
            summary["ingest"] = ingest_summary
        if pipeline_summary:
            summary["pipeline_timing"] = pipeline_summary
        if models_snapshot:
            summary["models"] = models_snapshot
        search_config = _snapshot_search_config(base_url)
        if search_config:
            summary["search_config"] = search_config
        if env_overrides:
            summary["env_overrides"] = env_overrides
        if env_fingerprint:
            summary["env_fingerprint"] = env_fingerprint
        cache_block = _index_cache_block(index_cache)
        if cache_block:
            summary["index_cache"] = cache_block
        if search_load:
            summary["search_load"] = search_load
        summary["cadence"] = _cadence_block(first_search_probe)
        summary["encoder_latency"] = _encoder_latency_block()
        return summary

    # 1. Load dataset
    query_records, qrels, meta = corpora.load(dataset_name, base_dir)
    if raw_context is not None:
        meta.doc_count = raw_context.identity.file_count

    # Filter to qrels-only queries (don't waste API calls on unjudged queries)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}

    # Cap query count for fast iteration
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])

    # Extract plain text dict for retriever (which expects dict[str, str])
    query_texts = {qid: qr.text for qid, qr in query_records.items()}

    log.info(
        "Loaded dataset %s: %d queries (of %d total), %d docs",
        meta.name, len(query_records), meta.query_count, meta.doc_count,
    )

    # [335 item 10] Snapshot model identity at run start.
    models_snapshot = _snapshot_models(base_url)

    # 2. Readiness check
    readiness_result = ReadinessResult(passed=True)
    if not skip_readiness:
        readiness_result = readiness.check_search_ready(
            base_url, embedding_enabled, splade_enabled, lambdamart_enabled,
        )
        if not readiness_result.passed:
            log.warning("Readiness failed: %s", readiness_result.failure_reasons)

    # tempdoc 931 §E item 10: equalize merge state BEFORE the state is snapshotted, so the
    # recorded block describes the index the queries actually hit. Re-check readiness once
    # afterwards: the settle commits and reopens the searcher, and the block's coverage
    # percentages come from that snapshot.
    settle_block = None
    if settle_index:
        settle_block = _settle_index(base_url)
        if settle_block is not None and not skip_readiness:
            readiness_result = readiness.check_search_ready(
                base_url, embedding_enabled, splade_enabled, lambdamart_enabled,
            )
            if not readiness_result.passed:
                log.warning(
                    "Readiness failed after settle: %s", readiness_result.failure_reasons,
                )

    # tempdoc 931 §E item 10: snapshot merge/enrichment state right as the query
    # phase starts (see _build_index_state_at_query).
    index_state_at_query = _build_index_state_at_query(
        readiness_result.snapshot, datetime.now(timezone.utc).isoformat(), settle_block,
    )
    content_exact_snapshot = None
    content_exact_identity = None
    persisted_corpus_identity = raw_identity
    persisted_ingest_summary = ingest_summary
    if duplicate_prevalence_request is not None:
        from . import duplicate_prevalence as duplicate_prevalence_mod
        from . import duplicate_prevalence_production as production_mod
        from .result_identity import ContentExactResultIdentityInputs

        try:
            request_root = duplicate_prevalence_request.source.raw_root.resolve(strict=True)
        except OSError as exc:
            raise ValueError(
                "duplicate-prevalence raw root is unavailable"
            ) from exc
        if request_root != raw_context.root:
            raise ValueError(
                "duplicate-prevalence raw root does not match the resolved run corpus"
            )
        if duplicate_prevalence_request.source.base_url != base_url.rstrip("/"):
            raise ValueError(
                "duplicate-prevalence base URL does not match the effective run backend"
            )
        effective_env = {**os.environ, **(env_overrides or {})}
        content_exact_snapshot = production_mod.capture_snapshot(
            duplicate_prevalence_request,
            env=effective_env,
        )
        if content_exact_snapshot.raw_manifest_digest != raw_context.identity.digest:
            raise ValueError(
                "duplicate-prevalence snapshot does not match the resolved raw manifest"
            )
        if dict(content_exact_snapshot.corpus_identity.get("admission_policy") or {}) != dict(
            raw_context.admission_policy
        ):
            raise ValueError(
                "duplicate-prevalence snapshot admission policy does not match the run corpus"
            )
        analysis_artifact = duplicate_prevalence_mod.analyze(
            content_exact_snapshot.observations,
            corpus_identity=content_exact_snapshot.corpus_identity,
            extraction_identity=content_exact_snapshot.extraction_identity,
            config=duplicate_prevalence_request.config,
        )
        content_exact_identity = ContentExactResultIdentityInputs(
            observations=content_exact_snapshot.observations,
            aliases_by_opaque_id=content_exact_snapshot.aliases_by_opaque_id,
            result_alias_commitment_key=(
                content_exact_snapshot.result_alias_commitment_key
            ),
            result_mapping_signing_key=(
                content_exact_snapshot.result_mapping_signing_key
            ),
            corpus_identity=content_exact_snapshot.corpus_identity,
            extraction_identity=content_exact_snapshot.extraction_identity,
            analysis_artifact=analysis_artifact,
            config=duplicate_prevalence_request.config,
        )
        persisted_corpus_identity = dict(content_exact_snapshot.corpus_identity)
        if ingest_summary is not None:
            persisted_ingest_summary = dict(ingest_summary)
            persisted_ingest_summary["corpus_identity"] = dict(
                content_exact_snapshot.corpus_identity
            )

    # 3. For each mode: retrieve → score → provenance → ANN proof → comparability
    mode_results: dict[str, dict] = {}
    for mode in modes:
        log.info("Evaluating mode: %s", mode)
        debug = embedding_enabled  # need debug=true for ANN proof evidence

        # Inject cross-encoder flag into pipeline dict when --ce is set.
        ce_pipeline_override = None
        if cross_encoder_enabled:
            base_pipeline = retriever.MODE_PIPELINES.get(mode)
            if base_pipeline:
                ce_pipeline_override = {**base_pipeline, "crossEncoderEnabled": True}

        scored_docs, raw_responses = retriever.retrieve(
            query_texts, base_url, mode, top_k, debug,
            pipeline=ce_pipeline_override,
            allow_errors=allow_errors, include_excerpts=context_coverage,
            query_syntax=query_syntax,
        )

        # Filter qrels to only the queries that were actually evaluated,
        # so ir_measures aggregates over the correct denominator.
        eval_qrels = {qid: qrels[qid] for qid in query_texts if qid in qrels}
        aggregate = scoring.evaluate(eval_qrels, scored_docs)
        per_query = scoring.evaluate_per_query(eval_qrels, scored_docs)

        query_evidences = [
            provenance.extract_query_evidence(r)
            for r in raw_responses
            if r.get("error") is None
        ]
        run_evidence = provenance.aggregate_run_evidence(query_evidences)

        pt = _compute_pipeline_tracking(mode, run_evidence)
        ann = ann_proof_mod.compute_ann_proof(
            run_evidence, embedding_enabled,
            mode=mode, pipeline_tracking=pt,
        )

        error_count = run_evidence.get("error_count", 0)
        comp = comparability_mod.determine_comparability(
            readiness_result, ann, error_count, len(query_texts),
        )

        # Context coverage (optional)
        coverage_result = None
        if context_coverage:
            from . import context_coverage as cc_mod

            coverages = []
            for raw_resp in raw_responses:
                if raw_resp.get("error"):
                    continue
                qid = raw_resp["query_id"]
                qr = query_records.get(qid)
                if not qr:
                    continue
                evidence = cc_mod.extract_expected_evidence(qr.annotations, qr.text)
                cov = cc_mod.compute_query_coverage(
                    raw_resp, evidence, qrels.get(qid, {}),
                )
                coverages.append(cov)
            coverage_result = cc_mod.summarize_coverage(
                coverages, coverage_thresholds or [0.25, 0.5],
            )

        latency_stats = _compute_latency_stats(raw_responses)
        score_stats = _compute_score_stats(raw_responses, top_k)

        # Promote the always-present latency-stage p50s into aggregate_metrics so the perf-latency
        # family flows + calibrates like a quality metric (tempdoc 640 metric-family registry). The
        # cross-encoder is the dominant, noise-robust cost (§C-2); tempdoc 647 completes the
        # decomposition by also promoting the retrieval stage (the only other stage present on every
        # query — chunk-merge/branch-fusion/lambdamart are query-conditional and stay report-only in
        # stage_timing_stats, alongside the `unaccounted_ms` remainder + per-stage shares).
        _stage_stats = run_evidence.get("stage_timing_stats") or {}
        for _stage_key, _metric_key in (
            ("cross_encoder_ms", "ce_p50_ms"),
            ("retrieval_ms", "retrieval_p50_ms"),
        ):
            _p50 = (_stage_stats.get(_stage_key) or {}).get("p50")
            if isinstance(_p50, (int, float)):
                aggregate[_metric_key] = float(_p50)

        mode_results[mode] = {
            "aggregate_metrics": aggregate,
            "per_query_metrics": per_query,
            "scored_docs": scored_docs,
            "raw_responses": raw_responses,
            "run_evidence": run_evidence,
            "pipeline_tracking": pt,
            "ann_proof": ann,
            "comparability": comp,
            "context_coverage": coverage_result,
            "latency_stats": latency_stats,
            "score_stats": score_stats,
        }

        log.info(
            "  %s: nDCG@10=%.4f  comparable=%s",
            mode,
            aggregate.get("nDCG@10", 0),
            comp.comparable,
        )

    if content_exact_snapshot is not None:
        from . import duplicate_prevalence_production as production_mod

        production_mod.revalidate_snapshot(
            content_exact_snapshot,
            duplicate_prevalence_request,
            env={**os.environ, **(env_overrides or {})},
        )
        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset_name,
        )

    # 4. Build summary + run manifest (tempdoc 400 LR1-a)
    search_config = _snapshot_search_config(base_url)
    state_snapshots = manifest_mod.capture_state_snapshots(base_url)
    # The Worker-owned data dir — where the Worker writes telemetry/, which
    # write_run copies into the run dir and _cadence_block reads directly.
    worker_data_dir = _worker_data_dir()
    # Phase 6 / 6.5: manifest override for LR5-d synthetic bisection.
    # When JUSTSEARCH_MANIFEST_OVERRIDE is set AND the
    # JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS safety flag is "1", skip
    # the endpoint-derived manifest computation and use the override
    # instead. Override manifests MUST mark `synthetic: true` so they
    # don't pollute the cross-cohort index for real-run consumers.
    override_path = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE")
    override_safe = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS") == "1"
    if override_path and override_safe:
        log.warning(
            "Using JUSTSEARCH_MANIFEST_OVERRIDE=%s — this is a synthetic "
            "run for LR5-d bisection. Manifest identity is NOT derived "
            "from observed state; cohort_hash comes from the override.",
            override_path,
        )
        import json as _json
        run_manifest = _json.loads(Path(override_path).read_text(encoding="utf-8"))
        run_manifest["synthetic"] = True
        run_manifest["synthetic_source"] = override_path
    elif override_path and not override_safe:
        raise RuntimeError(
            "JUSTSEARCH_MANIFEST_OVERRIDE is set but "
            "JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS != '1'. This flag "
            "bypasses identity derivation and produces cohort-misleading "
            "manifests; requires explicit opt-in."
        )
    else:
        run_manifest = manifest_mod.compute_manifest(
            dataset_name=dataset_name,
            meta=meta,
            env_fingerprint=env_fingerprint,
            models_snapshot=models_snapshot,
            eval_protocol=METRIC_CONTRACT,
            state_snapshots=state_snapshots,
            workflow_run_id=os.environ.get("JUSTSEARCH_WORKFLOW_RUN_ID"),
            corpus_identity=persisted_corpus_identity or _get_corpus_identity(
                dataset_name, meta, qrels, base_dir
            ),
        )
    summary = _build_summary(dataset_name, modes, mode_results, meta, qrels,
                             persisted_ingest_summary, pipeline_summary, models_snapshot,
                             search_config, env_overrides, env_fingerprint,
                             run_manifest=run_manifest, base_dir=base_dir,
                             status_snapshot=state_snapshots.get("/api/status"),
                             index_cache=index_cache, query_syntax=query_syntax,
                             index_state_at_query=index_state_at_query,
                             raw_context=raw_context,
                             corpus_identity_override=persisted_corpus_identity)
    # Tempdoc 885: additive block from the background search-load thread (absent by default).
    if search_load:
        summary["search_load"] = search_load
    # Tempdoc 885 item 19: cadence counters are always emitted (null when the Worker does
    # not publish them) so the arm-comparison table has its columns on every run.
    summary["cadence"] = _cadence_block(first_search_probe)
    # Tempdoc 930 §18.1 row 7: absolute per-encoder ONNX latency, always emitted
    # (empty `encoders` when the Worker published no `encoder.ort_run` spans).
    summary["encoder_latency"] = _encoder_latency_block()

    # 5. Write artifacts + append history
    if output_dir:
        run_dir = artifacts_mod.write_run(
            summary, mode_results, qrels, Path(output_dir), query_records,
            # Worker telemetry includes path-bearing indexing span attributes.
            # A private content-exact run may retain those only in its local
            # backend scratch directory, never in the published run artifact.
            data_dir=(None if content_exact_identity is not None else worker_data_dir),
            content_exact_identity=content_exact_identity,
        )
        log.info("Artifacts written to %s", run_dir)

        projection_summary = None
        if content_exact_identity is not None:
            # The normal projection runner intentionally quarantines exceptions.
            # An explicitly requested private join is different: validate the
            # exact persisted artifact chain before history/cohort publication.
            from jseval.projections.staged_recall_accounting import produce as produce_staged
            from jseval.projections import run_all_discovered

            produce_staged(run_dir)
            skip_csv = os.environ.get("JUSTSEARCH_SKIP_PROJECTIONS", "")
            skip_set = frozenset(
                name.strip() for name in skip_csv.split(",") if name.strip()
            )
            projection_summary = run_all_discovered(run_dir, skip=skip_set)
            staged = (projection_summary or {}).get("staged_recall_accounting")
            if not isinstance(staged, dict) or staged.get("status") != "ok":
                detail = staged.get("error") if isinstance(staged, dict) else "not registered"
                raise RuntimeError(
                    "staged_recall_accounting must succeed for a duplicate-prevalence run: "
                    f"{detail}"
                )

        history_dir = history_db or Path(output_dir)
        run_manifest_hash = run_manifest.get("manifest_hash") if isinstance(run_manifest, dict) else None
        # Perf families trended alongside quality (tempdoc 640 R3): per-run throughput + the derived
        # resident footprint are run-level; CE-stage p50 is per-mode (from aggregate_metrics).
        _run_metrics = summary.get("run_metrics") or {}
        from . import perf_gate as _perf_gate
        _footprint = _perf_gate.derive_resident_model_bytes(run_manifest)
        for mode in modes:
            mr = mode_results[mode]
            history_mod.append_run(
                summary, mode, mr["aggregate_metrics"],
                mr["comparability"].comparable,
                history_dir,
                mean_latency_ms=mr["latency_stats"].get("mean_ms"),
                context_hit_rate=(mr.get("context_coverage") or {}).get(
                    "mean_best_term_coverage"),
                manifest_hash=run_manifest_hash,
                perf_metrics={
                    "ce_p50_ms": (mr["aggregate_metrics"] or {}).get("ce_p50_ms"),
                    "primary_docs_s": _run_metrics.get("primary_docs_s"),
                    "enrich_docs_s": _run_metrics.get("enrich_docs_s"),
                    "resident_bytes": _footprint,
                    # tempdoc 647: trend the latency decomposition — retrieval from aggregate_metrics
                    # (promoted), the unaccounted remainder from stage_timing_stats (report-only, so it
                    # is trended without being promoted/gated).
                    "retrieval_p50_ms": (mr["aggregate_metrics"] or {}).get("retrieval_p50_ms"),
                    "unaccounted_p50_ms": (
                        (mr["run_evidence"].get("stage_timing_stats") or {}).get("unaccounted_ms")
                        or {}
                    ).get("p50"),
                },
            )

        # Phase 3 LR4-a: invoke every registered projection against the
        # run artifacts. Failures are quarantined per-projection (see
        # jseval.projections.base.run_all) so a single broken projection
        # cannot tank the whole run.
        # Phase 6 / 6.1: per-projection exceptions now also emit a
        # synthetic contract.violation event (aggregated by LR6-c) so
        # silent failures surface in the nightly gate.
        if projection_summary is None:
            from jseval.projections import run_all_discovered

            skip_csv = os.environ.get("JUSTSEARCH_SKIP_PROJECTIONS", "")
            skip_set = frozenset(
                name.strip() for name in skip_csv.split(",") if name.strip()
            )
            projection_summary = run_all_discovered(run_dir, skip=skip_set)
        if projection_summary:
            log.info("Projections ran: %s", {
                name: info["status"] for name, info in projection_summary.items()
            })

        # Phase 4 LR5-d: register the run in the cohort manifest index
        # so future bisection invocations can look up cached runs by
        # synthetic manifest hash.
        if run_manifest_hash:
            from jseval import bisection
            try:
                bisection.register_run(
                    Path(output_dir),
                    manifest_hash=run_manifest_hash,
                    run_dir=run_dir,
                    git_sha=run_manifest.get("git_sha") if isinstance(run_manifest, dict) else None,
                    dataset=dataset_name,
                    mode=",".join(modes) if modes else None,
                    timestamp=summary.get("timestamp"),
                )
            except OSError as e:
                log.debug("bisection register_run failed: %s", e)

    return summary


def _build_summary(
    dataset_name: str,
    modes: list[str],
    mode_results: dict,
    meta,
    qrels: dict,
    ingest_summary: dict | None = None,
    pipeline_summary: dict | None = None,
    models_snapshot: dict | None = None,
    search_config: dict | None = None,
    env_overrides: dict[str, str] | None = None,
    env_fingerprint: dict | None = None,
    run_manifest: dict | None = None,
    base_dir: Path | None = None,
    status_snapshot: dict | None = None,
    index_cache: dict | None = None,
    query_syntax: str | None = None,
    index_state_at_query: dict | None = None,
    raw_context=None,
    corpus_identity_override=None,
) -> dict:
    summary: dict = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git_sha": _get_git_sha(),
        "dataset": dataset_name,
        "modes": modes,
        "doc_count": meta.doc_count,
        "query_count": meta.query_count,
        "metric_contract": METRIC_CONTRACT,
        # Q-020 / F-046: self-documents which syntax retrieval ran under. `query_syntax=None`
        # (nothing sent on the wire) is recorded as the Head's own default, "simple" —
        # see `docs/reference/api-contract-map.md` (Knowledge Search API, `querySyntax`).
        "query_syntax": query_syntax or "simple",
        # tempdoc 931 §E item 10: merge-state snapshot at query-phase start, always present
        # (like `cadence`) so a paired-arm comparison table always has the column, even when
        # every field inside is null (skip_readiness / older backend).
        "index_state_at_query": index_state_at_query or _build_index_state_at_query({}, None),
        "qrels_summary": _compute_qrels_summary(qrels),
        "corpus_identity": (
            corpus_identity_override
            if corpus_identity_override is not None
            else (
                raw_context.to_corpus_identity()
                if raw_context is not None
                else _get_corpus_identity(dataset_name, meta, qrels, base_dir)
            )
        ),
        "per_mode": {
            mode: {
                "aggregate_metrics": mr["aggregate_metrics"],
                "ann_proof_status": mr["ann_proof"].status,
                "comparable": mr["comparability"].comparable,
                "comparability_reasons": mr["comparability"].reasons,
                "error_count": mr["run_evidence"].get("error_count", 0),
                "pipeline_tracking": mr["pipeline_tracking"],
                "latency_stats": mr["latency_stats"],
                "score_stats": mr["score_stats"],
                "stage_timing_stats": mr["run_evidence"].get("stage_timing_stats", {}),
                **({"context_coverage": mr["context_coverage"]}
                   if mr.get("context_coverage") else {}),
            }
            for mode, mr in mode_results.items()
        },
    }
    if ingest_summary:
        summary["ingest"] = ingest_summary
        # Per-run metric family (tempdoc 640): surface throughput as first-class `run_metrics` so it
        # flows into the release + history like quality, instead of being read out of `ingest` ad hoc.
        # (Footprint/resident_bytes is derived from the manifest at gate/compose time, not here.)
        _prim = (ingest_summary.get("pipeline_summary") or {}).get("primary_indexing") or {}
        _run_metrics: dict[str, float] = {}
        if isinstance(_prim.get("docs_per_s"), (int, float)):
            _run_metrics["primary_docs_s"] = float(_prim["docs_per_s"])
        if isinstance(ingest_summary.get("docs_per_sec"), (int, float)):
            _run_metrics["enrich_docs_s"] = float(ingest_summary["docs_per_sec"])
        if _run_metrics:
            summary["run_metrics"] = _run_metrics
    if pipeline_summary:
        summary["pipeline_timing"] = pipeline_summary
    if models_snapshot:
        summary["models"] = models_snapshot
    if search_config:
        summary["search_config"] = search_config
    if env_overrides:
        summary["env_overrides"] = env_overrides
    if env_fingerprint:
        summary["env_fingerprint"] = env_fingerprint
    if run_manifest:
        summary["manifest"] = run_manifest
    cache_block = _index_cache_block(index_cache)
    if cache_block:
        summary["index_cache"] = cache_block
    summary["chunk_completeness"] = _compute_chunk_completeness(
        dataset_name, mode_results, status_snapshot, base_dir,
    )
    summary["ce_coverage"] = _compute_ce_coverage(mode_results)
    return summary


def _index_cache_block(cache_outcome: dict | None) -> dict | None:
    """Project the BackendInfo cache outcome into the run-provenance block (751 M.4).

    Drops the internal ``selector_key`` (used only by the publish hook) and keeps
    the disclosed axes: adopted-vs-fresh mode, entry id, miss/confirm detail, and
    the sec M.5 scoped-pin placeholder. Returns ``None`` when the cache was not
    engaged (no block enters the summary -- byte-identical to today).
    """
    if not cache_outcome:
        return None
    return {
        "mode": cache_outcome.get("mode"),
        "entry": cache_outcome.get("entry"),
        "detail": cache_outcome.get("detail"),
        "would_have_hit_scoped_pin": cache_outcome.get("would_have_hit_scoped_pin"),
    }


def _compute_chunk_completeness(
    dataset_name: str,
    mode_results: dict,
    status_snapshot: dict | None,
    base_dir: Path | None,
) -> dict:
    """The tempdoc-718 chunk-completeness block: attach to every run so it self-documents,
    sibling of `manifest`/`corpus_identity`. Enforcement lives at the gate seam
    (`ratchet_kernel.assert_chunk_completeness`); this is the advisory half (644 idiom: both
    an embedded verdict AND a fail-closed gate assertion).

    Offline expectation is read from the run's `corpus.jsonl` (golden/mixed self-demo corpora
    only — a BEIR dataset has no local corpus.jsonl, so `expected_chunk_docs` gracefully
    returns 0 and this resolves to the harmless `chunk-free` verdict, never gating a BEIR run).

    The chunk threshold the expectation is computed against comes from the SAME status snapshot
    (tempdoc 821 §3-C3): the worker's enrichment auditor publishes `chunkMinChars`, so this
    oracle no longer mirrors the Java constant. A backend that predates that field reports 0 for
    it (proto3 scalar — never absent), which yields the `unevaluable` verdict: non-gating, but
    distinct from `chunk-free` and warned about at the gate seam.
    """
    dataset_dir = (base_dir or corpora._default_base_dir()) / dataset_name

    # Observed: worker.enrichment.chunk.* from the run-completion /api/status snapshot
    # (readiness.py already reads this same nested path — flatten_status merges it to the
    # top level; jseval.readiness is already imported for the readiness-poll machinery).
    flat_status = readiness.flatten_status(dict(status_snapshot)) if status_snapshot else {}
    observed_chunk_doc_count = flat_status.get("chunkDocCount", 0)
    observed_coverage_pct = flat_status.get("chunkVectorCoveragePercent")

    threshold_chars = chunk_completeness_mod.resolve_chunk_threshold_chars(flat_status)
    expected = chunk_completeness_mod.expected_chunk_docs(
        dataset_dir / "corpus.jsonl", threshold_chars
    )
    # Denominator for the corpus-shape waiver's rate predicate below (0 for a BEIR dataset with
    # no local corpus.jsonl -- same "nothing to expect" shape `expected` already returns).
    corpus_total_docs = chunk_completeness_mod.corpus_doc_count(dataset_dir / "corpus.jsonl")

    # chunk_merge corroborator: only meaningful when `vector` mode actually ran this run (it's
    # a query-time signal from vector-mode retrieval). A run that only exercises e.g. `lexical`
    # never fires chunk_merge regardless of index health, so its absence must not be read as a
    # strike when vector mode wasn't part of this run — pass True (not applicable, not a strike).
    vector_mr = mode_results.get("vector")
    if vector_mr is not None:
        chunk_merge_observed = "chunk_merge" in (
            (vector_mr.get("pipeline_tracking") or {}).get("observed") or []
        )
        if not chunk_merge_observed:
            # tempdoc 715 defect 1: chunk_merge legitimately never fires on a short corpus --
            # SearchPlanner.java:267/265 skips the leg with SearchReasonCode.SKIPPED_SHORT_CORPUS
            # / SKIPPED_NO_CHUNK_DOCS (CorpusCapabilities.corpusSupportsChunks()==false), which is
            # not a degeneracy (twice-observed false-positive on mixed/miracl-de-2k, 2026-07-16:
            # 3103 docs, ~19 reach the chunk threshold). When every skip this run carries one of
            # those engine-declared corpus-shape reasons, the corroborator MAY be not applicable
            # here (never a strike on its own, per chunk_completeness.py's own docstring) -- but a
            # skip with NO reason recorded, or a reason outside this set (e.g.
            # SKIPPED_VECTOR_BLOCKED -- a real failure), must still read as a strike.
            #
            # tempdoc 717/718 review: the engine's own SHORT_CORPUS classification is exactly what
            # 717 proved can be WRONG (a healthy, chunked index misclassified as short at query
            # time). Trusting the reason name alone would let a 717-class regression slip through
            # silently -- it produces the identical signature this waiver excuses. So the waiver is
            # conditional on the OFFLINE expectation (computed from the corpus TEXT, independent of
            # any engine misclassification) agreeing the corpus is plausibly short: the
            # chunk-eligible-doc RATE must be below _CHUNK_MERGE_SHORT_CORPUS_RATE_MAX, mirroring
            # CorpusProfile.chunkRate() < 0.05. `corpus_total_docs <= 0` (no local corpus.jsonl --
            # a BEIR run) keeps the prior unconditional-waiver behavior, since there is no offline
            # shape to check against.
            skip_reasons = set(
                (vector_mr.get("run_evidence") or {}).get("chunk_merge_skip_reason_counts") or {}
            )
            if skip_reasons and skip_reasons <= _CHUNK_MERGE_CORPUS_SHAPE_SKIP_REASONS:
                plausibly_short_corpus = (
                    corpus_total_docs <= 0
                    or (expected / corpus_total_docs) < _CHUNK_MERGE_SHORT_CORPUS_RATE_MAX
                )
                if plausibly_short_corpus:
                    chunk_merge_observed = True
    else:
        chunk_merge_observed = True

    if threshold_chars is None:
        # Stand down as its OWN verdict rather than borrowing `chunk-free`, whose reason asserts
        # "no corpus doc reaches the chunk threshold" — a fact this path never computed and one
        # that is affirmatively wrong on a degenerate build. `chunkMinChars` is a proto3 scalar,
        # so an old backend does not omit it: it arrives as 0. Name the observed shape, not an
        # absence. Non-blocking (821 §3-C3 back-compat), but ratchet_kernel warns on stderr.
        result = chunk_completeness_mod.unevaluable_result(
            observed_chunk_doc_count,
            "worker.enrichment.chunkMinChars is missing or non-positive in the status snapshot "
            "(a backend predating tempdoc 821 §3-C3 reports 0) — the offline chunk expectation "
            "could not be computed, so this run is NOT gated on chunk completeness",
        )
    else:
        result = chunk_completeness_mod.chunk_completeness_verdict(
            expected, observed_chunk_doc_count, observed_coverage_pct, chunk_merge_observed,
        )
    return {
        "expected": result.expected,
        "observed": result.observed,
        "verdict": result.verdict,
        "reasons": list(result.reasons),
        # Provenance: which threshold the expectation was computed against (null = none published).
        "threshold_chars": threshold_chars,
    }


def _compute_ce_coverage(mode_results: dict) -> dict:
    """The register-F-052 cross-encoder-coverage block: attach to every run so it self-documents,
    sibling of `chunk_completeness`. Enforcement lives at the gate seam
    (`ratchet_kernel.assert_ce_coverage`); this is the advisory half (644 idiom: both an embedded
    verdict AND a fail-closed gate assertion).

    Computed per mode from that mode's raw responses — a mode whose pipeline never runs the CE
    resolves to `not-applicable` on its own recorded reasons (PIPELINE_NOT_ELIGIBLE / DISABLED),
    so leg-isolation and lexical-only modes are never struck and no mode allow-list has to be
    maintained here. Errored queries are excluded, matching the `query_evidences` filter above:
    they have no trace to read and are already counted by `error_count`.
    """
    per_mode: dict[str, ce_coverage_mod.CeCoverageResult] = {}
    for mode, mr in mode_results.items():
        states = [
            ce_coverage_mod.state_from_response(r)
            for r in (mr.get("raw_responses") or [])
            if r.get("error") is None
        ]
        ce_requested = "cross_encoder" in ((mr.get("pipeline_tracking") or {}).get("observed") or [])
        per_mode[mode] = ce_coverage_mod.ce_coverage_verdict(states, ce_requested=ce_requested)

    verdict, reasons = ce_coverage_mod.combine_mode_verdicts(per_mode)
    return {
        "verdict": verdict,
        "reasons": reasons,
        "tolerance": ce_coverage_mod.DEFAULT_SILENT_DROP_TOLERANCE,
        "per_mode": {mode: ce_coverage_mod.as_block(r) for mode, r in per_mode.items()},
    }


def _compute_latency_stats(raw_responses: list[dict]) -> dict:
    """Compute latency distribution from per-query tookMs values."""
    values = sorted(
        r["tookMs"] for r in raw_responses
        if r.get("error") is None and r.get("tookMs") is not None
    )
    n = len(values)
    if n == 0:
        return {"query_count": 0}

    def _percentile(sorted_vals: list, p: float) -> float:
        idx = int(p / 100 * (len(sorted_vals) - 1) + 0.5)
        return sorted_vals[min(idx, len(sorted_vals) - 1)]

    return {
        "query_count": n,
        "mean_ms": round(sum(values) / n, 1),
        "p50_ms": _percentile(values, 50),
        "p95_ms": _percentile(values, 95),
        "p99_ms": _percentile(values, 99),
        "max_ms": values[-1],
        "min_ms": values[0],
    }


def _compute_score_stats(raw_responses: list[dict], top_k: int) -> dict:
    """Compute score distribution from per-query hit scores."""
    top1_scores: list[float] = []
    topk_means: list[float] = []
    for r in raw_responses:
        if r.get("error"):
            continue
        hits = r.get("results", [])[:top_k]
        scores = [h["score"] for h in hits if "score" in h]
        if scores:
            top1_scores.append(scores[0])
            topk_means.append(sum(scores) / len(scores))
    return {
        "mean_top1_score": round(sum(top1_scores) / len(top1_scores), 4) if top1_scores else None,
        "mean_topk_score": round(sum(topk_means) / len(topk_means), 4) if topk_means else None,
    }


def _compute_qrels_summary(qrels: dict) -> dict:
    max_rel = 0
    with_relevant = 0
    for docs in qrels.values():
        rels = [r for r in docs.values() if r > 0]
        if rels:
            with_relevant += 1
            max_rel = max(max_rel, max(rels))
    return {
        "relevance_mode": "binary" if max_rel <= 1 else "graded",
        "query_count": len(qrels),
        "max_relevance": max_rel,
        "queries_with_relevant": with_relevant,
    }


def _compute_pipeline_tracking(mode: str, run_evidence: dict) -> dict:
    expected = EXPECTED_COMPONENTS.get(mode, set())
    component_counts = run_evidence.get("component_status_counts", {})
    observed: set[str] = set()
    for comp, statuses in component_counts.items():
        if statuses.get("executed", 0) > 0:
            observed.add(comp)

    mismatches: list[str] = []
    for comp in sorted(expected - observed):
        mismatches.append(f"requested_{comp}_but_not_observed")
    for comp in sorted((observed - expected) & {"dense", "sparse", "splade"}):
        mismatches.append(f"unexpected_{comp}_observed")

    return {
        "requested": sorted(expected),
        "observed": sorted(observed),
        "mismatch_reasons": mismatches,
    }


def _get_corpus_identity(
    dataset_name: str | None = None,
    meta=None,
    qrels: dict | None = None,
    base_dir: Path | None = None,
) -> dict:
    """Corpus identity for the run manifest + benchmark-release pin (tempdoc 623 ③).

    Computes a real content **signature** (the previously-dead seam): a
    ``mixed/golden`` corpus is pinned by ``sha256(corpus.jsonl + qrels/test.tsv)``;
    a BEIR corpus is pinned by ``sha256({ir_datasets_id, qrels})`` — its ``.txt``
    files materialize only after ingest, so the stable inputs are the dataset id
    + the binary qrels. An explicit ``JUSTSEARCH_CORPUS_SIGNATURE`` still wins (an
    operator override); the computed value fills the seam when it is unset. The
    release composer (:func:`jseval.release._corpus_source`) reads ``signature``
    into ``corpus_source.sha256`` unchanged.
    """
    env_sig = os.environ.get("JUSTSEARCH_CORPUS_SIGNATURE")
    signature = env_sig
    if env_sig is None and meta is not None:
        try:
            source = getattr(meta, "source", None)
            if source in ("mixed", "golden") and dataset_name:
                # Single corpus-signature definition (tempdoc 635): the same
                # function the corpus metadata + agent-eval use, so all three
                # carry one identity (conform, don't fork).
                from . import corpus_identity
                dataset_dir = (base_dir or corpora._default_base_dir()) / dataset_name
                sig = corpus_identity.corpus_signature(dataset_dir)
                if sig is not None:
                    signature = sig
            elif source == "beir":
                ir_id = corpora.BEIR_DATASETS.get(getattr(meta, "name", None))
                if ir_id is not None:
                    signature = manifest_mod._sha256_canonical(
                        {"ir_datasets_id": ir_id, "qrels": qrels or {}}
                    )
        except OSError as e:  # best-effort — a missing/unreadable file never fails a run
            log.debug("corpus signature computation failed (best-effort): %s", e)
            signature = env_sig
    return {
        "profile_id": os.environ.get("JUSTSEARCH_CORPUS_PROFILE_ID"),
        "signature": signature,
    }


def _get_git_sha() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        return result.stdout.strip() or None
    except Exception:
        return None


def _capture_env_fingerprint() -> dict | None:
    """E-J-N11: capture machine-state snapshot for post-hoc variance context.

    Informational only — NEVER used as a comparability gate. Best-effort:
    probe failures yield null fields or an empty dict, never a run failure.
    """
    try:
        from . import env_fingerprint as ef
        return ef.capture_env_fingerprint()
    except Exception as e:
        log.debug("env_fingerprint capture failed (non-fatal): %s", e)
        return None
