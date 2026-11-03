"""Reproduce the deterministic-skip comparison blocker without the runner."""
from dataclasses import replace
from jseval.quality import compare
from jseval.quality.types import Served, Outcome
from .helpers import make_collection, make_run, config_for


def test_identical_navigational_skip_is_not_a_confound():
    c = make_collection()
    r = make_run(c)
    r = replace(r, results=tuple(replace(q, served=Served(q.served.legs, 'skipped', 'NAVIGATIONAL_QUERY')) for q in r.results))
    assert compare.compare(c, config_for(c), r, r).verdict.outcome is Outcome.PASS
