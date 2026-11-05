"""Index key redesign (KR1-KR9): reuse unless an index-shaping input changed; fail safe otherwise.

Key/preflight tests without ingestion. The real installDist is replaced by a stand-in that
behaves like an incremental, up-to-date install (it rewrites only the receipt); everything
else -- source identity, snapshots, digests, selection, refusals -- is the real code.
"""
from __future__ import annotations

from dataclasses import replace
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import time
from unittest.mock import Mock

import pytest

from jseval import backend
from jseval.quality import distribution, index as ix, inputs, run
from jseval.quality.types import RunHeader, sha256_text
from .conftest import REAL, pinned_env, write_checkout
from .helpers import header, make_collection

GIT = ["git", "-c", "user.name=Test", "-c", "user.email=t@example.com"]


def git(root: Path, *args: str) -> None:
    subprocess.run([*GIT, *args], cwd=root, check=True, capture_output=True)


@pytest.fixture
def checkout(tmp_path):
    """A committed checkout whose build folder holds an installed distribution."""
    root = write_checkout(tmp_path / "checkout")
    (root / ".gitignore").write_text("modules/ui/build/\n")
    (root / "docs").mkdir()
    (root / "docs/guide.md").write_text("guide\n")
    git(root, "init", "-q")
    git(root, "add", ".")
    git(root, "commit", "-qm", "base")
    return root


@pytest.fixture
def models(tmp_path):
    root = tmp_path / "models"
    for rel, data in {"onnx/embed/model.onnx": b"graph", "onnx/embed/model.onnx_data": b"tensors",
                      "onnx/embed/tokenizer.json": b"{}", "splade/v3/config.json": b"{}"}.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(data)
    return root


@pytest.fixture
def installs(monkeypatch):
    """An up-to-date incremental installDist: the receipt is the only file it rewrites."""
    calls = []

    def install(root, env):
        calls.append(Path(root))
        receipt = Path(root) / distribution.INSTALL / distribution.RECEIPT
        receipt.write_text(json.dumps(list(backend.source_identity(Path(root)))))
    monkeypatch.setattr(backend, "_install_distribution", install)
    monkeypatch.setattr(ix, "_select_distribution", REAL["select_distribution"])
    return calls


def cfg_for(checkout, models, **kw):
    return ix.IndexConfig(repo_root=checkout, models_dir=models, **kw)


def keyed(c, cfg, *, prepared_only=False):
    env = cfg.environment()
    env[ix.DISTRIBUTION_ENV] = ix._select_distribution(cfg, env, prepared_only)
    return ix.compute_index_key(c, cfg, env), env


# --------------------------------------------------------------------------- KR1


def test_kr1_unrelated_edits_rebase_and_equal_worktree_reuse_the_key(checkout, models, installs, tmp_path):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    (key, components), _ = keyed(c, cfg)
    assert len(installs) == 1
    edits = {"docs/guide.md": "edited guide\n", "modules/ui/web/src/App.tsx": "web ui\n",
             ".github/workflows/ci.yml": "ci\n", "scripts/jseval/jseval/quality/scoring.py": "scoring\n",
             "scripts/jseval/jseval/quality/report.py": "report\n",
             "scripts/jseval/jseval/quality/recipes/real-use/queries.jsonl": "{}\n"}
    for rel, text in edits.items():
        (checkout / rel).parent.mkdir(parents=True, exist_ok=True)
        (checkout / rel).write_text(text)
        # A new source identity costs one incremental install; the snapshot digest is unchanged.
        assert keyed(c, cfg)[0][0] == key, rel
    assert len(installs) == 1 + len(edits)
    # A commit (as after a rebase over such changes) is another source identity, same key.
    git(checkout, "add", ".")
    git(checkout, "commit", "-qm", "unrelated")
    assert keyed(c, cfg)[0][0] == key
    # A second worktree at an equal tree shares the snapshot without any install.
    count = len(installs)
    other = tmp_path / "second-worktree"
    git(checkout, "worktree", "add", "-q", "--detach", str(other))
    second = replace(cfg, repo_root=other)
    assert keyed(c, second)[0][0] == key
    assert len(installs) == count
    # Scoring in that worktree finds the same snapshot through its source identity.
    assert keyed(c, second, prepared_only=True)[0][0] == key


