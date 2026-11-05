"""Later candidates on frozen topical labels (P5c L6): unjudged concentration per arm."""
from __future__ import annotations

from jseval.quality import compare as qcmp
from jseval.quality.types import Collection, Outcome

from .helpers import config_for, make_collection, make_run


def _topical_world(judge_all: bool):
    c = make_collection({"de/topical": 20, "en/known-item": 20})
    if judge_all:
        judgments = {q: dict(j) for q, j in c.judgments.items()}
        for qid, ranked in ((r.qid, r.ranked) for r in make_run(c).results):
            if c.queries[qid].groups["kind"] == "topical":
                judgments[qid].update({d: 0 for d in ranked[:10] if d not in judgments[qid]})
        c = Collection(c.name, c.language, c.queries, judgments, c.documents, c.content, c.metadata)
    return c


def test_a_topical_group_with_shared_unjudged_top10_documents_is_inconclusive():
    c = _topical_world(judge_all=False)
    cmp = qcmp.compare(c, config_for(c), make_run(c), make_run(c, commit="b" * 40))
    assert cmp.unjudged["differing_pairs"] == 0  # every unknown is shared: the old rule passes
    assert cmp.verdict.outcome is Outcome.INCONCLUSIVE
    assert any("topical group lang=de kind=topical" in r for r in cmp.verdict.reasons)
    assert cmp.judgment_requests
    assert all(c.queries[q].groups["kind"] == "topical" for q, _ in cmp.judgment_requests)
    assert set(cmp.unjudged["topical_groups"]) == {"lang=de kind=topical"}


def test_a_fully_judged_topical_group_is_compared_normally():
    c = _topical_world(judge_all=True)
    cmp = qcmp.compare(c, config_for(c), make_run(c), make_run(c, commit="b" * 40))
    assert cmp.verdict.outcome is Outcome.PASS
    assert cmp.unjudged["topical_groups"]["lang=de kind=topical"]["candidate_share"] == 0
