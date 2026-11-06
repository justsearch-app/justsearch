"""Old versus new, question by question (S15-S19).

Both arms are rescored from their delivered rankings against the same frozen labels. The order of
decisions is fixed:

1. INVALID — either arm is not a complete, valid result for this collection and candidate: wrong
   collection or label version, interrupted, zero queries, missing / duplicate / extra query ids,
   query text changed under the same id, failed queries, queries served by fewer legs without
   explicit by-design trace evidence, retrieval/reranker drops, documents outside the manifest,
   an index population that is not exactly the
   manifest and its content, a dirty tree, a candidate from another commit or model set, a stored
   baseline whose metrics disagree with its own rescoring, non-finite metrics.
2. INCONCLUSIVE — the arms differ in something other than the declared change (models, request
   settings, budget, index key, fresh or cached index, index state such as freshness, enrichment
   or merge state, the legs or reranker status that served each query — the affected queries are
   named; identical by-design skips remain comparable), or — for a collection with
   pooled judgments only — the labels are too incomplete for this pair of rankings (the unjudged
   pairs to judge are listed), or — in a group of topical queries — either arm's own top-10
   unjudged share exceeds the pooled tolerance (shared unknowns count; never zero-filled). With official, fixed judgments an unjudged document counts as
   non-relevant and the unjudged share is reported, never changing the verdict.
3. Per group (fixed by query labels, never by input order): paired sign-flip randomization test
   on the per-query delta of the primary metric, Holm-adjusted over the collection's groups, a
   paired bootstrap 95% interval and d_z. A group *regressed* when the loss is significant and
   larger than its margin; *improved* when the gain is significant and larger than its margin;
   *non-inferior* when the interval excludes a loss larger than the margin; else *inconclusive*.
   A group smaller than the configured minimum is *inconclusive* unless it is the only group.
   Any regressed group makes the comparison REGRESSION; any inconclusive group INCONCLUSIVE; a
   gain elsewhere never offsets either.
"""

from __future__ import annotations

import json
import math
import os
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping

import numpy as np

from . import collections as qcoll
from . import scoring
from .collections import QualityConfig
from .types import (
    BASELINE_SCHEMA,
    GROUP_IMPROVED,
    GROUP_INCONCLUSIVE,
    GROUP_NON_INFERIOR,
    GROUP_REGRESSED,
    Collection,
    GroupVerdict,
    Outcome,
    QueryResult,
    RunHeader,
    RunRecord,
    Served,
    RunRecordError,
    Verdict,
    atomic_write_text,
    is_finite_number,
    sha256_text,
)

ALL_GROUP = "all"
# Topical queries ask for every file about a topic: their pooled labels are complete only near
# the pooled depth, so each arm's own unjudged share is bounded per topical group (design 8).
TOPICAL_KIND = "topical"


class BaselineError(ValueError):
    pass


# --------------------------------------------------------------------------- stored baseline


def write_baseline(run_path: str | os.PathLike, c: Collection,
                   out_path: str | os.PathLike | None = None) -> Path:
    """Score a run and store it as a baseline (its run file digest + per-query metrics)."""
    run_path = Path(run_path)
    text = run_path.read_text(encoding="utf-8")
    run = RunRecord.loads(text, str(run_path))
    problems = validate_run(c, run, {c.version}, "baseline")
    if problems:
        raise BaselineError("; ".join(problems))
    per_query = scoring.score_results(c.judgments, run.results)
    out = Path(out_path) if out_path else run_path.with_suffix(".baseline.json")
    doc = {"schema": BASELINE_SCHEMA, "run_file": os.path.relpath(run_path, out.parent),
           "run_sha256": sha256_text(text), "collection": c.name,
           "collection_version": c.version, "metrics": list(scoring.METRICS),
           "aggregate": scoring.aggregate(per_query), "per_query": per_query}
    atomic_write_text(out, json.dumps(doc, indent=1, sort_keys=True) + "\n")
    return out


