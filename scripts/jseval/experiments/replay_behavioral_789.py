"""tempdoc 789 Phase 1 item 3 — the acceptance bar: replay the shipped behavioral
classifiers OFFLINE over the 2026-07-28 window-2 hero logs and reproduce the hand
census EXACTLY.

The census (`tmp/hero-arc-analysis/census/`) was a one-off hand analysis. Phase 1
promotes its metrics into the standing harness; this script is what proves the
promotion did not change the numbers. It runs `$0` — no model, no API, no spend —
and asserts against the published census artifacts:

  name-pivot.v1.json    name_pivot per stratum x arm
  wrongness.v1.json     the abstained / fabricated / near-miss taxonomy
  hop1-stopping.v1.json stopped_at_hop1 + gold_present_in_answer
  (searched_before_grep, asserted at 60/60 per with-tool stratum)

Two definitions run side by side, per the charter's "emit both where they diverge":

  * CENSUS arm     — entities supplied from the corpus ground truth (the hop-1
    person, re-extracted from the gold evidence docs exactly as `census/hop1.py`
    did) and the census's hand-written identifier shape. This is the arm the
    assertions bind.
  * GENERALIZED arm — the harness default: identifier shape derived from each
    cell's OWN gold answer, no corpus-specific literal. Reported next to the census
    arm; a divergence is printed rather than hidden.

The delivered-span entity source (the harness's generalized `name_pivot`) is NOT
replayable here and is reported as such: an Inspect log persists tool-result
DIGESTS, never content, so the entities a cell was handed cannot be recovered from
window-2. That half is unit-covered and becomes live-comparable on the first
campaign recorded with tempdoc 789 in place.

Usage (from a checkout, with the jseval package importable):

    PYTHONPATH=$(node scripts/lib/python-path.cjs .) PYTHONUTF8=1 python scripts/jseval/experiments/replay_behavioral_789.py

Override the read-only input roots with --run-root / --datasets / --census.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

from jseval.agent_behavioral import (
    CENSUS_IDENTIFIER_SHAPE,
    classify_answer,
    hop1_stop,
    name_pivot,
    normalize,
    probe_texts,
    tool_shape,
)

DEFAULT_RUN_ROOT = Path(
    r"F:/justsearch-public/.claude/worktrees/pub-hero/tmp/782-run-2026-07-28b-hero")
DEFAULT_DATASETS = Path(
    r"F:/justsearch-public/.claude/worktrees/pub-hero/datasets/mixed")
DEFAULT_CENSUS = Path(r"F:/justsearch-public/tmp/hero-arc-analysis/census")

STRATA = (
    "en-email-enron-raw-1k-verbose",
    "en-email-enron-raw-10k-verbose",
    "en-legal-clerc-1k-verbose",
)
ARMS = ("A", "B")

# Verbatim from `tmp/hero-arc-analysis/census/hop1.py`: the injected hop-1 sentence
# template. The person named here is the intermediate entity a 2-document chain
# hands off through.
HOP1_TEMPLATE = re.compile(
    r"The ([A-Za-z]+(?: [a-z]+)?) in the ([a-z ]+), designated (.+?), was "
    r"(?:designed by the engineer|founded by|built by|led by|commissioned by|"
    r"operated by|financed by|restored by) ([^.\n]+)")


def _find_log(run_root: Path, stratum: str) -> Path:
    log_dir = run_root / stratum / "logs"
    for entry in sorted(log_dir.iterdir()):
        if entry.suffix == ".json" and "agent-utility-task" in entry.name:
            return entry
    raise SystemExit(f"no agent-utility-task log under {log_dir}")


def _hop1_people(datasets: Path) -> dict[tuple[str, int], str]:
    """Re-derive the census's hop-1 person per (stratum, query index) from the corpus."""
    people: dict[tuple[str, int], str] = {}
    for stratum in STRATA:
        queries = json.loads((datasets / stratum / "queries.json").read_text(encoding="utf-8"))
        for index, query in enumerate(queries[:20]):
            for evidence_id in query["evidence_ids"]:
                doc = datasets / stratum / "corpus-dir" / f"{evidence_id}.txt"
                if not doc.exists():
                    continue
                match = HOP1_TEMPLATE.search(doc.read_text(encoding="utf-8", errors="replace"))
                if match:
                    people[(stratum, index)] = match.group(4).strip()
                    break
    return people


