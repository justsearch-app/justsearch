"""No input that changes index contents leaves the key unchanged; no incomplete snapshot publishes.

One check per review finding: every model package (BGE-M3 included) and the effective digest
sidecars; no shared ORT optimized graph; the installation lock held through snapshot
publication; unreadable folders refuse; no inherited Node code loading; settings keep their
values unless they are listed resource locators; every file a model manifest, the engine
configuration or a resource setting names is resolved as the engine resolves it and lies in
a hashed root or is hashed itself; chat weights are keyed only while the LLM can run.
"""
from __future__ import annotations

import contextlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import textwrap

import pytest

from jseval import backend
from jseval.quality import distribution, index as ix, inputs
from .conftest import REAL, pinned_env, write_checkout
from .helpers import make_collection

GIT = ["git", "-c", "user.name=Test", "-c", "user.email=t@example.com"]
# The engine's per-package model locations and the Node variables that load code, spelled out
# so these checks stand on their own.
MODEL_PATHS = ("JUSTSEARCH_EMBED_ONNX_MODEL_PATH", "JUSTSEARCH_SPLADE_MODEL_PATH", "JUSTSEARCH_NER_MODEL_PATH",
               "JUSTSEARCH_BGE_M3_MODEL_PATH", "JUSTSEARCH_RERANK_MODEL_PATH",
               "JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH", "JUSTSEARCH_CITATION_SCORER_MODEL_PATH")
NODE_LOADING = ("NODE_OPTIONS", "NODE_PATH", "NODE_PRESERVE_SYMLINKS", "NODE_COMPILE_CACHE",
                "NODE_REPL_EXTERNAL_MODULE", "NODE_ICU_DATA")
needs_permissions = pytest.mark.skipif(os.geteuid() == 0, reason="root reads unreadable folders")


@pytest.fixture
def models(tmp_path):
    root = tmp_path / "models"
    for rel, data in {"onnx/embed/model.onnx": b"graph", "onnx/embed/tokenizer.json": b"{}",
                      "splade/naver-splade-v3/model.onnx": b"sparse", "splade/naver-splade-v3/vocab.txt": b"v",
                      "bge-m3/BAAI-bge-m3/model_fp16_with_sparse.onnx": b"bge graph",
                      "bge-m3/BAAI-bge-m3/tokenizer.json": b"{}", "chat.gguf": b"chat"}.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(data)
    return root


def keyed(models, **env):
    c = make_collection()
    cfg = ix.IndexConfig(models_dir=models)
    return ix.compute_index_key(c, cfg, pinned_env(cfg, **env))


def sidecar(model: Path, digest: str, *, size: int | None = None) -> Path:
    st = model.stat()
    path = model.with_name(model.name + ".sha256")
    path.write_text(f"sha256:{digest} mtime:{st.st_mtime_ns // 1_000_000} size:{st.st_size if size is None else size}\n")
    return path


# --------------------------------------------------------------------------- 1, 2: model inputs


def test_every_model_package_discovery_can_select_is_keyed(models):
    key, components = keyed(models)
    assert "bge-m3/BAAI-bge-m3/model_fp16_with_sparse.onnx" in components["models"]
    seen = {key}
    for rel in ("bge-m3/BAAI-bge-m3/model_fp16_with_sparse.onnx", "bge-m3/BAAI-bge-m3/tokenizer.json",
                "onnx/new-package/model.onnx"):
        (models / rel).parent.mkdir(parents=True, exist_ok=True)
        (models / rel).write_bytes(b"changed " + rel.encode())
        new = keyed(models)[0]
        assert new not in seen, rel
        seen.add(new)
    # Bookkeeping whose readers cannot change index contents leaves the key alone.
    current = keyed(models)[0]
    for rel in ("onnx/embed/model.onnx.optimized", "onnx/embed/model.onnx.cuda.opt-meta",
                "chat.gguf.justsearch-identity.v1", "onnx/embed/model.onnx.sha256.tmp",
                "onnx/embed/.chat-identity-123.tmp"):
        (models / rel).write_bytes(b"bookkeeping")
    assert keyed(models)[0] == current


