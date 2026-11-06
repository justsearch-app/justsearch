"""Offline parts of the real-use query writing (S30): families, gates, prompts, the model call."""
import json
import re
import subprocess
from pathlib import Path

import pytest

from jseval.quality import real_use_queries as ruq


SUBSCRIPTION = {"loggedIn": True, "authMethod": "claude.ai", "apiProvider": "firstParty",
                "subscriptionType": "max"}
AUTH_OVERRIDES = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
                  "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
                  "AWS_BEARER_TOKEN_BEDROCK", "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_PROFILE",
                  "ANTHROPIC_FEDERATION_RULE_ID", "ANTHROPIC_FOUNDRY_API_KEY",
                  "CLAUDE_CODE_USE_ANTHROPIC_AWS", "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD",
                  "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR", "CLAUDE_CODE_API_BASE_URL")


@pytest.fixture
def subscription_env(monkeypatch, tmp_path):
    import os
    for name in os.environ.keys() | set(AUTH_OVERRIDES):
        if name.startswith(("ANTHROPIC_", "CLAUDE_CODE_USE_")) or name in AUTH_OVERRIDES:
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(ruq, "WORK", tmp_path / "work")


def test_families_from_recipe_metadata():
    assert ruq.eurlex_act("32013R0099") == ruq.eurlex_act("02013R0099-20200101") == "2013R0099"
    assert ruq.eurlex_act("not-celex") is None
    assert ruq.thread_key("RE: Fw:  Ontario  Update") == ruq.thread_key("ontario update")
    assert ruq.series_key("Data/x/Abfall Hamburg 2018 (Q II 10 - j 18 HH).xlsx") == \
        ruq.series_key("Data/x/Abfall Hamburg 2019 (Q II 10 - j 19 HH).xlsx")
    runs = ruq.drucksache_runs({"a": "18/162", "b": "18/163", "c": "18/164", "d": "19/165",
                                "e": "19/1", "f": "19/2"})
    assert runs == [["a", "b", "c"], ["e", "f"]]
    rows = {"x": {"source": "enron", "title": "RE: Budget", "path": "Mail/RE Budget.eml"},
            "y": {"source": "enron", "title": "Budget", "path": "Mail/Budget.eml"},
            "z": {"source": "enron", "title": "Other", "path": "Mail/Other.eml"}}
    fams = ruq.families(rows)
    assert fams == [{"kind": "enron-thread", "members": ["x", "y"], "evidence": {"key": "budget"}}]


def test_near_duplicates_are_exact_or_jaccard_verified():
    base = " ".join(f"wort{i}" for i in range(400))
    texts = {"a": base, "b": base.replace("wort7 ", "anders ", 1), "c": base + " ",
             "d": " ".join(f"other{i}" for i in range(400))}
    found = ruq.near_duplicates(texts)
    kinds = {c["kind"]: c["members"] for c in found}
    assert kinds["content-exact"] == ["a", "c"]
    assert kinds["near-duplicate"] == ["a", "b", "c"]
    assert all("d" not in c["members"] for c in found)


def test_verbatim_leak_uses_folded_three_token_ngrams():
    text = "Die Verordnung über Fangmöglichkeiten für EU-Schiffe im Jahr 2013"
    assert ruq.leak_ngrams("fangmöglichkeiten FÜR eu schiffe", text) == [
        "fangmöglichkeiten für eu", "für eu schiffe"]
    assert ruq.leak_ngrams("Fischerei Quoten 2013", text) == []
    assert ruq.leak_ngrams("zwei Wörter", text) == []


def test_prompts_render_and_keep_copy_rule_by_level():
    row = {"path": "EU/Regulations/Deutsch/Verordnung.html", "format": "html"}
    q = {"qid": "ki-001", "kind": "known-item", "level": "vague", "focus": "deep", "offset": 6,
         "traits": ["misspelling", "other-language"], "language": "de"}
    text = "Kopf. " + "Inhalt " * 1000
    prompt = ruq.known_item_prompt(q, row, text)
    assert "Do not copy any three consecutive words" in prompt and "English" in prompt
    assert '{"query": "..."}' in prompt and "middle or end" in prompt and "Kopf" not in prompt
    exact = ruq.known_item_prompt({**q, "level": "exact", "focus": "start", "offset": 0}, row, text)
    assert "Titles and numbers may be typed" in exact and "Kopf" in exact
    topical = ruq.topical_prompt({"members": ["a", "b"], "language": "en"},
                                 {"a": {"path": "a.pdf"}, "b": {"path": "b.pdf"}},
                                 {"a": "alpha text", "b": "beta text"})
    assert "File 2: b.pdf" in topical and '{"query": null}' in topical


def test_model_call_is_tool_less_and_session_less(monkeypatch, subscription_env):
    seen = []

    def fake_run(cmd, **kw):
        seen.append((cmd, kw))
        if "auth" in cmd:
            return subprocess.CompletedProcess(cmd, 0, json.dumps(SUBSCRIPTION), "")
        return subprocess.CompletedProcess(cmd, 0, json.dumps(
            {"is_error": False, "result": 'Sure: {"query": "Fangquoten Ostsee"}'}), "")
    monkeypatch.setattr(ruq.subprocess, "run", fake_run)
    reply, _ = ruq.ask_claude("prompt")
    assert reply == {"query": "Fangquoten Ostsee"}
    assert len(seen) == 2 and "auth" in seen[0][0] and "status" in seen[0][0]
    cmd, kw = seen[1]
    assert cmd[:3] == ["claude", "-p", "prompt"] and cmd[cmd.index("--model") + 1] == ruq.MODEL
    assert cmd[cmd.index("--tools") + 1] == "" and "--strict-mcp-config" in cmd
    assert "--no-session-persistence" in cmd and "bypassPermissions" not in cmd
    assert kw["stdin"] is subprocess.DEVNULL
    assert seen[0][1]["env"] == kw["env"]
    assert not set(AUTH_OVERRIDES) & kw["env"].keys()
    assert Path(kw["cwd"]).is_relative_to(ruq.WORK)
    assert Path(kw["env"]["CLAUDE_CODE_DEBUG_LOGS_DIR"]).is_relative_to(ruq.WORK)
    with pytest.raises(ValueError, match="claude error"):
        ruq.parse_reply(json.dumps({"is_error": True, "result": "usage limit reached"}))


