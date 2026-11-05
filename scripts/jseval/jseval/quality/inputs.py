"""Effective index-shaping inputs: pinned explicitly for the launch, then hashed by content.

Each resolver either returns what the engine will use (passed to the launch explicitly, so the
engine's own discovery never runs) or raises :class:`InputRefused` with the reason. A refusal
says an input is unusable or unpinned; it is never evidence against a stored index.
"""
from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import yaml

from .. import backend
from . import distribution
from .types import canonical_json, sha256_text

JVM_OPTION_VARIABLES = ("JAVA_OPTS", "UI_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
# Whole values, per variable. An allowed value still enters the key; the list only admits
# mechanisms that load no code or option files. Empty (unset) is always allowed.
_HEAPS = frozenset(f"-Xmx{n}g" for n in (2, 4, 6, 8, 12, 16, 24, 32))
_HEADLESS = frozenset({"-Djava.awt.headless=true"})  # the real-use gpu.env value
ALLOWED_JVM_OPTIONS = {"JAVA_OPTS": _HEAPS, "UI_OPTS": _HEAPS, "JAVA_TOOL_OPTIONS": _HEADLESS,
                       "JDK_JAVA_OPTIONS": frozenset(), "_JAVA_OPTIONS": frozenset()}
_CODE_LOADING = re.compile(r"(^|\s)(@|-javaagent|-agentlib|-agentpath|-XX:VMOptionsFile|-XX:Flags"
                           r"|-Djustsearch\.extraction\.sandbox\.command)")
# Environment that injects native code outside every hashed root. LD_LIBRARY_PATH is a
# native selection instead: pinned for the launch and keyed by its folders' content.
LOADER_VARIABLES = ("LD_PRELOAD", "LD_AUDIT")
# The preparation harness: what materializes, ingests, launches and witnesses an index.
# Scoring, comparison, reporting, query planning and corpus acquisition are not listed.
HARNESS_MODULES = ("jseval.quality.index", "jseval.quality.collections", "jseval.quality.types",
                   "jseval.quality.distribution", "jseval.quality.inputs", "jseval.backend",
                   "jseval.ingest", "jseval.index_identity", "jseval.corpus_identity", "jseval.types",
                   "jsharness.readiness", "jsharness.types")
# Model-root bookkeeping, checked reader by reader; nothing else under the root is excluded.
# - ORT legacy optimized siblings: OrtOptimizedModelStore.cleanupLegacy only deletes them.
# - Chat identity receipts (*.justsearch-identity.v1): read only by InferenceConfig to identify
#   the chat model, and the chat model is off for quality runs (see CHAT_WEIGHTS).
# - Writer temporaries: Sha256SidecarCache's *.sha256.tmp and ChatArtifactIdentity's
#   .chat-identity-*.tmp are only ever renamed onto the two sidecar names.
# *.sha256 sidecars are not excluded blindly: model fingerprints (stored in index commit
# metadata) come from them, so each is validated as Sha256SidecarCache reads it.
MODEL_BOOKKEEPING = (".optimized", ".opt-meta", ".cuda.optimized", ".cuda.opt-meta",
                     ".justsearch-identity.v1", ".sha256.tmp")
SHA256_SIDECAR = ".sha256"
# Chat weights and vision projectors (GGUF): read only by the inference child, which is never
# created while the LLM is off. Quality runs force JUSTSEARCH_LLM_ENABLED=false
# (IndexConfig.environment); the engine then decides no inference
# (InferenceDecision.decideInferenceConfigured requires llmEnabled) and
# BootstrapInferenceFactory.create returns before InferenceConfig resolves any model file.
# With the LLM in any other state they are hashed like every other model file.
CHAT_WEIGHTS = ".gguf"
# File fields of model_manifest.json and their defaults (ModelManifest's compact constructor;
# Jackson reads them snake_case). cpu/gpu have no default file: unset means no reference.
MANIFEST = "model_manifest.json"
MANIFEST_FILES = {"cpu": None, "gpu": None, "tokenizer": "tokenizer.json",
                  "pooling_config": "pooling_config.json", "label_config": "config.json"}
# Per-package model locations bypass the hashed models root; JUSTSEARCH_MODELS_DIR selects it.
MODEL_PATH_OVERRIDES = ("JUSTSEARCH_EMBED_ONNX_MODEL_PATH", "JUSTSEARCH_SPLADE_MODEL_PATH",
                        "JUSTSEARCH_NER_MODEL_PATH", "JUSTSEARCH_BGE_M3_MODEL_PATH",
                        "JUSTSEARCH_RERANK_MODEL_PATH", "JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH",
                        "JUSTSEARCH_CITATION_SCORER_MODEL_PATH")
# Settings whose value names a resource the engine reads: keyed by that resource's content,
# never its location. Every other setting enters the key as its literal value.
RESOURCE_LOCATORS = frozenset({"JUSTSEARCH_FIELD_CATALOG", "JUSTSEARCH_MCP_HOST_CONFIG",
                               "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", "JUSTSEARCH_NATIVE_PATH",
                               "JUSTSEARCH_SERVER_EXE", "JUSTSEARCH_LLM_MODEL_PATH"})
# The selected engine configuration inside a snapshot (JUSTSEARCH_CONFIG for every launch).
ENGINE_CONFIG = "modules/ui/src/main/resources/headless-config/application.yaml"
LFS_POINTER = b"version https://git-lfs.github.com/spec/v1\n"


class InputRefused(RuntimeError):
    """An effective input could not be resolved, hashed or validated."""


def _hashing(fn, *args):
    try:
        return fn(*args)
    except (OSError, distribution.DistributionRefused) as e:
        raise InputRefused(f"unreadable input: {e}") from e


# --------------------------------------------------------------------------- JVM options


def jvm_options(environ) -> dict[str, str]:
    """Each option variable, validated: unset, empty or exactly an allow-listed value."""
    out = {}
    for name in JVM_OPTION_VARIABLES:
        value = environ.get(name, "")
        if value.strip() and value not in ALLOWED_JVM_OPTIONS[name]:
            kind = ("an argument file, VM option file, agent or extraction-command override"
                    if _CODE_LOADING.search(value) else "not an allow-listed value")
            raise InputRefused(f"{name}={value!r} is {kind}; unset it for a reusable index")
        out[name] = value if value.strip() else ""
    if environ.get("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND", "").strip():
        raise InputRefused("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND overrides the extraction "
                           "command outside the snapshot; unset it for a reusable index")
    for name in LOADER_VARIABLES:
        if environ.get(name, "").strip():
            raise InputRefused(f"{name} changes which native libraries load; unset it for a reusable index")
    return out


# --------------------------------------------------------------------------- JDK

_jdk_cache: dict = {}


def resolve_jdk(checkout: Path, environ) -> Path:
    """The launcher's own resolver (scripts/dev/lib/resolve-jdk.cjs), run once per inputs."""
    resolver = checkout / "scripts/dev/lib/resolve-jdk.cjs"
    memo = (str(resolver), environ.get("JAVA_HOME"), environ.get("JUSTSEARCH_DEV_JDK_HOME"), environ.get("PATH"))
    for name in ("JAVA_HOME", "JUSTSEARCH_DEV_JDK_HOME"):
        value = (environ.get(name) or "").strip()
        if value and not os.path.isabs(value):
            raise InputRefused(f"{name}={value!r} is relative to an unknown base; give an absolute path")
    if memo not in _jdk_cache:
        node = shutil.which("node", path=environ.get("PATH"))
        if not node or not resolver.is_file():
            raise InputRefused("JDK resolver unavailable (node or resolve-jdk.cjs missing)")
        result = subprocess.run([node, "-e", "process.stdout.write(require(process.argv[1]).resolveJdkHome())",
                                 str(resolver)], env=backend.without_node_code_loading(environ),
                                capture_output=True, text=True, timeout=60)
        if result.returncode != 0 or not result.stdout.strip():
            raise InputRefused(f"no usable JDK: {result.stderr.strip()[-500:]}")
        home = result.stdout.strip()
        if not os.path.isabs(home):
            raise InputRefused(f"the JDK resolver returned a relative home {home!r}")
        _jdk_cache[memo] = Path(home)
    return _jdk_cache[memo]


def jdk_digest(home: Path) -> dict[str, str]:
    """Runtime content: release, launcher, lib/modules and every native library under lib/."""
    files = [home / "release", home / "bin/java", home / "lib/modules"]
    missing = [p.name for p in files if not p.is_file()]
    if missing:
        raise InputRefused(f"JDK runtime incomplete at the resolved home: missing {missing}")
    out = {p.relative_to(home).as_posix(): _hashing(distribution.file_sha256, p.resolve()) for p in files}
    for rel, path in distribution._walk(home / "lib"):
        if path.name.endswith(".so") or ".so." in path.name or path.name.endswith((".dylib", ".dll")):
            out[f"lib/{rel}"] = _hashing(distribution.file_sha256, path.resolve())
    return out


# --------------------------------------------------------------------------- native / GPU


def native_selection(env) -> None:
    """GPU use and ORT/CUDA natives must be explicit; automatic discovery is never keyed."""
    gpu = env.get("JUSTSEARCH_GPU_ENABLED", "").strip().lower()
    if gpu not in ("true", "false"):
        raise InputRefused("automatic GPU/native discovery is not pinned: set JUSTSEARCH_GPU_ENABLED=false "
                           "(CPU), or true together with JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH")
    if gpu == "true" and not env.get("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", "").strip():
        raise InputRefused("GPU enabled without JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH: CUDA natives would "
                           "come from automatic discovery; set the native directory explicitly")


# --------------------------------------------------------------------------- OCR

_ocr_cache: dict = {}


def _executable(path: Path) -> Path | None:
    return path if path.is_file() and os.access(path, os.X_OK) else None


def _in_dir(directory: Path) -> Path | None:
    return _executable(directory / "tesseract") if directory.is_dir() else None


def _path_like(value: str | None) -> Path | None:
    if not value or not value.strip():
        return None
    p = Path(value)
    return _in_dir(p) if p.is_dir() else _executable(p)


def resolve_ocr(environ, launch_dir: Path) -> tuple[Path, Path]:
    """Tesseract and its language data, in TikaOcrRuntime's order, to be passed explicitly.

    Order: JUSTSEARCH_TESSERACT_PATH, TESSERACT_PATH, the bundle roots above the launch
    directory, PATH. Language data: JUSTSEARCH_TESSDATA_PATH, the tessdata folders beside the
    executable, TESSDATA_PREFIX, and otherwise the folder tesseract itself reports.
    """
    memo = tuple(environ.get(k) for k in ("JUSTSEARCH_TESSERACT_PATH", "TESSERACT_PATH", "PATH",
                                          "JUSTSEARCH_TESSDATA_PATH", "TESSDATA_PREFIX")) + (str(launch_dir),)
    if memo in _ocr_cache:
        return _ocr_cache[memo]
    for name in ("JUSTSEARCH_TESSERACT_PATH", "TESSERACT_PATH", "JUSTSEARCH_TESSDATA_PATH", "TESSDATA_PREFIX"):
        value = (environ.get(name) or "").strip()
        if value and not os.path.isabs(value):
            raise InputRefused(f"{name}={value!r} is relative to an unknown base; give an absolute path")
    exe = _path_like(environ.get("JUSTSEARCH_TESSERACT_PATH")) or _path_like(environ.get("TESSERACT_PATH"))
    if exe is None and (environ.get("JUSTSEARCH_TESSERACT_PATH") or "").strip():
        raise InputRefused("unresolvable OCR runtime: JUSTSEARCH_TESSERACT_PATH is not an executable tesseract")
    if exe is None:
        current = launch_dir.resolve()
        for _ in range(6):
            exe = _in_dir(current / "native-bin/tesseract")
            if exe or current.parent == current:
                break
            current = current.parent
    if exe is None:
        found = shutil.which("tesseract", path=environ.get("PATH"))
        exe = Path(found) if found else None
    if exe is None:
        raise InputRefused("unresolvable OCR runtime: tesseract not found; install it or set JUSTSEARCH_TESSERACT_PATH")
    exe = exe.resolve()
    data = None
    explicit = (environ.get("JUSTSEARCH_TESSDATA_PATH") or "").strip()
    if explicit:
        data = Path(explicit) if Path(explicit).is_dir() else None
        if data is None:
            raise InputRefused("unresolvable OCR runtime: JUSTSEARCH_TESSDATA_PATH is not a directory")
    for candidate in (exe.parent / "tessdata", exe.parent.parent / "tessdata", exe.parent / "share/tessdata"):
        if data is None and candidate.is_dir():
            data = candidate
    if data is None and (environ.get("TESSDATA_PREFIX") or "").strip() and Path(environ["TESSDATA_PREFIX"]).is_dir():
        data = Path(environ["TESSDATA_PREFIX"])
    if data is None:
        listed = subprocess.run([str(exe), "--list-langs"], capture_output=True, text=True, timeout=30,
                                env={k: v for k, v in environ.items() if k != "TESSDATA_PREFIX"})
        match = re.search(r'languages in "([^"]+)"', listed.stdout + listed.stderr)
        data = Path(match.group(1)) if match and Path(match.group(1)).is_dir() else None
    if data is None:
        raise InputRefused("unresolvable OCR runtime: no tesseract language data folder")
    _ocr_cache[memo] = (exe, data.resolve())
    return _ocr_cache[memo]


def _libraries(exe: Path) -> dict[str, str]:
    ldd = shutil.which("ldd")
    if not ldd:
        raise InputRefused("unresolvable OCR runtime: ldd unavailable to list tesseract's libraries")
    out = subprocess.run([ldd, str(exe)], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise InputRefused(f"unresolvable OCR runtime: ldd failed: {out.stderr.strip()[-300:]}")
    libs = {}
    for line in out.stdout.splitlines():
        line = line.strip()
        if "not found" in line:
            raise InputRefused(f"unresolvable OCR runtime: library {line.split()[0]} not found")
        m = re.match(r"(\S+) => (/\S+)", line) or re.match(r"(/\S+)", line)
        if m:
            target = Path(m.group(m.lastindex))
            libs[Path(m.group(1)).name] = _hashing(distribution.file_sha256, target.resolve())
    return libs


def ocr_digest(exe: Path, data: Path) -> dict:
    return {"executable": _hashing(distribution.file_sha256, exe), "libraries": _libraries(exe),
            "language_data": _hashing(distribution.tree_digest, data)}


# --------------------------------------------------------------------------- models


def llm_off(env) -> bool:
    """The LLM is off exactly as quality runs pin it; any other value counts as enabled."""
    return env.get("JUSTSEARCH_LLM_ENABLED") == "false"


def _bookkeeping(rel: str) -> bool:
    name = rel.rsplit("/", 1)[-1]
    return (name.endswith(MODEL_BOOKKEEPING + (SHA256_SIDECAR,))
            or (name.startswith(".chat-identity-") and name.endswith(".tmp")))


def _unread(llm_off: bool):
    """Files under the models root that never shape an index for this launch."""
    return lambda rel: _bookkeeping(rel) or (llm_off and rel.endswith(CHAT_WEIGHTS))


def _covered(candidate: str, root: str, contents: set[str], unread, what: str) -> None:
    """A model reference must name hashed bytes, a folder in the hashed root, or a file that
    would be hashed once it appears there; anything else changes indexing outside the key."""
    real = os.path.realpath(candidate)
    inside = os.path.commonpath([real, root]) == root
    if os.path.isfile(real):
        if real in contents:
            return
        reason = "a file the models hash leaves out" if inside else "outside the hashed models root"
    elif os.path.isdir(real):
        if inside:
            return
        reason = "a folder outside the hashed models root"
    elif os.path.lexists(real) or not inside:
        reason = "outside the hashed models root"
    elif unread(os.path.relpath(real, root).replace(os.sep, "/")):
        reason = "a file the models hash leaves out"
    else:
        return  # absent inside the root: it would enter the hash as soon as it appears
    raise InputRefused(f"model manifest reference {what} resolves to {real}, {reason}; "
                       f"keep every file a model package reads inside the models root")


def _manifest_references(models: Path, hashed: dict[str, str], unread) -> None:
    """Every file a model_manifest.json names, resolved as the engine's readers resolve it.

    The readers take the package folder under JUSTSEARCH_MODELS_DIR and resolve each field
    against it, two ways: Path.resolve, where the OS follows links before '..'
    (BertNerInference.buildAssembly, ModelManifest.resolveModelPath/resolveExistingModelFile,
    ModelAssetFingerprint, InferenceCompositionRoot), and resolve().normalize(), where '..'
    applies first (ModelCapabilityResolver.inputCandidates). An absolute field replaces the
    folder. Both resolutions must be covered.
    """
    root = os.path.realpath(models)
    contents = {os.path.realpath(models / rel) for rel in hashed}
    for rel in hashed:
        if rel.rsplit("/", 1)[-1] != MANIFEST:
            continue
        manifest = models / rel
        try:
            doc = json.loads(manifest.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, ValueError) as e:
            raise InputRefused(f"unreadable model manifest {manifest}: {e}") from None
        if not isinstance(doc, dict):
            raise InputRefused(f"model manifest {manifest} is not a JSON object")
        folder = os.path.abspath(manifest.parent)
        for field, default in MANIFEST_FILES.items():
            value = doc.get(field)
            if value is None or (isinstance(value, str) and not value.strip()):
                value = default
            if value is None:
                continue
            if not isinstance(value, str):
                raise InputRefused(f"model manifest {manifest}: {field} is not a file name")
            joined = os.path.join(folder, value)
            for candidate in (joined, os.path.normpath(joined)):
                _covered(candidate, root, contents, unread, f"{rel}: {field}={value!r}")


def _effective_sidecar(sidecar: Path) -> str | None:
    """The digest Sha256SidecarCache.readSidecar would return for its model file, or None."""
    model = sidecar.with_name(sidecar.name[:-len(SHA256_SIDECAR)])
    if not model.is_file() or not sidecar.is_file():
        return None  # getOrCompute never consults it
    try:
        parts = sidecar.read_text(encoding="utf-8").split()
    except UnicodeDecodeError:
        return None  # Files.readString fails; the reader recomputes
    digest, mtime, size = None, -1, -1
    try:
        for part in parts:
            if part.startswith("sha256:"):
                digest = part[len("sha256:"):]
            elif part.startswith("mtime:"):
                mtime = int(part[len("mtime:"):])
            elif part.startswith("size:"):
                size = int(part[len("size:"):])
    except ValueError:
        return None
    st = model.stat()
    return digest if digest and len(digest) == 64 and mtime == st.st_mtime_ns // 1_000_000 \
        and size == st.st_size else None


def model_hashes(models: Path, *, llm_off: bool) -> dict[str, str]:
    """Bytes of the whole models root: every package discovery can select (onnx/, splade/,
    bge-m3/, manifests, tokenizers, configuration), and chat weights unless the LLM is off.
    Links are followed; a dangling link, an LFS pointer, an unreadable file or folder, a
    manifest reference outside the hashed bytes, or a digest sidecar the engine would trust
    that disagrees with its model's bytes refuses."""
    if not models.is_dir():
        raise InputRefused(f"no models root at {models}; pass --models-dir /path/to/shared/models")
    unread = _unread(llm_off)
    out = _hashing(distribution.tree_digest, models, unread)
    for rel in out:
        with (models / rel).open("rb") as stream:
            if stream.read(len(LFS_POINTER)) == LFS_POINTER:
                raise InputRefused(f"model is an LFS pointer: {models / rel}; pass --models-dir /path/to/shared/models")
    for rel, path in _hashing(lambda: list(distribution._walk(models))):
        if rel.endswith(SHA256_SIDECAR):
            claimed = _hashing(_effective_sidecar, path)
            actual = out.get(rel[:-len(SHA256_SIDECAR)])
            if claimed is not None and claimed != actual:
                raise InputRefused(f"model digest sidecar {path} disagrees with its model's bytes; "
                                   f"delete it so the engine recomputes it")
    if not any(rel.endswith(".onnx") for rel in out):
        raise InputRefused(f"no model inputs under {models}; pass --models-dir /path/to/shared/models")
    _manifest_references(models, out, unread)
    return out


def model_selection(env) -> None:
    """Every model package comes from the hashed models root; per-package locations refuse."""
    for name in MODEL_PATH_OVERRIDES:
        if env.get(name, "").strip():
            raise InputRefused(f"{name} selects a model package outside the hashed models root; "
                               f"unset it and point JUSTSEARCH_MODELS_DIR at a models root instead")


# --------------------------------------------------------------------------- harness

_harness: dict | None = None


def harness_digest() -> dict[str, str]:
    """The preparation modules as loaded by this process (their own files, not the checkout's)."""
    global _harness
    if _harness is None:
        out = {}
        for name in HARNESS_MODULES:
            module = importlib.import_module(name)
            out[name] = _hashing(distribution.file_sha256, Path(module.__file__).resolve())
        _harness = out
    return dict(_harness)


# --------------------------------------------------------------------------- locations

# The engine's working directory is the snapshot: backend._boot_admitted starts the eval
# launcher with cwd=<snapshot>, and scripts/dev/eval-runner.cjs starts the JVM with
# cwd: root, root being the snapshot (path.resolve(__dirname, '../..')). Path.of, file reads
# and the dynamic loader resolve relative locations against it.


def pin_locations(env, workdir: Path) -> dict[str, str]:
    """Listed resource settings and LD_LIBRARY_PATH as absolute, link-free locations,
    resolved once against the engine's working directory; the launch receives these."""
    def absolute(value: str) -> str:
        if value.startswith("~"):
            raise InputRefused(f"{value!r} relies on home expansion the engine does not perform; "
                               f"give an absolute path")
        return os.path.realpath(os.path.join(workdir, value))
    out = {name: absolute(env[name]) for name in RESOURCE_LOCATORS if env.get(name, "").strip()}
    value = env.get("LD_LIBRARY_PATH", "")
    if value:
        entries = value.split(os.pathsep)
        if not all(entries):
            raise InputRefused("LD_LIBRARY_PATH has an empty entry, which loads from the working "
                               "directory; remove it")
        out["LD_LIBRARY_PATH"] = os.pathsep.join(absolute(entry) for entry in entries)
    return out


def library_path(value: str) -> list:
    """LD_LIBRARY_PATH as the ordered content of its folders, never their locations."""
    return [located(entry) for entry in value.split(os.pathsep)] if value else []


def located(value: str) -> dict:
    """A pinned location as the content it names; the location itself never enters the key."""
    if not os.path.isabs(value):
        raise InputRefused(f"location {value!r} is not resolved against the engine's working "
                           f"directory (pin_locations)")
    p = Path(value)
    if not p.exists():
        return {"absent": True}
    if p.is_dir():
        return {"tree": sha256_text(canonical_json(_hashing(distribution.tree_digest, p)))}
    return {"file": _hashing(distribution.file_sha256, p.resolve())}


def runtime(env, skip) -> dict:
    """Remaining settings: resource locators by content, everything else by literal value."""
    return {name: located(value) if name in RESOURCE_LOCATORS and value.strip() else value
            for name, value in env.items() if name not in skip}


def looks_like_path(value) -> bool:
    return isinstance(value, str) and ("/" in value or "\\" in value or value.startswith("~"))


def recorded(runtime_component: dict) -> dict:
    """The runtime component as run records keep it: path-like values only as digests.

    The key keeps every literal value; a record never carries a machine location.
    """
    return {name: {"sha256": sha256_text(value)} if looks_like_path(value) else value
            for name, value in runtime_component.items()}


def _scalars(node, where=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from _scalars(v, f"{where}.{k}" if where else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _scalars(v, f"{where}[{i}]")
    else:
        yield where, node


def config_references(snapshot: Path) -> None:
    """Every location the selected engine configuration names lies inside the snapshot.

    The snapshot is hashed and immutable, and relative values resolve against it (the
    engine's working directory), so such a reference is covered as written.
    """
    config = snapshot / ENGINE_CONFIG
    try:
        doc = yaml.safe_load(config.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, yaml.YAMLError) as e:
        raise InputRefused(f"unreadable engine configuration {ENGINE_CONFIG}: {e}") from None
    base = os.path.realpath(snapshot)
    for where, value in _scalars(doc):
        if not looks_like_path(value):
            continue
        real = os.path.realpath(os.path.join(base, value))
        if value.startswith("~") or os.path.commonpath([real, base]) != base:
            raise InputRefused(f"{ENGINE_CONFIG} names {where}={value!r} outside the snapshot; "
                               f"set it through a listed resource setting or keep it in the repository")
