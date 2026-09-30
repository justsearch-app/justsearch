"""Tests for the judge human-calibration machinery (tempdoc 624 §M.4 / §T.3).

`agreement_rate` in `judge_logs`'s stats block is a self-consistency statistic
(judge vs. the cheap EM fallback) -- these tests cover the machinery that
validates the judge against an INDEPENDENT signal instead: stratified sampling,
Cohen's kappa (+ bootstrap CI), and the additive `human_calibration` overlay
schema extension. The end-to-end dry run substitutes two deterministic
agent-substitute heuristics for real human raters (none are available in this
autonomous pipeline) -- see `test_dry_run_end_to_end` for the honesty check on
the emitted `rater_kind` field.
"""

from __future__ import annotations

import json
import re
import uuid
from pathlib import Path

import pytest

from jseval import utility_judge as uj


# --- sample_for_calibration --------------------------------------------------


def _make_scores(n_total: int, disagreement_keys: set[str]) -> dict:
    """A synthetic scores dict: `disagreement_keys` get judge != em (a rescue);
    everything else is a plain EM auto-pass (judge=None, em=True)."""
    scores = {}
    for i in range(n_total):
        key = f"C|0|q{i:03d}"
        if key in disagreement_keys:
            scores[key] = {"em": False, "judge": True, "final": True}
        else:
            scores[key] = {"em": True, "judge": None, "final": True}
    return scores


class TestSampleForCalibration:
    def test_deterministic_given_seed(self):
        scores = _make_scores(100, {f"C|0|q{i:03d}" for i in range(5)})
        s1 = uj.sample_for_calibration(scores, n=20, seed=7)
        s2 = uj.sample_for_calibration(scores, n=20, seed=7)
        assert s1 == s2

    def test_different_seeds_can_differ(self):
        scores = _make_scores(100, {f"C|0|q{i:03d}" for i in range(5)})
        s1 = uj.sample_for_calibration(scores, n=20, seed=1)
        s2 = uj.sample_for_calibration(scores, n=20, seed=2)
        assert s1 != s2  # not a hard guarantee in general, but true for this input/n

    def test_oversamples_disagreement_relative_to_natural_frequency(self):
        # 5/100 = 5% natural disagreement rate. n=20 should draw far above 5% of
        # the sample from that stratum (all 5 disagreement keys are pulled in).
        dis_keys = {f"C|0|q{i:03d}" for i in range(5)}
        scores = _make_scores(100, dis_keys)
        sample = uj.sample_for_calibration(scores, n=20, seed=0, disagreement_frac=0.6)
        assert len(sample) == 20
        sampled_dis = [k for k in sample if k in dis_keys]
        # natural expectation at 5% would be ~1 of 20; oversampling pulls in all 5.
        assert len(sampled_dis) == 5
        assert (len(sampled_dis) / len(sample)) > (len(dis_keys) / len(scores))

    def test_n_larger_than_population_returns_everything(self):
        scores = _make_scores(10, {"C|0|q000"})
        sample = uj.sample_for_calibration(scores, n=200, seed=0)
        assert set(sample) == set(scores.keys())

    def test_empty_scores_returns_empty(self):
        assert uj.sample_for_calibration({}, n=20) == []

    def test_n_zero_or_negative_returns_empty(self):
        scores = _make_scores(10, set())
        assert uj.sample_for_calibration(scores, n=0) == []
        assert uj.sample_for_calibration(scores, n=-3) == []


# --- cohens_kappa / bootstrap_kappa_ci ---------------------------------------


class TestCohensKappa:
    def test_perfect_agreement_is_one(self):
        labels = [True, False, True, True, False, False, True, False]
        assert uj.cohens_kappa(labels, labels) == pytest.approx(1.0, abs=1e-9)

    def test_degenerate_all_same_label_does_not_divide_by_zero(self):
        labels = [True] * 10
        assert uj.cohens_kappa(labels, labels) == pytest.approx(1.0, abs=1e-9)

    def test_wikipedia_worked_example(self):
        # Classic Cohen's kappa worked example (50 subjects): both-yes=20,
        # A-yes/B-no=5, A-no/B-yes=10, both-no=15 -> kappa = 0.40.
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        assert len(labels_a) == len(labels_b) == 50
        kappa = uj.cohens_kappa(labels_a, labels_b)
        assert kappa == pytest.approx(0.4, abs=1e-9)

    def test_mismatched_lengths_raises(self):
        with pytest.raises(ValueError):
            uj.cohens_kappa([True, False], [True])

    def test_empty_raises(self):
        with pytest.raises(ValueError):
            uj.cohens_kappa([], [])


class TestIsDegeneratePe:
    def test_true_when_both_raters_gave_every_item_the_same_label(self):
        labels = [True] * 10
        assert uj.is_degenerate_pe(labels, labels) is True

    def test_true_when_both_raters_agree_on_a_single_shared_constant_even_if_not_identical_calls(self):
        # p_e is a function of each rater's marginal rate, not identity of the arrays.
        assert uj.is_degenerate_pe([False] * 6, [False] * 6) is True

    def test_false_on_wikipedia_worked_example(self):
        # Same genuinely-mixed fixture as TestCohensKappa.test_wikipedia_worked_example
        # (kappa=0.4) -- both raters have non-degenerate marginals, so p_e < 1.
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        assert uj.is_degenerate_pe(labels_a, labels_b) is False

    def test_empty_raises(self):
        with pytest.raises(ValueError):
            uj.is_degenerate_pe([], [])


class TestBootstrapKappaCi:
    def test_ci_present_and_sane_on_partial_agreement_case(self):
        # Same Wikipedia worked example as above -- a genuine partial-agreement
        # case, so the CI should be a real (non-degenerate) interval.
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        lo, hi = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=1000, seed=42)
        assert lo < hi  # not degenerate
        assert -1.0 <= lo <= 1.0
        assert -1.0 <= hi <= 1.0

    def test_deterministic_given_seed(self):
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        ci1 = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=500, seed=13)
        ci2 = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=500, seed=13)
        assert ci1 == ci2

    def test_single_item_returns_point_estimate_twice(self):
        lo, hi = uj.bootstrap_kappa_ci([True], [True])
        assert lo == hi == pytest.approx(1.0)

    def test_empty_raises(self):
        with pytest.raises(ValueError):
            uj.bootstrap_kappa_ci([], [])


# --- rater_majority_vote / rater_agreement_report ----------------------------


class TestRaterMajorityVote:
    def test_agreement_and_ties(self):
        raters = [
            [True, False, True, False],
            [True, False, False, True],
        ]
        assert uj.rater_majority_vote(raters) == [True, False, None, None]

    def test_empty_raters_list(self):
        assert uj.rater_majority_vote([]) == []


