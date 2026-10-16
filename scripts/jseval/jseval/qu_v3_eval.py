"""V3 end-to-end eval: do extracted filters improve search results?

Runs each query twice against the live search API:
  A) Baseline: raw query, no filters
  B) QU-enhanced: extracted query + extracted filters from the spike

Scores both against MultiHop-RAG evidence annotations using ir_measures.

Requires: JustSearch backend running with MultiHop-RAG corpus ingested.

Usage:
    python -m jseval qu-v3 --base-url http://127.0.0.1:33221 [--max-queries 50] [--json]
"""

from __future__ import annotations

import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any

import click
import httpx

log = logging.getLogger(__name__)

DATA_DIR = Path(__file__).parent / "data"
TITLE_MAP_FILE = DATA_DIR / "multihop-title-map.json"
EVAL_QUERIES_FILE = Path("D:/code/JustSearch/datasets/multihop-rag/eval_queries.json")
SPIKE_PROMPT_FILE = DATA_DIR / "qu-spike-prompt.v2.txt"
SPIKE_SCHEMA_FILE = DATA_DIR / "qu-spike-schema.v1.json"

# Reuse spike's index snapshot and prompt building
from .qu_spike import (
    INDEX_SNAPSHOT,
    build_prompt,
    call_llama_server,
    load_prompt_template,
    load_schema,
    normalize_values,
)


def load_title_map() -> dict[str, str]:
    with open(TITLE_MAP_FILE) as f:
        return json.load(f)


def load_eval_queries() -> list[dict]:
    with open(EVAL_QUERIES_FILE) as f:
        return json.load(f)


def build_qrels(queries: list[dict], title_map: dict[str, str]) -> dict[str, dict[str, int]]:
    """Convert evidence_titles to qrels format: {qid: {doc_id: 1}}."""
    qrels = {}
    for i, q in enumerate(queries):
        qid = f"q{i}"
        rels = {}
        for title in q.get("evidence_titles", []):
            filename = title_map.get(title)
            if filename:
                doc_id = Path(filename).stem  # article_042.md → article_042
                rels[doc_id] = 1
        if rels:
            qrels[qid] = rels
    return qrels


def search_api(
    base_url: str, query: str, filters: dict | None = None,
    boost_filters: dict | None = None, limit: int = 10
) -> list[dict]:
    """Call JustSearch search API, return hits."""
    body: dict[str, Any] = {"query": query, "limit": limit, "includeExcerpts": True}
    if filters:
        body["filters"] = filters
    if boost_filters:
        body["boostFilters"] = boost_filters
    try:
        with httpx.Client(base_url=base_url, timeout=30.0) as client:
            resp = client.post("/api/knowledge/search", json=body)
            resp.raise_for_status()
            data = resp.json()
            return data.get("results", [])
    except Exception as e:
        log.warning("Search API error: %s", e)
        return []


def resolve_doc_id(hit: dict) -> str:
    """Extract doc ID from search hit, matching retriever.py logic."""
    fields = hit.get("fields", {})
    filename = fields.get("filename") or ""
    if not filename:
        path = fields.get("path") or hit.get("provenance", {}).get("path", "")
        if path:
            filename = Path(path).name
    if not filename:
        filename = hit.get("id", "")
    return Path(filename).stem


def extract_filters_for_query(
    query_text: str, llm_port: int, system_prompt: str, schema: dict
) -> tuple[str, dict]:
    """Run QU extraction on a query. Returns (refined_query, filters_dict)."""
    result, _, error = call_llama_server(
        llm_port, system_prompt, f'Query: "{query_text}"', schema,
        enable_thinking=False
    )
    if error or not result:
        return query_text, {}

    refined = result.get("query", query_text)
    extractions = result.get("extractions", {})

    # Convert per-field confidence format to flat filter dict
    filters = {}
    for field, val in extractions.items():
        if isinstance(val, dict):
            confidence = val.get("confidence", 0)
            if confidence < 0.5:
                continue  # Skip low-confidence extractions
            if "values" in val and val["values"]:
                filters[field] = val["values"]
            elif "value" in val and val["value"]:
                filters[field] = val["value"]
    return refined, filters


