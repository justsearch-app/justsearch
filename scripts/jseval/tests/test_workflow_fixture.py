"""Tests for the Lane F workflow fixture (capture projection + structural differ).

No live backend: every capture here is synthetic and the two HTTP-path tests drive an
``httpx.MockTransport``. What is under test is the *equality relation* — that the three
allowed-difference classes behave as declared, that an undeclared field cannot slip
through, that an unknown class raises, and that a capture which recorded nothing cannot
diff clean.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

import httpx
import pytest
from click.testing import CliRunner

from jseval import workflow_fixture as wf
from jseval.cli import main
from jseval.commands.workflow_fixture import DEFAULT_FIXTURE


# ---------------------------------------------------------------------------
# The committed fixture definition
# ---------------------------------------------------------------------------

def test_committed_fixture_validates():
    """The shipped fixture is well-formed and every declared class is one of three."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert fixture["schema"] == wf.FIXTURE_SCHEMA
    for path, klass in fixture["fields"].items():
        assert klass == wf.EXACT or klass in wf.ALLOWED_DIFFERENCE_CLASSES, path
    assert len(wf.ALLOWED_DIFFERENCE_CLASSES) == 3


def test_committed_fixture_shape():
    """12 queries, 3 chat turns, exactly one of which is cancelled after session_started."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert len(fixture["queries"]) == 12
    assert all(q["limit"] == 10 for q in fixture["queries"])
    assert len(fixture["chatTurns"]) == 3
    cancelled = [t for t in fixture["chatTurns"] if t.get("cancelAfterEvent")]
    assert len(cancelled) == 1
    assert cancelled[0]["cancelAfterEvent"] == "session_started"


def test_committed_fixture_pins_the_agent_sampling():
    """PR 0b: the fixture declares the `sampling` override the capture sends.

    temperature 0 removes the sampler's freedom and the seed pins what freedom remains;
    without both, the chat fields declared `exact` diff because the sampler differed
    rather than because the build did (the agent path runs at 0.7/0.8 unpinned).
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    sampling = fixture["sampling"]
    assert sampling["temperature"] == 0.0
    assert isinstance(sampling["seed"], int) and not isinstance(sampling["seed"], bool)
    assert set(sampling) <= wf.SAMPLING_KEYS


def test_fixture_without_a_sampling_block_is_refused():
    """The pin is REQUIRED, not advisory — an unpinned fixture must not validate."""
    fixture = _minimal_fixture({"queries.a": "exact"})
    del fixture["sampling"]
    with pytest.raises(wf.WorkflowFixtureError, match="sampling"):
        wf.validate_fixture(fixture)
    for malformed in ({}, {"temp": 0.0}, {"temperature": "cold"}, {"seed": 1.5}, "0.0"):
        broken = _minimal_fixture({"queries.a": "exact"})
        broken["sampling"] = malformed
        with pytest.raises(wf.WorkflowFixtureError, match="sampling"):
            wf.validate_fixture(broken)


def test_hits_are_one_field_not_one_per_attribute():
    """Review blocker 1: per-attribute score-tagged lists permute independently.

    The declaration table must carry exactly ONE `queries.hits[]` entry — no
    `queries.hits[].<attribute>` entries other than the plain score list.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    hit_paths = {p for p in fixture["fields"] if p.startswith("queries.hits[]")}
    assert hit_paths == {"queries.hits[]"}
    assert fixture["fields"]["queries.hits[]"] == "equal-score-order"


def test_chat_error_codes_are_exact_not_new_reason_code():
    """Review fix 4: a NEW failure must not be an allowed difference."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert fixture["fields"]["chatTurns.errorCode"] == "exact"
    assert fixture["fields"]["chatTurns.toolErrorCodes"] == "exact"


def test_a_run_that_newly_fails_is_a_regression_under_the_declared_class():
    """The behaviour fix 4 buys: `new-reason-code` would wave a NEW failure through."""
    clean, failed = None, "BUDGET_EXHAUSTED"
    permissive = _minimal_fixture({"chatTurns.errorCode": "new-reason-code"})
    strict = _minimal_fixture({"chatTurns.errorCode": "exact"})
    base = _capture(chat={"c1": {"errorCode": clean}})
    cand = _capture(chat={"c1": {"errorCode": failed}})
    assert wf.diff(base, cand, permissive)["pass"] is True     # the pre-fix declaration
    assert wf.diff(base, cand, strict)["pass"] is False        # the shipped declaration
    assert wf.load_fixture(DEFAULT_FIXTURE)["fields"]["chatTurns.errorCode"] == "exact"


def test_cancel_http_status_caveat_is_recorded():
    """Review nit 7: the cancel endpoint returns 200 unconditionally.

    `cancel.httpStatus` is captured and diffed anyway (so a future status-bearing cancel
    contract is covered from the moment it lands), which makes the caveat load-bearing:
    a reader must not take a green cancel.httpStatus as proof the cancel took effect.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    notes = " ".join(fixture["notes"])
    assert "cancel.httpStatus carries no signal" in notes
    assert "AgentController.java:705-709" in notes
    for pinned in ("cancel.sessionState", "cancel.sessionDisposition",
                   "cancel.sessionCancelTrigger", "cancel.terminalEvent"):
        assert fixture["fields"][f"chatTurns.{pinned}"] == "exact"


def test_every_captured_field_path_is_declared():
    """The capture projection emits no field the declaration table does not cover.

    This is the lock the differ's undeclared-field regression exists to protect: if a
    capture field is added without a class, this fails here rather than surfacing as a
    mysterious REGRESSION during a gate run.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    declared = set(fixture["fields"])

    query_paths = {f"queries.{k}" for k in wf.capture_query_record(_SEARCH_RESPONSE)}
    chat_paths = {
        f"chatTurns.{k}"
        for k in wf.capture_chat_record(_CHAT_FRAMES, cancel={"requested": True})
    }
    undeclared = (query_paths | chat_paths) - declared
    assert not undeclared, f"captured but undeclared: {sorted(undeclared)}"
    # …and the reverse: no declaration describes a field the capture never emits.
    uncaptured = declared - (query_paths | chat_paths)
    assert not uncaptured, f"declared but never captured: {sorted(uncaptured)}"


def test_full_fixture_diffs_itself_clean():
    """A capture diffed against itself over the WHOLE committed fixture: all equal.

    Exercises every declared field path end to end (not just the ones the unit tests
    perturb), and asserts the declaration table has no dead entries.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    n_query_fields = sum(1 for p in fixture["fields"] if p.startswith("queries."))
    n_chat_fields = sum(1 for p in fixture["fields"] if p.startswith("chatTurns."))
    expected = (len(fixture["queries"]) * n_query_fields
                + len(fixture["chatTurns"]) * n_chat_fields)

    result = wf.diff(_full_snapshot(fixture), _full_snapshot(fixture), fixture)
    assert result["pass"] is True, result["health"]["problems"]
    assert result["counts"] == {"equal": expected, "allowed": 0,
                                "REGRESSION": 0, "missing": 0}
    assert result["declared_not_captured"] == []
    assert result["health"]["ok"] is True


def test_no_timing_field_is_captured():
    """Timing is dropped at capture time rather than given a class."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    captured = set(wf.capture_query_record(_SEARCH_RESPONSE)) | set(
        wf.capture_chat_record(_CHAT_FRAMES))
    for name in captured | set(fixture["fields"]):
        lowered = name.lower()
        assert "ms" != lowered.split(".")[-1]
        assert "latency" not in lowered
        assert "tookms" not in lowered
        assert "elapsed" not in lowered


# ---------------------------------------------------------------------------
# Fixture validation
# ---------------------------------------------------------------------------

def test_unknown_class_in_fixture_raises():
    fixture = _minimal_fixture({"queries.a": "timing-noise"})
    with pytest.raises(wf.WorkflowFixtureError, match="not one of the three"):
        wf.validate_fixture(fixture)


def test_unknown_class_in_compare_raises():
    with pytest.raises(wf.WorkflowFixtureError, match="unknown allowed-difference class"):
        wf.compare_field("timing-noise", 1, 2)


