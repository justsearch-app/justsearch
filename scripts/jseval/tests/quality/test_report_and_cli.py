"""Reports (S12/S17/S19) and the one-command-per-action CLI (S27)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest
from click.testing import CliRunner

from jseval.cli import main
from jseval.quality import collections as qc
from jseval.quality import compare as qcmp
from jseval.quality import report
from jseval.quality.types import Served

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


def test_cli_never_exits_with_the_regression_code_on_bad_input_or_a_crash(stored, monkeypatch):
    c, d, tmp = stored
    r = _cli("score", d, tmp / "base.jsonl", "--store-baseline", tmp / "base.baseline.json")
    assert r.exit_code == 0, r.output
    common = ["--baseline", tmp / "base.baseline.json", "--expect-commit", "b" * 40]
    # a header that parses but has the wrong shape
    lines = (tmp / "same.jsonl").read_text().splitlines(True)
    head = json.loads(lines[0])
    for key, value in (("index_population", [40]), ("model_hashes", "e1"),
                       ("index_state", ["settled"])):
        (tmp / "shaped.jsonl").write_text(json.dumps({**head, key: value}) + "\n"
                                          + "".join(lines[1:]))
        r = _cli("compare", d, *common, "--candidate", tmp / "shaped.jsonl")
        assert r.exit_code == 2 and r.output.startswith("INVALID") and key in r.output, r.output
    head["candidate"]["dirty_hash"] = 7
    (tmp / "shaped.jsonl").write_text(json.dumps(head) + "\n" + "".join(lines[1:]))
    r = _cli("compare", d, *common, "--candidate", tmp / "shaped.jsonl")
    assert r.exit_code == 2 and "dirty_hash" in r.output, r.output
    # a baseline file without its run digest
    doc = json.loads((tmp / "base.baseline.json").read_text())
    del doc["run_sha256"]
    (tmp / "nodigest.baseline.json").write_text(json.dumps(doc))
    r = _cli("compare", d, "--baseline", tmp / "nodigest.baseline.json", "--expect-commit",
             "b" * 40, "--candidate", tmp / "same.jsonl")
    assert r.exit_code == 2 and "run_sha256" in r.output, r.output
    # a report path that cannot be written
    (tmp / "afile").write_text("x")
    r = _cli("compare", d, *common, "--candidate", tmp / "same.jsonl", "--report",
             tmp / "afile" / "r.md")
    assert r.exit_code == 2 and r.output.startswith("INVALID synthetic: cannot write the report"), \
        r.output
    assert len(r.output.strip().splitlines()) == 1
    # any other unexpected error, and an interrupt
    monkeypatch.setattr(qcmp, "compare", lambda *a, **k: 1 / 0)
    r = _cli("compare", d, *common, "--candidate", tmp / "same.jsonl")
    assert r.exit_code == 2 and "ZeroDivisionError" in r.output, r.output

    def interrupt(*a, **k):
        raise KeyboardInterrupt

    monkeypatch.setattr(qcmp, "compare", interrupt)
    r = _cli("compare", d, *common, "--candidate", tmp / "same.jsonl")
    assert r.exit_code == 2 and "interrupted" in r.output, r.output


def test_report_names_affected_queries_effective_inputs_and_numpy():
    c = make_collection({"en/vague": 60})
    cand = make_run(c, commit="b" * 40, budget={"deadline_ms": 300})
    cand = replace(cand, results=tuple(
        replace(r, served=Served(r.served.legs, "skipped", "DEADLINE_EXCEEDED"))
        if r.qid in ("q003", "q004") else r for r in cand.results))
    text = report.render(qcmp.compare(c, config_for(c), make_run(c), cand))
    assert "Affected queries (served.reranker, 2): q003, q004" in text
    assert "| budget: deadline_ms | - | 300 | differs |" in text
    assert "| expected legs / reranker | lexical+dense / executed | lexical+dense / executed |  |" in text
    assert "| index build | fresh | fresh |  |" in text
    assert f"numpy {np.__version__}." in text