def map_filters_to_api(filters: dict) -> dict:
    """Map extraction field names to search API filter parameter names."""
    api_filters = {}
    for field, value in filters.items():
        if field == "meta_source":
            api_filters["meta_source"] = value
        elif field == "meta_author":
            api_filters["meta_author"] = value
        elif field == "meta_category":
            api_filters["meta_category"] = value
        elif field == "meta_published_after":
            api_filters["meta_published_after"] = value
        elif field == "meta_published_before":
            api_filters["meta_published_before"] = value
        elif field == "entity_persons":
            api_filters["entity_persons"] = value
        elif field == "entity_organizations":
            api_filters["entity_organizations"] = value
        elif field == "entity_locations":
            api_filters["entity_locations"] = value
        # meta_source_exclude not directly supported in API — skip
    return api_filters


def simulate_soft_boost(
    baseline_hits: list[dict], filters: dict, boost_weight: float = 2.0
) -> list[dict]:
    """Simulate soft-boost: re-rank baseline results so filter-matching
    hits score higher, without excluding any results.

    This approximates what Lucene BooleanClause.SHOULD + BoostQuery
    would do: matching documents get a score boost, non-matching
    documents are retained at their original rank.
    """
    if not filters:
        return baseline_hits

    def matches_filter(hit: dict) -> bool:
        fields = hit.get("fields", {})
        for fk, fv in filters.items():
            if fk == "meta_source":
                hit_source = (fields.get("meta_source") or "").lower()
                if isinstance(fv, list):
                    if hit_source in [v.lower() for v in fv]:
                        return True
                elif isinstance(fv, str) and hit_source == fv.lower():
                    return True
            elif fk == "meta_category":
                hit_cat = (fields.get("meta_category") or "").lower()
                if isinstance(fv, list):
                    if hit_cat in [v.lower() for v in fv]:
                        return True
                elif isinstance(fv, str) and hit_cat == fv.lower():
                    return True
            elif fk == "meta_author":
                hit_auth = (fields.get("meta_author") or "").lower()
                if isinstance(fv, list):
                    if hit_auth in [v.lower() for v in fv]:
                        return True
                elif isinstance(fv, str) and hit_auth == fv.lower():
                    return True
        return False

    # Re-score: matching hits get boosted score
    scored = []
    for i, hit in enumerate(baseline_hits):
        base_score = hit.get("score", 1000 - i)
        if matches_filter(hit):
            scored.append((base_score * boost_weight, hit))
        else:
            scored.append((base_score, hit))

    # Sort by boosted score descending
    scored.sort(key=lambda x: x[0], reverse=True)
    return [hit for _, hit in scored]


def compute_per_query_metrics(
    qrels: dict[str, dict[str, int]],
    run: list[tuple[str, str, float]],
) -> dict[str, dict[str, float]]:
    """Compute per-query IR metrics. Returns {qid: {metric: value}}."""
    try:
        import ir_measures
        from ir_measures import nDCG, R

        ir_qrels = [
            ir_measures.Qrel(qid, did, rel)
            for qid, docs in qrels.items()
            for did, rel in docs.items()
        ]
        ir_run = [
            ir_measures.ScoredDoc(qid, did, score)
            for qid, did, score in run
        ]
        metrics = [nDCG @ 10, R @ 10]
        result: dict[str, dict[str, float]] = {}
        for m in ir_measures.iter_calc(metrics, ir_qrels, ir_run):
            qid = m.query_id
            result.setdefault(qid, {})[str(m.measure)] = float(m.value)
        return result
    except ImportError:
        return {}


def compute_metrics(
    qrels: dict[str, dict[str, int]],
    run: list[tuple[str, str, float]],
) -> dict[str, float]:
    """Compute IR metrics. Returns {metric: value}."""
    try:
        import ir_measures
        from ir_measures import AP, RR, nDCG, P, R

        ir_qrels = [
            ir_measures.Qrel(qid, did, rel)
            for qid, docs in qrels.items()
            for did, rel in docs.items()
        ]
        ir_run = [
            ir_measures.ScoredDoc(qid, did, score)
            for qid, did, score in run
        ]
        metrics = [nDCG @ 10, AP @ 10, RR @ 10, P @ 1, R @ 10]
        results = ir_measures.calc_aggregate(metrics, ir_qrels, ir_run)
        return {str(m): round(float(v), 4) for m, v in results.items()}
    except ImportError:
        log.warning("ir_measures not installed, computing basic recall only")
        # Fallback: compute recall@10 manually
        recall_sum = 0
        count = 0
        run_by_q = {}
        for qid, did, score in run:
            run_by_q.setdefault(qid, set()).add(did)
        for qid, rels in qrels.items():
            if qid in run_by_q:
                found = len(run_by_q[qid] & set(rels.keys()))
                recall_sum += found / max(len(rels), 1)
                count += 1
        return {"R@10": round(recall_sum / max(count, 1), 4)}


