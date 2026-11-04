"""K1: indexes are keyed and owned by document identity; K2: corpus-only and pending queries."""
from dataclasses import replace
import subprocess
from unittest.mock import Mock

from click.testing import CliRunner
import pytest

from jseval import backend
from jseval.cli import main
from jseval.quality import collections as qcoll
from jseval.quality import index as ix
from jseval.quality import known_answers, run
from jseval.quality.types import Collection, QueryText, sha256_text

from .helpers import config_for, make_collection
from .test_index import collection, config, env_for  # noqa: F401 (fixtures)


def _edited_labels(c: Collection) -> Collection:
    return replace(c, queries={**c.queries, "q2": QueryText("another query", {"kind": "topical"})},
                   judgments={**c.judgments, "q2": {"answer-2": 2}}, version="")


def test_queries_or_judgments_edit_keeps_key_and_owner(collection, config):
    c, _ = collection
    env = env_for(config)
    key = ix.compute_index_key(c, config, env)[0]
    edited = _edited_labels(c)
    assert edited.version != c.version
    assert ix.compute_index_key(edited, config, env)[0] == key
    assert ix._population_owner(edited, key) == ix._population_owner(c, key)
    regraded = replace(c, judgments={"q": {"ANSWER-1": 2, "answer-2": 1}}, version="")
    assert ix.compute_index_key(regraded, config, env)[0] == key


def test_document_layout_or_witness_change_alters_key_and_owner(collection, config):
    c, _ = collection
    env = env_for(config)
    key = ix.compute_index_key(c, config, env)[0]
    changed = [
        replace(c, content={**c.content, "answer-2": sha256_text("edited")}, version=""),
        replace(c, documents={**c.documents, "answer-2": "b/renamed.txt"}, version=""),
        replace(c, documents={"ANSWER-1": c.documents["ANSWER-1"]},
                content={"ANSWER-1": c.content["ANSWER-1"]}, version=""),
        replace(c, metadata={"layout": "as-is"}, version=""),
        replace(c, metadata={"witness_formats": ["md"]}, version=""),
        replace(c, metadata={"witness_formats": ["md", "txt"]}, version=""),
    ]
    keys = {ix.compute_index_key(d, config, env)[0] for d in changed}
    assert key not in keys and len(keys) == len(changed)
    for d in changed:
        assert ix._population_owner(d, key) != ix._population_owner(c, key)


def test_stored_collection_data_is_outside_the_tree_key(collection, config):
    c, _ = collection
    env = env_for(config)
    key = lambda: ix.compute_index_key(c, config, env)[0]
    first = key()
    data = config.repo_root / "scripts/jseval/jseval/quality/recipes/real-use/queries.jsonl"
    data.parent.mkdir(parents=True)
    data.write_text('{"qid": "q"}\n')
    cfg = config.repo_root / "scripts/jseval/jseval/quality/config/real-use.json"
    cfg.parent.mkdir(parents=True)
    cfg.write_text("{}")
    assert key() == first
    code = config.repo_root / "scripts/jseval/jseval/quality/collections.py"
    code.write_text("# document materialization code stays keyed")
    assert key() != first


def test_prepared_index_is_adopted_after_a_queries_only_edit(collection, config, tmp_path, monkeypatch):
    c, source = collection
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: env_for(config))
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    real_popen = subprocess.Popen
    launched = []

    class FakeWorker:
        def __init__(self, command, **kw):
            launched.append(command)

        def poll(self):
            return 0
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        real_popen(command, **kw) if command[0] == "git" else FakeWorker(command, **kw))
    cfg = replace(config, cache_root=tmp_path / "cache", documents_dir=source)
    with ix.prepare_index(c, cfg) as handle:
        assert handle.index_build == "fresh"
        root, key = handle.root, handle.index_key
    # A completed build publishes entry.json under its owner (the worker is faked here).
    ix._write(root / "entry.json", {"owner": ix._population_owner(c, key)})
    edited = _edited_labels(c)
    with ix.prepare_index(edited, cfg) as handle:
        assert (handle.index_build, handle.root, handle.index_key) == ("cached", root, key)
    assert qcoll.load_collection(root / "collection").queries == edited.queries
    with ix.prepare_index(replace(c, metadata={"witness_formats": ["md"]}, version=""), cfg) as handle:
        assert handle.index_build == "fresh" and handle.root != root


def _with_pending(n_topical: int = 3, judged: bool = False):
    c = make_collection({"de/known-item": 4})
    queries = dict(c.queries)
    judgments = {q: dict(d) for q, d in c.judgments.items()}
    for i in range(n_topical):
        queries[f"t{i}"] = QueryText(f"Unterlagen zum Thema {i}", {"lang": "de", "kind": "topical"})
        if judged:
            judgments[f"t{i}"] = {"d001": 2}
    c = Collection(c.name, c.language, queries, judgments, c.documents, c.content, c.metadata)
    expected = {**c.counts(), "pending": n_topical}
    return c, config_for(c, expected=expected, pending_kinds=("topical",))