def test_kr1_prepare_adopts_the_existing_entry_after_a_docs_edit(checkout, models, installs, tmp_path, monkeypatch):
    c = make_collection()
    cfg = cfg_for(checkout, models, cache_root=tmp_path / "cache")
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    (key, _), _ = keyed(c, cfg)
    root = cfg.cache_root / sha256_text(c.name) / key
    (root / "data").mkdir(parents=True)
    owner = ix._population_owner(c, key)
    ix._write(root / "data/quality-owner.json", owner)
    ix._write(root / "entry.json", {"owner": owner})
    monkeypatch.setattr(ix, "_materialize", lambda *a: c)
    worker = Mock()
    worker.poll.return_value = 0
    real_popen = subprocess.Popen
    monkeypatch.setattr(ix.subprocess, "Popen", lambda command, **kw:
                        worker if "pausable" in command else real_popen(command, **kw))
    (checkout / "docs/guide.md").write_text("docs-only edit\n")
    with ix.prepare_index(c, cfg) as handle:
        assert handle.index_build == "cached" and handle.index_key == key
    # Retention keeps the snapshot of an adopted entry.
    assert ix.DISTRIBUTION_ENV in handle.env
    assert key in {Path(r.read_text().strip()).name for r in (distribution.ROOT / "refs").glob("*/*")}


# --------------------------------------------------------------------------- KR2


def test_kr2_each_index_shaping_input_changes_the_key(checkout, models, installs, tmp_path, monkeypatch):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    (key, _), env = keyed(c, cfg)
    seen = {key}

    def changed(label, mutate, undo=None, *, env_override=None):
        mutate()
        # Source edits come with their install output: a new source identity, a new snapshot.
        (checkout / "src-edit.txt").write_text(label)
        new = keyed(c, cfg)[0][0] if env_override is None else ix.compute_index_key(c, cfg, env_override)[0]
        assert new not in seen, label
        seen.add(new)
        if undo:
            undo()
    install = checkout / distribution.INSTALL
    # Java code: an indexing module and a query-only class both change jar bytes.
    changed("indexing module", lambda: (install / "lib/app.jar").write_bytes(b"jar bytes: chunker edit"))
    changed("query-only class", lambda: (install / "lib/app.jar").write_bytes(b"jar bytes: fusion edit"))
    changed("dependency version", lambda: (install / "lib/app.jar").rename(install / "lib/app-2.0.jar"))
    changed("SSOT catalog", lambda: (checkout / "SSOT/catalogs/fields.v1.json").write_text('{"x": 1}'))
    changed("application.yaml", lambda: (checkout / "modules/ui/src/main/resources/headless-config/"
                                         "application.yaml").write_text("a: 2\n"))
    changed("launch harness", lambda: (checkout / "scripts/dev/lib/lazy.cjs").write_text("module.exports = 2;\n"))
    changed("model graph", lambda: (models / "onnx/embed/model.onnx").write_bytes(b"graph2"))
    changed("external tensors", lambda: (models / "onnx/embed/model.onnx_data").write_bytes(b"tensor2"))
    changed("tokenizer", lambda: (models / "onnx/embed/tokenizer.json").write_bytes(b'{"v": 2}'))
    env = keyed(c, cfg)[1]
    changed("runtime setting", lambda: None, env_override={**env, "JUSTSEARCH_CHUNK_SIZE": "123"})
    jdk = Path(env["JAVA_HOME"])
    changed("JDK modules", lambda: (jdk / "lib/modules").write_bytes(b"other modules"))
    changed("JDK native library", lambda: (jdk / "lib/server/libjvm.so").write_bytes(b"other jvm"))
    changed("allowed JVM option value", lambda: monkeypatch.setenv("JAVA_OPTS", "-Xmx4g"),
            lambda: monkeypatch.delenv("JAVA_OPTS"))
    natives = tmp_path / "ort-cuda/lib"
    natives.mkdir(parents=True)
    (natives / "libonnxruntime_providers_cuda.so").write_bytes(b"provider")
    changed("library path", lambda: monkeypatch.setenv("LD_LIBRARY_PATH", str(natives)))
    changed("library path native file", lambda: (natives / "libonnxruntime_providers_cuda.so").write_bytes(b"p2"))
    module = sys.modules["jseval.quality.collections"]
    fake = tmp_path / "collections.py"
    fake.write_text("# preparation harness edit\n")
    def harness():
        monkeypatch.setattr(module, "__file__", str(fake))
        monkeypatch.setattr(inputs, "_harness", None)
    changed("preparation harness", harness)
    # Document identity, layout and witness formats key through the collection (K1).
    (k, _), env = keyed(c, cfg)
    assert ix.compute_index_key(replace(c, metadata={"layout": "as-is"}, version=""), cfg, env)[0] != k


