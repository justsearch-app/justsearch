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


def test_an_all_null_sampling_block_is_refused():
    """A block whose values are ALL null declares a pin and pins nothing.

    Each key means "no override" individually, so `{"temperature": null, "seed": null}` runs
    the turns under SamplingParams.AGENT exactly as an absent block does — while the artifact
    records a `sampling` block and reads as pinned.
    """
    for empty_pin in ({"temperature": None},
                      {"temperature": None, "top_p": None, "seed": None}):
        fixture = _minimal_fixture({"queries.a": "exact"})
        fixture["sampling"] = empty_pin
        with pytest.raises(wf.WorkflowFixtureError, match="pins NOTHING"):
            wf.validate_fixture(fixture)
    # One real value is enough — the refusal is about pinning nothing, not about nulls.
    fixture = _minimal_fixture({"queries.a": "exact"})
    fixture["sampling"] = {"temperature": None, "top_p": None, "seed": 7}
    assert wf.validate_sampling(fixture["sampling"])["seed"] == 7


def test_a_chat_turn_spec_is_validated_against_a_closed_key_set():
    """`maxIterms: 8` must be REFUSED, not silently ignored.

    The capture reads exactly CHAT_TURN_KEYS, so a typo leaves the turn at the default cap of
    3 while the fixture reads as if it declared 8 — surfacing much later as an unexplained
    MAX_ITERATIONS capture-health failure.
    """
    fixture = _minimal_fixture({"queries.a": "exact"})
    fixture["chatTurns"] = [{"id": "c1", "content": "x", "maxIterms": 8}]
    with pytest.raises(wf.WorkflowFixtureError, match="maxIterms"):
        wf.validate_fixture(fixture)

    # Every key the capture actually honours passes.
    fixture["chatTurns"] = [{"id": "c1", "content": "x", "maxIterations": 8,
                             "cancelAfterEvent": "session_started"}]
    assert wf.validate_fixture(fixture)
    assert wf.CHAT_TURN_KEYS == {"id", "content", "maxIterations", "cancelAfterEvent"}
    # …and the shipped fixture uses nothing outside it.
    for turn in wf.load_fixture(DEFAULT_FIXTURE)["chatTurns"]:
        assert set(turn) <= wf.CHAT_TURN_KEYS


