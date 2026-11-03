"""Per-query scoring of delivered rankings with ir_measures.

Metrics: nDCG@10 (linear gain, ideal from every judged grade), RR@10, Success@10, R@10. A ranking
is scored in its delivered order (``QueryResult.ranked``); unjudged documents count as
non-relevant and are reported separately as the unjudged share of the top 10.

A query that delivered nothing scores 0 on every metric — that is its real score. A query that is
missing, errored, unjudged or scored non-finite is never filled in: scoring refuses it.
"""

from __future__ import annotations

import math
from typing import Iterable, Mapping

import ir_measures
from ir_measures import R, RR, Success, nDCG

from .types import QueryResult

K = 10
MEASURES = (nDCG @ K, RR @ K, Success @ K, R @ K)
METRICS = tuple(str(m) for m in MEASURES)  # ("nDCG@10", "RR@10", "Success@10", "R@10")


class ScoringError(ValueError):
    """Results that cannot be scored as given."""


def score_rankings(judgments: Mapping[str, Mapping[str, int]],
                   rankings: Mapping[str, Iterable[str]]) -> dict[str, dict[str, float]]:
    """``{qid: [doc_id, ...]}`` in delivered order -> ``{qid: {metric: value}}``."""
    qrels = []
    run = []
    out: dict[str, dict[str, float]] = {}
    for qid, ranked in rankings.items():
        ranked = list(ranked)
        docs = judgments.get(qid)
        if not docs or not any(g > 0 for g in docs.values()):
            raise ScoringError(f"query {qid} has no relevant judgment; its metrics are undefined")
        if len(set(ranked)) != len(ranked):
            raise ScoringError(f"query {qid}: a document appears twice in the ranking")
        if not ranked:
            out[qid] = {m: 0.0 for m in METRICS}
            continue
        qrels.extend(ir_measures.Qrel(qid, d, g) for d, g in docs.items())
        # Strictly decreasing scores reproduce the delivered order exactly (no tie-breaking).
        run.extend(ir_measures.ScoredDoc(qid, d, float(len(ranked) - i))
                   for i, d in enumerate(ranked))
    for m in ir_measures.iter_calc(list(MEASURES), qrels, run):
        out.setdefault(m.query_id, {})[str(m.measure)] = float(m.value)
    for qid in rankings:
        got = out.get(qid, {})
        if set(got) != set(METRICS) or not all(math.isfinite(v) for v in got.values()):
            raise ScoringError(f"query {qid}: incomplete or non-finite metrics {got}")
    return out


def score_results(judgments: Mapping[str, Mapping[str, int]],
                  results: Iterable[QueryResult]) -> dict[str, dict[str, float]]:
    rankings: dict[str, tuple[str, ...]] = {}
    for r in results:
        if r.error is not None:
            raise ScoringError(f"query {r.qid} failed ({r.error}); a failed query is not scored")
        if r.qid in rankings:
            raise ScoringError(f"query {r.qid} appears twice")
        rankings[r.qid] = r.ranked
    if not rankings:
        raise ScoringError("no queries to score")
    return score_rankings(judgments, rankings)


def mean(per_query: Mapping[str, Mapping[str, float]], metric: str,
         qids: Iterable[str] | None = None) -> float:
    ids = list(per_query if qids is None else qids)
    if not ids:
        raise ScoringError("mean over zero queries")
    return math.fsum(per_query[q][metric] for q in ids) / len(ids)


def aggregate(per_query: Mapping[str, Mapping[str, float]]) -> dict[str, float]:
    return {m: mean(per_query, m) for m in METRICS}


def unjudged_top(judgments: Mapping[str, Mapping[str, int]],
                 results: Iterable[QueryResult], k: int = K) -> tuple[int, int, set[tuple[str, str]]]:
    """(unjudged slots, total slots, unjudged (qid, doc_id) pairs) over each query's top ``k``."""
    slots = unjudged = 0
    pairs: set[tuple[str, str]] = set()
    for r in results:
        docs = judgments.get(r.qid, {})
        for d in r.ranked[:k]:
            slots += 1
            if d not in docs:
                unjudged += 1
                pairs.add((r.qid, d))
    return unjudged, slots, pairs