def test_kr2_ocr_runtime_executable_libraries_and_language_data_change_the_key(tmp_path, monkeypatch):
    data = tmp_path / "tessdata"
    data.mkdir()
    (data / "eng.traineddata").write_bytes(b"eng")
    exe = tmp_path / "tesseract"
    exe.write_bytes(b"\x7fELF fake")
    lib = tmp_path / "libtesseract.so.5"
    lib.write_bytes(b"lib")
    tools = tmp_path / "bin"
    tools.mkdir()
    (tools / "ldd").write_text(f"#!/bin/sh\necho '\tlibtesseract.so.5 => {lib} (0x0)'\n")
    (tools / "ldd").chmod(0o755)
    monkeypatch.setenv("PATH", f"{tools}{os.pathsep}{os.environ['PATH']}")
    first = inputs.ocr_digest(exe, data)
    seen = [first]
    for mutate in (lambda: exe.write_bytes(b"\x7fELF other"), lambda: lib.write_bytes(b"lib2"),
                   lambda: (data / "eng.traineddata").write_bytes(b"eng2"),
                   lambda: (data / "deu.traineddata").write_bytes(b"deu")):
        mutate()
        current = inputs.ocr_digest(exe, data)
        assert current not in seen
        seen.append(current)
    # Library names, not their locations, enter the key.
    assert set(current["libraries"]) == {"libtesseract.so.5"}


# --------------------------------------------------------------------------- KR3


@pytest.mark.parametrize("name,value,reason", [
    ("JAVA_TOOL_OPTIONS", "-javaagent:/tmp/agent.jar", "agent"),
    ("JDK_JAVA_OPTIONS", "@/tmp/options", "argument file"),
    ("_JAVA_OPTIONS", "-XX:VMOptionsFile=/tmp/vm", "VM option file"),
    ("JAVA_OPTS", "-Djustsearch.extraction.sandbox.command=/bin/x", "extraction-command"),
    ("UI_OPTS", "-Xss8m", "not an allow-listed value"),
    ("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true -javaagent:x.jar", "agent"),
    ("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND", "java -cp x Child", "extraction command"),
    ("LD_PRELOAD", "/tmp/hook.so", "native libraries"),
    ("LD_AUDIT", "/tmp/audit.so", "native libraries"),
])
def test_kr3_jvm_and_loader_mechanisms_outside_the_allow_list_refuse(models, monkeypatch, name, value, reason):
    monkeypatch.setenv(name, value)
    with pytest.raises(ix.InputUnavailable, match=reason):
        ix.IndexConfig(models_dir=models).environment()


def test_kr3_the_documented_gpu_env_is_pinned_not_refused(models, tmp_path, monkeypatch):
    natives = tmp_path / "ort-cuda12/lib"
    natives.mkdir(parents=True)
    (natives / "libcudart.so.12").write_bytes(b"cuda")
    monkeypatch.setenv("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", str(natives))
    monkeypatch.setenv("LD_LIBRARY_PATH", str(natives))
    monkeypatch.setenv("JUSTSEARCH_GPU_ENABLED", "true")
    monkeypatch.setenv("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true")
    env = ix.IndexConfig(models_dir=models).environment()
    assert env["LD_LIBRARY_PATH"] == str(natives)
    assert env["JAVA_TOOL_OPTIONS"] == "-Djava.awt.headless=true"
    assert inputs.library_path(env["LD_LIBRARY_PATH"]) == [inputs.located(str(natives))]


@pytest.mark.parametrize("gpu,native,reason", [
    (None, None, "not pinned"), ("auto", None, "not pinned"), ("true", None, "automatic discovery")])
def test_kr3_unpinned_native_discovery_refuses(models, monkeypatch, gpu, native, reason):
    monkeypatch.delenv("JUSTSEARCH_GPU_ENABLED", raising=False)
    if gpu:
        monkeypatch.setenv("JUSTSEARCH_GPU_ENABLED", gpu)
    with pytest.raises(ix.InputUnavailable, match=reason):
        ix.IndexConfig(models_dir=models).environment()


