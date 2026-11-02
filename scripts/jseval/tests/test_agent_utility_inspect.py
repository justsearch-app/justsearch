"""Tests for jseval.agent_utility_inspect — the Inspect-AI-based executor.

Executor v2 (tempdoc 675): each cell now runs as an in-process Claude Agent SDK
session (`ClaudeSDKClient`), not a `claude -p` subprocess, so there is no argv
to build and no stdout stream-json to parse. These tests unit-test the PURE
LOGIC that sits around the SDK call:

- `agent_utility_task` — the single-pool `condition × query` cross-product
  construction (one Inspect task for the whole matrix, not one per condition).
- `_record_cell` — the executed-vs-blocked projection of the SDK's own
  objects (tool-use blocks, tool-result blocks, `ResultMessage`) into
  `state.metadata`, plus the offered-MCP-surface tri-state assertion.
- `run_utility_eval` — corpus-dir isolation, cleanup-on-raise, the
  watched-roots safety gate, and the no-mcp gate skip.

`ClaudeSDKClient`/the async SDK session itself is NOT mocked here — a live
smoke test covers the SDK-integration path separately.

`inspect_ai` is an opt-in extra (`pip install -e scripts/jsharness -e 'scripts/jseval[agent]'`); skipped if
not installed.
"""

from __future__ import annotations

import asyncio
import json
import hashlib
import os
import random
import subprocess
import time
import types
from pathlib import Path

import pytest

pytest.importorskip("inspect_ai")

import inspect_ai  # noqa: E402
from inspect_ai import Task  # noqa: E402
from inspect_ai.model import ChatMessageUser, ModelName  # noqa: E402
from inspect_ai.solver import TaskState  # noqa: E402

from jseval import agent_utility_inspect as aui  # noqa: E402
from jseval.agent_manifest import mcp_tool_surface_hash  # noqa: E402
from jseval.agent_retrieval_eval import (  # noqa: E402
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
)
from jseval.utility_evidence import _error_class  # noqa: E402
from jseval.utility_governance import RESOURCE_EXHAUSTION, classify_error_kind  # noqa: E402


def _state(input_text="what is x?", sample_id="q0"):
    return TaskState(
        model=ModelName("mockllm/model"), sample_id=sample_id, epoch=0,
        input=input_text, messages=[ChatMessageUser(content=input_text)],
    )


def _rmsg(**overrides):
    """A minimal stand-in for `claude_agent_sdk.ResultMessage` (tests only need
    the attributes `_record_cell` reads off it, not the real SDK class)."""
    defaults = dict(
        total_cost_usd=0.02, usage={}, num_turns=1, is_error=False,
        permission_denials=[], result="the answer",
    )
    defaults.update(overrides)
    return types.SimpleNamespace(**defaults)


# --- agent_utility_task: single-pool condition x query cross-product
# (tempdoc 675 -- replaces the old per-condition-task construction). ---

def test_agent_utility_task_builds_one_task_over_the_full_cross_product(tmp_path):
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(json.dumps([
        {"query": "q1", "answer": "a1", "question_type": "t1"},
        {"query": "q2", "answer": "a2", "question_type": "t2"},
    ]), encoding="utf-8")

    t = aui.agent_utility_task(
        conditions=("A", "B"), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", corpus_dataset="ds", corpus_signature="sig",
    )

    assert isinstance(t, Task)
    samples = list(t.dataset)
    # ONE task; its dataset is the flat condition x query cross-product.
    assert len(samples) == 4  # 2 conditions x 2 queries

    by_id = {s.id: s for s in samples}
    assert set(by_id) == {"A|q0", "A|q1", "B|q0", "B|q1"}
    for cond in ("A", "B"):
        for i in (0, 1):
            sample = by_id[f"{cond}|q{i}"]
            assert sample.metadata["condition"] == cond

    # Task metadata carries the cohort identity, but NOT a top-level condition
    # (condition lives on the sample, not the task -- one task, many conditions).
    assert "model" in t.metadata
    assert "corpus" in t.metadata
    assert "cohort" in t.metadata
    assert "condition" not in t.metadata


def test_sample_metadata_carries_evidence_ids(tmp_path):
    # D6 (768 step 1): the gold identity per query (`evidence_ids`) must reach
    # Sample.metadata so rank-of-gold capture (step 2) can resolve gold hits.
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(json.dumps([
        {"query": "q1", "answer": "a1", "question_type": "2_hop",
         "evidence_ids": ["g1", "g2"]},
        {"query": "q2", "answer": "a2", "question_type": "1_hop"},  # no evidence_ids
    ]), encoding="utf-8")

    t = aui.agent_utility_task(
        conditions=("A", "C"), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku",
    )
    by_id = {s.id: s for s in list(t.dataset)}
    assert by_id["A|q0"].metadata["evidence_ids"] == ["g1", "g2"]
    assert by_id["C|q0"].metadata["evidence_ids"] == ["g1", "g2"]
    # A query without the field carries None, never a fabricated empty list.
    assert by_id["A|q1"].metadata["evidence_ids"] is None


def test_agent_utility_task_respects_max_queries(tmp_path):
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(json.dumps([
        {"query": "q1", "answer": "a1"},
        {"query": "q2", "answer": "a2"},
        {"query": "q3", "answer": "a3"},
    ]), encoding="utf-8")

    t = aui.agent_utility_task(
        conditions=("A", "B", "C"), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", max_queries=1,
    )

    samples = list(t.dataset)
    assert len(samples) == 3  # 3 conditions x 1 query


# --- _record_cell: executed-vs-blocked tool-call projection (tempdoc 675
# finding 3 -- the SDK reports a *blocked* disallowed-tool attempt too, so
# only ACTUALLY EXECUTED tools may feed the leak/disallowed assertions). ---

def test_record_cell_splits_executed_from_blocked_tool_calls():
    state = _state()
    got = {
        "attempts": {
            "t1": {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
            "t2": {"tool": "Bash", "input": {"command": "ls"}},   # errored result -> blocked
            "t3": {"tool": "WebSearch", "input": {"query": "x"}},  # no result at all -> blocked
        },
        "results": {
            "t1": {"is_error": False},
            "t2": {"is_error": True},
        },
        "texts": ["the answer"],
        "rmsg": _rmsg(),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["tool_calls"] == [
        {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
    ]
    blocked_tools = {tc["tool"] for tc in state.metadata["tool_calls_blocked"]}
    assert blocked_tools == {"Bash", "WebSearch"}


def test_record_cell_treats_permission_denied_tool_as_blocked_not_executed():
    """A tool_use whose name IS in `rmsg.permission_denials` counts as blocked
    even if a (spurious) non-error result entry exists for it.

    Uses the REAL SDK dict shape for `permission_denials`
    (`{"tool_name", "tool_use_id", "tool_input"}`) — a prior version of this test
    used bare strings, which cannot occur from the live SDK and masked a
    `set([{...}])` TypeError crash in `_record_cell` (tempdoc 675 review finding)."""
    state = _state()
    got = {
        "attempts": {"t1": {"tool": "WebFetch", "input": {"url": "http://x"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [],
        "rmsg": _rmsg(permission_denials=[
            {"tool_name": "WebFetch", "tool_use_id": "t1", "tool_input": {"url": "http://x"}}]),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["tool_calls"] == []
    assert [tc["tool"] for tc in state.metadata["tool_calls_blocked"]] == ["WebFetch"]


def test_record_cell_disallowed_and_leak_suspect_computed_over_executed_only():
    """A blocked disallowed/leak-suspect tool must NOT be flagged; an executed
    one IS flagged -- reuses the real `find_disallowed_tool_calls` /
    `find_leak_suspect_tool_calls` helpers, not reimplemented logic."""
    state = _state()
    disallowed = build_disallowed_tools("C")  # Bash is disallowed under C
    got = {
        "attempts": {
            "t1": {"tool": "Bash", "input": {"command": "cat /eval/queries.json"}},  # executes
            "t2": {"tool": "Bash", "input": {"command": "cat /corpus/secret"}},       # blocked
            "t3": {"tool": "Read", "input": {"file_path": "/eval/queries.json"}},     # executes, leak-suspect
        },
        "results": {
            "t1": {"is_error": False},
            "t3": {"is_error": False},
            # t2 has no result entry at all -> blocked.
        },
        "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", disallowed, None)

    executed = state.metadata["tool_calls"]
    assert {tc["tool"] for tc in executed} == {"Bash", "Read"}

    # Sanity: the projection matches calling the real helpers directly on the
    # executed list (i.e. _record_cell isn't reimplementing this logic).
    assert state.metadata["disallowed_tool_calls"] == find_disallowed_tool_calls(executed, disallowed)
    assert state.metadata["leak_suspect_tool_calls"] == find_leak_suspect_tool_calls(executed)

    # The blocked Bash call must not leak into either assertion list (Read is also
    # disallowed under C, so both executed calls are flagged -- only check the
    # Bash entries specifically).
    disallowed_bash_commands = [
        tc["input"]["command"] for tc in state.metadata["disallowed_tool_calls"]
        if tc["tool"] == "Bash"
    ]
    assert disallowed_bash_commands == ["cat /eval/queries.json"]  # the EXECUTED Bash call only


# --- toolsearch_targets / tool_call_sequence (tempdoc 725 increment 3): the
# adoption-funnel per-cell fields. Mirrors the real producer shape -- these
# tests drive `_record_cell` itself (the actual producer), not a re-derived
# shortcut (unreachable-seed-green discipline). ---

def test_toolsearch_targets_extracts_only_resolved_select_names_in_order():
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {
            "query": "select:mcp__justsearch__justsearch_search, mcp__justsearch__justsearch_answer"}},
        "t2": {"tool": "Read", "input": {"file_path": "/corpus/doc.txt"}},
    }
    assert aui._toolsearch_targets(attempts) == [
        "mcp__justsearch__justsearch_search", "mcp__justsearch__justsearch_answer",
    ]


def test_toolsearch_targets_rejects_free_text_keyword_queries():
    """A keyword-style ToolSearch query (no `select:` prefix) is a search
    STRING, not a resolved-tool declaration -- it must never leak into
    toolsearch_targets (the redaction contract this field exists under)."""
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {"query": "notification jira slack search tools"}},
    }
    assert aui._toolsearch_targets(attempts) == []


def test_toolsearch_targets_filters_non_justsearch_names_from_select_list():
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {
            "query": "select:Read,mcp__justsearch__justsearch_search,Bash"}},
    }
    assert aui._toolsearch_targets(attempts) == ["mcp__justsearch__justsearch_search"]


def test_toolsearch_targets_dedupes_preserving_first_seen_order():
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {"query": "select:mcp__justsearch__justsearch_search"}},
        "t2": {"tool": "ToolSearch", "input": {
            "query": "select:mcp__justsearch__justsearch_answer,mcp__justsearch__justsearch_search"}},
    }
    assert aui._toolsearch_targets(attempts) == [
        "mcp__justsearch__justsearch_search", "mcp__justsearch__justsearch_answer",
    ]


def test_toolsearch_targets_empty_when_no_toolsearch_call():
    attempts = {"t1": {"tool": "Read", "input": {"file_path": "/corpus/doc.txt"}}}
    assert aui._toolsearch_targets(attempts) == []


def test_toolsearch_targets_rejects_free_text_appended_to_a_justsearch_looking_segment():
    """tempdoc 725 review finding #1 (MAJOR), reviewer's adversarial case
    verbatim: a space-joined `select:` segment whose first token merely
    STARTS WITH the justsearch prefix, followed by a path and an email, must
    NOT leak that free text into toolsearch_targets -- a prefix-only check
    captured the whole segment verbatim into durable sanitized evidence. The
    fullmatch-against-the-tool-name-grammar fix drops the malformed segment
    entirely (no well-formed name is present here, so the result is empty)."""
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {
            "query": "select:mcp__justsearch__search /etc/passwd bob@evil.com"}},
    }
    targets = aui._toolsearch_targets(attempts)
    assert targets == []
    for name in targets:
        assert "/etc/passwd" not in name
        assert "bob@evil.com" not in name
        assert " " not in name


def test_toolsearch_targets_keeps_wellformed_name_alongside_a_malformed_segment():
    """The boolean fact 'a justsearch-ish reference existed' is not a new
    field -- a well-formed name elsewhere in the same comma list is still
    captured, but the malformed free-text segment next to it is dropped, not
    merged or truncated into a partial name."""
    attempts = {
        "t1": {"tool": "ToolSearch", "input": {
            "query": "select:mcp__justsearch__search /etc/passwd bob@evil.com,"
                     "mcp__justsearch__justsearch_answer"}},
    }
    assert aui._toolsearch_targets(attempts) == ["mcp__justsearch__justsearch_answer"]


def test_record_cell_populates_toolsearch_targets_and_tool_call_sequence():
    """Producer-shaped: a ToolSearch discovery call, a BLOCKED mcp call (denied),
    and an OK mcp call, in one cell -- mirrors the real message-stream shape
    `_record_cell` consumes (an insertion-ordered attempts dict)."""
    state = _state()
    got = {
        "attempts": {
            "t1": {"tool": "ToolSearch", "input": {
                "query": "select:mcp__justsearch__justsearch_search"}},
            "t2": {"tool": "mcp__justsearch__justsearch_search", "input": {"query": "x"}},
            "t3": {"tool": "mcp__justsearch__justsearch_search", "input": {"query": "y"}},
        },
        "results": {
            "t1": {"is_error": False},
            # t2 has no result entry at all -> blocked.
            "t3": {"is_error": False},
        },
        "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", build_disallowed_tools("C"), None)

    assert state.metadata["toolsearch_targets"] == ["mcp__justsearch__justsearch_search"]
    assert state.metadata["tool_call_sequence"] == [
        {"name": "ToolSearch", "status": "ok"},
        {"name": "mcp__justsearch__justsearch_search", "status": "blocked"},
        {"name": "mcp__justsearch__justsearch_search", "status": "ok"},
    ]


