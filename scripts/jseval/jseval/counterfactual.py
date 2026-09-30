"""Counterfactual single-pass runner (tempdoc 400 LR5-a).

**Historical design deviation vs section 8.5 spec.** The spec proposed a proto change
(``repeated string counterfactual_modes`` on SearchRequest +
``map<string, RankList>`` on response) so the Worker returns
all counterfactual rankings in one request ("single-pass"). Since
``SearchOrchestrator`` already computes per-branch rankings (C1 spike
confirmed) but discards them after fusion, the spec's single-pass is
efficient but required a cross-process protocol cascade at that time. The current
Engine uses in-process ports; the multi-pass implementation below is unchanged.

This implementation uses a **multi-pass variant**: jseval issues
one request per mode against the existing ``/api/knowledge/search``
endpoint with different ``pipeline`` configs. The externally-visible
outputs match the single-pass intent — per-query rankings under
``lexical_only``, ``dense_only``, ``splade_only``, ``hybrid_no_ce``,
``hybrid_full`` labels.

**Cost model (Phase 6 / 6.4 — corrected).** The previous docstring
claimed "same CE cost as hybrid_full" which is narrowly true but
misleading. Full breakdown per query (5 modes):

- **BM25 retrieval** runs **3×** (lexical_only, hybrid_no_ce,
  hybrid_full).
- **Dense encoder** runs **3×** (dense_only, hybrid_no_ce,
  hybrid_full). This is the heavy cost: embedding-model inference
  per query text.
- **SPLADE encoder** runs **3×** (splade_only, hybrid_no_ce,
  hybrid_full).
- **CE reranker** runs **1×** (hybrid_full only; the other 4 disable
  ``crossEncoderEnabled`` in the pipeline config).
- **Fusion** runs **2×** (hybrid_no_ce, hybrid_full).

For scifact 300 queries × 5 modes, expect ~3× total encoder time vs
a single ``jseval run --modes full``, plus ~5× HTTP round-trip
overhead. A spec-compliant single-pass Worker-side implementation
would encode each query exactly once and fan the shared encoded
vectors out to every counterfactual branch — the follow-up
optimization is an HTTP/proto extension accepting pre-encoded
vectors via ``repeated float query_vector`` + SPLADE weights inline.
Until that lands, operators who need many counterfactual runs
should consider caching at their own layer.

**Fusion algorithm is configurable (Phase 6 / 6.4).** Pass
``fusion_algorithm="rrf"`` to :func:`build_counterfactual_modes`
(or ``--fusion-algorithm rrf`` on the CLI) to compare RRF vs CC
fusion; default is ``cc`` matching the primary eval pipeline.
"""

from __future__ import annotations

import json
import logging
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from .retriever import _build_request, execute_query

log = logging.getLogger(__name__)

SCHEMA_VERSION = 1

DEFAULT_FUSION_ALGORITHM = "cc"
SUPPORTED_FUSION_ALGORITHMS: frozenset[str] = frozenset({"cc", "rrf"})


def build_counterfactual_modes(
    fusion_algorithm: str = DEFAULT_FUSION_ALGORITHM,
) -> dict[str, dict]:
    """Return the 5 canonical counterfactual modes for a fusion algorithm.

    - lexical_only: sparse only, no fusion, no rerank.
    - dense_only:   dense only.
    - splade_only:  splade only.
    - hybrid_no_ce: sparse+dense+splade fusion WITHOUT cross-encoder.
    - hybrid_full:  full pipeline with cross-encoder.

    ``fusion_algorithm`` applies to the hybrid modes (hybrid_no_ce,
    hybrid_full). Supported: "cc" (convex combination, default) or
    "rrf" (reciprocal rank fusion). Raises ValueError on unknown.
    """
    if fusion_algorithm not in SUPPORTED_FUSION_ALGORITHMS:
        raise ValueError(
            f"unknown fusion_algorithm={fusion_algorithm!r}; "
            f"supported: {sorted(SUPPORTED_FUSION_ALGORITHMS)}"
        )
    return {
        "lexical_only": {
            "sparseEnabled": True, "denseEnabled": False,
            "spladeEnabled": False, "fusionAlgorithm": "none",
            "lambdamartEnabled": False, "crossEncoderEnabled": False,
            "crossEncoderWindow": 0, "expansionEnabled": False,
            "freshnessEnabled": False,
        },
        "dense_only": {
            "sparseEnabled": False, "denseEnabled": True,
            "spladeEnabled": False, "fusionAlgorithm": "none",
            "lambdamartEnabled": False, "crossEncoderEnabled": False,
            "crossEncoderWindow": 0, "expansionEnabled": False,
            "freshnessEnabled": False,
        },
        "splade_only": {
            "sparseEnabled": False, "denseEnabled": False,
            "spladeEnabled": True, "fusionAlgorithm": "none",
            "lambdamartEnabled": False, "crossEncoderEnabled": False,
            "crossEncoderWindow": 0, "expansionEnabled": False,
            "freshnessEnabled": False,
        },
        "hybrid_no_ce": {
            "sparseEnabled": True, "denseEnabled": True,
            "spladeEnabled": True, "fusionAlgorithm": fusion_algorithm,
            "lambdamartEnabled": False, "crossEncoderEnabled": False,
            "crossEncoderWindow": 0, "expansionEnabled": False,
            "freshnessEnabled": False,
        },
        "hybrid_full": {
            "sparseEnabled": True, "denseEnabled": True,
            "spladeEnabled": True, "fusionAlgorithm": fusion_algorithm,
            "lambdamartEnabled": True, "crossEncoderEnabled": True,
            "crossEncoderWindow": 20, "expansionEnabled": False,
            "freshnessEnabled": False,
        },
    }