def test_kr3_unresolvable_ocr_runtime_refuses(tmp_path, monkeypatch):
    resolve = REAL["resolve_ocr"]
    empty = tmp_path / "empty-path"
    empty.mkdir()
    with pytest.raises(inputs.InputRefused, match="unresolvable OCR runtime: tesseract not found"):
        resolve({"PATH": str(empty)}, tmp_path)
    with pytest.raises(inputs.InputRefused, match="JUSTSEARCH_TESSERACT_PATH is not an executable"):
        resolve({"PATH": str(empty), "JUSTSEARCH_TESSERACT_PATH": str(tmp_path / "missing")}, tmp_path)
    exe = tmp_path / "bin/tesseract"
    exe.parent.mkdir()
    exe.write_text("#!/bin/sh\nexit 1\n")
    exe.chmod(0o755)
    with pytest.raises(inputs.InputRefused, match="no tesseract language data"):
        resolve({"PATH": str(exe.parent)}, tmp_path)
    (tmp_path / "tessdata").mkdir()  # beside the executable's parent, in the engine's order
    assert resolve({"PATH": str(exe.parent)}, tmp_path) == (exe, tmp_path / "tessdata")
    tools = tmp_path / "tools"
    tools.mkdir()
    (tools / "ldd").write_text("#!/bin/sh\necho '\tlibleptonica.so.6 => not found'\n")
    (tools / "ldd").chmod(0o755)
    monkeypatch.setenv("PATH", str(tools))
    with pytest.raises(inputs.InputRefused, match="libleptonica.so.6 not found"):
        inputs.ocr_digest(exe, tmp_path / "tessdata")


def test_kr3_unresolved_model_dependency_and_missing_snapshot_input_refuse(checkout, models, installs, tmp_path):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    _, env = keyed(c, cfg)
    (models / "onnx/embed/model.onnx_data").unlink()
    (models / "onnx/embed/model.onnx_data").symlink_to(tmp_path / "elsewhere/missing.onnx_data")
    with pytest.raises(ix.InputUnavailable, match="unresolvable input"):
        ix.compute_index_key(c, cfg, env)
    (models / "onnx/embed/model.onnx_data").unlink()
    (models / "onnx/embed/model.onnx_data").write_bytes(b"version https://git-lfs.github.com/spec/v1\noid")
    with pytest.raises(ix.InputUnavailable, match="LFS pointer"):
        ix.compute_index_key(c, cfg, env)
    (models / "onnx/embed/model.onnx_data").write_bytes(b"tensors")
    ix.compute_index_key(c, cfg, env)
    snapshot = distribution.path(env[ix.DISTRIBUTION_ENV])
    jar = snapshot / distribution.INSTALL / "lib/app.jar"
    os.chmod(jar.parent, 0o755)
    jar.unlink()
    with pytest.raises(ix.InputUnavailable, match="bytes differ"):
        ix.compute_index_key(c, cfg, env)
    shutil.rmtree(checkout / "SSOT")
    (checkout / "docs/guide.md").write_text("new source identity\n")
    with pytest.raises(ix.InputUnavailable, match="runtime resource missing: SSOT"):
        keyed(c, cfg)


def test_kr3_input_refusals_never_quarantine_a_valid_entry(checkout, models, installs, tmp_path, monkeypatch):
    c = make_collection()
    cfg = cfg_for(checkout, models, cache_root=tmp_path / "cache")
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    (key, components), env = keyed(c, cfg)
    root = cfg.cache_root / sha256_text(c.name) / key
    (root / "data").mkdir(parents=True)
    owner = ix._population_owner(c, key)
    ix._write(root / "data/quality-owner.json", owner)
    ix._write(root / "entry.json", {"owner": owner})
    monkeypatch.setattr(ix, "_materialize", lambda *a: c)
    model = models / "onnx/embed/model.onnx"
    model.chmod(0)
    try:
        if os.access(model, os.R_OK):
            pytest.skip("running with permissions that read mode-0 files")
        with pytest.raises(ix.InputUnavailable, match="unreadable input"):
            ix.prepare_index(c, cfg)
    finally:
        model.chmod(0o644)
    # During serving: the key is recomputed first and refuses before any content verdict.
    lock = (tmp_path / "lock").open("a+")
    handle = ix.IndexHandle(c, cfg, key, root, root / "data", "cached", components, env, lock)
    monkeypatch.setattr(ix, "_start_serving", lambda *a, **kw: backend.BackendInfo(Mock(), root / "data"))
    monkeypatch.setattr(backend, "stop_backend", Mock())
    model.write_bytes(b"changed while serving")
    with pytest.raises(ix.InputUnavailable, match="inputs changed since preparation"):
        handle.wait(deadline=time.monotonic() + 5)
    assert (root / "entry.json").exists() and (root / "data").exists()
    assert not (root / "quarantine").exists()


