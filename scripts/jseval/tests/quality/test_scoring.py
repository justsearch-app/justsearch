"""Scoring: exact values, refusals, unjudged share, cross-check against today's harness runs."""

from __future__ import annotations

import json
import math

import pytest

from jseval.quality import collections as qc
from jseval.quality import legacy, scoring
from jseval.quality.types import QueryResult

from .helpers import DE_RUN, SCIFACT_RUN, have_ir

J = {"q": {"a": 2, "b": 1, "c": 0}}


def test_delivered_order_is_scored_exactly():
    got = scoring.score_rankings(J, {"q": ["b", "a"]})["q"]
    dcg = 1 + 2 / math.log2(3)
    idcg = 2 + 1 / math.log2(3)
    assert got["nDCG@10"] == pytest.approx(dcg / idcg, abs=1e-15)
    assert got["RR@10"] == 1.0 and got["Success@10"] == 1.0 and got["R@10"] == 1.0
    # identifiers that would sort differently from the delivered order
    assert scoring.score_rankings(J, {"q": ["z", "y", "x", "a"]})["q"]["RR@10"] == 0.25


def test_only_the_top_ten_count():
    ranked = [f"n{i}" for i in range(10)] + ["a", "b"]
    got = scoring.score_rankings(J, {"q": ranked})["q"]
    assert got == {"nDCG@10": 0.0, "RR@10": 0.0, "Success@10": 0.0, "R@10": 0.0}


def test_empty_result_scores_zero_but_failures_and_gaps_are_refused():
    assert scoring.score_rankings(J, {"q": []})["q"]["nDCG@10"] == 0.0
    with pytest.raises(scoring.ScoringError, match="failed"):
        scoring.score_results(J, [QueryResult("q", "h", (), error="timeout")])
    with pytest.raises(scoring.ScoringError, match="no relevant judgment"):
        scoring.score_rankings(J, {"other": ["a"]})
    with pytest.raises(scoring.ScoringError, match="twice"):
        scoring.score_rankings(J, {"q": ["a", "a"]})
    with pytest.raises(scoring.ScoringError, match="zero queries"):
        scoring.mean({}, "nDCG@10")
    with pytest.raises(scoring.ScoringError, match="no queries"):
        scoring.score_results(J, [])


def test_unjudged_share_counts_top_ten_slots_per_query():
    results = [QueryResult("q", "h", ("a", "x", "c", "y")), QueryResult("q2", "h", ("a",))]
    unj, slots, pairs = scoring.unjudged_top({**J, "q2": {"b": 1}}, results)
    assert (unj, slots) == (3, 5)
    assert pairs == {("q", "x"), ("q", "y"), ("q2", "a")}


@pytest.mark.parametrize("alias,run_dir,ir_parts,expected", [
    ("scifact", SCIFACT_RUN, ("beir", "scifact"), 0.759),
    ("miracl-de", DE_RUN, ("miracl", "v1.0", "de", "docs.pklz4"), 0.986)])
def test_ndcg_matches_the_harness_on_todays_runs(alias, run_dir, ir_parts, expected):
    if not run_dir.exists() or not have_ir(*ir_parts):
        pytest.skip("today's harness outputs or the ir_datasets cache are not present")
    c = qc.build(qc.load_config(alias))
    summary = json.loads((run_dir / "summary.json").read_text())
    for mode in ("lexical", "vector", "splade", "hybrid"):
        run = legacy.run_from_harness(run_dir, c, mode)
        assert len(run.results) == len(c.queries)
        agg = scoring.aggregate(scoring.score_results(c.judgments, run.results))
        harness = summary["per_mode"][mode]["aggregate_metrics"]
        for metric in ("nDCG@10", "RR@10", "R@10"):
            assert abs(agg[metric] - harness[metric]) < 1e-9, (mode, metric)
        if mode == "hybrid":
            assert round(agg["nDCG@10"], 3) == expected
