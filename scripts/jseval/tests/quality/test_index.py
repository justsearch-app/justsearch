"""Index trust boundaries; real CPU lifecycle is opt-in via JSEVAL_QUALITY_INDEX_REAL=1."""
from dataclasses import replace
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import httpx
import pytest

from jseval.quality import distribution, index as ix
from jseval.quality.types import Collection, QueryText, sha256_text
from .conftest import pinned_env, write_checkout


@pytest.fixture
def collection(tmp_path):
    texts = {"ANSWER-1": "Alpha source with Unicode café and URL %20 #?\n",
             "answer-2": "Second document with other material\n"}
    paths = {"ANSWER-1": "a/Same%20#?.txt", "answer-2": "b/same%20#?.txt"}
    source = tmp_path / "source"
    for doc, text in texts.items():
        p = source / paths[doc]
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    c = Collection("unit", "en", {"q": QueryText("query words")}, {"q": {"ANSWER-1": 1}},
                   paths, {d: sha256_text(t) for d, t in texts.items()})
    return c, source


@pytest.fixture
def config(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    (repo / "shape.java").write_text("shape")
    models = tmp_path / "models"
    (models / "onnx").mkdir(parents=True)
    (models / "onnx/model.onnx").write_bytes(b"model actual bytes")
    return ix.IndexConfig(repo_root=repo, models_dir=models)


def env_for(config):
    return pinned_env(config)


@pytest.mark.parametrize('all_markdown', [False, True])
def test_legacy_witness_selection_keeps_custom_extensions(collection, config, tmp_path, all_markdown):
    c, source = collection
    paths = {doc: str(Path(rel).with_suffix('.md')) if all_markdown or doc == 'ANSWER-1' else rel
             for doc, rel in c.documents.items()}
    for doc, rel in c.documents.items():
        (source / rel).rename(source / paths[doc])
    c = replace(c, documents=paths, version='')
    root = tmp_path / 'entry'
    root.mkdir()
    m = ix._materialize(c, replace(config, documents_dir=source), root)
    longest, samples = ix._witness_documents(c, m, root / 'documents', 3)
    assert longest == 'ANSWER-1'
    assert samples == sorted(c.documents)


def test_key_follows_the_snapshot_not_the_source_tree(collection, config, tmp_path):
    c, _ = collection
    env = env_for(config)
    key = lambda e=env: ix.compute_index_key(c, config, e)[0]
    first = key()
    # Source edits outside the snapshot (any file of the checkout) never key the index.
    query = config.repo_root / "modules/app-services/src/main/java/KnowledgeSearchEngine.java"
    query.parent.mkdir(parents=True)
    query.write_text("request change")
    (config.repo_root / "shape.java").write_text("shape ")
    assert key() == first
    # Different distribution bytes are a different snapshot, so a different key.
    other = write_checkout(tmp_path / "other", jar=b"one changed byte")
    changed = {**env, ix.DISTRIBUTION_ENV: distribution.create(other, ["other", None])}
    assert key(changed) != first


def test_model_bytes_runtime_collection_and_content_invalidate(collection, config):
    c, _ = collection
    env = env_for(config)
    key = ix.compute_index_key(c, config, env)[0]
    model = config.models_dir / "onnx/model.onnx"
    model.write_bytes(b"same size changed!")
    assert ix.compute_index_key(c, config, env)[0] != key
    model.write_bytes(b"model actual bytes")
    assert ix.compute_index_key(c, config, env)[0] == key
    assert ix.compute_index_key(c, config, {**env, "JUSTSEARCH_CHUNK_SIZE": "123"})[0] != key
    assert ix.compute_index_key(replace(c, name="other", version=""), config, env)[0] != key
    assert ix.compute_index_key(replace(c, content={d: sha256_text("edited") for d in c.documents}, version=""), config, env)[0] != key


def test_opaque_materialization_roundtrip_and_content_guard(collection, config, tmp_path):
    c, source = collection
    root = tmp_path / "entry"
    root.mkdir()
    cfg = replace(config, documents_dir=source)
    m = ix._materialize(c, cfg, root)
    for doc, rel in m.documents.items():
        assert len(rel) == 68 and rel.endswith(".txt")
        assert doc not in rel and "ANSWER" not in rel and "query" not in rel
        assert m.doc_id_for_location((root / "documents" / rel).as_uri(), root / "documents") == doc
    assert m.documents == ix._materialize(c, cfg, root).documents
    assert ix._json(root / "materialized.json") == m.documents
    (root / "documents" / next(iter(m.documents.values()))).write_text("wrong")
    with pytest.raises(ix.IndexRefused, match="content digest"):
        ix._materialize(c, cfg, root)


def test_source_digest_is_checked_before_publish(collection, config, tmp_path):
    c, source = collection
    (source / next(iter(c.documents.values()))).write_text("different collection")
    root = tmp_path / "entry"
    root.mkdir()
    with pytest.raises(ix.IndexRefused, match="source content digest"):
        ix._materialize(c, replace(config, documents_dir=source), root)
    assert not (root / "entry.json").exists()


def test_foreign_and_unowned_data_folders_refused_before_launcher(collection, config, tmp_path, monkeypatch):
    c, _ = collection
    env = env_for(config)
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: dict(env))
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    data = tmp_path / "data"
    data.mkdir()
    (data / "foreign-index").write_text("not ours")
    cfg = replace(config, cache_root=tmp_path / "cache", data_dir=data)
    with pytest.raises(ix.IndexRefused, match="no collection owner"):
        ix.prepare_index(c, cfg)
    ix._write(data / "quality-owner.json", ix._population_owner(c, "foreign-key"))
    with pytest.raises(ix.IndexRefused, match="another collection"):
        ix.prepare_index(c, cfg)
    assert (data / "foreign-index").exists()