class TestRaterAgreementReport:
    def test_requires_at_least_two_raters(self):
        with pytest.raises(ValueError):
            uj.rater_agreement_report([True, False], [[True, False]])

    def test_three_raters_not_yet_implemented(self):
        # Krippendorff's alpha (3+) is a named-but-deferred generalization point
        # (tempdoc 624 §M.4/§T.3) -- must fail loudly, not silently degrade.
        raters = [[True, False], [True, False], [False, True]]
        with pytest.raises(NotImplementedError):
            uj.rater_agreement_report([True, False], raters)

    def test_basic_shape_and_bounds(self):
        judge = [True, True, False, False, True, False, True, False]
        rater_a = [True, True, False, True, True, False, True, False]
        rater_b = [True, False, False, False, True, False, True, True]
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=500, seed=1)
        assert report["n"] == 8
        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            block = report[block_name]
            assert -1.0 <= block["value"] <= 1.0
            assert block["ci_low"] <= block["ci_high"]
            assert "degenerate_pe" in block

    def test_ties_are_dropped_and_counted(self):
        # rater_a/rater_b disagree on every item -> majority is always None.
        judge = [True, False, True]
        rater_a = [True, False, True]
        rater_b = [False, True, False]
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=200, seed=1)
        assert report["n_dropped_ties"] == 3
        assert report["judge_vs_rater_agreement"]["value"] is None
        assert report["judge_vs_rater_agreement"]["degenerate_pe"] is None

    def test_homogeneous_sample_flags_degenerate_pe_true(self):
        # Both raters (and the judge, via a unanimous majority) give every item
        # the same label -- kappa=1.0 for the "wrong" (homogeneous-sample) reason.
        judge = [True] * 6
        rater_a = [True] * 6
        rater_b = [True] * 6
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=200, seed=1)
        assert report["rater_vs_rater_agreement"]["value"] == pytest.approx(1.0)
        assert report["rater_vs_rater_agreement"]["degenerate_pe"] is True
        assert report["judge_vs_rater_agreement"]["value"] == pytest.approx(1.0)
        assert report["judge_vs_rater_agreement"]["degenerate_pe"] is True

    def test_mixed_agreement_sample_flags_degenerate_pe_false(self):
        # Wikipedia worked-example marginals (kappa=0.4) reused as the rater pair
        # -- a genuinely mixed, non-degenerate sample.
        rater_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        rater_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        judge = rater_a  # any non-constant judge sequence; majority == rater_a here
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=200, seed=1)
        assert report["rater_vs_rater_agreement"]["value"] == pytest.approx(0.4, abs=1e-9)
        assert report["rater_vs_rater_agreement"]["degenerate_pe"] is False
        assert report["judge_vs_rater_agreement"]["degenerate_pe"] is False


# --- overlay schema extension (write_overlay round-trip) ---------------------


class TestOverlaySchemaExtension:
    def test_human_calibration_round_trips_without_clobbering_existing_fields(self, tmp_path):
        overlay = {
            "judge_identity": {"kind": "hybrid-em-llm", "model": "m"},
            "stats": {"em_auto_pass": 3, "judged_misses": 2, "agreement_rate": 0.9},
            "scores": {"C|0|q0": {"em": True, "judge": None, "final": True}},
            "human_calibration": {
                "rater_kind": "agent-substitute, NOT human",
                "n": 6,
                "sample_qids": ["C|0|q0"],
                "judge_vs_rater_agreement": {"value": 0.5, "ci_low": 0.1, "ci_high": 0.8},
                "rater_vs_rater_agreement": {"value": 0.7, "ci_low": 0.3, "ci_high": 0.95},
            },
        }
        path = uj.write_overlay(tmp_path.as_posix(), overlay)
        on_disk = json.loads((tmp_path / "judge-overlay.json").read_text(encoding="utf-8"))
        assert path.endswith("judge-overlay.json")
        assert on_disk == overlay
        # Existing fields untouched by the extension:
        assert on_disk["judge_identity"] == overlay["judge_identity"]
        assert on_disk["stats"] == overlay["stats"]
        assert on_disk["scores"] == overlay["scores"]
        # New field present + honest about what it is:
        assert on_disk["human_calibration"]["rater_kind"] == "agent-substitute, NOT human"

    def test_overlay_without_human_calibration_still_writes_fine(self, tmp_path):
        # additive means optional: an overlay with no calibration block at all
        # (the normal `judge_logs` output, unchanged) must still round-trip.
        overlay = {"judge_identity": {"kind": "substring-em"}, "stats": {}, "scores": {}}
        path = uj.write_overlay(tmp_path.as_posix(), overlay)
        on_disk = json.loads((tmp_path / "judge-overlay.json").read_text(encoding="utf-8"))
        assert "human_calibration" not in on_disk
        assert path.endswith("judge-overlay.json")


# --- CLI wiring (cmd_utility_judge) ------------------------------------------
#
# Nothing previously pinned the `--judge-url` default or that the WARNING /
# NOTE console lines are genuinely emitted by the Click command itself (as
# opposed to `judge_logs()`, which already has its own coverage above). A
# future accidental revert of just the CLI wiring wouldn't be caught by
# anything -- these pin it at the `CliRunner` level, matching the convention
# in tests/test_cli.py (invoke `main`, assert on `result.output`).


class TestUtilityJudgeCli:
    def test_judge_url_default_is_eval_backend_port(self):
        # Introspect the Click option directly -- more robust than scraping
        # --help text, and exercises the same object cmd_utility_judge runs.
        from jseval.commands.utility import cmd_utility_judge
        judge_url_param = next(p for p in cmd_utility_judge.params if p.name == "judge_url")
        assert judge_url_param.default == "http://127.0.0.1:33221"

    def test_judge_url_default_appears_in_help_output(self):
        from click.testing import CliRunner

        from jseval.cli import main
        runner = CliRunner()
        result = runner.invoke(main, ["utility-judge", "--help"])
        assert result.exit_code == 0
        assert "http://127.0.0.1:33221" in result.output

    def test_fully_degraded_judge_prints_warning(self, tmp_path, monkeypatch):
        # Simulates a fully-unreachable judge endpoint (judge_logs() falls
        # back to EM for every miss): the CLI must echo the literal WARNING
        # text, not just embed degraded_to_em silently in the artifact.
        from click.testing import CliRunner

        from jseval import utility_judge as uj
        from jseval.cli import main

        fake_overlay = {
            "judge_identity": {"kind": "substring-em", "model": None,
                                "version": None, "prompt_hash": "x"},
            "stats": {
                "em_auto_pass": 3, "judged_misses": 0, "judge_flips": 0,
                "judge_disagreements": 0, "agreement_rate": None,
                "call_failures": 2, "degraded_to_em": True,
            },
            "scores": {},
        }
        monkeypatch.setattr(uj, "judge_logs", lambda *a, **k: fake_overlay)

        runner = CliRunner()
        result = runner.invoke(main, ["utility-judge", str(tmp_path)])
        assert result.exit_code == 0
        assert "WARNING: judge endpoint unreachable" in result.output
        assert "NOTE:" not in result.output  # fully-degraded is WARNING, not NOTE

    def test_partially_degraded_judge_prints_note_not_warning(self, tmp_path, monkeypatch):
        # Some (not all) judge calls failed: call_failures > 0 but judged > 0,
        # so degraded_to_em is False. The CLI must still surface the failure
        # count -- previously silent (only recoverable from the artifact).
        from click.testing import CliRunner

        from jseval import utility_judge as uj
        from jseval.cli import main

        fake_overlay = {
            "judge_identity": {"kind": "hybrid-em-llm", "model": "m",
                                "version": "m", "prompt_hash": "x"},
            "stats": {
                "em_auto_pass": 3, "judged_misses": 5, "judge_flips": 2,
                "judge_disagreements": 1, "agreement_rate": 0.8,
                "call_failures": 2, "degraded_to_em": False,
            },
            "scores": {},
        }
        monkeypatch.setattr(uj, "judge_logs", lambda *a, **k: fake_overlay)

        runner = CliRunner()
        result = runner.invoke(main, ["utility-judge", str(tmp_path)])
        assert result.exit_code == 0
        assert "WARNING:" not in result.output
        assert "NOTE: 2 of 7 judge calls failed and fell back to EM" in result.output