def _cells(run_root: Path):
    """Every window-2 cell, as (stratum, arm, qidx, sample) — read-only."""
    from inspect_ai.log import read_eval_log

    for stratum in STRATA:
        log = read_eval_log(_find_log(run_root, stratum).as_posix())
        for sample in log.samples or []:
            arm, raw_qid = str(sample.id).split("|", 1)
            yield stratum, arm, int(raw_qid.lstrip("q")), sample


def _bucket() -> dict:
    return {
        "cells": 0, "correct": 0,
        "name_pivot": 0, "correct_with_pivot": 0, "wrong_with_pivot": 0,
        "wrong": 0, "stopped_at_hop1": 0, "gold_present_in_answer": 0,
        "searched_before_grep": 0,
        "abstained": 0, "fabricated_specific": 0, "format_near_miss": 0,
        "wrong_value": 0, "harness_error": 0,
        "generalized_abstained": 0, "generalized_fabricated_specific": 0,
        "generalized_format_near_miss": 0, "generalized_wrong_value": 0,
        "generalized_harness_error": 0,
    }


def replay(run_root: Path, datasets: Path) -> dict:
    people = _hop1_people(datasets)
    missing = [f"{s}|q{i}" for s in STRATA for i in range(20) if (s, i) not in people]
    out: dict[str, dict] = {f"{s}|{a}": _bucket() for s in STRATA for a in ARMS}
    for stratum, arm, qidx, sample in _cells(run_root):
        key = f"{stratum}|{arm}"
        bucket = out[key]
        metadata = sample.metadata or {}
        error = metadata.get("error") or getattr(sample, "error", None)
        score = (sample.scores or {}).get("substring_scorer")
        correct = bool(score and score.value == "C")
        answer = (sample.output.completion if sample.output else "") or ""
        # The census read `answer_norm` (whitespace-collapsed); the classifier is
        # whitespace-insensitive for every branch except the empty check, which
        # `str.strip()` already handles identically.
        gold = sample.target if isinstance(sample.target, str) else getattr(
            sample.target, "text", None)

        bucket["cells"] += 1
        bucket["correct"] += correct

        person = people.get((stratum, qidx))
        pivoted = bool(person) and name_pivot(
            [person], probe_texts(metadata.get("tool_calls")))
        bucket["name_pivot"] += pivoted
        if correct:
            bucket["correct_with_pivot"] += pivoted
        else:
            bucket["wrong_with_pivot"] += pivoted

        if normalize(gold) and normalize(gold) in normalize(answer):
            bucket["gold_present_in_answer"] += 1
        if not correct:
            bucket["wrong"] += 1
            if person and hop1_stop([person], answer, gold):
                bucket["stopped_at_hop1"] += 1

        census_class = classify_answer(
            answer, gold, error=error, correct=correct,
            identifier_pattern=CENSUS_IDENTIFIER_SHAPE)
        if census_class:
            bucket[census_class] += 1
        generalized_class = classify_answer(answer, gold, error=error, correct=correct)
        if generalized_class:
            bucket["generalized_" + generalized_class] += 1

        shape = tool_shape(metadata.get("tool_call_sequence"), metadata.get("tool_calls"))
        bucket["searched_before_grep"] += shape["searched_before_grep"]
    return {"missing_hop1_entities": missing, "per_stratum_arm": out}


def _expected(census: Path) -> dict:
    pivot = json.loads((census / "name-pivot.v1.json").read_text(encoding="utf-8"))
    wrongness = json.loads((census / "wrongness.v1.json").read_text(encoding="utf-8"))
    hop1 = json.loads((census / "hop1-stopping.v1.json").read_text(encoding="utf-8"))
    return {
        "pivot": pivot["per_stratum_arm"],
        "wrongness": wrongness["per_stratum_arm"],
        "hop1": hop1["per_stratum_arm"],
    }