def test_wrong_schema_raises():
    fixture = _minimal_fixture({"queries.a": "exact"})
    fixture["schema"] = "something.else"
    with pytest.raises(wf.WorkflowFixtureError, match="fixture schema"):
        wf.validate_fixture(fixture)


# ---------------------------------------------------------------------------
# The four statuses
# ---------------------------------------------------------------------------

def test_status_equal():
    fixture = _minimal_fixture({"queries.a": "exact"})
    result = wf.diff(_capture({"q1": {"a": 1}}), _capture({"q1": {"a": 1}}), fixture)
    assert result["pass"] is True
    assert _status(result, "queries.a") == "equal"


def test_status_allowed():
    fixture = _minimal_fixture({"queries.a": "generative-text"})
    result = wf.diff(_capture({"q1": {"a": "x"}}), _capture({"q1": {"a": "y"}}), fixture)
    assert result["pass"] is True
    assert _status(result, "queries.a") == "allowed:generative-text"
    assert result["allowed_by_class"]["generative-text"] == 1


def test_status_regression_on_exact_field():
    fixture = _minimal_fixture({"queries.a": "exact"})
    result = wf.diff(_capture({"q1": {"a": 1}}), _capture({"q1": {"a": 2}}), fixture)
    assert result["pass"] is False
    entry = _entry(result, "queries.a")
    assert entry["status"] == "REGRESSION"
    assert (entry["baseline"], entry["candidate"]) == (1, 2)


def test_status_missing_when_absent_from_one_side():
    fixture = _minimal_fixture({"queries.a": "exact", "queries.b": "exact"})
    result = wf.diff(_capture({"q1": {"a": 1, "b": 2}}), _capture({"q1": {"a": 1}}), fixture)
    assert result["pass"] is False
    assert _status(result, "queries.b") == "missing"
    assert result["counts"]["missing"] == 1


def test_missing_record_reports_every_field_missing():
    fixture = _minimal_fixture({"queries.a": "exact"})
    base = _capture({"q1": {"a": 1}, "q2": {"a": 1}})
    result = wf.diff(base, _capture({"q1": {"a": 1}}), fixture)
    assert result["pass"] is False
    assert [e["status"] for e in result["fields"] if e["record"] == "queries/q2"] == [
        "missing"] * 3


# ---------------------------------------------------------------------------
# equal-score-order (whole-hit values)
# ---------------------------------------------------------------------------

def _tagged(pairs):
    return [{"score": s, "value": v} for s, v in pairs]


def _hit(hid, **overrides):
    base = {"id": hid, "path": f"docs/{hid}.md", "chunkSpan": "0:512",
            "extractionReasonCode": None}
    base.update(overrides)
    return base


def test_equal_score_group_reordering_is_allowed():
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged(
        [(2.0, _hit("a")), (1.0, _hit("b")), (1.0, _hit("c"))])}})
    cand = _capture({"q1": {"hits[]": _tagged(
        [(2.0, _hit("a")), (1.0, _hit("c")), (1.0, _hit("b"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order"


def test_swapped_chunk_spans_inside_a_tie_group_is_a_regression():
    """Review blocker 1, reproduced: ids stay put, chunk spans swap.

    With one score-tagged list per attribute this diffed as `allowed:equal-score-order`.
    With the whole hit as one value the members no longer pair, so it is a REGRESSION.
    """
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged([
        (1.0, _hit("b", chunkSpan="0:512")),
        (1.0, _hit("c", chunkSpan="512:1024")),
    ])}})
    cand = _capture({"q1": {"hits[]": _tagged([
        (1.0, _hit("b", chunkSpan="512:1024")),
        (1.0, _hit("c", chunkSpan="0:512")),
    ])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    entry = _entry(result, "queries.hits[]")
    assert entry["status"] == "REGRESSION"
    assert "not a permutation" in entry["reason"]


def test_swapped_excerpt_spans_inside_a_tie_group_is_a_regression():
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged([
        (1.0, _hit("b", excerptSpans=["4:40"])),
        (1.0, _hit("c", excerptSpans=["9:90"])),
    ])}})
    cand = _capture({"q1": {"hits[]": _tagged([
        (1.0, _hit("b", excerptSpans=["9:90"])),
        (1.0, _hit("c", excerptSpans=["4:40"])),
    ])}})
    assert wf.diff(base, cand, fixture)["pass"] is False


def test_reordering_across_different_scores_is_a_regression():
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged([(2.0, _hit("a")), (1.0, _hit("b"))])}})
    cand = _capture({"q1": {"hits[]": _tagged([(1.0, _hit("b")), (2.0, _hit("a"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    # 2.0 vs 1.0 is far outside the epsilon, so both sides partition as two singletons and
    # the swap surfaces as a per-group membership change rather than a partition change.
    assert "not a permutation" in _entry(result, "queries.hits[]")["reason"]


def test_changed_member_within_a_tie_group_is_a_regression():
    """Permutation is allowed; a different document is not."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged([(1.0, _hit("b")), (1.0, _hit("c"))])}})
    cand = _capture({"q1": {"hits[]": _tagged([(1.0, _hit("b")), (1.0, _hit("d"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "not a permutation" in _entry(result, "queries.hits[]")["reason"]


def test_reordering_is_only_allowed_under_equal_score_order():
    """The same permutation declared `exact` is a regression."""
    fixture = _minimal_fixture({"queries.hits[]": "exact"})
    base = _capture({"q1": {"hits[]": _tagged([(1.0, _hit("b")), (1.0, _hit("c"))])}})
    cand = _capture({"q1": {"hits[]": _tagged([(1.0, _hit("c")), (1.0, _hit("b"))])}})
    assert wf.diff(base, cand, fixture)["pass"] is False


def test_non_score_tagged_value_under_equal_score_order_is_a_regression():
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": ["b", "c"]}})
    cand = _capture({"q1": {"hits[]": ["c", "b"]}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "score-tagged" in _entry(result, "queries.hits[]")["reason"]


def test_jittering_scores_do_not_make_a_diff(monkeypatch):
    """Live finding 1: scores jitter in the 3rd decimal on ONE build.

    Same hits, same order, scores nudged by less than the epsilon -> equal. With scores
    compared (the pre-fix behaviour) this was a REGRESSION on every query.
    """
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged(
        [(0.704289, _hit("a")), (0.502373, _hit("b"))])}})
    cand = _capture({"q1": {"hits[]": _tagged(
        [(0.704254, _hit("a")), (0.499312, _hit("b"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order"


def test_hits_score_is_not_a_declared_or_captured_field():
    """A declared score field would be a REGRESSION on every query (113/120 jittered)."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert "queries.hits[].score" not in fixture["fields"]
    assert "hits[].score" not in wf.capture_query_record(_SEARCH_RESPONSE)
    # …but the observed values are still recoverable for the reader.
    assert wf.observed_query_scores(_SEARCH_RESPONSE) == [2.0, 1.0]


def test_partition_change_without_reordering_is_not_a_regression():
    """The q07 false positive: identical hits, identical ORDER, a gap crossing the epsilon.

    Live evidence (tmp/fixture-capture-{1,2}.json, q07): the hits were identical in identity
    and order, but the gap between ranks 7 and 8 moved 0.010470 -> 0.009788, so an
    unconditional partition comparison flipped ten singletons to [...,2] and called it a
    REGRESSION with nothing changed. The partition is only consulted when something
    actually swapped.
    """
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.489530, _hit("b"))])}})   # gap 0.010470 -> two groups
    cand = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.490212, _hit("b"))])}})   # gap 0.009788 -> one group
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]