# --- agent-substitute heuristic raters ---------------------------------------


class TestRaterSubstituteHeuristics:
    def test_token_overlap_true_on_match(self):
        assert uj._rater_substitute_token_overlap("Q", "ANS0", "the answer is ANS0") is True

    def test_token_overlap_false_on_miss(self):
        assert uj._rater_substitute_token_overlap("Q", "ANS0", "totally unrelated") is False

    def test_containment_true_on_match(self):
        assert uj._rater_substitute_containment("Q", "ANS0", "the answer is ans0 here") is True

    def test_containment_false_on_miss(self):
        assert uj._rater_substitute_containment("Q", "ANS0", "totally unrelated") is False

    def test_two_heuristics_are_not_the_same_function(self):
        # Distinct decision rules is a design requirement (not the same check
        # under two names) -- construct an input where they diverge.
        # Reference has one short (<=2 char) token and one long token; candidate
        # contains the short token as a whole word but not the long anchor word.
        reference = "ok reallylongdistinctiveword"
        candidate = "ok but nothing else matches"
        overlap = uj._rater_substitute_token_overlap("Q", reference, candidate)
        contains = uj._rater_substitute_containment("Q", reference, candidate)
        assert (overlap, contains) == (True, False)


# --- end-to-end mechanism-proving dry run ------------------------------------


_COHORT = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
           "judge_kind": "substring-em", "prompt_template_hash": "p"}


def _calibration_dry_run_logs(tmp_path):
    """8-query EvalLog fixture: 2 EM-passes, 3 judge-rescued EM-misses (the
    disagreement stratum), 3 judge-confirmed EM-misses (agreement stratum)."""
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    answers = {
        "q0": "the answer is ANS0", "q1": "the answer is ANS1",
        "q2": "yellow RESCUE fruit", "q3": "yellow RESCUE fruit", "q4": "yellow RESCUE fruit",
        "q5": "totally wrong", "q6": "still wrong", "q7": "nope wrong",
    }

    @solver
    def fixed():
        async def solve(state, generate):
            qid = str(state.sample_id).split("|", 1)[-1]
            state.output.completion = answers[qid]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3})
            return state
        return solve

    @task
    def ct():
        samples = [Sample(id=f"C|q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"condition": "C"}) for i in range(8)]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "d", "signature": "s"}, "cohort": _COHORT})

    log = (tmp_path / "j").as_posix()
    eval_set([ct()], log_dir=log, epochs=1, model="mockllm/model", log_format="json")
    return log


def _fake_judge_post(url, json=None, timeout=None):
    """Both dual-order calls agree: YES iff the candidate contains RESCUE."""
    class _Resp:
        def __init__(self, c):
            self._c = c

        def json(self):
            return {"choices": [{"message": {"content": self._c}}]}

    user = json["messages"][1]["content"]
    return _Resp("YES" if "RESCUE" in user else "NO")


class TestCalibrationDryRunEndToEnd:
    def test_dry_run_end_to_end(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)

        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        overlay = uj.judge_logs(log, judge_url="http://x")
        assert overlay["stats"]["judge_flips"] == 3       # q2,q3,q4 rescued
        assert overlay["stats"]["judge_disagreements"] == 0

        uj.attach_human_calibration(overlay, log, n=6, seed=0)
        calib = overlay["human_calibration"]

        # The honesty check this whole feature exists for:
        assert calib["rater_kind"] == "agent-substitute, NOT human"
        assert calib["n"] == 6
        assert len(calib["sample_qids"]) == 6
        # All 3 disagreement (rescue) items are pulled into a 6-item sample
        # (default disagreement_frac=0.6 -> target 3.6, capped at the 3 that exist).
        rescued_keys = {f"C|0|q{i}" for i in (2, 3, 4)}
        assert rescued_keys.issubset(set(calib["sample_qids"]))

        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            block = calib[block_name]
            assert block["value"] is not None
            assert -1.0 <= block["value"] <= 1.0
            assert block["ci_low"] <= block["ci_high"]
            assert block["degenerate_pe"] in (True, False)

        # Round-trips through write_overlay/JSON like every other overlay field.
        path = uj.write_overlay(log, overlay)
        on_disk = json.loads(open(path, encoding="utf-8").read())
        assert on_disk["human_calibration"]["rater_kind"] == "agent-substitute, NOT human"
        assert on_disk["human_calibration"] == calib
        assert on_disk["scores"] == overlay["scores"]  # existing field untouched

    def test_dry_run_degrades_gracefully_with_too_small_a_sample(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        overlay = uj.judge_logs(log, judge_url="http://x")
        result = uj.run_calibration_dry_run(log, overlay, n=1, seed=0)
        assert result["rater_kind"] == "agent-substitute, NOT human"
        assert result["n"] <= 1
        assert result["judge_vs_rater_agreement"]["value"] is None
        assert "note" in result


# --- `utility-judge --calibrate` CLI reachability ----------------------------
#
# Gap 1 (tempdoc 624 hardening pass): `run_calibration_dry_run` / `attach_human_
# calibration` were correct and unit-tested but unreachable from any CLI command.
# These tests exercise the actual `jseval utility-judge` Click command end-to-end
# (not just the library functions) to prove the wiring, per the audit-driven-fixes-
# need-a-runnable-test discipline.


class TestUtilityJudgeCalibrateCli:
    def test_calibrate_flag_is_advertised_in_help(self):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, ["--help"])
        assert result.exit_code == 0
        assert "--calibrate" in result.output
        normalized = " ".join(result.output.split())
        # rater_kind honesty caveat surfaced in --help too (Click wraps long help
        # text across lines, so compare against whitespace-normalized output).
        assert "NOT real human raters" in normalized

    def test_calibrate_flag_attaches_human_calibration_to_the_written_overlay(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log, "--judge-url", "http://x",
            "--calibrate", "--calibration-n", "6", "--calibration-seed", "0",
        ])
        assert result.exit_code == 0, result.output
        assert "calibration (rater_kind='agent-substitute, NOT human'" in result.output

        on_disk = json.loads((Path(log) / "judge-overlay.json").read_text(encoding="utf-8"))
        assert "human_calibration" in on_disk
        hc = on_disk["human_calibration"]
        assert hc["rater_kind"] == "agent-substitute, NOT human"  # unconditional, not gated by the flag's value
        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            assert "degenerate_pe" in hc[block_name]

    def test_without_calibrate_flag_overlay_has_no_human_calibration_block(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [log, "--judge-url", "http://x"])
        assert result.exit_code == 0, result.output
        assert "calibration (" not in result.output

        on_disk = json.loads((Path(log) / "judge-overlay.json").read_text(encoding="utf-8"))
        assert "human_calibration" not in on_disk


# --- write-before-print ordering (independent-reviewer nit #2) ---------------
#
# `cmd_utility_judge`'s `output_dir` branch used to write the composed record
# to disk BEFORE its per-model click.echo print-summary loop; a session-624
# consolidation pass (splitting cli.py into commands/) moved the write to AFTER
# the print loop, so a crash in the print loop (e.g. a malformed `cell["accuracy"]`)
# now loses an already-computed record that would previously have survived. This
# regressed only `cmd_utility_judge` -- `cmd_utility_compose`/
# `cmd_utility_compose_cross_corpus` were unaffected by the consolidation (both
# already wrote after their own print loops, before AND after that session).


class TestUtilityJudgeWriteBeforePrintOrdering:
    def test_record_is_written_even_if_the_print_summary_loop_crashes(self, tmp_path, monkeypatch):
        """Forces a crash inside the per-model print loop (via a monkeypatched
        `click.echo` that raises on the `"  JUDGED"`-prefixed line) and asserts the
        composed record still landed on disk -- proving the write happens BEFORE
        the loop, not after. Needs a log dir with BOTH conditions (A and C) paired
        into a real cell -- `_calibration_dry_run_logs` above is condition-C-only
        (`measured` would be empty and the print loop would never run), so this
        reuses `test_agent_utility_run._leak_scan_logs` (both conditions, all EM
        correct -> no judge network calls needed)."""
        pytest.importorskip("inspect_ai")
        import click as _click

        from jseval.commands.utility import cmd_utility_judge
        from tests.test_agent_utility_run import _leak_scan_logs

        log = _leak_scan_logs(tmp_path)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        real_echo = _click.echo

        def _boom(message="", *args, **kwargs):
            if isinstance(message, str) and message.startswith("  JUDGED"):
                raise RuntimeError("simulated print-loop crash")
            return real_echo(message, *args, **kwargs)

        monkeypatch.setattr(_click, "echo", _boom)

        out_dir = tmp_path / "out"
        from click.testing import CliRunner

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log, "--judge-url", "http://x", "--output-dir", str(out_dir),
        ])
        assert result.exit_code != 0
        assert isinstance(result.exception, RuntimeError)

        written = out_dir / "utility-comparison.v1.json"
        assert written.exists(), "record must be written BEFORE the print loop, not after"