def load_baseline(path: str | os.PathLike) -> tuple[RunRecord, dict]:
    p = Path(path)
    if not p.is_file():
        raise BaselineError(f"absent baseline: {p}")
    try:
        doc = json.loads(p.read_text(encoding="utf-8"))
        if not isinstance(doc, dict) or doc.get("schema") != BASELINE_SCHEMA:
            raise BaselineError(f"{p}: not a {BASELINE_SCHEMA} file")
        run_path = p.parent / doc["run_file"]
        text = run_path.read_text(encoding="utf-8")
        recorded = doc["run_sha256"]
    except BaselineError:
        raise
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise BaselineError(f"{p}: {type(e).__name__}: {e}") from None
    if sha256_text(text) != recorded:
        raise BaselineError(f"{p}: its run file {run_path} changed after the baseline was stored")
    record = RunRecord.loads(text, str(run_path))
    if record.header.run_kind == "sensitivity":
        raise BaselineError(f"{p}: sensitivity run cannot be a baseline")
    return record, doc


# --------------------------------------------------------------------------- validity


# modules/app-api/src/main/java/io/justsearch/app/api/knowledge/
# CrossEncoderSkipReason.java:33-48; isDrop():102-119 explicitly classifies these
# seven reasons as by-design. RerankSkipCause.java:36-66 has no legitimate skip:
# all its non-NONE causes are budget/loading/inference drops. Unknown stays invalid.
BY_DESIGN_RERANK_SKIPS = frozenset({
    "NAVIGATIONAL_QUERY", "DISABLED", "BELOW_MIN_THRESHOLD", "DOCS_TOO_LONG",
    "PIPELINE_NOT_ELIGIBLE", "MODEL_NOT_CONFIGURED", "FUSION_CONFIDENT", "QUERY_TOO_LONG",
})
# modules/worker-services/src/main/java/io/justsearch/indexerworker/services/
# respond/SearchTraceProjector.java:157-185 emits these routing selections;
# plan/SearchPlanner.java:222-236 emits the two deliberate dense-query shortcuts.
# PIPELINE_NOT_ELIGIBLE is a reranker reason, never evidence for a missing leg.
BY_DESIGN_LEG_SKIPS = frozenset({
    "sparse-shortcut", "not-selected", "dense-only", "splade-only", "dense-splade",
    "SKIPPED_SHORT_QUERY", "SKIPPED_NO_DISCRIMINATIVE_TERM",
})
# KnowledgeSearchController.java:527-529: a deliberate syntax refusal is the
# Search window's zero-result product outcome, not a failed evaluation request.
SYNTAX_REFUSAL = "SEARCH_SYNTAX_INVALID"


def syntax_refused(result: QueryResult) -> bool:
    return (result.error is None and not result.ranked
            and result.served == Served((), "skipped", SYNTAX_REFUSAL))


