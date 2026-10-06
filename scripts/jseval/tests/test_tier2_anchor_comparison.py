"""Focused evidence and paired-comparison tests for Tier-2 RAG eval."""

from __future__ import annotations

import copy
import hashlib
import json
from unittest.mock import MagicMock

import pytest
from click.testing import CliRunner

from jseval.agent_retrieval_eval import (
    Tier2ComparisonError,
    _extract_tier2_anchor_evidence,
    _tier2_evaluator_protocol,
    _tier2_protocol_digest,
    compare_tier2_results,
    run_tier2_eval,
)
from jseval.commands.eval_cmds import cmd_tier2_eval


SERVED_MODEL = "Qwen3.5-9B-Instruct-Q4_K_M.gguf"
WINDOWS_PARENT = r"C:\Users\Elias\legal:archive\opinion.txt"


def _anchor(index: int, parent: str = WINDOWS_PARENT) -> dict:
    return {"parent_doc_id": parent, "chunk_index": index}


def _eval_config(model: str = SERVED_MODEL, max_context_tokens: int = 4096) -> dict:
    return {
        "top_k": 10,
        "max_context_tokens": max_context_tokens,
        "structured": True,
        "use_paper_prompt": False,
        "source_check": False,
        "served_model": model,
    }


def _result(query: str, anchors: list[dict], **overrides) -> dict:
    row = {
        "query": query,
        "answer": f"answer-{query}",
        "question_type": "comparison",
        "error": "",
        "answer_in_context": False,
        "correct_has_intersection": False,
        "context_truncated": False,
        "latency_retrieve_ms": 10,
        "retrieval_chunks": len(anchors),
        "included_anchors": copy.deepcopy(anchors),
        "included_anchor_count": len(anchors),
        "anchor_evidence_error": "",
    }
    row.update(overrides)
    return row


def _record(
    rows: list[dict],
    model: str = SERVED_MODEL,
    *,
    base_url: str = "http://127.0.0.1:33221",
    llm_url: str = "http://127.0.0.1:8080",
) -> dict:
    return {
        "tier": "tier2_single_shot_rag",
        "served_model": model,
        "eval_config": _eval_config(model),
        "evaluator_protocol": _tier2_evaluator_protocol(
            structured=True, use_paper_prompt=False),
        "eval_provenance": {"base_url": base_url, "llm_url": llm_url},
        "total_queries": len(rows),
        "anchor_evidence_errors": sum(
            1 for row in rows if row.get("anchor_evidence_error")),
        "results": copy.deepcopy(rows),
    }