# --- cross-family LLM grader panel calibration (tempdoc 624 §M.9 "U-Founder-4
# revised") --------------------------------------------------------------------
#
# The founder-decided REPLACEMENT for bulk human labeling: a stratified sample
# graded by >= 2 external providers from DIFFERENT families than both the agent
# and the local judge, reporting their mutual cross-family kappa. Every HTTP
# call below is mocked (`monkeypatch.setattr(eg.httpx, "post", ...)`) -- no real
# network call is ever made by this test module.


def _fake_grader_post_agree(url, json=None, headers=None, timeout=None):
    """Both dual-order calls, for both graders, agree: YES iff the user message
    contains 'RESCUE' -- mirrors `_fake_judge_post` so the same
    `_calibration_dry_run_logs` fixture produces a comparable result shape."""

    class _R:
        def raise_for_status(self):
            pass

        def json(self):
            return {"choices": [{"message": {"content": self._c}}]}

        def __init__(self, c):
            self._c = c

    user = json["messages"][1]["content"]
    return _R("YES" if "RESCUE" in user else "NO")


class TestRunCrossFamilyCalibration:
    def _graders(self):
        from jseval import external_grader as eg
        return [
            eg.GraderConfig(name="gpt-class", endpoint_url="http://gpt.invalid/v1/chat",
                             model="gpt-family-model"),
            eg.GraderConfig(name="gemini-class", endpoint_url="http://gemini.invalid/v1/chat",
                             model="gemini-family-model"),
        ]

    def test_end_to_end_shape_and_honesty_stamp(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import external_grader as eg

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        monkeypatch.setattr(eg.httpx, "post", _fake_grader_post_agree)
        result = uj.run_cross_family_calibration(
            log, overlay, graders=self._graders(), n=6, seed=0)

        # The honesty check this whole feature exists for -- unconditional, not
        # gated by n, seed, graders, or any other input.
        assert result["rater_kind"] == "cross-family-llm, NOT human"
        assert result["graders"] == ["gpt-class", "gemini-class"]
        assert result["n"] == 6
        assert len(result["sample_qids"]) == 6
        assert result["n_abstained"] == 0  # both graders' dual-order calls always agree here

        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            block = result[block_name]
            assert block["value"] is not None
            assert -1.0 <= block["value"] <= 1.0
            assert block["ci_low"] <= block["ci_high"]
            assert block["degenerate_pe"] in (True, False)

        # Round-trips through write_overlay like every other additive overlay field.
        overlay["cross_family_calibration"] = result
        path = uj.write_overlay(log, overlay)
        on_disk = json.loads(open(path, encoding="utf-8").read())
        assert on_disk["cross_family_calibration"]["rater_kind"] == "cross-family-llm, NOT human"

    def test_requires_at_least_two_graders(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import external_grader as eg

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        one_grader = [eg.GraderConfig(name="only-one", endpoint_url="http://x", model="m")]
        with pytest.raises(ValueError):
            uj.run_cross_family_calibration(log, overlay, graders=one_grader, n=6, seed=0)

    def test_degrades_gracefully_with_too_small_a_sample(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        from jseval import external_grader as eg
        monkeypatch.setattr(eg.httpx, "post", _fake_grader_post_agree)
        result = uj.run_cross_family_calibration(
            log, overlay, graders=self._graders(), n=1, seed=0)
        # Even the degraded/too-small-sample path stamps the honesty field.
        assert result["rater_kind"] == "cross-family-llm, NOT human"
        assert result["n"] <= 1
        assert result["judge_vs_rater_agreement"]["value"] is None
        assert "note" in result

    def test_within_grader_dual_order_disagreement_drops_the_whole_item(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import external_grader as eg

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        # ref-first order says YES, candidate-first says NO -- every item's own
        # dual-order calls disagree, for both graders -> every item abstains.
        def _split_by_order(url, json=None, headers=None, timeout=None):
            user = json["messages"][1]["content"]
            ref_first = user.index("REFERENCE") < user.index("CANDIDATE")

            class _R:
                def raise_for_status(self):
                    pass

                def json(self):
                    return {"choices": [{"message": {"content": "YES" if ref_first else "NO"}}]}
            return _R()

        monkeypatch.setattr(eg.httpx, "post", _split_by_order)
        result = uj.run_cross_family_calibration(
            log, overlay, graders=self._graders(), n=6, seed=0)
        assert result["rater_kind"] == "cross-family-llm, NOT human"
        assert result["n"] == 0
        assert result["n_abstained"] == 6
        assert result["judge_vs_rater_agreement"]["value"] is None
        assert "note" in result

    def test_max_calls_budget_is_enforced(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import external_grader as eg

        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        monkeypatch.setattr(eg.httpx, "post", _fake_grader_post_agree)
        # 6 items * 2 graders * 2 (dual-order) = 24 calls needed; cap far below that.
        with pytest.raises(eg.GraderCallBudgetExceeded):
            uj.run_cross_family_calibration(
                log, overlay, graders=self._graders(), n=6, seed=0, max_calls=3)


# --- `utility-judge-cross-family` CLI reachability -----------------------------


class TestUtilityJudgeCrossFamilyCli:
    def _graders_config_file(self, tmp_path):
        cfg = [
            {"name": "gpt-class", "endpoint_url": "http://gpt.invalid/v1/chat",
             "model": "gpt-family-model", "price_per_call_usd": 0.01},
            {"name": "gemini-class", "endpoint_url": "http://gemini.invalid/v1/chat",
             "model": "gemini-family-model", "price_per_call_usd": 0.02},
        ]
        p = tmp_path / "graders.json"
        p.write_text(json.dumps(cfg), encoding="utf-8")
        return p

    def _overlay_log_dir(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")
        uj.write_overlay(log, overlay)
        return log

    def test_dry_run_without_yes_makes_no_network_call(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval import external_grader as eg
        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        graders_config = self._graders_config_file(tmp_path)

        def _boom(*a, **k):
            raise AssertionError("no --yes given -- must never call the network")
        monkeypatch.setattr(eg.httpx, "post", _boom)

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(graders_config), "--calibration-n", "6",
        ])
        assert result.exit_code == 0, result.output
        assert "cost estimate" in result.output
        assert "24 calls" in result.output  # 6 * 2 graders * 2 dual-order
        assert "NO network call was made" in result.output

        on_disk = json.loads((Path(log) / "judge-overlay.json").read_text(encoding="utf-8"))
        assert "cross_family_calibration" not in on_disk

    def test_yes_flag_runs_the_real_mocked_calibration_and_writes_overlay(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval import external_grader as eg
        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        graders_config = self._graders_config_file(tmp_path)
        monkeypatch.setattr(eg.httpx, "post", _fake_grader_post_agree)

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(graders_config), "--calibration-n", "6",
            "--calibration-seed", "0", "--yes",
        ])
        assert result.exit_code == 0, result.output
        assert "cross-family-llm, NOT human" in result.output
        assert "Written overlay to" in result.output

        on_disk = json.loads((Path(log) / "judge-overlay.json").read_text(encoding="utf-8"))
        hc = on_disk["cross_family_calibration"]
        assert hc["rater_kind"] == "cross-family-llm, NOT human"
        assert hc["graders"] == ["gpt-class", "gemini-class"]

    def test_missing_overlay_file_errors(self, tmp_path):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_cross_family

        empty_log = tmp_path / "empty"
        empty_log.mkdir()
        graders_config = self._graders_config_file(tmp_path)

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            str(empty_log), "--graders-config", str(graders_config),
        ])
        assert result.exit_code != 0
        assert "run `jseval utility-judge" in result.output

    def test_fewer_than_two_graders_errors(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        one_grader = tmp_path / "one_grader.json"
        one_grader.write_text(json.dumps(
            [{"name": "only-one", "endpoint_url": "http://x", "model": "m"}]), encoding="utf-8")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(one_grader),
        ])
        assert result.exit_code != 0
        assert ">= 2 grader config" in result.output

    # --- kind field: local-serial (tempdoc 674) -------------------------------

    def _mixed_graders_config_file(self, tmp_path, *, local_price=None):
        cfg = [
            {"name": "gpt-class", "endpoint_url": "http://gpt.invalid/v1/chat",
             "model": "gpt-family-model", "price_per_call_usd": 0.01},
            {"name": "llama-class", "kind": "local-serial",
             "model_path": "models/Llama-3.1-8B-Instruct-Q4_K_M.gguf"},
        ]
        if local_price is not None:
            cfg[1]["price_per_call_usd"] = local_price
        p = tmp_path / "graders.json"
        p.write_text(json.dumps(cfg), encoding="utf-8")
        return p

    def test_local_serial_kind_produces_a_zero_cost_dry_run(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval import external_grader as eg
        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        graders_config = self._mixed_graders_config_file(tmp_path)

        def _boom(*a, **k):
            raise AssertionError("no --yes given -- must never call the network")
        monkeypatch.setattr(eg.httpx, "post", _boom)

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(graders_config), "--calibration-n", "6",
        ])
        assert result.exit_code == 0, result.output
        assert "cost estimate" in result.output
        assert "llama-class: $0.0000" in result.output  # local grader never costs money
        assert "NO network call was made" in result.output

    def test_local_serial_with_nonzero_price_is_rejected(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        graders_config = self._mixed_graders_config_file(tmp_path, local_price=0.05)

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(graders_config),
        ])
        assert result.exit_code != 0
        assert "llama-class" in result.output
        assert "nonzero price_per_call_usd" in result.output

    def test_local_serial_missing_model_path_is_rejected(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        bad_config = tmp_path / "bad_graders.json"
        bad_config.write_text(json.dumps([
            {"name": "gpt-class", "endpoint_url": "http://gpt.invalid/v1/chat", "model": "m1"},
            {"name": "llama-class", "kind": "local-serial"},  # missing model_path
        ]), encoding="utf-8")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(bad_config),
        ])
        assert result.exit_code != 0
        assert "llama-class" in result.output
        assert "model_path" in result.output

    def test_unknown_kind_is_rejected(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = self._overlay_log_dir(tmp_path, monkeypatch)
        bad_config = tmp_path / "bad_graders.json"
        bad_config.write_text(json.dumps([
            {"name": "gpt-class", "endpoint_url": "http://gpt.invalid/v1/chat", "model": "m1"},
            {"name": "mystery-class", "kind": "carrier-pigeon", "model_path": "x"},
        ]), encoding="utf-8")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(bad_config),
        ])
        assert result.exit_code != 0
        assert "unknown kind" in result.output
        assert "carrier-pigeon" in result.output