@pytest.mark.parametrize("module_name", ["jseval.quality.index", "__main__"])
def test_interrupted_build_is_fresh_and_partial_data_is_not_adopted(collection, config, tmp_path, monkeypatch, module_name):
    c, _ = collection
    env = env_for(config)
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: dict(env))
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    monkeypatch.setattr(ix, "__name__", module_name)
    captured = []
    real_popen = subprocess.Popen
    class FakeProcess:
        def __init__(self, command, **kw):
            captured.append(command)
        def poll(self):
            return 0
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        FakeProcess(command, **kw) if "pausable" in command else real_popen(command, **kw))
    cfg = replace(config, cache_root=tmp_path / "cache")
    with ix.prepare_index(c, cfg) as handle:
        assert handle.index_build == "fresh"
        (handle.data_dir / "partial").write_text("interrupted")
        data = handle.data_dir
    with ix.prepare_index(c, cfg) as handle:
        assert handle.index_build == "fresh"
        assert not (data / "partial").exists()
        assert not (handle.root / "entry.json").exists()
    assert "pausable" in captured[0]
    assert captured[0][-5:-2] == [sys.executable, "-m", "jseval.quality.index"]
    for flag in ("--pause-hook", "--resume-hook"):
        assert ix.shlex.split(captured[0][captured[0].index(flag) + 1])[:3] == [
            sys.executable, "-m", "jseval.quality.index"]
    assert ["nice", "-n", "19", "ionice", "-c3"] == captured[0][captured[0].index("nice"):captured[0].index("nice") + 5]


def test_cache_paths_cannot_escape():
    with pytest.raises(ValueError, match="must be under"):
        ix._under_cache(Path("/tmp/foreign"))


def test_preview_paginates_by_engine_utf16_cursor_and_rejects_truncation():
    pages = iter([{"content": "😀a", "totalChars": 5, "truncated": True, "nextOffsetChars": 3},
                  {"content": "bc", "totalChars": 5, "truncated": False}])
    offsets = []
    def transport(request):
        offsets.append(request.url.params["offsetChars"])
        return httpx.Response(200, json=next(pages))
    with httpx.Client(transport=httpx.MockTransport(transport), base_url="http://engine") as client:
        assert ix._preview(client, "a") == "😀abc"
    assert offsets == ["0", "3"]
    pages = iter([{"content": "\ud83d", "totalChars": 2, "truncated": True, "nextOffsetChars": 1},
                  {"content": "\ude00", "totalChars": 2, "truncated": False}])
    import json
    # JSON permits escaped UTF-16 halves; keep them escaped on the fake wire.
    with httpx.Client(transport=httpx.MockTransport(lambda _:
            httpx.Response(200, content=json.dumps(next(pages)).encode())), base_url="http://engine") as client:
        assert ix._preview(client, "a") == "😀"
    with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, json={
            "contentTruncated": True})), base_url="http://engine") as client:
        with pytest.raises(ix.IndexRefused, match="truncated"):
            ix._preview(client, "a")