def validate_run(c: Collection, run: RunRecord, label_versions: set[str], arm: str,
                 *, sensitivity: bool = False) -> list[str]:
    """Every reason this run is not a complete, valid result for collection ``c``."""
    h = run.header
    out = []
    if h.run_kind == "sensitivity" and not sensitivity:
        out.append(f"{arm}: sensitivity run cannot be baseline or comparison evidence")
    if h.collection != c.name:
        out.append(f"{arm}: run is for collection {h.collection!r}, not {c.name!r}")
    if h.collection_version not in label_versions:
        out.append(f"{arm}: run used collection version {h.collection_version[:12]}, which is not "
                   f"{c.version[:12]} or one of its verified label ancestors (judgments, queries "
                   "or documents were altered)")
    if h.ended is None:
        out.append(f"{arm}: interrupted output (the run never recorded its end)")
    if h.dirty_hash is not None:
        out.append(f"{arm}: built from a dirty tree ({h.dirty_hash[:12]})")
    if h.index_state.get("settled") is False:
        out.append(f"{arm}: index is not settled")
    pop, want = h.index_population, c.index_population()
    if any(pop.get(k) != v for k, v in want.items()):
        out.append(f"{arm}: index population {pop.get('count')} docs / manifest "
                   f"{str(pop.get('manifest_sha256'))[:12]} / content "
                   f"{str(pop.get('content_sha256'))[:12]} is not exactly the collection's "
                   f"{want['count']} docs / {want['manifest_sha256'][:12]} / "
                   f"{want['content_sha256'][:12]}")
    if not run.results:
        out.append(f"{arm}: zero queries")
        return out
    seen: dict[str, int] = {}
    for r in run.results:
        seen[r.qid] = seen.get(r.qid, 0) + 1
    dup = sorted(q for q, n in seen.items() if n > 1)
    expected_queries = set(c.queries)
    if sensitivity and h.run_kind == "sensitivity":
        from .sensitivity import query_ids
        try:
            chosen = query_ids(c, h.sensitivity.get("sample_size"), h.sensitivity.get("seed"))
            expected_queries = set(chosen)
            if h.sensitivity.get("queries") != {q: c.queries[q].sha256 for q in chosen}:
                out.append(f"{arm}: sensitivity sample declaration differs from chosen queries")
        except ValueError as e:
            out.append(f"{arm}: invalid sensitivity sample: {e}")
    missing = sorted(expected_queries - set(seen))
    extra = sorted(set(seen) - expected_queries)
    for label, ids in (("duplicate", dup), ("missing", missing), ("unknown", extra)):
        if ids:
            out.append(f"{arm}: {len(ids)} {label} query ids ({', '.join(ids[:5])})")
    changed = sorted(r.qid for r in run.results
                     if r.qid in c.queries and r.query_text_sha256 != c.queries[r.qid].sha256)
    if changed:
        out.append(f"{arm}: query text changed under the same id for {len(changed)} queries "
                   f"({', '.join(changed[:5])})")
    failed = [r for r in run.results if r.error is not None]
    if failed:
        out.append(f"{arm}: {len(failed)} queries failed (e.g. {failed[0].qid}: {failed[0].error})")
    short = sorted(r.qid for r in run.results
                   if r.error is None and not syntax_refused(r)
                   and any(r.served.leg_details.get(leg, {}).get('status') != 'skipped'
                           or r.served.leg_details[leg].get('reason') not in BY_DESIGN_LEG_SKIPS
                           for leg in set(h.expected_legs) - set(r.served.legs)))
    if short:
        out.append(f"{arm}: {len(short)} queries were served by fewer legs than the declared "
                   f"{', '.join(h.expected_legs)} ({_ids(short)})")
    for r in run.results:
        if r.error is not None or syntax_refused(r):
            continue
        for leg, detail in r.served.leg_details.items():
            if (detail['status'] == 'executed') != (leg in r.served.legs):
                out.append(f"{arm}: {r.qid}: {leg} trace disagrees with served legs")
            elif detail['status'] != 'executed' and detail.get('reason') not in BY_DESIGN_LEG_SKIPS:
                out.append(f"{arm}: {r.qid}: {leg} dropped: {detail.get('reason') or 'missing reason'}")
    drops = [r for r in run.results if r.error is None
             and not syntax_refused(r)
             and r.served.reranker == "skipped"
             and r.served.reason not in (BY_DESIGN_RERANK_SKIPS |
                 ({"DISABLED", "DEADLINE_EXCEEDED", "SEARCH_DEADLINE_EXCEEDED"}
                  if sensitivity and h.run_kind == "sensitivity" else set()))]
    if drops:
        out.append(f"{arm}: reranker dropped for {len(drops)} queries: " + "; ".join(
            f"{r.qid}: {r.served.reason or 'missing reason'}" for r in drops))
    outside = sorted({d for r in run.results for d in r.ranked if d not in c.documents})
    if outside:
        out.append(f"{arm}: {len(outside)} delivered documents are not in the manifest "
                   f"({', '.join(outside[:3])})")
    twice = [r.qid for r in run.results if len(set(r.ranked)) != len(r.ranked)]
    if twice:
        out.append(f"{arm}: {len(twice)} rankings deliver a document twice ({twice[0]})")
    return out