def test_c02_was_replaced_with_a_single_anchor_question():
    """c02 looped at maxIterations 8 on BOTH captures even at temperature 0.

    A looping turn is not merely a partial answer: its tool trajectory diverges as soon as
    retrieval does, so the `exact` chat fields downstream of it differ for a reason that is
    not a build difference. The replacement has c01's shape — one obvious anchor document.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    c02 = next(t for t in fixture["chatTurns"] if t["id"] == "c02")
    assert "IndexingPacing" not in c02["content"]
    assert "three processes" in c02["content"]
    assert c02["maxIterations"] == 8
    notes = " ".join(fixture["notes"])
    assert "C02 WAS REPLACED" in notes
    assert "docs/explanation/01-system-overview.md" in notes
    # The superseded "c02 completes in 6 iterations" claim must be gone, not outnumbered.
    assert "c02 in 6" not in notes


def test_cutoff_group_rule_is_recorded_in_the_fixture_notes():
    """Including the caveat a reader must not miss: 2K changes what the backend ranks."""
    notes = " ".join(wf.load_fixture(DEFAULT_FIXTURE)["notes"])
    assert "STRADDLING THE RANK-K CUTOFF" in notes
    assert "NO fourth class is introduced" in notes
    assert "CAPTURES TAKEN AT THE OLD LIMIT ARE NOT COMPARABLE TO NEW ONES" in notes


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
# The cutoff tie group (the group straddling rank K)
# ---------------------------------------------------------------------------
#
# Every fixture here declares limit 3, so K = 3 and the capture would have asked the backend
# for 6. The compared slice therefore ends on a tie-group boundary — hits `c` and `d` below
# are one group whose FIRST member sits at rank 3, i.e. the cutoff group.

def _cutoff_fixture(limit: int = 3) -> dict:
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    fixture["queries"][0]["limit"] = limit
    return fixture


def _cutoff_capture(tagged, *, hits_captured: int | None = 6) -> dict:
    """A capture whose q1 carries ``tagged`` as its compared slice.

    ``hits_captured`` is what the backend returned for the 2K request, recorded where the
    real capture records it: the non-diffed ``observed`` block. ``None`` omits the whole
    block, i.e. an older capture.
    """
    observed = None
    if hits_captured is not None:
        observed = {"q1": {"scores": [t["score"] for t in tagged],
                           "hitsCaptured": hits_captured,
                           "hitsCompared": len(tagged)}}
    return _capture({"q1": {"hits[]": tagged, "hitCount": 3}}, observed=observed)


def test_swap_across_the_rank_k_cutoff_inside_one_tie_group_is_allowed():
    """The q06 / q10 live failure: one member of the BOTTOM tie group differed.

    At limit 10 only the rank-10 half of that group was captured, so a swap with its rank-11
    partner read as "tie group N is not a permutation". With the group observed whole, the
    swap is what it is: a permutation of equal-score hits, across the K boundary.
    """
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),      # the cutoff group, ranks 3-4
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.499, _hit("d")), (0.500, _hit("c")),      # same group, swapped
    ]))
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]
    # The status string, not merely `pass`: this must be recorded as one of the three
    # declared classes, and specifically as the ordering class.
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order"


def test_cutoff_group_tolerates_a_head_partition_change_that_today_would_fail():
    """The discriminating case — this FAILS if the cutoff rule is removed.

    A swap inside the cutoff group plus a jittered gap ABOVE it: the old whole-list guard
    saw "the order changed AND the partition sizes differ" and called it a regression, even
    though the only reordering happened inside one tie group and nothing above it moved.
    Splitting the cutoff group off means the head's order is unchanged (so its partition is
    never consulted, exactly as the q07 fix intends) and the cutoff group is a set.
    """
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.880, _hit("b")),      # gap 0.020 -> two head groups
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.895, _hit("b")),      # gap 0.005 -> ONE head group
        (0.499, _hit("d")), (0.500, _hit("c")),
    ]))
    # Guard the premise: the two partitions really do differ in shape.
    assert [len(m) for _, m in wf._tie_groups(base["queries"]["q1"]["hits[]"], 0.01)[1]] == [
        1, 1, 2]
    assert [len(m) for _, m in wf._tie_groups(cand["queries"]["q1"]["hits[]"], 0.01)[1]] == [
        2, 2]
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order"
    # …and without the cutoff (the same values compared as a plain hits[] list) it is the
    # REGRESSION the rule exists to remove — proof the pass above is the rule's doing.
    without_cutoff = wf.compare_field(
        "equal-score-order", base["queries"]["q1"]["hits[]"],
        cand["queries"]["q1"]["hits[]"], 0.01, None)
    assert without_cutoff[0] == "REGRESSION"
    assert "tie-group partition differs" in without_cutoff[1]


def test_a_hit_that_joins_the_cutoff_group_without_moving_is_not_a_regression():
    """The q07 false positive, at the boundary — caught in the post-implementation pass.

    `b` jitters from its own group into the cutoff group while the DELIVERED ORDER stays
    identical. Splitting the partition at the cutoff unconditionally shortened the head by
    one group and the head's own partition check called it a regression with nothing
    reordered. The split is therefore gated on "did anything actually reorder", exactly as
    the whole-list guard already was.
    """
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),      # groups [a] [b] [c,d]
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.505, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),      # groups [a] [b,c,d] — same order
    ]))
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order"


def test_a_member_missing_from_the_other_sides_cutoff_group_is_a_regression():
    """Permutation across the cutoff is allowed; a different document is not."""
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("e")),      # d -> e: a real membership change
    ]))
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    reason = _entry(result, "queries.hits[]")["reason"]
    assert "cutoff tie group" in reason and "not the same SET" in reason
    # The membership is PROVEN here (4 compared out of 6 captured), so the regression must
    # not be reported as the fail-closed case — that would hide a genuine finding behind
    # "we could not tell".
    assert "UNPROVEN" not in reason


def test_a_change_in_a_group_above_the_cutoff_is_a_regression_exactly_as_today():
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("ZZZ")),    # above the cutoff group
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]))
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    reason = _entry(result, "queries.hits[]")["reason"]
    assert "not a permutation" in reason          # the unchanged per-group rule fired…
    assert "cutoff tie group" not in reason       # …not the cutoff rule


def test_a_cutoff_group_that_ran_off_the_end_of_a_full_2k_response_fails_closed():
    """The group may continue past rank 2K, so a set difference cannot be told from an unseen
    member. Fail closed, and say why."""
    fixture = _cutoff_fixture()
    # 6 compared out of 6 captured, and 6 == 2 * K: the group ran to the end of a FULL
    # response, so nothing proves where it really ends.
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")), (0.498, _hit("e")), (0.497, _hit("f")),
    ]), hits_captured=6)
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")), (0.498, _hit("e")), (0.497, _hit("g")),
    ]), hits_captured=6)
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    reason = _entry(result, "queries.hits[]")["reason"]
    assert "UNPROVEN" in reason
    assert "runs to the end of what was captured" in reason   # the 2K cause, named


def test_a_capture_without_hits_captured_fails_closed_too():
    """An older capture records no `hitsCaptured`, so there is no evidence either way."""
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]), hits_captured=None)
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("e")),
    ]), hits_captured=None)
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is False
    reason = _entry(result, "queries.hits[]")["reason"]
    assert "UNPROVEN" in reason
    assert "older capture" in reason      # …and for THIS cause, not the 2K one
    assert "runs to the end of what was captured" not in reason


def test_an_unsliced_capture_is_named_not_silently_compared():
    """The cutoff group is the LAST group by construction — assert it, don't assume it."""
    fixture = _cutoff_fixture()
    unsliced = _tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),
        (0.100, _hit("e")),                          # past the cutoff group: never sliced off
    ])
    swapped = list(unsliced)
    swapped[2], swapped[3] = swapped[3], swapped[2]
    result = wf.diff(_cutoff_capture(unsliced), _cutoff_capture(swapped), fixture)
    assert result["pass"] is False
    assert "was not sliced at the cutoff group" in _entry(result, "queries.hits[]")["reason"]


def test_a_new_reason_code_inside_the_cutoff_group_is_still_allowed():
    """The subset rule keeps applying to members present on both sides of the set."""
    fixture = _cutoff_fixture()
    base = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.500, _hit("c")), (0.499, _hit("d")),
    ]))
    cand = _cutoff_capture(_tagged([
        (0.900, _hit("a")), (0.800, _hit("b")),
        (0.499, _hit("d")), (0.500, _hit("c", extractionReasonCode="OCR_FALLBACK")),
    ]))
    result = wf.diff(base, cand, fixture)
    assert result["pass"] is True, _entry(result, "queries.hits[]")["reason"]
    assert _status(result, "queries.hits[]") == "allowed:new-reason-code"

    lost = wf.diff(cand, base, fixture)
    assert lost["pass"] is False
    assert "gone in the candidate" in _entry(lost, "queries.hits[]")["reason"]


def test_chat_turns_get_no_cutoff():
    """There is no rank-K contract on a chat turn, so nothing is passed down for one."""
    fixture = _minimal_fixture({"chatTurns.sourceRefs": "exact"})
    base = _capture(chat={"c1": {"sourceRefs": ["docs/a.md#0"]}})
    cand = _capture(chat={"c1": {"sourceRefs": ["docs/b.md#0"]}})
    assert wf.diff(base, cand, fixture)["pass"] is False


def test_build_search_body_asks_for_twice_the_declared_limit():
    """K is what gets compared; the extra K is what makes the cutoff group observable."""
    assert wf._build_search_body({"query": "q", "limit": 10, "mode": "hybrid"})["limit"] == 20
    assert wf._build_search_body({"query": "q", "limit": 3})["limit"] == 6
    # …and the default limit doubles too, rather than silently staying at 10.
    assert wf._build_search_body({"query": "q"})["limit"] == 20


def test_compared_slice_keeps_the_whole_cutoff_group_and_drops_the_rest():
    hits = _response_with_scores([0.9, 0.8, 0.5, 0.499, 0.498, 0.1])["results"]
    kept = wf.compared_slice(hits, 3, 0.01)
    assert [h["id"] for h in kept] == ["h0", "h1", "h2", "h3", "h4"]
    # Nothing to extend when the cutoff group is a singleton.
    assert len(wf.compared_slice(hits, 2, 0.01)) == 2
    # Fewer hits than K: the whole list, unchanged.
    assert wf.compared_slice(hits, 99, 0.01) == hits