def test_population_and_completeness_fail_closed(collection):
    c, _ = collection
    reasons = ix._ready_reasons({}, c, False)
    assert "population.count differs from collection" in reasons
    assert "completeness audits unavailable" in reasons
    assert "NER failed count is not zero" in reasons
    assert "unused sparse stages are enabled" in reasons


def test_confirm_without_running_backend_refuses(collection, config, tmp_path):
    c, _ = collection
    env = env_for(config)
    key, components = ix.compute_index_key(c, config, env)
    handle = ix.IndexHandle(c, config, key, tmp_path, tmp_path, "fresh", components, env, None)
    result = ix.confirm(handle)
    assert not result.ok and "no live serving backend" in result.failures[0]


def test_confirm_allows_query_time_ranking_changes_but_rejects_shape_and_content(
        collection, config, tmp_path, monkeypatch):
    from types import SimpleNamespace
    from copy import deepcopy
    c, source = collection
    cfg = replace(config, documents_dir=source)
    env = env_for(cfg)
    key, components = ix.compute_index_key(c, cfg, env)
    root = tmp_path / "entry"
    data = root / "data"
    data.mkdir(parents=True)
    owner = ix._population_owner(c, key)
    ix._write(data / "quality-owner.json", owner)
    original = {"index_population": c.index_population(), "content": dict(c.content),
                "extracted_content": dict(c.content), "samples": {"ANSWER-1": {"chunks": 2}},
                "probes": [{"ranked": ["ANSWER-1", "answer-2"]}],
                "generation": "generation", "completeness": []}
    ix._write(root / "entry.json", {"owner": owner, "witness": original})
    current = deepcopy(original)
    current["probes"][0]["ranked"].reverse()
    monkeypatch.setattr(ix, "witness", lambda *args: current)
    monkeypatch.setattr(ix.index_identity, "confirm_adoption", lambda *a, **kw:
                        ix.index_identity.ConfirmResult(True, [], {}))
    handle = ix.IndexHandle(c, cfg, key, root, data, "cached", components, env, None,
                           _backend=SimpleNamespace(proc=SimpleNamespace(poll=lambda: None)),
                           base_url="http://engine")
    query_file = cfg.repo_root / "modules/app-services/src/main/java/KnowledgeSearchEngine.java"
    query_file.parent.mkdir(parents=True)
    query_file.write_text("changed fusion weight")
    assert ix.confirm(handle).ok
    # A fresh engine stays owned by its worker until close, before entry.json is
    # published. Extra confirmation uses the worker's witnessed entry receipt.
    import contextlib
    entry = ix._json(root / "entry.json")
    ix._write(root / "prepared.json", {"entry": entry})
    (root / "entry.json").unlink()
    handle._worker = SimpleNamespace(poll=lambda: None)
    with monkeypatch.context() as patch:
        patch.setattr(ix.backend, "_machine_admission", lambda _: contextlib.nullcontext())
        assert ix.confirm(handle).ok
    handle._worker = None
    ix._write(root / "entry.json", entry)
    # Full delivered-order agreement remains a separate, stricter check.
    assert ix._agreement(original, current) == ["witness differs: probes"]
    current["content"]["ANSWER-1"] = sha256_text("wrong live content")
    refused = ix.confirm(handle)
    assert not refused.ok and "witness differs: content" in refused.failures
    current["content"] = dict(c.content)
    # A changed input refuses at once, as not-ready, and is no evidence against the entry.
    (cfg.models_dir / "onnx/model.onnx").write_bytes(b"new index shape")
    with pytest.raises(ix.InputUnavailable, match="index-shaping inputs changed"):
        ix.confirm(handle)