def test_record_cell_tool_call_sequence_marks_disallowed_over_blocked():
    """A disallowed tool that never executes is still reported as
    "disallowed", not the more generic "blocked" -- the finer of the two
    signals wins."""
    state = _state()
    disallowed = build_disallowed_tools("C")  # Bash is disallowed under C
    got = {
        "attempts": {"t1": {"tool": "Bash", "input": {"command": "cat /corpus/secret"}}},
        "results": {},
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", disallowed, None)

    assert state.metadata["tool_call_sequence"] == [{"name": "Bash", "status": "disallowed"}]


# --- tempdoc 736 U3: the four-state (ok/blocked/disallowed/errored) partition
# must be complete and correct over the FULL cartesian of {result present/absent}
# x {is_error T/F} x {denied T/F} x {disallowed T/F}. Precedence: disallowed >
# blocked (no execution) > errored (executed, is_error) > ok. ---

@pytest.mark.parametrize(
    "result_present,is_error,denied,disallowed,expected",
    [
        # result absent -- is_error is moot (no result carries an is_error flag).
        (False, False, False, False, "blocked"),
        (False, False, False, True, "disallowed"),
        (False, False, True, False, "blocked"),
        (False, False, True, True, "disallowed"),
        (False, True, False, False, "blocked"),
        (False, True, False, True, "disallowed"),
        (False, True, True, False, "blocked"),
        (False, True, True, True, "disallowed"),
        # result present
        (True, False, False, False, "ok"),
        (True, False, False, True, "disallowed"),
        (True, False, True, False, "blocked"),
        (True, False, True, True, "disallowed"),
        (True, True, False, False, "errored"),
        (True, True, False, True, "disallowed"),
        (True, True, True, False, "blocked"),
        (True, True, True, True, "disallowed"),
    ],
)
def test_record_cell_four_state_status_partition_is_complete_and_correct(
    result_present, is_error, denied, disallowed, expected,
):
    state = _state()
    tool_name = "mcp__justsearch__search"
    results = {}
    if result_present:
        results["t1"] = {"is_error": is_error}
    rmsg_kwargs = {}
    if denied:
        rmsg_kwargs["permission_denials"] = [
            {"tool_name": tool_name, "tool_use_id": "t1", "tool_input": {}}]
    got = {
        "attempts": {"t1": {"tool": tool_name, "input": {}}},
        "results": results,
        "texts": [], "rmsg": _rmsg(**rmsg_kwargs), "mcp_servers": None, "justsearch_tools": [],
    }
    disallowed_tools = [tool_name] if disallowed else []

    aui._record_cell(state, got, "C", disallowed_tools, None)

    assert state.metadata["tool_call_sequence"] == [{"name": tool_name, "status": expected}]


def test_record_cell_tool_result_digests_content_is_error_matches_errored_status():
    """The `content_is_error` digest field (D9) and the `errored` status (D10) are
    cross-consistent BY CONSTRUCTION -- both derive from the same
    `results[tid]["is_error"]`."""
    state = _state()
    got = {
        "attempts": {
            "t1": {"tool": "mcp__justsearch__search", "input": {}},
            "t2": {"tool": "mcp__justsearch__search", "input": {}},
        },
        "results": {
            "t1": {"is_error": True, "content": "boom"},
            "t2": {"is_error": False, "content": "ok"},
        },
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", [], None)

    sequence = state.metadata["tool_call_sequence"]
    digests = state.metadata["tool_result_digests"]
    assert len(sequence) == len(digests) == 2
    for seq_entry, digest_entry in zip(sequence, digests):
        assert (seq_entry["status"] == "errored") == bool(digest_entry["content_is_error"])


def test_record_cell_tool_result_digests_never_stash_raw_content():
    """tempdoc 736 D9 leak boundary + the echo-leak assertion: a result whose
    content contains a known corpus-shaped string must not have that string appear
    anywhere in the projected `tool_result_digests` (hash/len/shape/flags only)."""
    state = _state()
    secret = "CORPUS_SECRET_STRING_a1b2c3"
    got = {
        "attempts": {"t1": {"tool": "mcp__justsearch__search", "input": {}}},
        "results": {"t1": {"is_error": False, "content": f"Evidence pack: 1 passages ({secret})"}},
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", [], None)

    digests = state.metadata["tool_result_digests"]
    assert digests == [{
        "content_sha256": aui._content_sha256(f"Evidence pack: 1 passages ({secret})"),
        "content_len": len(f"Evidence pack: 1 passages ({secret})"),
        "content_is_error": False,
        "content_shape": "text",
        "furniture_markers": {
            "rationale": False, "evidence_pack": True, "coverage": False, "degradation": False,
        },
        "delivered_tier": "prose",
        "delivered_fields": None,
        "component_bytes": None,
        "ordered_doc_ids": None,
        "scores": None,
        "gold_rank": None,
    }]
    assert secret not in json.dumps(digests)


# --- tempdoc 768 D6 (step 2): rank-of-gold at capture ---


def _search_content(ordered_ids, scores=None):
    """A structured-json `justsearch_search` delivery (a JSON *object* string, the
    `structured-json` tier) with `results[]` in rank order -- mirrors
    `McpEvidenceProjection.searchEvidence` (`id`/`score` per hit)."""
    scores = scores if scores is not None else [1.0 - 0.1 * i for i in range(len(ordered_ids))]
    return json.dumps({
        "results": [{"id": did, "score": sc} for did, sc in zip(ordered_ids, scores)],
        "coverage": {"totalHits": len(ordered_ids)},
    })


def test_tool_result_digest_entry_gold_rank_hit():
    content = _search_content(["docA", "docB", "goldZ", "docC"], [0.9, 0.8, 0.7, 0.6])
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["goldZ", "goldY"])
    assert entry["ordered_doc_ids"] == ["docA", "docB", "goldZ", "docC"]
    assert entry["scores"] == [0.9, 0.8, 0.7, 0.6]
    assert entry["gold_rank"] == 2  # 0-based rank of the first gold id


def test_tool_result_digest_entry_gold_rank_miss():
    # Search ran, ranking captured, but no gold id present -> gold_rank is None
    # (never a fabricated -1).
    content = _search_content(["docA", "docB", "docC"])
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["goldZ"])
    assert entry["ordered_doc_ids"] == ["docA", "docB", "docC"]
    assert entry["gold_rank"] is None


def test_tool_result_digest_entry_gold_rank_non_search_null():
    # A prose / non-`justsearch_search` delivery (no structured `results[]`):
    # ordered_doc_ids/scores/gold_rank are all None, never fabricated.
    prose = aui._tool_result_digest_entry(
        {"is_error": False, "content": "Evidence pack: 3 passages"}, evidence_ids=["goldZ"])
    assert prose["ordered_doc_ids"] is None
    assert prose["scores"] is None
    assert prose["gold_rank"] is None
    # A structured `justsearch_answer`-shape delivery (quality/citations, no
    # results[]) is likewise null for the rank fields.
    answer = aui._tool_result_digest_entry(
        {"is_error": False, "content": json.dumps({"quality": {}, "citations": []})},
        evidence_ids=["goldZ"])
    assert answer["ordered_doc_ids"] is None
    assert answer["gold_rank"] is None


def test_tool_result_digest_entry_gold_rank_normalizes_path_ids_to_basenames():
    # tempdoc 768 §E: the LIVE McpEvidenceProjection results[*].id is an absolute
    # corpus path (e.g. ...\corpus-dir\limker1.txt) while the queries file's
    # evidence_ids are extensionless basenames (limker1). gold_rank must normalize
    # both sides (basename/ext/case) or it would ALWAYS miss. ordered_doc_ids stay
    # the RAW delivered ids.
    path_ids = [
        r"f:\corpus-dir\cavby8.txt",
        r"f:\corpus-dir\Limker1.TXT",   # gold, different case + ext
        r"f:\corpus-dir\other9.txt",
    ]
    content = _search_content(path_ids, [0.9, 0.8, 0.7])
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["limker1", "tasholt2"])
    assert entry["ordered_doc_ids"] == path_ids            # raw ids preserved
    assert entry["gold_rank"] == 1                          # matched despite path+case+ext
    # A gold id that is not present anywhere -> None (never fabricated).
    miss = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["absent42"])
    assert miss["gold_rank"] is None


def test_normalize_doc_id_basename_ext_lower():
    assert aui._normalize_doc_id(r"f:\a\b\Limker1.TXT") == "limker1"
    assert aui._normalize_doc_id("limker1") == "limker1"
    assert aui._normalize_doc_id("dir/3467622") == "3467622"
    assert aui._normalize_doc_id(None) is None


def test_tool_result_digest_entry_gold_rank_null_without_evidence_ids():
    # Ranking still captured, but with no gold list gold_rank stays None.
    content = _search_content(["docA", "goldZ"])
    entry = aui._tool_result_digest_entry({"is_error": False, "content": content})
    assert entry["ordered_doc_ids"] == ["docA", "goldZ"]
    assert entry["gold_rank"] is None


def test_record_cell_threads_evidence_ids_into_gold_rank():
    # End-to-end through _record_cell: evidence_ids on state.metadata (step 1)
    # reaches the per-call digest's gold_rank (step 2).
    state = _state()
    state.metadata["evidence_ids"] = ["goldZ"]
    got = {
        "attempts": {"t1": {"tool": "mcp__justsearch__justsearch_search", "input": {}}},
        "results": {"t1": {"is_error": False,
                           "content": _search_content(["docA", "goldZ", "docC"])}},
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }
    aui._record_cell(state, got, "C", [], None)
    assert state.metadata["tool_result_digests"][0]["gold_rank"] == 1


# --- tempdoc 755 Track 1: MCP surface-capture hardening (retry + integrity-checked fallback) ---


def _mcp_status_populated():
    return {"servers": [{
        "name": "justsearch", "status": "connected",
        "tools": [{"name": "search", "description": "Search", "inputSchema": {}}],
    }]}


class _FakeStatusClient:
    """Minimal stand-in for ClaudeSDKClient: `_mcp_surface` only calls `get_mcp_status`.
    `responses` is consumed one per call; the LAST entry repeats for any further calls."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = 0

    async def get_mcp_status(self):
        self.calls += 1
        idx = min(self.calls - 1, len(self._responses) - 1)
        return self._responses[idx]


def test_mcp_surface_first_probe_reports_status_evidence():
    client = _FakeStatusClient([_mcp_status_populated()])
    servers, js_tools, _js_surface, evidence = asyncio.run(aui._mcp_surface(client, with_tool=True))
    assert client.calls == 1
    assert js_tools == ["mcp__justsearch__search"]
    assert evidence == "status"


def test_mcp_surface_empty_then_populated_reports_status_retry(monkeypatch):
    """The core Track 1 retry path: a transient empty get_mcp_status recovers on reprobe."""
    monkeypatch.setattr(aui, "_MCP_SURFACE_RETRY_BACKOFF_S", 0.0)
    client = _FakeStatusClient([{}, _mcp_status_populated()])  # empty, then populated
    servers, js_tools, _js_surface, evidence = asyncio.run(aui._mcp_surface(client, with_tool=True))
    assert client.calls == 2
    assert js_tools == ["mcp__justsearch__search"]
    assert evidence == "status-retry"


def test_mcp_surface_permanently_empty_stays_unverified(monkeypatch):
    monkeypatch.setattr(aui, "_MCP_SURFACE_RETRY_BACKOFF_S", 0.0)
    client = _FakeStatusClient([{}])  # never reports a surface
    servers, js_tools, _js_surface, evidence = asyncio.run(aui._mcp_surface(client, with_tool=True))
    assert client.calls == aui._MCP_SURFACE_PROBE_ATTEMPTS  # exhausted the bounded retry
    assert js_tools == []
    assert evidence is None


def test_mcp_surface_non_with_tool_probes_once_never_retries(monkeypatch):
    """A condition-A cell legitimately reports no surface and must not be retried into one."""
    monkeypatch.setattr(aui, "_MCP_SURFACE_RETRY_BACKOFF_S", 0.0)
    client = _FakeStatusClient([{"servers": []}])  # known-empty (status available, zero servers)
    servers, js_tools, _js_surface, evidence = asyncio.run(aui._mcp_surface(client, with_tool=False))
    assert client.calls == 1
    assert servers == []
    assert evidence is None


def test_record_cell_fallback_documents_unverified_when_status_empty_but_mcp_executed():
    """Integrity rule: status never reported a surface but the cell executed a
    mcp__justsearch__* tool. The executed-subset cross-check is RECORDED, but the cell stays
    UNVERIFIED (no fabricated hash) -- a subset of executed tools cannot establish that the
    full offered surface equalled the declared surface."""
    state = _state()
    declared = [{"name": "mcp__justsearch__search", "description": "Search", "input_schema": {}}]
    declared_hash = mcp_tool_surface_hash(declared)
    got = {
        "attempts": {"t1": {"tool": "mcp__justsearch__search", "input": {"query": "x"}}},
        "results": {"t1": {"is_error": False, "content": "ok"}},
        "texts": [], "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [], "justsearch_tool_surface": [],
        "surface_evidence": None,
    }

    aui._record_cell(state, got, "C", build_disallowed_tools("C"), "mcp.json",
                     declared_hash, declared)

    assert state.metadata["surface_evidence"] is None
    assert state.metadata["observed_mcp_tool_surface_hash"] is None
    assert state.metadata["mcp_surface_unverified"] is True
    fallback = state.metadata["mcp_surface_fallback"]
    assert fallback["verified"] is False
    assert fallback["executed_justsearch_subset_of_declared"] is True


def test_record_cell_unverified_preserved_with_no_status_and_no_mcp_calls():
    """No executed mcp call + no status => still unverified, and NO fallback object is
    fabricated. Gate semantics (a missing hash is a capture miss) are unchanged."""
    state = _state()
    declared = [{"name": "mcp__justsearch__search", "description": "Search", "input_schema": {}}]
    got = {
        "attempts": {"t1": {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [], "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [], "justsearch_tool_surface": [],
        "surface_evidence": None,
    }

    aui._record_cell(state, got, "C", build_disallowed_tools("C"), "mcp.json",
                     mcp_tool_surface_hash(declared), declared)

    assert state.metadata["surface_evidence"] is None
    assert state.metadata["observed_mcp_tool_surface_hash"] is None
    assert state.metadata["mcp_surface_unverified"] is True
    assert "mcp_surface_fallback" not in state.metadata


def test_record_cell_tool_result_digests_furniture_markers_block_list_content_shape():
    """tempdoc 736 L1 probe follow-up: `ToolResultBlock.content` is typed
    `str | list[dict[str, Any]] | None` on the real Claude Agent SDK
    (`claude_agent_sdk/types.py`, confirmed against the installed package) -- a
    tool result can arrive as a LIST of content blocks (`[{"type": "text",
    "text": ...}]`), not only as a plain string. `_content_sha256`/`_content_len`
    operate on the raw `content` value directly (shape-agnostic: `len()` /
    `json.dumps()` both accept `str` or `list`), so they already cover this
    shape; this fixture proves the SAME real shape drives `furniture_markers`
    correctly too -- both readings go through the ONE extraction helper,
    `_content_text`, so there is a single source of truth for "what text did
    this result contain" across sha/len and the marker booleans."""
    state = _state()
    secret = "CORPUS_SECRET_STRING_a1b2c3"
    block_list_content = [
        {"type": "text", "text": f"Evidence pack: 1 passages ({secret})"},
    ]
    got = {
        "attempts": {"t1": {"tool": "mcp__justsearch__justsearch_answer", "input": {}}},
        "results": {"t1": {"is_error": False, "content": block_list_content}},
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", [], None)

    digests = state.metadata["tool_result_digests"]
    assert digests == [{
        "content_sha256": aui._content_sha256(block_list_content),
        "content_len": len(block_list_content),
        "content_is_error": False,
        "content_shape": "blocks",
        "furniture_markers": {
            "rationale": False, "evidence_pack": True, "coverage": False, "degradation": False,
        },
        "delivered_tier": "blocks",
        "delivered_fields": None,
        "component_bytes": None,
        "ordered_doc_ids": None,
        "scores": None,
        "gold_rank": None,
    }]
    assert secret not in json.dumps(digests)


def test_record_cell_tool_result_digests_null_for_blocked_and_disallowed_attempts():
    """No result arrived (blocked) or the tool never executed (disallowed) -- the
    digest carries honest nulls, never a fabricated zero/empty hash."""
    state = _state()
    disallowed = ["Bash"]
    got = {
        "attempts": {
            "t1": {"tool": "Bash", "input": {}},  # disallowed, never executes
            "t2": {"tool": "WebFetch", "input": {}},  # no result at all -> blocked
        },
        "results": {},
        "texts": [], "rmsg": _rmsg(), "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", disallowed, None)

    for entry in state.metadata["tool_result_digests"]:
        assert entry["content_sha256"] is None
        assert entry["content_len"] is None
        assert entry["content_is_error"] is None
        assert entry["content_shape"] == "empty"
        assert entry["furniture_markers"] == {
            "rationale": False, "evidence_pack": False, "coverage": False, "degradation": False,
        }
        assert entry["delivered_tier"] is None
        assert entry["delivered_fields"] is None


def test_record_cell_stashes_cost_turns_and_tokens_from_rmsg():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": ["ok"],
        "rmsg": _rmsg(total_cost_usd=0.1234, num_turns=5,
                       usage={"cache_creation_input_tokens": 77}, result="ok"),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["cost_usd"] == 0.1234
    assert state.metadata["num_turns"] == 5
    assert state.metadata["unique_tokens"] == 77
    assert state.output.completion == "ok"
    assert "error" not in state.metadata


def test_record_cell_stashes_raw_usage_and_resolved_provider_model():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": ["ok"],
        "resolved_models": {"claude-haiku-4-5-20251001"},
        "rmsg": _rmsg(
            usage={"input_tokens": 11, "cache_creation_input_tokens": 7},
            model_usage={"claude-haiku-4-5-20251001": {"inputTokens": 18}},
        ),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["resolved_model"] == "claude-haiku-4-5-20251001"
    assert state.metadata["usage"]["input_tokens"] == 11
    assert state.metadata["model_usage"]["claude-haiku-4-5-20251001"]["inputTokens"] == 18
    assert "error" not in state.metadata


def test_source_identity_resume_rejects_git_drift(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    first = aui._capture_or_load_source_identity(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
    )
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "b" * 40)
    with pytest.raises(ValueError, match="source_git_sha"):
        aui._capture_or_load_source_identity(
            log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
            declared_corpus_signature="", search_config_cohort_key="search-1",
        )

    assert first["source_git_sha"] == "a" * 40
    assert first["corpus"]["signature"] is not None


def test_source_identity_hashes_untracked_files_but_excludes_its_log_dir(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", "fixture@example.test"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.name", "Fixture"], cwd=repo, check=True)
    (repo / "tracked.txt").write_text("tracked", encoding="utf-8")
    subprocess.run(["git", "add", "tracked.txt"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-qm", "fixture"], cwd=repo, check=True)
    corpus = tmp_path / "corpus-outside-repo"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("corpus", encoding="utf-8")
    logs = repo / "logs"
    monkeypatch.chdir(repo)

    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
    )
    first = aui._capture_or_load_source_identity(**kwargs)
    assert first["source_git_state"]["dirty"] is False
    # The sidecar created inside logs is run output and must not poison resume.
    assert aui._capture_or_load_source_identity(**kwargs) == first

    (repo / "new-source.txt").write_text("untracked source", encoding="utf-8")
    with pytest.raises(ValueError, match="source_git_dirty|source_git_state"):
        aui._capture_or_load_source_identity(**kwargs)


def test_source_identity_resume_rejects_changed_corpus_and_query_bytes(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64,
        "untracked_sha256": "0" * 64,
        "untracked_count": 0,
        "dirty": False,
    })
    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
        queries_path=str(queries), conditions=("A", "B"), seeds=2,
        mcp_tool_surface=[{
            "name": "mcp__justsearch__search", "description": "Search",
            "input_schema": {"type": "object"},
        }],
    )
    captured = aui._capture_or_load_source_identity(**kwargs)
    assert len(captured["campaign"]["expected_cells"]) == 4

    changed_surface = dict(kwargs)
    changed_surface["mcp_tool_surface"] = [{
        "name": "mcp__justsearch__search", "description": "Search",
        "input_schema": {"type": "object", "required": ["q"]},
    }]
    with pytest.raises(ValueError, match="mcp_tool_surface"):
        aui._capture_or_load_source_identity(**changed_surface)

    (corpus / "doc.txt").write_text("changed corpus", encoding="utf-8")
    with pytest.raises(ValueError, match="corpus"):
        aui._capture_or_load_source_identity(**kwargs)

    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    queries.write_text('[{"query":"changed","answer":"a"}]', encoding="utf-8")
    with pytest.raises(ValueError, match="queries"):
        aui._capture_or_load_source_identity(**kwargs)