# --- The column-level rater seam (tempdoc 674) --------------------------------


class _StubRater:
    """A minimal column-producer for testing `run_calibration`'s collection loop
    in isolation, independent of `_HeuristicRater`/`_EndpointRater`/`LocalSerialRater`."""

    def __init__(self, name: str, columns: dict):
        self.name = name
        self._columns = columns  # {key: bool | None}
        self.calls = []

    def label_sample(self, texts: dict) -> dict:
        self.calls.append(sorted(texts.keys()))
        return dict(self._columns)


class TestRunCalibrationSeam:
    def test_needs_at_least_two_raters(self, tmp_path):
        overlay = {"scores": {"C|0|q0": {"em": True, "judge": None, "final": True}}}
        with pytest.raises(ValueError, match=">= 2 independent raters"):
            uj.run_calibration(tmp_path.as_posix(), overlay, [_StubRater("solo", {})],
                                n=1, rater_kind="test-kind")
        with pytest.raises(ValueError, match=">= 2 independent raters"):
            uj.run_calibration(tmp_path.as_posix(), overlay, [], n=1, rater_kind="test-kind")

    def test_rater_kind_is_stamped_unconditionally(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        keys = uj.sample_for_calibration(overlay["scores"], n=6, seed=0)
        columns = {k: True for k in keys}
        raters = [_StubRater("r1", columns), _StubRater("r2", columns)]
        result = uj.run_calibration(log, overlay, raters, n=6, seed=0, rater_kind="a-custom-kind")
        assert result["rater_kind"] == "a-custom-kind"
        assert result["raters"] == ["r1", "r2"]

    def test_any_rater_abstaining_on_an_item_drops_the_whole_item(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        keys = uj.sample_for_calibration(overlay["scores"], n=6, seed=0)
        assert len(keys) >= 2
        abstained_key, rest = keys[0], keys[1:]
        col_a = {k: True for k in keys}
        col_b = {**{k: True for k in rest}, abstained_key: None}  # rater B abstains on ONE key
        raters = [_StubRater("r1", col_a), _StubRater("r2", col_b)]
        result = uj.run_calibration(log, overlay, raters, n=6, seed=0, rater_kind="test-kind")
        assert abstained_key not in result["sample_qids"]
        assert result["n_abstained"] == 1
        assert result["n"] == len(rest)

    def test_each_rater_produces_its_whole_column_before_the_next_starts(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        keys = uj.sample_for_calibration(overlay["scores"], n=6, seed=0)
        columns = {k: True for k in keys}
        r1, r2 = _StubRater("r1", columns), _StubRater("r2", columns)
        uj.run_calibration(log, overlay, [r1, r2], n=6, seed=0, rater_kind="test-kind")
        # each rater is called exactly once, with the FULL sample -- a column
        # producer's whole contribution happens in one turn, not interleaved
        # per item across raters (the seam that lets a GPU-serial rater own its
        # own load/label/unload turn -- tempdoc 674 §Long-term design).
        assert len(r1.calls) == 1
        assert len(r2.calls) == 1
        assert r1.calls[0] == r2.calls[0]


class TestCalibrationDryRunWrapperShapePreserved:
    def test_dry_run_result_has_no_abstain_or_raters_keys(self, tmp_path, monkeypatch):
        """`run_calibration_dry_run` is a thin wrapper over `run_calibration`, but
        its heuristic raters never abstain -- strip the generic `n_abstained`/
        `raters` keys so this function's original return shape is preserved
        exactly (tempdoc 674)."""
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
        overlay = uj.judge_logs(log, judge_url="http://x")

        result = uj.run_calibration_dry_run(log, overlay, n=6, seed=0)
        assert "n_abstained" not in result
        assert "raters" not in result
        assert "graders" not in result


# --- LocalSerialRater (tempdoc 674) -------------------------------------------


class _FakeResp:
    def __init__(self, body):
        self._body = body

    def raise_for_status(self):
        pass

    def json(self):
        return self._body


class _FakeHeadApi:
    """Minimal in-memory model of the Head-API surface `LocalSerialRater` drives
    (settings persistence + activation reload + the OpenAI-compat proxy).
    Mirrors the real routes' behavior closely enough to exercise the swap /
    assert-served / restore sequence without a live backend: settings are a
    partial merge (only `llm.modelPath` is tracked here), activating reloads
    whatever `llm.modelPath` currently holds into "served", and `/v1/models`
    reports whatever is currently "served" (or a stale value, if
    `fail_models_probe` is set, to simulate a failed/no-op swap)."""

    INITIAL_MODEL = "Qwen_Qwen3.5-9B-Q4_K_M.gguf"

    def __init__(self):
        self.settings = {
            "llm": {"modelPath": self.INITIAL_MODEL},
            "witness": {"acceptedRevision": 3, "lastCommittedOperationKey": "018cc251-f400-7000-8000-000000000001"},
        }
        self.served_model_path = self.INITIAL_MODEL
        self.activate_calls = []
        self.fail_models_probe = False
        self.calls = []

    def get(self, url, timeout=None):
        self.calls.append(("GET", url, self.settings.copy()))
        if url.endswith("/api/settings/v2"):
            return _FakeResp(self.settings)
        if url.endswith("/api/ai/runtime/status"):
            return _FakeResp({"active": {"activeVariantId": "cuda12"},
                               "activation": {"state": "completed", "message": ""}})
        if url.endswith("/v1/models"):
            reported = "STALE-model.gguf" if self.fail_models_probe else self.served_model_path
            return _FakeResp({"data": [{"id": Path(reported).name}]})
        raise AssertionError(f"unexpected GET {url}")

    def post(self, url, json=None, timeout=None, headers=None):
        self.calls.append(("POST", url, json))
        if url.endswith("/api/settings/v2"):
            llm = (json or {}).get("llm") or {}
            if "modelPath" in llm:
                self.settings["llm"]["modelPath"] = llm["modelPath"]
            return _FakeResp(self.settings)
        if url.endswith("/api/ai/runtime/activate"):
            self.activate_calls.append(json.get("variantId"))
            self.served_model_path = self.settings["llm"]["modelPath"]
            return _FakeResp({"activation": {"state": "running"}})
        if url.endswith("/v1/chat/completions"):
            user = json["messages"][1]["content"]
            content = "YES" if "RESCUE" in user else "NO"
            return _FakeResp({"choices": [{"message": {"content": content}}]})
        raise AssertionError(f"unexpected POST {url}")


class TestLocalSerialRater:
    _MODEL_PATH = "models/Llama-3.1-8B-Instruct-Q4_K_M.gguf"

    def _patch(self, monkeypatch, api):
        # A single assignment per method suffices -- `uj.httpx` and
        # `external_grader`'s `eg.httpx` are the same shared module object.
        monkeypatch.setattr(uj.httpx, "get", api.get)
        monkeypatch.setattr(uj.httpx, "post", api.post)

    def test_settings_posts_use_latest_witness_and_uuid_v7_key(self, monkeypatch):
        api = _FakeHeadApi()
        self._patch(monkeypatch, api)

        uj.LocalSerialRater("llama-grader", self._MODEL_PATH)._set_model_path(self._MODEL_PATH)

        settings_get = next(call for call in api.calls if call[0] == "GET" and call[1].endswith("/api/settings/v2"))
        settings_post = next(call for call in api.calls if call[0] == "POST" and call[1].endswith("/api/settings/v2"))
        body = settings_post[2]
        assert body["witness"] == settings_get[2]["witness"]
        assert body["witness"] == {
            "acceptedRevision": 3,
            "lastCommittedOperationKey": "018cc251-f400-7000-8000-000000000001",
        }
        key = body["operationKey"]
        assert re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", key)
        parsed = uuid.UUID(key)
        assert parsed.version == 7 and parsed.variant == uuid.RFC_4122

    def test_swaps_labels_and_restores(self, monkeypatch):
        api = _FakeHeadApi()
        self._patch(monkeypatch, api)
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH)
        texts = {"k1": {"question": "Q", "reference": "r", "candidate": "yellow RESCUE fruit"},
                 "k2": {"question": "Q", "reference": "r", "candidate": "totally wrong"}}
        column = rater.label_sample(texts)
        assert column == {"k1": True, "k2": False}
        assert api.settings["llm"]["modelPath"] == _FakeHeadApi.INITIAL_MODEL  # restored
        assert len(api.activate_calls) == 2  # one to swap in, one to restore

    def test_restores_on_labeling_exception(self, monkeypatch):
        # NOTE: `uj.httpx` and `external_grader`'s `eg.httpx` are the SAME shared
        # module object (both modules did `import httpx`) -- monkeypatching
        # `.get`/`.post` must be done ONCE per method with a single combined
        # router, not once "for uj" and once "for eg", or the second
        # `monkeypatch.setattr` silently clobbers the first (last-wins on the
        # shared attribute) instead of layering.
        api = _FakeHeadApi()

        def _post(url, json=None, headers=None, timeout=None):
            if url.endswith("/v1/chat/completions"):
                raise RuntimeError("simulated grading failure")
            return api.post(url, json=json, timeout=timeout, headers=headers)

        monkeypatch.setattr(uj.httpx, "get", api.get)
        monkeypatch.setattr(uj.httpx, "post", _post)

        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH)
        with pytest.raises(RuntimeError, match="simulated grading failure"):
            rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "x"}})
        assert api.settings["llm"]["modelPath"] == _FakeHeadApi.INITIAL_MODEL  # restored anyway

    def test_restore_failure_warns_instead_of_masking_the_original_error(self, monkeypatch):
        api = _FakeHeadApi()
        call_count = {"settings_posts": 0}

        def _post(url, json=None, timeout=None, headers=None):
            if url.endswith("/api/settings/v2"):
                call_count["settings_posts"] += 1
                if call_count["settings_posts"] == 2:  # the RESTORE settings call
                    raise RuntimeError("simulated network failure")
            return api.post(url, json=json, timeout=timeout, headers=headers)

        monkeypatch.setattr(uj.httpx, "get", api.get)
        monkeypatch.setattr(uj.httpx, "post", _post)

        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH)
        with pytest.warns(RuntimeWarning, match="failed to restore"):
            column = rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "x"}})
        assert column == {"k1": False}  # labeling itself succeeded; only restore failed

    def test_served_model_assertion_fails_loud_on_a_silently_failed_swap(self, monkeypatch):
        api = _FakeHeadApi()
        api.fail_models_probe = True
        self._patch(monkeypatch, api)
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH)
        with pytest.raises(RuntimeError, match="served-model assertion failed"):
            rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "x"}})
        # restore was still attempted even though the swap never got confirmed
        assert api.settings["llm"]["modelPath"] == _FakeHeadApi.INITIAL_MODEL

    def test_no_active_variant_raises_before_any_settings_mutation(self, monkeypatch):
        api = _FakeHeadApi()

        def _uj_get(url, timeout=None):
            if url.endswith("/api/ai/runtime/status"):
                return _FakeResp({"active": {}, "activation": {"state": "idle"}})
            return api.get(url, timeout=timeout)

        monkeypatch.setattr(uj.httpx, "get", _uj_get)
        monkeypatch.setattr(uj.httpx, "post", api.post)
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH)
        with pytest.raises(RuntimeError, match="no active runtime variant"):
            rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "x"}})
        assert api.calls == []  # failed before touching settings at all

    def test_keep_loaded_between_raters_skips_the_restore(self, monkeypatch):
        api = _FakeHeadApi()
        self._patch(monkeypatch, api)
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH,
                                     keep_loaded_between_raters=True)
        rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "yellow RESCUE fruit"}})
        # model stays swapped-in -- NOT restored to the original
        assert api.settings["llm"]["modelPath"] == self._MODEL_PATH
        assert len(api.activate_calls) == 1  # only the swap-in, no restore activate call

    def test_on_progress_callback_fires_in_order_with_expected_events(self, monkeypatch):
        api = _FakeHeadApi()
        self._patch(monkeypatch, api)
        events = []
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH,
                                     on_progress=lambda event, detail: events.append(event))
        rater.label_sample({
            "k1": {"question": "Q", "reference": "r", "candidate": "yellow RESCUE fruit"},
            "k2": {"question": "Q", "reference": "r", "candidate": "totally wrong"},
        })
        assert events == ["swap_start", "swap_complete", "item_labeled", "item_labeled",
                           "restore_start", "restore_complete"]

    def test_on_progress_reports_restore_skipped_when_keeping_loaded(self, monkeypatch):
        api = _FakeHeadApi()
        self._patch(monkeypatch, api)
        events = []
        rater = uj.LocalSerialRater("llama-grader", self._MODEL_PATH,
                                     keep_loaded_between_raters=True,
                                     on_progress=lambda event, detail: events.append(event))
        rater.label_sample({"k1": {"question": "Q", "reference": "r", "candidate": "x"}})
        assert events == ["swap_start", "swap_complete", "item_labeled", "restore_skipped"]