_CENSUS_CLASS = {
    "empty-abstained": "abstained",
    "fabricated-specific": "fabricated_specific",
    "format-near-miss": "format_near_miss",
    "wrong-value": "wrong_value",
    "harness-error": "harness_error",
}


def check(result: dict, census: Path) -> list[str]:
    """Every assertion, as a list of failures (empty == the census reproduced)."""
    expected = _expected(census)
    failures: list[str] = []

    def want(key: str, field: str, got: int, exp: int) -> None:
        if got != exp:
            failures.append(f"{key}.{field}: replay={got} census={exp}")

    if result["missing_hop1_entities"]:
        failures.append(
            f"hop1 entity extraction missed {result['missing_hop1_entities']}")
    for key, bucket in result["per_stratum_arm"].items():
        pivot = expected["pivot"][key]
        want(key, "cells", bucket["cells"], pivot["cells"])
        want(key, "name_pivot", bucket["name_pivot"], pivot["name_pivot"])
        want(key, "correct", bucket["correct"], pivot["correct"])
        want(key, "correct_with_pivot", bucket["correct_with_pivot"], pivot["correct_with_pivot"])
        want(key, "wrong_with_pivot", bucket["wrong_with_pivot"], pivot["wrong_with_pivot"])

        hop1 = expected["hop1"][key]
        want(key, "wrong", bucket["wrong"], hop1["wrong"])
        want(key, "stopped_at_hop1", bucket["stopped_at_hop1"], hop1["stopped_at_hop1"])
        want(key, "gold_present_in_answer",
             bucket["gold_present_in_answer"], hop1["gold_present_in_answer"])

        counts = expected["wrongness"][key]["counts"]
        for census_name, field in _CENSUS_CLASS.items():
            want(key, field, bucket[field], counts.get(census_name, 0))
            # The generalized (gold-derived shape) arm must agree with the census arm;
            # a divergence is a real finding, not a tolerance to widen.
            if bucket["generalized_" + field] != bucket[field]:
                failures.append(
                    f"{key}.{field}: generalized={bucket['generalized_' + field]} "
                    f"census-shape={bucket[field]} (definitions diverge)")

        if key.endswith("|B"):
            want(key, "searched_before_grep", bucket["searched_before_grep"], 60)
        elif key.endswith("|A"):
            # The baseline arm is offered no MCP tools, so it can never search first.
            want(key, "searched_before_grep", bucket["searched_before_grep"], 0)
    return failures


def render_table(result: dict) -> str:
    header = (f"{'stratum|arm':<34} {'cells':>5} {'pivot':>6} {'hop1_stop':>9} "
              f"{'abst':>5} {'fabr':>5} {'near':>5} {'srch1st':>7}")
    lines = [header, "-" * len(header)]
    for key in sorted(result["per_stratum_arm"]):
        b = result["per_stratum_arm"][key]
        lines.append(
            f"{key:<34} {b['cells']:>5} {b['name_pivot']:>6} {b['stopped_at_hop1']:>9} "
            f"{b['abstained']:>5} {b['fabricated_specific']:>5} "
            f"{b['format_near_miss']:>5} {b['searched_before_grep']:>7}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", type=Path, default=DEFAULT_RUN_ROOT)
    parser.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    parser.add_argument("--census", type=Path, default=DEFAULT_CENSUS)
    parser.add_argument("--json", type=Path, default=None,
                        help="also write the replay result as JSON")
    args = parser.parse_args(argv)

    for label, path in (("run root", args.run_root), ("datasets", args.datasets),
                        ("census", args.census)):
        if not path.exists():
            print(f"SKIP: {label} not present at {path}", file=sys.stderr)
            return 77

    result = replay(args.run_root, args.datasets)
    print(render_table(result))
    failures = check(result, args.census)
    if args.json:
        args.json.write_text(json.dumps(result, indent=1), encoding="utf-8")
    print()
    if failures:
        print(f"FAIL: {len(failures)} census disagreements")
        for failure in failures:
            print("  " + failure)
        return 1
    print("OK: window-2 census reproduced exactly by the shipped classifiers")
    return 0


if __name__ == "__main__":
    os.environ.setdefault("PYTHONUTF8", "1")
    raise SystemExit(main())