def test_run_captures_structured_windows_anchor_identities_and_eval_config(monkeypatch):
    original_protocol = _tier2_evaluator_protocol(
        structured=True, use_paper_prompt=False)
    fingerprinted_path = "/fingerprinted/retrieve-context"
    fingerprinted_template = "EVIDENCE={context}\nASK={question}"
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval._TIER2_RETRIEVE_PATH", fingerprinted_path)
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval._TIER2_USER_MESSAGE_TEMPLATE",
        fingerprinted_template,
    )
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.served_model_name", lambda _url: SERVED_MODEL)

    retrieve_response = MagicMock()
    retrieve_response.raise_for_status = MagicMock()
    retrieve_response.json.return_value = {
        "context": "The visible answer is 42.",
        "chunks": [_anchor(5), _anchor(7)],
        "quality": {"chunks_included": 2, "truncated": False},
    }
    retrieve_client = MagicMock()
    retrieve_client.post.return_value = retrieve_response
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.httpx.Client",
        lambda *, base_url, timeout: retrieve_client,
    )

    llm_response = MagicMock()
    llm_response.json.return_value = {
        "choices": [{"message": {"content": (
            '{"answer": "42", "evidence_summary": "visible", "confidence": "high"}'
        )}}],
        "usage": {"completion_tokens": 3},
    }
    llm_post = MagicMock(return_value=llm_response)
    monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.post", llm_post)

    aggregate = run_tier2_eval(
        [{"query": "q", "answer": "42", "question_type": "comparison"}],
        top_k=10,
        max_context_tokens=4096,
    )

    row = aggregate["results"][0]
    assert row["included_anchors"] == [_anchor(5), _anchor(7)]
    assert row["included_anchor_count"] == 2
    assert row["retrieval_chunks"] == 2
    assert row["anchor_evidence_error"] == ""
    assert aggregate["eval_config"] == _eval_config()
    assert aggregate["evaluator_protocol"]["id"] == "tier2-evaluator.v1"
    assert len(aggregate["evaluator_protocol"]["digest_sha256"]) == 64
    protocol_definition = aggregate["evaluator_protocol"]["definition"]
    assert protocol_definition["retrieval_request"]["path"] == fingerprinted_path
    assert protocol_definition["prompt_variant"] == "standard"
    assert protocol_definition["scoring_version"] == "exact-substring-has-intersection.v1"
    assert protocol_definition["llm_request"]["max_tokens"] == 512
    assert protocol_definition["llm_request"]["response_schema_sha256"]
    assert protocol_definition["llm_request"]["user_message_template"] == {
        "id": "context-question.v1",
        "sha256": hashlib.sha256(fingerprinted_template.encode("utf-8")).hexdigest(),
    }
    assert aggregate["evaluator_protocol"]["digest_sha256"] != (
        original_protocol["digest_sha256"])
    assert aggregate["evaluator_protocol"]["digest_sha256"] != (
        _tier2_evaluator_protocol(
            structured=True, use_paper_prompt=True)["digest_sha256"])
    assert aggregate["eval_provenance"] == {
        "base_url": "http://127.0.0.1:33221",
        # The LLM default is the Engine's /v1 proxy (reconciliation F13), not llama-server's port.
        "llm_url": "http://127.0.0.1:33221",
    }
    assert retrieve_client.post.call_args.args[0] == fingerprinted_path
    llm_body = llm_post.call_args.kwargs["json"]
    assert llm_body["messages"][1]["content"] == "EVIDENCE=The visible answer is 42.\nASK=q"


def test_run_marks_invalid_context_and_truncated_types_as_comparison_blocking(monkeypatch):
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.served_model_name", lambda _url: SERVED_MODEL)

    retrieve_response = MagicMock()
    retrieve_response.raise_for_status = MagicMock()
    retrieve_response.json.return_value = {
        "context": 42,
        "chunks": [_anchor(5)],
        "quality": {"chunks_included": 2, "truncated": "false"},
    }
    retrieve_client = MagicMock()
    retrieve_client.post.return_value = retrieve_response
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.httpx.Client",
        lambda *, base_url, timeout: retrieve_client,
    )

    llm_response = MagicMock()
    llm_response.json.return_value = {
        "choices": [{"message": {"content": (
            '{"answer": "42", "evidence_summary": "none", "confidence": "low"}'
        )}}],
        "usage": {"completion_tokens": 3},
    }
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.httpx.post",
        MagicMock(return_value=llm_response),
    )

    aggregate = run_tier2_eval([
        {"query": "q", "answer": "42", "question_type": "comparison"},
    ])

    evidence_error = aggregate["results"][0]["anchor_evidence_error"]
    assert "disagrees with quality.chunks_included" in evidence_error
    assert "retrieve-context context must be a string" in evidence_error
    assert "quality.truncated must be a boolean" in evidence_error
    assert aggregate["anchor_evidence_errors"] == 1
    with pytest.raises(Tier2ComparisonError, match="invalid anchor evidence"):
        compare_tier2_results(aggregate, aggregate)


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        ({"quality": {"chunks_included": 0}}, "missing the chunks array"),
        (
            {"chunks": [{"parent_doc_id": WINDOWS_PARENT, "chunk_index": "5"}],
             "quality": {"chunks_included": 1}},
            "chunk_index must be -1 or a non-negative integer",
        ),
        (
            {"chunks": [_anchor(5)], "quality": {"chunks_included": 2}},
            "disagrees with quality.chunks_included",
        ),
    ],
)
def test_anchor_evidence_marks_missing_malformed_and_mismatched_payloads_invalid(
    payload, message,
):
    _anchors, _count, _retrieval_chunks, error = _extract_tier2_anchor_evidence(payload)
    assert message in error