def identity_differences(b: RunHeader, n: RunHeader) -> dict[str, str]:
    """Named differences between the arms' effective inputs and index state."""
    diffs: dict[str, str] = {}
    if b.commit != n.commit:
        diffs["commit"] = f"{b.commit[:12]} -> {n.commit[:12]}"
    if b.index_key != n.index_key:
        diffs["index_key"] = f"{b.index_key} -> {n.index_key}"
    if b.request_config_sha256 != n.request_config_sha256:
        keys = sorted(k for k in set(b.request_config) | set(n.request_config)
                      if b.request_config.get(k) != n.request_config.get(k))
        diffs["request_config"] = "differs in " + ", ".join(keys)
    model_keys = sorted(k for k in set(b.model_hashes) | set(n.model_hashes)
                        if b.model_hashes.get(k) != n.model_hashes.get(k))
    if model_keys:
        diffs["models"] = "differ in " + ", ".join(model_keys)
    if b.index_build != n.index_build:
        diffs["index_build"] = f"{b.index_build} -> {n.index_build}"
    for name in ("index_state", "budget"):
        bm, nm = getattr(b, name), getattr(n, name)
        for k in sorted(set(bm) | set(nm)):
            # Adoption already checks expected lexical hits. Probe ordering is
            # query-time output, retained as evidence and compared only by the
            # separate same-code lifecycle agreement command.
            if name == "index_state" and k == "probes":
                continue
            if bm.get(k) != nm.get(k):
                diffs[f"{name}.{k}"] = f"{bm.get(k)!r} -> {nm.get(k)!r}"
    if (set(b.expected_legs), b.expected_reranker) != (set(n.expected_legs), n.expected_reranker):
        diffs["served.expected"] = (f"legs {'+'.join(b.expected_legs)} reranker "
                                    f"{b.expected_reranker} -> legs {'+'.join(n.expected_legs)} "
                                    f"reranker {n.expected_reranker}")
    return diffs


def served_differences(baseline: RunRecord, candidate: RunRecord) -> dict[str, list[str]]:
    """Queries served differently between the arms, or short of their arm's declared reranker
    use: ``served.legs`` (including each leg's trace status/reason) / ``served.reranker`` and
    ``served.reranker_skipped`` (declared ``executed``, skipped in at least one arm)."""
    b = {r.qid: r.served for r in baseline.results}
    n = {r.qid: r.served for r in candidate.results}
    # Refusals have no execution trace. Their quality change is scored as zero
    # results, rather than treated as an uncontrolled execution difference.
    refusals = {r.qid for arm in (baseline, candidate) for r in arm.results if syntax_refused(r)}
    qids = sorted((set(b) & set(n)) - refusals)
    out = {
        "served.legs": [q for q in qids if set(b[q].legs) != set(n[q].legs)
                        or b[q].leg_details != n[q].leg_details],
        "served.reranker": [q for q in qids if (b[q].reranker, b[q].reason) != (n[q].reranker, n[q].reason)],
        "served.reranker_skipped": [
            q for q in qids
            if not (b[q].reranker == n[q].reranker == "skipped"
                    and b[q].reason == n[q].reason
                    and b[q].reason in BY_DESIGN_RERANK_SKIPS)
            and ((baseline.header.expected_reranker == "executed" and b[q].reranker != "executed")
            or (candidate.header.expected_reranker == "executed" and n[q].reranker != "executed"))],
    }
    return {k: v for k, v in out.items() if v}


def _ids(qids: list[str], show: int = 10) -> str:
    return ", ".join(qids[:show]) + (f", +{len(qids) - show} more" if len(qids) > show else "")


def _is_intended(name: str, intended: Iterable[str]) -> bool:
    if name in {"index_state.index_cache_key", "index_state.generation"} and "index_key" in intended:
        return True
    return any(name == i or name.startswith(i + ".") for i in intended)


# --------------------------------------------------------------------------- statistics


def holm(p_values: Mapping[str, float]) -> dict[str, float]:
    order = sorted(p_values, key=lambda g: (p_values[g], g))
    m = len(order)
    out: dict[str, float] = {}
    running = 0.0
    for i, g in enumerate(order):
        running = max(running, min(1.0, (m - i) * p_values[g]))
        out[g] = running
    return out