def test_a_digest_sidecar_the_engine_would_trust_must_match_its_model(models):
    model = models / "bge-m3/BAAI-bge-m3/model_fp16_with_sparse.onnx"
    key = keyed(models)[0]
    sidecar(model, distribution.file_sha256(model))
    assert keyed(models)[0] == key  # a correct sidecar is the engine's cache of hashed bytes
    sidecar(model, distribution.file_sha256(model), size=1)
    assert keyed(models)[0] == key  # stale: Sha256SidecarCache recomputes from the bytes
    # Trusted (mtime and size match) but wrong: the fingerprint stored in the index would differ.
    sidecar(model, "0" * 64)
    with pytest.raises(ix.InputUnavailable, match="sidecar .* disagrees"):
        keyed(models)


@pytest.mark.parametrize("name", MODEL_PATHS)
def test_a_model_package_outside_the_hashed_root_refuses(models, tmp_path, monkeypatch, name):
    package = tmp_path / "elsewhere"
    package.mkdir()
    monkeypatch.setenv(name, str(package))
    with pytest.raises(ix.InputUnavailable, match="outside the hashed models root"):
        keyed(models)


@needs_permissions
def test_an_unreadable_model_folder_refuses(models):
    hidden = models / "bge-m3/BAAI-bge-m3"
    hidden.chmod(0)
    try:
        with pytest.raises(ix.InputUnavailable, match="unreadable folder"):
            keyed(models)
    finally:
        hidden.chmod(0o755)


def test_quality_engines_never_load_a_shared_optimized_graph(models, tmp_path, monkeypatch):
    shared = tmp_path / "shared-ort-cache"
    monkeypatch.setenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR", str(shared))
    monkeypatch.setenv("JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB", "16384")
    cfg = ix.IndexConfig(models_dir=models)
    env = pinned_env(cfg)
    assert env["JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB"] == "0"
    data = tmp_path / "entry/data"
    launch = ix._launch_env(cfg, data, env)
    assert launch["JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB"] == "0"
    assert launch["JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR"] == str(data / "ort-optimized")
    with pytest.raises(ix.InputUnavailable, match="optimized-graph cache"):
        ix.compute_index_key(make_collection(), cfg, {**env, "JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB": "16384"})
    with pytest.raises(ValueError, match="lifecycle-owned"):
        replace_env = ix.IndexConfig(models_dir=models, env={"JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR": str(shared)})
        replace_env.environment()


def test_a_models_folder_beside_the_hashed_root_refuses_the_launch(models, tmp_path):
    cfg = ix.IndexConfig(models_dir=models)
    env = pinned_env(cfg)
    data = tmp_path / "entry/data"
    (data / "models/onnx/embed").mkdir(parents=True)
    with pytest.raises(ix.InputUnavailable, match="outside the hashed models root"):
        ix._launch_env(cfg, data, env)


# --------------------------------------------------------------------------- 3, 4: snapshots


def test_a_snapshot_missing_a_classpath_jar_is_never_published(tmp_path):
    checkout = write_checkout(tmp_path / "checkout")
    (checkout / distribution.INSTALL / "bin/ui").write_text(
        "#!/bin/sh\nCLASSPATH=$APP_HOME/lib/app.jar:$APP_HOME/lib/core.jar\n")
    with pytest.raises(distribution.DistributionRefused, match=r"classpath jars missing \['lib/core.jar'\]"):
        distribution.create(checkout, ["c", None])
    assert not list((distribution.ROOT / "snapshots").glob("*.json"))


@needs_permissions
def test_an_unreadable_install_folder_is_never_published(tmp_path):
    checkout = write_checkout(tmp_path / "checkout")
    hidden = checkout / distribution.INSTALL / "native/linux-x86_64"
    hidden.mkdir(parents=True)
    (hidden / "justsearch-bound-exec").write_bytes(b"helper")
    hidden.chmod(0)
    try:
        with pytest.raises(distribution.DistributionRefused, match="unreadable folder"):
            distribution.create(checkout, ["c", None])
    finally:
        hidden.chmod(0o755)
    assert not list((distribution.ROOT / "snapshots").glob("*.json"))


