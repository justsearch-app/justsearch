"""Tests for jseval.index_identity (tempdoc 751 WP1).

Covers: canonical-hash stability, working-tree dirty-state hashing against real
tmp git repos, the static selector, and the live identity + confirm_adoption
protocol against a stdlib HTTP server (pytest-httpserver is not available in
this environment, so a threaded http.server stands in).
"""

from __future__ import annotations

import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from jseval import index_identity as ii
from jseval.index_identity import (
    ConfirmResult,
    IdentityUnavailable,
    IndexIdentity,
    SelectorKey,
    compute_live_identity,
    compute_selector,
    confirm_adoption,
)


# --------------------------------------------------------------------------- #
# git repo + models-dir fixtures.
# --------------------------------------------------------------------------- #


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


def _init_repo(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    _git(path, "init", "-q")
    _git(path, "config", "user.email", "t@example.com")
    _git(path, "config", "user.name", "Tester")
    _git(path, "config", "commit.gpgsign", "false")
    (path / "README.md").write_text("hello\n", encoding="utf-8")
    # An in-scope tracked file (dirt scoping, review fix F-B): only dirt under
    # _DIRT_SCOPE_PREFIXES enters the dirty-state hash.
    (path / "modules").mkdir()
    (path / "modules" / "Engine.java").write_text("class Engine {}\n", encoding="utf-8")
    _git(path, "add", "-A")
    _git(path, "commit", "-q", "-m", "init")


@pytest.fixture
def git_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    _init_repo(repo)
    return repo


def _make_models_dir(
    root: Path, *, with_ner: bool = True, with_sidecars: bool = True,
    with_splade: bool = True,
) -> Path:
    models = root / "models"
    (models / "onnx" / "gte-multilingual-base").mkdir(parents=True, exist_ok=True)
    (models / "splade" / "naver-splade-v3").mkdir(parents=True, exist_ok=True)
    (models / "onnx" / "ner").mkdir(parents=True, exist_ok=True)
    if with_sidecars:
        (models / "onnx" / "gte-multilingual-base" / "model_fp16.onnx.sha256").write_text(
            "a" * 64 + "  model_fp16.onnx\n", encoding="utf-8",
        )
    if with_splade:
        (models / "splade" / "naver-splade-v3" / "model.onnx.sha256").write_text(
            "b" * 64 + "\n", encoding="utf-8",
        )
    if with_ner:
        (models / "onnx" / "ner" / "model_manifest.json").write_text(
            json.dumps({"cpu": "model.onnx", "gpu": "model_fp16.onnx"}),
            encoding="utf-8",
        )
    return models


# --------------------------------------------------------------------------- #
# Fake backend (threaded stdlib HTTP server).
# --------------------------------------------------------------------------- #


class FakeBackend:
    """Minimal routable JSON server: ``set(method, path, code, obj)``."""

    def __init__(self) -> None:
        self.routes: dict[tuple[str, str], tuple[int, object]] = {}
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _make_handler())
        self._server.routes = self.routes  # type: ignore[attr-defined]
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def set(self, method: str, path: str, code: int, obj: object) -> None:
        self.routes[(method, path)] = (code, obj)

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()


def _make_handler():
    class _Handler(BaseHTTPRequestHandler):
        def _send(self, code: int, obj: object) -> None:
            body = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _route(self, method: str) -> None:
            path = self.path.split("?", 1)[0]
            spec = self.server.routes.get((method, path))  # type: ignore[attr-defined]
            if spec is None:
                self._send(404, {"error": "not found", "path": path})
                return
            code, obj = spec
            self._send(code, obj)

        def do_GET(self) -> None:  # noqa: N802
            self._route("GET")

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", 0) or 0)
            if length:
                self.rfile.read(length)
            self._route("POST")

        def log_message(self, *args) -> None:  # silence
            pass

    return _Handler


@pytest.fixture
def backend():
    b = FakeBackend()
    try:
        yield b
    finally:
        b.stop()


# --------------------------------------------------------------------------- #
# 1. Canonical-hash stability.
# --------------------------------------------------------------------------- #


