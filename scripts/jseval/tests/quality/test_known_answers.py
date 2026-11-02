"""Known-answer checks (S10)."""

from __future__ import annotations

import math

import pytest

from jseval.quality import collections as qc
from jseval.quality import known_answers as ka
from jseval.quality import scoring

from .helpers import config_for, have_ir, make_collection


def test_hand_computed_constant_is_right():
    dcg = 1 / math.log2(3) + 2 / math.log2(5)
    idcg = 2 + 1 / math.log2(3)
    assert ka.HAND_EXPECTED["nDCG@10"] == pytest.approx(dcg / idcg, abs=1e-15)
    assert ka.reference_metrics(ka.HAND_JUDGMENTS["ka"], ka.HAND_RANKING) == pytest.approx(
        ka.HAND_EXPECTED, abs=1e-15)


def test_constructed_rankings_have_their_exact_scores():
    c = make_collection({"en/vague": 6, "de/known-item": 4})
    built = ka._constructed(c)
    ideal = scoring.score_rankings(c.judgments, built["ideal"])
    nonrel = scoring.score_rankings(c.judgments, built["non-relevant"])
    mixed = scoring.score_rankings(c.judgments, built["mixed"])
    assert all(m["nDCG@10"] == pytest.approx(1.0, abs=1e-12) for m in ideal.values())
    assert all(v == 0.0 for m in nonrel.values() for v in m.values())
    # mixed = [non-rel, grade 2, non-rel, non-rel, grade 1]: (2/log2 3 + 1/log2 6) / (2 + 1/log2 3)
    want = (2 / math.log2(3) + 1 / math.log2(6)) / (2 + 1 / math.log2(3))
    assert all(m["nDCG@10"] == pytest.approx(want, abs=1e-12) for m in mixed.values())
    assert all(m["RR@10"] == 0.5 for m in mixed.values())
    report = ka.check(c, config_for(c))
    assert report.checks == 4 + 3 * 4 * len(c.queries)


def test_count_mismatch_stops_with_its_reason():
    c = make_collection()
    with pytest.raises(ka.KnownAnswerFailure, match="expected queries 41, found 40"):
        ka.check(c, config_for(c, expected={**c.counts(), "queries": 41}))


def test_query_without_positive_or_unknown_judged_doc_stops():
    c = make_collection()
    j = {q: dict(d) for q, d in c.judgments.items()}
    j["q001"] = {d: 0 for d in j["q001"]}
    j["q002"]["not-in-manifest"] = 1
    bad = qc.Collection(c.name, c.language, c.queries, j, c.documents)
    with pytest.raises(ka.KnownAnswerFailure) as e:
        ka.check(bad, config_for(bad))
    text = " ".join(e.value.reasons)
    assert "no relevant judgment (e.g. q001)" in text
    assert "not in the manifest (e.g. not-in-manifest)" in text


def test_a_broken_scorer_is_caught(monkeypatch):
    c = make_collection()
    real = scoring.score_rankings

    def off_by_one(judgments, rankings):
        return {q: {**m, "RR@10": m["RR@10"] * 0.999} for q, m in real(judgments, rankings).items()}

    monkeypatch.setattr(scoring, "score_rankings", off_by_one)
    with pytest.raises(ka.KnownAnswerFailure, match="hand-computed ranking: RR@10"):
        ka.check(c, config_for(c))


@pytest.mark.parametrize("alias,parts", [
    ("scifact", ("beir", "scifact")),
    ("miracl-de", ("miracl", "v1.0", "de", "docs.pklz4")),
    ("miracl-fr", ("miracl", "v1.0", "fr", "docs.pklz4"))])
def test_known_answers_pass_on_every_official_collection_and_catch_a_dropped_positive(alias, parts):
    if not have_ir(*parts):
        pytest.skip(f"{alias} not in the ir_datasets cache")
    cfg = qc.load_config(alias)
    c = qc.build(cfg)
    report = ka.check(c, cfg)
    assert report.checks > 3 * len(c.queries)
    j = {q: dict(d) for q, d in c.judgments.items()}
    q = sorted(j)[0]
    del j[q][next(d for d, g in sorted(j[q].items()) if g > 0)]
    with pytest.raises(ka.KnownAnswerFailure, match="expected positives"):
        ka.check(qc.Collection(c.name, c.language, c.queries, j, c.documents), cfg)