def test_the_installation_lock_is_held_through_snapshot_publication(tmp_path, monkeypatch):
    """Real Gradle slot: a second installer waiting for the worktree lock gets it only after
    the snapshot of the first install is published, so it can never rewrite it partially."""
    slot = REAL["which"]("js-gradle-slot")
    if slot is None:
        pytest.skip("js-gradle-slot is not installed")
    root = write_checkout(tmp_path / "repo")
    (root / ".gitignore").write_text(".gradle/\nmodules/ui/build/\ninstalling\nobserved\n")
    for rel in ("scripts/lib/tool-invoker.cjs", "scripts/dev/admission.py"):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ix.REPO_ROOT / rel, root / rel)
    # The stand-in installDist rewrites a complete install, as an up-to-date Gradle run would.
    (root / "gradlew").write_text(textwrap.dedent("""\
        #!/bin/sh
        lib=modules/ui/build/install/ui/lib
        printf 'jar bytes' > "$lib/app.jar"
        touch installing
        """))
    (root / "gradlew").chmod(0o755)
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "add", "."], cwd=root, check=True)
    subprocess.run([*GIT, "commit", "-qm", "fixture"], cwd=root, check=True)
    monkeypatch.setenv("JS_ADMISSION_DIR", str(tmp_path / "admission"))
    monkeypatch.setenv("JS_GRADLE_SLOT_DIR", str(tmp_path / "slots"))
    monkeypatch.delenv("JS_ADMISSION_TOKEN", raising=False)
    # A second installer: queues for the worktree lock once the first install holds it, then
    # starts rewriting the install (receipt and a jar gone), as installDist does.
    waiter = subprocess.Popen([sys.executable, "-c", textwrap.dedent("""\
        import fcntl, os, sys, time
        from pathlib import Path
        root, store = Path(sys.argv[1]), Path(sys.argv[2])
        until = time.monotonic() + 60
        while not (root / "installing").exists():
            assert time.monotonic() < until
            time.sleep(0.01)
        with (root / ".gradle/agent-build.lock").open("a+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            published = any((store / "sources").glob("*.json"))
            install = root / "modules/ui/build/install/ui"
            (install / ".jseval-source.json").unlink(missing_ok=True)
            (install / "lib/app.jar").unlink()
            (root / "observed").write_text("published" if published else "unpublished")
        """), str(root), str(distribution.ROOT)])
    real_create = distribution.create

    def create(*args):
        # Code that snapshots after the slot ends lets the second installer in first.
        with contextlib.suppress(subprocess.TimeoutExpired):
            waiter.wait(timeout=30)
        return real_create(*args)
    monkeypatch.setattr(distribution, "create", create)
    refused = None
    try:
        digest = REAL["select_distribution"](ix.IndexConfig(repo_root=root), {"JSEVAL_GRADLE_SLOT": slot}, False)
    except ix.IndexRefused as e:
        refused = e
    finally:
        waiter.wait(timeout=60)
    assert (root / "observed").read_text() == "published"
    assert refused is None
    snapshot = distribution.path(digest)
    assert (snapshot / distribution.INSTALL / "lib/app.jar").read_bytes() == b"jar bytes"
    assert distribution.lookup(list(backend.source_identity(root))) == digest


# --------------------------------------------------------------------------- 5: Node


def test_the_launch_environment_drops_node_code_loading(tmp_path, monkeypatch):
    checkout = write_checkout(tmp_path / "checkout")
    snapshot = distribution.path(distribution.create(checkout, ["c", None]))
    preload = tmp_path / "preload.js"
    preload.write_text("process.env.JUSTSEARCH_CHUNK_SIZE = '1';\n")
    for name in NODE_LOADING:
        monkeypatch.setenv(name, f"--require {preload}" if name == "NODE_OPTIONS" else str(tmp_path))
    launched = {}

    class Proc:
        pid, stdout = 4242, None

        def poll(self):
            return None
    monkeypatch.setattr(backend.subprocess, "Popen", lambda cmd, **kw: launched.update(kw) or Proc())
    monkeypatch.setattr(backend, "bind_command", lambda root, cmd: cmd)
    monkeypatch.setattr(backend, "_wait_for_health", lambda *a: True)
    monkeypatch.setattr(backend.run_register, "register_backend", lambda **kw: None)
    data = tmp_path / "data"
    (data / "index/default").mkdir(parents=True)
    (data / "index/default/segments_1").touch()
    backend.start_backend(repo_root=checkout, data_dir=data, distribution=snapshot, prepared_only=True,
                          startup_admission=False, port=1,
                          env_overrides={"NODE_OPTIONS": f"--require {preload}"})
    assert not set(NODE_LOADING) & set(launched["env"])