def test_source_identity_resume_rejects_changed_exposure_identity(tmp_path, monkeypatch):
    """tempdoc 725 increment 2: exposure_config/mcp_initialize_identity are part
    of the persisted source-identity sidecar (the generic per-key mismatch loop
    in `_capture_or_load_source_identity` already covers any new key added to
    `stable_identity` -- this pins that a config/instructions drift across a
    resume fails closed, same bar as mcp_tool_surface above)."""
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64, "untracked_sha256": "0" * 64,
        "untracked_count": 0, "dirty": False,
    })
    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
        exposure_config={"enable_tool_search": "true", "always_load": False,
                          "exposure_mode": "deferred"},
        mcp_initialize_identity={"instructions": "search the corpus",
                                  "instructions_sha256": "d" * 64,
                                  "server_version": "1.0.0", "protocol_version": "2025-06-18"},
    )
    aui._capture_or_load_source_identity(**kwargs)

    changed_exposure = dict(kwargs)
    changed_exposure["exposure_config"] = {
        "enable_tool_search": "false", "always_load": True, "exposure_mode": "eager",
    }
    with pytest.raises(ValueError, match="exposure_config"):
        aui._capture_or_load_source_identity(**changed_exposure)

    changed_initialize = dict(kwargs)
    changed_initialize["mcp_initialize_identity"] = dict(
        kwargs["mcp_initialize_identity"], server_version="2.0.0")
    with pytest.raises(ValueError, match="mcp_initialize_identity"):
        aui._capture_or_load_source_identity(**changed_initialize)


def test_source_identity_captures_and_rechecks_corpus_certification(tmp_path, monkeypatch):
    from jseval.corpus_certify import SCIENTIFIC_GATES
    from jseval.corpus_identity import corpus_signature
    from tests.test_corpus_inject import _complete_certificate, _gate_evidence

    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    signature = corpus_signature(corpus, [corpus / "doc.txt"])
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    query_gold_sha256 = hashlib.sha256(queries.read_bytes()).hexdigest()
    gate_rows = {
        gate: _gate_evidence(
            gate, dataset="fixture", signature=signature,
            query_gold_sha256=query_gold_sha256, query_count=1,
        )
        for gate in SCIENTIFIC_GATES
    }
    certification = tmp_path / "certification.json"
    certification.write_text(json.dumps(_complete_certificate(
        "fixture-member", "fixture", signature, gate_rows, query_count=1,
        query_gold_sha256=query_gold_sha256,
    )), encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64,
        "untracked_sha256": "0" * 64,
        "untracked_count": 0,
        "dirty": False,
    })
    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature=signature, search_config_cohort_key="search-1",
        corpus_certification=str(certification),
        queries_path=str(queries), conditions=("A", "B"), seeds=1,
    )
    first = aui._capture_or_load_source_identity(**kwargs)
    assert first["corpus_certification"]["fully_certified"] is True

    changed = json.loads(certification.read_text(encoding="utf-8"))
    changed["datasets"]["1000"]["verbose"]["scientific_gates"][
        "closed_book"
    ]["threshold"] = {"maximum": 0.01}
    certification.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="corpus_certification"):
        aui._capture_or_load_source_identity(**kwargs)


# --- corpus-root identity axis (tempdoc 624 confirmatory pre-registration,
#     2026-07-17: corpus identity/staging decoupling) ------------------------
#
# A claim-grade run stages a leak-safe corpus SUBDIR (queries.json structurally
# absent) but must certify against the dataset ROOT's signature (corpus.jsonl +
# qrels). `corpus_root` makes identity resolve from the root while `corpus_dir`
# stays the agent-facing staged subdir.


def _dataset_root(base: Path) -> Path:
    """A minimal dataset ROOT (corpus.jsonl + qrels/test.tsv) — dataset-dir mode
    signable by corpus_signature. BEIR `_id` keys so the corpus-dir derivation check
    (`_verify_corpus_dir_derivation`) can re-materialize each doc."""
    root = base / "dataset-root"
    (root / "qrels").mkdir(parents=True)
    (root / "corpus.jsonl").write_text(
        '{"_id":"d1","title":"","text":"body one"}\n'
        '{"_id":"d2","title":"","text":"body two"}\n', encoding="utf-8")
    (root / "qrels" / "test.tsv").write_text("q1\t0\td1\t1\n", encoding="utf-8")
    return root


def _staged_child(root: Path) -> Path:
    """The leak-safe staged subdir: an immediate child of `root` holding exploded
    text only (no corpus.jsonl/qrels — so it is NOT itself a dataset root).

    A FAITHFUL derivation of `root/corpus.jsonl` — materialized through the same
    `materialize.materialize` production uses (so the sentinel + exact per-doc bytes
    match), which the root-mode derivation check now enforces."""
    from jseval import materialize as mat_mod

    staged = root / "corpus-dir"
    docs = [
        json.loads(line)
        for line in (root / "corpus.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    mat_mod.materialize(iter(docs), staged, skip_existing=False)
    return staged


def _pin_git(monkeypatch):
    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64, "untracked_sha256": "0" * 64,
        "untracked_count": 0, "dirty": False,
    })


def test_source_identity_root_mode_signs_root_not_staged_subdir(tmp_path, monkeypatch):
    from jseval.corpus_identity import corpus_signature

    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    root_sig = corpus_signature(root)  # dataset-dir mode
    staged_files_sig = corpus_signature(
        staged, sorted(staged.rglob("*"), key=lambda p: p.relative_to(staged).as_posix()))
    assert root_sig and staged_files_sig and root_sig != staged_files_sig
    _pin_git(monkeypatch)

    identity = aui._capture_or_load_source_identity(
        log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
        corpus_dataset="fixture", declared_corpus_signature=root_sig,
        search_config_cohort_key="search-1",
    )

    corpus = identity["corpus"]
    # Identity is the ROOT signature, not the staged subdir's files hash.
    assert corpus["signature"] == root_sig
    assert corpus["signature"] != staged_files_sig
    assert corpus["signature_matches"] is True
    assert corpus["corpus_root"] == str(root.resolve())
    # The raw-text axis survives as an audit-only attestation.
    assert corpus["corpus_dir_files_signature"] == staged_files_sig


def test_source_identity_root_mode_declared_signature_checked_against_root(tmp_path, monkeypatch):
    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    _pin_git(monkeypatch)
    # A declared 64-char signature that disagrees with the ROOT signature fails
    # closed (the declared check now compares against the canonical/certified root).
    with pytest.raises(ValueError, match="declared corpus signature"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
            corpus_dataset="fixture", declared_corpus_signature="e" * 64,
            search_config_cohort_key="search-1",
        )


def test_source_identity_root_mode_fails_closed_on_non_dataset_root(tmp_path, monkeypatch):
    # A "root" without corpus.jsonl/qrels is a config error — NO files-mode
    # fallback on the root (unlike the declared/staged path, which does fall back).
    root = tmp_path / "not-a-root"
    (root).mkdir()
    (root / "loose.txt").write_text("x", encoding="utf-8")
    staged = root / "corpus-dir"
    staged.mkdir()
    (staged / "d1.txt").write_text("y", encoding="utf-8")
    _pin_git(monkeypatch)
    with pytest.raises(ValueError, match="not a dataset root"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )


