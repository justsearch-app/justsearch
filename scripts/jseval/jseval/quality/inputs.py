"""Effective index-shaping inputs: pinned explicitly for the launch, then hashed by content.

Each resolver either returns what the engine will use (passed to the launch explicitly, so the
engine's own discovery never runs) or raises :class:`InputRefused` with the reason. A refusal
says an input is unusable or unpinned; it is never evidence against a stored index.
"""
from __future__ import annotations

import importlib
import os
from pathlib import Path
import re
import shutil
import subprocess

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
# Generated model bookkeeping whose readers cannot change index contents (checked):
# *.sha256 is Sha256SidecarCache's mtime+size-validated copy of a digest of the hashed bytes;
# the legacy ORT optimized siblings are only ever deleted (OrtOptimizedModelStore.cleanupLegacy).
MODEL_SIDECARS = (".sha256", ".optimized", ".opt-meta", ".cuda.optimized", ".cuda.opt-meta")
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
    if memo not in _jdk_cache:
        node = shutil.which("node", path=environ.get("PATH"))
        if not node or not resolver.is_file():
            raise InputRefused("JDK resolver unavailable (node or resolve-jdk.cjs missing)")
        result = subprocess.run([node, "-e", "process.stdout.write(require(process.argv[1]).resolveJdkHome())",
                                 str(resolver)], env=dict(environ), capture_output=True, text=True, timeout=60)
        if result.returncode != 0 or not result.stdout.strip():
            raise InputRefused(f"no usable JDK: {result.stderr.strip()[-500:]}")
        _jdk_cache[memo] = Path(result.stdout.strip())
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


def library_path(value: str) -> list:
    """LD_LIBRARY_PATH as the ordered content of its folders, never their locations."""
    return [located(entry) for entry in value.split(os.pathsep) if entry]


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


def model_hashes(models: Path) -> dict[str, str]:
    """Bytes of every model package under onnx/ and splade/: graphs, external tensors,
    tokenizers, manifests and configuration sidecars. Links are followed; a dangling link,
    an LFS pointer or an unreadable file is an unresolved dependency and refuses."""
    out = {}
    for root in (models / "onnx", models / "splade"):
        if not root.is_dir():
            continue
        digests = _hashing(distribution.tree_digest, root, lambda rel: rel.endswith(MODEL_SIDECARS))
        for rel in digests:
            with (root / rel).open("rb") as stream:
                if stream.read(len(LFS_POINTER)) == LFS_POINTER:
                    raise InputRefused(f"model is an LFS pointer: {root / rel}; pass --models-dir /path/to/shared/models")
        out.update({f"{root.name}/{rel}": sha for rel, sha in digests.items()})
    if not out:
        raise InputRefused(f"no model inputs under {models}; pass --models-dir /path/to/shared/models")
    return out


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


# --------------------------------------------------------------------------- location independence


def located(value: str) -> dict:
    """A path-valued setting as the content it locates; the path itself never enters the key."""
    p = Path(value)
    if not p.exists():
        return {"absent": True}
    if p.is_dir():
        return {"tree": sha256_text(canonical_json(_hashing(distribution.tree_digest, p)))}
    return {"file": _hashing(distribution.file_sha256, p.resolve())}


def runtime(env, skip) -> dict:
    out = {}
    for name, value in env.items():
        if name in skip:
            continue
        out[name] = located(value) if name.startswith("JUSTSEARCH_") and os.path.isabs(value) else value
    return out
