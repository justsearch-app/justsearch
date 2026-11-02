"""Index trust boundaries; real CPU lifecycle is opt-in via JSEVAL_QUALITY_INDEX_REAL=1."""
from dataclasses import replace
import os
from pathlib import Path
import subprocess
import tempfile
import time

import httpx
import pytest

from jseval.quality import index as ix
from jseval.quality.types import Collection, QueryText, sha256_text


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
    return {"JUSTSEARCH_MODELS_DIR": str(config.models_dir), "JUSTSEARCH_SPLADE_ENABLED": "false"}


def test_key_ignores_head_and_reviewed_request_file_but_hashes_every_other_input(collection, config):
    c, _ = collection
    key = lambda: ix.compute_index_key(c, config, env_for(config))[0]
    first = key()
    # Even a repository without HEAD can key its actual tree bytes.
    excluded = config.repo_root / next(p for p in ix.QUERY_TIME_ONLY if p.endswith("KnowledgeSearchEngine.java"))
    excluded.parent.mkdir(parents=True)
    excluded.write_text("request change")
    assert key() == first
    shape = config.repo_root / "modules/indexing/ChunkSplitter.java"
    shape.parent.mkdir(parents=True)
    shape.write_text(" ")
    assert key() != first
    shape.unlink()
    assert key() == first
    (config.repo_root / "shape.java").write_text("shape ")
    assert key() != first


def test_model_bytes_runtime_collection_and_content_invalidate(collection, config):
    c, _ = collection
    env = env_for(config)
    key = ix.compute_index_key(c, config, env)[0]
    model = config.models_dir / "onnx/model.onnx"
    model.write_bytes(b"same size changed!")
    assert ix.compute_index_key(c, config, env)[0] != key
    model.write_bytes(b"model actual bytes")
    assert ix.compute_index_key(c, config, {**env, "JUSTSEARCH_CHUNK_SIZE": "123"})[0] != key
    assert ix.compute_index_key(replace(c, name="other", version=""), config, env)[0] != key
    assert ix.compute_index_key(replace(c, content={d: sha256_text("edited") for d in c.documents}, version=""), config, env)[0] != key


def test_tracked_deletion_changes_key(collection, config):
    subprocess.run(["git", "add", "."], cwd=config.repo_root, check=True)
    before = ix.tree_hash(config.repo_root)
    (config.repo_root / "shape.java").unlink()
    assert ix.tree_hash(config.repo_root) != before


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
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: env_for(config))
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


def test_interrupted_build_is_fresh_and_partial_data_is_not_adopted(collection, config, tmp_path, monkeypatch):
    c, _ = collection
    monkeypatch.setattr(ix.IndexConfig, "environment", lambda _: env_for(config))
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    captured = []
    real_popen = subprocess.Popen
    class FakeProcess:
        def __init__(self, command, **kw):
            captured.append(command)
        def poll(self):
            return 0
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        real_popen(command, **kw) if command[0] == "git" else FakeProcess(command, **kw))
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
    assert captured[0][-10:-4] != []
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
    handle = ix.IndexHandle(c, config, "key", tmp_path, tmp_path, "fresh", {}, {}, None)
    result = ix.confirm(handle)
    assert not result.ok and "no live serving backend" in result.failures[0]


@pytest.mark.skipif(os.environ.get("JSEVAL_QUALITY_INDEX_REAL") != "1",
                    reason="set JSEVAL_QUALITY_INDEX_REAL=1 for the real CPU engine lifecycle")
def test_real_cpu_official_subset_agreement_pause_and_foreign_population():
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
    states, timings = [], {}
    for mode in ("build", "adopt", "restart"):
        start = time.monotonic()
        with ix.prepare_index(c, cfg) as handle:
            assert handle.index_build == ("fresh" if mode == "build" else "cached")
            if mode == "build":
                # Let the launcher reach healthy before exercising build-only suspension.
                deadline = time.monotonic() + 3720
                while not (handle.root / "engine-ready").exists():
                    if handle._worker.poll() is not None:
                        handle.wait()  # report the real build failure
                    assert time.monotonic() < deadline, "engine startup deadline"
                    time.sleep(.1)
                ix._hook(handle.root, "pause")
                time.sleep(.2)
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
    assert not ix._agreement(states[0], states[1])
    assert not ix._agreement(states[0], states[2])
    assert max(s["chunks"] for s in states[0]["samples"].values()) > 1
    ix._write(root / "timings.json", timings)
    print(f"CPU lifecycle evidence: {root}; timings={timings}")


def test_witness_reads_live_manifest_content_chunks_and_raw_search_paths(collection, config, tmp_path, monkeypatch):
    c, source = collection
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
    stages = [{"id": s, "status": "executed"} for s in
              ("sparse-retrieval", "dense-retrieval", "cross-encoder")]
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


def test_cpu_stage_selection_and_lifecycle_paths_override_inherited_state(config, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATA_DIR", "/foreign/data")
    monkeypatch.setenv("JUSTSEARCH_INDEX_BASE_PATH", "/foreign/index")
    monkeypatch.setenv("JUSTSEARCH_RERANK_GPU_ENABLED", "true")
    monkeypatch.setattr(ix.shutil, "which", lambda _: "/usr/bin/slot")
    env = config.environment()
    assert env["JUSTSEARCH_GPU_ENABLED"] == env["JUSTSEARCH_RERANK_GPU_ENABLED"] == "false"
    assert env["JUSTSEARCH_SPLADE_ENABLED"] == env["JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED"] == "false"
    assert "JUSTSEARCH_DATA_DIR" not in env and "JUSTSEARCH_INDEX_BASE_PATH" not in env
    launch = ix._launch_env(config, Path("/owned/data"), env)
    assert launch["JUSTSEARCH_HOME"] == "/owned/data"
    assert launch["JUSTSEARCH_INDEX_BASE_PATH"] == "/owned/data/index/default"


def test_external_runtime_model_bytes_invalidate_key(collection, config, tmp_path):
    c, _ = collection
    model = tmp_path / "external.onnx"
    model.write_bytes(b"one")
    env = {**env_for(config), "JUSTSEARCH_EMBED_MODEL_PATH": str(model)}
    key = ix.compute_index_key(c, config, env)[0]
    model.write_bytes(b"two")
    assert ix.compute_index_key(c, config, env)[0] != key


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