@click.command("qu-v3")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True)
@click.option("--llm-port", default=33221, show_default=True,
              help="Engine API port whose /v1 proxy serves QU extraction")
@click.option("--max-queries", default=50, type=int, help="Limit queries (0=all)")
@click.option("--retrieval-depth", default=10, type=int, help="How many docs to retrieve (10=shallow, 100=deep pool)")
@click.option("--json", "json_output", is_flag=True)
@click.option("-v", "--verbose", is_flag=True)
def qu_v3(base_url: str, llm_port: int, max_queries: int, retrieval_depth: int, json_output: bool, verbose: bool) -> None:
    """V3: measure whether extracted filters improve search results."""
    if verbose:
        logging.basicConfig(level=logging.DEBUG)
    else:
        logging.basicConfig(level=logging.INFO)

    # Load data
    title_map = load_title_map()
    all_queries = load_eval_queries()
    schema = load_schema()
    template = load_prompt_template(version=2)
    system_prompt = build_prompt(template, grounded=True)

    # Filter to queries with resolvable evidence (skip null_query)
    queries = [
        q for q in all_queries
        if q.get("evidence_titles")
        and all(t in title_map for t in q["evidence_titles"])
    ]
    if max_queries > 0:
        queries = queries[:max_queries]

    qrels = build_qrels(queries, title_map)

    top_k = retrieval_depth
    eval_k = 10  # always score at top-10

    if not json_output:
        print(f"V3 eval: {len(queries)} queries, depth={top_k}, backend={base_url}, llm={llm_port}")

    # Check backend health
    try:
        with httpx.Client(base_url=base_url, timeout=5.0) as c:
            r = c.get("/api/health")
            r.raise_for_status()
    except Exception as e:
        print(f"FATAL: Backend not reachable at {base_url}: {e}")
        sys.exit(1)

    # Run all conditions
    baseline_run = []     # A: raw query, no filters
    qu_run = []           # B: extracted query + hard filters
    boost_run = []        # C: raw query + soft boost (simulated)
    fallback_run = []     # D: hard filter with zero-result fallback

    for i, q in enumerate(queries):
        qid = f"q{i}"
        query_text = q["query"]

        if not json_output:
            print(f"  [{i+1}/{len(queries)}] {query_text[:60]}...", end=" ", flush=True)

        # Condition A: baseline (raw query, no filters)
        hits_a = search_api(base_url, query_text, limit=top_k)
        for rank, hit in enumerate(hits_a[:eval_k]):
            doc_id = resolve_doc_id(hit)
            baseline_run.append((qid, doc_id, 1000 - rank))

        # QU extraction
        refined_query, filters = extract_filters_for_query(
            query_text, llm_port, system_prompt, schema
        )
        api_filters = map_filters_to_api(filters)

        # Condition B: QU-enhanced (extracted query + hard filters)
        hits_b = search_api(base_url, refined_query, filters=api_filters if api_filters else None, limit=top_k)
        for rank, hit in enumerate(hits_b[:eval_k]):
            doc_id = resolve_doc_id(hit)
            qu_run.append((qid, doc_id, 1000 - rank))

        # Condition C: soft boost via real boostFilters API (363 Track A)
        hits_c = search_api(base_url, query_text, boost_filters=api_filters if api_filters else None, limit=top_k)
        if not hits_c:
            # Fallback to simulated boost if API doesn't support boostFilters yet
            hits_c = simulate_soft_boost(hits_a, api_filters)
        for rank, hit in enumerate(hits_c[:eval_k]):
            doc_id = resolve_doc_id(hit)
            boost_run.append((qid, doc_id, 1000 - rank))

        # Condition D: hard filter with zero-result fallback
        hits_d = hits_b if hits_b else hits_a
        for rank, hit in enumerate(hits_d[:eval_k]):
            doc_id = resolve_doc_id(hit)
            fallback_run.append((qid, doc_id, 1000 - rank))

        if not json_output:
            n_filters = len(api_filters)
            boosted = sum(1 for h in hits_c if h != hits_a[hits_c.index(h)] if hits_c.index(h) < len(hits_a)) if hits_c else 0
            print(f"A={len(hits_a)} B={len(hits_b)} C={len(hits_c)} filters={n_filters}")

    # Compute metrics
    metrics_a = compute_metrics(qrels, baseline_run)
    metrics_b = compute_metrics(qrels, qu_run)

    # Compute metrics for all conditions
    metrics_c = compute_metrics(qrels, boost_run)
    metrics_d = compute_metrics(qrels, fallback_run)

    # Identify zero-result queries
    qu_hits_by_q = {}
    for i, q in enumerate(queries):
        qid = f"q{i}"
        qu_hits_by_q[qid] = sum(1 for x in qu_run if x[0] == qid)

    zero_result_qids = {qid for qid, n in qu_hits_by_q.items() if n == 0}
    nonzero_qids = {qid for qid, n in qu_hits_by_q.items() if n > 0}

    # Filter runs to only non-zero queries
    baseline_nonzero = [x for x in baseline_run if x[0] in nonzero_qids]
    qu_nonzero = [x for x in qu_run if x[0] in nonzero_qids]
    qrels_nonzero = {k: v for k, v in qrels.items() if k in nonzero_qids}

    metrics_a_nz = compute_metrics(qrels_nonzero, baseline_nonzero) if qrels_nonzero else {}
    metrics_b_nz = compute_metrics(qrels_nonzero, qu_nonzero) if qrels_nonzero else {}

    # Per-query analysis: which queries improved, which degraded?
    per_query_a = compute_per_query_metrics(qrels, baseline_run)
    per_query_b = compute_per_query_metrics(qrels, qu_run)

    improved = 0
    degraded = 0
    neutral = 0
    for qid in per_query_a:
        a_ndcg = per_query_a[qid].get("nDCG@10", 0)
        b_ndcg = per_query_b.get(qid, {}).get("nDCG@10", 0)
        delta = b_ndcg - a_ndcg
        if delta > 0.01:
            improved += 1
        elif delta < -0.01:
            degraded += 1
        else:
            neutral += 1

    if json_output:
        result = {
            "queries": len(queries),
            "A_baseline": metrics_a,
            "B_hard_filter": metrics_b,
            "C_soft_boost": metrics_c,
            "D_hard_with_fallback": metrics_d,
            "zero_result_queries": len(zero_result_qids),
        }
        json.dump(result, sys.stdout, indent=2)
        print()
    else:
        print(f"\n{'='*70}")
        print("V3 RESULTS: 4 Conditions Compared")
        print(f"{'='*70}")
        print(f"  A = Baseline (raw query, no filters)")
        print(f"  B = Hard filter (extracted query + FILTER clause)")
        print(f"  C = Soft boost (raw query + simulated SHOULD boost)")
        print(f"  D = Hard filter + zero-result fallback")
        print(f"\n{'Metric':<10} {'A baseline':>11} {'B hard':>11} {'C boost':>11} {'D fallback':>11}")
        print(f"{'-'*56}")
        for m in sorted(metrics_a.keys()):
            a = metrics_a.get(m, 0)
            b = metrics_b.get(m, 0)
            c = metrics_c.get(m, 0)
            d = metrics_d.get(m, 0)
            print(f"{m:<10} {a:>11.4f} {b:>11.4f} {c:>11.4f} {d:>11.4f}")

        print(f"\nDeltas vs baseline (A):")
        print(f"{'Metric':<10} {'B-A':>11} {'C-A':>11} {'D-A':>11}")
        print(f"{'-'*44}")
        for m in sorted(metrics_a.keys()):
            a = metrics_a.get(m, 0)
            b = metrics_b.get(m, 0)
            c = metrics_c.get(m, 0)
            d = metrics_d.get(m, 0)
            def fmt(v):
                s = "+" if v > 0 else ""
                return f"{s}{v:.4f}"
            print(f"{m:<10} {fmt(b-a):>11} {fmt(c-a):>11} {fmt(d-a):>11}")

        print(f"\nZero-result queries (B): {len(zero_result_qids)}/{len(queries)}")
        print(f"Per-query nDCG@10 movement (B vs A): {improved} improved, {degraded} degraded, {neutral} neutral")


COMMANDS = [qu_v3]