def paired_stats(deltas: np.ndarray, resamples: int, seed: int, key: str):
    """(bootstrap 95% interval low, high, two-sided sign-flip p, d_z) of the mean delta."""
    rng = np.random.default_rng([seed, zlib.crc32(key.encode("utf-8"))])
    n = len(deltas)
    obs = float(deltas.mean())
    idx = rng.integers(0, n, size=(resamples, n))
    boot = deltas[idx].mean(axis=1)
    lo, hi = (float(x) for x in np.quantile(boot, [0.025, 0.975]))
    signs = rng.choice(np.array([-1.0, 1.0]), size=(resamples, n))
    flipped = np.abs((signs * deltas).mean(axis=1))
    p = float((1 + np.count_nonzero(flipped >= abs(obs) - 1e-12)) / (resamples + 1))
    sd = float(deltas.std(ddof=1)) if n > 1 else 0.0
    dz = obs / sd if sd > 0 else None
    return lo, hi, p, dz


def group_outcome(mean_delta: float, lo: float, p_adj: float, margin: float, alpha: float,
                  too_small: bool = False) -> str:
    if too_small:
        return GROUP_INCONCLUSIVE
    if p_adj < alpha and mean_delta < -margin:
        return GROUP_REGRESSED
    if p_adj < alpha and mean_delta > margin:
        return GROUP_IMPROVED
    if lo >= -margin:
        return GROUP_NON_INFERIOR
    return GROUP_INCONCLUSIVE


# --------------------------------------------------------------------------- comparison


@dataclass
class Comparison:
    verdict: Verdict
    collection: Collection
    config: QualityConfig
    baseline: RunRecord | None
    candidate: RunRecord | None
    per_query: dict[str, dict[str, Any]] = field(default_factory=dict)
    differences: dict[str, str] = field(default_factory=dict)
    confounds: list[str] = field(default_factory=list)
    affected: dict[str, list[str]] = field(default_factory=dict)  # per-query confound -> qids
    notes: list[str] = field(default_factory=list)
    unjudged: dict[str, Any] = field(default_factory=dict)
    judgment_requests: list[tuple[str, str]] = field(default_factory=list)

    def results(self, arm: str) -> dict[str, QueryResult]:
        run = self.baseline if arm == "baseline" else self.candidate
        return {r.qid: r for r in run.results} if run else {}