def test_canonical_hash_independent_of_insertion_order():
    a = {"z": 1, "a": {"n": 2, "m": 3}, "runtime_config": {"x": "1", "y": "2"}}
    b = {"runtime_config": {"y": "2", "x": "1"}, "a": {"m": 3, "n": 2}, "z": 1}
    assert ii._sha256_canonical(a) == ii._sha256_canonical(b)


def test_index_identity_key_stable_across_component_order():
    comps1 = {"schema_version": "index-identity.v1", "git_sha": "x", "runtime_config": {"a": "1", "b": "2"}}
    comps2 = {"runtime_config": {"b": "2", "a": "1"}, "git_sha": "x", "schema_version": "index-identity.v1"}
    assert ii._sha256_canonical(comps1) == ii._sha256_canonical(comps2)


# --------------------------------------------------------------------------- #
# 2. dirty_state_hash against real git repos.
# --------------------------------------------------------------------------- #


def test_dirty_state_hash_clean_repo_is_stable(git_repo: Path):
    h1 = ii._dirty_state_hash(git_repo)
    h2 = ii._dirty_state_hash(git_repo)
    assert h1 == h2
    assert len(h1) == 64


def test_tracked_modification_in_scope_changes_hash(git_repo: Path):
    clean = ii._dirty_state_hash(git_repo)
    (git_repo / "modules" / "Engine.java").write_text(
        "class Engine { int x; }\n", encoding="utf-8",
    )
    dirty = ii._dirty_state_hash(git_repo)
    assert dirty != clean


def test_tracked_modification_out_of_scope_does_not_change_hash(git_repo: Path):
    """Review fix F-B: routine non-index dirt (docs edits) must not flip the key."""
    clean = ii._dirty_state_hash(git_repo)
    (git_repo / "README.md").write_text("hello world\n", encoding="utf-8")
    assert ii._dirty_state_hash(git_repo) == clean


def test_untracked_source_file_changes_hash_and_is_content_hashed(git_repo: Path):
    clean = ii._dirty_state_hash(git_repo)
    src = git_repo / "modules" / "x.txt"
    src.parent.mkdir(parents=True, exist_ok=True)
    src.write_text("aaa\n", encoding="utf-8")
    h_a = ii._dirty_state_hash(git_repo)
    assert h_a != clean
    # Content change (name unchanged in porcelain) must still change the hash.
    src.write_text("bbb\n", encoding="utf-8")
    h_b = ii._dirty_state_hash(git_repo)
    assert h_b != h_a


def test_untracked_out_of_scope_file_does_not_change_hash(git_repo: Path):
    """Review fix F-B: an untracked file outside _DIRT_SCOPE_PREFIXES is
    invisible to the key entirely (neither porcelain nor content)."""
    clean = ii._dirty_state_hash(git_repo)
    (git_repo / "notes.txt").write_text("first\n", encoding="utf-8")
    assert ii._dirty_state_hash(git_repo) == clean


def test_untracked_jseval_file_changes_hash(git_repo: Path):
    """scripts/jseval/ is in the dirt scope (corpus derivation code, 751 sec I.5)."""
    clean = ii._dirty_state_hash(git_repo)
    (git_repo / "scripts" / "jseval").mkdir(parents=True)
    (git_repo / "scripts" / "jseval" / "ingest.py").write_text("x = 1\n", encoding="utf-8")
    assert ii._dirty_state_hash(git_repo) != clean


def test_oversized_untracked_source_file_is_unavailable(git_repo: Path):
    big = git_repo / "modules" / "big.bin"
    big.parent.mkdir(parents=True, exist_ok=True)
    with open(big, "wb") as fh:
        fh.write(b"\0" * (ii._MAX_UNTRACKED_BYTES + 1))
    with pytest.raises(IdentityUnavailable) as exc:
        ii._dirty_state_hash(git_repo)
    assert "10MB" in exc.value.reason


# --------------------------------------------------------------------------- #
# 3. compute_selector.
# --------------------------------------------------------------------------- #