@pytest.mark.parametrize("name", AUTH_OVERRIDES)
@pytest.mark.parametrize("value", ["SECRET_OVERRIDE", ""])
def test_api_or_provider_override_refuses_before_any_subprocess(
        monkeypatch, subscription_env, name, value):
    monkeypatch.setenv(name, value)
    calls = []
    def fake_run(cmd, **kw):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, json.dumps({"result": '{"query": "a b"}'}), "")
    monkeypatch.setattr(ruq.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="subscription") as error:
        ruq.ask_claude("PRIVATE_PROMPT")
    assert not calls
    assert "SECRET_OVERRIDE" not in str(error.value) and "PRIVATE_PROMPT" not in str(error.value)


@pytest.mark.parametrize("status", [
    {**SUBSCRIPTION, "loggedIn": False}, {**SUBSCRIPTION, "authMethod": "api_key"},
    {**SUBSCRIPTION, "authMethod": "api_key_helper"}, {**SUBSCRIPTION, "authMethod": "oauth_token"},
    {**SUBSCRIPTION, "apiProvider": "bedrock"}, {**SUBSCRIPTION, "subscriptionType": None},
    {**SUBSCRIPTION, "subscriptionType": "unknown"}, {},
])
def test_non_subscription_status_never_starts_generation(monkeypatch, subscription_env, status):
    calls = []
    def fake_run(cmd, **kw):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, json.dumps(status), "")
    monkeypatch.setattr(ruq.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="subscription"):
        ruq.ask_claude("PRIVATE_PROMPT")
    assert len(calls) == 1 and "-p" not in calls[0]


@pytest.mark.parametrize("failure", ["timeout", "exit", "missing", "json"])
def test_unavailable_auth_status_fails_closed(monkeypatch, subscription_env, failure):
    secret = "PRIVATE_AUTH_DIAGNOSTIC"
    calls = []
    def fake_run(cmd, **kw):
        calls.append(cmd)
        if failure == "timeout":
            raise subprocess.TimeoutExpired(cmd, 30, output=secret, stderr=secret)
        if failure == "missing":
            raise FileNotFoundError(secret)
        return subprocess.CompletedProcess(cmd, 1 if failure == "exit" else 0, secret, secret)
    monkeypatch.setattr(ruq.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="subscription") as error:
        ruq.ask_claude("PRIVATE_PROMPT")
    assert len(calls) == 1 and "-p" not in calls[0]
    assert secret not in str(error.value)


@pytest.mark.parametrize("failure", ["timeout", "exit", "parse", "runtime"])
def test_generation_errors_never_disclose_subprocess_arguments_or_output(
        monkeypatch, tmp_path, failure):
    secret = "PRIVATE_DOCUMENT_MARKER"
    cmd = ["claude", "-p", secret]
    errors = {"timeout": subprocess.TimeoutExpired(cmd, 180, output=secret, stderr=secret),
              "exit": subprocess.CalledProcessError(7, cmd, output=secret, stderr=secret),
              "parse": ValueError(secret), "runtime": RuntimeError(secret)}
    monkeypatch.setattr(ruq, "RECORD", tmp_path / "record")
    monkeypatch.setattr(ruq, "WORK", tmp_path / "work")
    monkeypatch.setattr(ruq, "_template", lambda _: "template")
    monkeypatch.setattr(ruq, "known_item_prompt", lambda *a: secret)
    def fail(_):
        raise errors[failure]
    monkeypatch.setattr(ruq, "ask_claude", fail)
    q = {"qid": "ki-1", "kind": "known-item", "target": "t"}
    assert ruq.generate_one(q, {"t": {}}, {"t": secret}, max_attempts=1) is None
    for path in ruq.RECORD.rglob("*"):
        if path.is_file():
            assert secret not in path.read_text()
    error = ruq._jsonl(ruq.RECORD / "generation.jsonl")[0]["error"]
    assert set(error) == {"type", "returncode", "elapsed_seconds", "reason"}
    assert error["type"] == type(errors[failure]).__name__
    assert error["returncode"] == (7 if failure == "exit" else None)
    assert error["elapsed_seconds"] >= 0
    assert error["reason"] == "generation failed"


@pytest.fixture
def generation_env(monkeypatch, tmp_path):
    monkeypatch.setattr(ruq, "RECORD", tmp_path / "record")
    monkeypatch.setattr(ruq, "WORK", tmp_path / "work")
    monkeypatch.setattr(ruq, "_template", lambda _: "template")
    monkeypatch.setattr(ruq, "known_item_prompt", lambda *a: "prompt")
    monkeypatch.setattr(ruq, "topical_prompt", lambda *a: "prompt")
    return {"qid": "ki-1", "kind": "known-item", "level": "partial", "target": "t"}