# --------------------------------------------------------------------------- KR4


def test_kr4_scoring_refuses_a_snapshot_from_other_sources_before_any_engine(
        checkout, models, installs, tmp_path, monkeypatch):
    c = make_collection()
    cfg = cfg_for(checkout, models, cache_root=tmp_path / "cache")
    keyed(c, cfg)
    assert keyed(c, cfg, prepared_only=True)
    start = Mock(side_effect=AssertionError("engine started"))
    monkeypatch.setattr(backend, "start_backend", start)
    real_popen = subprocess.Popen
    def popen(command, **kw):
        assert "pausable" not in command, "worker started"
        return real_popen(command, **kw)
    monkeypatch.setattr(ix.subprocess, "Popen", popen)
    # A committed Java edit without reinstall.
    java = checkout / "modules/indexing/src/main/java/Chunker.java"
    java.parent.mkdir(parents=True)
    java.write_text("class Chunker {}\n")
    git(checkout, "add", ".")
    git(checkout, "commit", "-qm", "java edit")
    with pytest.raises(ix.IndexRefused, match="stale distribution"):
        ix.prepare_index(c, cfg, prepared_only=True)
    with pytest.raises(run.RunRefused, match="stale distribution"):
        run.execute("check", (), baseline_dir=tmp_path, runs_dir=tmp_path, repo_root=checkout)
    # An uncommitted edit, through the prepared-only path.
    keyed(c, cfg)
    java.write_text("class Chunker { int changed; }\n")
    with pytest.raises(ix.IndexRefused, match="stale distribution"):
        ix.prepare_index(c, cfg, prepared_only=True)
    start.assert_not_called()


def test_kr4_changed_snapshot_bytes_with_an_unchanged_record_are_refused(checkout, models, installs):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    _, env = keyed(c, cfg)
    snapshot = distribution.path(env[ix.DISTRIBUTION_ENV])
    jar = snapshot / distribution.INSTALL / "lib/app.jar"
    jar.chmod(0o644)
    jar.write_bytes(b"jar bytez")  # same size, hard-linked store copy changes with it
    source = list(backend.source_identity(checkout))
    with pytest.raises(distribution.DistributionRefused, match="bytes differ"):
        distribution.current(checkout, source)
    with pytest.raises(ix.InputUnavailable, match="bytes differ"):
        keyed(c, cfg, prepared_only=True)
    # Preparation repairs it from the build folder through a verified store copy.
    (checkout / "docs/guide.md").write_text("reinstall\n")
    _, repaired = keyed(c, cfg)
    assert repaired[ix.DISTRIBUTION_ENV] == env[ix.DISTRIBUTION_ENV]
    assert (snapshot / distribution.INSTALL / "lib/app.jar").read_bytes() == b"jar bytes"


# --------------------------------------------------------------------------- KR5


def test_kr5_equal_bytes_give_equal_digests_and_keys_wherever_they_live(checkout, models, installs, tmp_path):
    first = distribution.create(checkout, ["a", None])
    other = write_checkout(tmp_path / "elsewhere")
    os.utime(other / distribution.INSTALL / "lib/app.jar", (1, 1))
    assert distribution.create(other, ["b", None]) == first
    c = make_collection()
    cfg = cfg_for(checkout, models)
    (key, _), env = keyed(c, cfg)
    moved_models = tmp_path / "moved/models"
    shutil.copytree(models, moved_models)
    os.utime(moved_models / "onnx/embed/model.onnx", (2, 2))
    moved_jdk = tmp_path / "moved/jdk"
    shutil.copytree(env["JAVA_HOME"], moved_jdk)
    moved_data = tmp_path / "moved/tessdata"
    shutil.copytree(env["JUSTSEARCH_TESSDATA_PATH"], moved_data)
    moved = {**env, "JUSTSEARCH_MODELS_DIR": str(moved_models), "JAVA_HOME": str(moved_jdk),
             "JUSTSEARCH_TESSDATA_PATH": str(moved_data)}
    assert ix.compute_index_key(c, replace(cfg, models_dir=moved_models), moved)[0] == key
    # Generated model sidecars the engine rewrites (mtime-validated digests, legacy ORT
    # optimized siblings it only deletes) do not key the index.
    (models / "onnx/embed/model.onnx.sha256").write_text("cached digest")
    (models / "onnx/embed/model.onnx.opt-meta").write_text("legacy")
    assert ix.compute_index_key(c, cfg, env)[0] == key


