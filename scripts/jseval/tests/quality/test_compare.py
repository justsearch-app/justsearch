"""Old versus new (S15-S19)."""

from __future__ import annotations

import math
from dataclasses import replace

import numpy as np
import pytest

from jseval.quality import collections as qc
from jseval.quality import compare as qcmp
from jseval.quality import legacy
from jseval.quality.types import Outcome, QueryResult

from .helpers import SCIFACT_RUN, config_for, have_ir, make_collection, make_run, with_results


@pytest.fixture
def c():
    return make_collection({"en/vague": 60})


def _run(c, quality="good", **kw):
    return make_run(c, quality, **kw)


def _worse(c, every=10):
    """Every ``every``-th query's top result replaced by a non-relevant document."""
    return {q: ("bad" if i % every == 0 else "good") for i, q in enumerate(sorted(c.queries))}


def test_identical_runs_pass_with_zero_deltas(c):
    cmp = qcmp.compare(c, config_for(c), _run(c), _run(c, commit="b" * 40))
    assert cmp.verdict.outcome is Outcome.PASS and cmp.verdict.exit_code == 0
    assert all(row["delta"] == 0 for row in cmp.per_query.values())
    (g,) = cmp.verdict.groups
    assert (g.mean_delta, g.ci_low, g.ci_high, g.p_value, g.effect_size_dz) == (0, 0, 0, 1.0, None)
    assert g.outcome == "non-inferior"


def test_ten_percent_of_top_results_swapped_is_a_regression():
    c = make_collection({"en/vague": 300}, n_docs=400)
    cmp = qcmp.compare(c, config_for(c), _run(c), _run(c, _worse(c), commit="b" * 40))
    assert cmp.verdict.outcome is Outcome.REGRESSION and cmp.verdict.exit_code == 1
    (g,) = cmp.verdict.groups
    assert g.mean_delta < -g.margin and g.ci_high < 0 and g.p_adjusted < 0.05


@pytest.mark.skipif(not SCIFACT_RUN.exists() or not have_ir("beir", "scifact"),
                    reason="today's scifact run or beir/scifact cache not present")
def test_real_scifact_run_with_ten_percent_top_results_swapped_regresses():
    cfg = qc.load_config("scifact")
    col = qc.build(cfg)
    run = legacy.run_from_harness(SCIFACT_RUN, col, "hybrid")
    # make the legacy record a valid arm for this collection (its index predates the manifest)
    header = replace(run.header, index_population={"count": len(col.documents),
                                                   "manifest_sha256": col.manifest_sha256})
    base = replace(run, header=header)
    swapped = []
    for i, r in enumerate(base.results):
        if i % 10 == 0:
            j = col.judgments[r.qid]
            filler = next(d for d in sorted(col.documents) if d not in j and d not in r.ranked)
            r = replace(r, ranked=(filler,) + r.ranked[1:])
        swapped.append(r)
    cand = replace(base, header=replace(header, commit="f" * 40), results=tuple(swapped))
    same = qcmp.compare(col, cfg, base, replace(base, header=replace(header, commit="f" * 40)))
    assert same.verdict.outcome is Outcome.PASS
    cmp = qcmp.compare(col, cfg, base, cand)
    assert cmp.verdict.outcome is Outcome.REGRESSION, cmp.verdict.reasons
    assert cmp.unjudged["differing_share"] < cfg.max_differing_unjudged_share


def test_gain_in_one_group_cannot_hide_a_loss_in_another():
    c = make_collection({"en/known-item": 80, "de/vague": 80}, n_docs=300)
    # baseline: en bad / de good; candidate: en good / de bad on half of its queries
    base_q, cand_q = {}, {}
    for i, q in enumerate(sorted(c.queries)):
        en = c.queries[q].groups["lang"] == "en"
        base_q[q] = "bad" if en else "good"
        cand_q[q] = "good" if en else ("bad" if i % 2 else "good")
    cmp = qcmp.compare(c, config_for(c), _run(c, base_q), _run(c, cand_q, commit="b" * 40))
    by = {g.group: g for g in cmp.verdict.groups}
    assert set(by) == {"all", "lang=en kind=known-item", "lang=de kind=vague"}
    assert by["lang=en kind=known-item"].outcome == "improved"
    assert by["lang=de kind=vague"].outcome == "regressed"
    assert by["all"].mean_delta > 0  # the pooled mean alone would look like a gain
    assert cmp.verdict.outcome is Outcome.REGRESSION


def test_group_membership_is_fixed_by_labels_not_input_order(c):
    base, cand = _run(c), _run(c, _worse(c, 3), commit="b" * 40)
    flipped = with_results(cand, reversed(cand.results))
    a = qcmp.compare(c, config_for(c), base, cand)
    b = qcmp.compare(c, config_for(c), base, flipped)
    assert a.verdict.groups == b.verdict.groups