def test_the_jdk_resolver_never_runs_an_inherited_node_preload(tmp_path):
    if REAL["which"]("node") is None:
        pytest.skip("node is not installed")
    marker = tmp_path / "preloaded"
    preload = tmp_path / "preload.js"
    preload.write_text(f"require('fs').writeFileSync({json.dumps(str(marker))}, 'ran');\n")
    environ = {**os.environ, "NODE_OPTIONS": f"--require {preload}"}
    with contextlib.suppress(inputs.InputRefused):
        REAL["resolve_jdk"](ix.REPO_ROOT, environ)
    assert not marker.exists()


# --------------------------------------------------------------------------- 6: settings


def test_settings_keep_their_values_and_only_listed_locators_key_by_content(models, tmp_path):
    secret = keyed(models, JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES="/missing,secret")
    public = keyed(models, JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES="/missing,public")
    assert secret[0] != public[0]
    assert public[1]["runtime"]["JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES"] == "/missing,public"
    # An unknown absolute-looking value is a value, even when it names an existing folder.
    assert keyed(models, JUSTSEARCH_SOME_SETTING=str(tmp_path))[1]["runtime"]["JUSTSEARCH_SOME_SETTING"] == str(tmp_path)
    # A listed locator keys by the content it names, never by its location.
    one, two = tmp_path / "one/fields.json", tmp_path / "two/fields.json"
    for path in (one, two):
        path.parent.mkdir()
        path.write_text("{}")
    assert keyed(models, JUSTSEARCH_FIELD_CATALOG=str(one))[0] == keyed(models, JUSTSEARCH_FIELD_CATALOG=str(two))[0]
    two.write_text('{"x": 1}')
    assert keyed(models, JUSTSEARCH_FIELD_CATALOG=str(one))[0] != keyed(models, JUSTSEARCH_FIELD_CATALOG=str(two))[0]


# --------------------------------------------------------------------------- 7: file references

ENGINE_CONFIG = "modules/ui/src/main/resources/headless-config/application.yaml"


def ner(models: Path, **manifest) -> Path:
    """A NER package whose model_manifest.json carries ``manifest`` beside its CPU graph."""
    package = models / "onnx/ner"
    package.mkdir(parents=True, exist_ok=True)
    (package / "model.onnx").write_bytes(b"ner graph")
    if not (package / "tokenizer.json").is_symlink():
        (package / "tokenizer.json").write_text("{}")
    (package / "model_manifest.json").write_text(json.dumps({"cpu": "model.onnx", **manifest}))
    return package


@pytest.mark.parametrize("reference", ["absolute", "climbing"])
def test_a_manifest_reference_outside_the_models_root_refuses(models, tmp_path, reference):
    outside = tmp_path / "outside/tokenizer.json"
    outside.parent.mkdir()
    outside.write_text("{}")
    # BertNerInference loads modelDir.resolve(manifest.tokenizer()): an absolute name replaces
    # the folder, a relative one climbs from onnx/ner.
    ner(models, tokenizer=str(outside) if reference == "absolute" else "../../../outside/tokenizer.json")
    with pytest.raises(ix.InputUnavailable, match="tokenizer=.* outside the hashed models root"):
        keyed(models)


def test_a_manifest_reference_climbing_inside_the_models_root_is_keyed(models):
    ner(models, tokenizer="../embed/tokenizer.json", label_config="../../onnx/embed/tokenizer.json")
    key = keyed(models)[0]
    (models / "onnx/embed/tokenizer.json").write_text('{"changed": 1}')
    assert keyed(models)[0] != key