@pytest.mark.parametrize("rank,ok", [(0, True), (2, True), (3, False)])
def test_adoption_probe_is_exact_lexical_and_requires_expected_in_top_three(
        collection, tmp_path, rank, ok):
    c, _ = collection
    docs = tmp_path / "documents"
    # Additional opaque ids let the fourth result exercise the bound.
    m = Collection("probe", "en", {"q": QueryText("query")}, {"q": {"0": 1}},
                   {str(i): f"{i}.txt" for i in range(4)},
                   {str(i): sha256_text(str(i)) for i in range(4)})
    order = ["1", "2", "3"]
    order.insert(rank, "0")
    seen = []
    def transport(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"results": [
            {"fields": {"path": str(docs / m.documents[d])}} for d in order],
            "searchTrace": {"stages": [
                {"id": "sparse-retrieval", "status": "executed"},
                {"id": "dense-retrieval", "status": "skipped"},
                {"id": "cross-encoder", "status": "skipped", "reason": "NAVIGATIONAL_QUERY"}]}})
    with httpx.Client(base_url="http://engine", transport=httpx.MockTransport(transport)) as client:
        request = {"mode": "text", "querySyntax": "search", "limit": 3}
        if ok:
            ix._probe(client, 'unique "quoted" passage', request, m, docs, "0")
        else:
            with pytest.raises(ix.IndexRefused, match="top 3"):
                ix._probe(client, 'unique "quoted" passage', request, m, docs, "0")
    assert seen == [{"mode": "text", "querySyntax": "search", "limit": 3,
                     "query": '"unique \\"quoted\\" passage"', "debug": True}]


def _wait_for_file(path, worker, timeout=30):
    deadline = time.monotonic() + timeout
    while not path.exists():
        assert worker.poll() is None, f"worker exited {worker.returncode} before {path}"
        assert time.monotonic() < deadline, f"deadline waiting for {path}"
        time.sleep(.05)


def _stand_in_gradle_slot(monkeypatch):
    """CI runners have no machine Gradle slot wrapper; tests that stop before any build need a path."""
    which = shutil.which
    monkeypatch.setattr(ix.shutil, "which",
                        lambda name, *a, **kw: "/bin/false" if name == "js-gradle-slot" else which(name, *a, **kw))


def _startup_queue(monkeypatch, *, real):
    """Hold this build at its launcher boundary, before the engine starts from its snapshot.

    The real variant continues through the normal CPU launcher after admission
    resumes it. The small process check ends at that boundary without an engine.
    """
    popen = subprocess.Popen
    script = '''
import sys, time
from pathlib import Path
from jseval.quality import distribution, index as ix
root = Path(sys.argv[1])
real = sys.argv[2] == "True"
distribution.ROOT = Path(sys.argv[3])
original = ix.backend.start_backend
def queued(**kw):
    (root / "startup-queued").touch()
    while not (root / "startup-release").exists():
        time.sleep(.05)
    if real:
        return original(**kw)
    raise RuntimeError("fixture resumed queued startup")
ix.backend.start_backend = queued
ix._build(root)
'''
    def launch(command, **kwargs):
        if "pausable" in command and command[-2] == "build":
            command = command[:-5] + [sys.executable, "-c", script, command[-1], str(real),
                                      str(distribution.ROOT)]
        return popen(command, **kwargs)
    monkeypatch.setattr(ix.subprocess, "Popen", launch)