def test_anchor_evidence_accepts_only_absent_sentinel_or_nonnegative_indices():
    anchors, count, retrieval_chunks, error = _extract_tier2_anchor_evidence({
        "chunks": [_anchor(-1), _anchor(0)],
        "quality": {"chunks_included": 2},
    })
    assert anchors == [_anchor(-1), _anchor(0)]
    assert (count, retrieval_chunks, error) == (2, 2, "")

    _anchors, _count, _retrieval_chunks, error = _extract_tier2_anchor_evidence({
        "chunks": [_anchor(-2)],
        "quality": {"chunks_included": 1},
    })
    assert "must be -1 or a non-negative integer" in error


def test_comparator_reports_all_paired_transitions_and_anchor_changes():
    before = _record([
        _result("q1", [_anchor(5)], latency_retrieve_ms=10),
        _result(
            "q2", [_anchor(5), _anchor(7)], answer_in_context=True,
            correct_has_intersection=True, context_truncated=True,
            latency_retrieve_ms=20,
        ),
        _result(
            "q3", [_anchor(5)], answer_in_context=True, error="baseline error",
            latency_retrieve_ms=30,
        ),
        _result(
            "q4", [_anchor(5)], correct_has_intersection=True,
            context_truncated=True, error="baseline error", latency_retrieve_ms=40,
        ),
    ])
    after = _record([
        _result(
            "q1", [_anchor(5), _anchor(7)], answer_in_context=True,
            correct_has_intersection=True, context_truncated=True,
            latency_retrieve_ms=12,
        ),
        _result("q2", [_anchor(7), _anchor(5)], error="candidate error",
                latency_retrieve_ms=18),
        _result("q3", [], answer_in_context=True, latency_retrieve_ms=45),
        _result(
            "q4", [_anchor(5)], correct_has_intersection=True,
            context_truncated=True, error="candidate error", latency_retrieve_ms=40,
        ),
    ])

    before_snapshot = copy.deepcopy(before)
    after_snapshot = copy.deepcopy(after)
    comparison = compare_tier2_results(before, after)

    assert comparison["schema_version"] == "tier2-paired-comparison.v2"
    assert comparison["evaluator_compatible"] is True
    assert "compatible" not in comparison
    assert comparison["experimental_admissibility"] == {
        "status": "external-check-required",
        "checked_by_comparator": False,
        "required_checks": [
            "baseline and candidate use the same logical index identity",
            "enrichment state is stable within and equal across both arms",
        ],
    }
    assert comparison == compare_tier2_results(before, after)
    assert before == before_snapshot
    assert after == after_snapshot
    comparison["evaluator_protocol"]["definition"]["id"] = "mutated-output"
    assert before["evaluator_protocol"]["definition"]["id"] == "tier2-evaluator.v1"
    assert comparison["transitions"]["answer_in_context"] == {
        "false_to_false": 1,
        "false_to_true": 1,
        "true_to_false": 1,
        "true_to_true": 1,
    }
    assert comparison["transitions"]["correct_has_intersection"] == {
        "false_to_false": 1,
        "false_to_true": 1,
        "true_to_false": 1,
        "true_to_true": 1,
    }
    assert comparison["transitions"]["errors"] == {
        "none_to_none": 1,
        "none_to_error": 1,
        "error_to_none": 1,
        "error_to_error": 1,
    }
    assert comparison["transitions"]["context_truncated"] == {
        "false_to_false": 1,
        "false_to_true": 1,
        "true_to_false": 1,
        "true_to_true": 1,
    }
    assert comparison["transitions"]["anchor_count"] == {
        "decreased": 1, "unchanged": 2, "increased": 1}
    assert comparison["transitions"]["anchor_identity"] == {
        "unchanged": 2, "changed": 2}
    assert comparison["transitions"]["anchor_order"] == {
        "unchanged": 1, "changed": 1, "not_comparable": 2}
    assert comparison["anchor_changes"] == {
        "additions": 1, "removals": 1, "baseline_total": 5, "candidate_total": 5}
    assert comparison["retrieval_latency_ms"] == {
        "baseline_mean": 25.0,
        "candidate_mean": 28.75,
        "mean_delta": 3.75,
        "mean_delta_percent": 15.0,
    }
    assert comparison["per_query"][0]["anchor_additions"] == [_anchor(7)]
    assert comparison["per_query"][1]["anchor_order_changed"] is True
    assert comparison["per_query"][2]["anchor_removals"] == [_anchor(5)]