def test_source_identity_root_mode_requires_corpus_dir_child_of_root(tmp_path, monkeypatch):
    # The corpus-dir must be the root's OWN exploded subdir. Pointing it at a
    # DIFFERENT corpus's staged text (corpus A root + corpus B staged) fails closed.
    root_a = _dataset_root(tmp_path / "a")
    root_b = _dataset_root(tmp_path / "b")
    staged_b = _staged_child(root_b)  # child of B, not A
    _pin_git(monkeypatch)
    with pytest.raises(ValueError, match="immediate child"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged_b), corpus_root=str(root_a),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )
    # A grandchild (not an IMMEDIATE child) is also rejected.
    grandchild = staged_b / "nested"
    grandchild.mkdir()
    (grandchild / "d.txt").write_text("z", encoding="utf-8")
    with pytest.raises(ValueError, match="immediate child"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs2"), corpus_dir=str(grandchild), corpus_root=str(root_b),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )


def test_source_identity_root_mode_attaches_certification_that_declared_mode_rejects(
        tmp_path, monkeypatch):
    """The load-bearing decoupling: a cert signing the dataset-ROOT signature
    attaches in root mode on a LEAK-SAFE staged subdir — and the same cert on the
    same staged subdir is REJECTED in declared mode (the 624 §M.7a impossibility
    this axis removes: the staged subdir's files hash never equals the root sig)."""
    from jseval.corpus_certify import SCIENTIFIC_GATES
    from jseval.corpus_identity import corpus_signature
    from tests.test_corpus_inject import _complete_certificate, _gate_evidence

    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    root_sig = corpus_signature(root)
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    query_gold_sha256 = hashlib.sha256(queries.read_bytes()).hexdigest()
    gate_rows = {
        gate: _gate_evidence(
            gate, dataset="fixture", signature=root_sig,
            query_gold_sha256=query_gold_sha256, query_count=1,
        )
        for gate in SCIENTIFIC_GATES
    }
    certification = tmp_path / "certification.json"
    certification.write_text(json.dumps(_complete_certificate(
        "fixture-member", "fixture", root_sig, gate_rows, query_count=1,
        query_gold_sha256=query_gold_sha256,
    )), encoding="utf-8")
    _pin_git(monkeypatch)

    kwargs = dict(
        corpus_dir=str(staged), corpus_dataset="fixture",
        declared_corpus_signature=root_sig, search_config_cohort_key="search-1",
        corpus_certification=str(certification),
        queries_path=str(queries), conditions=("A", "B"), seeds=1,
    )
    identity = aui._capture_or_load_source_identity(
        log_dir=str(tmp_path / "logs-root"), corpus_root=str(root), **kwargs)
    snapshot = identity["corpus_certification"]
    assert snapshot["fully_certified"] is True
    assert snapshot["corpus_signature"] == root_sig
    assert snapshot["member"] == "fixture-member"
    assert snapshot["size"] == 1000
    assert snapshot["query_variant"] == "verbose"

    # Declared mode (no corpus_root) signs the staged subdir's files instead, whose
    # hash != root_sig, so the SAME cert can never attach — the pre-decoupling wall.
    with pytest.raises(ValueError, match="corpus_certification|signature"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs-declared"), corpus_root=None, **kwargs)


# --- corpus-dir derivation check (tempdoc 624 identity hardening) --------------------
#
# Root mode signs corpus.jsonl but only ATTESTS the exploded corpus-dir the agents read.
# `_verify_corpus_dir_derivation` fails CLOSED unless the corpus-dir is the derivation of
# corpus.jsonl (exact file set + deterministically-sampled content).


def _faithful_dataset(base: Path, n_docs: int) -> tuple[Path, Path]:
    """A dataset ROOT + its FAITHFULLY-materialized corpus-dir (n_docs docs).

    corpus.jsonl is the BEIR source; corpus-dir is produced through the same
    `materialize.materialize` production uses, so it is a genuine derivation (sentinel
    + exact per-doc bytes) — the intact baseline the check must accept."""
    from jseval import materialize as mat_mod

    root = base / "ds"
    (root / "qrels").mkdir(parents=True)
    docs = [{"_id": f"d{i}", "title": "", "text": f"body of document number {i}"}
            for i in range(n_docs)]
    with (root / "corpus.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    (root / "qrels" / "test.tsv").write_text("q1\t0\td0\t1\n", encoding="utf-8")
    staged = root / "corpus-dir"
    mat_mod.materialize(iter(docs), staged, skip_existing=False)
    return root, staged


def _sampled_doc_ids(base: Path, n_docs: int, sample_n: int) -> list[str]:
    """Reproduce the check's deterministic sample (seed = corpus signature) so a test
    can target a doc that IS vs is NOT in the sample. `_faithful_dataset` content is
    deterministic in `n_docs`, so this signature equals every `_run`'s signature."""
    from jseval.corpus_identity import corpus_signature

    root, _ = _faithful_dataset(base, n_docs)
    sig = corpus_signature(root)
    rng = random.Random(int(sig[:16], 16))
    idx = rng.sample(range(n_docs), min(sample_n, n_docs))
    return [f"d{i}" for i in idx]


def test_derivation_check_passes_on_faithful_corpus_dir(tmp_path):
    root, staged = _faithful_dataset(tmp_path, 8)
    from jseval.corpus_identity import corpus_signature
    staged_files = sorted(
        (p for p in staged.rglob("*") if p.is_file()),
        key=lambda p: p.relative_to(staged).as_posix())
    # No raise == intact derivation accepted.
    aui._verify_corpus_dir_derivation(
        corpus_root=root, staged_dir=staged, staged_files=staged_files,
        signature=corpus_signature(root))


def test_derivation_check_fails_closed_on_extra_file(tmp_path, monkeypatch):
    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    (staged / "d3.txt").write_text("body three (not in corpus.jsonl)", encoding="utf-8")
    _pin_git(monkeypatch)
    with pytest.raises(ValueError, match="file-set mismatch"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )


def test_derivation_check_fails_closed_on_missing_file(tmp_path, monkeypatch):
    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    (staged / "d2.txt").unlink()  # a doc from corpus.jsonl is absent from the explosion
    _pin_git(monkeypatch)
    with pytest.raises(ValueError, match="file-set mismatch"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )


def test_derivation_check_fails_closed_on_content_divergence(tmp_path, monkeypatch):
    # 2-doc corpus → sample covers both docs, so any content edit is caught and named.
    root = _dataset_root(tmp_path)
    staged = _staged_child(root)
    (staged / "d1.txt").write_text("SILENTLY DIVERGENT TEXT", encoding="utf-8")
    _pin_git(monkeypatch)
    with pytest.raises(ValueError, match=r"derivation mismatch.*'d1'"):
        aui._capture_or_load_source_identity(
            log_dir=str(tmp_path / "logs"), corpus_dir=str(staged), corpus_root=str(root),
            corpus_dataset="fixture", declared_corpus_signature="",
            search_config_cohort_key="search-1",
        )


def test_derivation_check_sample_is_deterministic(tmp_path):
    # 40-doc corpus, sample_n=8 → a strict subset. Corrupting a SAMPLED doc is caught and
    # names the SAME doc on every call; corrupting a NON-sampled doc's content passes the
    # file-set check and is not sampled — proving the sample is deterministic AND a subset.
    from jseval.corpus_identity import corpus_signature

    n, k = 40, 8
    sampled = set(_sampled_doc_ids(tmp_path / "a", n, k))
    assert 0 < len(sampled) < n  # a genuine subset

    def _run(base: Path, corrupt_id: str):
        root, staged = _faithful_dataset(base, n)
        (staged / f"{corrupt_id}.txt").write_text("mutated", encoding="utf-8")
        staged_files = sorted(
            (p for p in staged.rglob("*") if p.is_file()),
            key=lambda p: p.relative_to(staged).as_posix())
        return lambda: aui._verify_corpus_dir_derivation(
            corpus_root=root, staged_dir=staged, staged_files=staged_files,
            signature=corpus_signature(root), sample_n=k)

    in_sample = sorted(sampled)[0]
    not_in_sample = next(f"d{i}" for i in range(n) if f"d{i}" not in sampled)

    # Deterministic: two independent invocations flag the SAME sampled doc.
    msgs = []
    for base in ("run1", "run2"):
        with pytest.raises(ValueError) as exc:
            _run(tmp_path / base, in_sample)()
        msgs.append(str(exc.value))
    assert msgs[0] == msgs[1]
    assert repr(in_sample) in msgs[0]

    # A non-sampled content divergence is (deterministically) NOT sampled → no raise.
    _run(tmp_path / "unsampled", not_in_sample)()


@pytest.mark.skipif(
    not os.environ.get("JSEVAL_PERF_TESTS"),
    reason="perf/scale check — set JSEVAL_PERF_TESTS=1 to run (kept out of the default suite)")
@pytest.mark.parametrize("n_docs", [1000, 10000])
def test_derivation_check_scale_under_budget(tmp_path, n_docs):
    from jseval.corpus_identity import corpus_signature

    root, staged = _faithful_dataset(tmp_path, n_docs)
    staged_files = sorted(
        (p for p in staged.rglob("*") if p.is_file()),
        key=lambda p: p.relative_to(staged).as_posix())
    sig = corpus_signature(root)
    start = time.perf_counter()
    aui._verify_corpus_dir_derivation(
        corpus_root=root, staged_dir=staged, staged_files=staged_files, signature=sig)
    elapsed = time.perf_counter() - start
    print(f"\n[derivation-check] {n_docs} docs: {elapsed:.3f}s")
    assert elapsed < 30.0, f"derivation check took {elapsed:.1f}s at {n_docs} docs (budget 30s)"


def test_source_identity_declared_mode_sidecar_has_no_root_keys(tmp_path, monkeypatch):
    """Regression: without corpus_root the persisted corpus block is byte-identical
    to pre-change (no corpus_root / corpus_dir_files_signature keys leak in)."""
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    _pin_git(monkeypatch)
    identity = aui._capture_or_load_source_identity(
        log_dir=str(tmp_path / "logs"), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
    )
    assert set(identity["corpus"]) == {
        "dataset", "declared_signature", "signature", "signature_matches",
    }


def test_source_identity_resume_recomputes_safe_environment(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64, "untracked_sha256": "0" * 64,
        "untracked_count": 0, "dirty": False,
    })
    import jseval.env_fingerprint as env
    monkeypatch.setattr(env, "safe_environment_identity", lambda: {
        "platform": {"system": "one"}, "gpu": {"available": False},
    })
    kwargs = dict(
        log_dir=str(tmp_path / "logs"), corpus_dir=str(corpus),
        corpus_dataset="fixture", declared_corpus_signature="",
        search_config_cohort_key="search", queries_path=str(queries),
        conditions=("A", "B"), seeds=1, mcp_tool_surface=[],
    )
    aui._capture_or_load_source_identity(**kwargs)
    monkeypatch.setattr(env, "safe_environment_identity", lambda: {
        "platform": {"system": "two"}, "gpu": {"available": False},
    })
    with pytest.raises(ValueError, match="environment"):
        aui._capture_or_load_source_identity(**kwargs)


def test_record_cell_result_error_sets_error_and_stops():
    state = _state()
    got = {
        "attempts": {"t1": {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [],
        "rmsg": _rmsg(is_error=True, result="budget exceeded"),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    # Credibility bar (tempdoc 624 sec M.8 item 2, preserved under v2): tool
    # calls are stashed UNCONDITIONALLY, before the error short-circuit.
    assert state.metadata["tool_calls"] == [
        {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
    ]
    assert "budget exceeded" in state.metadata["error"]
    # tempdoc 736 D12 (issue 12): cost_usd/num_turns ARE on the ResultMessage
    # regardless of is_error, so they are now populated BEFORE the short-circuit
    # (this assertion used to be the inverse -- "cost_usd not in state.metadata" --
    # which was exactly the defect issue 12 fixes: a recoverable value dropped
    # only because of the early-return, not because it was unavailable).
    assert state.metadata["cost_usd"] == 0.02
    assert state.metadata["num_turns"] == 1


def test_record_cell_no_result_message_sets_error():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": None, "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert "no ResultMessage" in state.metadata["error"]
    # tempdoc 736 D12 (issue 12, absent subset): genuinely unknowable here (no
    # ResultMessage ever arrived) -- an honest null, never a fabricated zero.
    assert state.metadata.get("cost_usd") is None
    assert state.metadata.get("num_turns") is None


# --- _record_cell: num_turns/cost_usd preserved on an errored cell (tempdoc 736
# D12, issue 12 -- the two increment-A4 regression tests named in the plan). ---

def test_record_cell_preserves_cost_and_turns_on_errored_result_message():
    """Recoverable subset: a ResultMessage arrived with is_error=True -- num_turns
    and total_cost_usd are fields of every ResultMessage (SDK-confirmed), so they
    must be projected even though the cell short-circuits on the error."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(is_error=True, num_turns=7, total_cost_usd=0.4321, result="max turns"),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["num_turns"] == 7
    assert state.metadata["cost_usd"] == 0.4321
    assert "error" in state.metadata


def test_record_cell_no_result_message_leaves_cost_and_turns_null_not_zero():
    """Absent subset (sibling of test_record_cell_no_result_message_sets_error,
    explicit on the tri-state honesty distinction): no ResultMessage ever arrived,
    so cost_usd/num_turns are None -- distinct from a measured 0."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": None, "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata.get("cost_usd") is None
    assert state.metadata.get("num_turns") is None
    assert state.metadata.get("cost_usd") != 0
    assert state.metadata.get("num_turns") != 0


# --- _record_cell: offered MCP tool-surface tri-state (tempdoc 675 finding 2
# -- replaces the CLI init-event parse with the SDK's own `get_mcp_status()`
# result, already captured into `got["mcp_servers"]`/`got["justsearch_tools"]`
# by the solver before `_record_cell` runs). ---

def test_capture_canonical_mcp_surface_keeps_full_tools_list(tmp_path, monkeypatch):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({
        "mcpServers": {"justsearch": {"type": "http", "url": "http://localhost/mcp"}},
    }), encoding="utf-8")
    payload = {"jsonrpc": "2.0", "id": "x", "result": {"tools": [{
        "name": "search_query", "description": "Search documents",
        "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}}},
        "outputSchema": {"type": "object", "required": ["hits"]},
        "annotations": {"readOnlyHint": True},
    }]}}

    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *_):
            return False
        def read(self):
            return json.dumps(payload).encode("utf-8")

    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: Response())
    assert aui._capture_canonical_mcp_surface(str(config)) == [{
        "name": "mcp__justsearch__search_query",
        "description": "Search documents",
        "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}}},
        "outputSchema": {"type": "object", "required": ["hits"]},
        "annotations": {"readOnlyHint": True},
    }]


# --- _capture_mcp_initialize_identity (tempdoc 725 increment 2): the missing
# `initialize` capture -- nothing captured this response anywhere before. ---

def _mcp_config(tmp_path):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({
        "mcpServers": {"justsearch": {"type": "http", "url": "http://localhost/mcp"}},
    }), encoding="utf-8")
    return config


class _JsonResponse:
    def __init__(self, payload):
        self._payload = payload
    def __enter__(self):
        return self
    def __exit__(self, *_):
        return False
    def read(self):
        return json.dumps(self._payload).encode("utf-8")


def test_capture_mcp_initialize_identity_parses_instructions_and_hashes_them(tmp_path, monkeypatch):
    config = _mcp_config(tmp_path)
    payload = {"jsonrpc": "2.0", "id": "x", "result": {
        "instructions": "search the corpus", "protocolVersion": "2025-06-18",
        "serverInfo": {"name": "justsearch", "version": "1.2.3"},
    }}
    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: _JsonResponse(payload))

    identity = aui._capture_mcp_initialize_identity(str(config))

    assert identity["instructions"] == "search the corpus"
    assert identity["instructions_sha256"] == hashlib.sha256(
        b"search the corpus").hexdigest()
    assert identity["server_version"] == "1.2.3"
    assert identity["protocol_version"] == "2025-06-18"


def test_capture_mcp_initialize_identity_null_instructions_hash_when_absent(tmp_path, monkeypatch):
    """`instructions` is optional per the MCP spec -- absence is NOT a capture
    failure and must hash to `None`, not crash or fabricate a sentinel."""
    config = _mcp_config(tmp_path)
    payload = {"jsonrpc": "2.0", "id": "x", "result": {
        "protocolVersion": "2025-06-18", "serverInfo": {"version": "1.0.0"},
    }}
    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: _JsonResponse(payload))

    identity = aui._capture_mcp_initialize_identity(str(config))

    assert identity["instructions"] is None
    assert identity["instructions_sha256"] is None
    assert identity["server_version"] == "1.0.0"