def _startup_pause_exclusive(handle, admission_dir):
    _wait_for_file(handle.root / "startup-queued", handle._worker, 4200)
    # Gate closure must stop the queued controller and release ALL its work
    # admission. The exclusive command then releases its queue while stopped.
    result = subprocess.run([
        sys.executable, "-I", str(ix.REPO_ROOT / "scripts/dev/admission.py"),
        "exclusive", "--purpose", "startup-pause-exclusive", "--drain-timeout", "3",
        "--timeout", "10", "--", sys.executable, "-c",
        "from pathlib import Path; import sys; "
        "from jseval.quality.index import _json, _process; "
        "root=Path(sys.argv[1]); owner=_json(root/'worker.json'); "
        "assert _process(owner['pid'])['state'] in ('T','t'); "
        "(root/'startup-release').touch()", str(handle.root)],
        env={**os.environ, "JS_ADMISSION_DIR": str(admission_dir),
             "PYTHONPATH": str(ix.REPO_ROOT / "scripts/jseval")},
        capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.skipif(sys.platform != "linux", reason="Linux process-group admission")
def test_startup_pause_exclusive_drains_queued_backend(collection, config, tmp_path, monkeypatch):
    c, source = collection
    admission_dir = tmp_path / "admission"
    monkeypatch.setenv("JS_ADMISSION_DIR", str(admission_dir))
    monkeypatch.delenv("JS_ADMISSION_TOKEN", raising=False)
    # Real launcher and admission processes; only installDist is a controlled queue.
    cfg = replace(config, repo_root=ix.REPO_ROOT, documents_dir=source,
                  cache_root=tmp_path / "cache")
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    _stand_in_gradle_slot(monkeypatch)  # never invoked: the snapshot is already selected
    _startup_queue(monkeypatch, real=False)
    with pytest.raises(ix.IndexRefused, match='fixture resumed queued startup'), ix.prepare_index(c, cfg) as handle:
        _startup_pause_exclusive(handle, admission_dir)
        assert handle._worker.wait(timeout=30) == 1
        assert "fixture resumed queued startup" in (handle.root / "build.log").read_text()
        assert not (handle.root / "entry.json").exists()


@pytest.mark.skipif(os.environ.get("JSEVAL_QUALITY_INDEX_REAL") != "1",
                    reason="set JSEVAL_QUALITY_INDEX_REAL=1 for the real CPU engine lifecycle")
def test_real_cpu_official_subset_agreement_pause_and_foreign_population(monkeypatch, real_inputs):
    import ir_datasets
    from jseval.quality.collections import write_collection
    from jseval.quality.types import document_text
    # Entirely official SciFact abstracts; include the longest to exercise late chunks.
    ds = ir_datasets.load("beir/scifact/test")
    # More than the engine's default five-hit reranking threshold, so the
    # measured hybrid surface can execute every required stage without overrides.
    docs = sorted(ds.docs_iter(), key=lambda d: (-len(d.text), d.doc_id))[:8]
    c = Collection("scifact-cpu-subset", "en", {"q": QueryText("scientific evidence")},
                   {"q": {docs[0].doc_id: 1}},
                   {d.doc_id: f"nested/{d.doc_id}.txt" for d in docs},
                   {d.doc_id: sha256_text(document_text(d.title, d.text)) for d in docs},
                   {"source": {"dataset": "beir/scifact/test"}})
    parent = Path.home() / ".cache/justsearch-quality"
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="lane-b-real-", dir=parent))
    cfg = ix.IndexConfig(cache_root=root / "indexes", timeout_sec=600)
    admission_dir = root / "admission"
    monkeypatch.setenv("JS_ADMISSION_DIR", str(admission_dir))
    monkeypatch.delenv("JS_ADMISSION_TOKEN", raising=False)
    write_collection(c, root / "collection")
    # Interrupt only after installDist/startup: queued shared Gradle slots are
    # waited for, never cancelled. No COMPLETE entry may survive interruption.
    with ix.prepare_index(c, cfg) as interrupted:
        deadline = time.monotonic() + 4200
        while not (interrupted.root / "engine-ready").exists():
            if interrupted._worker.poll() is not None:
                interrupted.wait()
            assert time.monotonic() < deadline, "engine startup deadline"
            time.sleep(.1)
        interrupted_root = interrupted.root
    assert not (interrupted_root / "entry.json").exists()
    _startup_queue(monkeypatch, real=True)
    states, timings = [], {}
    for mode in ("build", "adopt", "restart"):
        start = time.monotonic()
        with ix.prepare_index(c, cfg) as handle:
            assert handle.index_build == ("fresh" if mode == "build" else "cached")
            if mode == "build":
                _startup_pause_exclusive(handle, admission_dir)
                # Let the launcher reach healthy before exercising build-only suspension.
                deadline = time.monotonic() + 3720
                # Suspend while indexing: after the engine accepted the watched root.
                while not (handle.data_dir / "watched_roots.json").exists():
                    if handle._worker.poll() is not None:
                        handle.wait()  # report the real build failure
                    assert time.monotonic() < deadline, "engine startup deadline"
                    time.sleep(.1)
                ix._hook(handle.root, "pause")
                owner = ix._json(handle.root / "worker.json")
                assert ix._process(owner["pid"])["state"] in ("T", "t")
                # A real suspend gap while indexing; the engine must not read it as a hang.
                time.sleep(10)
                ix._hook(handle.root, "resume")
            handle.wait()
            assert ix.confirm(handle).ok
            states.append(handle.index_state)
            timings[mode] = round(time.monotonic() - start, 3)
            # Querying a real engine whose inputs claim another collection must fail.
            foreign = replace(c, name="other", version="")
            assert not ix.confirm(replace(handle, collection=foreign)).ok
            data = handle.data_dir
            if mode == "restart":
                # The real running index is polluted through its real ingest interface.
                extra = root / "foreign-documents"
                extra.mkdir()
                (extra / (sha256_text("foreign") + ".txt")).write_text("An unrelated foreign collection.")
                ix.add_watched_root(handle.base_url, extra)
                deadline = time.monotonic() + 120
                with httpx.Client(base_url=handle.base_url, timeout=10) as client:
                    while ix.readiness.flatten_status(ix._get(client, "/api/status")).get("indexedDocuments") == len(c.documents):
                        assert time.monotonic() < deadline, "foreign document not ingested"
                        time.sleep(.2)
                refused = ix.confirm(handle)
                assert not refused.ok and any("population.count" in r for r in refused.failures)
    with pytest.raises(ix.IndexRefused, match="another collection"):
        ix.prepare_index(foreign, replace(cfg, data_dir=data))
    ix._write(root / "timings.json", timings)
    assert not ix._agreement(states[0], states[1])
    assert not ix._agreement(states[0], states[2])
    assert max(s["chunks"] for s in states[0]["samples"].values()) > 1
    assert any(p["source_word_offset"] >= p["source_word_count"] * .89
               for p in states[0]["probes"])
    # spladeDocCount is the total parent population, even with that stage off
    # (IndexCountOps.querySpladeFeatureCounts). Audit completion measures encoding.
    for state in states:
        assert next(a for a in state["completeness"] if a["stageId"] == "splade")["present"] == 0
        assert all(p["stages"]["dense-retrieval"] == "skipped" for p in state["probes"])
    print(f"CPU lifecycle evidence: {root}; timings={timings}")