def test_hit_count_stays_the_top_k_count_when_the_slice_is_longer():
    """`hitCount` is diffed `exact`: an 11-vs-12 cutoff group must not fail on it."""
    response = _response_with_scores([0.9, 0.8, 0.5, 0.499, 0.498, 0.1])
    record = wf.capture_query_record(response, limit=3, epsilon=0.01)
    assert record["hitCount"] == 3                  # top-K, NOT the slice length
    assert len(record["hits[]"]) == 5               # …and the slice really is longer
    # A shorter response still reports what it returned rather than K.
    assert wf.capture_query_record(
        _response_with_scores([0.9, 0.8]), limit=3, epsilon=0.01)["hitCount"] == 2


def test_hits_captured_and_compared_are_observed_only_and_never_diffed():
    """Both legitimately differ between builds, so they must not be diffed fields."""
    response = _response_with_scores([0.9, 0.8, 0.5, 0.499, 0.498, 0.1])
    record = wf.capture_query_record(response, limit=3, epsilon=0.01)
    counts = wf.observed_query_counts(response, record)
    assert counts["hitsCaptured"] == 6
    assert counts["hitsCompared"] == 5
    assert "hitsCaptured" not in record and "hitsCompared" not in record
    # The two candidate-pool counts share this block for the same reason and must not be in
    # the record either (see test_candidate_pool_counts_are_observed_only).
    assert "totalHits" in counts and "stageCardinality" in counts
    assert "totalHits" not in record and "trace.stageCardinality" not in record

    # And the differ does not walk them: two captures whose counts differ, with identical
    # query records, still diff clean and produce no entry for either name.
    fixture = _cutoff_fixture()
    tagged = _tagged([(0.900, _hit("a")), (0.800, _hit("b")),
                      (0.500, _hit("c")), (0.499, _hit("d"))])
    result = wf.diff(_cutoff_capture(tagged, hits_captured=6),
                     _cutoff_capture(tagged, hits_captured=4), fixture)
    assert result["pass"] is True, result["health"]["problems"]
    compared = {e["field"] for e in result["fields"]}
    assert not [f for f in compared if "hitsCaptured" in f or "hitsCompared" in f]
    # …and the pass is for the right reason: the hits[] field was byte-equal, not waved
    # through under an allowed class.
    assert _status(result, "queries.hits[]") == "equal"


def _response_with_ce(pairs: list[tuple[float, float]], ids: list[str] | None = None) -> dict:
    """A response carrying BOTH scores per hit: (delivered/fusion, cross-encoder).

    The delivered order is the CROSS-ENCODER's (`KnowledgeSearchEngine` applies
    `applyRerankOrder`), so `results` here is already in CE order while `score` is the
    pre-rerank fusion value — the real shape, which is why it is worth building in a helper.
    """
    names = ids or [f"h{i}" for i in range(len(pairs))]
    return {
        "totalHits": len(pairs),
        "matchCount": len(pairs),
        "results": [
            {
                "id": name,
                "score": delivered,
                "fields": {"doc_id": name, "path": f"docs/{name}.md",
                           "filename": f"{name}.md", "is_chunk": "true",
                           "parent_doc_id": name, "chunk_index": "0"},
                "matchedFields": [], "excerptRegions": [],
                "trace": ([{"id": "fusion", "score": delivered}]
                          + ([{"id": "cross-encoder", "score": ce}] if ce is not None else [])),
            }
            for name, (delivered, ce) in zip(names, pairs)
        ],
        "searchTrace": {"stages": [], "degradation": {}},
    }


def test_equal_score_is_grouped_on_the_cross_encoder_score_not_the_delivered_one():
    """The delivered ORDER is the CE's, so the CE score is the only key whose ties mean
    "these two could legitimately swap".

    Two hits tie on the CE score (0.700 / 0.7005) while their delivered scores are 0.9 and
    0.2 — far outside epsilon. Grouping on the delivered score makes them singletons and a
    swap a REGRESSION; grouping on the CE score makes them one tie group and the swap
    allowed. This is the case that fails if the basis reverts.
    """
    response = _response_with_ce([(0.9, 0.700), (0.2, 0.7005), (0.1, 0.100)])
    record = wf.capture_query_record(response, limit=10, epsilon=0.01)
    tags = record["hits[]"]
    assert [t["score"] for t in tags] == [0.700, 0.7005, 0.100],         "the stored tag must be the CROSS-ENCODER score, not the delivered one"

    ok, groups = wf._tie_groups(tags, 0.01)
    assert ok
    assert [len(members) for _, members in groups] == [2, 1],         "the two CE-tied hits must land in ONE group despite delivered scores 0.9 vs 0.2"

    # And end to end: swapping the two CE-tied hits is allowed, not a regression.
    swapped = _response_with_ce([(0.2, 0.7005), (0.9, 0.700), (0.1, 0.100)],
                                ids=["h1", "h0", "h2"])
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    base = _capture({"q1": record})
    cand = _capture({"q1": wf.capture_query_record(swapped, limit=10, epsilon=0.01)})
    result = wf.diff(base, cand, fixture)
    assert _status(result, "queries.hits[]") == "allowed:equal-score-order", result["fields"]


def test_score_basis_falls_back_to_delivered_when_the_cross_encoder_did_not_score():
    """A lexical/TEXT fallback never runs the CE, so there is no sort key to group on."""
    response = _response_with_ce([(0.9, None), (0.2, None)])
    basis, scores = wf.resolve_score_basis(response["results"])
    assert basis == wf.SCORE_BASIS_DELIVERED
    assert scores == [0.9, 0.2]
    record = wf.capture_query_record(response, limit=10, epsilon=0.01)
    assert [t["score"] for t in record["hits[]"]] == [0.9, 0.2]