@pytest.mark.parametrize("kind", ["known-item", "topical"])
@pytest.mark.parametrize("query", [None, "ocean quotas"])
def test_generation_keeps_extra_reply_fields_only_in_work(monkeypatch, generation_env, kind, query):
    marker = "PRIVATE_REPLY_MARKER"
    q = {**generation_env, "kind": kind, "members": ["t"]}
    # The topic carries document text (an excerpt) in the topical case: it must stay in WORK.
    reply = {"query": query, "diagnostic": marker, "note": marker,
             "topic": f"shared ocean topic {marker} " + "excerpt words " * 40}
    monkeypatch.setattr(ruq, "ask_claude", lambda _: (reply, json.dumps(reply)))
    result = ruq.generate_one(q, {"t": {}}, {"t": "unrelated text"}, max_attempts=1)
    committed = (ruq.RECORD / "generation.jsonl").read_text()
    assert marker not in committed
    row = json.loads(committed)
    if query is None:
        assert result is None and "output" not in row and "query" not in row
    else:
        assert row["output"] == {"query": query}
    assert marker in (ruq.WORK / "generation.jsonl").read_text()


@pytest.mark.parametrize("query,reason", [
    ("PRIVATE_REJECTED_MARKER one two three four five", "6 words"),
    ("PRIVATE_REJECTED_MARKER copied sequence", "verbatim leak"),
])
def test_generation_keeps_rejected_queries_and_retry_feedback_only_in_work(
        monkeypatch, generation_env, query, reason):
    replies = iter([{"query": query}, {"query": "ocean quotas"}])
    monkeypatch.setattr(ruq, "ask_claude", lambda _: (next(replies), "raw reply"))
    result = ruq.generate_one(generation_env, {"t": {}}, {"t": query}, max_attempts=2)
    committed = (ruq.RECORD / "generation.jsonl").read_text()
    assert "PRIVATE_REJECTED_MARKER" not in committed
    rejected, accepted = [json.loads(line) for line in committed.splitlines()]
    assert rejected["rejected"] == reason and not rejected["accepted"]
    assert "query" not in rejected and "output" not in rejected
    assert result["query"] == accepted["query"] == "ocean quotas"
    assert "PRIVATE_REJECTED_MARKER" in (ruq.WORK / "generation.jsonl").read_text()


@pytest.mark.parametrize("query", [17, ["PRIVATE_INVALID_MARKER"], {"excerpt": "PRIVATE_INVALID_MARKER"}])
def test_generation_rejects_non_string_queries(monkeypatch, generation_env, query):
    monkeypatch.setattr(ruq, "ask_claude", lambda _: ({"query": query}, "raw reply"))
    assert ruq.generate_one(generation_env, {"t": {}}, {"t": "text"}, max_attempts=1) is None
    row = ruq._jsonl(ruq.RECORD / "generation.jsonl")[0]
    assert row["rejected"] == "invalid query" and "output" not in row and "query" not in row


@pytest.mark.parametrize("feedback", [
    "Your previous query had 7 words; use 2 to 4.", "PRIVATE_FEEDBACK_MARKER",
])
def test_generation_commits_only_fixed_numeric_feedback(monkeypatch, generation_env, feedback):
    monkeypatch.setattr(ruq, "ask_claude", lambda _: ({"query": "ocean quotas"}, "raw reply"))
    ruq.generate_one(generation_env, {"t": {}}, {"t": "text"}, feedback=feedback, max_attempts=1)
    row = ruq._jsonl(ruq.RECORD / "generation.jsonl")[0]
    assert row["feedback"] == (None if "PRIVATE" in feedback else feedback)
    assert ruq._jsonl(ruq.WORK / "generation.jsonl")[0]["feedback"] == feedback


def test_stored_generation_records_follow_the_committed_allow_list():
    fields = {"at", "qid", "attempt_id", "round", "model", "template", "template_sha256",
              "prompt_sha256", "params", "feedback", "accepted", "output", "query", "gates",
              "rejected", "error"}
    params = {"qid", "kind", "language", "clusters", "focus", "format", "level", "offset",
              "source", "target", "traits", "group", "group_kind", "sources"}
    rows = ruq._jsonl(ruq.RECORD / "generation.jsonl")
    assert rows
    for row in rows:
        assert set(row) <= fields, row["attempt_id"]
        assert set(row["params"]) <= params, row["attempt_id"]
        assert row["feedback"] is None or re.fullmatch(
            r"Your previous query had [0-9]+ words; use [0-9]+ to [0-9]+\.", row["feedback"])
        if row["accepted"]:
            assert set(row["output"]) == {"query"}, row["attempt_id"]
            assert row["output"]["query"] == row["query"]
            assert row["gates"]["verbatim_leak"] == []
        else:
            assert "query" not in row and "output" not in row, row["attempt_id"]
            assert "verbatim_leak" not in row.get("gates", {})
        assert set(row.get("gates", {})) <= {"words", "verbatim_leak", "verbatim_leak_count"}
        if "error" in row:
            assert isinstance(row["error"], dict)
            assert set(row["error"]) <= {"type", "returncode", "elapsed_seconds", "reason"}
            assert row["error"]["reason"] == "generation failed"
        if "rejected" in row:
            assert row["rejected"] in {"no shared topic", "verbatim leak", "invalid query"} or re.fullmatch(
                r"[0-9]+ words", row["rejected"])


def test_chunk_report_and_format_cycle():
    records = [{"doc": "a", "source": "s", "format": "pdf", "chars": 10, "chunks": 2},
               {"doc": "b", "source": "s", "format": "csv", "chars": 0, "chunks": 0}]
    report = ruq.chunk_report(records, engine_chunks=40)
    assert report["debug_chunks_found_capped_50"] == 2 and report["empty_extractions"] == ["b"]
    assert report["by_source_format"]["s/pdf"]["debug_chunks_found_capped_50"]["sum"] == 2
    assert report["by_format"]["pdf"]["engine_chunks_estimated"] == 40
    assert report["by_format"]["csv"]["engine_chunks_estimated"] == 0
    recs = {d: {"format": f} for d, f in (("1", "pdf"), ("2", "pdf"), ("3", "csv"))}
    assert ruq._format_cycle(["1", "2", "3"], recs) == ["3", "1", "2"]