class TestEstimateLocalSerialPreflight:
    def test_reports_architecture_size_label_and_vram_fit_for_a_real_gguf(self, monkeypatch):
        from jseval._paths import shared_models_dir

        models_dir = shared_models_dir()
        path = models_dir / "Qwen_Qwen3.5-9B-Q4_K_M.gguf" if models_dir else None
        if path is None or not path.is_file():
            pytest.skip("Qwen_Qwen3.5-9B-Q4_K_M.gguf not found under shared_models_dir()")

        monkeypatch.setattr("jseval.env_fingerprint.probe_gpu_vram",
                             lambda: {"available": True, "mem_total_mb": 12282, "mem_used_mb": 1000})
        report = uj.estimate_local_serial_preflight([("llama-class", path.as_posix())])
        assert report["n_local_graders"] == 1
        entry = report["per_grader"][0]
        assert entry["architecture"] == "qwen35"
        assert entry["size_label"] == "9B"
        assert entry["vram_fit"] in ("likely_fits", "likely_too_large")
        assert report["estimated_swap_count"] == 2  # default swaps_per_rater=2, one grader
        assert report["estimated_time_sec"] > 0

    def test_missing_or_unparseable_gguf_fails_open_not_loud(self, tmp_path, monkeypatch):
        bogus = tmp_path / "not-a-model.gguf"
        bogus.write_bytes(b"not a real gguf file")
        monkeypatch.setattr("jseval.env_fingerprint.probe_gpu_vram",
                             lambda: {"available": False})
        report = uj.estimate_local_serial_preflight([("bad-grader", bogus.as_posix())])
        entry = report["per_grader"][0]
        assert "error" in entry
        assert entry["architecture"] is None
        assert entry["vram_fit"] == "unknown"
        assert report["gpu_available"] is False
        assert report["vram_total_bytes"] is None

    def test_swap_count_scales_with_grader_count_and_swaps_per_rater(self, tmp_path, monkeypatch):
        f1 = tmp_path / "a.gguf"
        f1.write_bytes(b"x" * 100)
        f2 = tmp_path / "b.gguf"
        f2.write_bytes(b"y" * 100)
        monkeypatch.setattr("jseval.env_fingerprint.probe_gpu_vram", lambda: {"available": False})
        report = uj.estimate_local_serial_preflight(
            [("g1", f1.as_posix()), ("g2", f2.as_posix())], swaps_per_rater=1)
        assert report["estimated_swap_count"] == 2  # 2 graders * 1 swap each
        assert report["n_local_graders"] == 2