def test_partition_change_WITH_reordering_is_a_regression():
    """A hit that crossed a tie-group boundary AND moved is the real ordering change."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.505, _hit("b")), (0.300, _hit("c"))])}})
    cand = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.300, _hit("c")), (0.505, _hit("b"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "tie-group partition differs" in _entry(result, "queries.hits[]")["reason"]


def test_a_changed_hit_at_the_same_position_is_still_caught():
    """The order-unchanged shortcut must not let a swapped-in document through."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.400, _hit("b"))])}})
    cand = _capture({"q1": {"hits[]": _tagged(
        [(0.500, _hit("a")), (0.400, _hit("ZZZ"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "not a permutation" in _entry(result, "queries.hits[]")["reason"]


def test_tie_group_is_measured_from_the_group_first_member():
    """A chain of small steps must not accumulate into an arbitrarily wide group."""
    ok, groups = wf._tie_groups(
        _tagged([(1.00, "a"), (0.995, "b"), (0.99, "c"), (0.985, "d")]), 0.01)
    assert ok
    # a/b/c are within 0.01 of a; d is 0.015 from a, so it starts a new group.
    # c sits EXACTLY at the epsilon, where abs(0.99 - 1.00) is 0.010000000000000009 in
    # IEEE 754 — the boundary is inclusive, and not at the mercy of representation error.
    assert [len(m) for _, m in groups] == [3, 1]


def test_tie_boundary_is_inclusive_despite_float_representation():
    assert wf._within(0.99, 1.00, 0.01) is True      # bare <= would say False
    assert wf._within(0.985, 1.00, 0.01) is False
    assert wf._within(1.00, 1.00, 0.0) is True       # epsilon 0 still matches exact ties


def test_fixture_must_declare_score_tie_epsilon():
    fixture = _minimal_fixture({"queries.a": "exact"})
    del fixture["scoreTieEpsilon"]
    with pytest.raises(wf.WorkflowFixtureError, match="scoreTieEpsilon"):
        wf.validate_fixture(fixture)
    for bad in (-0.1, "0.01", True, None):
        fixture["scoreTieEpsilon"] = bad
        with pytest.raises(wf.WorkflowFixtureError, match="scoreTieEpsilon"):
            wf.validate_fixture(fixture)


def test_committed_epsilon_matches_the_recorded_measurement():
    """The epsilon and the measurement that justifies it must not drift apart."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert fixture["scoreTieEpsilon"] == 0.01
    notes = " ".join(fixture["notes"])
    assert "0.009442" in notes           # the measured max identity-matched jitter
    assert "0.003652" in notes           # the smallest adjacent score gap
    assert fixture["scoreTieEpsilon"] > 0.009442


def test_none_score_is_a_shape_violation_not_a_universal_tie():
    """Review nit 9: all-None scores would otherwise collapse into one free-order group."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": {"hits[]": _tagged([(None, _hit("b")), (None, _hit("c"))])}})
    cand = _capture({"q1": {"hits[]": _tagged([(None, _hit("c")), (None, _hit("b"))])}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "numeric-scored" in _entry(result, "queries.hits[]")["reason"]


def test_new_reason_code_on_a_hit_is_allowed_and_a_lost_one_is_not():
    """The one new-reason-code sub-key inside the hits[] projection."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    plain = _tagged([(1.0, _hit("b"))])
    coded = _tagged([(1.0, _hit("b", extractionReasonCode="OCR_FALLBACK"))])

    gained = wf.diff(_capture({"q1": {"hits[]": plain}}),
                     _capture({"q1": {"hits[]": coded}}), fixture)
    assert gained["pass"] is True
    assert _status(gained, "queries.hits[]") == "allowed:new-reason-code"

    lost = wf.diff(_capture({"q1": {"hits[]": coded}}),
                   _capture({"q1": {"hits[]": plain}}), fixture)
    assert lost["pass"] is False
    assert "gone in the candidate" in _entry(lost, "queries.hits[]")["reason"]


# ---------------------------------------------------------------------------
# new-reason-code
# ---------------------------------------------------------------------------

def test_reason_code_only_in_candidate_is_allowed():
    fixture = _minimal_fixture({"queries.trace.stageReasons": "new-reason-code"})
    base = _capture({"q1": {"trace.stageReasons": {"0:fusion": None}}})
    cand = _capture({"q1": {"trace.stageReasons": {"0:fusion": "ENCODER_RELOADING"}}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True
    entry = _entry(result, "queries.trace.stageReasons")
    assert entry["status"] == "allowed:new-reason-code"
    assert "ENCODER_RELOADING" in entry["reason"]


def test_reason_code_only_in_baseline_is_a_regression():
    fixture = _minimal_fixture({"queries.trace.stageReasons": "new-reason-code"})
    base = _capture({"q1": {"trace.stageReasons": {"0:fusion": "VECTOR_BLOCKED"}}})
    cand = _capture({"q1": {"trace.stageReasons": {"0:fusion": None}}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    entry = _entry(result, "queries.trace.stageReasons")
    assert entry["status"] == "REGRESSION"
    assert "VECTOR_BLOCKED" in entry["reason"]


def test_relocated_reason_code_is_a_regression():
    """Review fix 3: the same code under a DIFFERENT stage is not "the set is unchanged"."""
    fixture = _minimal_fixture({"queries.trace.stageReasons": "new-reason-code"})
    base = _capture({"q1": {"trace.stageReasons": {
        "0:dense-retrieval": "VECTOR_BLOCKED", "1:fusion": None}}})
    cand = _capture({"q1": {"trace.stageReasons": {
        "0:dense-retrieval": None, "1:fusion": "VECTOR_BLOCKED"}}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    assert "0:dense-retrieval=VECTOR_BLOCKED" in _entry(
        result, "queries.trace.stageReasons")["reason"]


def test_reason_codes_carry_dict_keys_but_not_list_positions():
    assert wf._reason_codes({"a": "X", "b": None}) == {"a=X"}
    assert wf._reason_codes(["X", None, "Y"]) == {"X", "Y"}
    assert wf._reason_codes(None) == set()


def test_changed_reason_code_is_a_regression():
    """A code swapped for a different one loses the baseline's code — a regression."""
    fixture = _minimal_fixture({"chatTurns.cancel.terminalReasonCode": "new-reason-code"})
    base = _capture(chat={"c1": {"cancel.terminalReasonCode": "BUDGET_EXHAUSTED"}})
    cand = _capture(chat={"c1": {"cancel.terminalReasonCode": "ADMISSION_REJECTED"}})
    assert wf.diff(base, cand, fixture)["pass"] is False


# ---------------------------------------------------------------------------
# generative-text and undeclared fields
# ---------------------------------------------------------------------------

def test_generative_text_differs_is_allowed():
    fixture = _minimal_fixture({"chatTurns.finalResponse": "generative-text"})
    base = _capture(chat={"c1": {"finalResponse": "The worker owns Lucene I/O."}})
    cand = _capture(chat={"c1": {"finalResponse": "All index I/O lives in the worker."}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True
    assert _status(result, "chatTurns.finalResponse") == "allowed:generative-text"


def test_undeclared_field_is_a_regression():
    """A newly captured field with no declared class cannot slip through."""
    fixture = _minimal_fixture({"queries.a": "exact"})
    base = _capture({"q1": {"a": 1, "brandNew": "x"}})
    cand = _capture({"q1": {"a": 1, "brandNew": "x"}})
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    entry = _entry(result, "queries.brandNew")
    assert entry["status"] == "REGRESSION"
    assert "not declared" in entry["reason"]


# ---------------------------------------------------------------------------
# Capture health (review blocker 2)
# ---------------------------------------------------------------------------

def test_all_non_200_captures_do_not_diff_clean():
    """Two identical failures are byte-equal — the health check is what catches it."""
    fixture = _minimal_fixture({"queries.a": "exact"})
    broken = _capture({"q1": {"a": None, "httpStatus": 401, "hitCount": 0}})
    result = wf.diff(broken, broken, fixture)
    assert result["pass"] is False
    assert result["counts"]["REGRESSION"] == 0  # nothing DIFFERED — health is the verdict
    assert result["health"]["ok"] is False
    joined = " ".join(result["health"]["problems"])
    assert "httpStatus 401" in joined
    assert "hitCount 0" in joined


def test_zero_hit_query_fails_health():
    fixture = _minimal_fixture({"queries.a": "exact"})
    empty = _capture({"q1": {"a": 1, "hitCount": 0}})
    result = wf.diff(empty, empty, fixture)
    assert result["pass"] is False
    assert any("hitCount 0" in p for p in result["health"]["problems"])


def test_section_empty_on_both_sides_fails_health():
    fixture = _minimal_fixture({"queries.a": "exact"})
    doc = _capture({"q1": {"a": 1}})
    doc["chatTurns"] = {}
    result = wf.diff(doc, doc, fixture)
    assert result["pass"] is False
    assert any("'chatTurns' is empty in BOTH" in p for p in result["health"]["problems"])


def test_declared_but_uncaptured_path_fails_health():
    fixture = _minimal_fixture({"queries.a": "exact", "queries.zzz": "exact"})
    result = wf.diff(_capture({"q1": {"a": 1}}), _capture({"q1": {"a": 1}}), fixture)
    assert result["pass"] is False
    assert result["declared_not_captured"] == ["queries.zzz"]
    assert any("never captured" in p for p in result["health"]["problems"])


def test_max_iterations_turn_fails_health():
    """Live finding 2: a truncated turn's citations are partial by construction."""
    fixture = _minimal_fixture({"chatTurns.disposition": "exact"})
    truncated = _capture(chat={"c1": {"disposition": "MAX_ITERATIONS",
                                      "iterationsUsed": 3, "cancel.requested": False}})
    result = wf.diff(truncated, truncated, fixture)
    assert result["pass"] is False
    assert any("chat turn ended MAX_ITERATIONS" in p for p in result["health"]["problems"])

    completed = _capture(chat={"c1": {"disposition": "COMPLETED",
                                      "iterationsUsed": 2, "cancel.requested": False}})
    assert wf.diff(completed, completed, fixture)["health"]["ok"] is True


def test_cancelled_turn_is_exempt_from_the_max_iterations_rule():
    """The cancelled turn is SUPPOSED to end early — only ordinary turns must complete."""
    fixture = _minimal_fixture({"chatTurns.disposition": "exact"})
    cancelled = _capture(chat={"c1": {"disposition": "MAX_ITERATIONS",
                                      "cancel.requested": True}})
    assert wf.diff(cancelled, cancelled, fixture)["health"]["ok"] is True


def test_ordinary_turns_are_given_room_to_complete():
    """Both live captures truncated at maxIterations 3 on the compact model."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    ordinary = [t for t in fixture["chatTurns"] if not t.get("cancelAfterEvent")]
    assert len(ordinary) == 2
    assert all(t["maxIterations"] == 8 for t in ordinary)
    notes = " ".join(fixture["notes"])
    assert "MEANT TO COMPLETE" in notes


def test_generation_nondeterminism_finding_is_recorded():
    """Live finding 4: citationTargets stays `exact`; the finding is recorded, not hidden.

    The note must name the sampling the AGENT path actually runs under. It is not
    `ConversationEngine`'s hard-coded 0.8/0.95 — an agent turn is shape-driven and never
    reaches `parseSamplingParams`; it samples under `SamplingParams.AGENT` (0.7 / 0.8)
    returned by `AgentLlmCaller.resolveAgentSampling`.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert fixture["fields"]["chatTurns.citationTargets"] == "exact"
    notes = " ".join(fixture["notes"])
    assert "AgentLlmCaller.resolveAgentSampling" in notes
    assert "SamplingParams.AGENT" in notes
    assert "temperature 0.7, top_p 0.8" in notes
    assert "owner decision" in notes or "owner" in notes
    # The superseded claim must be gone, not merely outnumbered.
    assert "ConversationEngine.java:1154" not in notes
    assert "SamplingParams(0.8, 0.95" not in notes
    # The cancelled turn WAS stable across both captures — say so, don't imply otherwise.
    assert "CANCELLED turn's fields were fully stable" in notes


def test_one_capture_per_fresh_corpus_is_recorded():
    """Live finding 3: the chat turns index agent history, moving the index under a re-run."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    assert "ONE CAPTURE PER FRESH CORPUS" in fixture["corpus"]
    assert "91 -> 102" in fixture["corpus"]


def test_healthy_capture_passes_health():
    fixture = _minimal_fixture({"queries.a": "exact"})
    result = wf.diff(_capture({"q1": {"a": 1}}), _capture({"q1": {"a": 1}}), fixture)
    assert result["health"] == {"ok": True, "problems": []}


def test_capture_refuses_when_every_query_is_rejected(tmp_path: Path):
    """Review blocker 2, capture side: an all-401 artifact is never written."""
    fixture = _minimal_fixture({"queries.a": "exact"})

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/status":
            return httpx.Response(200, json={"service": "justsearch"})
        return httpx.Response(401, json={"error": "missing session token"})

    client_kwargs = {"transport": httpx.MockTransport(handler)}
    with pytest.raises(wf.WorkflowFixtureError, match="fixture query was rejected"):
        _capture_with_transport(fixture, tmp_path / "out.json", client_kwargs,
                                corpus_root=_ROOT)
    assert not (tmp_path / "out.json").exists()


def test_capture_refuses_when_one_query_is_rejected(tmp_path: Path):
    """Re-review nit: a partial capture (one 503 among successes) is refused at the source too,
    since capture_health would fail it anyway and a stored optimistic artifact misleads."""
    fixture = _minimal_fixture({"queries.a": "exact"})
    fixture["queries"] = [
        {"id": "q1", "query": "one", "limit": 10, "mode": "hybrid"},
        {"id": "q2", "query": "two", "limit": 10, "mode": "hybrid"},
    ]
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/status":
            return httpx.Response(200, json={"service": "justsearch"})
        body = json.loads(request.content.decode("utf-8"))
        seen.append(body["query"])
        if body["query"] == "two":
            return httpx.Response(503, json={"error": "reloading"})
        return httpx.Response(200, json={"totalHits": 1, "matchCount": 1, "results": [], "searchTrace": {}})

    client_kwargs = {"transport": httpx.MockTransport(handler)}
    with pytest.raises(wf.WorkflowFixtureError, match=r"statuses \[200, 503\]"):
        _capture_with_transport(fixture, tmp_path / "out.json", client_kwargs,
                                corpus_root=_ROOT)
    assert seen == ["one", "two"]
    assert not (tmp_path / "out.json").exists()


# ---------------------------------------------------------------------------
# SSE parsing
# ---------------------------------------------------------------------------

_SYNTHETIC_STREAM = (
    "event: session_started\n"
    'data: {"sessionId":"sess-1"}\n'
    "\n"
    "event: heartbeat\n"
    'data: {"t":1}\n'
    "\n"
    "event: tool_exec_started\n"
    'data: {"callId":"c1","toolName":"search"}\n'
    "\n"
    "event: done\n"
    'data: {"finalResponse":"The worker owns index I/O.","iterationsUsed":1,'
    '"toolCallsExecuted":1,"totalTokensUsed":42,'
    '"sources":[{"parentDocId":"docA","chunkIndex":3,"path":"docs/explanation/01.md",'
    '"excerpt":"…","startLine":10,"endLine":20,"contextInclusion":"FULL",'
    '"contextIncludedChars":512}],'
    '"citations":[{"sentenceText":"The worker owns index I/O.","sourceIndex":0,'
    '"similarity":0.91}],"citationScorer":"cross-encoder","disposition":"COMPLETED"}\n'
    "\n"
)


def test_parse_sse_frames_on_a_synthetic_stream():
    frames = wf.parse_sse_frames(_SYNTHETIC_STREAM)
    assert [e for e, _ in frames] == [
        "session_started", "heartbeat", "tool_exec_started", "done"]
    assert frames[0][1]["sessionId"] == "sess-1"
    assert frames[-1][1]["citations"][0]["sourceIndex"] == 0


def test_sse_buffer_is_incremental_and_crlf_tolerant():
    buf = wf.SseBuffer()
    assert buf.feed("event: session_st") == []
    assert buf.feed('arted\r\ndata: {"sessionId":"s"}\r\n\r\n') == [
        ("session_started", {"sessionId": "s"})]


def test_crlf_split_across_chunks_does_not_fabricate_a_frame():
    """Review nit 8a: a trailing lone '\\r' is held back, not normalised to '\\n'.

    The CRLF between a frame's `event:` and `data:` lines lands on a chunk boundary.
    Normalising the lone '\\r' immediately turns it into '\\n', so the next chunk's leading
    '\\n' completes a blank line INSIDE the frame: the event line and the data line are
    split into two frames, and the payload is lost from the event it belongs to.
    """
    buf = wf.SseBuffer()
    assert buf.feed("event: session_started\r") == []
    assert buf.feed('\ndata: {"sessionId":"s"}\r\n\r\n') == [
        ("session_started", {"sessionId": "s"}),
    ]


def test_sse_frame_without_trailing_blank_line_is_flushed():
    buf = wf.SseBuffer()
    buf.feed('event: done\ndata: {"finalResponse":"x"}')
    assert buf.flush() == [("done", {"finalResponse": "x"})]


def test_unparseable_terminal_payload_is_named():
    """Review nit 8b: `done` with a non-object payload must not read as a silent success."""
    frames = wf.parse_sse_frames(
        "event: session_started\ndata: {}\n\nevent: done\ndata: not-json\n\n")
    record = wf.capture_chat_record(frames)
    assert record["terminalEvent"] == "done"
    assert record["terminalPayloadParseError"] is True
    assert record["disposition"] is None

    clean = wf.capture_chat_record(wf.parse_sse_frames(_SYNTHETIC_STREAM))
    assert clean["terminalPayloadParseError"] is False


def test_capture_chat_record_drops_heartbeat_and_resolves_citations():
    record = wf.capture_chat_record(wf.parse_sse_frames(_SYNTHETIC_STREAM))
    assert "heartbeat" not in record["eventNames"]
    assert record["terminalEvent"] == "done"
    assert record["toolNames"] == ["search"]
    assert record["citationTargets"] == ["docA#3"]
    assert record["sourceRefs"] == ["docA#3"]
    assert record["sourceSpans"] == ["10:20"]
    assert record["sourceContextIncludedChars"] == [512]
    assert record["disposition"] == "COMPLETED"
    assert record["cancel.requested"] is False


def test_capture_chat_record_collapses_token_events():
    frames = [
        ("session_started", {"sessionId": "s"}),
        ("chunk", {"text": "a"}),
        ("chunk", {"text": "b"}),
        ("reasoning_chunk", {"text": "r"}),
        ("chunk", {"text": "c"}),
        ("done", {"finalResponse": "abc"}),
    ]
    record = wf.capture_chat_record(frames)
    assert record["eventNames"] == [
        "session_started", "chunk", "reasoning_chunk", "chunk", "done"]
    assert record["reasoningText"] == "r"


def test_capture_query_record_projection():
    record = wf.capture_query_record(_SEARCH_RESPONSE)
    assert record["hitCount"] == 2
    assert "hits[].score" not in record          # jitters; never diffed
    assert [t["score"] for t in record["hits[]"]] == [2.0, 1.0]   # kept for grouping only
    first = record["hits[]"][0]["value"]
    assert first["path"] == "docs/explanation/01.md"
    assert first["chunkSpan"] == "0:512"
    assert first["excerptSpans"] == ["4:40"]
    assert first["extractionReasonCode"] == "OK"
    assert record["trace.stageIds"] == ["sparse-retrieval", "fusion"]
    assert record["trace.stageStatuses"] == {
        "0:sparse-retrieval": "executed", "1:fusion": "executed"}
    assert "ms" not in json.dumps(record)


def test_hit_projection_drops_the_non_deterministic_ids():
    """`hit.id` / `doc_id` are a per-run UUID for a chunk hit — never captured.

    `ChunkIds.newChunkDocId()` mints `"chunk:" + UUID.randomUUID()` per indexing run, so
    two builds of the same corpus never agree on it and there is nothing to rewrite it to.
    """
    projection = wf.hit_projection({
        "id": "chunk:1f0e3dad-9999-4a5b-8c7d-000000000001",
        "score": 1.0,
        "fields": {"doc_id": "chunk:1f0e3dad-9999-4a5b-8c7d-000000000001",
                   "path": "docs/a.md", "parent_doc_id": "docs/a.md"},
    })
    assert "id" not in projection
    assert "docId" not in projection
    assert "chunk:" not in json.dumps(projection)
    # The stable chunk identity is still there.
    assert projection["parentDocId"] == "docs/a.md"
    assert set(projection) == {
        "path", "filename", "isChunk", "parentDocId", "chunkIndex", "chunkSpan",
        "contentTruncated", "excerptSpans", "matchedFields", "stageIds",
        "extractionReasonCode",
    }


# ---------------------------------------------------------------------------
# Corpus-root anchoring
# ---------------------------------------------------------------------------

_ROOT = r"F:\justsearch-public\.claude\worktrees\lane-F"


def test_rewriter_makes_paths_relative_forward_slash_and_case_preserved():
    r = wf.CorpusRootRewriter(_ROOT)
    # Backend lowercases on Windows (PathNormalizer.normalizePath), so the root's case need
    # not match — matching is case-insensitive, the remainder keeps the value's own case.
    assert r.rewrite(r"f:\justsearch-public\.claude\worktrees\lane-f\docs\explanation"
                     r"\02-process-coordination.md") == \
        "docs/explanation/02-process-coordination.md"
    assert r.rewrite(_ROOT + r"\docs\reference\API-Contract-Map.md") == \
        "docs/reference/API-Contract-Map.md"
    assert r.rewrite(_ROOT) == ""
    assert r.rewrite(None) is None
    assert r.rewrite("") == ""
    assert r.outside == []


def test_rewriter_passes_an_already_relative_value_through():
    """A non-absolute value is not "outside the root" — it has no machine-specific prefix.

    Flagging it would overload the outside-root health signal with a second meaning and
    fire on any already-relative value the backend returns.
    """
    r = wf.CorpusRootRewriter(_ROOT)
    assert r.rewrite("docs/explanation/01.md") == "docs/explanation/01.md"
    assert r.rewrite("docA") == "docA"
    assert r.outside == []
    assert r.provenance()["pathsOutsideCorpusRoot"] == 0
    # …while a genuinely absolute stray is still caught.
    assert r.rewrite(r"C:\elsewhere\x.md") == wf.OUTSIDE_ROOT_SENTINEL
    assert r.outside == [r"C:\elsewhere\x.md"]


def test_is_absolute_is_platform_independent():
    """Not os.path.isabs: a Windows capture may be diffed on Linux and vice versa."""
    for absolute in (r"F:\a\b", "F:/a/b", "/var/corpus/a.md", r"\\server\share\a.md"):
        assert wf.CorpusRootRewriter.is_absolute(absolute), absolute
    for relative in ("docs/a.md", r"docs\a.md", "docA", "a:b/c"[2:]):
        assert not wf.CorpusRootRewriter.is_absolute(relative), relative


def test_rewriter_flags_a_path_outside_the_root():
    r = wf.CorpusRootRewriter(_ROOT)
    assert r.rewrite(r"C:\elsewhere\notes.md") == wf.OUTSIDE_ROOT_SENTINEL
    assert r.outside == [r"C:\elsewhere\notes.md"]
    prov = r.provenance()
    assert prov["pathsOutsideCorpusRoot"] == 1
    assert prov["rawRootExample"] == r"C:\elsewhere\notes.md"
    assert prov["corpusRoot"] == _ROOT


def test_rewriter_only_strips_the_path_half_of_a_composite_ref():
    r = wf.CorpusRootRewriter(_ROOT)
    assert r.rewrite_ref(_ROOT + r"\docs\a.md#3") == "docs/a.md#3"
    # A sibling directory that merely shares a prefix is NOT inside the root.
    assert r.rewrite(_ROOT + "-other/docs/a.md") == wf.OUTSIDE_ROOT_SENTINEL


def test_capture_rewrites_absolute_paths_under_the_root(tmp_path: Path):
    """A mock backend returning absolute paths under the root -> captured relative."""
    doc = _capture_with_transport(
        _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
        {"transport": httpx.MockTransport(_search_backend(_absolute_search_response()))},
        skip_chat=True, corpus_root=_ROOT)

    hits = doc["queries"]["q1"]["hits[]"]
    assert [h["value"]["path"] for h in hits] == [
        "docs/explanation/01.md", "docs/reference/api.md"]
    assert [h["value"]["parentDocId"] for h in hits] == [
        "docs/explanation/01.md", "docs/reference/api.md"]
    assert "\\" not in json.dumps(doc["queries"])
    prov = doc["provenance"]
    assert prov["corpusRoot"] == _ROOT
    assert prov["corpusRootSource"] == "option"
    assert prov["rawRootExample"].startswith(_ROOT)   # non-diffed: shows what was stripped
    assert prov["pathsOutsideCorpusRoot"] == 0


def test_capture_flags_a_path_outside_the_root_as_a_health_problem(tmp_path: Path):
    response = _absolute_search_response()
    stray = r"C:\elsewhere\stray.md"
    response["results"][1]["fields"]["path"] = stray
    response["results"][1]["fields"]["parent_doc_id"] = stray

    doc = _capture_with_transport(
        _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
        {"transport": httpx.MockTransport(_search_backend(response))},
        skip_chat=True, corpus_root=_ROOT)

    assert doc["queries"]["q1"]["hits[]"][1]["value"]["path"] == wf.OUTSIDE_ROOT_SENTINEL
    assert doc["provenance"]["pathsOutsideCorpusRoot"] == 1
    assert doc["provenance"]["pathsOutsideCorpusRootExamples"] == [stray]

    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    result = wf.diff(doc, doc, fixture)
    assert result["pass"] is False
    assert any("path outside corpus root" in p for p in result["health"]["problems"])


def test_capture_records_the_chat_profile(tmp_path: Path):
    """The profile decides which model answered — a paired diff needs both on the same one."""
    doc = _capture_with_transport(
        _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
        {"transport": httpx.MockTransport(_search_backend(_absolute_search_response()))},
        skip_chat=True, corpus_root=_ROOT)
    assert doc["provenance"]["chatProfile"] == "compact"
    assert doc["provenance"]["aiRuntimeState"] == "completed"


def test_capture_records_the_sampling_pin_in_provenance(tmp_path: Path):
    """Recorded beside chatProfile and for the same reason: it decides what was generated.

    Non-diffed — `provenance` is not walked by `diff` — so a pair captured under two
    different pins is legible after the fact instead of reading as chat regressions.
    """
    fixture = _minimal_fixture({"queries.a": "exact"})
    fixture["sampling"] = {"temperature": 0.0, "top_p": None, "seed": 20260907}
    doc = _capture_with_transport(
        fixture, tmp_path / "c.json",
        {"transport": httpx.MockTransport(_search_backend(_absolute_search_response()))},
        skip_chat=True, corpus_root=_ROOT)
    assert doc["provenance"]["sampling"] == {
        "temperature": 0.0, "top_p": None, "seed": 20260907}
    # Beside chatProfile, and neither is compared.
    assert doc["provenance"]["chatProfile"] == "compact"
    result = wf.diff(doc, doc, fixture)
    assert not [e for e in result["fields"] if "sampling" in e["field"]]


def test_chat_profile_falls_back_to_the_top_level_field():
    """`active.chatProfile ?? chatProfile` — the dev-MCP reads both shapes (server.mjs:2376)."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"chatProfile": "standard",
                                         "activation": {"state": "activating"}})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        assert wf.read_ai_runtime(client) == ("standard", "activating")


def test_chat_profile_is_absent_not_fatal_when_ai_is_offline():
    """A search-only capture must not fail because the AI runtime answered nothing."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        assert wf.read_ai_runtime(client) == (None, None)


def test_chat_profile_requirement_is_recorded():
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    notes = " ".join(fixture["notes"])
    assert "SAME CHAT PROFILE" in notes
    assert "compact" in notes and "11 GB" in notes
    assert "c02 in 6" in notes


def test_capture_derives_the_root_from_a_single_watched_root(tmp_path: Path):
    doc = _capture_with_transport(
        _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
        {"transport": httpx.MockTransport(
            _search_backend(_absolute_search_response(), roots=[_ROOT]))},
        skip_chat=True)
    assert doc["provenance"]["corpusRoot"] == _ROOT
    assert doc["provenance"]["corpusRootSource"] == "indexing-roots"
    assert doc["queries"]["q1"]["hits[]"][0]["value"]["path"] == "docs/explanation/01.md"


def test_capture_refuses_when_two_roots_and_no_corpus_root(tmp_path: Path):
    roots = [_ROOT, r"D:\second-corpus"]
    with pytest.raises(wf.WorkflowFixtureError) as exc:
        _capture_with_transport(
            _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
            {"transport": httpx.MockTransport(
                _search_backend(_absolute_search_response(), roots=roots))},
            skip_chat=True)
    message = str(exc.value)
    assert "2 watched roots" in message
    assert r"D:\second-corpus" in message           # the roots are NAMED, not guessed between
    assert "--corpus-root" in message
    assert not (tmp_path / "c.json").exists()


def test_capture_refuses_when_no_root_is_registered(tmp_path: Path):
    with pytest.raises(wf.WorkflowFixtureError, match="0 watched roots"):
        _capture_with_transport(
            _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
            {"transport": httpx.MockTransport(
                _search_backend(_absolute_search_response(), roots=[]))},
            skip_chat=True)


def test_corpus_root_env_is_used_when_no_option(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("JUSTSEARCH_FIXTURE_CORPUS_ROOT", _ROOT)
    doc = _capture_with_transport(
        _minimal_fixture({"queries.a": "exact"}), tmp_path / "c.json",
        {"transport": httpx.MockTransport(
            _search_backend(_absolute_search_response(), roots=[r"D:\a", r"D:\b"]))},
        skip_chat=True)
    assert doc["provenance"]["corpusRootSource"] == "env"
    assert doc["provenance"]["corpusRoot"] == _ROOT


def test_capture_run_requirements_are_recorded():
    """The pin is four settings, three of them boot-time — all four must be written down."""
    notes = " ".join(wf.load_fixture(DEFAULT_FIXTURE)["notes"])
    assert "JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true" in notes
    assert "justsearch.llm.slots" in notes and "JUSTSEARCH_LLM_SLOTS" in notes
    assert "JUSTSEARCH_RERANK_DEADLINE_MS" in notes
    assert "JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS" in notes
    assert "ONE chat profile" in notes


def test_chat_source_and_citation_paths_are_made_relative():
    absolute = _ROOT + r"\docs\explanation\01.md"
    frames = [
        ("session_started", {"sessionId": "s"}),
        ("done", {"finalResponse": "x", "iterationsUsed": 1, "toolCallsExecuted": 1,
                  "totalTokensUsed": 1,
                  "sources": [{"parentDocId": absolute, "chunkIndex": 3, "path": absolute,
                               "excerpt": "…", "startLine": 1, "endLine": 2}],
                  "citations": [{"sentenceText": "s.", "sourceIndex": 0,
                                 "similarity": 0.9}]}),
    ]
    record = wf.capture_chat_record(frames, paths=wf.CorpusRootRewriter(_ROOT))
    assert record["sourcePaths"] == ["docs/explanation/01.md"]
    assert record["sourceRefs"] == ["docs/explanation/01.md#3"]
    assert record["citationTargets"] == ["docs/explanation/01.md#3"]


def test_fixture_notes_record_the_same_relative_layout_requirement():
    notes = " ".join(wf.load_fixture(DEFAULT_FIXTURE)["notes"])
    assert "SAME RELATIVE LAYOUT" in notes
    assert "ChunkIds.java:52-54" in notes


# ---------------------------------------------------------------------------
# The live HTTP paths, over httpx.MockTransport (review fixes 5 + 6)
# ---------------------------------------------------------------------------

_CANCEL_STREAM_HEAD = (
    "event: session_started\n"
    'data: {"sessionId":"sess-cancel"}\n'
    "\n"
)
_CANCEL_STREAM_TAIL = (
    "event: error\n"
    'data: {"error":"cancelled by client","errorCode":"CANCELLED"}\n'
    "\n"
)


def _cancel_backend(calls: list[tuple[str, str]]):
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        path = request.url.path
        if path == "/api/chat/agent":
            return httpx.Response(
                200, text=_CANCEL_STREAM_HEAD + _CANCEL_STREAM_TAIL,
                headers={"Content-Type": "text/event-stream"})
        if path == "/api/chat/sessions/sess-cancel" and request.method == "DELETE":
            return httpx.Response(200, json={"status": "cancelled"})
        if path == "/api/chat/sessions/sess-cancel":
            return httpx.Response(200, json={
                "sessionId": "sess-cancel", "state": "TERMINATED",
                "terminationReason": {"disposition": "CANCELLED", "errorCode": None,
                                      "cancelTrigger": "USER_REQUEST"}})
        return httpx.Response(404, json={})
    return handler


def test_run_chat_turn_cancels_and_records_the_session_outcome():
    calls: list[tuple[str, str]] = []
    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(_cancel_backend(calls))) as client:
        record = wf.run_chat_turn(
            client,
            {"id": "c03", "content": "…", "maxIterations": 3,
             "cancelAfterEvent": "session_started"},
            session_token="tok",
        )

    assert calls == [
        ("POST", "/api/chat/agent"),
        ("DELETE", "/api/chat/sessions/sess-cancel"),
        ("GET", "/api/chat/sessions/sess-cancel"),
    ]
    assert record["cancel.requested"] is True
    assert record["cancel.httpStatus"] == 200
    assert record["cancel.terminalEvent"] == "error"
    assert record["cancel.terminalReasonCode"] == "CANCELLED"
    assert record["cancel.sessionState"] == "TERMINATED"
    assert record["cancel.sessionDisposition"] == "CANCELLED"
    assert record["cancel.sessionCancelTrigger"] == "USER_REQUEST"
    assert record["terminalEvent"] == "error"


def test_cancel_is_issued_on_session_started_before_the_terminal_frame():
    """The DELETE must land while the run is live, not after the stream drained."""
    order: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/chat/agent":
            order.append("stream-open")
            return httpx.Response(
                200, text=_CANCEL_STREAM_HEAD + _CANCEL_STREAM_TAIL,
                headers={"Content-Type": "text/event-stream"})
        if request.method == "DELETE":
            order.append("cancel")
            return httpx.Response(200, json={"status": "cancelled"})
        order.append("session-read")
        return httpx.Response(200, json={"state": "TERMINATED", "terminationReason": {}})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        wf.run_chat_turn(client, {"id": "c", "content": "x",
                                  "cancelAfterEvent": "session_started"})
    assert order == ["stream-open", "cancel", "session-read"]


def test_run_chat_turn_sends_the_sampling_override_in_the_request_body():
    """PR 0b: the pin has to reach the wire, not just sit in the fixture file."""
    bodies: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(
            200, text=_CANCEL_STREAM_HEAD + _CANCEL_STREAM_TAIL,
            headers={"Content-Type": "text/event-stream"})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        wf.run_chat_turn(client, {"id": "c01", "content": "x", "maxIterations": 8},
                         sampling={"temperature": 0.0, "seed": 20260907})

    assert len(bodies) == 1
    assert bodies[0]["sampling"] == {"temperature": 0.0, "seed": 20260907}
    # Unchanged shape around it — the override is additive, not a replacement.
    assert bodies[0]["maxIterations"] == 8
    assert bodies[0]["messages"] == [{"role": "user", "content": "x"}]


def test_run_chat_turn_omits_sampling_when_none_is_pinned():
    """Absent means "no override" on the wire too — not `"sampling": null`."""
    bodies: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(
            200, text=_CANCEL_STREAM_HEAD + _CANCEL_STREAM_TAIL,
            headers={"Content-Type": "text/event-stream"})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        wf.run_chat_turn(client, {"id": "c01", "content": "x"})

    assert "sampling" not in bodies[0]


def test_capture_sends_the_session_token_on_every_request(tmp_path: Path):
    """Review fix 5: POST/PUT/DELETE all require the token (ApiSecurityFilters)."""
    seen: list[tuple[str, str, str | None]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path,
                     request.headers.get("X-JustSearch-Session")))
        if request.url.path == "/api/status":
            return httpx.Response(200, json={"service": "justsearch", "schema_version": 1,
                                             "worker": {"buildStamp": "abc"}})
        if request.url.path == "/api/knowledge/search":
            return httpx.Response(200, json=_SEARCH_RESPONSE)
        return httpx.Response(404, json={})

    fixture = _minimal_fixture({"queries.a": "exact"})
    out = tmp_path / "capture.json"
    doc = _capture_with_transport(
        fixture, out, {"transport": httpx.MockTransport(handler)},
        session_token="tok-123", skip_chat=True, corpus_root=_ROOT)

    assert out.exists()
    assert doc["queries"]["q1"]["httpStatus"] == 200
    assert doc["queries"]["q1"]["hitCount"] == 2
    assert doc["provenance"]["worker.buildStamp"] == "abc"
    search_calls = [c for c in seen if c[1] == "/api/knowledge/search"]
    assert search_calls and all(token == "tok-123" for _, _, token in search_calls)
    assert all(token == "tok-123" for _, _, token in seen)


def test_build_search_body_carries_the_fixture_spec():
    body = wf._build_search_body({"query": "q", "limit": 10, "mode": "hybrid"})
    assert body == {"query": "q", "limit": 10, "mode": "hybrid",
                    "includeExcerpts": True, "debug": True}


def test_session_headers_fall_back_to_the_env(monkeypatch):
    monkeypatch.delenv("JUSTSEARCH_SESSION_TOKEN", raising=False)
    assert wf._session_headers(None) == {}
    monkeypatch.setenv("JUSTSEARCH_SESSION_TOKEN", "env-tok")
    assert wf._session_headers(None) == {"X-JustSearch-Session": "env-tok"}
    assert wf._session_headers("explicit") == {"X-JustSearch-Session": "explicit"}


def test_capture_provenance_survives_an_unreachable_status():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        assert wf.capture_provenance(client)["available"] is False


# ---------------------------------------------------------------------------
# CLI wiring
# ---------------------------------------------------------------------------

def test_cli_group_help_lists_both_subcommands():
    result = CliRunner().invoke(main, ["workflow-fixture", "--help"])
    assert result.exit_code == 0
    assert "capture" in result.output
    assert "diff" in result.output


def test_cli_capture_summary_reports_the_profile(tmp_path: Path):
    """The summary block does direct key access — exercise it, don't infer it."""
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(json.dumps(_minimal_fixture({"queries.a": "exact"})),
                            encoding="utf-8")
    out = tmp_path / "capture.json"
    real_client = httpx.Client
    transport = httpx.MockTransport(_search_backend(_absolute_search_response()))

    def patched(*args, **kw):
        kw["transport"] = transport
        return real_client(*args, **kw)

    httpx.Client = patched
    try:
        result = CliRunner().invoke(main, [
            "workflow-fixture", "capture", "--fixture", str(fixture_path),
            "--out", str(out), "--corpus-root", _ROOT, "--skip-chat"])
    finally:
        httpx.Client = real_client

    assert result.exit_code == 0, result.output
    assert "chat profile: compact" in result.output
    assert "same profile" in result.output
    assert "corpus root:" in result.output


def test_cli_diff_exit_codes(tmp_path: Path):
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(json.dumps(_minimal_fixture({"queries.a": "exact"})),
                            encoding="utf-8")
    base = tmp_path / "base.json"
    cand = tmp_path / "cand.json"
    base.write_text(json.dumps(_capture({"q1": {"a": 1}})), encoding="utf-8")

    cand.write_text(json.dumps(_capture({"q1": {"a": 1}})), encoding="utf-8")
    ok = _run_diff(base, cand, fixture_path)
    assert ok.exit_code == 0, ok.output

    cand.write_text(json.dumps(_capture({"q1": {"a": 2}})), encoding="utf-8")
    bad = _run_diff(base, cand, fixture_path)
    assert bad.exit_code == 1, bad.output
    assert "REGRESSION" in bad.output


def test_cli_diff_exits_1_on_an_unhealthy_capture(tmp_path: Path):
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(
        json.dumps(_minimal_fixture({"queries.a": "exact", "queries.zzz": "exact"})),
        encoding="utf-8")
    broken = tmp_path / "broken.json"
    broken.write_text(
        json.dumps(_capture({"q1": {"a": None, "httpStatus": 401, "hitCount": 0}})),
        encoding="utf-8")
    result = _run_diff(broken, broken, fixture_path)
    assert result.exit_code == 1, result.output
    assert "UNHEALTHY" in result.output
    assert "declared but never captured: queries.zzz" in result.output


def test_cli_diff_usage_error_on_bad_fixture(tmp_path: Path):
    fixture_path = tmp_path / "fixture.json"
    fixture_path.write_text(json.dumps(_minimal_fixture({"queries.a": "timing-noise"})),
                            encoding="utf-8")
    capture_path = tmp_path / "c.json"
    capture_path.write_text(json.dumps(_capture({"q1": {"a": 1}})), encoding="utf-8")
    result = _run_diff(capture_path, capture_path, fixture_path)
    assert result.exit_code == 2, result.output


# ---------------------------------------------------------------------------
# Helpers + synthetic payloads
# ---------------------------------------------------------------------------

_SEARCH_RESPONSE = {
    "totalHits": 2,
    "matchCount": 7,
    "tookMs": 31,
    "results": [
        {
            "id": "docA#0",
            "score": 2.0,
            "fields": {
                "doc_id": "docA", "path": "docs/explanation/01.md", "filename": "01.md",
                "is_chunk": "true", "parent_doc_id": "docA", "chunk_index": "0",
                "chunk_start_char": "0", "chunk_end_char": "512",
                "content_truncated": "false", "extraction_reason_code": "OK",
            },
            "matchedFields": ["content", "title"],
            "excerptRegions": [{"text": "…", "startChar": 4, "endChar": 40}],
            "trace": [{"id": "sparse-retrieval", "rank": 1, "score": 2.0}],
        },
        {
            "id": "docB#1",
            "score": 1.0,
            "fields": {
                "doc_id": "docB", "path": "docs/reference/api.md", "filename": "api.md",
                "is_chunk": "true", "parent_doc_id": "docB", "chunk_index": "1",
                "chunk_start_char": "512", "chunk_end_char": "1024",
                "content_truncated": "false",
            },
            "matchedFields": ["content"],
            "excerptRegions": [],
            "trace": [{"id": "fusion", "rank": 2, "score": 1.0}],
        },
    ],
    "searchTrace": {
        "version": 1,
        "effectiveMode": "hybrid",
        "decisionKind": "multi_leg",
        "degradation": {
            "vectorBlocked": False, "hybridFallback": False, "spladeExecuted": True,
        },
        "stages": [
            {"id": "sparse-retrieval", "status": "executed", "ms": 12, "cardinality": 40},
            {"id": "fusion", "status": "executed", "ms": 3, "cardinality": 20},
        ],
    },
}

_CHAT_FRAMES = [
    ("session_started", {"sessionId": "s"}),
    ("done", {"finalResponse": "x", "iterationsUsed": 1, "toolCallsExecuted": 0,
              "totalTokensUsed": 1, "sources": [], "citations": []}),
]


def _minimal_fixture(fields: dict[str, str]) -> dict:
    """A one-query/one-turn fixture that is HEALTHY by default.

    The health floor (`httpStatus`/`hitCount` on every query, a non-empty chat section)
    is declared here so each test only has to add the field it is about. The `sampling`
    pin is REQUIRED by `validate_fixture`, so it is part of that floor too.
    """
    declared = {"queries.httpStatus": "exact", "queries.hitCount": "exact",
                "chatTurns.httpStatus": "exact"}
    declared.update(fields)
    return {
        "schema": wf.FIXTURE_SCHEMA,
        "version": 1,
        "id": "test-fixture",
        "scoreTieEpsilon": 0.01,
        "sampling": {"temperature": 0.0, "seed": 7},
        "queries": [{"id": "q1", "query": "x", "limit": 10, "mode": "hybrid"}],
        "chatTurns": [{"id": "c1", "content": "x", "maxIterations": 1}],
        "fields": declared,
    }


def _capture(queries: dict | None = None, chat: dict | None = None) -> dict:
    """A healthy synthetic capture: every record carries httpStatus 200 (+ hitCount)."""
    q: dict[str, dict] = {}
    for rid, rec in (queries or {"q1": {}}).items():
        merged = {"httpStatus": 200, "hitCount": 1}
        merged.update(rec)
        q[rid] = merged
    c: dict[str, dict] = {}
    for rid, rec in (chat or {"c1": {}}).items():
        merged = {"httpStatus": 200}
        merged.update(rec)
        c[rid] = merged
    return {
        "schema": wf.CAPTURE_SCHEMA,
        "fixture_id": "test-fixture",
        "fixture_version": 1,
        "provenance": {"available": True},
        "queries": q,
        "chatTurns": c,
    }


def _full_snapshot(fixture: dict) -> dict:
    frames = wf.parse_sse_frames(_SYNTHETIC_STREAM)
    return {
        "schema": wf.CAPTURE_SCHEMA,
        "fixture_id": fixture["id"],
        "fixture_version": fixture["version"],
        "provenance": {"available": True},
        "queries": {q["id"]: wf.capture_query_record(_SEARCH_RESPONSE)
                    for q in fixture["queries"]},
        "chatTurns": {
            t["id"]: wf.capture_chat_record(
                frames, cancel={"requested": True} if t.get("cancelAfterEvent") else None)
            for t in fixture["chatTurns"]
        },
    }


def _search_backend(response: dict, roots: list[str] | None = None, status: int = 200):
    """A mock backend serving /api/status, /api/indexing/roots and the search POST."""
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/status":
            return httpx.Response(200, json={"service": "justsearch", "schema_version": 1,
                                             "worker": {"buildStamp": "abc"}})
        if path == "/api/ai/runtime/status":
            return httpx.Response(200, json={
                "active": {"chatProfile": "compact", "activeVariantId": "v1"},
                "activation": {"state": "completed"}})
        if path == "/api/indexing/roots":
            return httpx.Response(200, json={"roots": [
                {"collection": "default", "path": r, "fileCount": -1}
                for r in (roots if roots is not None else [])]})
        if path == "/api/knowledge/search":
            return httpx.Response(status, json=response if status == 200 else {})
        return httpx.Response(404, json={})
    return handler


def _absolute_search_response() -> dict:
    """`_SEARCH_RESPONSE` with the paths as the backend really returns them: absolute."""
    response = copy.deepcopy(_SEARCH_RESPONSE)
    for hit in response["results"]:
        absolute = _ROOT + "\\" + hit["fields"]["path"].replace("/", "\\")
        hit["fields"]["path"] = absolute
        hit["fields"]["parent_doc_id"] = absolute
        hit["fields"]["doc_id"] = absolute
    return response


def _capture_with_transport(fixture, out_path, client_kwargs, **kwargs):
    """Run ``wf.capture`` with a mock transport injected into its httpx.Client."""
    real_client = httpx.Client

    def patched(*args, **kw):
        kw.update(client_kwargs)
        return real_client(*args, **kw)

    httpx.Client = patched
    try:
        return wf.capture("http://127.0.0.1:33221", fixture, out_path, **kwargs)
    finally:
        httpx.Client = real_client


def _run_diff(baseline, candidate, fixture_path):
    return CliRunner().invoke(main, ["workflow-fixture", "diff",
                                     "--baseline", str(baseline),
                                     "--candidate", str(candidate),
                                     "--fixture", str(fixture_path)])


def _entry(result: dict, field: str) -> dict:
    matches = [e for e in result["fields"] if e["field"] == field]
    assert matches, f"{field} not in diff result: {[e['field'] for e in result['fields']]}"
    return matches[0]


def _status(result: dict, field: str) -> str:
    return _entry(result, field)["status"]