def test_stored_real_use_collection_matches_recipe_and_its_config():
    from jseval.quality import collections, corpus, known_answers
    c, cfg = collections.resolve_collection("real-use")
    report = known_answers.check(c, cfg, require_scored=False)
    rows = {d["id"]: d for d in corpus.load_recipe("real-use")["documents"]}
    excluded = json.loads((ruq.RECORD / "excluded.json").read_text(encoding="utf-8"))["documents"]
    assert len(rows) == 2960 and len(excluded) == 70
    full = ruq.population(rows)
    assert full == set(rows) - {e["doc"] for e in excluded} and len(full) == 2890
    chosen = json.loads((ruq.RECORD / "subset.json").read_text(encoding="utf-8"))
    assert set(c.documents) == set(chosen["kept"]) | set(chosen["sampled"]) <= full
    assert len(c.documents) == chosen["documents"] == 1000
    assert all(rows[d]["path"] == p and rows[d]["sha256"] == c.content[d] for d, p in c.documents.items())
    assert cfg.layout == "as-is" and cfg.witness_formats == ("md",) and cfg.pending_kinds == ()
    assert report.pending == len(collections.pending_queries(c, cfg)) == 0
    scored = collections.scored_collection(c, cfg)
    assert {q.groups["kind"] for q in scored.queries.values()} == {"known-item", "topical"}
    assert all(max(scored.judgments[q].values()) == 2 for q in scored.queries
               if scored.queries[q].groups["kind"] == "known-item")
    assert all(max(scored.judgments[q].values()) == 1 for q in scored.queries
               if scored.queries[q].groups["kind"] == "topical")


def test_in_cluster_allocation_moves_shortfall_to_strata_with_spare_clusters():
    alloc = ruq.allocate_in_cluster({"mail": (30, 2), "acts": (16, 16), "prints": (30, 40)})
    assert alloc["mail"] == 2 and alloc["acts"] <= 16 and alloc["prints"] <= 30
    assert sum(alloc.values()) == 38
    assert ruq.allocate_in_cluster({"none": (10, 0)}) == {"none": 0}


def test_reply_parsing_takes_the_first_object_and_word_bounds_follow_the_level():
    stdout = json.dumps({"is_error": False, "result": '{"query": "a b"}\n\n{"query": "other"}'})
    assert ruq.parse_reply(stdout) == {"query": "a b"}
    assert ruq.word_bounds({"kind": "known-item", "level": "exact"}) == (1, 5)
    assert ruq.word_bounds({"kind": "known-item", "level": "vague"}) == (2, 4)
    assert ruq.word_bounds({"kind": "topical"}) == (2, 5)
    q = {"kind": "known-item", "level": "partial", "target": "t"}
    assert ruq.meets_gates(q, {"query": "Fischerei Quoten"}, {"t": "fischerei quoten ostsee"})
    assert not ruq.meets_gates(q, {"query": "fischerei quoten ostsee"}, {"t": "fischerei quoten ostsee"})
    assert not ruq.meets_gates(q, {"query": "one two three four five"}, {"t": "x"})


def test_triviality_is_measured_never_dropped_or_regenerated(monkeypatch, tmp_path):
    # S30 (amended): keyword search alone ranking the target first is recorded per query and per
    # recall level; no query is dropped or rewritten for it. Gate and duplicate drops stay.
    def q(qid, kind="known-item", level="partial"):
        return {"qid": qid, "kind": kind, "level": level, "target": f"d-{qid}", "source": "enron",
                "format": "eml", "language": "en", "clusters": [], "focus": "start",
                "members": [f"d-{qid}"]}

    plan = [q("ki-1"), q("ki-2"), q("ki-3", level="vague"), q("ki-4", level="exact"), q("ki-5"),
            q("tp-1", kind="topical"), q("tp-2", kind="topical")]
    accepted = {"ki-1": "budget memo", "ki-2": "travel claim", "ki-3": "boat party", "ki-4": "x y z",
                "tp-1": "shared topic", "tp-2": "Shared Topic"}  # ki-5 never met the gates
    monkeypatch.setattr(ruq, "RECORD", tmp_path)
    monkeypatch.setattr(ruq, "load_texts", lambda: ({}, {f"d-{x['qid']}": "other words" for x in plan}))
    monkeypatch.setattr(ruq, "_recipe_rows", dict)
    monkeypatch.setattr(ruq, "load_plan", lambda documents: plan)
    monkeypatch.setattr(ruq, "_accepted", lambda: {k: {"query": v, "gates": {"verbatim_leak": [],
                                                       "words": len(v.split())}} for k, v in accepted.items()})
    monkeypatch.setattr(ruq, "distinguishability", lambda *a, **k: {})
    monkeypatch.setattr(ruq, "lexical_ranks", lambda handle, client, items, stages: {
        qid: 1 for qid, _, _ in items})  # every known-item target ranks first

    def regenerate(*a, **k):
        raise AssertionError("the gate regenerated a query")

    monkeypatch.setattr(ruq, "generate_one", regenerate)
    c = type("C", (), {"documents": {}})()
    report = ruq._gate(c, "http://127.0.0.1:9", _Handle())
    assert set(report["queries"]) == {"ki-1", "ki-2", "ki-3", "ki-4", "tp-1"}
    assert {k: d["reason"] for k, d in report["dropped"].items()} == {"ki-5": "gates", "tp-2": "duplicate"}
    assert all(report["queries"][k]["lexical_rank"] == 1 for k in ("ki-1", "ki-2", "ki-3", "ki-4"))
    assert report["summary"]["triviality"] == {
        "exact": {"queries": 1, "rank1": 1, "rank1_share": 1.0},
        "partial": {"queries": 2, "rank1": 2, "rank1_share": 1.0},
        "vague": {"queries": 1, "rank1": 1, "rank1_share": 1.0}}
    assert "triviality_drop_seed" not in report["summary"]
    for name in ("triviality_drops", "triviality_feedback", "rare_words", "TRIVIALITY_DROP_SEED"):
        assert not hasattr(ruq, name), name