def test_capture_mcp_initialize_identity_fails_closed_on_rpc_error(tmp_path, monkeypatch):
    """Fail-closed like _capture_canonical_mcp_surface: a JSON-RPC error RAISES,
    it never returns a silently-empty identity block that would read as
    healthy."""
    config = _mcp_config(tmp_path)
    payload = {"jsonrpc": "2.0", "id": "x", "error": {"code": -32000, "message": "boom"}}
    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: _JsonResponse(payload))

    with pytest.raises(RuntimeError, match="MCP initialize failed"):
        aui._capture_mcp_initialize_identity(str(config))


def test_capture_mcp_initialize_identity_fails_closed_on_malformed_result(tmp_path, monkeypatch):
    config = _mcp_config(tmp_path)
    payload = {"jsonrpc": "2.0", "id": "x", "result": "not-an-object"}
    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: _JsonResponse(payload))

    with pytest.raises(ValueError, match="malformed result"):
        aui._capture_mcp_initialize_identity(str(config))


# --- _derive_exposure_mode / _capture_exposure_config (tempdoc 725 derisk:
# config-only derivation, NEVER inferred from the SDK's init/status message). --

@pytest.mark.parametrize(
    ("enable_tool_search", "always_load", "expected"),
    [
        (None, True, "eager"),               # always_load wins regardless of env
        ("true", True, "eager"),
        ("false", False, "eager"),           # enable_tool_search=="false" alone -> eager
        ("false", None, "eager"),
        (None, None, "deferred"),            # no always_load, unset env -> deferred
        ("", False, "deferred"),
        ("true", False, "deferred"),
        ("auto", None, "unknown"),           # anything else -> unknown
        ("true", None, "deferred"),
    ],
)
def test_derive_exposure_mode_matches_config_only_truth_table(enable_tool_search, always_load, expected):
    assert aui._derive_exposure_mode(
        enable_tool_search=enable_tool_search, always_load=always_load) == expected


def test_capture_exposure_config_reads_env_and_always_load_from_mcp_config(tmp_path, monkeypatch):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({
        "mcpServers": {"justsearch": {
            "type": "http", "url": "http://localhost/mcp", "alwaysLoad": True,
        }},
    }), encoding="utf-8")
    monkeypatch.delenv("ENABLE_TOOL_SEARCH", raising=False)

    result = aui._capture_exposure_config(str(config))

    assert result == {
        "enable_tool_search": None, "always_load": True, "exposure_mode": "eager",
    }


def test_capture_exposure_config_none_without_mcp_config():
    """No with-tool arm at all (condition-A-only run) -> None, mirrors the
    empty mcp_tool_surface convention."""
    assert aui._capture_exposure_config(None) is None


def test_capture_exposure_config_explicit_params_override_env_and_config(tmp_path, monkeypatch):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({
        "mcpServers": {"justsearch": {"type": "http", "url": "http://localhost/mcp"}},
    }), encoding="utf-8")
    monkeypatch.setenv("ENABLE_TOOL_SEARCH", "true")

    result = aui._capture_exposure_config(
        str(config), enable_tool_search="false", always_load=False)

    assert result == {
        "enable_tool_search": "false", "always_load": False, "exposure_mode": "eager",
    }


def test_record_cell_condition_a_exempt_from_surface_assertion():
    """Condition A never carries a real mcp_config -- and even if a caller
    passed one through, A must not be held to the with-tool surface assertion."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), "/mcp.json")

    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata


def test_record_cell_condition_b_unverified_when_status_unavailable():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert state.metadata["mcp_surface_unverified"] is True
    assert "error" not in state.metadata


def test_record_cell_condition_b_healthy_surface_no_error():
    state = _state()
    servers = [{"name": "justsearch", "status": "connected",
                "tools": [{"name": "search_query"}]}]
    justsearch_tools = ["mcp__justsearch__search_query"]
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": servers, "justsearch_tools": justsearch_tools,
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert state.metadata["mcp_tools_offered"] == len(justsearch_tools)
    assert state.metadata["mcp_tool_names_offered"] == justsearch_tools
    from jseval.agent_manifest import mcp_tool_surface_hash
    expected = mcp_tool_surface_hash([{
        "name": justsearch_tools[0], "description": None, "input_schema": None,
    }])
    assert state.metadata["observed_mcp_tool_surface_hash"] == expected
    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata


def test_record_cell_surface_hash_includes_schema_and_rejects_declared_mismatch():
    """Same-name tools with different schemas are different source identities."""
    def capture(schema, declared=None):
        state = _state()
        got = {
            "attempts": {}, "results": {}, "texts": [], "rmsg": _rmsg(),
            "mcp_servers": [{
                "name": "justsearch", "status": "connected",
                "tools": [{
                    "name": "search_query", "description": "Search",
                    "inputSchema": schema,
                }],
            }],
            "justsearch_tools": ["mcp__justsearch__search_query"],
        }
        aui._record_cell(
            state, got, "B", build_disallowed_tools("B"), "/mcp.json", declared)
        return state

    string_surface = capture({"type": "object", "properties": {"q": {"type": "string"}}})
    integer_surface = capture({"type": "object", "properties": {"q": {"type": "integer"}}})
    assert (string_surface.metadata["observed_mcp_tool_surface_hash"]
            != integer_surface.metadata["observed_mcp_tool_surface_hash"])

    mismatch = capture({"type": "object"}, declared="0" * 64)
    assert "declared MCP tool-surface hash disagrees" in mismatch.metadata["error"]


def test_record_cell_condition_b_connected_but_no_tools_errors_and_deferred():
    """Server connected (unlike a genuinely dead config) but no mcp__justsearch
    tool was offered -- the ToolSearch-deferred surfacing shape (tempdoc 624
    Step 0 item 4, preserved under v2)."""
    state = _state()
    servers = [{"name": "justsearch", "status": "connected"}]
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": servers, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert "expected MCP tool surface not offered" in state.metadata["error"]
    assert state.metadata["mcp_tools_deferred"] is True


def test_record_cell_condition_b_dead_config_not_misread_as_deferred():
    """The server never connected at all -- must NOT be misread as 'deferred'."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": [], "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert "expected MCP tool surface not offered" in state.metadata["error"]
    assert state.metadata["mcp_tools_deferred"] is False


def test_record_cell_with_tool_condition_without_mcp_config_is_exempt():
    """B/C with mcp_config=None (no real config in play, e.g. a negative
    control) must not be held to the offered-surface assertion."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": [], "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), None)

    assert "error" not in state.metadata


# --- _record_cell: the 2026-07-14 exposure A/B smoke regression (tempdoc 725)
# -- the with-tool surface assertion was NOT condition-gated, so a condition-A
# (baseline, no MCP servers) cell that received the campaign-level declared
# surface got compared against it and errored at record time. ---

def test_record_cell_condition_a_live_arm_shape_exempt_from_surface_assertion():
    """The EXACT live-run shape (tempdoc 725, 2026-07-14 exposure A/B smoke): a
    baseline condition-A cell whose captured `mcp_servers` is `[]` (an empty
    LIST, not None -- the SDK's `get_mcp_status()` legitimately returns this
    for a session `_one_attempt` gave zero MCP servers to, since condition A
    is not in `_WITH_TOOL`), while the campaign-level `declared_mcp_tool_surface`
    (6 canonical justsearch tools, parsed once per task) is threaded through
    unconditionally to every cell including A.

    This is the exact shape the prior unconditional assertion missed: the
    pre-existing regression test (`test_record_cell_condition_a_exempt_from_surface_assertion`
    below) seeded `mcp_servers: None` and passed NO declared surface -- a shape
    that never occurs in production, so it stayed green while the live matrix
    voided 17/20 A-cells at record time ($4.91 burned), arm A error_rate 1.0,
    against the declared surface. Classic `unreachable-seed-green`: a seed that
    doesn't mirror the real producer's data flow gives confident-but-wrong
    green."""
    state = _state()
    declared = [
        {"name": f"mcp__justsearch__justsearch_{n}", "description": "d",
         "input_schema": {"type": "object"}}
        for n in ("answer", "browse", "ingest", "runtime_manifest", "search", "status")
    ]
    from jseval.agent_manifest import mcp_tool_surface_hash
    declared_hash = mcp_tool_surface_hash(declared)
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": [], "justsearch_tools": [],
    }

    aui._record_cell(
        state, got, "A", build_disallowed_tools("A"), "/mcp.json",
        declared_hash, declared,
    )

    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata
    assert state.metadata["mcp_tool_names_offered"] == []


def test_record_cell_condition_a_none_status_with_declared_surface_is_exempt():
    """Sibling of `test_record_cell_condition_a_exempt_from_surface_assertion`:
    the `servers is None` (status genuinely unavailable) A-cell shape, but WITH
    a declared surface present -- production threads the declared surface to
    every cell regardless of whether `get_mcp_status()` succeeded."""
    state = _state()
    declared = [{"name": "mcp__justsearch__search", "description": "d",
                 "input_schema": {"type": "object"}}]
    from jseval.agent_manifest import mcp_tool_surface_hash
    declared_hash = mcp_tool_surface_hash(declared)
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(
        state, got, "A", build_disallowed_tools("A"), "/mcp.json",
        declared_hash, declared,
    )

    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata


def test_record_cell_condition_b_names_mismatch_still_errors():
    """Guard against over-correction: the new condition-A exemption gate must
    NOT weaken the assertion for with-tool cells. A B cell with a declared
    surface whose observed offered names genuinely disagree must still error
    exactly as before (tempdoc 725 fix 1 -- gate, don't remove)."""
    state = _state()
    declared = [
        {"name": "mcp__justsearch__search", "description": "d", "input_schema": {"type": "object"}},
        {"name": "mcp__justsearch__answer", "description": "d", "input_schema": {"type": "object"}},
    ]
    servers = [{"name": "justsearch", "status": "connected",
                "tools": [{"name": "search"}]}]
    got = {
        "attempts": {}, "results": {}, "texts": [], "rmsg": _rmsg(),
        "mcp_servers": servers, "justsearch_tools": ["mcp__justsearch__search"],
    }

    # declared_mcp_tool_surface_hash intentionally None so the (separately
    # tested) hash-mismatch assertion can't set `error` first and mask the
    # names-mismatch assertion under test here.
    aui._record_cell(
        state, got, "B", build_disallowed_tools("B"), "/mcp.json",
        None, declared,
    )

    assert "offered MCP tool names disagree" in state.metadata["error"]


# --- _mcp_surface: first-non-null-key tri-state semantics (tempdoc 725 fix 2
# -- the prior `status.get("servers") or status.get("mcp_servers") or
# status.get("mcpServers")` `or`-chain returned the LAST operand when all were
# falsy, so an empty list under a non-last key silently collapsed towards
# `None`, conflating known-empty with status-unavailable). ---

class _StubMcpStatusClient:
    """Minimal async stand-in for `ClaudeSDKClient` -- `_mcp_surface` only
    calls `get_mcp_status()` on the client it's given."""
    def __init__(self, status):
        self._status = status

    async def get_mcp_status(self):
        return self._status


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        ({"servers": []}, []),
        ({"mcpServers": []}, []),
        ({}, None),
        # Null-padded shapes: typed serializers commonly emit every alternative
        # key with null for the unused ones -- an explicit null is "absent",
        # never a match, so the populated (even empty) key still wins.
        ({"servers": None, "mcpServers": []}, []),
        ({"servers": None, "mcp_servers": None, "mcpServers": None}, None),
    ],
)
def test_mcp_surface_first_non_null_key_tri_state(status, expected):
    # with_tool=False isolates the single-probe parse (no retry): these cases assert the
    # tri-state key resolution, not the tempdoc 755 retry behaviour.
    servers, tools, surface, evidence = asyncio.run(
        aui._mcp_surface(_StubMcpStatusClient(status), with_tool=False))
    assert servers == expected
    assert tools == []
    assert surface == []
    assert evidence is None


# --- run_utility_eval: the corpus-dir isolation fix, cleanup-on-raise, the
# watched-roots safety gate, and the no-mcp gate skip. run_utility_eval now
# builds exactly ONE task for the whole matrix (tempdoc 675 single pool), so
# `eval_set` is called WITHOUT `max_tasks=1`. ---

def test_run_utility_eval_builds_one_task_with_staged_not_original_corpus_dir(tmp_path, monkeypatch):
    """Mocks `agent_utility_task` (the Inspect `@task` factory) and `eval_set`
    (the actual multi-hour agent runner) so this stays a fast unit test, while
    still exercising run_utility_eval's real staging + cleanup wiring."""
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    (dataset_dir / "queries.json").write_text(
        json.dumps([{"query": "q", "answer": "the secret answer"}]), encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    task_calls = []

    def fake_agent_utility_task(*, corpus_dir, conditions, **kwargs):
        task_calls.append({"corpus_dir": corpus_dir, "conditions": conditions})
        # capture the staged dir's parent listing NOW -- before run_utility_eval's
        # cleanup (which fires once the mocked eval_set below returns) removes it.
        task_calls[-1]["staged_parent_listing"] = sorted(os.listdir(Path(corpus_dir).parent))
        return object()  # eval_set is mocked too, so any placeholder Task works

    eval_set_calls = []
    monkeypatch.setattr(aui, "agent_utility_task", fake_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda tasks, **k: eval_set_calls.append((tasks, k)))

    original_corpus_dir = str(corpus_dir)
    aui.run_utility_eval(
        queries_path=str(queries_for_eval), corpus_dir=original_corpus_dir, mcp_config=None,
        conditions=("A", "C"), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
    )

    # ONE task built for the whole matrix (tempdoc 675) -- not one per condition.
    assert len(task_calls) == 1
    call = task_calls[0]
    assert call["conditions"] == ("A", "C")
    used_corpus_dir = call["corpus_dir"]
    assert used_corpus_dir != original_corpus_dir
    assert Path(used_corpus_dir).name == "corpus-dir"
    assert call["staged_parent_listing"] == ["corpus-dir"]  # no queries.json sibling

    # the staging directory is a temp artifact -- must be cleaned up after the run
    assert not Path(used_corpus_dir).parent.exists()

    # ONE task handed to eval_set, and no per-condition max_tasks=1 serialization.
    assert len(eval_set_calls) == 1
    tasks_arg, kwargs = eval_set_calls[0]
    assert len(tasks_arg) == 1
    assert "max_tasks" not in kwargs


def test_run_utility_eval_cleans_up_staged_dir_when_task_construction_raises(tmp_path, monkeypatch):
    """Regression test (independent-reviewer nit #1): `tasks = [...]` construction
    calls `agent_utility_task`, which does `json.load(open(queries_path))` and can
    raise on a bad/missing/corrupted queries file. Before the fix, only the
    subsequent `eval_set(...)` call was wrapped in the cleanup try/finally, so a
    task-construction failure leaked the staged temp directory. This forces the
    failure IN task construction (not in eval_set) and asserts the staged dir is
    still cleaned up."""
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    captured = {}
    real_stage = aui.stage_corpus_dir

    def spying_stage_corpus_dir(cdir, **kwargs):
        staged = real_stage(cdir, **kwargs)
        captured["staged_dir"] = staged
        return staged

    def raising_agent_utility_task(*, corpus_dir, **kwargs):
        raise ValueError("simulated bad queries.json during task construction")

    monkeypatch.setattr(aui, "stage_corpus_dir", spying_stage_corpus_dir)
    monkeypatch.setattr(aui, "agent_utility_task", raising_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with pytest.raises(ValueError, match="simulated bad queries.json"):
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir), mcp_config=None,
            conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        )

    assert "staged_dir" in captured, "stage_corpus_dir should have been invoked"
    # the staging directory is a temp artifact -- must be cleaned up even though
    # task construction (not eval_set) is what raised.
    assert not Path(captured["staged_dir"]).parent.exists()