def test_pending_queries_are_counted_and_never_scored():
    c, cfg = _with_pending()
    report = known_answers.check(c, cfg)
    assert report.pending == 3 and report.counts["queries"] == 7
    scored = qcoll.scored_collection(c, cfg)
    assert sorted(scored.queries) == sorted(c.judgments) and len(scored.queries) == 4
    assert scored.document_identity() == c.document_identity()
    assert qcoll.QualityConfig.from_dict({
        "schema": qcoll.CONFIG_SCHEMA, "collection": "x", "language": "de", "source": {},
        "expected": {}, "judgments": "pooled", "pending_kinds": ["topical"]}).pending_kinds == ("topical",)


def test_pending_kind_with_judgments_is_refused():
    c, cfg = _with_pending(judged=True)
    with pytest.raises(known_answers.KnownAnswerFailure, match="pending kind already have judgments"):
        known_answers.check(c, replace(cfg, expected={}))


def _corpus_only():
    c = make_collection({"de/known-item": 1})
    c = Collection(c.name, c.language, {}, {}, c.documents, c.content, c.metadata)
    return c, config_for(c)


def test_corpus_only_collection_passes_preparation_checks_but_not_scoring():
    c, cfg = _corpus_only()
    assert known_answers.check(c, cfg, require_scored=False).counts["queries"] == 0
    with pytest.raises(known_answers.KnownAnswerFailure, match="no scored queries"):
        known_answers.check(c, cfg)
    pending, pcfg = _with_pending()
    only_pending = Collection(pending.name, pending.language,
                              {q: t for q, t in pending.queries.items() if q.startswith("t")}, {},
                              pending.documents, pending.content, pending.metadata)
    with pytest.raises(known_answers.KnownAnswerFailure, match=r"no scored queries \(3 pending"):
        known_answers.check(only_pending, replace(pcfg, expected={}))


def test_cli_prepare_accepts_a_corpus_only_collection(monkeypatch):
    c, cfg = _corpus_only()
    monkeypatch.setattr(qcoll, "resolve_collection", lambda _: (c, cfg))
    prepared = []

    class Handle:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def wait(self):
            prepared.append(True)
    monkeypatch.setattr(ix, "prepare_index", lambda col, _cfg: prepared.append(col) or Handle())
    result = CliRunner().invoke(main, ["quality", "prepare", "corpus-only"])
    assert result.exit_code == 0 and result.output.startswith("PASS synthetic prepared index ready"), result.output
    assert prepared[0] is c and prepared[1] is True
    result = CliRunner().invoke(main, ["quality", "known-answers", "corpus-only"])
    assert result.exit_code == 0, result.output


@pytest.mark.parametrize("action", ["baseline", "check"])
@pytest.mark.parametrize("make", [_corpus_only, _with_pending])
def test_baseline_and_check_refuse_without_scored_queries(tmp_path, monkeypatch, action, make):
    c, cfg = make()
    if make is _with_pending:
        c = Collection(c.name, c.language, {q: t for q, t in c.queries.items() if q.startswith("t")},
                       {}, c.documents, c.content, c.metadata)
        cfg = replace(cfg, expected={})
    monkeypatch.setattr(backend, "source_identity", lambda _: ("a" * 40, None))
    monkeypatch.setattr(backend, "require_prepared", lambda *_: None)
    monkeypatch.setattr(run.collections, "resolve_collection", lambda _: (c, cfg))
    prepare = Mock(side_effect=AssertionError("refused before any index work"))
    monkeypatch.setattr(run.index, "prepare_index", prepare)
    with pytest.raises(known_answers.KnownAnswerFailure, match="no scored queries"):
        run.execute(action, ("fixture",), baseline_dir=tmp_path / "base", runs_dir=tmp_path / "runs")
    prepare.assert_not_called()
    result = CliRunner().invoke(main, ["quality", action, "fixture", "--runs-dir", tmp_path / "runs",
                                       "--baseline-dir", tmp_path / "base"])
    assert result.exit_code == 2 and "no scored queries" in result.output, result.output


def test_execute_scores_only_labelled_queries_and_reports_pending(tmp_path, monkeypatch):
    c, cfg = _with_pending()
    monkeypatch.setattr(backend, "source_identity", lambda _: ("a" * 40, None))
    monkeypatch.setattr(backend, "require_prepared", lambda *_: None)
    monkeypatch.setattr(run.collections, "resolve_collection", lambda _: (c, cfg))
    seen = []
    handle = Mock()
    handle.__enter__ = Mock(return_value=handle)
    handle.__exit__ = Mock(return_value=False)
    handle.model_hashes = {"model": "pinned"}
    monkeypatch.setattr(run.index, "prepare_index", lambda col, *a, **kw: seen.append(col) or handle)
    monkeypatch.setattr(run, "estimate_seconds", lambda *a: 601)
    which = run.index.shutil.which
    monkeypatch.setattr(run.index.shutil, "which", lambda name, *a, **kw:
                        "/bin/false" if name == "js-gradle-slot" else which(name, *a, **kw))
    with pytest.raises(run.RunRefused, match="estimate.*budget"):
        run.execute("baseline", ("fixture",), baseline_dir=tmp_path / "base", runs_dir=tmp_path / "runs")
    assert sorted(seen[0].queries) == sorted(c.judgments)
    assert seen[0].document_identity() == c.document_identity()