# Back-compat alias: callers that imported `COUNTERFACTUAL_MODES`
# directly (pre-Phase-6) get the default "cc" mode set. Tests and
# new callers should prefer `build_counterfactual_modes(...)`.
COUNTERFACTUAL_MODES = build_counterfactual_modes(DEFAULT_FUSION_ALGORITHM)


def _top_k_ids(response: dict, k: int) -> list[str]:
    results = response.get("results") or []
    return [h.get("id") for h in results[:k] if h.get("id") is not None]


def _jaccard(a: list[str], b: list[str]) -> float:
    sa, sb = set(a), set(b)
    if not sa and not sb:
        return 1.0
    union = sa | sb
    return len(sa & sb) / len(union) if union else 0.0


def run_counterfactual(
    queries: dict[str, str],
    *,
    base_url: str,
    modes: list[str] | None = None,
    top_k: int = 10,
    timeout: float = 90.0,
    max_retries: int = 5,
    allow_errors: bool = False,
    fusion_algorithm: str = DEFAULT_FUSION_ALGORITHM,
) -> dict:
    """Run each query under every counterfactual mode.

    Phase 6 / 6.4: ``fusion_algorithm`` parameterizes the hybrid
    modes (``hybrid_no_ce``, ``hybrid_full``) — "cc" or "rrf".

    Returns an LR5-a schema-v1 document with per-query rankings per
    mode and pairwise mode divergence summaries (e.g. "how much does
    removing the cross-encoder change the ranking vs the full
    pipeline?").
    """
    modes_map = build_counterfactual_modes(fusion_algorithm)
    resolved_modes = modes or list(modes_map.keys())
    unknown = [m for m in resolved_modes if m not in modes_map]
    if unknown:
        raise ValueError(f"unknown counterfactual modes: {unknown}")

    start_ts = datetime.now(timezone.utc).isoformat()
    t0 = time.perf_counter()
    per_query: list[dict] = []
    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        for qid, qtext in queries.items():
            per_mode: dict[str, dict] = {}
            for mode in resolved_modes:
                pipeline = modes_map[mode]
                body = _build_request(qtext, mode=mode, top_k=top_k,
                                      debug=False, pipeline=pipeline)
                t_q0 = time.perf_counter()
                response = execute_query(
                    client, qid, body, max_retries, allow_errors,
                )
                dispatch_ms = (time.perf_counter() - t_q0) * 1000.0
                if response is None:
                    per_mode[mode] = {
                        "status": "error", "dispatch_ms": dispatch_ms,
                    }
                else:
                    per_mode[mode] = {
                        "status": "ok",
                        "dispatch_ms": dispatch_ms,
                        "top_k_ids": _top_k_ids(response, top_k),
                        "total_hits": response.get("totalHits"),
                        "took_ms": response.get("tookMs"),
                    }
            per_query.append({"qid": qid, "query": qtext, "modes": per_mode})

    wall_ms = (time.perf_counter() - t0) * 1000.0
    end_ts = datetime.now(timezone.utc).isoformat()

    pairwise = _pairwise_divergence(per_query, resolved_modes)
    summary = _aggregate_summary(per_query, resolved_modes)

    return {
        "schema_version": SCHEMA_VERSION,
        "start_ts": start_ts,
        "end_ts": end_ts,
        "wall_ms": wall_ms,
        "modes": resolved_modes,
        "top_k": top_k,
        "query_count": len(queries),
        "fusion_algorithm": fusion_algorithm,
        "pairwise_divergence": pairwise,
        "summary": summary,
        "per_query": per_query,
    }


def _pairwise_divergence(
    per_query: list[dict], modes: list[str],
) -> dict[str, dict[str, float | None]]:
    """Compute mean top-K Jaccard between every ordered pair of modes.

    The self-pair (mode, mode) is always 1.0. The matrix is symmetric
    but emitted as a flat dict for easy JSON consumption.
    """
    out: dict[str, dict[str, float | None]] = {}
    for ma in modes:
        out[ma] = {}
        for mb in modes:
            vals: list[float] = []
            for rec in per_query:
                pma, pmb = rec["modes"].get(ma), rec["modes"].get(mb)
                if not pma or not pmb:
                    continue
                if pma["status"] != "ok" or pmb["status"] != "ok":
                    continue
                vals.append(_jaccard(pma["top_k_ids"], pmb["top_k_ids"]))
            out[ma][mb] = (statistics.mean(vals) if vals else None)
    return out


def _aggregate_summary(
    per_query: list[dict], modes: list[str],
) -> dict[str, dict]:
    """Per-mode aggregate: ok count + mean dispatch + p95 dispatch."""
    out: dict[str, dict] = {}
    for m in modes:
        latencies: list[float] = []
        ok = 0
        err = 0
        for rec in per_query:
            pm = rec["modes"].get(m)
            if not pm:
                continue
            if pm["status"] == "ok":
                ok += 1
                latencies.append(pm["dispatch_ms"])
            else:
                err += 1
        out[m] = {
            "ok": ok,
            "errors": err,
            "mean_dispatch_ms": (statistics.mean(latencies)
                                 if latencies else None),
            "max_dispatch_ms": max(latencies) if latencies else None,
        }
    return out


def write_report(result: dict, run_dir: Path) -> Path:
    """Write the counterfactual result as ``<run_dir>/counterfactual.json``."""
    path = run_dir / "counterfactual.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path