# --- Watched-roots safety gate: run_utility_eval is the actual eval-executing path,
# so (unlike the optional `utility-calibrate` CLI) a stray/broader watched root must
# abort the run before any subprocess or eval_set work happens (tempdoc 624 As-built
# #7 follow-up). ---

def _mock_roots_client(MockClient, roots_paths):
    from unittest.mock import MagicMock

    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    resp = MagicMock()
    resp.json.return_value = {
        "roots": [{"collection": "default", "path": p, "fileCount": 3} for p in roots_paths]
    }
    resp.raise_for_status = MagicMock()
    mock_client.get.return_value = resp
    return mock_client


def test_run_utility_eval_raises_on_stray_watched_root(tmp_path, monkeypatch):
    """mcp_config given (a real search backend is in play) + a stray/broader watched
    root reported by the live backend -> run_utility_eval must raise StrayWatchedRootError
    and must NOT reach agent_utility_task / eval_set (no staging, no subprocess work)."""
    from unittest.mock import patch

    from jseval.utility_calibrate import StrayWatchedRootError

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    # "type":"http" is mandatory (McpConfigMissingTypeError) -- these tests exercise
    # the watched-roots gate specifically, so the config must pass the earlier
    # dead-config fail-fast check to reach it.
    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

    called = {"agent_utility_task": False, "eval_set": False}
    monkeypatch.setattr(aui, "agent_utility_task",
                         lambda **kw: called.__setitem__("agent_utility_task", True))
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda *a, **k: called.__setitem__("eval_set", True))

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        # a stray root broader than corpus_dir -- the corpus's own PARENT.
        _mock_roots_client(MockClient, [str(dataset_dir)])

        with pytest.raises(StrayWatchedRootError):
            aui.run_utility_eval(
                queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
                mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
                log_dir=str(tmp_path / "logs"),
            )

    assert called["agent_utility_task"] is False
    assert called["eval_set"] is False


def test_run_utility_eval_proceeds_when_roots_correctly_scoped(tmp_path, monkeypatch):
    """The mirror-positive: when the live backend's watched roots are exactly
    corpus_dir, the safety gate must NOT block the run."""
    from unittest.mock import patch

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    # "type":"http" is mandatory (McpConfigMissingTypeError) -- these tests exercise
    # the watched-roots gate specifically, so the config must pass the earlier
    # dead-config fail-fast check to reach it.
    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

    called = {"eval_set": False}
    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda *a, **k: called.__setitem__("eval_set", True))
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: [{
        "name": "mcp__justsearch__search", "description": "search",
        "input_schema": {"type": "object"},
    }])
    monkeypatch.setattr(aui, "_capture_mcp_initialize_identity", lambda _: {
        "instructions": None, "instructions_sha256": None,
        "server_version": "1.0", "protocol_version": "2025-06-18",
    })

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])

        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
            mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
            log_dir=str(tmp_path / "logs"),
        )

    assert called["eval_set"] is True


def test_run_utility_eval_rejects_mcp_schema_drift_during_campaign(tmp_path, monkeypatch):
    from unittest.mock import patch

    corpus_dir = tmp_path / "dataset" / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    config = tmp_path / "mcp.json"
    config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8",
    )
    surfaces = iter([
        [{"name": "mcp__justsearch__search", "inputSchema": {"type": "object"},
          "outputSchema": {"type": "string"}}],
        [{"name": "mcp__justsearch__search", "inputSchema": {"type": "object"},
          "outputSchema": {"type": "integer"}}],
    ])
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: next(surfaces))
    monkeypatch.setattr(aui, "_capture_mcp_initialize_identity", lambda _: {
        "instructions": None, "instructions_sha256": None,
        "server_version": "1.0", "protocol_version": "2025-06-18",
    })
    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with patch("jseval.utility_calibrate.httpx.Client") as client:
        _mock_roots_client(client, [str(corpus_dir)])
        with pytest.raises(RuntimeError, match="tools/list changed"):
            aui.run_utility_eval(
                queries_path=str(queries), corpus_dir=str(corpus_dir),
                mcp_config=str(config), conditions=("A", "B"), seeds=1,
                concurrency=1, log_dir=str(tmp_path / "logs"),
            )


def test_run_utility_eval_skips_gate_when_no_mcp_config(tmp_path, monkeypatch):
    """condition-A-only runs (mcp_config=None) never touch the search backend, so the
    gate must not attempt an HTTP call at all -- covered implicitly by the pre-existing
    staging test above (mcp_config=None there too), pinned explicitly here so a future
    change can't silently make mcp_config=None try to hit a backend anyway."""
    from unittest.mock import patch

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir), mcp_config=None,
            conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        )
        MockClient.assert_not_called()


def test_record_cell_preserves_partial_tool_calls_and_does_not_clobber_timeout_error():
    """F2 (tempdoc 675 review): a timed-out cell — `solve()` pre-set the timeout error and the
    SDK loop was cancelled so there is NO ResultMessage — must STILL record its partial
    `tool_calls`, and `_record_cell` must NOT overwrite the timeout error with 'no ResultMessage'.
    Regression for 'timed-out cells lose their partial evidence', the failure v2 exists to fix."""
    state = _state()
    state.metadata["error"] = "per-cell wall-clock budget exhausted"  # solve() set this on timeout
    capture = {
        "attempts": {"t1": {"tool": "Grep", "input": {"pattern": "x"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [], "rmsg": None, "mcp_servers": None, "justsearch_tools": [],
    }
    aui._record_cell(state, capture, "A", build_disallowed_tools("A"), None)
    assert [tc["tool"] for tc in state.metadata["tool_calls"]] == ["Grep"]  # partial evidence preserved
    assert state.metadata["error"] == "per-cell wall-clock budget exhausted"  # not clobbered


def test_agent_utility_task_rejects_a_float_identity_arg(tmp_path):
    """Regression guard (tempdoc 675 F0 follow-up): a float anywhere in
    `agent_utility_task`'s bound args must raise, not silently ship — Inspect's JSON
    recorder reads a persisted float back as Decimal, which breaks eval_set resume
    (the root cause of F0). Converts the prior prose-only docstring warning into an
    enforced check."""
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t1"}]), encoding="utf-8")
    with pytest.raises(AssertionError, match="float"):
        aui.agent_utility_task(
            conditions=("A",), queries_path=str(queries_path), corpus_dir=str(tmp_path),
            mcp_config=None, model="haiku", max_budget=0.5,  # float, not the required str
            corpus_dataset="ds", corpus_signature="sig",
        )


def test_agent_utility_task_happy_path_has_no_float_args(tmp_path):
    """Sibling to the raise-on-float test above: the real call shape (all current
    default/str/int/None/tuple args) must NOT trip the guard."""
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t1"}]), encoding="utf-8")
    t = aui.agent_utility_task(
        conditions=("A",), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", corpus_dataset="ds", corpus_signature="sig",
    )
    assert isinstance(t, Task)


def test_run_utility_eval_resumes_on_rerun_same_log_dir(tmp_path, monkeypatch):
    """F0 (tempdoc 675 review): re-invoking `run_utility_eval` with the same `log_dir` RESUMES
    (skips the completed sample) instead of raising Inspect's `PrerequisiteError`. Regression for
    the task-identity drift (a float `max_budget` read back as Decimal + a random staged-corpus
    path). Uses a trivial instant solver — no real agent/backend. This test FAILS pre-fix."""
    from inspect_ai.solver import solver as _solver_dec

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "d.txt").write_text("hello", encoding="utf-8")
    queries = tmp_path / "q.json"
    queries.write_text(
        json.dumps([{"query": "q", "answer": "a", "question_type": "t"}]), encoding="utf-8")
    log_dir = str(tmp_path / "logs")
    calls = {"n": 0}

    @_solver_dec
    def trivial(*a, **k):
        async def solve(state, generate):
            calls["n"] += 1
            state.output.completion = "a"
            state.metadata.update({"cost_usd": 0.0, "unique_tokens": 0, "num_turns": 1})
            return state
        return solve

    monkeypatch.setattr(aui, "claude_agent_solver", trivial)
    kw = dict(queries_path=str(queries), corpus_dir=str(corpus), mcp_config=None, model="haiku",
              conditions=("A",), seeds=1, concurrency=1, log_dir=log_dir, max_queries=1,
              corpus_dataset="d", corpus_signature="s")
    aui.run_utility_eval(**kw)
    assert calls["n"] == 1
    aui.run_utility_eval(**kw)  # RESUME: must not raise, must not re-run the completed cell
    assert calls["n"] == 1


def test_run_utility_eval_resumes_a_multi_sample_full_completion(tmp_path, monkeypatch):
    """Strengthens the single-sample resume test above to N=3 samples across 2 conditions
    (tempdoc 675 review follow-up): proves resume isn't a degenerate single-sample-only
    behavior — a full N-sample completion, re-invoked with IDENTICAL args, must not
    re-run ANY of the N cells. (A genuinely partial/interrupted-mid-run resume needs an
    actual process kill to be faithful — covered by a live, non-unit-test check per the
    675 tempdoc's §Unverified assumptions, not simulated here: eval_set's retry/
    fail-on-error semantics for an in-process raised exception are a different, untested
    code path and changing `max_queries` between invocations would change task identity,
    defeating resume entirely — neither is a safe basis for a deterministic unit test.)"""
    from inspect_ai.solver import solver as _solver_dec

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "d.txt").write_text("hello", encoding="utf-8")
    queries = tmp_path / "q.json"
    queries.write_text(json.dumps([
        {"query": f"q{i}", "answer": f"a{i}", "question_type": "t"} for i in range(3)
    ]), encoding="utf-8")
    log_dir = str(tmp_path / "logs")
    calls = {"n": 0}

    @_solver_dec
    def trivial(*a, **k):
        async def solve(state, generate):
            calls["n"] += 1
            state.output.completion = "a"
            state.metadata.update({"cost_usd": 0.0, "unique_tokens": 0, "num_turns": 1})
            return state
        return solve

    monkeypatch.setattr(aui, "claude_agent_solver", trivial)
    kw = dict(queries_path=str(queries), corpus_dir=str(corpus), mcp_config=None, model="haiku",
              conditions=("A", "C"), seeds=1, concurrency=2, log_dir=log_dir, max_queries=3,
              corpus_dataset="d", corpus_signature="s")
    aui.run_utility_eval(**kw)
    assert calls["n"] == 6  # 3 queries x 2 conditions, all completed
    aui.run_utility_eval(**kw)  # RESUME: none of the 6 completed cells re-run
    assert calls["n"] == 6


# --- --agent-env wiring (tempdoc 725 increment 4): a repeatable env-var overlay
# threaded from `jseval utility-run` all the way into the per-cell child Agent SDK
# session's `ClaudeAgentOptions.env`, and its effective `ENABLE_TOOL_SEARCH` value
# (agent_env's own entry if set, else the harness process env) feeding the
# exposure-config capture as an EXPLICIT input -- so the recorded exposure identity
# describes the child session's actual config, not merely this parent process. ---


def test_claude_agent_solver_threads_agent_env_into_claude_agent_options(tmp_path, monkeypatch):
    """The solver's `_one_attempt` must build `ClaudeAgentOptions(env=...)` from the
    decoded `agent_env_json` -- verified by swapping in a spy `ClaudeAgentOptions`
    stand-in and a minimal fake `ClaudeSDKClient` (no real SDK/model call)."""
    captured_options: dict = {}

    class _FakeOptions:
        def __init__(self, **kwargs):
            captured_options.update(kwargs)

    class _FakeClient:
        def __init__(self, options):
            self.options = options

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def query(self, prompt):
            return None

        async def receive_response(self):
            return
            yield  # pragma: no cover — makes this an empty async generator

        async def get_mcp_status(self):
            return None

    monkeypatch.setattr(aui, "ClaudeAgentOptions", _FakeOptions)
    monkeypatch.setattr(aui, "ClaudeSDKClient", _FakeClient)

    solve = aui.claude_agent_solver(
        str(tmp_path), None, "haiku", "0.50", 5, 10, "[]",
        agent_env_json=json.dumps({"ENABLE_TOOL_SEARCH": "false"}),
    )
    state = _state()
    state.metadata["condition"] = "A"

    import asyncio
    asyncio.run(solve(state, None))

    assert captured_options["env"] == {"ENABLE_TOOL_SEARCH": "false"}


def test_claude_agent_solver_default_agent_env_is_empty_dict_today_behavior(tmp_path, monkeypatch):
    """No `--agent-env` given -> `agent_env_json` defaults to `"{}"` -> `env={}` --
    byte-identical to omitting `env` entirely (the SDK's own `default_factory=dict`),
    so today's behavior is unchanged when the new option is unused."""
    captured_options: dict = {}

    class _FakeOptions:
        def __init__(self, **kwargs):
            captured_options.update(kwargs)

    class _FakeClient:
        def __init__(self, options):
            self.options = options

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def query(self, prompt):
            return None

        async def receive_response(self):
            return
            yield  # pragma: no cover

        async def get_mcp_status(self):
            return None

    monkeypatch.setattr(aui, "ClaudeAgentOptions", _FakeOptions)
    monkeypatch.setattr(aui, "ClaudeSDKClient", _FakeClient)

    solve = aui.claude_agent_solver(str(tmp_path), None, "haiku", "0.50", 5, 10, "[]")
    state = _state()
    state.metadata["condition"] = "A"

    import asyncio
    asyncio.run(solve(state, None))

    assert captured_options["env"] == {}


def test_agent_utility_task_threads_agent_env_json_into_solver(tmp_path, monkeypatch):
    """`agent_utility_task` must pass its `agent_env_json` arg through to the
    `claude_agent_solver` factory call (task-level identity -> solver-level closure)."""
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t1"}]), encoding="utf-8")

    captured_args = {}
    real_solver = aui.claude_agent_solver

    def spying_solver(*args, **kwargs):
        captured_args["args"] = args
        return real_solver(*args, **kwargs)

    monkeypatch.setattr(aui, "claude_agent_solver", spying_solver)

    aui.agent_utility_task(
        conditions=("A",), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", corpus_dataset="ds", corpus_signature="sig",
        agent_env_json=json.dumps({"ENABLE_TOOL_SEARCH": "false"}),
    )

    # agent_env_json is threaded through as a positional solver arg (its exact slot is
    # not load-bearing -- later per-cell args, e.g. timeout_s_by_condition_json, may follow).
    assert json.dumps({"ENABLE_TOOL_SEARCH": "false"}) in captured_args["args"]


def test_run_utility_eval_threads_agent_env_into_task_and_exposure_config(tmp_path, monkeypatch):
    """End-to-end (mocked backend) wiring check: `run_utility_eval(agent_env=...)` must
    (1) pass an `agent_env_json` matching `agent_env` into `agent_utility_task`, and
    (2) feed the EFFECTIVE `ENABLE_TOOL_SEARCH` (agent_env's own value, not the parent
    process env) into the exposure-config capture -- verified via the persisted
    source-identity sidecar, the same durable artifact `_capture_or_load_source_identity`
    writes/checks on resume."""
    from unittest.mock import patch

    monkeypatch.setenv("ENABLE_TOOL_SEARCH", "true")  # parent process env: the OPPOSITE

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

    captured_task_kwargs = {}

    def fake_agent_utility_task(**kwargs):
        captured_task_kwargs.update(kwargs)
        return object()

    monkeypatch.setattr(aui, "agent_utility_task", fake_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: [{
        "name": "mcp__justsearch__search", "description": "search",
        "input_schema": {"type": "object"},
    }])
    monkeypatch.setattr(aui, "_capture_mcp_initialize_identity", lambda _: {
        "instructions": None, "instructions_sha256": None,
        "server_version": "1.0", "protocol_version": "2025-06-18",
    })

    log_dir = tmp_path / "logs"
    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
            mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
            log_dir=str(log_dir),
            agent_env={"ENABLE_TOOL_SEARCH": "false"},
        )

    assert captured_task_kwargs["agent_env_json"] == json.dumps(
        {"ENABLE_TOOL_SEARCH": "false"}, sort_keys=True, separators=(",", ":"))

    source_identity = json.loads((log_dir / "source-identity.v1.json").read_text(encoding="utf-8"))
    # The EFFECTIVE (agent_env-overridden) value, not the parent process's "true".
    assert source_identity["exposure_config"]["enable_tool_search"] == "false"
    assert source_identity["exposure_config"]["exposure_mode"] == "eager"