def test_a_partially_scored_query_falls_back_WHOLE_rather_than_mixing_two_bases():
    """All-or-nothing per query.

    A list mixing CE scores and delivered scores would have epsilon comparing numbers from
    two different scales — precisely the defect this replaces. Falling back for the whole
    query is merely conservative.
    """
    response = _response_with_ce([(0.9, 0.70), (0.2, None), (0.1, 0.10)])
    basis, scores = wf.resolve_score_basis(response["results"])
    assert basis == wf.SCORE_BASIS_DELIVERED
    assert scores == [0.9, 0.2, 0.1], "no CE score may leak into a delivered-basis list"


def test_the_score_basis_and_both_other_scores_are_observed_never_diffed():
    """`scoreBasis` tells a reader which number grouped; the other two are for diagnosis."""
    response = _response_with_ce([(0.9, 0.70), (0.2, 0.60)])
    record = wf.capture_query_record(response, limit=10, epsilon=0.01)
    observed = wf.observed_query_counts(response, record)
    assert observed["scoreBasis"] == wf.SCORE_BASIS_CROSS_ENCODER
    assert observed["crossEncoderScores"] == [0.70, 0.60]
    assert observed["fusionScores"] == [0.9, 0.2]
    assert observed["scores"] if "scores" in observed else True
    # None of the three may be a diffed field.
    for name in ("scoreBasis", "crossEncoderScores", "fusionScores"):
        assert name not in record


def test_the_cutoff_slice_uses_the_cross_encoder_basis_too():
    """The slice and the stored tags must mean the same number, or they disagree on where
    the cutoff group ends — the disagreement the cutoff rule exists to remove."""
    # CE scores tie at the rank-2 boundary (limit 2); delivered scores do not.
    pairs = [(0.9, 0.90), (0.8, 0.50), (0.1, 0.5005), (0.05, 0.20)]
    record = wf.capture_query_record(_response_with_ce(pairs), limit=2, epsilon=0.01)
    assert record["hitCount"] == 2, "hitCount stays the TOP-K count"
    assert len(record["hits[]"]) == 3,         "the slice must extend to the whole CE tie group holding rank 2"


#: The `trace` arrays of the FIRST THREE results of a real `debug:true`, `limit:20`
#: `POST /api/knowledge/search` response over `docs/explanation`
#: (captured 2026-09-07, `tmp/raw-search-debug.json`), copied verbatim.
#:
#: This is the wire shape the extraction has to work against, and it is worth pinning from a
#: real body rather than a hand-built one: pair run 3 recorded `scoreBasis: delivered` on all
#: 12 queries and the first hypothesis was a wrong JSON path. It was not — the path below is
#: exactly what the code reads; the cross-encoder stage was ABSENT because the stage had been
#: dropped (`INFERENCE_FAILED`). A synthetic fixture could not have told those two apart.
#:
#: Note the shape facts this pins: stages are `{id, rank, score, detail}`; `rank` and `detail`
#: are ABSENT on the cross-encoder entry (`SearchTraceMapper.mapHitStages` appends it with
#: nulls for both); and `results[i].score` equals the `branch-fusion` stage score, NOT the
#: cross-encoder's — which is the whole reason the basis had to change.
_REAL_TRACES = [
    [
        {"id": "sparse-retrieval", "rank": 19, "score": 3.7805243,
         "detail": {"sparse_rank": 19.0, "sparse": 3.7805243}},
        {"id": "dense-retrieval", "rank": 16, "score": 0.5049805,
         "detail": {"vector": 0.5049805, "vector_rank": 16.0}},
        {"id": "fusion", "score": 0.42335153, "detail": {"cc_alpha": 0.5, "cc": 0.42335153}},
        {"id": "chunk-merge", "score": 0.49315822, "detail": {"chunk_cc": 0.49315822}},
        {"id": "branch-fusion", "score": 0.46152, "detail": {"branch_merge_cc": 0.46152}},
        {"id": "cross-encoder", "score": 0.2602539},
    ],
    [
        {"id": "sparse-retrieval", "rank": 8, "score": 4.6122785, "detail": {}},
        {"id": "dense-retrieval", "rank": 11, "score": 0.5312805, "detail": {}},
        {"id": "fusion", "score": 0.36807224, "detail": {}},
        {"id": "branch-fusion", "score": 0.5792252, "detail": {}},
        {"id": "cross-encoder", "score": 0.14123535},
    ],
    [
        {"id": "sparse-retrieval", "rank": 3, "score": 5.011, "detail": {}},
        {"id": "dense-retrieval", "rank": 2, "score": 0.61, "detail": {}},
        {"id": "fusion", "score": 0.56067425, "detail": {}},
        {"id": "branch-fusion", "score": 0.6673769, "detail": {}},
        {"id": "cross-encoder", "score": 0.019317627},
    ],
]

#: `results[i].score` of the same three hits — the branch-fusion value, freshness-decayed.
#: NOT monotone with the delivered order, which is the defect the CE basis fixes.
_REAL_DELIVERED = [0.4615199863910675, 0.5792251825332642, 0.6673769354820251]


def _real_response() -> dict:
    """A trimmed real response: the three real traces above on minimal hit envelopes.

    Only `trace` and `score` are real; the identity fields are simplified because the
    extraction under test does not read them (path rewriting has its own tests).
    """
    return {
        "totalHits": 3,
        "matchCount": 3,
        "results": [
            {
                "id": f"h{i}",
                "score": _REAL_DELIVERED[i],
                "fields": {"doc_id": f"h{i}", "path": f"docs/h{i}.md",
                           "filename": f"h{i}.md", "is_chunk": "true",
                           "parent_doc_id": f"h{i}", "chunk_index": str(i)},
                "matchedFields": [], "excerptRegions": [], "trace": trace,
            }
            for i, trace in enumerate(_REAL_TRACES)
        ],
        "searchTrace": {"stages": [], "degradation": {}},
    }


def test_cross_encoder_score_is_read_from_the_real_wire_shape():
    """Proven against a real `debug:true` body, not a synthetic one.

    Pins the exact path — `results[i].trace[]`, entry with `id == "cross-encoder"`, its
    `score` — and the three real values.
    """
    response = _real_response()
    hits = response["results"]
    assert [wf.stage_score(h, "cross-encoder") for h in hits] == [
        0.2602539, 0.14123535, 0.019317627]
    assert [wf.stage_score(h, "fusion") for h in hits] == [
        0.42335153, 0.36807224, 0.56067425]

    basis, scores = wf.resolve_score_basis(hits)
    assert basis == wf.SCORE_BASIS_CROSS_ENCODER
    assert scores == [0.2602539, 0.14123535, 0.019317627]

    record = wf.capture_query_record(response, limit=10, epsilon=0.01)
    assert [t["score"] for t in record["hits[]"]] == [0.2602539, 0.14123535, 0.019317627], \
        "the stored tag must be the cross-encoder score"


