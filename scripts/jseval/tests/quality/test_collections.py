"""Collections: stored format, corruption detection, label updates, official loaders."""

from __future__ import annotations

import hashlib
import json
from collections import Counter

import pytest

from jseval.quality import collections as qc
from jseval.quality import known_answers
from jseval.quality.types import sha256_text

from .helpers import have_ir, make_collection


def test_stored_collection_round_trips(tmp_path):
    c = make_collection({"en/vague": 5, "de/known-item": 3})
    qc.write_collection(c, tmp_path)
    assert sorted(p.name for p in tmp_path.iterdir()) == [
        "collection.v1.json", "content.tsv", "manifest.tsv", "qrels.tsv", "queries.jsonl"]
    back = qc.load_collection(tmp_path)
    assert back.version == c.version
    assert back.queries == c.queries and back.judgments == c.judgments
    assert back.documents == c.documents and back.content == c.content


def test_dropped_positive_in_stored_qrels_is_detected(tmp_path):
    c = make_collection()
    qc.write_collection(c, tmp_path)
    lines = (tmp_path / "qrels.tsv").read_text().splitlines(keepends=True)
    victim = next(i for i, l in enumerate(lines) if l.rstrip().endswith("\t2"))
    (tmp_path / "qrels.tsv").write_text("".join(lines[:victim] + lines[victim + 1:]))
    with pytest.raises(qc.CollectionError, match="judgments altered.*160 judgments / 80 positives"
                                                 ".*found 159 judgments / 79 positives"):
        qc.load_collection(tmp_path)


def test_dropped_positive_with_recomputed_digests_fails_known_answers(tmp_path):
    """Someone 're-froze' the damaged file: the digests agree, the pinned counts do not."""
    c = make_collection()
    cfg_expected = c.counts()
    j = {q: dict(d) for q, d in c.judgments.items()}
    del j["q005"][next(d for d, g in j["q005"].items() if g == 2)]
    damaged = qc.Collection(c.name, c.language, c.queries, j, c.documents, c.content, c.metadata)
    qc.write_collection(damaged, tmp_path)
    loaded = qc.load_collection(tmp_path)
    from .helpers import config_for

    with pytest.raises(known_answers.KnownAnswerFailure, match="expected positives 80, found 79"):
        known_answers.check(loaded, config_for(c, expected=cfg_expected))


def test_edited_query_or_manifest_is_detected(tmp_path):
    c = make_collection()
    qc.write_collection(c, tmp_path)
    q = tmp_path / "queries.jsonl"
    q.write_text(q.read_text().replace("question 3 ", "question 3b ", 1))
    with pytest.raises(qc.CollectionError, match="recorded version"):
        qc.load_collection(tmp_path)
    qc.write_collection(c, tmp_path)
    m = tmp_path / "manifest.tsv"
    m.write_text(m.read_text().replace("docs/d001.txt", "docs/D001.txt"))
    with pytest.raises(qc.CollectionError, match="manifest"):
        qc.load_collection(tmp_path)


def test_unknown_alias_is_refused():
    with pytest.raises(qc.CollectionError, match="unknown collection alias 'scifactt'"):
        qc.load_config("scifactt")
    assert {"scifact", "miracl-de", "miracl-fr"} <= set(qc.known_aliases())


def test_packaged_configs_parse():
    for alias in qc.known_aliases():
        cfg = qc.load_config(alias)
        assert cfg.collection == alias
        assert cfg.margin("anything") == cfg.margins["default"] > 0
        assert set(cfg.expected) >= {"queries", "judgments", "positives", "grades", "documents"}