def test_comparator_accepts_zero_context_budget_absent_anchor_and_different_endpoints():
    baseline = _record(
        [_result("q", [_anchor(-1)])],
        base_url="http://127.0.0.1:33221",
        llm_url="http://127.0.0.1:8080",
    )
    candidate = _record(
        [_result("q", [_anchor(-1)])],
        base_url="http://127.0.0.1:34221",
        llm_url="http://127.0.0.1:8180",
    )
    baseline["eval_config"]["max_context_tokens"] = 0
    candidate["eval_config"]["max_context_tokens"] = 0

    comparison = compare_tier2_results(baseline, candidate)

    assert comparison["evaluator_compatible"] is True
    assert comparison["eval_config"]["max_context_tokens"] == 0
    assert comparison["eval_provenance"] == {
        "affects_semantic_compatibility": False,
        "baseline": baseline["eval_provenance"],
        "candidate": candidate["eval_provenance"],
    }


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        ("model", "configuration/model differs"),
        ("config", "configuration/model differs"),
        ("query", "ordered query/answer/question_type sequence differs"),
        ("protocol", "evaluator protocol differs"),
        ("zero_top_k", "eval_config.top_k has an invalid value"),
        ("tier", "tier must be"),
        ("missing_evidence", "missing included_anchors"),
        ("evidence_error", "invalid anchor evidence"),
        ("summary_evidence_mismatch", "anchor_evidence_errors 1 disagrees"),
        ("malformed_anchor", "chunk_index must be -1 or a non-negative integer"),
        ("below_absent_anchor", "chunk_index must be -1 or a non-negative integer"),
        ("count_mismatch", "included_anchor_count disagrees"),
    ],
)
def test_comparator_rejects_incompatible_or_invalid_records(mutation, message):
    baseline = _record([_result("q", [_anchor(5)])])
    candidate = copy.deepcopy(baseline)

    if mutation == "model":
        candidate["served_model"] = "different-model"
        candidate["eval_config"]["served_model"] = "different-model"
    elif mutation == "config":
        candidate["eval_config"]["top_k"] = 20
    elif mutation == "query":
        candidate["results"][0]["query"] = "different query"
    elif mutation == "protocol":
        definition = candidate["evaluator_protocol"]["definition"]
        definition["scoring_version"] = "different-scoring.v2"
        candidate["evaluator_protocol"]["digest_sha256"] = _tier2_protocol_digest(definition)
    elif mutation == "zero_top_k":
        candidate["eval_config"]["top_k"] = 0
    elif mutation == "tier":
        candidate["tier"] = 2
    elif mutation == "missing_evidence":
        del candidate["results"][0]["included_anchors"]
    elif mutation == "evidence_error":
        candidate["results"][0]["anchor_evidence_error"] = "producer mismatch"
        candidate["anchor_evidence_errors"] = 1
    elif mutation == "summary_evidence_mismatch":
        candidate["anchor_evidence_errors"] = 1
    elif mutation == "malformed_anchor":
        candidate["results"][0]["included_anchors"][0]["chunk_index"] = "5"
    elif mutation == "below_absent_anchor":
        candidate["results"][0]["included_anchors"][0]["chunk_index"] = -2
    elif mutation == "count_mismatch":
        candidate["results"][0]["included_anchor_count"] = 2

    with pytest.raises(Tier2ComparisonError, match=message):
        compare_tier2_results(baseline, candidate)