@pytest.mark.parametrize("mutate,reason", [
    (lambda c, r: with_results(r, [replace(x, query_text_sha256="0" * 64) if x.qid == "q003" else x
                                   for x in r.results]),
     "query text changed under the same id for 1 queries (q003)"),
    (lambda c, r: with_results(r, r.results[1:]), "1 missing query ids (q000)"),
    (lambda c, r: with_results(r, r.results + r.results[:1]), "1 duplicate query ids (q000)"),
    (lambda c, r: with_results(r, r.results + (QueryResult("zz", "h", ()),)), "1 unknown query ids"),
    (lambda c, r: with_results(r, ()), "zero queries"),
    (lambda c, r: with_results(r, [replace(x, error="HTTP 500") if x.qid == "q004" else x
                                   for x in r.results]), "1 queries failed (e.g. q004: HTTP 500)"),
    (lambda c, r: with_results(r, [replace(x, ranked=("ghost",) + x.ranked) if x.qid == "q004" else x
                                   for x in r.results]), "not in the manifest (ghost)"),
    (lambda c, r: replace(r, header=replace(r.header, ended=None)), "interrupted output"),
    (lambda c, r: replace(r, header=replace(r.header, dirty_hash="d" * 40)), "dirty tree"),
    (lambda c, r: replace(r, header=replace(r.header, collection="other")), "not 'synthetic'"),
    (lambda c, r: replace(r, header=replace(r.header, index_population={"count": 59,
                                                                        "manifest_sha256": "x"})),
     "index population 59 docs"),
])
def test_invalid_candidates_are_refused_with_their_reason(c, mutate, reason):
    cmp = qcmp.compare(c, config_for(c), _run(c), mutate(c, _run(c, commit="b" * 40)))
    assert cmp.verdict.outcome is Outcome.INVALID and cmp.verdict.exit_code == 2
    assert any(reason in r for r in cmp.verdict.reasons), cmp.verdict.reasons
    assert not cmp.per_query and not cmp.verdict.groups  # nothing is filled in


def test_altered_judgments_refuse_the_comparison(c):
    base, cand = _run(c), _run(c, commit="b" * 40)
    j = {q: dict(d) for q, d in c.judgments.items()}
    j["q000"]["d000"] = 0 if j["q000"].get("d000") else 1
    altered = qc.Collection(c.name, c.language, c.queries, j, c.documents)
    cmp = qcmp.compare(altered, config_for(altered), base, cand)
    assert cmp.verdict.outcome is Outcome.INVALID
    assert any("judgments, queries or documents were altered" in r for r in cmp.verdict.reasons)


def test_added_labels_rescore_both_arms(c):
    base, cand = _run(c), _run(c, commit="b" * 40)
    newer = qc.with_added_judgments(c, {"q000": {"d059": 1}}, "judged")
    cmp = qcmp.compare(newer, config_for(newer), base, cand)
    assert cmp.verdict.outcome is Outcome.PASS
    assert any("rescored against updated labels" in n for n in cmp.notes)


def test_result_from_another_commit_or_model_set_is_invalid(c):
    cand = _run(c, commit="b" * 40)
    cmp = qcmp.compare(c, config_for(c), _run(c), cand, expect_commit="c" * 40)
    assert cmp.verdict.outcome is Outcome.INVALID and "not the candidate" in cmp.verdict.reasons[0]
    cmp = qcmp.compare(c, config_for(c), _run(c), cand, expect_commit="b" * 40,
                       expect_models={"embed": "e2", "reranker": "r1"})
    assert cmp.verdict.outcome is Outcome.INVALID and "model set" in cmp.verdict.reasons[0]
    assert qcmp.compare(c, config_for(c), _run(c), cand, expect_commit="b" * 40).verdict.outcome \
        is Outcome.PASS


def test_absent_baseline_is_invalid(c):
    cmp = qcmp.compare(c, config_for(c), None, _run(c))
    assert cmp.verdict.outcome is Outcome.INVALID and cmp.verdict.reasons == ("absent baseline",)


@pytest.mark.parametrize("change,named", [
    (dict(model_hashes={"embed": "e2", "reranker": "r1"}), "models: differ in embed"),
    (dict(request_config={"mode": "hybrid", "k": 20}), "request_config: differs in k"),
    (dict(index_key="idx-2"), "index_key: idx-1 -> idx-2"),
    (dict(index_state={"settled": False}), "index_state.settled: True -> False"),
    (dict(index_state={"settled": True, "enrichment": "partial"}), "index_state.enrichment"),
])
def test_uncontrolled_difference_is_named_and_inconclusive(c, change, named):
    base = _run(c)
    cand = _run(c, _worse(c, 2), commit="b" * 40, **change)  # a real loss, but confounded
    cmp = qcmp.compare(c, config_for(c), base, cand)
    assert cmp.verdict.outcome is Outcome.INCONCLUSIVE and cmp.verdict.exit_code == 2
    assert any(named in x for x in cmp.confounds), cmp.confounds
    assert "confounded" in cmp.verdict.reasons[0]
    assert cmp.verdict.groups  # still reported, never turned into a verdict