def test_plan_is_scoped_to_the_documents_the_collection_still_holds():
    planned = [{"qid": "ki-1", "kind": "known-item", "target": "a"},
               {"qid": "ki-2", "kind": "known-item", "target": "gone"},
               {"qid": "tp-1", "kind": "topical", "members": ["a", "b", "gone"]},
               {"qid": "tp-2", "kind": "topical", "members": ["a", "gone"]}]
    scoped = ruq.in_collection(planned, {"a", "b"})
    assert [q["qid"] for q in scoped] == ["ki-1", "tp-1"]
    assert scoped[1]["members"] == ["a", "b"]  # a topical query never rests on one document
    assert planned[2]["members"] == ["a", "b", "gone"]  # the stored plan is not rewritten


def test_subset_holds_every_target_its_copies_clusters_and_topical_groups():
    from jseval.quality import collections, corpus
    c, cfg = collections.resolve_collection("real-use")
    rows = {d["id"]: d for d in corpus.load_recipe("real-use")["documents"]}
    full = ruq.population(rows)
    planned = {q["qid"]: q for q in json.loads((ruq.RECORD / "plan.json").read_text(encoding="utf-8"))["queries"]}
    clusters = ruq.load_clusters()
    by_id = {cl["id"]: cl for cl in clusters["clusters"] + clusters["topics"]}
    docs = set(c.documents)
    for qid, text in c.queries.items():
        q = planned[qid]
        if text.groups["kind"] == "known-item":
            assert q["target"] in docs and c.judgments[qid][q["target"]] == 2
            copies = {d for d in full if rows[d]["sha256"] == rows[q["target"]]["sha256"]}
            members = {d for cid in q["clusters"] for d in by_id[cid]["members"]} & full
            assert copies | members <= docs, qid
        else:
            assert set(q["members"]) & full <= docs, qid
    chosen = json.loads((ruq.RECORD / "subset.json").read_text(encoding="utf-8"))
    assert not set(chosen["sampled"]) & set(chosen["ocr_routed_in_population"])  # no extra OCR


def _rows(spec):
    return {doc: {"source": s, "format": f, "language": l} for doc, (s, f, l) in spec.items()}


def test_select_subset_keeps_query_sets_and_fills_toward_full_proportions(monkeypatch):
    spec = {f"m{i}": ("mail", "eml", "en") for i in range(60)}
    spec |= {f"p{i}": ("prints", "pdf", "de") for i in range(40)}
    rows = _rows(spec)
    docs = set(rows)
    content = {d: d for d in docs} | {"m1": "same", "m2": "same"}  # m2: byte copy of target m1
    clusters = {"clusters": [{"id": "cl-1", "members": ["p0", "p1", "p2", "gone"]}],
                "topics": [{"id": "tg-1", "members": ["p5", "p6"]}]}
    planned = [{"qid": "ki-1", "kind": "known-item", "target": "m1", "clusters": []},
               {"qid": "ki-2", "kind": "known-item", "target": "p0", "clusters": ["cl-1"]},
               {"qid": "tp-1", "kind": "topical", "members": ["p5", "p6", "gone"]}]
    ocr = {f"p{i}" for i in range(10, 40)} | {"p6"}
    out = ruq.select_subset(rows, docs, content, planned, clusters, ocr, size=20, seed=7)
    chosen = set(out["kept"]) | set(out["sampled"])
    assert out["kept"]["m2"] == ["byte-duplicate:ki-1"] and out["kept"]["p6"] == ["topical:tp-1"]
    assert {"m1", "m2", "p0", "p1", "p2", "p5", "p6"} <= chosen and "gone" not in chosen
    assert len(chosen) == 20 and not set(out["sampled"]) & ocr  # p6 is kept although OCR'd
    # Full set is 60% mail: mail is filled toward 12 of 20; prints run out of non-OCR files.
    assert out["strata"]["mail/eml/en"]["subset"] == 12
    assert out["strata"]["prints/pdf/de"]["subset"] == 8
    assert out == ruq.select_subset(rows, docs, content, planned, clusters, ocr, size=20, seed=7)
    assert out["sampled"] != ruq.select_subset(rows, docs, content, planned, clusters, ocr,
                                               size=20, seed=8)["sampled"]
    monkeypatch.setattr(ruq, "KEPT_CAP", 6)  # seven kept files
    with pytest.raises(ValueError, match="topical groups need caps"):
        ruq.select_subset(rows, docs, content, planned, clusters, ocr, size=20)


def test_select_subset_samples_as_many_others_as_targets_before_the_proportional_fill():
    spec = {f"m{i}": ("mail", "eml", "en") for i in range(90)}
    spec |= {f"w{i}": ("wiki", "md", "en") for i in range(10)}
    rows = _rows(spec)
    docs = set(rows)
    planned = [{"qid": f"ki-{i}", "kind": "known-item", "target": f"w{i}", "clusters": []} for i in range(3)]
    out = ruq.select_subset(rows, docs, {d: d for d in docs}, planned, {"clusters": [], "topics": []},
                            set(), size=20, seed=7)
    # The wiki share of 20 is 2, below its three targets; it still gets three other files.
    assert out["strata"]["wiki/md/en"] == {**out["strata"]["wiki/md/en"], "kept": 3, "targets": 3,
                                           "sampled": 3, "subset": 6}
    assert out["strata"]["mail/eml/en"]["sampled"] == 14 and out["documents"] == 20
    assert not {"w0", "w1", "w2"} & set(out["sampled"])