def test_run_utility_eval_agent_env_none_falls_back_to_harness_process_env(tmp_path, monkeypatch):
    """Sibling to the test above: when `agent_env` is `None`/absent, the exposure
    capture falls back to the harness process's own `ENABLE_TOOL_SEARCH` (today's
    pre-increment-4 behavior), not a hardcoded default."""
    from unittest.mock import patch

    monkeypatch.setenv("ENABLE_TOOL_SEARCH", "true")

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: [{
        "name": "mcp__justsearch__search", "description": "search",
        "input_schema": {"type": "object"},
    }])
    monkeypatch.setattr(aui, "_capture_mcp_initialize_identity", lambda _: {
        "instructions": None, "instructions_sha256": None,
        "server_version": "1.0", "protocol_version": "2025-06-18",
    })

    log_dir = tmp_path / "logs"
    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
            mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
            log_dir=str(log_dir),
        )

    source_identity = json.loads((log_dir / "source-identity.v1.json").read_text(encoding="utf-8"))
    assert source_identity["exposure_config"]["enable_tool_search"] == "true"


# --- per-arm (per-condition) timeout calibration (tempdoc 624 §Harness lessons):
# the run-side threads the {condition: int} map through the task identity as a
# canonical int-valued JSON string, and the solver resolves each cell's wall-clock
# budget by its own condition (falling back to the scalar). ---

def _tiny_corpus_and_queries(tmp_path):
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")
    return str(corpus_dir), str(queries_for_eval)


def _capture_task_kwargs(monkeypatch):
    captured = {}

    def fake_agent_utility_task(**kwargs):
        captured.update(kwargs)
        return object()  # eval_set is mocked too, so any placeholder Task works

    monkeypatch.setattr(aui, "agent_utility_task", fake_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)
    return captured


def test_run_utility_eval_threads_per_condition_timeout_as_canonical_int_json(tmp_path, monkeypatch):
    corpus_dir, queries = _tiny_corpus_and_queries(tmp_path)
    captured = _capture_task_kwargs(monkeypatch)
    aui.run_utility_eval(
        queries_path=queries, corpus_dir=corpus_dir, mcp_config=None,
        conditions=("A", "C"), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        timeout_s_by_condition={"C": 150, "A": 300},
    )
    # Canonical: sorted keys, no whitespace, int values -> deterministic task identity.
    assert captured["timeout_s_by_condition_json"] == '{"A":300,"C":150}'


def test_run_utility_eval_omitted_per_condition_timeout_is_empty_json(tmp_path, monkeypatch):
    # Old calibration files (no timeout_s_by_condition) -> "{}" -> scalar for every cell.
    corpus_dir, queries = _tiny_corpus_and_queries(tmp_path)
    captured = _capture_task_kwargs(monkeypatch)
    aui.run_utility_eval(
        queries_path=queries, corpus_dir=corpus_dir, mcp_config=None,
        conditions=("A", "C"), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
    )
    assert captured["timeout_s_by_condition_json"] == "{}"


def test_run_utility_eval_coerces_float_budgets_to_int_in_task_args(tmp_path, monkeypatch):
    # A float in the task-identity args breaks eval_set resume (_assert_no_float_task_args);
    # the map is int-coerced at serialization so a float can never reach the task args.
    corpus_dir, queries = _tiny_corpus_and_queries(tmp_path)
    captured = _capture_task_kwargs(monkeypatch)
    aui.run_utility_eval(
        queries_path=queries, corpus_dir=corpus_dir, mcp_config=None,
        conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        timeout_s_by_condition={"A": 300.0},
    )
    assert captured["timeout_s_by_condition_json"] == '{"A":300}'


def _drive_solver_budget(monkeypatch, tmp_path, *, condition, timeout_s,
                         timeout_s_by_condition_json):
    """Run the solver just far enough to capture the wall-clock budget it hands to
    `asyncio.wait_for` for a cell of `condition`, without touching the real SDK: the
    fake `wait_for` records the timeout and closes the (never-awaited) coroutine."""
    captured = {}

    async def fake_wait_for(coro, timeout):
        captured["timeout"] = timeout
        coro.close()  # _one_attempt body (mkdtemp / SDK session) never runs
        raise asyncio.TimeoutError()

    monkeypatch.setattr(aui.asyncio, "wait_for", fake_wait_for)
    monkeypatch.setattr(aui, "_record_cell", lambda *a, **k: None)
    solve = aui.claude_agent_solver(
        corpus_dir=str(tmp_path), mcp_config=None, model="haiku",
        timeout_s=timeout_s, timeout_s_by_condition_json=timeout_s_by_condition_json)
    state = _state()
    state.metadata["condition"] = condition
    asyncio.run(solve(state, None))
    return captured["timeout"]


def test_solver_resolves_timeout_by_condition(tmp_path, monkeypatch):
    # tempdoc 768 D7: the resolved per-condition wall-clock is widened by the
    # generous backstop multiple (USD is the binding budget); the per-condition
    # ORDERING and calibration inputs are preserved (a > c still holds).
    mult = aui._WALL_CLOCK_BACKSTOP_MULT
    m = '{"A":300,"C":150}'
    a = _drive_solver_budget(monkeypatch, tmp_path, condition="A", timeout_s=180,
                             timeout_s_by_condition_json=m)
    c = _drive_solver_budget(monkeypatch, tmp_path, condition="C", timeout_s=180,
                             timeout_s_by_condition_json=m)
    assert a == pytest.approx(300 * mult, abs=5)
    assert c == pytest.approx(150 * mult, abs=5)
    assert a > c


def test_solver_falls_back_to_scalar_for_condition_absent_from_map(tmp_path, monkeypatch):
    # A requested but only C is in the map -> A uses the scalar timeout_s (x backstop).
    budget = _drive_solver_budget(monkeypatch, tmp_path, condition="A", timeout_s=180,
                                  timeout_s_by_condition_json='{"C":150}')
    assert budget == pytest.approx(180 * aui._WALL_CLOCK_BACKSTOP_MULT, abs=5)


def test_solver_empty_map_uses_scalar_for_every_condition(tmp_path, monkeypatch):
    # Old-calibration-file behavior: "{}" -> scalar for all cells (x backstop).
    mult = aui._WALL_CLOCK_BACKSTOP_MULT
    a = _drive_solver_budget(monkeypatch, tmp_path, condition="A", timeout_s=180,
                             timeout_s_by_condition_json="{}")
    c = _drive_solver_budget(monkeypatch, tmp_path, condition="C", timeout_s=180,
                             timeout_s_by_condition_json="{}")
    assert a == pytest.approx(180 * mult, abs=5)
    assert c == pytest.approx(180 * mult, abs=5)


def test_usd_cap_kill_retains_cost_receipt():
    # tempdoc 768 D7 / §B acceptance ("exhausted-cell receipts"): a USD-cap kill
    # DOES deliver a terminal ResultMessage (is_error, subtype error_max_budget_usd)
    # with cost intact -- unlike a wall-clock cancel (no ResultMessage, cost lost).
    # _record_cell populates cost_usd/unique_tokens BEFORE the is_error early-return,
    # so the receipt survives. This is the regression guard for the 765 §E loss.
    state = _state()
    rmsg = _rmsg(is_error=True, subtype="error_max_budget_usd", stop_reason="budget",
                 total_cost_usd=0.49, usage={"cache_creation_input_tokens": 1234},
                 result=None)
    got = {
        "attempts": {}, "results": {}, "texts": [], "rmsg": rmsg,
        "mcp_servers": None, "justsearch_tools": [],
    }
    aui._record_cell(state, got, "A", [], None)  # A = no-tool, no MCP surface gate
    assert state.metadata["cost_usd"] == 0.49          # receipt retained
    assert state.metadata["unique_tokens"] == 1234     # token lower bound retained
    # The error carries the USD-cap subtype, which utility_evidence classifies as
    # usd_budget_exhausted (distinct from the wall-clock-exhaustion bucket).
    assert "error_max_budget_usd" in state.metadata.get("error", "")
    assert _error_class(state.metadata["error"]) == "usd_budget_exhausted"


# --- tempdoc 770 §D: per-component payload byte decomposition ---


def _decomposable_payload():
    """One structured-json `justsearch_search` delivery whose per-field byte
    shares are known BY CONSTRUCTION (each expected count below is hand-derived
    from the JSON text, not read back from the code under test)."""
    return {"results": [{
        "id": "X", "path": "X", "excerpts": ["e"], "trace": {"a": 1}, "legScores": [1],
    }]}


def test_delivered_component_bytes_shares_known_by_construction():
    payload = _decomposable_payload()
    content = json.dumps(payload)

    comp = aui._delivered_component_bytes(content)

    # Hand-derived entry costs: len('"key"') + 1 (colon) + len(json value text).
    assert comp["hit_component_bytes"]["id"] == 8          # '"id"'(4) + 1 + '"X"'(3)
    assert comp["hit_component_bytes"]["path"] == 10       # '"path"'(6) + 1 + '"X"'(3)
    assert comp["hit_component_bytes"]["excerpts"] == 16   # '"excerpts"'(10) + 1 + '["e"]'(5)
    assert comp["hit_component_bytes"]["trace"] == 16      # '"trace"'(7) + 1 + '{"a": 1}'(8)
    assert comp["hit_component_bytes"]["legScores"] == 15  # '"legScores"'(11) + 1 + '[1]'(3)
    # `results` entry bytes, derived independently of the SUT.
    expected_results = len('"results"') + 1 + len(json.dumps(payload["results"]))
    assert comp["results_bytes"] == expected_results
    assert comp["top_level_bytes"] == {"results": expected_results}
    # The only unaccounted top-level bytes are structural punctuation: the two
    # braces plus the one space in json.dumps' default `": "` key separator.
    assert comp["serialized_bytes"] == len(json.dumps(payload))
    assert comp["top_level_other_bytes"] == 3
    # `other` is an explicit remainder, never a measured field.
    assert comp["hit_component_bytes"]["other"] == expected_results - (8 + 10 + 16 + 16 + 15)
    assert comp["total_bytes"] == len(content)
    assert comp["hit_count"] == 1


def test_delivered_component_bytes_counts_id_equals_path_duplicates():
    content = json.dumps({"results": [
        {"id": "a.txt", "path": "a.txt"},   # byte-identical duplicate
        {"id": "b.txt", "path": "b.txt"},   # byte-identical duplicate
        {"id": "c.txt", "path": "other.txt"},  # genuinely different
        {"id": "d.txt"},                    # no path at all -> not in the denominator
    ]})

    comp = aui._delivered_component_bytes(content)

    assert comp["hit_count"] == 4
    assert comp["hits_with_id_and_path"] == 3
    assert comp["id_equals_path_hits"] == 2


def test_delivered_component_bytes_none_for_non_structured_tiers():
    """Prose / blocks / never-executed deliveries have nothing to decompose --
    the honest null, never a fabricated all-zero decomposition."""
    assert aui._delivered_component_bytes("Evidence pack: 3 passages") is None
    assert aui._delivered_component_bytes([{"type": "text", "text": "hi"}]) is None
    assert aui._delivered_component_bytes(None) is None
    prose = aui._tool_result_digest_entry({"is_error": False, "content": "Evidence pack: 1"})
    assert prose["component_bytes"] is None
    blocked = aui._tool_result_digest_entry(None)
    assert blocked["component_bytes"] is None


def test_tool_result_digest_entry_carries_component_bytes_for_structured():
    content = json.dumps(_decomposable_payload())
    entry = aui._tool_result_digest_entry({"is_error": False, "content": content})
    assert entry["component_bytes"]["hit_count"] == 1
    assert entry["delivered_tier"] == "structured-json"
    # The decomposition is counts-only: no payload VALUE text leaks into the digest
    # (key names are the product's own schema, the values are corpus-derived).
    assert "\"e\"" not in json.dumps(entry["component_bytes"])