def test_declared_change_is_not_a_confound(c):
    cand = _run(c, commit="b" * 40, model_hashes={"embed": "e2", "reranker": "r1"})
    cmp = qcmp.compare(c, config_for(c), _run(c), cand, intended=["commit", "models"])
    assert cmp.verdict.outcome is Outcome.PASS and not cmp.confounds
    assert "models" in cmp.differences


def _unjudged_candidate(c, base, keep=2):
    """The baseline's first ``keep`` results, then six documents unjudged for that query."""
    shifted = []
    for r in base.results:
        j = c.judgments[r.qid]
        fresh = tuple(d for d in sorted(c.documents, reverse=True) if d not in j)[:6]
        shifted.append(replace(r, ranked=r.ranked[:keep] + fresh))
    return with_results(replace(base, header=replace(base.header, commit="b" * 40)), shifted)


def test_many_unjudged_differences_send_pairs_to_judgment_with_pooled_judgments(c):
    base = _run(c)
    cand = _unjudged_candidate(c, base)
    cmp = qcmp.compare(c, config_for(c, judgments="pooled"), base, cand)
    assert cmp.verdict.outcome is Outcome.INCONCLUSIVE
    assert "labels incomplete" in cmp.verdict.reasons[0]
    assert cmp.unjudged["differing_share"] > cmp.unjudged["threshold"] == 0.10
    expected = sorted({(r.qid, d) for run in (base, cand) for r in run.results for d in r.ranked
                       if d not in c.judgments[r.qid]})
    assert cmp.judgment_requests == expected
    assert all(d not in c.judgments[q] for q, d in cmp.judgment_requests)


@pytest.mark.parametrize("keep,outcome", [(2, Outcome.PASS), (0, Outcome.REGRESSION)])
def test_official_judgments_count_unjudged_as_non_relevant_and_only_report_the_share(c, keep,
                                                                                      outcome):
    base = _run(c)
    cand = _unjudged_candidate(c, base, keep)  # keep=0: the positives are pushed out of the top 10
    cmp = qcmp.compare(c, config_for(c, judgments="official"), base, cand)
    assert cmp.verdict.outcome is outcome, cmp.verdict.reasons
    assert not any("labels incomplete" in r for r in cmp.verdict.reasons)
    assert not cmp.judgment_requests
    assert cmp.unjudged["differing_share"] > 0.10 and cmp.unjudged["candidate_share"] > 0.5
    assert cmp.unjudged["judgments"] == "official" and cmp.unjudged["threshold"] is None


def test_stored_baseline_round_trip_and_tamper_detection(c, tmp_path):
    p = tmp_path / "base.jsonl"
    _run(c).write(p)
    stored = qcmp.write_baseline(p, c)
    run, metrics = qcmp.load_baseline(stored)
    cmp = qcmp.compare(c, config_for(c), run, _run(c, commit="b" * 40), baseline_metrics=metrics)
    assert cmp.verdict.outcome is Outcome.PASS
    metrics["per_query"]["q000"]["nDCG@10"] = 0.5
    cmp = qcmp.compare(c, config_for(c), run, _run(c, commit="b" * 40), baseline_metrics=metrics)
    assert cmp.verdict.outcome is Outcome.INVALID and "disagrees with rescoring" in cmp.verdict.reasons[0]
    _run(c, "bad").write(p)
    with pytest.raises(qcmp.BaselineError, match="changed after the baseline was stored"):
        qcmp.load_baseline(stored)
    with pytest.raises(qcmp.BaselineError, match="absent baseline"):
        qcmp.load_baseline(tmp_path / "nope.baseline.json")


def test_holm_and_paired_stats():
    adj = qcmp.holm({"a": 0.01, "b": 0.04, "c": 0.03})
    assert adj == {"a": pytest.approx(0.03), "c": pytest.approx(0.06), "b": pytest.approx(0.06)}
    d = np.array([-0.1] * 30 + [0.0] * 270)
    lo, hi, p, dz = qcmp.paired_stats(d, 4000, 1, "g")
    assert lo < d.mean() < hi < 0 and p < 0.001 and dz < 0
    assert qcmp.paired_stats(d, 4000, 1, "g") == (lo, hi, p, dz)  # seeded
    assert all(math.isfinite(x) for x in (lo, hi, p))
