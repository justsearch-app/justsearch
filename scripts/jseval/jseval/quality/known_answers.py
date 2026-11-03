"""Known-answer checks (S10): run before any scoring, on every collection; any failure stops.

1. Counts: queries, judgments, positives, grades and documents equal the values pinned in the
   collection's config (taken from the official source), so a dropped positive is caught even when
   the stored digests were recomputed.
2. Integrity: every query has a relevant judgment, judgments and queries cover the same ids, and
   every judged document is in the manifest.
3. Constructed rankings through the real scorer, per query: the ideal grade order scores 1.0, an
   all non-relevant ranking scores 0, and a mixed ranking matches an independent closed-form
   computation; plus one fixed ranking whose value was computed by hand.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Mapping

from . import scoring
from .collections import QualityConfig
from .types import Collection

TOLERANCE = 1e-12

# Hand-computed (linear gain, log2 discount): judgments {a: 2, b: 1, c: 0}, ranking [c, b, x, a]
#   DCG@10  = 0/log2(2) + 1/log2(3) + 0/log2(4) + 2/log2(5) = 0.63092975 + 0.86135312 = 1.49228287
#   IDCG@10 = 2/log2(2) + 1/log2(3)                          = 2 + 0.63092975        = 2.63092975
#   nDCG@10 = 1.49228287 / 2.63092975                        = 0.56720742 (double: below)
#   RR@10 = 1/2 (b at rank 2), Success@10 = 1, R@10 = 2 of 2 relevant = 1
HAND_JUDGMENTS = {"ka": {"a": 2, "b": 1, "c": 0}}
HAND_RANKING = ["c", "b", "x", "a"]
HAND_EXPECTED = {"nDCG@10": 0.5672074169568709, "RR@10": 0.5, "Success@10": 1.0, "R@10": 1.0}


class KnownAnswerFailure(RuntimeError):
    def __init__(self, collection: str, reasons: list[str]):
        self.reasons = reasons
        super().__init__(f"{collection}: known-answer checks failed: " + "; ".join(reasons[:5])
                         + (f" (+{len(reasons) - 5} more)" if len(reasons) > 5 else ""))


@dataclass(frozen=True)
class KnownAnswerReport:
    collection: str
    version: str
    checks: int
    counts: Mapping


def reference_metrics(grades: Mapping[str, int], ranking: list[str], k: int = 10) -> dict:
    """Closed-form metrics, independent of ir_measures (used to check it)."""
    top = ranking[:k]
    dcg = math.fsum(grades.get(d, 0) / math.log2(i + 2) for i, d in enumerate(top))
    ideal = sorted((g for g in grades.values() if g > 0), reverse=True)[:k]
    idcg = math.fsum(g / math.log2(i + 2) for i, g in enumerate(ideal))
    rel = [i for i, d in enumerate(top) if grades.get(d, 0) > 0]
    n_rel = sum(1 for g in grades.values() if g > 0)
    return {"nDCG@10": dcg / idcg, "RR@10": 1.0 / (rel[0] + 1) if rel else 0.0,
            "Success@10": 1.0 if rel else 0.0, "R@10": len(rel) / n_rel}


def _constructed(c: Collection) -> dict[str, dict[str, list[str]]]:
    """Per kind, per query: a ranking built only from the query's own judgments + manifest."""
    non_relevant_pool = sorted(c.documents)
    ideal, nonrel, mixed = {}, {}, {}
    for qid, docs in c.judgments.items():
        positives = sorted((d for d, g in docs.items() if g > 0), key=lambda d: (-docs[d], d))
        ideal[qid] = positives[:scoring.K]
        negatives = sorted(d for d, g in docs.items() if g <= 0)
        for d in non_relevant_pool:
            if len(negatives) >= scoring.K:
                break
            if d not in docs:
                negatives.append(d)
        nonrel[qid] = negatives[:scoring.K]
        # mixed: non-relevant, best positive, two non-relevant, the remaining positives (reversed)
        m = negatives[:1] + positives[:1] + negatives[1:3] + positives[1:][::-1]
        mixed[qid] = m
    return {"ideal": ideal, "non-relevant": nonrel, "mixed": mixed}


def check(c: Collection, cfg: QualityConfig) -> KnownAnswerReport:
    reasons: list[str] = []
    counts = c.counts()
    for key, want in cfg.expected.items():
        if counts.get(key) != want:
            reasons.append(f"expected {key} {want}, found {counts.get(key)}")
    if c.name != cfg.collection:
        reasons.append(f"collection is {c.name!r}, config is for {cfg.collection!r}")
    if not c.queries:
        reasons.append("zero queries")
    if set(c.queries) != set(c.judgments):
        reasons.append(f"{len(set(c.queries) ^ set(c.judgments))} query ids are in only one of "
                       "queries and judgments")
    no_pos = sorted(q for q, d in c.judgments.items() if not any(g > 0 for g in d.values()))
    if no_pos:
        reasons.append(f"{len(no_pos)} queries have no relevant judgment (e.g. {no_pos[0]})")
    missing = sorted({d for docs in c.judgments.values() for d in docs} - set(c.documents))
    if missing:
        reasons.append(f"{len(missing)} judged documents are not in the manifest (e.g. {missing[0]})")
    if reasons:
        raise KnownAnswerFailure(c.name, reasons)

    checks = 0
    got = scoring.score_rankings(HAND_JUDGMENTS, {"ka": HAND_RANKING})["ka"]
    for metric, want in HAND_EXPECTED.items():
        checks += 1
        if abs(got[metric] - want) > TOLERANCE:
            reasons.append(f"hand-computed ranking: {metric} {got[metric]!r} != {want!r}")
    for kind, rankings in _constructed(c).items():
        scored = scoring.score_rankings(c.judgments, rankings)
        for qid, ranking in rankings.items():
            ref = reference_metrics(c.judgments[qid], ranking)
            if kind == "ideal" and ref["nDCG@10"] != 1.0:
                reasons.append(f"{qid}: ideal ranking reference nDCG {ref['nDCG@10']!r}")
            if kind == "non-relevant" and any(ref.values()):
                reasons.append(f"{qid}: non-relevant ranking reference {ref}")
            for metric in scoring.METRICS:
                checks += 1
                if abs(scored[qid][metric] - ref[metric]) > TOLERANCE:
                    reasons.append(f"{qid} {kind}: {metric} scored {scored[qid][metric]!r}, "
                                   f"expected {ref[metric]!r}")
            if kind == "ideal" and abs(scored[qid]["nDCG@10"] - 1.0) > TOLERANCE:
                reasons.append(f"{qid}: ideal ranking scored nDCG@10 {scored[qid]['nDCG@10']!r}")
            if kind == "non-relevant" and any(scored[qid].values()):
                reasons.append(f"{qid}: non-relevant ranking scored {scored[qid]}")
    if reasons:
        raise KnownAnswerFailure(c.name, reasons)
    return KnownAnswerReport(c.name, c.version, checks, counts)