def test_stage_timing_splits_the_build_at_stage_completion_and_counts_pauses():
    def poll(at, **kw):
        base = {"queue": 0, "indexedDocuments": 2, "embeddingPendingCount": 0,
                "chunkEmbeddingPendingCount": 0, "pendingNerCount": 0, "spladePendingCount": 0}
        return {"at": at, **base, **kw}
    rows = [{"at": 0, "start": "fresh"}, {"at": 15, "starting": True},
            poll(30, queue=2, indexedDocuments=0, embeddingPendingCount=1),
            {"at": 45, "paused": True}, {"at": 60, "paused": True},
            poll(75, embeddingPendingCount=1, batchTiming={"totalMs": {"embed": 5}}),
            poll(90, pendingNerCount=3), {"at": 105, "paused": True},
            poll(120, batchTiming={"totalMs": {"embed": 9, "ner": 4}}), {"at": 130, "done": {}}]
    t = ruq.stage_timing(rows, 2)
    assert t["total_seconds"] == 130 and t["paused_seconds_approx"] == 3 * ruq.POLL_SECONDS
    assert {k: v["seconds"] for k, v in t["stages"].items()} == {
        "startup": 30, "extraction_ocr": 45, "embedding": 15, "other_enrichment": 30, "confirmation": 10}
    assert t["stages"]["extraction_ocr"]["paused_seconds_approx"] == 2 * ruq.POLL_SECONDS
    assert t["incomplete"] == [] and t["engine_batch_ms"] == {"embed": 9, "ner": 4}
    # A disabled document SPLADE stage keeps its pending count; an enabled one must drain.
    disabled = [{**r, "spladePendingCount": 2, "spladeEnabled": False} if "queue" in r else r for r in rows]
    assert ruq.stage_timing(disabled, 2)["incomplete"] == []
    enabled = [{**r, "spladePendingCount": 2, "spladeEnabled": True} if "queue" in r else r for r in rows]
    assert ruq.stage_timing(enabled, 2)["incomplete"] == ["other_enrichment"]


class _SearchClient:
    """Records each search request and answers with one hit and the given metadata."""

    def __init__(self, stages, query_understanding=None):
        self.requests, self.stages = [], stages
        self.query_understanding = query_understanding

    def post(self, path, json):
        self.requests.append(json)
        body = {"results": [{"id": "doc-a"}], "searchTrace": {"stages": [
            {"id": stage, "status": status} for stage, status in self.stages.items()]}}
        if self.query_understanding is not None:
            body["queryUnderstanding"] = self.query_understanding
        return type("Response", (), {"raise_for_status": lambda self: None, "json": lambda self: body})()


class _Handle:
    def ranked_doc_ids(self, locations):
        return list(locations)


KEYWORD_TRACE = {"sparse-retrieval": "executed", "dense-retrieval": "disabled", "fusion": "skipped",
                 "lambdamart": "skipped", "cross-encoder": "skipped", "expansion": "skipped"}


def test_triviality_ranks_with_keyword_search_alone():
    # S30: the reranker is a model stage, not keyword search, and it is not deterministic
    # (it can run, be skipped or time out per request). The gate turns it and every other
    # model stage off explicitly instead of inheriting the engine's text preset.
    client = _SearchClient(KEYWORD_TRACE)
    assert ruq.lexical_ranks(_Handle(), client, [("ki-1", "budget memo", "doc-a")]) == {"ki-1": 1}
    pipeline = client.requests[0].get("pipeline") or {}
    assert pipeline.get("sparseEnabled") is True, client.requests[0]
    assert not any(pipeline.get(k, True) for k in ("denseEnabled", "spladeEnabled", "lambdamartEnabled",
                                                   "crossEncoderEnabled", "expansionEnabled", "freshnessEnabled"))
    assert pipeline["fusionAlgorithm"] == "none" and pipeline["crossEncoderWindow"] == 0


def test_triviality_records_the_trace_stages_it_ranked_with():
    stages = __import__("collections").Counter()
    ruq.lexical_ranks(_Handle(), _SearchClient(KEYWORD_TRACE), [("ki-1", "budget memo", "doc-a")], stages)
    assert stages["cross-encoder:skipped"] == 1 and stages["sparse-retrieval:executed"] == 1


@pytest.mark.parametrize("stage", ["cross-encoder", "lambdamart", "dense-retrieval", "expansion"])
def test_triviality_refuses_a_rank_a_model_stage_shaped(stage):
    client = _SearchClient({**KEYWORD_TRACE, stage: "executed"})
    with pytest.raises(ValueError, match="not keyword search alone"):
        ruq.lexical_ranks(_Handle(), client, [("ki-1", "budget memo", "doc-a")])


def test_triviality_refuses_query_understanding_boosts():
    from jsonschema import validate
    qu = {"appliedBoosts": {"entity_organizations": ["Acme"]},
          "expectedAnswerType": "LOOKUP", "latencyMs": 20}
    schema = json.loads((ruq.ix.REPO_ROOT / "SSOT/schemas/knowledge-search-response.v1.json").read_text())
    validate(qu, schema["properties"]["queryUnderstanding"])
    assert set(qu) <= schema["properties"]["queryUnderstanding"]["properties"].keys()
    client = _SearchClient({**KEYWORD_TRACE, "query-understanding": "executed"},
                           qu)
    with pytest.raises(ValueError, match="query-understanding boosts"):
        ruq.lexical_ranks(_Handle(), client, [("ki-1", "budget memo", "doc-a")])