class TestRunCrossFamilyCalibrationWithLocalSerialRater:
    def test_local_serial_rater_composes_with_an_endpoint_grader(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import external_grader as eg

        log = _calibration_dry_run_logs(tmp_path)
        # Scoped to just the overlay build: `_probe_judge_model` is also called
        # by `LocalSerialRater._assert_serving` below, and a module-wide stub
        # would shadow the fake Head API's own `/v1/models` response there.
        with monkeypatch.context() as m:
            m.setattr(uj.httpx, "post", _fake_judge_post)
            m.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")
            overlay = uj.judge_logs(log, judge_url="http://x")

        api = _FakeHeadApi()

        def _post(url, json=None, headers=None, timeout=None):
            # Settings/activate go through the fake Head-API state machine;
            # everything else (both graders' chat-completion calls, including
            # the "gpt-class" fake endpoint) goes through the same RESCUE-based
            # agree-fake the rest of this suite already uses. A single combined
            # router, since `uj.httpx`/`eg.httpx` are the same shared module.
            if url.endswith("/api/settings/v2") or url.endswith("/api/ai/runtime/activate"):
                return api.post(url, json=json, timeout=timeout, headers=headers)
            return _fake_grader_post_agree(url, json=json, headers=headers, timeout=timeout)

        monkeypatch.setattr(uj.httpx, "get", api.get)
        monkeypatch.setattr(uj.httpx, "post", _post)

        graders = [
            eg.GraderConfig(name="gpt-class", endpoint_url="http://gpt.invalid/v1/chat", model="m1"),
            uj.LocalSerialRater("llama-class", "models/Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
        ]
        result = uj.run_cross_family_calibration(log, overlay, graders=graders, n=6, seed=0)
        assert result["rater_kind"] == "cross-family-llm, NOT human"
        assert result["graders"] == ["gpt-class", "llama-class"]
        # the local grader's swap was performed and fully restored afterward
        assert api.settings["llm"]["modelPath"] == _FakeHeadApi.INITIAL_MODEL


# --- local-serial preflight report in the CLI dry-run block (tempdoc 674) -----


class TestLocalSerialPreflightInCli:
    def test_dry_run_prints_the_local_serial_preflight_report(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval import external_grader as eg
        from jseval.commands.utility import cmd_utility_judge_cross_family

        log = TestUtilityJudgeCrossFamilyCli()._overlay_log_dir(tmp_path, monkeypatch)
        graders_config = TestUtilityJudgeCrossFamilyCli()._mixed_graders_config_file(tmp_path)

        def _boom(*a, **k):
            raise AssertionError("no --yes given -- must never call the network")
        monkeypatch.setattr(eg.httpx, "post", _boom)
        monkeypatch.setattr("jseval.env_fingerprint.probe_gpu_vram",
                             lambda: {"available": True, "mem_total_mb": 12282, "mem_used_mb": 1000})

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_cross_family, [
            log, "--graders-config", str(graders_config), "--calibration-n", "6",
        ])
        assert result.exit_code == 0, result.output
        assert "local-serial preflight" in result.output
        assert "llama-class" in result.output
        assert "vram_fit=" in result.output


# --- swap smoke-test CLI command (tempdoc 674) --------------------------------


class TestUtilityJudgeLocalSwapSmoketestCli:
    def test_passing_swap_reports_success(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_local_swap_smoketest

        api = _FakeHeadApi()

        def _post(url, json=None, headers=None, timeout=None):
            if url.endswith("/v1/chat/completions"):
                return _FakeResp({"choices": [{"message": {"content": "YES"}}]})
            return api.post(url, json=json, timeout=timeout, headers=headers)

        monkeypatch.setattr(uj.httpx, "get", api.get)
        monkeypatch.setattr(uj.httpx, "post", _post)

        model_path = tmp_path / "some-model.gguf"
        model_path.write_bytes(b"fake gguf bytes")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_local_swap_smoketest, ["--model-path", str(model_path)])
        assert result.exit_code == 0, result.output
        assert "SMOKETEST PASSED" in result.output
        assert "verdict=True" in result.output
        # the swap actually happened and was restored -- not a no-op
        assert api.settings["llm"]["modelPath"] == _FakeHeadApi.INITIAL_MODEL
        assert len(api.activate_calls) == 2

    def test_failed_swap_reports_nonzero_exit_and_clear_message(self, tmp_path, monkeypatch):
        from click.testing import CliRunner

        from jseval.commands.utility import cmd_utility_judge_local_swap_smoketest

        def _uj_get(url, timeout=None):
            if url.endswith("/api/ai/runtime/status"):
                return _FakeResp({"active": {}, "activation": {"state": "idle"}})
            raise AssertionError(f"unexpected GET {url}")

        monkeypatch.setattr(uj.httpx, "get", _uj_get)

        model_path = tmp_path / "some-model.gguf"
        model_path.write_bytes(b"fake gguf bytes")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge_local_swap_smoketest, ["--model-path", str(model_path)])
        assert result.exit_code != 0
        assert "swap smoke test FAILED" in result.output
        assert "no active runtime variant" in result.output