def compare(c: Collection, cfg: QualityConfig, baseline: RunRecord | None,
            candidate: RunRecord | None, *, baseline_metrics: Mapping | None = None,
            intended: Iterable[str] = ("commit",),
            expect_commit: str | None = None,
            expect_models: Mapping[str, str] | None = None,
            invalid: Iterable[str] = ()) -> Comparison:
    intended = tuple(intended)
    reasons = list(invalid)
    if baseline is None:
        reasons.append("absent baseline")
    if candidate is None:
        reasons.append("absent candidate")
    try:
        versions = {c.version, *qcoll.ancestor_versions(c)}
    except (qcoll.CollectionError, KeyError) as e:
        versions = {c.version}
        reasons.append(f"label lineage cannot be verified: {e}")
    if baseline is not None:
        reasons += validate_run(c, baseline, versions, "baseline")
    if candidate is not None:
        reasons += validate_run(c, candidate, versions, "candidate")
        h = candidate.header
        if expect_commit is not None and h.commit != expect_commit:
            reasons.append(f"candidate: result is from commit {h.commit[:12]}, not the candidate "
                           f"{expect_commit[:12]}")
        if expect_models is not None and dict(h.model_hashes) != dict(expect_models):
            reasons.append("candidate: result is from another model set")
    if reasons:
        affected = served_differences(baseline, candidate) if baseline and candidate else {}
        return Comparison(Verdict(Outcome.INVALID, tuple(reasons)), c, cfg, baseline, candidate,
                          affected=affected)

    notes = []
    if baseline.header.collection_version != c.version or \
            candidate.header.collection_version != c.version:
        notes.append(f"both arms rescored against updated labels {c.version[:12]}")
    if dict(baseline.header.hardware) != dict(candidate.header.hardware):
        notes.append("hardware differs between the arms")

    try:
        base_pq = scoring.score_results(c.judgments, baseline.results)
        cand_pq = scoring.score_results(c.judgments, candidate.results)
    except scoring.ScoringError as e:
        return Comparison(Verdict(Outcome.INVALID, (str(e),)), c, cfg, baseline, candidate)
    if baseline_metrics is not None:
        reasons += _check_stored_metrics(baseline_metrics, base_pq, c)
    for arm, pq in (("baseline", base_pq), ("candidate", cand_pq)):
        bad = [q for q, m in pq.items() if not all(is_finite_number(v) for v in m.values())]
        if bad:
            reasons.append(f"{arm}: non-finite metrics for {len(bad)} queries")
    if reasons:
        return Comparison(Verdict(Outcome.INVALID, tuple(reasons)), c, cfg, baseline, candidate)

    metric = cfg.metric
    qids = sorted(c.queries)
    per_query = {q: {"baseline": base_pq[q], "candidate": cand_pq[q],
                     "delta": cand_pq[q][metric] - base_pq[q][metric],
                     "group": qcoll.query_group(c, q, cfg.groups)} for q in qids}

    members: dict[str, list[str]] = {}
    for q in qids:
        members.setdefault(per_query[q]["group"], []).append(q)
    several = len(members) > 1
    if several:
        members = {ALL_GROUP: qids, **members}
    stats = {}
    for g, ids in members.items():
        d = np.array([per_query[q]["delta"] for q in ids], dtype=float)
        stats[g] = (ids, *paired_stats(d, cfg.bootstrap_resamples, cfg.seed, g))
    adjusted = holm({g: s[3] for g, s in stats.items()})
    groups = []
    for g, (ids, lo, hi, p, dz) in stats.items():
        b_mean = scoring.mean(base_pq, metric, ids)
        n_mean = scoring.mean(cand_pq, metric, ids)
        delta = math.fsum(per_query[q]["delta"] for q in ids) / len(ids)
        margin = cfg.margin(g)
        too_small = several and g != ALL_GROUP and len(ids) < cfg.min_group_size
        groups.append(GroupVerdict(g, len(ids), metric, b_mean, n_mean, delta, lo, hi, dz, p,
                                   adjusted[g], margin,
                                   group_outcome(delta, lo, adjusted[g], margin, cfg.alpha,
                                                 too_small)))

    # Unjudged top-10 share per arm; pairs in only one arm's top 10 decide whether labels suffice.
    b_unj, b_slots, b_pairs = scoring.unjudged_top(c.judgments, baseline.results)
    n_unj, n_slots, n_pairs = scoring.unjudged_top(c.judgments, candidate.results)
    differing = b_pairs ^ n_pairs
    differing_share = len(differing) / max(1, b_slots + n_slots)
    pooled = cfg.judgments == qcoll.JUDGMENTS_POOLED
    unjudged = {"baseline_share": b_unj / max(1, b_slots), "candidate_share": n_unj / max(1, n_slots),
                "differing_pairs": len(differing), "differing_share": differing_share,
                "judgments": cfg.judgments,
                "threshold": cfg.max_differing_unjudged_share if pooled else None}
    requests: list[tuple[str, str]] = []

    differences = identity_differences(baseline.header, candidate.header)
    affected = served_differences(baseline, candidate)
    for k, ids in affected.items():
        what = ("reranker skipped where its arm declared it executed" if k.endswith("_skipped")
                else "differs")
        differences[k] = f"{what} on {len(ids)} queries ({_ids(ids)})"
    # a skipped declared reranker is a shortfall of the arm itself, never an intended change
    affected = {k: v for k, v in affected.items()
                if k.endswith("_skipped") or not _is_intended(k, intended)}
    confounds = [f"{k}: {v}" for k, v in differences.items()
                 if not _is_intended(k, intended) or k in affected]
    reasons = []
    if confounds:
        outcome = Outcome.INCONCLUSIVE
        reasons.append("confounded: the arms differ in more than the declared change ("
                       + "; ".join(confounds) + ")")
    if pooled and differing_share > cfg.max_differing_unjudged_share:
        outcome = Outcome.INCONCLUSIVE
        requests = sorted(b_pairs | n_pairs)
        reasons.append(f"labels incomplete: {differing_share:.1%} of top-10 pairs are unjudged in "
                       f"only one arm (threshold {cfg.max_differing_unjudged_share:.0%}); "
                       f"{len(requests)} unjudged pairs need judgment, then rescore both arms")
    topical = topical_unjudged(c, cfg, baseline.results, candidate.results)
    if topical:
        unjudged["topical_groups"] = {g: {k: v for k, v in s.items() if k != "pairs"}
                                      for g, s in topical.items()}
    over = {g: s for g, s in topical.items()
            if max(s["baseline_share"], s["candidate_share"]) > cfg.max_differing_unjudged_share}
    if over:
        outcome = Outcome.INCONCLUSIVE
        pairs = {p for s in over.values() for p in s["pairs"]}
        requests = sorted(set(requests) | pairs)
        reasons += [f"labels incomplete in topical group {g}: top-10 unjudged share baseline "
                    f"{s['baseline_share']:.1%}, candidate {s['candidate_share']:.1%} (threshold "
                    f"{cfg.max_differing_unjudged_share:.0%} per arm, shared unknowns included)"
                    for g, s in sorted(over.items())]
        reasons.append(f"{len(pairs)} unjudged topical pairs need judgment, then rescore both arms")
    if not reasons:
        regressed = [g for g in groups if g.outcome == GROUP_REGRESSED]
        undecided = [g for g in groups if g.outcome == GROUP_INCONCLUSIVE]
        if regressed:
            outcome = Outcome.REGRESSION
            reasons += [f"{g.group}: {metric} {g.mean_delta:+.4f} beyond margin {g.margin}"
                        for g in regressed]
        elif undecided:
            outcome = Outcome.INCONCLUSIVE
            reasons += [f"{g.group}: n={g.n} is below the minimum group size {cfg.min_group_size}"
                        if several and g.group != ALL_GROUP and g.n < cfg.min_group_size else
                        f"{g.group}: interval [{g.ci_low:+.4f}, {g.ci_high:+.4f}] admits a loss "
                        f"beyond margin {g.margin}" for g in undecided]
        else:
            outcome = Outcome.PASS
    return Comparison(Verdict(outcome, tuple(reasons), tuple(groups)), c, cfg, baseline,
                      candidate, per_query, differences, confounds, affected, notes, unjudged,
                      requests)


