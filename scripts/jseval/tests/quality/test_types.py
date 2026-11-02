"""Shared types: canonical digests, RunRecord storage, document identity (S14)."""

from __future__ import annotations

import unicodedata

import pytest

from jseval.quality.types import (
    Collection,
    Outcome,
    QueryResult,
    QueryText,
    RunRecord,
    RunRecordError,
    UnknownDocument,
    canonical_qrels_text,
    canonical_relpath,
    parse_qrels_text,
)

from .helpers import header, make_collection, make_run


def _docs_collection(paths: dict[str, str]) -> Collection:
    return Collection("ids", "en", {"q1": QueryText("x")}, {"q1": {next(iter(paths)): 1}}, paths)


# ------------------------------------------------------------------ S14 document identity

def test_same_basename_in_different_folders_are_different_documents():
    c = _docs_collection({"A": "inbox/report.txt", "B": "archive/report.txt"})
    assert c.doc_id_for_path("inbox/report.txt") == "A"
    assert c.doc_id_for_path("archive/report.txt") == "B"
    with pytest.raises(UnknownDocument):
        c.doc_id_for_path("report.txt")


def test_names_differing_only_by_case_stay_distinct():
    c = _docs_collection({"lower": "notes/readme.txt", "upper": "notes/README.txt"})
    assert c.doc_id_for_path("notes/README.txt") == "upper"
    assert c.doc_id_for_path("notes/readme.txt") == "lower"
    with pytest.raises(UnknownDocument):
        c.doc_id_for_path("notes/Readme.txt")


def test_unicode_normalization_forms_map_to_the_same_document():
    nfc = unicodedata.normalize("NFC", "Bücher/Übersicht größe.txt")
    nfd = unicodedata.normalize("NFD", nfc)
    assert nfc != nfd
    c = _docs_collection({"de": nfd})  # stored NFD (as macOS hands it back)
    assert c.doc_id_for_path(nfc) == "de"
    assert c.doc_id_for_path(nfd) == "de"


def test_url_like_characters_are_literal_in_paths_and_decoded_in_uris(tmp_path):
    c = _docs_collection({"pct": "a%20b.txt", "space": "a b.txt", "hash": "x/36951#0?v=1&y.txt"})
    assert c.doc_id_for_path("a%20b.txt") == "pct"  # a literal %20 in a file name
    assert c.doc_id_for_path("a b.txt") == "space"
    assert c.doc_id_for_path("x/36951#0?v=1&y.txt") == "hash"
    root = tmp_path / "corpus root"
    assert c.doc_id_for_location((root / "a b.txt").as_uri(), root) == "space"
    assert c.doc_id_for_location((root / "a%20b.txt").as_uri(), root) == "pct"
    assert c.doc_id_for_location(str(root / "x" / "36951#0?v=1&y.txt"), root) == "hash"
    assert c.doc_id_for_location("x\\36951#0?v=1&y.txt") == "hash"  # Windows separators
    with pytest.raises(UnknownDocument):
        c.doc_id_for_location("/elsewhere/a b.txt", root)


def test_multi_chunk_documents_count_once_at_their_first_position():
    c = _docs_collection({"long": "big/manual.pdf", "short": "small.txt", "other": "o.txt"})
    hits = ["big/manual.pdf", "small.txt", "big/manual.pdf", "o.txt", "big/manual.pdf"]
    assert c.ranked_doc_ids(hits) == ["long", "short", "other"]


def test_two_documents_cannot_share_a_path_and_escapes_are_refused():
    with pytest.raises(ValueError, match="share the path"):
        _docs_collection({"a": "x/y.txt", "b": "x//./y.txt"})
    for bad in ("/abs/y.txt", "../y.txt", "C:/y.txt", "a\tb"):
        with pytest.raises(ValueError):
            canonical_relpath(bad)


# ------------------------------------------------------------------ digests

def test_version_changes_with_queries_judgments_and_manifest():
    c = make_collection()
    v = c.version
    q = dict(c.queries)
    q["q000"] = QueryText(q["q000"].text + "?", q["q000"].groups)
    assert Collection(c.name, c.language, q, c.judgments, c.documents).version != v
    j = {k: dict(d) for k, d in c.judgments.items()}
    j["q000"].pop(next(d for d, g in j["q000"].items() if g > 0))
    assert Collection(c.name, c.language, c.queries, j, c.documents).version != v
    docs = dict(c.documents)
    docs["d000"] = "docs/renamed.txt"
    assert Collection(c.name, c.language, c.queries, c.judgments, docs).version != v
    with pytest.raises(ValueError, match="recorded version"):
        Collection(c.name, c.language, c.queries, c.judgments, c.documents, version="0" * 64)


def test_qrels_text_round_trips_and_refuses_duplicates():
    j = {"q2": {"b": 0, "a": 2}, "q1": {"z": 1}}
    text = canonical_qrels_text(j)
    assert text == "q1\t0\tz\t1\nq2\t0\ta\t2\nq2\t0\tb\t0\n"
    assert parse_qrels_text(text) == j
    with pytest.raises(ValueError, match="duplicate"):
        parse_qrels_text("q1 0 a 1\nq1 0 a 0\n")


# ------------------------------------------------------------------ RunRecord

def test_run_record_round_trips_through_jsonl(tmp_path):
    c = make_collection()
    run = make_run(c)
    p = tmp_path / "run.jsonl"
    run.write(p)
    back = RunRecord.read(p)
    assert back == run
    assert p.read_text().count("\n") == 1 + len(c.queries)


def test_interrupted_or_corrupt_run_records_are_refused(tmp_path):
    text = make_run(make_collection()).dumps()
    with pytest.raises(RunRecordError, match="interrupted"):
        RunRecord.loads(text[:-30])
    lines = text.splitlines()
    with pytest.raises(RunRecordError, match="line 3"):
        RunRecord.loads("\n".join(lines[:2] + ["{not json"] + lines[3:]) + "\n")
    with pytest.raises(RunRecordError, match="header"):
        RunRecord.loads("\n".join(lines[1:]) + "\n")


def test_request_config_digest_is_verified():
    c = make_collection()
    h = header(c)
    assert len(h.request_config_sha256) == 64
    with pytest.raises(ValueError):
        header(c, request_config_sha256="0" * 64)


def test_served_reranker_values_are_checked():
    with pytest.raises(ValueError):
        QueryResult.from_dict({"qid": "q", "query_text_sha256": "x", "ranked": [],
                               "served": {"legs": [], "reranker": "maybe"}})


def test_exit_codes():
    assert [o.exit_code for o in Outcome] == [0, 1, 2, 2]