def test_cli_attaches_paired_comparison_before_writing_json(monkeypatch, tmp_path):
    baseline = _record([_result("q", [_anchor(5)])])
    candidate = _record([_result("q", [_anchor(5), _anchor(7)])])
    queries_file = tmp_path / "queries.json"
    baseline_file = tmp_path / "baseline.json"
    output_dir = tmp_path / "candidate"
    queries_file.write_text("[]", encoding="utf-8")
    baseline_file.write_text(json.dumps(baseline), encoding="utf-8")

    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.run_tier2_eval",
        lambda *args, **kwargs: copy.deepcopy(candidate),
    )

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        [
            "--queries", str(queries_file),
            "--baseline-results", str(baseline_file),
            "--output-dir", str(output_dir),
        ],
        obj={"json": True},
    )

    assert invocation.exit_code == 0, invocation.output
    saved = json.loads((output_dir / "tier2-eval.json").read_text(encoding="utf-8"))
    assert saved["paired_comparison"]["evaluator_compatible"] is True
    assert saved["paired_comparison"]["per_query"][0]["anchor_additions"] == [_anchor(7)]


def test_default_console_reports_decision_evidence_and_admissibility(monkeypatch, tmp_path):
    baseline = _record([_result("q", [_anchor(5)])])
    candidate = _record([
        _result(
            "q", [_anchor(5), _anchor(7)], answer_in_context=True,
            context_truncated=True, latency_retrieve_ms=15,
        )
    ])
    candidate.update({
        "answer_in_context_rate": 1.0,
        "context_truncated_rate": 1.0,
        "accuracy_has_intersection": 0.0,
        "accuracy_substring": 0.0,
        "accuracy_exact": 0.0,
        "avg_latency_retrieve_ms": 15,
        "avg_latency_llm_ms": 20,
        "avg_context_tokens": 100,
        "avg_completion_tokens": 10,
        "confidence_distribution": {},
        "by_type": {},
        "errors": 0,
    })
    queries_file = tmp_path / "queries.json"
    baseline_file = tmp_path / "baseline.json"
    queries_file.write_text("[]", encoding="utf-8")
    baseline_file.write_text(json.dumps(baseline), encoding="utf-8")
    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.run_tier2_eval",
        lambda *args, **kwargs: copy.deepcopy(candidate),
    )

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        ["--queries", str(queries_file), "--baseline-results", str(baseline_file)],
        obj={"json": False},
    )

    assert invocation.exit_code == 0, invocation.output
    assert "Answer in context:       100.0%" in invocation.output
    assert "Context truncated:       100.0%" in invocation.output
    assert "--- Paired Comparison ---" in invocation.output
    assert "Evaluator compatible: True" in invocation.output
    assert "Experimental admissibility: external-check-required" in invocation.output
    assert "Gold-span context wins/losses: 1/0" in invocation.output
    assert "Anchor counts decreased/unchanged/increased: 0/0/1" in invocation.output


def test_cli_rejects_malformed_baseline_before_starting_run(monkeypatch, tmp_path):
    queries_file = tmp_path / "queries.json"
    baseline_file = tmp_path / "baseline.json"
    queries_file.write_text("[]", encoding="utf-8")
    baseline_file.write_text("{not-json", encoding="utf-8")
    run = MagicMock()
    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr("jseval.agent_retrieval_eval.run_tier2_eval", run)

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        ["--queries", str(queries_file), "--baseline-results", str(baseline_file)],
        obj={"json": False},
    )

    assert invocation.exit_code != 0
    assert "baseline is not canonical" in invocation.output
    run.assert_not_called()