def test_existing_collections_have_official_judgments_and_the_field_is_required(tmp_path):
    assert {a: qc.load_config(a).judgments for a in
            ("scifact", "miracl-de", "miracl-fr", "enron-email")} == dict.fromkeys(
        ("scifact", "miracl-de", "miracl-fr", "enron-email"), qc.JUDGMENTS_OFFICIAL)
    doc = json.loads((qc.CONFIG_DIR / "scifact.json").read_text(encoding="utf-8"))
    for value in (None, "fixed"):
        if value is None:
            doc.pop("judgments")
        else:
            doc["judgments"] = value
        p = tmp_path / "c.json"
        p.write_text(json.dumps(doc), encoding="utf-8")
        with pytest.raises(qc.CollectionError, match="config judgments must be 'official' or 'pooled'"):
            qc.load_config(p)


def test_added_judgments_make_a_new_verifiable_version():
    c = make_collection()
    new = qc.with_added_judgments(c, {"q000": {"d050": 1}}, "judged 1 pair, rubric v1")
    assert new.version != c.version
    assert new.judgments["q000"]["d050"] == 1 and "d050" not in c.judgments["q000"]
    assert qc.ancestor_versions(new) == [c.version]
    newer = qc.with_added_judgments(new, {"q001": {"d051": 0}}, "second round")
    assert qc.ancestor_versions(newer) == [new.version, c.version]
    with pytest.raises(qc.CollectionError, match="frozen"):
        qc.with_added_judgments(c, {"q000": {next(iter(c.judgments["q000"])): 0}}, "relabel")
    forged = qc.Collection(c.name, c.language, c.queries, newer.judgments, c.documents, c.content,
                           {**newer.metadata, "label_lineage": [
                               {"from_version": "f" * 64, "added_qrels": "q001\t0\td051\t0\n",
                                "note": "forged"}]})
    with pytest.raises(qc.CollectionError, match="does not re-derive"):
        qc.ancestor_versions(forged)


def test_beir_dir_recipe_keeps_per_query_groups(tmp_path):
    (tmp_path / "qrels").mkdir()
    (tmp_path / "corpus.jsonl").write_text(
        "".join(json.dumps({"_id": f"m{i}", "text": "t"}) + "\n" for i in range(4)))
    (tmp_path / "queries.jsonl").write_text(
        json.dumps({"_id": "a", "text": "who sent the memo"}) + "\n"
        + json.dumps({"_id": "b", "text": "gas prices", "groups": {"kind": "vague"}}) + "\n")
    (tmp_path / "qrels" / "test.tsv").write_text("query-id\tcorpus-id\tscore\na\tm1\t1\nb\tm2\t1\n")
    cfg = qc.load_config("enron-email")
    c = qc.build(cfg, path=tmp_path)
    assert c.queries["a"].groups == {"lang": "en", "kind": "known-item"}
    assert c.queries["b"].groups == {"lang": "en", "kind": "vague"}
    assert c.judgments == {"a": {"m1": 1}, "b": {"m2": 1}}


def _beir_dir(root, corpus=None, queries=None, qrels=None):
    (root / "qrels").mkdir(parents=True, exist_ok=True)
    corpus = corpus if corpus is not None else [
        json.dumps({"_id": f"m{i}", "title": "Re: gas", "text": f"mail {i}"}) for i in range(4)]
    queries = queries if queries is not None else [
        json.dumps({"_id": "a", "text": "who sent the memo"}), json.dumps({"_id": "b", "text": "gas"})]
    qrels = qrels if qrels is not None else ["query-id\tcorpus-id\tscore", "a\tm1\t1", "b\tm2\t1"]
    (root / "corpus.jsonl").write_text("".join(x + "\n" for x in corpus))
    (root / "queries.jsonl").write_text("".join(x + "\n" for x in queries))
    (root / "qrels" / "test.tsv").write_text("".join(x + "\n" for x in qrels))
    return root


def test_beir_dir_hashes_each_document_text(tmp_path):
    c = qc.build(qc.load_config("enron-email"), path=_beir_dir(tmp_path))
    assert c.content["m1"] == sha256_text("Re: gas\n\nmail 1")