@pytest.mark.parametrize("query_understanding", [None, {"expectedAnswerType": "LOOKUP"},
                                                {"appliedBoosts": {}, "expectedAnswerType": "LOOKUP"}])
def test_triviality_accepts_query_understanding_without_boosts(query_understanding):
    # Classification can execute this trace stage without an LLM shaping the rank.
    client = _SearchClient({**KEYWORD_TRACE, "query-understanding": "executed"}, query_understanding)
    assert ruq.lexical_ranks(_Handle(), client, [("ki-1", "budget memo", "doc-a")]) == {"ki-1": 1}


@pytest.mark.parametrize("option", ["--url", "--root"])
def test_gate_has_no_external_engine_path(option, capsys):
    with pytest.raises(SystemExit) as refused:
        ruq.main(["gate", option, "external"])
    assert refused.value.code == 2
    assert "unrecognized arguments" in capsys.readouterr().err


@pytest.mark.parametrize("env", [
    {"JUSTSEARCH_QU_ENABLED": "true", "JUSTSEARCH_LLM_ENABLED": "false"},
    {"JUSTSEARCH_QU_ENABLED": "false", "JUSTSEARCH_LLM_ENABLED": "true"},
    {},  # LLM defaults to enabled in Java; absence is insufficient.
    *[{"JUSTSEARCH_LLM_ENABLED": "false", name: value}
      for name in ("JAVA_OPTS", "UI_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
      for value in ("-Djustsearch.qu.enabled=true", "-Djustsearch.llm.enabled=on", "@hidden-options",
                    # The launcher keeps the backslash and splits at the space; shlex did not.
                    r"-Dpadding=x\ -Djustsearch.qu.enabled=true",
                    '"-Djustsearch.qu.enabled=true"', "-XX:VMOptionsFile=/tmp/opts",
                    # The JVM removes embedded quotes: no contiguous property name needed.
                    '-Djust""search.qu.enabled=true -Djust""search.llm.enabled=true',
                    "-Djava.awt.headless=true -Dother=1")],
])
def test_gate_refuses_before_start_or_date_only_extraction(env, monkeypatch):
    from jseval.quality import run
    from contextlib import nullcontext
    from types import SimpleNamespace

    def forbidden(*args, **kwargs):
        # QueryUnderstandingService:172 returns before inference when the captured search
        # config is disabled (isAvailable:223-227). This blocks date-only output too, even
        # though buildQueryUnderstanding:1486-1500 cannot report its publication range.
        raise AssertionError("engine started or a search could extract date-only boosts")

    monkeypatch.setattr(ruq, "_collection", lambda: (object(), {}))
    monkeypatch.setattr(run, "require_exclusive", lambda root: None)
    monkeypatch.setattr(ruq.ix, "prepare_index", lambda c, cfg, *, prepared_only:
                        nullcontext(SimpleNamespace(env=env, wait=forbidden)))
    monkeypatch.setattr(ruq, "_gate", forbidden)
    with pytest.raises(ValueError, match="keyword measurement"):
        ruq.gate(1)


@pytest.mark.parametrize("qu", [None, "false"])
def test_gate_starts_only_the_prepared_engine_with_qu_unavailable(qu, monkeypatch, tmp_path):
    from contextlib import nullcontext
    from types import SimpleNamespace
    from jseval.quality import run
    env = {"JUSTSEARCH_LLM_ENABLED": "false", "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"}
    if qu is not None:
        env["JUSTSEARCH_QU_ENABLED"] = qu
    events = []
    handle = SimpleNamespace(env=env, base_url="http://owned-engine", index_state={"generation": 7},
                             wait=lambda *, prepared_only: events.append(("start", prepared_only)))
    c = object()

    def prepare(collection, config, *, prepared_only):
        assert collection is c and prepared_only
        events.append(("prepare", prepared_only))
        return nullcontext(handle)

    def measure(collection, url, actual):
        assert collection is c and actual is handle and url == handle.base_url
        assert events == [("prepare", True), ("start", True)]
        return {"summary": {}}

    monkeypatch.setattr(ruq, "RECORD", tmp_path)
    monkeypatch.setattr(ruq, "_collection", lambda: (c, {}))
    monkeypatch.setattr(run, "require_exclusive", lambda root: None)
    monkeypatch.setattr(ruq.ix, "prepare_index", prepare)
    monkeypatch.setattr(ruq, "_gate", measure)
    assert ruq.gate(1) == 0
    assert json.loads((tmp_path / "gates.json").read_text())["generation"] == 7


def test_prepare_applies_the_collection_configs_deadline_and_keeps_the_default_elsewhere(tmp_path, monkeypatch):
    from click.testing import CliRunner
    from jseval.cli import main
    from jseval.quality import collections, index
    from tests.quality.helpers import make_collection
    seen = []

    class Handle:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def wait(self):
            pass

    monkeypatch.setattr(index, "prepare_index", lambda c, cfg: seen.append((c.name, cfg)) or Handle())
    out = CliRunner().invoke(main, ["quality", "prepare", "real-use"])
    assert out.exit_code == 0, out.output
    assert seen[-1][0] == "real-use" and seen[-1][1].timeout_sec == 7200
    raw = json.loads((collections.CONFIG_DIR / "real-use.json").read_text(encoding="utf-8"))
    assert raw["prepare_timeout_sec"] == 7200
    # A collection whose config names no deadline keeps IndexConfig's 1,800 s.
    c = make_collection()
    folder = collections.write_collection(c, tmp_path / "collection")
    (folder / "quality.config.json").write_text(json.dumps({
        "schema": collections.CONFIG_SCHEMA, "collection": c.name, "language": c.language,
        "source": {"kind": "synthetic"}, "judgments": "official", "expected": c.counts()}))
    out = CliRunner().invoke(main, ["quality", "prepare", str(folder)])
    assert out.exit_code == 0, out.output
    assert seen[-1][1].timeout_sec == index.IndexConfig().timeout_sec == 1800
    others = [p.stem for p in collections.CONFIG_DIR.glob("*.json") if p.stem != "real-use"]
    assert others and not any("prepare_timeout_sec" in json.loads(p.read_text(encoding="utf-8"))
                              for p in collections.CONFIG_DIR.glob("*.json") if p.stem != "real-use")


def test_subset_refuses_to_replace_an_existing_selection(tmp_path, monkeypatch, capsys):
    # Reselecting after queries changed would silently change the corpus (and its index key).
    monkeypatch.setattr(ruq, "RECORD", tmp_path)
    (tmp_path / "subset.json").write_text('{"documents": 1000}\n')

    def reselect():
        raise AssertionError("subset reselected over an existing subset.json")

    monkeypatch.setattr(ruq, "_collection", reselect)
    assert ruq.subset(1000) == 2
    assert (tmp_path / "subset.json").read_text() == '{"documents": 1000}\n'
    assert "subset refused" in capsys.readouterr().err


def test_duplicate_query_texts_are_found_case_folded_and_keep_the_first_id():
    done = {"tq-2": {"query": "Ontario Update"}, "tq-1": {"query": "ontario update"},
            "tq-3": {"query": "other"}}
    assert ruq.duplicates(done) == {"tq-2": "tq-1"}


def test_gate_records_explain_every_planned_query_the_collection_lacks():
    # Every planned query of the stored collection's documents is either stored or recorded as
    # dropped in gates.json with its reason; repeated texts among them carry "duplicate".
    from jseval.quality import collections
    c, _ = collections.resolve_collection("real-use")
    planned = {q["qid"] for q in ruq.load_plan(c.documents)}
    record = json.loads((ruq.RECORD / "gates.json").read_text(encoding="utf-8"))
    dropped = record["dropped"]
    assert planned - set(c.queries) == set(dropped), sorted((planned - set(c.queries)) ^ set(dropped))
    assert not set(dropped) & set(c.queries)
    assert {d["reason"] for d in dropped.values()} <= {"gates", "duplicate"}  # triviality is never a drop
    for qid, d in dropped.items():
        if d["reason"] == "duplicate":
            assert d["duplicate_of"] in c.queries
            assert d["query"].casefold() == c.queries[d["duplicate_of"]].text.casefold()
    assert record["summary"]["dropped"] == dict(sorted(
        __import__("collections").Counter(d["reason"] for d in dropped.values()).items()))
    # Triviality is recorded, not gated: every stored known-item query carries its keyword rank,
    # and the per-level shares are counted from those ranks.
    known = {q for q in c.queries if c.queries[q].groups["kind"] == "known-item"}
    assert len(known) == 187 and len(set(c.queries) - known) == 122
    assert set(record["queries"]) == set(c.queries)
    assert all(record["queries"][q]["query"] == c.queries[q].text for q in c.queries)
    assert all(record["queries"][q]["level"] == c.queries[q].groups["level"] for q in known)
    for level in ruq.LEVELS:
        ranks = [record["queries"][q]["lexical_rank"] for q in known if c.queries[q].groups["level"] == level]
        share = record["summary"]["triviality"][level]
        assert share == {"queries": len(ranks), "rank1": ranks.count(1),
                         "rank1_share": round(ranks.count(1) / len(ranks), 3)}
    assert {lv: (v["rank1"], v["queries"]) for lv, v in record["summary"]["triviality"].items()} == {
        "exact": (60, 65), "partial": (44, 60), "vague": (28, 62)}
    # The stored ranks come from keyword search alone; the trace never shows a model stage.
    assert record["ranking"]["pipeline"] == ruq.KEYWORD_PIPELINE
    assert not any(k.split(":")[0] in ruq.KEYWORD_OFF and k.endswith(":executed")
                   for k in record["ranking"]["trace_stages"])


# P5c: deferred P5b items.
RECIPES = Path(ruq.__file__).with_name("recipes")


def test_a_refused_generation_run_appends_nothing_to_the_committed_record(monkeypatch, tmp_path):
    monkeypatch.setattr(ruq, "RECORD", tmp_path / "record")
    monkeypatch.setattr(ruq, "WORK", tmp_path / "work")
    monkeypatch.setattr(ruq, "_template", lambda _: "template")
    monkeypatch.setattr(ruq, "known_item_prompt", lambda *a: "prompt")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "x")
    q = {"qid": "ki-1", "kind": "known-item", "level": "partial", "target": "t"}
    with pytest.raises(ruq.SubscriptionRequired):
        ruq.generate_one(q, {"t": {}}, {"t": "text"}, max_attempts=1)
    assert not (ruq.RECORD / "generation.jsonl").exists()
    assert ruq._jsonl(ruq.WORK / "generation.jsonl")[0]["error"]["type"] == "SubscriptionRequired"


def test_the_second_build_note_names_its_files_excluded():
    from jseval.quality import collections
    record = json.loads((RECIPES / "real-use-queries" / "excluded.json").read_text(encoding="utf-8"))
    second = record["second_build"]
    excluded = set(record["ocr_page_cap"]["excluded"])
    c = collections.load_collection(RECIPES / "real-use")
    assert all(t["doc"] in excluded and t["doc"] not in c.documents for t in second["truncated"])
    assert "still in the collection" not in second["note"].lower()
    assert "excluded" in second["note"].lower()