def test_selector_green(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    sel = compute_selector(git_repo, None, env)
    assert isinstance(sel, SelectorKey)
    assert sel.unavailable_reason is None
    assert sel.key is not None and len(sel.key) == 64
    assert sel.components["git_sha"]
    assert "runtime_config" in sel.components
    assert "corpus_signature" not in sel.components


def test_selector_missing_splade_sidecar_unavailable(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path, with_splade=False)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    sel = compute_selector(git_repo, None, env)
    assert sel.key is None
    assert "splade_model_sha256" in sel.unavailable_reason


def test_selector_missing_embed_sidecar_unavailable(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path, with_sidecars=False)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    sel = compute_selector(git_repo, None, env)
    assert sel.key is None
    assert "embed_model_sha256" in sel.unavailable_reason


def test_selector_env_knob_change_changes_key(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    base_env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    k1 = compute_selector(git_repo, None, base_env).key
    changed = dict(base_env)
    changed["JUSTSEARCH_INDEX_VECTOR_HNSW_M"] = "64"
    k2 = compute_selector(git_repo, None, changed).key
    assert k1 is not None and k2 is not None
    assert k1 != k2


def test_selector_corpus_signature_included_when_dir_given(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "corpus.jsonl").write_text('{"id":"1"}\n', encoding="utf-8")
    sel = compute_selector(git_repo, corpus, env)
    assert sel.key is not None
    assert "corpus_signature" in sel.components
    # A different git repo without corpus produces a different key.
    no_corpus = compute_selector(git_repo, None, env)
    assert sel.key != no_corpus.key


def test_selector_corpus_dir_without_files_unavailable(git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    empty_corpus = tmp_path / "empty-corpus"
    empty_corpus.mkdir()
    sel = compute_selector(git_repo, empty_corpus, env)
    assert sel.key is None
    # 751 P.5: the axis resolver now reports garbage dirs, naming both shapes.
    assert "corpus axis unresolvable" in sel.unavailable_reason
    assert "corpus.jsonl" in sel.unavailable_reason


def test_selector_key_differs_by_corpus_dir_path(git_repo: Path, tmp_path: Path):
    """Review fix F-A: byte-identical corpus at a different absolute path must
    produce a different key (doc identity in the engine is the absolute path,
    so cross-checkout entries are not equivalent)."""
    models = _make_models_dir(tmp_path)
    env = {"JUSTSEARCH_MODELS_DIR": str(models)}
    content = '{"id":"1"}\n'
    a = tmp_path / "checkout-a" / "corpus"
    b = tmp_path / "checkout-b" / "corpus"
    for d in (a, b):
        d.mkdir(parents=True)
        (d / "corpus.jsonl").write_text(content, encoding="utf-8")
    sa = compute_selector(git_repo, a, env)
    sb = compute_selector(git_repo, b, env)
    assert sa.components["corpus_signature"] == sb.components["corpus_signature"]
    assert sa.components["corpus_dir_path"] != sb.components["corpus_dir_path"]
    assert sa.key != sb.key


def _confirm_roots(adopted_dir: Path, entry_doc: dict):
    failures: list = []
    checks: dict = {}
    ii._confirm_watched_roots(adopted_dir, entry_doc, failures.append, checks)
    return failures, checks


def test_confirm_watched_roots_match_passes(tmp_path: Path):
    root_path = str(tmp_path / "corpus-dir")
    (tmp_path / "watched_roots.json").write_text(
        json.dumps({"roots": [{"path": root_path}]}), encoding="utf-8",
    )
    failures, checks = _confirm_roots(
        tmp_path, {"attestation": {"watched_roots": [root_path]}},
    )
    assert failures == []
    assert checks["watched_roots"]["ok"] is True


def test_confirm_watched_roots_foreign_path_fails_named(tmp_path: Path):
    """Review fix F-A check 6: a foreign absolute root in the adopted copy is
    the cross-worktree pollution signature -- must fail with paths named."""
    (tmp_path / "watched_roots.json").write_text(
        json.dumps({"roots": [{"path": r"F:\other-worktree\eval-corpora\x"}]}),
        encoding="utf-8",
    )
    failures, checks = _confirm_roots(
        tmp_path, {"attestation": {"watched_roots": [str(tmp_path / "corpus-dir")]}},
    )
    assert len(failures) == 1
    assert "watched_roots.mismatch" in failures[0]
    assert checks["watched_roots"]["ok"] is False


def test_confirm_watched_roots_unrecorded_with_roots_fails(tmp_path: Path):
    """Pre-F-A entry (no recorded roots) + adopted dir WITH roots = the old
    blind spot -> fail closed."""
    (tmp_path / "watched_roots.json").write_text(
        json.dumps({"roots": [{"path": str(tmp_path / "somewhere")}]}),
        encoding="utf-8",
    )
    failures, _ = _confirm_roots(tmp_path, {"attestation": {}})
    assert len(failures) == 1
    assert "watched_roots.unrecorded" in failures[0]


def test_confirm_watched_roots_both_absent_passes(tmp_path: Path):
    failures, checks = _confirm_roots(
        tmp_path, {"attestation": {"watched_roots": []}},
    )
    assert failures == []
    assert checks["watched_roots"]["ok"] is True


class _FakeResp:
    def __init__(self, status_code, body=None):
        self.status_code = status_code
        self._body = body or {}

    def json(self):
        return self._body


def _canary_entry():
    return {"attestation": {"canary": {"query": "q", "required_stages": ["fusion"]}}}


def _good_canary_body():
    return {
        "totalHits": 3,
        "searchTrace": {"stages": [{"id": "fusion", "status": "executed"}], "degradation": {}},
    }


def test_canary_retries_transient_504_then_passes(monkeypatch):
    """Live finding 2026-07-17: freshly-booted Workers 504 the first search
    while the search path warms; the canary must retry, not void the adoption."""
    monkeypatch.setattr(ii, "_CANARY_WARMUP_SEC", 10.0)
    monkeypatch.setattr(ii.time, "sleep", lambda s: None)
    responses = [_FakeResp(504), _FakeResp(504), _FakeResp(200, _good_canary_body())]
    client = type("C", (), {"post": lambda self, *a, **k: responses.pop(0)})()
    failures: list = []
    checks: dict = {}
    ii._confirm_canary(client, _canary_entry(), failures.append, checks)
    assert failures == []
    assert checks["canary"]["ok"] is True


def test_canary_persistent_504_fails_after_deadline(monkeypatch):
    monkeypatch.setattr(ii, "_CANARY_WARMUP_SEC", 0.0)
    monkeypatch.setattr(ii.time, "sleep", lambda s: None)
    client = type("C", (), {"post": lambda self, *a, **k: _FakeResp(504)})()
    failures: list = []
    checks: dict = {}
    ii._confirm_canary(client, _canary_entry(), failures.append, checks)
    assert len(failures) == 1
    assert "canary.http: HTTP 504" in failures[0]


def test_canary_non_transient_status_fails_immediately(monkeypatch):
    monkeypatch.setattr(ii, "_CANARY_WARMUP_SEC", 60.0)
    calls = {"n": 0}

    def post(self, *a, **k):
        calls["n"] += 1
        return _FakeResp(500)

    client = type("C", (), {"post": post})()
    failures: list = []
    checks: dict = {}
    ii._confirm_canary(client, _canary_entry(), failures.append, checks)
    assert calls["n"] == 1
    assert "canary.http: HTTP 500" in failures[0]


# --------------------------------------------------------------------------- #
# 4. compute_live_identity + confirm_adoption against the fake backend.
# --------------------------------------------------------------------------- #


_COMMIT_META = {
    "embedding_model_sha256": "e" * 64,
    "splade_model_sha256": "5" * 64,
    "field_catalog_hash": "fch",
    "index_fingerprint": "isf",
    "synonyms_hash": "syn",
    "vector_format": "int8_sq",
    "build_state": "COMPLETE",
    "commit_time": "2026-07-17T10:00:00Z",
}


def _live_env(git_repo: Path, models: Path) -> dict:
    return {
        "JUSTSEARCH_REPO_ROOT": str(git_repo),
        "JUSTSEARCH_MODELS_DIR": str(models),
    }


def test_compute_live_identity_green(backend, git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    backend.set("GET", "/api/debug/commit-metadata", 200, dict(_COMMIT_META))
    ident = compute_live_identity(backend.base_url, _live_env(git_repo, models))
    assert isinstance(ident, IndexIdentity)
    assert len(ident.key) == 64
    assert ident.components["embedding_model_sha256"] == "e" * 64
    assert ident.components["ner_model_hash"]
    # build_state / commit_time are attestation-side, NOT in the key components.
    assert "build_state" not in ident.components
    assert "commit_time" not in ident.components


def test_compute_live_identity_commit_metadata_500_unavailable(backend, git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    backend.set("GET", "/api/debug/commit-metadata", 500, {"error": "boom"})
    with pytest.raises(IdentityUnavailable) as exc:
        compute_live_identity(backend.base_url, _live_env(git_repo, models))
    assert "commit-metadata" in exc.value.reason


def test_compute_live_identity_missing_ner_unavailable(backend, git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path, with_ner=False)
    backend.set("GET", "/api/debug/commit-metadata", 200, dict(_COMMIT_META))
    with pytest.raises(IdentityUnavailable) as exc:
        compute_live_identity(backend.base_url, _live_env(git_repo, models))
    assert "ner_model_hash" in exc.value.reason


def test_compute_live_identity_missing_meta_field_unavailable(backend, git_repo: Path, tmp_path: Path):
    models = _make_models_dir(tmp_path)
    meta = dict(_COMMIT_META)
    del meta["index_fingerprint"]
    backend.set("GET", "/api/debug/commit-metadata", 200, meta)
    with pytest.raises(IdentityUnavailable) as exc:
        compute_live_identity(backend.base_url, _live_env(git_repo, models))
    assert "index_fingerprint" in exc.value.reason


# ---- confirm_adoption scenario helpers ------------------------------------ #


def _green_status(data_dir: Path) -> dict:
    return {
        "worker": {
            "migration": {
                "activeGenerationId": "g-1",
                "buildingGenerationId": "",
                "migrationState": "IDLE",
            },
            "enrichment": {
                "embeddingDocCount": 199,
                "spladeDocCount": 199,
                "chunkDocCount": 4293,
                "chunkVectorCoveragePercent": 100.0,
            },
        },
    }


def _green_search() -> dict:
    return {
        "totalHits": 5,
        "searchTrace": {
            "effectiveMode": "hybrid",
            "stages": [
                {"id": "splade-retrieval", "status": "executed"},
                {"id": "dense-retrieval", "status": "executed"},
                {"id": "chunk-merge", "status": "executed"},
                {"id": "fusion", "status": "executed"},
            ],
            "degradation": {},
        },
    }


def _setup_green(backend, data_dir: Path, git_repo: Path, models: Path):
    """Wire all routes green and return (env, entry_doc)."""
    backend.set("GET", "/api/debug/commit-metadata", 200, dict(_COMMIT_META))
    backend.set("GET", "/api/debug/state", 200, {"config": {"justsearch.data.dir": str(data_dir)}})
    backend.set("GET", "/api/status", 200, _green_status(data_dir))
    backend.set("POST", "/api/knowledge/search", 200, _green_search())
    env = _live_env(git_repo, models)
    ident = compute_live_identity(backend.base_url, env)
    entry_doc = {
        "identity": ident.to_doc(),
        "attestation": {
            "generation_id": "g-1",
            "counts": {
                "embeddingDocCount": 199,
                "spladeDocCount": 199,
                "chunkDocCount": 4293,
                "chunkVectorCoveragePercent": 100.0,
            },
            "canary": {
                "query": "contract termination",
                "required_stages": ["chunk-merge", "fusion"],
            },
        },
    }
    return env, entry_doc


def test_confirm_adoption_green(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert isinstance(result, ConfirmResult)
    assert result.ok, result.failures
    assert result.failures == []


def test_confirm_identity_mismatch_names_component(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    # Mutate a live commit-metadata field so the recomputed key diverges.
    changed = dict(_COMMIT_META)
    changed["index_fingerprint"] = "DIFFERENT"
    backend.set("GET", "/api/debug/commit-metadata", 200, changed)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("identity.index_fingerprint:") for f in result.failures), result.failures


def test_confirm_wrong_data_dir(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    other = tmp_path / "somewhere-else"
    other.mkdir()
    backend.set("GET", "/api/debug/state", 200, {"config": {"justsearch.data.dir": str(other)}})
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("binding.data_dir:") for f in result.failures), result.failures


def test_confirm_building_generation_nonempty(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    status = _green_status(data_dir)
    status["worker"]["migration"]["buildingGenerationId"] = "g-2"
    backend.set("GET", "/api/status", 200, status)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("generation.building:") for f in result.failures), result.failures


def test_confirm_migration_not_idle(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    status = _green_status(data_dir)
    status["worker"]["migration"]["migrationState"] = "CUTTING_OVER"
    backend.set("GET", "/api/status", 200, status)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("generation.migration:") for f in result.failures), result.failures


def test_confirm_count_mismatch(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    status = _green_status(data_dir)
    status["worker"]["enrichment"]["chunkDocCount"] = 4000
    backend.set("GET", "/api/status", 200, status)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("counts.chunkDocCount:") for f in result.failures), result.failures


def test_confirm_low_coverage(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    status = _green_status(data_dir)
    status["worker"]["enrichment"]["chunkVectorCoveragePercent"] = 80.0
    backend.set("GET", "/api/status", 200, status)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any("chunkVectorCoveragePercent" in f for f in result.failures), result.failures


def test_confirm_canary_stage_skipped(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    search = _green_search()
    for stage in search["searchTrace"]["stages"]:
        if stage["id"] == "chunk-merge":
            stage["status"] = "skipped"
    backend.set("POST", "/api/knowledge/search", 200, search)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("canary.stage.chunk-merge:") for f in result.failures), result.failures


def test_confirm_canary_degradation(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    search = _green_search()
    search["searchTrace"]["degradation"] = {"vectorBlocked": True}
    backend.set("POST", "/api/knowledge/search", 200, search)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("canary.degradation:") for f in result.failures), result.failures


def test_confirm_status_500_fails_generation_and_counts(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    backend.set("GET", "/api/status", 500, {"error": "boom"})
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("generation.status:") for f in result.failures), result.failures
    assert any(f.startswith("counts.status:") for f in result.failures), result.failures


def test_confirm_collects_all_failures_no_short_circuit(backend, git_repo: Path, tmp_path: Path):
    data_dir = tmp_path / "adopted"
    data_dir.mkdir()
    models = _make_models_dir(tmp_path)
    env, entry_doc = _setup_green(backend, data_dir, git_repo, models)
    # Break binding AND canary simultaneously -> both must be reported.
    backend.set("GET", "/api/debug/state", 200, {"config": {"justsearch.data.dir": str(tmp_path / "elsewhere")}})
    search = _green_search()
    search["totalHits"] = 0
    backend.set("POST", "/api/knowledge/search", 200, search)
    result = confirm_adoption(backend.base_url, entry_doc, data_dir, env)
    assert not result.ok
    assert any(f.startswith("binding.data_dir:") for f in result.failures)
    assert any(f.startswith("canary.totalHits:") for f in result.failures)


# --------------------------------------------------------------------------- #
# 5. IndexIdentity round-trip.
# --------------------------------------------------------------------------- #


def test_index_identity_round_trip():
    comps = {
        "schema_version": "index-identity.v1",
        "git_sha": "0" * 40,
        "runtime_config": {"JUSTSEARCH_EMBED_CONTEXT_LENGTH": "default"},
    }
    ident = IndexIdentity(ii._sha256_canonical(comps), comps)
    doc = ident.to_doc()
    # JSON-serializable.
    round = json.loads(json.dumps(doc))
    restored = IndexIdentity.from_doc(round)
    assert restored == ident
    assert restored.key == ident.key
    assert restored.components == ident.components