# --------------------------------------------------------------------------- KR6


def test_kr6_snapshot_is_read_only_and_independent_of_the_build_folder(checkout, models, installs):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    (key, _), env = keyed(c, cfg)
    snapshot = distribution.path(env[ix.DISTRIBUTION_ENV])
    jar = snapshot / distribution.INSTALL / "lib/app.jar"
    assert not jar.stat().st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH)
    assert not jar.parent.stat().st_mode & stat.S_IWUSR
    assert (snapshot / distribution.INSTALL / "bin/ui").stat().st_mode & stat.S_IXUSR
    # A jar replacement in the build folder changes neither what runs nor the key.
    build_jar = checkout / distribution.INSTALL / "lib/app.jar"
    build_jar.unlink()
    build_jar.write_bytes(b"replaced during preparation")
    assert jar.read_bytes() == b"jar bytes"
    assert ix.compute_index_key(c, cfg, env)[0] == key


def test_kr6_a_jar_replaced_during_the_snapshot_refuses_it(checkout, monkeypatch):
    store = distribution._store

    def racing(source, sha, executable):
        if source.name == "app.jar":
            source.write_bytes(b"concurrent reinstall")
        return store(source, sha, executable)
    monkeypatch.setattr(distribution, "_store", racing)
    with pytest.raises(distribution.DistributionRefused, match="changed during snapshot"):
        distribution.create(checkout, ["c", None])
    assert not list((distribution.ROOT / "snapshots").glob("*.json"))
    assert not list((distribution.ROOT / "snapshots").glob(".partial-*"))


def test_kr6_an_interrupted_snapshot_leaves_nothing_adoptable(checkout, monkeypatch):
    link = os.link
    calls = []

    def interrupted(src, dst):
        calls.append(dst)
        if len(calls) == 3:
            raise KeyboardInterrupt
        return link(src, dst)
    monkeypatch.setattr(distribution.os, "link", interrupted)
    with pytest.raises(KeyboardInterrupt):
        distribution.create(checkout, ["c", None])
    snapshots = distribution.ROOT / "snapshots"
    assert not [p for p in snapshots.iterdir() if not p.name.endswith(".lock")]
    assert distribution.lookup(["c", None]) is None
    monkeypatch.setattr(distribution.os, "link", link)
    digest = distribution.create(checkout, ["c", None])
    assert distribution.verify(digest) == digest


def test_kr6_engine_and_runner_launch_from_the_snapshot(checkout, tmp_path, monkeypatch):
    digest = distribution.create(checkout, ["c", None])
    snapshot = distribution.path(digest)
    launched = {}

    class Proc:
        pid, stdout = 4242, None
        def poll(self):
            return None
    def popen(cmd, **kw):
        launched.update(cmd=cmd, cwd=kw["cwd"])
        return Proc()
    monkeypatch.setattr(backend.subprocess, "Popen", popen)
    monkeypatch.setattr(backend, "_install_distribution", Mock(side_effect=AssertionError("installed")))
    monkeypatch.setattr(backend, "bind_command", lambda root, cmd: [str(root / "bound-exec"), *cmd])
    monkeypatch.setattr(backend, "_wait_for_health", lambda *a: True)
    monkeypatch.setattr(backend.run_register, "register_backend", lambda **kw: None)
    data = tmp_path / "data"
    (data / "index/default").mkdir(parents=True)
    (data / "index/default/segments_1").touch()
    backend.start_backend(repo_root=checkout, data_dir=data, distribution=snapshot, prepared_only=True,
                          startup_admission=False, port=1)
    assert launched["cmd"] == [str(snapshot / "bound-exec"), "node", str(snapshot / distribution.LAUNCHER)]
    assert launched["cwd"] == str(snapshot)