@pytest.mark.parametrize("shared_prefix", [False, True])
def test_witness_reads_live_manifest_content_chunks_and_raw_search_paths(collection, config, tmp_path, monkeypatch, shared_prefix):
    c, source = collection
    if shared_prefix:
        prefix = " ".join(f"common{i}" for i in range(12)) + " "
        for rel in c.documents.values():
            path = source / rel
            path.write_text(prefix + path.read_text())
        c = replace(c, content={d: sha256_text((source / rel).read_text())
                                for d, rel in c.documents.items()}, version="")
    root = tmp_path / "entry"
    root.mkdir()
    cfg = replace(config, documents_dir=source)
    m = ix._materialize(c, cfg, root)
    ids = {d: str(root / "documents" / r) for d, r in m.documents.items()}
    texts = {ids[d]: (source / c.documents[d]).read_text() for d in ids}
    status = {"indexState": "IDLE", "indexedDocuments": len(ids), "embeddingEnabled": True,
              "embeddingDocCount": 2, "embeddingCoveragePercent": 100, "chunkDocCount": 0,
              "chunkingNotRequired": True, "spladeEnabled": False, "spladeDocCount": 0,
              "chunkSpladeEnabled": False, "nerEnabled": True, "completedNerCount": 2,
              "failedNerCount": 0, "completenessKnown": True, "activeGenerationId": "generation",
              "completeness": [{"stageId": "embed", "expected": 2, "present": 2,
                                "missing": 0, "failed": 0},
                               {"stageId": "ner", "expected": 2, "present": 2,
                                "missing": 0, "failed": 0},
                               {"stageId": "splade", "expected": 2, "present": 0,
                                "missing": 2, "failed": 0}]}
    stages = [{"id": "sparse-retrieval", "status": "executed"},
              {"id": "dense-retrieval", "status": "skipped"},
              {"id": "cross-encoder", "status": "skipped", "reason": "NAVIGATIONAL_QUERY"}]
    returned_ids = list(ids.values())
    source_hashes = {ids[d]: c.content[d] for d in ids}
    def transport(request):
        path = request.url.path
        if path == "/api/status":
            body = status
        elif path.endswith("document-ids"):
            body = {"docIds": returned_ids, "totalCount": len(returned_ids)}
        elif path == "/api/preview":
            text = texts[request.url.params["docId"]]
            body = {"content": text, "totalChars": len(text), "truncated": False,
                    "sourceSha256": source_hashes[request.url.params["docId"]]}
        elif path.endswith("chunks"):
            body = {"chunksFound": 0}
        elif path.endswith("search"):
            body = {"results": [{"id": p, "fields": {"path": p}} for p in returned_ids],
                    "searchTrace": {"stages": stages, "degradation": {"spladeExecuted": False}}}
        else:
            body = {"build_state": "COMPLETE"}
        return httpx.Response(200, json=body)
    client_class = httpx.Client
    monkeypatch.setattr(ix.httpx, "Client", lambda **kw:
                        client_class(transport=httpx.MockTransport(transport), **kw))
    w = ix.witness("http://engine", c, m, root / "documents", cfg)
    assert w["index_population"] == c.index_population()
    assert w["content"] == c.content
    assert w["samples"] and len(w["probes"]) == 3
    if shared_prefix:
        assert all(len(p["query"].split()) > 12 for p in w["probes"] if p["source_word_offset"] == 0)
    for p in w["probes"]:
        assert sum(p["query"].casefold() in " ".join(text.split()).casefold()
                   for text in texts.values()) == 1
    texts[returned_ids[0]] = texts[returned_ids[0]].strip()  # extraction may normalize whitespace
    normalized = ix.witness("http://engine", c, m, root / "documents", cfg)
    assert normalized["content"] == w["content"]
    assert normalized["extracted_content"] != w["extracted_content"]
    complete = texts[returned_ids[0]]
    texts[returned_ids[0]] = complete[:len(complete) // 2]
    with pytest.raises(ix.IndexRefused, match="source passage missing"):
        ix.witness("http://engine", c, m, root / "documents", cfg)
    texts[returned_ids[0]] = complete
    # Same size population with a foreign full path fails the live manifest boundary.
    returned_ids[0] = str(root / "foreign.txt")
    with pytest.raises(KeyError):
        ix.witness("http://engine", c, m, root / "documents", cfg)
    returned_ids[:] = list(ids.values())
    source_hashes[returned_ids[0]] = sha256_text("foreign content under the same id")
    with pytest.raises(ix.IndexRefused, match="live content digest"):
        ix.witness("http://engine", c, m, root / "documents", cfg)


def test_gpu_selection_is_inherited_and_keyed_and_lifecycle_paths_override_inherited_state(
        collection, config, monkeypatch, tmp_path):
    c, _ = collection
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", "/foreign/data")
    monkeypatch.setenv("JUSTSEARCH_INDEX_BASE_PATH", "/foreign/index")
    monkeypatch.setenv("JUSTSEARCH_SPLADE_ENABLED", "true")
    monkeypatch.setenv("JUSTSEARCH_GPU_ENABLED", "false")
    cpu_key, _ = ix.compute_index_key(c, config, pinned_env(config))
    natives = tmp_path / "cuda"
    natives.mkdir()
    (natives / "libcudart.so.12").write_bytes(b"cuda")
    monkeypatch.setenv("JUSTSEARCH_GPU_ENABLED", "true")
    monkeypatch.setenv("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", str(natives))
    monkeypatch.setenv("JUSTSEARCH_RERANK_GPU_ENABLED", "true")
    env = pinned_env(config)
    # The caller's GPU selection reaches the engine and shapes the reusable index key.
    assert env["JUSTSEARCH_GPU_ENABLED"] == env["JUSTSEARCH_RERANK_GPU_ENABLED"] == "true"
    gpu_key, components = ix.compute_index_key(c, config, env)
    assert gpu_key != cpu_key
    # The selected native directory is keyed by content, not by its path.
    assert components["runtime"]["JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH"] == {"tree": sha256_text(
        ix.canonical_json({"libcudart.so.12": sha256_text("cuda")}))}
    (natives / "libcudart.so.12").write_bytes(b"other cuda")
    assert ix.compute_index_key(c, config, env)[0] != gpu_key
    assert env["JUSTSEARCH_SPLADE_ENABLED"] == env["JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED"] == "false"
    assert "JUSTSEARCH_DATA_DIR" not in env and "JUSTSEARCH_INDEX_BASE_PATH" not in env
    launch = ix._launch_env(config, Path("/owned/data"), env)
    assert launch["JUSTSEARCH_HOME"] == "/owned/data"
    assert launch["JUSTSEARCH_INDEX_BASE_PATH"] == "/owned/data/index/default"
    # Code and repo resources come from the snapshot, never the checkout.
    snapshot = distribution.path(env[ix.DISTRIBUTION_ENV])
    assert launch["JUSTSEARCH_REPO_ROOT"] == str(snapshot)
    assert launch["JUSTSEARCH_SSOT_PATH"] == str(snapshot / "SSOT")
    assert launch["JUSTSEARCH_CONFIG"].startswith(str(snapshot) + "/")


def test_resource_locator_bytes_invalidate_key_and_model_locations_refuse(collection, config, tmp_path):
    c, _ = collection
    catalog = tmp_path / "fields.json"
    catalog.write_bytes(b"one")
    env = {**env_for(config), "JUSTSEARCH_FIELD_CATALOG": str(catalog)}
    key = ix.compute_index_key(c, config, env)[0]
    catalog.write_bytes(b"two")
    assert ix.compute_index_key(c, config, env)[0] != key
    model = tmp_path / "external.onnx"
    model.write_bytes(b"one")
    with pytest.raises(ix.InputUnavailable, match="outside the hashed models root"):
        ix.compute_index_key(c, config, {**env_for(config), "JUSTSEARCH_EMBED_ONNX_MODEL_PATH": str(model)})


def test_runtime_registration_is_not_an_index_shaping_input(collection, config, tmp_path):
    c, _ = collection
    register = tmp_path / "dev-runner-state"
    env = {**env_for(config), "JUSTSEARCH_DEV_RUNNER_STATE_ROOT": str(register)}
    first = ix.compute_index_key(c, config, env)[0]
    register.mkdir()
    (register / "own-backend.json").write_text('{"pid": 123}')
    assert ix.compute_index_key(c, config, env)[0] == first
    (register / "own-backend.json").write_text('{"pid": 456}')
    assert ix.compute_index_key(c, config, env)[0] == first


def test_canary_is_the_first_candidate_whose_search_executes_every_canary_stage(monkeypatch):
    def trace(ce_status, reason=None):
        return {"searchTrace": {"stages": [
            {"id": "sparse-retrieval", "status": "executed"},
            {"id": "dense-retrieval", "status": "executed"},
            {"id": "cross-encoder", "status": ce_status, "reason": reason}]}}
    answers = {"Forwarded by Phillip": trace("skipped", "NAVIGATIONAL_QUERY"),
               "who approved the pipeline contract": trace("executed")}
    asked = []

    def post(_client, path, payload):
        assert path == "/api/knowledge/search"
        # The exact request adoption's canary check replays.
        assert {k: v for k, v in payload.items() if k != "query"} == {
            "mode": "hybrid", "limit": 5, "debug": True}
        asked.append(payload["query"])
        return answers[payload["query"]]

    monkeypatch.setattr(ix, "_post", post)
    candidates = ["Forwarded by Phillip", "Forwarded by Phillip", "who approved the pipeline contract"]
    assert ix._canary_query("http://engine", candidates) == "who approved the pipeline contract"
    assert asked == ["Forwarded by Phillip", "who approved the pipeline contract"]
    with pytest.raises(ix.IndexRefused, match="NAVIGATIONAL_QUERY"):
        ix._canary_query("http://engine", ["Forwarded by Phillip"])