@pytest.mark.parametrize("part,lines,reason", [
    ("qrels", ["query-id\tcorpus-id\tscore", "a\tm1\t1", "b m2 1"],
     r"qrels/test.tsv line 3: expected 'query-id<TAB>corpus-id<TAB>score'"),
    ("qrels", ["a\tm1\t1", "b\tm2\t1\textra"], r"qrels/test.tsv line 2: expected"),
    ("qrels", ["a\tm1\t1", "b\tm2\tyes"], r"qrels/test.tsv line 2: grade 'yes' is not an integer"),
    ("qrels", ["a\tm1\t1", "b\tm2\t1", "a\tm1\t0"],
     r"qrels/test.tsv line 3: duplicate judgment for a/m1"),
    ("corpus", [json.dumps({"_id": "m1", "text": "x"}), json.dumps({"_id": "m2", "text": "y"}),
                json.dumps({"_id": "m1", "text": "z"})], r"corpus.jsonl line 3: doc id m1 appears twice"),
    ("corpus", [json.dumps({"_id": "m1", "text": "x"}), "{broken"], r"corpus.jsonl line 2:"),
    ("queries", [json.dumps({"_id": "a", "text": "q"}), json.dumps({"_id": "b", "text": "r"}),
                 json.dumps({"_id": "a", "text": "s"})], r"queries.jsonl line 3: query id a appears twice"),
])
def test_beir_dir_refuses_malformed_lines_and_duplicates_with_their_line(tmp_path, part, lines, reason):
    root = _beir_dir(tmp_path, **{part: lines})
    with pytest.raises(qc.CollectionError, match=reason):
        qc.build(qc.load_config("enron-email"), path=root)


def test_a_stored_collection_must_record_its_version(tmp_path):
    c = make_collection()
    qc.write_collection(c, tmp_path)
    meta_path = tmp_path / "collection.v1.json"
    meta = json.loads(meta_path.read_text())
    for value in (None, ""):
        meta["version"] = value
        meta_path.write_text(json.dumps(meta))
        with pytest.raises(qc.CollectionError, match="records no version"):
            qc.load_collection(tmp_path)
    del meta["version"]
    meta_path.write_text(json.dumps(meta))
    with pytest.raises(qc.CollectionError, match="records no version"):
        qc.load_collection(tmp_path)


def test_edited_document_content_is_detected(tmp_path):
    c = make_collection()
    qc.write_collection(c, tmp_path)
    p = tmp_path / "content.tsv"
    p.write_text(p.read_text().replace(c.content["d003"], "0" * 64))
    with pytest.raises(qc.CollectionError, match="content.tsv does not match"):
        qc.load_collection(tmp_path)
    (tmp_path / "content.tsv").unlink()
    with pytest.raises(qc.CollectionError, match="incomplete collection"):
        qc.load_collection(tmp_path)


class _Doc:
    def __init__(self, doc_id, text, title=""):
        self.doc_id, self.text, self.title = doc_id, text, title


class _FakeDataset:
    """The ir_datasets surface the builders use: positional docs, a docstore, a count."""

    def __init__(self, n_docs):
        self.docs = [_Doc(f"p{i}", f"passage {i}", "T") for i in range(n_docs)]

    def docs_iter(self):
        return self.docs

    def docs_count(self):
        return len(self.docs)

    def docs_store(self):
        by_id = {d.doc_id: d for d in self.docs}
        ns = type("Store", (), {})()
        ns.get_many_iter = lambda ids: (by_id[i] for i in ids if i in by_id)
        return ns


def _miracl_cfg(filler):
    return qc.QualityConfig(collection="miracl-x", language="xx",
                            source={"kind": "miracl-pool", "dataset": "fake", "filler": filler,
                                    "seed": 7}, labels={}, expected={})


def _fake_miracl(monkeypatch, n_docs, judged=("p0", "p1", "p2")):
    import ir_datasets

    ds = _FakeDataset(n_docs)
    queries = {"q1": qc.QueryText("frage")}
    judgments = {"q1": {d: (1 if i == 0 else 0) for i, d in enumerate(judged)}}
    monkeypatch.setattr(qc, "_load_queries_and_qrels", lambda *a: (ds, queries, judgments))
    monkeypatch.setattr(ir_datasets, "__version__", "fake", raising=False)
    return ds