def test_run_preflight_rejects_unknown_model_and_mismatched_baseline_before_retrieval(
    monkeypatch,
):
    client_factory = MagicMock()
    monkeypatch.setattr("httpx.Client", client_factory)
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.served_model_name", lambda _url: None)

    with pytest.raises(Tier2ComparisonError, match="model identity is unavailable"):
        run_tier2_eval([], require_served_model=True)
    client_factory.assert_not_called()

    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.served_model_name", lambda _url: SERVED_MODEL)
    baseline = _record([_result("q", [_anchor(5)])])
    with pytest.raises(Tier2ComparisonError, match="planned run"):
        run_tier2_eval(
            [{"query": "q", "answer": "answer-q", "question_type": "comparison"}],
            top_k=20,
            comparison_baseline=baseline,
            require_served_model=True,
        )
    client_factory.assert_not_called()


def test_standalone_cli_refuses_to_write_invalid_anchor_evidence(monkeypatch, tmp_path):
    candidate = _record([_result(
        "q", [_anchor(5)], anchor_evidence_error="quality.truncated must be a boolean")])
    queries_file = tmp_path / "queries.json"
    output_dir = tmp_path / "candidate"
    queries_file.write_text("[]", encoding="utf-8")

    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.run_tier2_eval",
        lambda *args, **kwargs: copy.deepcopy(candidate),
    )

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        ["--queries", str(queries_file), "--output-dir", str(output_dir)],
        obj={"json": True},
    )

    assert invocation.exit_code != 0
    assert "invalid anchor evidence for 1 query" in invocation.output
    assert not (output_dir / "tier2-eval.json").exists()


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        ("missing", "missing included_anchors"),
        ("malformed", "chunk_index must be -1 or a non-negative integer"),
        ("count_mismatch", "included_anchor_count disagrees"),
    ],
)
def test_standalone_cli_revalidates_rows_when_summary_claims_zero_errors(
    monkeypatch, tmp_path, mutation, message,
):
    candidate = _record([_result("q", [_anchor(5)])])
    assert candidate["anchor_evidence_errors"] == 0
    if mutation == "missing":
        del candidate["results"][0]["included_anchors"]
    elif mutation == "malformed":
        candidate["results"][0]["included_anchors"][0]["chunk_index"] = "5"
    elif mutation == "count_mismatch":
        candidate["results"][0]["included_anchor_count"] = 2

    queries_file = tmp_path / f"queries-{mutation}.json"
    output_dir = tmp_path / f"candidate-{mutation}"
    queries_file.write_text("[]", encoding="utf-8")
    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.run_tier2_eval",
        lambda *args, **kwargs: copy.deepcopy(candidate),
    )

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        ["--queries", str(queries_file), "--output-dir", str(output_dir)],
        obj={"json": True},
    )

    assert invocation.exit_code != 0
    assert message in invocation.output
    assert not (output_dir / "tier2-eval.json").exists()


def test_cli_fails_nonzero_without_serializing_an_incompatible_comparison(
    monkeypatch, tmp_path,
):
    baseline = _record([_result("q", [_anchor(5)])])
    candidate = _record([_result("q", [_anchor(5)])], model="different-model")
    queries_file = tmp_path / "queries.json"
    baseline_file = tmp_path / "baseline.json"
    output_dir = tmp_path / "candidate"
    queries_file.write_text("[]", encoding="utf-8")
    baseline_file.write_text(json.dumps(baseline), encoding="utf-8")

    monkeypatch.setattr("jseval.agent_retrieval_eval.load_queries", lambda _path: [])
    monkeypatch.setattr(
        "jseval.agent_retrieval_eval.run_tier2_eval",
        lambda *args, **kwargs: copy.deepcopy(candidate),
    )

    invocation = CliRunner().invoke(
        cmd_tier2_eval,
        [
            "--queries", str(queries_file),
            "--baseline-results", str(baseline_file),
            "--output-dir", str(output_dir),
        ],
        obj={"json": True},
    )

    assert invocation.exit_code != 0
    assert "comparison is incompatible" in invocation.output
    assert not (output_dir / "tier2-eval.json").exists()