def test_decompose_payload_shares_reports_unavailable_for_digest_only_entries(monkeypatch, tmp_path):
    """A v5-era log stores digests only (`content_sha256`/`content_len`, never raw
    content), so its structured deliveries CANNOT be decomposed. The aggregate must
    report that count honestly instead of estimating or silently skipping."""
    digest_only = [
        {"delivered_tier": "structured-json", "content_sha256": "aa", "content_len": 100},
        {"delivered_tier": "structured-json", "content_sha256": "bb", "content_len": 200},
        {"delivered_tier": "prose", "content_sha256": "cc", "content_len": 10},
    ]
    monkeypatch.setattr(aui, "read_digest_entries", lambda _d: digest_only)

    report = aui.decompose_payload_shares(tmp_path)

    assert report["structured_deliveries"] == 2
    assert report["decomposed"] == 0
    assert report["decomposition_unavailable"] == 2
    assert report["aggregate_serialized_bytes"] == 0
    # Every statistic reports N=0 and an honest null -- never a fabricated share.
    for stat in report["components"].values():
        assert stat["n"] == 0
        assert stat["median_share"] is None
        assert stat["aggregate_share"] is None
    assert report["hits"]["n"] == 0
    assert report["hits"]["median"] is None


def test_decompose_payload_shares_decomposes_component_bytes_captured_at_run_time(monkeypatch, tmp_path):
    content = json.dumps(_decomposable_payload())
    entry = aui._tool_result_digest_entry({"is_error": False, "content": content})
    monkeypatch.setattr(aui, "read_digest_entries", lambda _d: [entry])

    report = aui.decompose_payload_shares(tmp_path)

    assert report["decomposed"] == 1
    assert report["decomposed_from_log_component_bytes"] == 1
    assert report["decomposition_unavailable"] == 0
    assert report["components"]["hit.id"]["n"] == 1
    assert report["components"]["hit.id"]["aggregate_bytes"] == 8
    assert report["hits"]["median"] == 1


def test_decompose_payload_shares_sha256_verifies_recovered_payloads(monkeypatch, tmp_path):
    """The tempdoc 770 §D recovery route: raw payloads recovered from CLI session
    transcripts are decomposed ONLY after their SHA256 matches the campaign digest
    index. An unmatched file is counted and discarded, never decomposed."""
    content = json.dumps(_decomposable_payload())
    digest_only = [{
        "delivered_tier": "structured-json",
        "content_sha256": aui._content_sha256(content),
        "content_len": len(content),
    }]
    monkeypatch.setattr(aui, "read_digest_entries", lambda _d: digest_only)
    payload_dir = tmp_path / "payloads"
    payload_dir.mkdir()
    (payload_dir / "verified.json").write_text(content, encoding="utf-8")
    (payload_dir / "foreign.json").write_text(
        json.dumps({"results": [{"id": "not-from-this-campaign"}]}), encoding="utf-8")

    report = aui.decompose_payload_shares(tmp_path, payload_dir)

    assert report["payloads_sha256_verified"] == 1
    assert report["payloads_sha256_unmatched"] == 1
    assert report["decomposed_from_verified_payloads"] == 1
    assert report["decomposed_from_log_component_bytes"] == 0
    assert report["decomposition_unavailable"] == 0
    assert report["components"]["hit.path"]["aggregate_bytes"] == 10
    assert report["id_equals_path"]["hits_id_equals_path"] == 1


def test_decompose_payload_shares_counts_a_both_routes_delivery_once(monkeypatch, tmp_path):
    """A delivery reachable by BOTH routes -- the log carries `component_bytes`
    AND the raw payload file is still on disk and SHA-verifies -- is ONE delivery,
    not two. Decomposing both inflated every `n` and every `aggregate_bytes` and
    drove `decomposition_unavailable` negative, which would destroy the "every
    statistic reports its own N" contract this module is built on."""
    content = json.dumps(_decomposable_payload())
    entry = aui._tool_result_digest_entry({"is_error": False, "content": content})
    assert isinstance(entry["component_bytes"], dict)  # route 1 is genuinely available
    monkeypatch.setattr(aui, "read_digest_entries", lambda _d: [entry])
    payload_dir = tmp_path / "payloads"
    payload_dir.mkdir()
    (payload_dir / "same-delivery.json").write_text(content, encoding="utf-8")  # route 2

    report = aui.decompose_payload_shares(tmp_path, payload_dir)

    assert report["structured_deliveries"] == 1
    assert report["decomposed"] == 1
    # The log's own component_bytes is the authoritative route; the payload file
    # still SHA-verifies (a real fact about the dir) but is not decomposed again.
    assert report["decomposed_from_log_component_bytes"] == 1
    assert report["decomposed_from_verified_payloads"] == 0
    assert report["payloads_sha256_verified"] == 1
    assert report["payloads_skipped_already_decomposed"] == 1
    assert report["decomposition_unavailable"] == 0
    # Byte statistics are counted once -- the pre-fix code reported 2 and 16 here.
    assert report["components"]["hit.id"]["n"] == 1
    assert report["components"]["hit.id"]["aggregate_bytes"] == 8
    assert report["hits"]["n"] == 1


# --- tempdoc 782 follow-up, defect 1: the mixed-model guard must not destroy the
# cell's budget-exhaustion label (the guard site fires before the
# `ResultMessage.is_error` site, and both used to write the same first-wins slot).
# The 782 hero campaign voided a whole stratum on the guard and lost every
# exhaustion receipt with it. ---


def _poisoned_and_exhausted_got():
    """The exact producer input for a cell that is BOTH mixed-model-poisoned AND
    USD-budget-exhausted: two resolved provider models in one cell's `model_usage`
    PLUS a terminal `is_error` ResultMessage carrying the `error_max_budget_usd`
    subtype (the shape a USD-cap kill actually delivers -- see
    test_usd_cap_kill_retains_cost_receipt)."""
    rmsg = _rmsg(
        is_error=True, subtype="error_max_budget_usd", stop_reason="budget",
        total_cost_usd=0.79, usage={"cache_creation_input_tokens": 4321},
        model_usage={"haiku-4-5": {"input_tokens": 1}, "sonnet-5": {"input_tokens": 2}},
        result=None,
    )
    return {
        "attempts": {}, "results": {}, "texts": [], "rmsg": rmsg,
        "mcp_servers": None, "justsearch_tools": [],
    }


def test_mixed_model_guard_preserves_the_exhaustion_label():
    state = _state()
    aui._record_cell(state, _poisoned_and_exhausted_got(), "A", [], None)

    # Fact 1 -- the guard error stays PRIMARY. That is deliberate: `error` is the
    # fail-closed exclusion label, and a poisoned cell must not be promoted into the
    # scored (comparability-neutral) resource-exhaustion bucket.
    assert "resolved provider model changed within one cell" in state.metadata["error"]
    assert _error_class(state.metadata["error"]) != "usd_budget_exhausted"

    # Fact 2 -- the exhaustion label survives ALONGSIDE it (pre-fix: destroyed by
    # the guard, so the cell looked like a plain executor error).
    secondary = state.metadata["secondary_errors"]
    assert any("error_max_budget_usd" in text for text in secondary)
    assert any(_error_class(text) == "usd_budget_exhausted" for text in secondary)

    # Both facts are readable together, which is what the exhaustion accounting needs.
    assert any(
        classify_error_kind(text) == RESOURCE_EXHAUSTION
        for text in aui._cell_error_texts(state.metadata)
    )
    # The receipts a USD-cap kill delivers are still retained (unchanged by this fix).
    assert state.metadata["cost_usd"] == 0.79
    assert state.metadata["unique_tokens"] == 4321


def test_unpoisoned_exhausted_cell_keeps_the_exhaustion_label_primary():
    # Precision control: with ONE resolved model the guard never fires, so the
    # exhaustion label is the primary `error` and nothing is pushed to secondary.
    # (Distinguishes "passes because both errors are recorded" from "passes because
    # the fix reshuffled every cell's error".)
    state = _state()
    got = _poisoned_and_exhausted_got()
    got["rmsg"].model_usage = {"sonnet-5": {"input_tokens": 2}}
    aui._record_cell(state, got, "A", [], None)
    assert _error_class(state.metadata["error"]) == "usd_budget_exhausted"
    assert "secondary_errors" not in state.metadata


def test_note_error_is_first_wins_and_deduplicates():
    metadata: dict = {}
    aui._note_error(metadata, "first")
    aui._note_error(metadata, "first")          # exact repeat -> not recorded twice
    aui._note_error(metadata, "second")
    aui._note_error(metadata, "second")
    assert metadata["error"] == "first"
    assert metadata["secondary_errors"] == ["second"]
    assert aui._cell_error_texts(metadata) == ["first", "second"]
    assert aui._cell_error_texts({}) == []


def _drive_solver_with_recorded_cell(monkeypatch, tmp_path, got):
    """Run the solver's `solve` closure to completion over a pre-built `got`,
    without touching the real SDK: `wait_for` closes the (never-awaited) attempt
    coroutine and returns success, and `_record_cell` is redirected onto the REAL
    projection applied to `got` -- so the post-record stamping glue under test sees
    exactly the metadata the production projection would have written."""
    real_record_cell = aui._record_cell

    async def fake_wait_for(coro, timeout):
        coro.close()
        return None

    def fake_record_cell(state, _capture, condition, disallowed, mcp_config, *args, **kwargs):
        real_record_cell(state, got, condition, disallowed, mcp_config, *args, **kwargs)

    monkeypatch.setattr(aui.asyncio, "wait_for", fake_wait_for)
    monkeypatch.setattr(aui, "_record_cell", fake_record_cell)
    solve = aui.claude_agent_solver(
        corpus_dir=str(tmp_path), mcp_config=None, model="haiku",
        timeout_s=180, timeout_s_by_condition_json="{}")
    state = _state()
    state.metadata["condition"] = "A"
    asyncio.run(solve(state, None))
    return state


def test_guard_voided_exhausted_cell_is_still_flagged_usage_truncated(tmp_path, monkeypatch):
    # The consumer of the preserved label: `usage_truncated` marks cost/tokens as a
    # LOWER BOUND for a resource-exhausted cell. Pre-fix the guard error was the only
    # text this predicate could see, so a guard-voided exhausted cell's truncated
    # receipts were silently presented as exact.
    state = _drive_solver_with_recorded_cell(
        monkeypatch, tmp_path, _poisoned_and_exhausted_got())
    assert state.metadata["usage_truncated"] is True


def test_guard_voided_non_exhausted_cell_is_not_flagged_usage_truncated(tmp_path, monkeypatch):
    # Adverse control: a guard-voided cell whose terminal error is NOT an exhaustion
    # shape must stay unflagged -- the predicate widened to read every recorded error,
    # it did not become "any error truncates usage".
    got = _poisoned_and_exhausted_got()
    got["rmsg"].subtype = "error_during_execution"
    state = _drive_solver_with_recorded_cell(monkeypatch, tmp_path, got)
    assert "error_max_budget_usd" not in json.dumps(aui._cell_error_texts(state.metadata))
    assert state.metadata.get("usage_truncated") is None


# --- tempdoc 782 follow-up, defect 2: rank-of-gold capture on the delivered hit
# shape. `McpEvidenceProjection.putIdentity` (McpEvidenceProjection.java:168-175)
# OMITS `id` when it is byte-identical to `path`, so reading `id` alone captured a
# null gold_rank on 633 of 635 B-arm search calls in the 782 hero run. ---


def _search_content_path_only(ordered_paths, scores=None):
    """A structured-json `justsearch_search` delivery in the LIVE dedup shape: each
    hit carries `path` and NO `id` (what putIdentity emits when hit.id() == path)."""
    scores = scores if scores is not None else [1.0 - 0.1 * i for i in range(len(ordered_paths))]
    return json.dumps({
        "results": [{"path": p, "score": sc} for p, sc in zip(ordered_paths, scores)],
        "coverage": {"totalHits": len(ordered_paths)},
    })


def test_gold_rank_captured_when_hits_carry_path_but_no_id():
    paths = [
        r"f:\corpus-dir\cavby8.txt",
        r"f:\corpus-dir\limker1.txt",   # gold
        r"f:\corpus-dir\other9.txt",
    ]
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": _search_content_path_only(paths, [0.9, 0.8, 0.7])},
        evidence_ids=["limker1"])
    assert entry["ordered_doc_ids"] == paths     # raw delivered identity, not None
    assert entry["scores"] == [0.9, 0.8, 0.7]
    assert entry["gold_rank"] == 1               # was None pre-fix


def test_gold_rank_prefers_id_when_both_keys_are_delivered():
    # When the projection emits BOTH (id != path), `id` stays the ranking identifier:
    # the gold match follows `id`, and a `path` naming a different doc does not
    # divert it.
    content = json.dumps({"results": [
        {"id": r"f:\corpus-dir\gold7.txt", "path": r"f:\other\decoy.txt", "score": 0.9},
    ]})
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["gold7"])
    assert entry["ordered_doc_ids"] == [r"f:\corpus-dir\gold7.txt"]
    assert entry["gold_rank"] == 0
    decoy_gold = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["decoy"])
    assert decoy_gold["gold_rank"] is None


def test_hit_doc_id_falls_back_and_stays_null_when_neither_key_is_usable():
    assert aui._hit_doc_id({"id": "A", "path": "B"}) == "A"
    assert aui._hit_doc_id({"path": "B"}) == "B"
    assert aui._hit_doc_id({"id": "", "path": "B"}) == "B"   # blank id is not an identity
    assert aui._hit_doc_id({"score": 1.0}) is None
    assert aui._hit_doc_id({"id": 7}) is None                # non-str -> honest null


def test_normalize_doc_id_percent_decodes_materialized_filenames():
    # materialize.doc_id_to_filename percent-encodes the doc id when it names the
    # file (urllib.parse.quote(doc_id, safe="")), so a MIRACL-shaped id `1234567#0`
    # lands on disk as `1234567%230.txt`; without decoding it could never match its
    # gold id. Mirrors retriever._filename_to_doc_id (jseval/retriever.py:251-268).
    assert aui._normalize_doc_id(r"f:\corpus-dir\1234567%230.txt") == "1234567#0"
    assert aui._normalize_doc_id("limker1") == "limker1"     # plain ids unaffected
    content = _search_content_path_only([r"f:\corpus-dir\1234567%230.txt"])
    entry = aui._tool_result_digest_entry(
        {"is_error": False, "content": content}, evidence_ids=["1234567#0"])
    assert entry["gold_rank"] == 0