def test_miracl_filler_never_asks_for_more_than_the_corpus_has(monkeypatch):
    _fake_miracl(monkeypatch, 10)
    c = qc.build(_miracl_cfg(7))  # exactly every unjudged passage
    assert len(c.documents) == 10
    assert c.content["p4"] == sha256_text("T\n\npassage 4")
    with pytest.raises(qc.CollectionError, match="8 filler passages requested, but the corpus "
                                                 "has only 7 unjudged passages"):
        qc.build(_miracl_cfg(8))
    _fake_miracl(monkeypatch, 10, judged=("p0", "ghost1", "ghost2"))
    with pytest.raises(qc.CollectionError, match="2 judged passages are not in the corpus"):
        qc.build(_miracl_cfg(7))


def test_write_documents_refuses_text_that_differs_from_the_content_hash(monkeypatch, tmp_path):
    import ir_datasets

    ds = _fake_miracl(monkeypatch, 6)
    c = qc.build(_miracl_cfg(2))
    monkeypatch.setattr(ir_datasets, "load", lambda name: ds)
    assert qc.write_documents(c, tmp_path / "a") == 5
    written = (tmp_path / "a" / c.documents["p1"]).read_bytes()
    assert hashlib.sha256(written).hexdigest() == c.content["p1"]
    ds.docs[1].text = "passage 1, re-extracted"
    with pytest.raises(qc.CollectionError, match="text of p1 does not match its recorded content"):
        qc.write_documents(c, tmp_path / "b")


# ------------------------------------------------------------------ official sets (real data)

def _raw(dataset_id):
    import ir_datasets

    ds = ir_datasets.load(dataset_id)
    return list(ds.queries_iter()), list(ds.qrels_iter())


@pytest.mark.skipif(not have_ir("beir", "scifact"), reason="beir/scifact not in the ir_datasets cache")
def test_scifact_loads_unchanged():
    cfg = qc.load_config("scifact")
    c = qc.build(cfg)
    queries, qrels = _raw("beir/scifact/test")
    assert {(r.query_id, r.doc_id, r.relevance) for r in qrels} == {
        (q, d, g) for q, docs in c.judgments.items() for d, g in docs.items()}
    assert c.counts() == {"queries": 300, "judgments": 339, "positives": 339,
                          "grades": {"1": 339}, "documents": 5183}
    assert qc.build(cfg).version == c.version
    known_answers.check(c, cfg)


@pytest.mark.parametrize("lang,queries,judgments,positives", [
    ("de", 305, 3144, 811), ("fr", 343, 3429, 731)])
def test_miracl_pool_is_every_judged_passage_plus_seeded_filler(lang, queries, judgments, positives):
    if not have_ir("miracl", "v1.0", lang, "docs.pklz4"):
        pytest.skip(f"miracl/{lang} docstore not in the ir_datasets cache")
    cfg = qc.load_config(f"miracl-{lang}")
    c = qc.build(cfg)
    raw_q, raw_qrels = _raw(f"miracl/{lang}/dev")
    assert {(r.query_id, r.doc_id, r.relevance) for r in raw_qrels} == {
        (q, d, g) for q, docs in c.judgments.items() for d, g in docs.items()}
    assert {q.query_id: q.text for q in raw_q} == {q: t.text for q, t in c.queries.items()}
    counts = c.counts()
    assert (counts["queries"], counts["judgments"], counts["positives"]) == (queries, judgments, positives)
    judged = {r.doc_id for r in raw_qrels}
    assert judged <= set(c.documents)
    filler = set(c.documents) - judged
    assert len(filler) == cfg.source["filler"] == c.metadata["filler_documents"]
    assert Counter(r.relevance for r in raw_qrels)[1] == positives
    again = qc.build(cfg)
    assert again.version == c.version and again.documents == c.documents
    known_answers.check(c, cfg)
