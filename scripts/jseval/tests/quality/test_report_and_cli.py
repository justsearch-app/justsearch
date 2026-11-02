"""Reports (S12/S17/S19) and the one-command-per-action CLI (S27)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval.cli import main
from jseval.quality import collections as qc
from jseval.quality import compare as qcmp
from jseval.quality import report

from .helpers import config_for, make_collection, make_run

JSEVAL_DIR = Path(__file__).resolve().parents[2]


def _worse(c):
    return {q: ("bad" if i % 3 == 0 else "good") for i, q in enumerate(sorted(c.queries))}


def test_report_states_the_unjudged_share_as_information_for_official_judgments():
    c = make_collection({"en/vague": 60})
    cmp = qcmp.compare(c, config_for(c, judgments="official"), make_run(c),
                       make_run(c, _worse(c), commit="b" * 40))
    text = report.render(cmp)
    assert "Share of top-10 results without a judgment: baseline" in text
    assert "official judgments: unjudged counts as non-relevant, information only" in text
    assert "threshold" not in text.split("## Unjudged results")[1].split("##")[0]
    pooled = report.render(qcmp.compare(c, config_for(c, judgments="pooled"), make_run(c),
                                        make_run(c, _worse(c), commit="b" * 40)))
    assert "threshold 10%" in pooled


def test_report_has_outcome_line_most_changed_queries_and_unjudged_share():
    c = make_collection({"en/vague": 60})
    cmp = qcmp.compare(c, config_for(c), make_run(c), make_run(c, _worse(c), commit="b" * 40))
    line = report.outcome_line(cmp)
    assert line.startswith("REGRESSION synthetic nDCG@10 1.0000 -> ")
    assert "\n" not in line and "unjudged@10" in line
    text = report.render(cmp)
    assert text.splitlines()[2] == f"**{line}**"
    assert "## Most changed queries (nDCG@10)" in text
    assert "> question 0 (en/vague)" in text  # the query text itself
    assert "candidate top 5:" in text and "(grade 2)" in text and "(unjudged)" in text
    assert "Share of top-10 results without a judgment: baseline" in text
    assert "| commit | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | bbbb" in text
    assert text.count("### q") == 10


def test_report_names_confounds_and_invalid_reasons():
    c = make_collection()
    cand = make_run(c, commit="b" * 40, model_hashes={"embed": "e9", "reranker": "r1"})
    cmp = qcmp.compare(c, config_for(c), make_run(c), cand)
    text = report.render(cmp)
    assert "Uncontrolled differences" in text and "models: differ in embed" in text
    assert "| model: embed | e1 | e9 | differs |" in text
    bad = qcmp.compare(c, config_for(c), None, cand)
    assert report.outcome_line(bad) == "INVALID synthetic: absent baseline"
    assert "## Most changed" not in report.render(bad)


@pytest.fixture
def stored(tmp_path):
    c = make_collection({"en/vague": 40})
    d = qc.write_collection(c, tmp_path / "col")
    cfg = {"schema": "jseval.quality.config.v1", "collection": c.name, "language": "en",
           "source": {"kind": "synthetic"}, "judgments": "official", "labels": {},
           "expected": c.counts(),
           "bootstrap": {"resamples": 2000, "seed": 1}}
    (d / "quality.config.json").write_text(json.dumps(cfg))
    base = tmp_path / "base.jsonl"
    make_run(c).write(base)
    same = tmp_path / "same.jsonl"
    make_run(c, commit="b" * 40).write(same)
    worse = tmp_path / "worse.jsonl"
    make_run(c, _worse(c), commit="b" * 40).write(worse)
    return c, d, tmp_path


def _cli(*args):
    return CliRunner().invoke(main, ["quality", *map(str, args)])


def test_cli_known_answers_score_and_compare_exit_codes(stored):
    c, d, tmp = stored
    r = _cli("known-answers", d)
    assert r.exit_code == 0 and r.output.startswith("PASS synthetic known answers:"), r.output
    r = _cli("score", d, tmp / "base.jsonl", "--store-baseline", tmp / "base.baseline.json")
    assert r.exit_code == 0 and r.output.startswith("VALID synthetic n=40 nDCG@10 1.0000"), r.output
    common = ["--baseline", tmp / "base.baseline.json", "--expect-commit", "b" * 40]
    r = _cli("compare", d, *common, "--candidate", tmp / "same.jsonl")
    assert r.exit_code == 0 and r.output.startswith("PASS synthetic"), r.output
    assert len(r.output.strip().splitlines()) == 1
    r = _cli("compare", d, *common, "--candidate", tmp / "worse.jsonl", "--report", tmp / "r.md")
    assert r.exit_code == 1 and r.output.startswith("REGRESSION"), r.output
    assert "## Most changed queries" in (tmp / "r.md").read_text()
    r = _cli("compare", d, "--baseline", tmp / "base.baseline.json", "--expect-commit", "c" * 40,
             "--candidate", tmp / "same.jsonl")
    assert r.exit_code == 2 and r.output.startswith("INVALID") and "not the candidate" in r.output


def test_cli_invalid_inputs_exit_2(stored):
    c, d, tmp = stored
    r = _cli("known-answers", "no-such-alias")
    assert r.exit_code == 2 and "unknown collection alias 'no-such-alias'" in r.output
    r = _cli("compare", d, "--baseline", tmp / "missing.baseline.json", "--candidate",
             tmp / "same.jsonl", "--expect-commit", "b" * 40)
    assert r.exit_code == 2 and "absent baseline" in r.output
    text = (tmp / "same.jsonl").read_text()
    (tmp / "cut.jsonl").write_text(text[: len(text) // 2])
    r = _cli("score", d, tmp / "cut.jsonl")
    assert r.exit_code == 2 and "interrupted output" in r.output
    (d / "qrels.tsv").write_text("".join((d / "qrels.tsv").read_text().splitlines(True)[1:]))
    r = _cli("known-answers", d)
    assert r.exit_code == 2 and "judgments altered" in r.output


def test_python_dash_m_entry_point(stored):
    c, d, tmp = stored
    env = {**os.environ, "PYTHONPATH": str(JSEVAL_DIR)}
    p = subprocess.run([sys.executable, "-m", "jseval", "quality", "score", str(d),
                        str(tmp / "worse.jsonl")], capture_output=True, text=True, env=env,
                       cwd=JSEVAL_DIR)
    assert p.returncode == 0, p.stderr
    assert p.stdout.startswith("VALID synthetic n=40")
