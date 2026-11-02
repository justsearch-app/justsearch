"""Old versus new, question by question (S15-S19).

Both arms are rescored from their delivered rankings against the same frozen labels. The order of
decisions is fixed:

1. INVALID — either arm is not a complete, valid result for this collection and candidate: wrong
   collection or label version, interrupted, zero queries, missing / duplicate / extra query ids,
   query text changed under the same id, failed queries, documents outside the manifest, an index
   population that is not exactly the manifest, a dirty tree, a candidate from another commit or
   model set, a stored baseline whose metrics disagree with its own rescoring, non-finite metrics.
2. INCONCLUSIVE — the arms differ in something other than the declared change (models, request
   settings, index key, index state such as freshness, enrichment or merge state), or — for a
   collection with pooled judgments only — the labels are too incomplete for this pair of
   rankings (the unjudged pairs to judge are listed). With official, fixed judgments an unjudged
   document counts as non-relevant and the unjudged share is reported, never changing the verdict.
3. Per group (fixed by query labels, never by input order): paired sign-flip randomization test
   on the per-query delta of the primary metric, Holm-adjusted over the collection's groups, a
   paired bootstrap 95% interval and d_z. A group *regressed* when the loss is significant and
   larger than its margin; *improved* when the gain is significant and larger than its margin;
   *non-inferior* when the interval excludes a loss larger than the margin; else *inconclusive*.
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
    RunRecordError,
    Verdict,
    atomic_write_text,
    is_finite_number,
    sha256_text,
)

ALL_GROUP = "all"


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
        if doc.get("schema") != BASELINE_SCHEMA:
            raise BaselineError(f"{p}: not a {BASELINE_SCHEMA} file")
        run_path = p.parent / doc["run_file"]
        text = run_path.read_text(encoding="utf-8")
    except (OSError, ValueError, KeyError) as e:
        raise BaselineError(f"{p}: {e}") from None
    if sha256_text(text) != doc["run_sha256"]:
        raise BaselineError(f"{p}: its run file {run_path} changed after the baseline was stored")
    return RunRecord.loads(text, str(run_path)), doc


# --------------------------------------------------------------------------- validity


def validate_run(c: Collection, run: RunRecord, label_versions: set[str], arm: str) -> list[str]:
    """Every reason this run is not a complete, valid result for collection ``c``."""
    h = run.header
    out = []
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
    pop = h.index_population
    if pop.get("count") != len(c.documents) or pop.get("manifest_sha256") != c.manifest_sha256:
        out.append(f"{arm}: index population {pop.get('count')} docs / manifest "
                   f"{str(pop.get('manifest_sha256'))[:12]} is not exactly the collection's "
                   f"{len(c.documents)} docs / {c.manifest_sha256[:12]}")
    if not run.results:
        out.append(f"{arm}: zero queries")
        return out
    seen: dict[str, int] = {}
    for r in run.results:
        seen[r.qid] = seen.get(r.qid, 0) + 1
    dup = sorted(q for q, n in seen.items() if n > 1)
    missing = sorted(set(c.queries) - set(seen))
    extra = sorted(set(seen) - set(c.queries))
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
    for k in sorted(set(b.index_state) | set(n.index_state)):
        if b.index_state.get(k) != n.index_state.get(k):
            diffs[f"index_state.{k}"] = f"{b.index_state.get(k)!r} -> {n.index_state.get(k)!r}"
    return diffs


def _is_intended(name: str, intended: Iterable[str]) -> bool:
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


def group_outcome(mean_delta: float, lo: float, p_adj: float, margin: float, alpha: float) -> str:
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
        return Comparison(Verdict(Outcome.INVALID, tuple(reasons)), c, cfg, baseline, candidate)

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
    if len(members) > 1:
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
        groups.append(GroupVerdict(g, len(ids), metric, b_mean, n_mean, delta, lo, hi, dz, p,
                                   adjusted[g], margin,
                                   group_outcome(delta, lo, adjusted[g], margin, cfg.alpha)))

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
    confounds = [f"{k}: {v}" for k, v in differences.items() if not _is_intended(k, intended)]
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
    if not reasons:
        regressed = [g for g in groups if g.outcome == GROUP_REGRESSED]
        undecided = [g for g in groups if g.outcome == GROUP_INCONCLUSIVE]
        if regressed:
            outcome = Outcome.REGRESSION
            reasons += [f"{g.group}: {metric} {g.mean_delta:+.4f} beyond margin {g.margin}"
                        for g in regressed]
        elif undecided:
            outcome = Outcome.INCONCLUSIVE
            reasons += [f"{g.group}: interval [{g.ci_low:+.4f}, {g.ci_high:+.4f}] admits a loss "
                        f"beyond margin {g.margin}" for g in undecided]
        else:
            outcome = Outcome.PASS
    return Comparison(Verdict(outcome, tuple(reasons), tuple(groups)), c, cfg, baseline,
                      candidate, per_query, differences, confounds, notes, unjudged, requests)


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
