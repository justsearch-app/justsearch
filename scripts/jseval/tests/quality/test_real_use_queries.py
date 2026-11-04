"""Offline parts of the real-use query writing (S30): families, gates, prompts, the model call."""
import json
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
    assert cfg.layout == "as-is" and cfg.witness_formats == ("md",) and cfg.pending_kinds == ("topical",)
    assert report.pending == len(collections.pending_queries(c, cfg))
    scored = collections.scored_collection(c, cfg)
    assert all(q.groups["kind"] == "known-item" for q in scored.queries.values())
    assert all(max(scored.judgments[q].values()) == 2 for q in scored.queries)


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


def test_triviality_feedback_names_rare_target_words():
    from collections import Counter
    df = Counter({"priska": 1, "schule": 40, "aschaffenburg": 3})
    rare = ruq.rare_words("Priska Schule Aschaffenburg Ferien", "priska schule aschaffenburg", df)
    assert rare == ["priska", "aschaffenburg"]
    text = ruq.triviality_feedback({"kind": "known-item", "level": "partial"}, "Priska Schule", rare)
    assert "priska, aschaffenburg" in text and "Keep at most one" in text
    assert "Use 2 to 4 words in total" in text  # replacements must not lengthen the query
    assert "weeks later" in ruq.triviality_feedback({"kind": "known-item", "level": "vague"}, "x y", [])


def test_triviality_drops_take_the_fewest_seeded_rank1_queries_per_level():
    planned = {f"p{i}": {"kind": "known-item", "level": "partial"} for i in range(10)}
    planned |= {f"v{i}": {"kind": "known-item", "level": "vague"} for i in range(4)}
    planned |= {"e0": {"kind": "known-item", "level": "exact"}, "t0": {"kind": "topical"}}
    rank = {f"p{i}": 1 if i < 7 else 3 for i in range(10)} | {f"v{i}": 1 if i < 2 else None for i in range(4)}
    rank |= {"e0": 1}
    results = {q: {"rank": r} for q, r in rank.items()}
    done = dict.fromkeys(planned)
    drops = ruq.triviality_drops(planned, done, results)
    assert len(drops) == 4 and all(results[q]["rank"] == 1 and q.startswith("p") for q in drops)
    assert (7 - 4) / (10 - 4) <= .5 and (7 - 3) / (10 - 3) > .5  # 4 is the fewest that pass
    assert drops == ruq.triviality_drops(planned, done, results)  # fixed seed, not by hand


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
