"""Shared types: canonical digests, RunRecord storage, document identity (S14)."""

from __future__ import annotations

import unicodedata

import pytest

from jseval.quality import types as qtypes
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
    document_text,
    parse_qrels_text,
    sha256_text,
)

from .helpers import content_of, header, make_collection, make_run


def _docs_collection(paths: dict[str, str]) -> Collection:
    return Collection("ids", "en", {"q1": QueryText("x")}, {"q1": {next(iter(paths)): 1}}, paths,
                      content_of(paths))


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
    for bad in ("/abs/y.txt", "../y.txt", "a\tb"):
        with pytest.raises(ValueError):
            canonical_relpath(bad)


def test_a_colon_is_a_file_name_character_on_posix_and_a_drive_on_windows(monkeypatch):
    monkeypatch.setattr(qtypes, "_WINDOWS", False)
    for ok in ("a:b.txt", "c:d.txt", "notes/10:30 meeting.txt", "C:/y.txt"):
        assert canonical_relpath(ok) == ok
    c = _docs_collection({"m": "notes/10:30 meeting.txt", "d": "c:d.txt"})
    assert c.doc_id_for_path("notes/10:30 meeting.txt") == "m"
    assert c.doc_id_for_location("c:d.txt") == "d"
    monkeypatch.setattr(qtypes, "_WINDOWS", True)
    for bad in ("C:/y.txt", "c:d.txt", "C:\\y.txt", "\\\\server\\share\\y.txt"):
        with pytest.raises(ValueError, match="relative"):
            canonical_relpath(bad)
    assert canonical_relpath("notes/10:30 meeting.txt") == "notes/10:30 meeting.txt"


# ------------------------------------------------------------------ digests

def test_version_changes_with_queries_judgments_and_manifest():
    c = make_collection()
    v = c.version
    q = dict(c.queries)
    q["q000"] = QueryText(q["q000"].text + "?", q["q000"].groups)
    assert Collection(c.name, c.language, q, c.judgments, c.documents, c.content).version != v
    j = {k: dict(d) for k, d in c.judgments.items()}
    j["q000"].pop(next(d for d, g in j["q000"].items() if g > 0))
    assert Collection(c.name, c.language, c.queries, j, c.documents, c.content).version != v
    docs = dict(c.documents)
    docs["d000"] = "docs/renamed.txt"
    assert Collection(c.name, c.language, c.queries, c.judgments, docs, c.content).version != v
    with pytest.raises(ValueError, match="recorded version"):
        Collection(c.name, c.language, c.queries, c.judgments, c.documents, c.content,
                   version="0" * 64)


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
    assert [o.exit_code for o in Outcome] == [0, 1, 2, 2, 3]


def _header_line(**changes):
    import json

    run = make_run(make_collection())
    doc = json.loads(run.dumps().splitlines()[0])
    for path, value in changes.items():
        *parents, leaf = path.split("__")
        d = doc
        for k in parents:
            d = d[k]
        if value is _DROP:
            del d[leaf]
        else:
            d[leaf] = value
    return run, json.dumps(doc)


_DROP = object()


@pytest.mark.parametrize("change,reason", [
    (dict(index_population=[60, "abc"]), "index_population must be an object"),
    (dict(candidate__dirty_hash=123), "candidate.dirty_hash must be a non-empty string or null"),
    (dict(model_hashes=["e1", "r1"]), "model_hashes must be an object"),
    (dict(model_hashes={"embed": 1}), "model_hashes must map model names to hash strings"),
    (dict(index_state="settled"), "index_state must be an object"),
    (dict(budget=[500]), "budget must be an object"),
    (dict(collection="synthetic"), "collection must be an object"),
    (dict(index_population__count="60"), "index_population.count must be a count"),
    (dict(index_population__content_sha256=_DROP), "index_population.content_sha256"),
    (dict(expected_served=_DROP), "expected_served"),
    (dict(expected_served__legs=[]), "expected_served.legs must name at least one leg"),
    (dict(expected_served__reranker="sometimes"), "expected_served.reranker must be"),
    (dict(index_build="reused"), "index_build must be 'fresh' or 'cached'"),
    (dict(index_build="cached"), "index_cache_key must be a non-empty string"),
    (dict(ended=5), "ended must be a non-empty string or null"),
])
def test_a_badly_shaped_header_is_refused_with_its_reason(change, reason):
    run, line = _header_line(**change)
    rest = "".join(x + "\n" for x in run.dumps().splitlines()[1:])
    with pytest.raises(RunRecordError, match="bad header: .*" + reason.replace(".", r"\.")):
        RunRecord.loads(line + "\n" + rest)


def test_a_header_or_result_that_is_not_an_object_is_refused():
    text = make_run(make_collection()).dumps()
    lines = text.splitlines()
    with pytest.raises(RunRecordError, match="bad header: header must be an object"):
        RunRecord.loads("[1]\n" + "".join(x + "\n" for x in lines[1:]))
    with pytest.raises(RunRecordError, match="line 2: query result must be an object"):
        RunRecord.loads(lines[0] + "\n\"q\"\n")
    with pytest.raises(RunRecordError, match="line 2: query q000: timings"):
        RunRecord.loads(lines[0] + "\n" + lines[1].replace('"timings":{}', '"timings":{"x":"y"}')
                        + "\n")


def test_header_declares_served_legs_population_content_and_index_origin():
    c = make_collection()
    h = header(c, index_build="cached", index_cache_key="sha-of-inputs", budget={"ms": 900})
    d = h.to_dict()
    assert d["expected_served"] == {"legs": ["lexical", "dense"], "reranker": "executed"}
    assert d["index_population"] == {"count": 60, "manifest_sha256": c.manifest_sha256,
                                     "content_sha256": c.content_sha256}
    assert (d["index_build"], d["index_cache_key"]) == ("cached", "sha-of-inputs")
    assert type(h).from_dict(d) == h


def test_every_document_needs_a_content_hash_and_content_changes_the_version():
    c = make_collection()
    with pytest.raises(ValueError, match="content hashes cover 59 of 60 documents"):
        Collection(c.name, c.language, c.queries, c.judgments, c.documents,
                   {k: v for k, v in c.content.items() if k != "d007"})
    with pytest.raises(ValueError, match="lowercase sha256"):
        Collection(c.name, c.language, c.queries, c.judgments, c.documents,
                   {**c.content, "d007": "not-a-hash"})
    reformatted = {**c.content, "d007": sha256_text(document_text("Title", "text of d007"))}
    other = Collection(c.name, c.language, c.queries, c.judgments, c.documents, reformatted)
    assert other.manifest_sha256 == c.manifest_sha256  # same paths ...
    assert other.content_sha256 != c.content_sha256 and other.version != c.version  # ... new text
    assert document_text("T", "body") == "T\n\nbody" and document_text("", "body") == "body"