def test_the_real_response_shows_why_the_delivered_score_is_the_wrong_basis():
    """The delivered score RISES down the list while the CE score falls.

    `results[i].score` equals the branch-fusion stage score, and the list is ordered by the
    cross-encoder. Grouping on the delivered score therefore grouped a CE-ordered list by an
    unrelated key — measured non-monotone in all 12 queries of both 2026-09-07 captures.
    """
    hits = _real_response()["results"]
    delivered = [h["score"] for h in hits]
    ce = [wf.stage_score(h, "cross-encoder") for h in hits]

    assert delivered == sorted(delivered), \
        "the delivered score INCREASES down the delivered order — it cannot be the sort key"
    assert ce == sorted(ce, reverse=True), \
        "the cross-encoder score decreases monotonically — it IS the sort key"
    # And each hit's delivered score is its branch-fusion stage score, not its CE score.
    for h in hits:
        assert abs(h["score"] - wf.stage_score(h, "branch-fusion")) < 1e-6


def test_a_hit_without_a_cross_encoder_entry_falls_back():
    """The fallback the dropped-CE case needs: no `cross-encoder` entry in `trace`."""
    response = _real_response()
    for h in response["results"]:
        h["trace"] = [st for st in h["trace"] if st["id"] != "cross-encoder"]
    basis, scores = wf.resolve_score_basis(response["results"])
    assert basis == wf.SCORE_BASIS_DELIVERED
    assert scores == _REAL_DELIVERED


def test_a_dropped_cross_encoder_fails_capture_health():
    """A capture whose CE was dropped records a DEGRADED pipeline, so it is refused.

    Both sides of a pair degrade together, so the diff gets QUIETER — the dangerous
    direction. Pair run 3 read as "2 regressions, nearly clean" with every query showing
    `cross-encoder: skipped / INFERENCE_FAILED`.
    """
    record = {
        "httpStatus": 200, "matchCount": 1, "hitCount": 1, "hits[]": [],
        "trace.stageStatuses": {"10:cross-encoder": "skipped"},
        "trace.stageReasons": {"10:cross-encoder": "INFERENCE_FAILED"},
    }
    base = _capture({"q1": record})
    problems = wf._cross_encoder_problems(base, base)
    assert problems, "a dropped cross-encoder must be a health problem"
    assert "INFERENCE_FAILED" in problems[0]
    assert "JUSTSEARCH_RERANK_GPU_MEM_MB" in problems[0], "the message must name the remedy"

    # A cross-encoder that RAN is not a problem, and neither is a by-design skip.
    ok = dict(record, **{"trace.stageStatuses": {"10:cross-encoder": "executed"},
                         "trace.stageReasons": {}})
    assert not wf._cross_encoder_problems(_capture({"q1": ok}), _capture({"q1": ok}))
    by_design = dict(record, **{"trace.stageReasons": {"10:cross-encoder": "not-selected"}})
    assert not wf._cross_encoder_problems(
        _capture({"q1": by_design}), _capture({"q1": by_design})), \
        "a by-design skip is not a drop"


def test_candidate_pool_counts_are_observed_only():
    """`totalHits` and `trace.stageCardinality` are pool sizes, not evidence.

    Both moved between two fresh ingests of ONE corpus on ONE build (q02/q03/q07/q09 of the
    2026-09-07 pair) because they count how many candidates a stage happened to consider,
    which follows the candidate budget and which chunks sat at a per-leg cutoff. Design 16
    names evidence selection, truncation points, citation targets and cancellation as the
    byte-equal fields and never these. `matchCount` is different and stays exact: it is an
    IndexSearcher.count over the query, a property of the corpus and the query.
    """
    response = _response_with_scores([0.9, 0.8, 0.5])
    record = wf.capture_query_record(response, limit=10, epsilon=0.01)
    assert "totalHits" not in record
    assert "trace.stageCardinality" not in record
    assert "matchCount" in record, "matchCount is NOT a pool size and must survive"

    # The shipped fixture no longer declares either, and still declares matchCount.
    fields = wf.load_fixture(DEFAULT_FIXTURE)["fields"]
    assert "queries.totalHits" not in fields
    assert "queries.trace.stageCardinality" not in fields
    assert fields["queries.matchCount"] == "exact"

    # An undeclared field is a regression by construction, so the removal only works because
    # the capture stopped emitting them. Prove the differ sees neither name.
    counts = wf.observed_query_counts(response, record)
    assert counts["totalHits"] == 3
    assert "stageCardinality" in counts