def test_a_link_inside_the_models_root_resolves_as_the_engine_follows_it(models, tmp_path):
    outside = tmp_path / "outside"
    (outside / "dir").mkdir(parents=True)
    (outside / "tokenizer.json").write_text("{}")
    package = ner(models)
    (package / "shared").symlink_to(outside / "dir", target_is_directory=True)
    # The OS follows shared before '..': the engine reads outside/tokenizer.json, although the
    # lexically normalized name (onnx/ner/tokenizer.json) is hashed.
    ner(models, tokenizer="shared/../tokenizer.json")
    with pytest.raises(ix.InputUnavailable, match="outside the hashed models root"):
        keyed(models)
    # A link to a file is hashed by the content it names, wherever that is.
    ner(models)
    (package / "tokenizer.json").unlink()
    (package / "tokenizer.json").symlink_to(outside / "tokenizer.json")
    key = keyed(models)[0]
    (outside / "tokenizer.json").write_text('{"changed": 1}')
    assert keyed(models)[0] != key


@pytest.mark.parametrize("value, accepted", [("SSOT/catalogs/fields.v1.json", True),
                                             ("{outside}", False), ("../../catalog.json", False)])
def test_an_engine_configuration_reference_outside_the_snapshot_refuses(models, tmp_path, value, accepted):
    catalog = tmp_path / "catalog.json"
    catalog.write_text("{}")
    checkout = write_checkout(tmp_path / "checkout")
    (checkout / ENGINE_CONFIG).write_text(f"index:\n  field_catalog: {value.format(outside=catalog)}\n")
    cfg = ix.IndexConfig(models_dir=models)
    env = {**pinned_env(cfg), ix.DISTRIBUTION_ENV: distribution.create(checkout, ["configured", None])}
    if accepted:
        ix.compute_index_key(make_collection(), cfg, env)
        return
    with pytest.raises(ix.InputUnavailable, match="field_catalog=.* outside the snapshot"):
        ix.compute_index_key(make_collection(), cfg, env)


def test_a_relative_resource_setting_resolves_against_the_engines_working_directory(models, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)  # what the harness process happens to run in names nothing
    cfg = ix.IndexConfig(models_dir=models)
    # The engine runs in snapshots/<digest>; from there ../../catalog.json is beside snapshots/.
    catalog = distribution.ROOT / "catalog.json"
    catalog.parent.mkdir(parents=True, exist_ok=True)
    catalog.write_text("{}")
    monkeypatch.setenv("JUSTSEARCH_FIELD_CATALOG", "../../catalog.json")
    key, components = keyed(models)
    assert components["runtime"]["JUSTSEARCH_FIELD_CATALOG"] == {"file": distribution.file_sha256(catalog)}
    catalog.write_text('{"x": 1}')
    assert keyed(models)[0] != key
    # The engine receives the location that was hashed, never the relative value.
    env = pinned_env(cfg)
    assert ix._launch_env(cfg, tmp_path / "entry/data", env)["JUSTSEARCH_FIELD_CATALOG"] == str(catalog.resolve())
    with pytest.raises(ix.InputUnavailable, match="not resolved against the engine's working directory"):
        ix.compute_index_key(make_collection(), cfg, {**env, "JUSTSEARCH_FIELD_CATALOG": "../../catalog.json"})


# --------------------------------------------------------------------------- 8: chat weights


def test_chat_weights_leave_the_key_alone_while_the_llm_is_off(models):
    c = make_collection()
    cfg = ix.IndexConfig(models_dir=models)
    env = pinned_env(cfg)
    assert env["JUSTSEARCH_LLM_ENABLED"] == "false"
    key, components = ix.compute_index_key(c, cfg, env)
    assert "chat.gguf" not in components["models"]
    (models / "chat.gguf").write_bytes(b"other chat weights")
    (models / "compact").mkdir()
    (models / "compact/mmproj-F16.gguf").write_bytes(b"projector")
    assert ix.compute_index_key(c, cfg, env)[0] == key
    # With the LLM in any other state the weights are keyed.
    on = {**env, "JUSTSEARCH_LLM_ENABLED": "true"}
    enabled = ix.compute_index_key(c, cfg, on)[0]
    (models / "chat.gguf").write_bytes(b"third chat weights")
    assert ix.compute_index_key(c, cfg, on)[0] != enabled
    # Indexing models keep their whole closure.
    (models / "onnx/embed/tokenizer.json").write_text('{"changed": 1}')
    assert ix.compute_index_key(c, cfg, env)[0] != key