def test_kr6_retention_keeps_used_newest_and_referenced_snapshots(checkout, tmp_path):
    old = distribution.create(checkout, ["old", None])
    distribution.publish(checkout, ["old", None], old)
    (checkout / distribution.INSTALL / "lib/app.jar").write_bytes(b"newer jar")
    new = distribution.create(checkout, ["new", None])
    distribution.publish(checkout, ["new", None], new)
    held = distribution.lease(old)
    assert distribution.prune() == []  # in use
    held.close()
    entry = tmp_path / "entry"
    entry.mkdir()
    (entry / "entry.json").write_text("{}")
    distribution.reference(old, entry)
    assert distribution.prune() == []  # referenced by a cache entry
    (entry / "entry.json").unlink()
    assert distribution.prune() == [old]
    assert distribution.verify(new) == new  # newest for its checkout
    assert distribution.lookup(["old", None]) is None
    assert all(p.stat().st_nlink > 1 for p in (distribution.ROOT / "store").glob("*.[rx]"))
    # A removed worktree no longer keeps its newest snapshot.
    gone = write_checkout(tmp_path / "removed-worktree", jar=b"worktree jar")
    only = distribution.create(gone, ["gone", None])
    distribution.publish(gone, ["gone", None], only)
    assert only not in distribution.prune()
    shutil.rmtree(gone)
    assert distribution.prune() == [only]
    assert distribution.verify(new) == new


# --------------------------------------------------------------------------- KR7


def test_kr7_warm_digests_read_no_file_but_a_racy_change_is_always_rehashed(tmp_path, monkeypatch):
    f = tmp_path / "model.onnx"
    f.write_bytes(b"one")
    reads = []
    digest = distribution.hashlib.file_digest
    monkeypatch.setattr(distribution.hashlib, "file_digest", lambda *a: reads.append(1) or digest(*a))
    distribution.file_sha256(f)
    distribution.file_sha256(f)
    assert len(reads) == 2  # younger than the timestamp-tick guard: never trusted from the cache
    f.write_bytes(b"two")  # same size, possibly same tick
    assert distribution.file_sha256(f) == sha256_text("two")
    monkeypatch.setattr(distribution, "RACY_SECONDS", -1)
    reads.clear()
    distribution.file_sha256(f)
    distribution.file_sha256(f)
    assert len(reads) == 1


# --------------------------------------------------------------------------- KR8


def test_kr8_older_index_identity_is_refused_by_name(models, tmp_path, monkeypatch):
    c = make_collection()
    data = tmp_path / "chosen-data"
    data.mkdir()
    ix._write(data / "quality-owner.json", {**ix._population_owner(c, "old-key"),
                                            "schema": "jseval.quality.index.v1"})
    cfg = ix.IndexConfig(models_dir=models, cache_root=tmp_path / "cache", data_dir=data)
    monkeypatch.setattr(ix, "_under_cache", lambda p: p)
    with pytest.raises(ix.IndexRefused, match=r"older index identity \(jseval.quality.index.v1\)"):
        ix.prepare_index(c, cfg)
    assert (data / "quality-owner.json").exists()
    assert ix.SCHEMA == "jseval.quality.index.v2"


# --------------------------------------------------------------------------- KR9


def test_kr9_headers_record_identity_digests_without_machine_paths(checkout, models, installs, tmp_path):
    c = make_collection()
    cfg = cfg_for(checkout, models)
    (key, components), env = keyed(c, cfg)
    identity = ix.identity_digests(components)
    assert set(identity) == {"distribution", "harness", "jdk", "jvm_options", "library_path", "ocr",
                             "models", "runtime", "documents"}
    h = header(c, index_key=key, identity=identity, index_state={"runtime": components["runtime"]})
    text = json.dumps(h.to_dict())
    assert RunHeader.from_dict(json.loads(text)) == h
    for path in (str(tmp_path), str(Path.home()), env["JAVA_HOME"], env["JUSTSEARCH_MODELS_DIR"]):
        assert path not in text
    with pytest.raises(ValueError, match="identity must map"):
        header(c, identity={"models": "/home/someone/models"})
    # Records written before the identity field still load.
    old = h.to_dict()
    del old["identity"]
    assert RunHeader.from_dict(old).identity == {}