def test_capture_records_the_counts_in_the_observed_block(tmp_path: Path):
    """End to end through `capture`: the counts land in `observed`, not in `queries`."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    client_kwargs = {"transport": httpx.MockTransport(
        _search_backend(_absolute_search_response(), roots=[_ROOT]))}
    doc = _capture_with_transport(fixture, tmp_path / "out.json", client_kwargs)
    observed = doc["observed"]["queries"]["q1"]
    assert observed["hitsCaptured"] == 2
    assert observed["hitsCompared"] == 2
    assert "hitsCaptured" not in doc["queries"]["q1"]
    assert "hitsCompared" not in doc["queries"]["q1"]


# ---------------------------------------------------------------------------
# Run preconditions: the boot-time pins and the APPLIED sampling
# ---------------------------------------------------------------------------
#
# These are not fields either build produced — they are the conditions both runs were
# performed under, so they are capture-HEALTH problems rather than diffed rows. Every
# assertion below checks the problem TEXT, not just `pass is False`: a test that only
# asserted the verdict would pass because some unrelated health check happened to fire.

def _problems(baseline, candidate, fixture=None):
    fixture = fixture or _minimal_fixture({"queries.a": "exact"})
    return wf.diff(baseline, candidate, fixture)["health"]["problems"]


def _has(problems, *needles):
    return [p for p in problems if all(n in p for n in needles)]


def test_absent_pins_fail_the_diff():
    """No `provenance.pins` at all — an older capture, or an endpoint that 404s."""
    base = _capture({"q1": {"a": 1}}, provenance={"pins": None})
    cand = _capture({"q1": {"a": 1}})
    problems = _problems(base, cand)
    hit = _has(problems, "baseline", "provenance.pins is missing")
    assert hit, problems
    assert "index.vector.exhaustive_search" in hit[0]
    assert "JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true" in hit[0]
    assert wf.diff(base, cand, _minimal_fixture({"queries.a": "exact"}))["pass"] is False
    # The candidate had all four, so only ONE side is named.
    assert not _has(problems, "candidate:", "provenance.pins is missing")


def test_pins_missing_one_key_fail_the_diff_and_the_key_is_named():
    partial = dict(_HEALTHY_PINS)
    del partial["justsearch.llm.slots"]
    base = _capture({"q1": {"a": 1}}, provenance={"pins": partial})
    problems = _problems(base, _capture({"q1": {"a": 1}}))
    hit = _has(problems, "provenance.pins is missing")
    assert hit, problems
    # The named list is EXACTLY the absent key — not the three that were present.
    assert "provenance.pins is missing ['justsearch.llm.slots']" in hit[0]


def test_pins_that_differ_between_the_two_sides_fail_the_diff():
    """A pair whose sides ran under different boot-time settings is not a build comparison."""
    other = dict(_HEALTHY_PINS, **{"justsearch.llm.slots": "2"})
    problems = _problems(_capture({"q1": {"a": 1}}),
                         _capture({"q1": {"a": 1}}, provenance={"pins": other}))
    hit = _has(problems, "ran under DIFFERENT justsearch.llm.slots")
    assert hit, problems
    assert "'1'" in hit[0] and "'2'" in hit[0]      # both values, so the reader can act
    assert "two configurations, not two builds" in hit[0]


def test_absent_applied_sampling_fails_the_diff():
    """`provenance.sampling` is only what was REQUESTED — absence of the applied echo fails."""
    base = _capture({"q1": {"a": 1}}, provenance={"samplingApplied": None})
    problems = _problems(base, _capture({"q1": {"a": 1}}))
    hit = _has(problems, "baseline", "provenance.samplingApplied is absent")
    assert hit, problems
    assert "only what it requested" in hit[0]


def test_applied_sampling_missing_a_recorded_turn_fails_the_diff():
    base = _capture({"q1": {"a": 1}}, chat={"c1": {}, "c2": {}},
                    provenance={"samplingApplied": {"c1": dict(_HEALTHY_APPLIED)}})
    cand = _capture({"q1": {"a": 1}}, chat={"c1": {}, "c2": {}})
    problems = _problems(base, cand)
    hit = _has(problems, "baseline chatTurns/c2", "no entry in provenance.samplingApplied")
    assert hit, problems
    assert not _has(problems, "baseline chatTurns/c1", "no entry")


def test_a_build_that_echoes_no_applied_sampling_fails_the_diff():
    """The pre-PR-0b case: session_started omits all three keys, so the entry is all-null.

    This must FAIL rather than read as "the run applied no override" — it is exactly the
    situation `provenance.sampling` alone cannot distinguish, since the capture writes its
    own request back regardless of what the backend did with it.
    """
    silent = {"c1": {"temperature": None, "top_p": None, "seed": None}}
    base = _capture({"q1": {"a": 1}}, provenance={"samplingApplied": silent})
    problems = _problems(base, _capture({"q1": {"a": 1}}))
    hit = _has(problems, "baseline chatTurns/c1", "carried NO applied sampling")
    assert hit, problems
    assert "predating PR 0b" in hit[0]


def test_applied_sampling_that_differs_between_the_two_sides_fails_the_diff():
    hotter = {"c1": dict(_HEALTHY_APPLIED, temperature=0.7)}
    problems = _problems(_capture({"q1": {"a": 1}}),
                         _capture({"q1": {"a": 1}},
                                  provenance={"samplingApplied": hotter}))
    hit = _has(problems, "chatTurns/c1", "APPLIED different sampling")
    assert hit, problems
    assert "0.7" in hit[0] and "not a build difference" in hit[0]


def test_matching_preconditions_produce_no_precondition_problem():
    """The floor itself must be clean, or every assertion above proves nothing."""
    result = wf.diff(_capture({"q1": {"a": 1}}), _capture({"q1": {"a": 1}}),
                     _minimal_fixture({"queries.a": "exact"}))
    assert result["health"] == {"ok": True, "problems": []}


def test_applied_sampling_is_read_off_the_session_started_frame():
    frames = wf.parse_sse_frames(
        "event: session_started\n"
        'data: {"sessionId":"s","samplingTemperature":0.0,"samplingTopP":0.8,'
        '"samplingSeed":20260907}\n\n'
        "event: done\n"
        'data: {"finalResponse":"x"}\n\n'
    )
    assert wf.applied_sampling(frames) == {
        "temperature": 0.0, "top_p": 0.8, "seed": 20260907}
    # A key the backend omitted stays None — never defaulted to the requested value.
    partial = wf.parse_sse_frames(
        'event: session_started\ndata: {"sessionId":"s","samplingTemperature":0.0}\n\n')
    assert wf.applied_sampling(partial) == {
        "temperature": 0.0, "top_p": None, "seed": None}
    # A build predating PR 0b emits the one-key payload: all three absent.
    old = wf.parse_sse_frames('event: session_started\ndata: {"sessionId":"s"}\n\n')
    assert wf.applied_sampling(old) == {
        "temperature": None, "top_p": None, "seed": None}
    assert wf.applied_sampling([]) == {
        "temperature": None, "top_p": None, "seed": None}


def test_run_chat_turn_returns_the_applied_sampling_beside_the_record():
    """It rides NEXT TO the record, not inside it — an undeclared record key is a regression."""
    stream = (
        "event: session_started\n"
        'data: {"sessionId":"s","samplingTemperature":0.0,"samplingSeed":7}\n\n'
        'event: done\ndata: {"finalResponse":"x","iterationsUsed":2,"disposition":"COMPLETED"}\n\n'
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=stream,
                              headers={"Content-Type": "text/event-stream"})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        turn = wf.run_chat_turn(client, {"id": "c01", "content": "x"})
    assert turn.sampling_applied == {"temperature": 0.0, "top_p": None, "seed": 7}
    assert turn.record["disposition"] == "COMPLETED"
    for leaked in ("samplingApplied", "temperature", "samplingTemperature"):
        assert leaked not in turn.record


def test_pins_are_read_from_the_effective_config_endpoint():
    """The shape is `resolvedConfig[] = {key, value, source, ordinal, detail, candidates}`.

    `value` is a STRING and is OMITTED (not null) for a key no source supplied — the record
    is `@JsonInclude(NON_NULL)` — so an unset `index.vector.exhaustive_search` must read as
    MISSING, not as a value.
    """
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/debug/effective-config"
        return httpx.Response(200, json={"schemaVersion": 1, "resolvedConfig": [
            {"key": "justsearch.data.dir", "value": "F:/data", "source": "env_var",
             "ordinal": 400, "candidates": []},
            {"key": "index.vector.exhaustive_search", "value": "true", "source": "env_var",
             "ordinal": 400, "detail": "JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH",
             "candidates": []},
            {"key": "justsearch.llm.slots", "value": "1", "source": "jvm_arg",
             "ordinal": 500, "candidates": []},
            {"key": "justsearch.rerank.deadline_ms", "value": "5000", "source": "env_var",
             "ordinal": 400, "candidates": []},
            # No `value` key at all: nothing supplied one.
            {"key": "justsearch.rerank.chunks.deadline_ms", "source": "none",
             "ordinal": 0, "candidates": []},
        ]})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        pins, sources = wf.read_config_pins(client)
    assert pins == {"index.vector.exhaustive_search": "true",
                    "justsearch.llm.slots": "1",
                    "justsearch.rerank.deadline_ms": "5000"}
    assert "justsearch.rerank.chunks.deadline_ms" not in pins    # valueless != a value
    assert sources["justsearch.llm.slots"] == "jvm_arg"
    assert "justsearch.data.dir" not in pins                     # only the four pins


def test_an_absent_effective_config_endpoint_yields_empty_pins_not_an_exception():
    """`/api/config/effective` is what the PR 0b pair run fetched — it 404s.

    (`tmp/pr0b-pair/effective-config-1.json` is the NOT_FOUND body.) The capture must still
    be written, and the diff built on it must then FAIL health rather than pass silently.
    """
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"error": "No handler registered",
                                         "errorCode": "NOT_FOUND"})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(handler)) as client:
        assert wf.read_config_pins(client) == ({}, {})
    # A body with no `resolvedConfig` array is the same "cannot prove it" answer.
    def no_block(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"schemaVersion": 1, "keys": []})

    with httpx.Client(base_url="http://127.0.0.1:33221",
                      transport=httpx.MockTransport(no_block)) as client:
        assert wf.read_config_pins(client) == ({}, {})

    empty = _capture({"q1": {"a": 1}}, provenance={"pins": {}})
    result = wf.diff(empty, empty, _minimal_fixture({"queries.a": "exact"}))
    assert result["pass"] is False
    assert _has(result["health"]["problems"], "provenance.pins is missing")


def test_capture_records_the_pins_and_the_applied_sampling(tmp_path: Path):
    """End to end: both blocks land in `provenance`, and neither becomes a diffed field."""
    fixture = _minimal_fixture({"queries.hits[]": "equal-score-order"})
    client_kwargs = {"transport": httpx.MockTransport(
        _search_backend(_absolute_search_response(), roots=[_ROOT], chat=True))}
    doc = _capture_with_transport(fixture, tmp_path / "out.json", client_kwargs)
    prov = doc["provenance"]
    # Compared against the shared healthy set, which is itself derived from PINNED_CONFIG_KEYS —
    # a hard-coded literal here would have to be edited every time a pin is added, and the
    # editing is exactly where it would silently fall short of the code.
    assert prov["pins"] == _HEALTHY_PINS
    assert set(prov["pins"]) == set(wf.PINNED_CONFIG_KEYS)
    assert prov["pinSources"]["index.vector.exhaustive_search"] == "env_var"
    assert prov["samplingApplied"] == {"c1": {"temperature": 0.0, "top_p": 0.8, "seed": 7}}
    # The REQUESTED pin is still recorded separately — the two must be able to disagree.
    assert prov["sampling"] == {"temperature": 0.0, "seed": 7}
    assert "samplingApplied" not in doc["chatTurns"]["c1"]
    assert "pins" not in doc["chatTurns"]["c1"]
    # …and a capture written this way passes health against itself.
    assert wf.diff(doc, doc, fixture)["health"] == {"ok": True, "problems": []}


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
    """The note must pin the profile requirement AND the per-turn completion evidence.

    It used to assert "c02 in 6" — a measurement the next paired capture falsified: c02 ended
    MAX_ITERATIONS at 8 on BOTH sides at temperature 0, which is why the question was
    replaced (`test_c02_was_replaced_with_a_single_anchor_question`). The assertion now pins
    c01's still-true figure and the note's honesty about c02 instead of a stale number.
    """
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    notes = " ".join(fixture["notes"])
    assert "SAME CHAT PROFILE" in notes
    assert "compact" in notes and "11 GB" in notes
    assert "c01 completes on compact in 3 iterations" in notes
    assert "MAX_ITERATIONS at 8 in both captures" in notes


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
    # …and that the four are now OBSERVED, not merely asked for: the note must say the diff
    # fails on a missing or disagreeing pin, or a reader will keep reading it as advice.
    assert "THESE ARE NOW OBSERVED, NOT MERELY REQUESTED" in notes
    assert "/api/debug/effective-config" in notes
    assert "provenance.pins" in notes


def test_the_applied_sampling_proof_is_recorded_in_the_notes():
    """Requested vs applied is the whole mechanism — it must be written down, not implied."""
    fixture = wf.load_fixture(DEFAULT_FIXTURE)
    notes = " ".join(fixture["notes"])
    assert "THE CAPTURE PROVES THE SAMPLING PIN WAS APPLIED, NOT JUST SENT" in notes
    assert "provenance.samplingApplied" in notes
    assert "samplingTemperature" in notes
    assert "predating PR 0b" in notes
    # The pinned keys the code reads and the note names must not drift apart.
    for key in wf.PINNED_CONFIG_KEYS:
        assert key in notes, key


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
        record, _applied = wf.run_chat_turn(
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
    """The spec's query/mode ride verbatim; `limit` is doubled (see the cutoff-group tests)."""
    body = wf._build_search_body({"query": "q", "limit": 10, "mode": "hybrid"})
    assert body == {"query": "q", "limit": 20, "mode": "hybrid",
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


#: A synthetic capture's healthy run PRECONDITIONS: the four boot-time pins as
#: `/api/debug/effective-config` reports them (string values, as the real endpoint emits) and
#: the applied sampling `session_started` echoes. Part of the health FLOOR, like httpStatus
#: 200 — a test that is not about preconditions should not have to think about them.
#: Derived from PINNED_CONFIG_KEYS rather than listed, so adding a pin cannot leave this helper
#: silently short and turn every health assertion in this file into a "missing pin" failure that
#: has nothing to do with the property under test.
_HEALTHY_PIN_VALUES = {
    "index.vector.exhaustive_search": "true",
    "justsearch.llm.slots": "1",
    "justsearch.rerank.deadline_ms": "5000",
    "justsearch.rerank.chunks.deadline_ms": "5000",
    "justsearch.rerank.top_k": "100",
    "index.hybrid.candidate_limit_max": "5000",
    "index.hybrid.chunk_collapse_limit_multiplier": "50",
    "index.hybrid.leg_arbitration_enabled": "false",
    "index.hybrid.leg_recall_complete_enabled": "false",
}
_HEALTHY_PINS = {k: _HEALTHY_PIN_VALUES[k] for k in wf.PINNED_CONFIG_KEYS}
_HEALTHY_APPLIED = {"temperature": 0.0, "top_p": 0.8, "seed": 7}


def _capture(
    queries: dict | None = None,
    chat: dict | None = None,
    observed: dict | None = None,
    provenance: dict | None = None,
) -> dict:
    """A healthy synthetic capture: every record carries httpStatus 200 (+ hitCount).

    ``observed`` populates the NON-diffed ``observed.queries`` block (``scores``,
    ``hitsCaptured``, ``hitsCompared``). Omitted entirely, the capture looks like one taken
    before that block carried the counts — which the cutoff rule must fail closed on.

    ``provenance`` overrides the healthy precondition floor (pins + applied sampling); a key
    set to ``None`` there is DELETED, which is how a test says "this capture predates it".
    """
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
    prov: dict = {
        "available": True,
        "pins": dict(_HEALTHY_PINS),
        "samplingApplied": {rid: dict(_HEALTHY_APPLIED) for rid in c},
    }
    for key, value in (provenance or {}).items():
        if value is None:
            prov.pop(key, None)
        else:
            prov[key] = value
    doc = {
        "schema": wf.CAPTURE_SCHEMA,
        "fixture_id": "test-fixture",
        "fixture_version": 1,
        "provenance": prov,
        "queries": q,
        "chatTurns": c,
    }
    if observed is not None:
        doc["observed"] = {"queries": observed}
    return doc


def _response_with_scores(scores: list[float], ids: list[str] | None = None) -> dict:
    """A `/api/knowledge/search` response whose results carry exactly these scores."""
    names = ids or [f"h{i}" for i in range(len(scores))]
    return {
        "totalHits": len(scores),
        "matchCount": len(scores),
        "results": [
            {
                "id": name,
                "score": score,
                "fields": {"doc_id": name, "path": f"docs/{name}.md",
                           "filename": f"{name}.md", "is_chunk": "true",
                           "parent_doc_id": name, "chunk_index": "0"},
                "matchedFields": [], "excerptRegions": [], "trace": [],
            }
            for name, score in zip(names, scores)
        ],
        "searchTrace": {"stages": [], "degradation": {}},
    }


def _full_snapshot(fixture: dict) -> dict:
    frames = wf.parse_sse_frames(_SYNTHETIC_STREAM)
    return {
        "schema": wf.CAPTURE_SCHEMA,
        "fixture_id": fixture["id"],
        "fixture_version": fixture["version"],
        "provenance": {
            "available": True,
            "pins": dict(_HEALTHY_PINS),
            "samplingApplied": {t["id"]: dict(_HEALTHY_APPLIED)
                                for t in fixture["chatTurns"]},
        },
        "queries": {q["id"]: wf.capture_query_record(_SEARCH_RESPONSE)
                    for q in fixture["queries"]},
        "chatTurns": {
            t["id"]: wf.capture_chat_record(
                frames, cancel={"requested": True} if t.get("cancelAfterEvent") else None)
            for t in fixture["chatTurns"]
        },
    }


#: What the mock effective-config endpoint answers: the four pins, in the real
#: `resolvedConfig[]` shape (`{key, value, source, ordinal, detail, candidates}`) with STRING
#: values, as `EffectiveConfigEntry` serialises them.
_EFFECTIVE_CONFIG_BODY = {"schemaVersion": 1, "resolvedConfig": [
    {"key": key, "value": value, "source": "env_var", "ordinal": 400,
     "detail": "JUSTSEARCH_" + key.upper().replace(".", "_"), "candidates": []}
    for key, value in _HEALTHY_PINS.items()
]}

#: A `session_started`-first agent stream that echoes the APPLIED sampling (PR 0b).
_APPLIED_SAMPLING_STREAM = (
    "event: session_started\n"
    'data: {"sessionId":"s1","samplingTemperature":0.0,"samplingTopP":0.8,"samplingSeed":7}\n\n'
    "event: done\n"
    'data: {"finalResponse":"x","iterationsUsed":2,"toolCallsExecuted":1,'
    '"disposition":"COMPLETED","sources":[],"citations":[]}\n\n'
)


def _search_backend(response: dict, roots: list[str] | None = None, status: int = 200,
                    chat: bool = False):
    """A mock backend serving /api/status, /api/indexing/roots and the search POST.

    ``chat=True`` also serves the agent stream and the effective-config endpoint, for the
    tests that exercise the run PRECONDITIONS end to end.
    """
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/debug/effective-config":
            return httpx.Response(200, json=_EFFECTIVE_CONFIG_BODY) if chat else (
                httpx.Response(404, json={"errorCode": "NOT_FOUND"}))
        if chat and path == "/api/chat/agent":
            return httpx.Response(200, text=_APPLIED_SAMPLING_STREAM,
                                  headers={"Content-Type": "text/event-stream"})
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