def topical_unjudged(c: Collection, cfg: QualityConfig, baseline: Iterable[QueryResult],
                     candidate: Iterable[QueryResult]) -> dict[str, dict]:
    """Per topical group, each arm's own top-10 unjudged share (unknowns both arms share count)."""
    topical = {q for q, t in c.queries.items() if t.groups.get("kind") == TOPICAL_KIND}
    out: dict[str, dict] = {}
    for arm, results in (("baseline", baseline), ("candidate", candidate)):
        by_group: dict[str, list[QueryResult]] = {}
        for r in results:
            if r.qid in topical:
                by_group.setdefault(qcoll.query_group(c, r.qid, cfg.groups), []).append(r)
        for g, rs in by_group.items():
            unj, slots, pairs = scoring.unjudged_top(c.judgments, rs)
            entry = out.setdefault(g, {"baseline_share": 0.0, "candidate_share": 0.0, "pairs": []})
            entry[f"{arm}_share"] = unj / max(1, slots)
            entry["pairs"] = sorted(set(entry["pairs"]) | pairs)
    return out


def _check_stored_metrics(stored: Mapping, rescored: Mapping, c: Collection) -> list[str]:
    if stored.get("collection_version") != c.version:
        return []  # stored against older labels: the rescoring above is the one that counts
    sq = stored.get("per_query") or {}
    if set(sq) != set(rescored):
        return ["baseline: stored metrics cover a different query set than its run"]
    for q, m in rescored.items():
        for k, v in m.items():
            s = sq[q].get(k)
            if not is_finite_number(s) or abs(s - v) > 1e-12:
                return [f"baseline: stored {k} for {q} ({s!r}) disagrees with rescoring ({v!r})"]
    return []


def load_run_or_reason(path: str | os.PathLike | None, arm: str) -> tuple[RunRecord | None, list[str]]:
    if path is None or not Path(path).is_file():
        return None, [f"absent {arm}: {path}"]
    try:
        return RunRecord.read(path), []
    except (RunRecordError, OSError) as e:
        return None, [f"{arm}: {e}"]
