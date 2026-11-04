"""Offline parts of the real-use query writing (S30): families, gates, prompts, the model call."""
import json
import subprocess

import pytest

from jseval.quality import real_use_queries as ruq


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


def test_model_call_is_tool_less_and_session_less(monkeypatch):
    seen = {}

    def fake_run(cmd, **kw):
        seen.update(cmd=cmd, kw=kw)
        return subprocess.CompletedProcess(cmd, 0, json.dumps(
            {"is_error": False, "result": 'Sure: {"query": "Fangquoten Ostsee"}'}), "")
    monkeypatch.setattr(ruq.subprocess, "run", fake_run)
    reply, _ = ruq.ask_claude("prompt")
    assert reply == {"query": "Fangquoten Ostsee"}
    cmd = seen["cmd"]
    assert cmd[:3] == ["claude", "-p", "prompt"] and cmd[cmd.index("--model") + 1] == ruq.MODEL
    assert cmd[cmd.index("--tools") + 1] == "" and "--strict-mcp-config" in cmd
    assert "--no-session-persistence" in cmd and "bypassPermissions" not in cmd
    assert seen["kw"]["stdin"] is subprocess.DEVNULL
    with pytest.raises(ValueError, match="claude error"):
        ruq.parse_reply(json.dumps({"is_error": True, "result": "usage limit reached"}))


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
    assert set(c.documents) == set(rows) - {e["doc"] for e in excluded}
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
    text = ruq.triviality_feedback({"level": "partial"}, "Priska Schule", rare)
    assert "priska, aschaffenburg" in text and "Keep at most one" in text
    assert "weeks later" in ruq.triviality_feedback({"level": "vague"}, "x y", [])


def test_plan_is_scoped_to_the_documents_the_collection_still_holds():
    planned = [{"qid": "ki-1", "kind": "known-item", "target": "a"},
               {"qid": "ki-2", "kind": "known-item", "target": "gone"},
               {"qid": "tp-1", "kind": "topical", "members": ["a", "b", "gone"]},
               {"qid": "tp-2", "kind": "topical", "members": ["a", "gone"]}]
    scoped = ruq.in_collection(planned, {"a", "b"})
    assert [q["qid"] for q in scoped] == ["ki-1", "tp-1"]
    assert scoped[1]["members"] == ["a", "b"]  # a topical query never rests on one document
    assert planned[2]["members"] == ["a", "b", "gone"]  # the stored plan is not rewritten
