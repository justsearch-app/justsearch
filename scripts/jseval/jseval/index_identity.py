"""Eval-index identity: the single authority for "is this built index the one
these inputs describe?" (tempdoc 751 WP1, design sec M.1/M.2).

751 caches quiesced eval data dirs keyed on ``corpus_signature x
index_identity_key`` and adopts them via a two-phase protocol: a cheap *static
selector* nominates a cache entry before any backend starts, and the *running
backend confirms its own identity* after adoption. This module is the identity
primitive both phases share -- one key computation, never a second forked
authority (751 sec E projection-vs-fork discipline).

Three surfaces, deliberately distinct component sets (751 sec M.1/M.2):

- ``compute_selector`` -- STATIC, no backend. A heuristic candidate selector:
  it may false-positive and is NEVER authoritative. Its inputs are everything
  computable without a running Worker (git identity, model sidecar hashes,
  index-shaping env knobs, corpus signature).
- ``compute_live_identity`` -- AUTHORITATIVE. Reads the config-derived
  fingerprints from the backend's own live surfaces
  (``/api/debug/commit-metadata``). jseval never re-implements a Java
  fingerprint in Python -- that would be two lists that drift (751 sec D.1,
  716's actual lesson). The live key is the one confirmed against.
- ``confirm_adoption`` -- the confirm step: recompute the live key, assert the
  process is bound to the adopted dir, the adopted generation is serving with
  no migration in flight, attestation counts match, and a behavioral canary
  passes. All checks are independent and every failure is collected.

Selector and live keys are computed over DIFFERENT component sets and are
therefore NOT comparable to each other -- the selector nominates, the live key
authorizes. This is by construction: the selector must be computable with no
backend, so it cannot include the commit-metadata index fingerprints or the
live model content hashes.

Live/static model-hash asymmetry (751 sec O.3): the Lucene commit already
carries ``embedding_model_sha256`` and ``splade_model_sha256`` (read live in
the identity), but there is NO live surface for the NER model, so the NER hash
is always read from disk (manifest content, or the model.onnx bytes). The
selector, having no backend at all, reads embed/splade from the on-disk
``.onnx.sha256`` sidecars instead.

Fail closed everywhere: any doubt -- missing sidecar, unavailable git identity,
an oversized untracked source file, a missing commit-metadata field -- yields
an unavailable selector or raises ``IdentityUnavailable`` for the live path.
There is no permissive default and no flag that forces a match.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping

import httpx

from .corpus_identity import corpus_signature

log = logging.getLogger(__name__)

# Schema tag stamped into the identity components (751 sec M.1). Bump only on a
# breaking change to which inputs the key covers.
_SCHEMA_VERSION = "index-identity.v1"
_SELECTOR_SCHEMA_VERSION = "index-selector.v1"

# An untracked source file larger than this makes the whole identity
# unavailable -- fail closed, never silently skip the file (751 sec O.4).
_MAX_UNTRACKED_BYTES = 10 * 1024 * 1024

# Canary warm-up budget: transient 502/503/504 or transport errors retry with
# backoff up to this many seconds after boot (freshly-booted Workers 504 the
# first search while the search path warms -- observed live 2026-07-17).
_CANARY_WARMUP_SEC = 90.0

# Working-tree dirt is scoped to these repo-relative prefixes (review fix F-B,
# 2026-07-17 refute-first audit): dirt anywhere else (docs/, governance/,
# .claude/, ...) cannot shape the built index -- committed state is pinned by
# ``git_sha``, runtime knobs and corpus content are pinned as their own key
# components -- and hashing it made routine multi-agent dirt (tempdoc edits)
# zero the hit rate. ``scripts/jseval/`` IS in scope because corpus
# materialization/derivation code is not otherwise pinned (751 sec I.5).
# Residual risk (accepted, documented): dirt outside these prefixes that
# somehow shapes the index is not covered; blast radius is small because
# ``git_sha`` still pins every committed file.
_DIRT_SCOPE_PREFIXES = ("modules/", "SSOT/", "contracts/", "scripts/jseval/")
# Untracked files under the same prefixes are content-hashed into the
# dirty-state component (their content appears in neither porcelain nor
# ``git diff HEAD``, only their name in porcelain).
_UNTRACKED_CONTENT_PREFIXES = _DIRT_SCOPE_PREFIXES

# Index-shaping runtime knobs whose env/-D overrides bypass ``git_sha`` (751 sec
# A.2/A.3). Env-var names and file:line citations are from
# modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java
# in this worktree (verified 2026-07-17). Both the selector and the live
# identity include this same dict, computed from the same ``spawn_env`` -- one
# source, no drift.
#
# EnvRegistry.java:933  EMBED_CONTEXT_LENGTH      -> base embed context window
# EnvRegistry.java:329  EMBED_LATE_CHUNKING_ENABLED
# EnvRegistry.java:340  EMBED_LATE_CHUNKING_CONTEXT_LENGTH
# EnvRegistry.java:1064 INDEX_VECTOR_HNSW_M       -> HNSW graph connectivity
# EnvRegistry.java:1066 INDEX_VECTOR_HNSW_EF_CONSTRUCTION -> HNSW build effort
# EnvRegistry.java:1072 INDEX_VECTOR_QUANTIZATION_ENABLED -> vector_format
# EnvRegistry.java:1092 RAG_CHUNK_VECTORS_ENABLED
# EnvRegistry.java:1094 RAG_CHUNK_SPLADE_ENABLED
#
# EnvRegistry.java:1069 INDEX_VECTOR_EF_SEARCH is deliberately EXCLUDED: it is a
# query-time HNSW parameter that does not shape the built index, so pinning it
# would only starve the cache (a fresh index is byte-identical across ef_search
# values). Design sec M.1 names "HNSW M/ef" for the index-shaping subset -- M
# and ef_construction are that subset.
_INDEX_SHAPING_ENV_VARS = (
    "JUSTSEARCH_EMBED_CONTEXT_LENGTH",
    "JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED",
    "JUSTSEARCH_EMBED_LATE_CHUNKING_CONTEXT_LENGTH",
    "JUSTSEARCH_INDEX_VECTOR_HNSW_M",
    "JUSTSEARCH_INDEX_VECTOR_HNSW_EF_CONSTRUCTION",
    "JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED",
    "JUSTSEARCH_RAG_CHUNK_VECTORS_ENABLED",
    "JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED",
)

# Commit-metadata fields that enter the live key: the rebuild-requiring
# `index_fingerprint` (tempdoc 915 -- replaces the old five-key parity set,
# including the retired `index_schema_fp` / `analyzer_fp` this dict used to
# carry separately), `field_catalog_hash` + `synonyms_hash` (751 sec A.3 item
# 4), plus the two model content hashes (sec O.3) plus vector_format.
# build_state / commit_time are attestation-side (they vary per build) and
# are NOT read here -- the publish step (WP3) assembles them into the entry
# attestation, not into the identity key.
_LIVE_KEY_META_FIELDS = (
    "embedding_model_sha256",
    "splade_model_sha256",
    "field_catalog_hash",
    "index_fingerprint",
    "synonyms_hash",
    "vector_format",
)

_MISSING = object()


class IdentityUnavailable(Exception):
    """The live index identity cannot be computed (fail closed).

    Carries a machine-readable ``.reason`` naming the missing/oversized input,
    e.g. ``"ner_model_hash: no model_manifest.json or model.onnx ..."``.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class SelectorKey:
    """Static, heuristic candidate key (may false-positive; never authoritative).

    ``key`` is the sha256 hex over the canonical components JSON, or ``None``
    when any input was unavailable (``unavailable_reason`` then names why).
    ``components`` holds whatever inputs were gathered verbatim, even on the
    unavailable path, for miss-reason diffing.
    """

    key: str | None
    components: dict
    unavailable_reason: str | None


@dataclass(frozen=True)
class IndexIdentity:
    """Authoritative index identity read from the live backend surfaces.

    ``key`` is the sha256 hex over the canonical ``components`` JSON.
    ``components`` are the inputs verbatim, carrying ``schema_version``.
    """

    key: str
    components: dict

    def to_doc(self) -> dict:
        """JSON-serializable document (round-trips through :meth:`from_doc`)."""
        return {"key": self.key, "components": dict(self.components)}

    @staticmethod
    def from_doc(doc: dict) -> "IndexIdentity":
        """Reconstruct from a :meth:`to_doc` document."""
        return IndexIdentity(key=doc["key"], components=dict(doc["components"]))


@dataclass(frozen=True)
class ConfirmResult:
    """Outcome of :func:`confirm_adoption`.

    ``ok`` is ``True`` iff ``failures`` is empty. Each failure is a
    machine-readable miss-reason like
    ``"identity.embed_fingerprint: entry=<a> live=<b>"``. ``checks`` records
    per-check detail for diagnostics.
    """

    ok: bool
    failures: list[str]
    checks: dict = field(default_factory=dict)


# --------------------------------------------------------------------------- #
# Canonical hashing (mirrors jseval/manifest.py::_sha256_canonical).
# --------------------------------------------------------------------------- #


def _sha256_canonical(obj: Any) -> str:
    """SHA-256 of canonical JSON (sorted keys, compact separators)."""
    canon = json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()


def _sha256_bytes(path: Path) -> str:
    """Streaming SHA-256 hex of a file's bytes."""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


# --------------------------------------------------------------------------- #
# Git identity + working-tree dirt.
# --------------------------------------------------------------------------- #


def _git_sha_full(repo_root: Path) -> str | None:
    """Full 40-char HEAD sha in ``repo_root``, or ``None`` if unavailable.

    Local definition (not imported from calibrate.py:124's ``_git_sha_full``,
    which runs in the process CWD): identity computation must pin ``repo_root``
    explicitly so a worktree's git state -- not the invoking CWD's -- is what
    the key covers.
    """
    out = _git_bytes(repo_root, ["rev-parse", "HEAD"])
    if out is None:
        return None
    sha = out.decode("utf-8", errors="replace").strip()
    return sha or None


def _git_bytes(repo_root: Path, args: list[str]) -> bytes | None:
    """Run ``git <args>`` in ``repo_root``, returning stdout bytes or ``None``."""
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=str(repo_root),
            capture_output=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as e:  # pragma: no cover - defensive
        log.debug("git %s failed: %s", args, e)
        return None
    if result.returncode != 0:
        log.debug("git %s exited %d", args, result.returncode)
        return None
    return result.stdout


def _dirty_state_hash(repo_root: Path) -> str:
    """Content hash of the working-tree dirt (751 sec O.4).

    Instead of gating on ``git_dirty`` (which would zero the hit rate -- the
    chartering campaigns are all dirty with tracked edits), the dirt is folded
    INTO the key: identical dirty state => identical key => hit; any edit =>
    miss. Composed from:

    - ``git status --porcelain`` output (captures which paths changed / are
      untracked), and
    - ``git diff HEAD`` output (the tracked-file content delta), plus
    - for each untracked path under a source prefix (modules/ SSOT/ contracts/),
      ``relpath\\0 sha256(bytes)`` -- because an untracked file's *content* does
      not appear in either of the above, only its name in porcelain.

    Scoped (review fix F-B) to :data:`_DIRT_SCOPE_PREFIXES`: porcelain lines and
    the tracked diff are filtered/pathspec-limited to those prefixes, so routine
    non-index dirt (docs/tempdocs edits) leaves the key unchanged while any
    engine/SSOT/contracts/jseval dirt still changes it.

    Raises :class:`IdentityUnavailable` if git is unavailable, or if any such
    untracked source file exceeds 10 MB (fail closed -- never skip it).
    """
    porcelain = _git_bytes(repo_root, ["status", "--porcelain"])
    diff = _git_bytes(
        repo_root,
        ["diff", "HEAD", "--", *(p.rstrip("/") for p in _DIRT_SCOPE_PREFIXES)],
    )
    if porcelain is None or diff is None:
        raise IdentityUnavailable(
            "dirty_state_hash: git status/diff unavailable in " + str(repo_root),
        )
    scoped_porcelain = _scope_porcelain(porcelain)
    untracked = _collect_untracked_content(repo_root, porcelain)
    h = hashlib.sha256()
    h.update(scoped_porcelain)
    h.update(b"\x00")
    h.update(diff)
    for relpath, filehash in untracked:
        h.update(b"\x00")
        h.update(relpath.encode("utf-8"))
        h.update(b"\x00")
        h.update(filehash.encode("ascii"))
    return h.hexdigest()


def _scope_porcelain(porcelain: bytes) -> bytes:
    """Porcelain output filtered to :data:`_DIRT_SCOPE_PREFIXES` (fix F-B).

    A rename line (``XY old -> new``) is kept when EITHER side is in scope.
    Line order is git's own (stable for a given tree state).
    """
    kept: list[str] = []
    for line in porcelain.decode("utf-8", errors="replace").splitlines():
        if len(line) < 4:
            continue
        remainder = line[3:]
        paths = [p.strip() for p in remainder.split(" -> ")]
        in_scope = False
        for p in paths:
            norm = _unquote_porcelain(p).replace("\\", "/")
            if _dirt_path_in_scope(norm):
                in_scope = True
                break
        if in_scope:
            kept.append(line)
    return ("\n".join(kept) + ("\n" if kept else "")).encode("utf-8")


def _dirt_path_in_scope(norm: str) -> bool:
    """True when a porcelain path is inside a scope prefix OR is a collapsed
    untracked parent dir of one (git lists a wholly-untracked tree as one
    ``?? scripts/`` line -- ``scripts/`` must count for ``scripts/jseval/``;
    the per-file rglob expansion then filters to the real prefix)."""
    return any(
        norm.startswith(pref) or pref.startswith(norm)
        for pref in _DIRT_SCOPE_PREFIXES
    )


def _collect_untracked_content(
    repo_root: Path, porcelain: bytes,
) -> list[tuple[str, str]]:
    """Sorted ``(relpath, sha256hex)`` for untracked source files.

    Parses ``??`` porcelain lines; for each whose path is under a source prefix,
    expands directories (``git status`` collapses an untracked dir to one
    ``?? dir/`` entry) and content-hashes every regular file found. Raises
    :class:`IdentityUnavailable` on any file over the 10 MB ceiling.
    """
    text = porcelain.decode("utf-8", errors="replace")
    entries: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in text.splitlines():
        if not line.startswith("??"):
            continue
        # Porcelain untracked line: "?? <path>" (path begins at column 3).
        raw_path = _unquote_porcelain(line[3:])
        norm = raw_path.replace("\\", "/")
        if not _dirt_path_in_scope(norm):
            continue
        base = Path(repo_root) / raw_path
        for f in _iter_regular_files(base):
            rel = _rel_posix(repo_root, f)
            # A collapsed parent dir (e.g. "?? scripts/") expands to files both
            # in and out of scope -- filter per file to the real prefixes.
            if not any(rel.startswith(p) for p in _UNTRACKED_CONTENT_PREFIXES):
                continue
            if rel in seen:
                continue
            try:
                size = f.stat().st_size
            except OSError as e:
                raise IdentityUnavailable(
                    "dirty_state_hash: untracked source file unreadable ("
                    + rel + ": " + type(e).__name__ + ")",
                )
            if size > _MAX_UNTRACKED_BYTES:
                raise IdentityUnavailable(
                    "dirty_state_hash: untracked source file exceeds 10MB ("
                    + rel + ")",
                )
            seen.add(rel)
            entries.append((rel, _sha256_bytes(f)))
    entries.sort(key=lambda t: t[0])
    return entries


def _iter_regular_files(base: Path) -> list[Path]:
    """Regular files at ``base`` (itself if a file, recursively if a dir)."""
    if base.is_file():
        return [base]
    if base.is_dir():
        return sorted(p for p in base.rglob("*") if p.is_file())
    return []


def _unquote_porcelain(raw: str) -> str:
    """Strip git's optional C-quoting from a porcelain path (ASCII paths pass)."""
    raw = raw.strip()
    if len(raw) >= 2 and raw.startswith('"') and raw.endswith('"'):
        inner = raw[1:-1]
        try:
            return inner.encode("utf-8").decode("unicode_escape")
        except (UnicodeDecodeError, ValueError):  # pragma: no cover - defensive
            return inner
    return raw


def _rel_posix(repo_root: Path, f: Path) -> str:
    """POSIX-style path of ``f`` relative to ``repo_root`` (stable across OSes)."""
    try:
        return f.relative_to(repo_root).as_posix()
    except ValueError:  # pragma: no cover - defensive
        return f.as_posix()


# --------------------------------------------------------------------------- #
# Model fingerprints + runtime config.
# --------------------------------------------------------------------------- #


def _resolve_repo_root(spawn_env: Mapping[str, str]) -> Path:
    """Repo root for git ops in the live path.

    ``JUSTSEARCH_REPO_ROOT`` in ``spawn_env`` wins (the env jseval passes to the
    backend); otherwise the module's own resolved checkout root.
    """
    override = spawn_env.get("JUSTSEARCH_REPO_ROOT")
    if override:
        return Path(override)
    from ._paths import REPO_ROOT
    return REPO_ROOT


def _resolve_models_dir(repo_root: Path, spawn_env: Mapping[str, str]) -> Path:
    """Models dir: ``JUSTSEARCH_MODELS_DIR`` if set, else ``<repo_root>/models``."""
    override = spawn_env.get("JUSTSEARCH_MODELS_DIR")
    if override:
        return Path(override)
    return Path(repo_root) / "models"


def _read_sha256_sidecar(path: Path) -> str | None:
    """First whitespace token (the hex digest) of a ``.onnx.sha256`` sidecar."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return None
    if not text:
        return None
    token = text.split()[0].strip().lower()
    return token or None


def _selector_splade_sidecar(models_dir: Path) -> str | None:
    """SPLADE sidecar hash (fp32 then fp16 filename), or ``None`` if absent."""
    base = models_dir / "splade" / "naver-splade-v3"
    for name in ("model.onnx.sha256", "model_fp16.onnx.sha256"):
        h = _read_sha256_sidecar(base / name)
        if h is not None:
            return h
    return None


def _ner_model_hash(models_dir: Path) -> str:
    """NER model content hash from disk (no live surface exists -- 751 sec O.3).

    Prefers the manifest's canonical content (stable across the CPU/GPU .onnx
    pair it describes); falls back to the model.onnx bytes. Raises
    :class:`IdentityUnavailable` if neither is present (fail closed).
    """
    ner_dir = Path(models_dir) / "onnx" / "ner"
    manifest = ner_dir / "model_manifest.json"
    if manifest.is_file():
        try:
            doc = json.loads(manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise IdentityUnavailable(
                "ner_model_hash: model_manifest.json unreadable ("
                + type(e).__name__ + ")",
            )
        return _sha256_canonical(doc)
    onnx = ner_dir / "model.onnx"
    if onnx.is_file():
        try:
            return _sha256_bytes(onnx)
        except OSError as e:  # pragma: no cover - defensive
            raise IdentityUnavailable(
                "ner_model_hash: model.onnx unreadable (" + type(e).__name__ + ")",
            )
    raise IdentityUnavailable(
        "ner_model_hash: no model_manifest.json or model.onnx under "
        + str(ner_dir),
    )


def _runtime_config(spawn_env: Mapping[str, str]) -> dict:
    """Index-shaping runtime knobs from ``spawn_env`` (absent -> "default").

    One source for both the selector and the live identity so the two keys can
    never disagree on the knob axis.
    """
    return {
        name: spawn_env.get(name, "default") for name in _INDEX_SHAPING_ENV_VARS
    }


def _fetch_commit_metadata(base_url: str, timeout: float) -> dict:
    """Read the key-relevant fields from ``/api/debug/commit-metadata``.

    Raises :class:`IdentityUnavailable` on any transport error, non-200, or a
    missing/blank key field (fail closed).
    """
    try:
        with httpx.Client(base_url=base_url, timeout=timeout) as client:
            resp = client.get("/api/debug/commit-metadata")
    except httpx.HTTPError as e:
        raise IdentityUnavailable(
            "commit-metadata: request failed (" + type(e).__name__ + ")",
        )
    if resp.status_code != 200:
        raise IdentityUnavailable(
            "commit-metadata: HTTP " + str(resp.status_code),
        )
    try:
        doc = resp.json()
    except ValueError:
        raise IdentityUnavailable("commit-metadata: non-JSON body")
    if not isinstance(doc, dict):
        raise IdentityUnavailable("commit-metadata: unexpected shape")
    out: dict = {}
    for f in _LIVE_KEY_META_FIELDS:
        v = doc.get(f)
        if v is None or (isinstance(v, str) and not v.strip()):
            raise IdentityUnavailable("commit-metadata." + f + ": missing")
        out[f] = v
    return out


# --------------------------------------------------------------------------- #
# Public API.
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class CorpusAxis:
    """Canonical corpus-axis resolution shared by EVERY index-cache caller (751 P.5).

    ``watched_dir`` is the directory that becomes the backend's watched root (the
    engine's doc-identity path base -- IndexingDocumentOps.java:137); it is what
    the selector's ``corpus_dir_path`` component binds. ``signature_root`` is
    where ``corpus.jsonl``/qrels live -- the input to the canonical
    ``corpus_signature``. Both are ``None`` when the axis is unresolvable, and
    ``reason`` then names why (fail closed).

    The publisher (``jseval run --dataset X`` / ``index-cache warm``) and the
    adopter (``serve-eval-backend.py --corpus-dir ...``) bind identical key
    components ONLY because they run this one resolver on the same input
    (finding 2): the shared function is the by-construction agreement, not a
    convention two call sites must both remember.
    """

    watched_dir: Path | None
    signature_root: Path | None
    reason: str | None = None
    raw_context: object | None = None


def resolve_corpus_axis(
    dataset_name: str | None,
    explicit_dir: Path | None,
    raw_context=None,
    env_overrides: Mapping[str, str] | None = None,
) -> CorpusAxis:
    """Resolve the corpus axis exactly as ``ingest.prepare_corpus`` derives its
    watched root (ingest.py:181-209), so a cached entry's ``corpus_dir_path``
    equals the path the real ingest will watch.

    Resolution rules (751 P.5 WP-1):

    * ``explicit_dir`` with ``corpus.jsonl`` -> watched=dir, signature_root=dir.
    * ``explicit_dir`` whose PARENT has ``corpus.jsonl`` (the exploded
      ``datasets/<cell>/corpus-dir`` subdir, finding 1) -> watched=dir,
      signature_root=parent -- the files are watched here, but corpus.jsonl+qrels
      live one level up.
    * ``explicit_dir`` with neither -> unresolvable (reason names both shapes).
    * no ``explicit_dir``, golden/mixed ``raw_files`` dataset -> watched=
      ``<datasets>/<ds>/corpus-dir``, signature_root=``<datasets>/<ds>``
      (mirrors ``ingest._raw_corpus_dir`` / ingest.py:205).
    * no ``explicit_dir``, golden/mixed materialized -> watched=
      ``default_corpus_dir(ds)`` (the tmp/eval-corpora target, which does NOT
      exist until ingest materializes it -- fine, the selector needs only its
      normalized PATH, ingest.py:207), signature_root=``<datasets>/<ds>`` (which
      MUST already hold corpus.jsonl, else nothing to sign -> unresolvable).
    * anything else (BEIR / unknown / ``None`` name) -> unresolvable.
    """
    if raw_context is not None:
        from .raw_corpus_manifest import validate_raw_corpus_context

        validate_raw_corpus_context(
            raw_context,
            env_overrides,
            expected_dataset=dataset_name,
            explicit_dir=explicit_dir,
        )
        return CorpusAxis(raw_context.root, None, None, raw_context)

    if explicit_dir is not None:
        explicit_dir = Path(explicit_dir)
        if (explicit_dir / "corpus.jsonl").is_file():
            return CorpusAxis(explicit_dir, explicit_dir, None)
        parent = explicit_dir.parent
        if (parent / "corpus.jsonl").is_file():
            return CorpusAxis(explicit_dir, parent, None)
        return CorpusAxis(
            None, None,
            reason=(
                f"corpus axis unresolvable: {explicit_dir} has no corpus.jsonl and "
                f"neither does its parent {parent}; pass either a dataset dir "
                f"containing corpus.jsonl, or a child directory of one"
            ),
        )

    if not dataset_name:
        return CorpusAxis(
            None, None,
            reason="corpus axis unresolvable: no dataset name and no --corpus-dir given",
        )
    if not (dataset_name.startswith("golden/") or dataset_name.startswith("mixed/")):
        return CorpusAxis(
            None, None,
            reason=(
                f"corpus axis unresolvable: dataset {dataset_name!r} is not a local "
                f"golden/mixed dataset (BEIR/unknown corpora materialize only mid-run, "
                f"so their path is not known before the backend starts); pass --corpus-dir"
            ),
        )

    from ._paths import default_corpus_dir
    from .corpora import _default_base_dir
    from .ingest import _raw_corpus_dir

    dataset_root = _default_base_dir() / dataset_name
    raw_dir = _raw_corpus_dir(dataset_name)
    if raw_dir is not None:
        # raw_files dataset (ingest.py:192-205): the real binary files ARE the
        # corpus (there is no corpus.jsonl); the watched root is the corpus-dir,
        # the signature comes off the dataset root (qrels-based signature).
        return CorpusAxis(raw_dir, dataset_root, None)

    if not (dataset_root / "corpus.jsonl").is_file():
        return CorpusAxis(
            None, None,
            reason=(
                f"corpus axis unresolvable: dataset {dataset_name!r} has no "
                f"corpus.jsonl at {dataset_root}"
            ),
        )
    return CorpusAxis(default_corpus_dir(dataset_name), dataset_root, None)


def compute_selector(
    repo_root: Path,
    corpus_dir: Path | None,
    spawn_env: Mapping[str, str],
    dataset_name: str | None = None,
    raw_context=None,
) -> SelectorKey:
    """Static candidate selector (751 sec M.2 -- heuristic, never authoritative).

    Computable with no backend: git identity + working-tree dirt, on-disk model
    sidecar hashes (embed + SPLADE), the index-shaping runtime knobs, and (when a
    corpus axis is given) the canonical ``corpus_signature`` + normalized watched
    path. Any unavailable input returns a ``SelectorKey`` with ``key=None`` and a
    named reason (fail closed). NER is intentionally absent here (the selector has
    no backend and the design's selector set is embed/splade sidecars only) -- the
    selector and live component sets differ by construction and are never compared
    to each other.

    Corpus axis (751 P.5): ``corpus_dir`` and ``dataset_name`` are the AXIS input,
    resolved through the ONE shared :func:`resolve_corpus_axis` so the publisher and
    the adopter bind identical corpus components by construction (finding 2). When
    BOTH are ``None`` the caller is axis-less (v0 selector with no corpus) and no
    corpus components are added -- today's behavior for axis-less callers.
    """
    components: dict = {"schema_version": _SELECTOR_SCHEMA_VERSION}
    try:
        git_sha = _git_sha_full(repo_root)
        if git_sha is None:
            return SelectorKey(
                None, components,
                "git_sha: git rev-parse HEAD unavailable in " + str(repo_root),
            )
        components["git_sha"] = git_sha
        components["dirty_state_hash"] = _dirty_state_hash(repo_root)

        models_dir = _resolve_models_dir(repo_root, spawn_env)
        embed = _read_sha256_sidecar(
            models_dir / "onnx" / "gte-multilingual-base" / "model_fp16.onnx.sha256",
        )
        if embed is None:
            return SelectorKey(
                None, components,
                "embed_model_sha256: sidecar missing under " + str(models_dir),
            )
        components["embed_model_sha256"] = embed
        splade = _selector_splade_sidecar(models_dir)
        if splade is None:
            return SelectorKey(
                None, components,
                "splade_model_sha256: sidecar missing under " + str(models_dir),
            )
        components["splade_model_sha256"] = splade

        components["runtime_config"] = _runtime_config(spawn_env)

        if dataset_name is not None or corpus_dir is not None:
            axis = resolve_corpus_axis(
                dataset_name, corpus_dir, raw_context, spawn_env,
            )
            if axis.reason is not None:
                return SelectorKey(None, components, axis.reason)
            if axis.raw_context is not None:
                raw_identity = axis.raw_context.identity
                components["corpus_signature"] = raw_identity.digest
                components["corpus_kind"] = "raw-files"
                components["corpus_manifest_schema"] = raw_identity.manifest["schema"]
                components["corpus_file_count"] = raw_identity.file_count
                components["corpus_admission_policy"] = dict(
                    axis.raw_context.admission_policy
                )
            else:
                sig = corpus_signature(axis.signature_root)
                if sig is None:
                    return SelectorKey(
                        None, components,
                        "corpus_signature: no corpus files under "
                        + str(axis.signature_root),
                    )
                components["corpus_signature"] = sig
            # Review fix F-A (refute-first audit claim 10): the engine's doc
            # identity is the absolute file path (IndexingDocumentOps.java:137),
            # so a byte-identical corpus at a DIFFERENT absolute path (another
            # worktree) builds a different-doc-universe index. Binding the
            # normalized WATCHED path into the key makes cross-checkout entries
            # live under different keys -- a foreign-path entry can never even be
            # nominated (and same-key last-writer republish races can only
            # involve same-path, genuinely-equivalent entries). The watched dir
            # (not the signature root) is what the engine actually indexes.
            components["corpus_dir_path"] = _norm_path(axis.watched_dir)
    except IdentityUnavailable as e:
        return SelectorKey(None, components, e.reason)

    return SelectorKey(_sha256_canonical(components), components, None)


def compute_live_identity(
    base_url: str,
    spawn_env: Mapping[str, str],
    timeout: float = 10.0,
) -> IndexIdentity:
    """Authoritative index identity from the running backend (751 sec M.1).

    The config-derived fingerprints (embed/SPLADE model content,
    ``index_fingerprint``, ``field_catalog_hash``, ``synonyms_hash``,
    ``vector_format``) come from the backend's own
    ``/api/debug/commit-metadata`` -- jseval never re-derives a Java fingerprint
    in Python. git identity + working-tree dirt come from
    ``JUSTSEARCH_REPO_ROOT`` (or the resolved checkout); the NER hash comes from
    disk (no live surface). Raises :class:`IdentityUnavailable` on any
    unavailable input (fail closed).

    build_state / commit_time are deliberately excluded from the key: they vary
    per build and belong to the entry attestation the publish step assembles,
    not to the identity.
    """
    repo_root = _resolve_repo_root(spawn_env)
    git_sha = _git_sha_full(repo_root)
    if git_sha is None:
        raise IdentityUnavailable(
            "git_sha: git rev-parse HEAD unavailable in " + str(repo_root),
        )
    dirty = _dirty_state_hash(repo_root)

    models_dir = _resolve_models_dir(repo_root, spawn_env)
    ner = _ner_model_hash(models_dir)

    meta = _fetch_commit_metadata(base_url, timeout)

    components: dict = {
        "schema_version": _SCHEMA_VERSION,
        "git_sha": git_sha,
        "dirty_state_hash": dirty,
        "embedding_model_sha256": meta["embedding_model_sha256"],
        "splade_model_sha256": meta["splade_model_sha256"],
        "ner_model_hash": ner,
        "field_catalog_hash": meta["field_catalog_hash"],
        "index_fingerprint": meta["index_fingerprint"],
        "synonyms_hash": meta["synonyms_hash"],
        "vector_format": meta["vector_format"],
        "runtime_config": _runtime_config(spawn_env),
    }
    return IndexIdentity(_sha256_canonical(components), components)


def confirm_adoption(
    base_url: str,
    entry_doc: dict,
    adopted_data_dir: Path,
    spawn_env: Mapping[str, str],
    timeout: float = 30.0,
) -> ConfirmResult:
    """Confirm an adopted cache entry against the live backend (751 sec M.2).

    Five independent checks -- live-key equality, process binding, generation
    stability, attestation counts, and a behavioral canary. Every failure is
    collected (no short-circuit) and any transport error, timeout, or missing
    key inside a check fails THAT check with a named reason; this function never
    raises out.
    """
    failures: list[str] = []
    checks: dict = {}

    def fail(msg: str) -> None:
        failures.append(msg)

    # -- Check 1: live identity key equals the entry's recorded key. --------- #
    try:
        live = compute_live_identity(base_url, spawn_env, timeout=timeout)
        entry_ident = entry_doc.get("identity") or {}
        entry_key = entry_ident.get("key")
        entry_comps = entry_ident.get("components") or {}
        if entry_key is None:
            fail("identity.key: entry_doc has no identity.key")
            checks["identity"] = {"ok": False, "reason": "no entry key"}
        elif live.key == entry_key:
            checks["identity"] = {"ok": True, "key": live.key}
        else:
            diffs = []
            for k in sorted(set(entry_comps) | set(live.components)):
                a = entry_comps.get(k, "<absent>")
                b = live.components.get(k, "<absent>")
                if a != b:
                    msg = "identity." + k + ": entry=" + _short(a) + " live=" + _short(b)
                    diffs.append(msg)
                    fail(msg)
            if not diffs:
                # Keys differ with equal components -- schema/version skew.
                msg = "identity.key: entry=" + _short(entry_key) + " live=" + _short(live.key)
                fail(msg)
                diffs.append(msg)
            checks["identity"] = {"ok": False, "diffs": diffs}
    except IdentityUnavailable as e:
        fail("identity.unavailable: " + e.reason)
        checks["identity"] = {"ok": False, "reason": e.reason}
    except Exception as e:  # pragma: no cover - defensive last resort
        fail("identity.error: " + type(e).__name__)
        checks["identity"] = {"ok": False, "reason": str(e)}

    # -- Checks 2-5 share one client; each guards its own transport. --------- #
    try:
        with httpx.Client(base_url=base_url, timeout=timeout) as client:
            _confirm_binding(client, adopted_data_dir, fail, checks)
            status, status_err = _get_json(client, "/api/status")
            _confirm_generation(status, status_err, entry_doc, fail, checks)
            _confirm_counts(status, status_err, entry_doc, fail, checks)
            _confirm_canary(client, entry_doc, fail, checks)
    except Exception as e:  # pragma: no cover - defensive last resort
        fail("confirm.error: " + type(e).__name__)

    # -- Check 6: watched roots match the entry's recorded roots (fix F-A). --- #
    _confirm_watched_roots(adopted_data_dir, entry_doc, fail, checks)

    return ConfirmResult(ok=not failures, failures=failures, checks=checks)


def _confirm_watched_roots(adopted_data_dir, entry_doc, fail, checks) -> None:
    """Check 6 (review fix F-A): the adopted dir's watched roots equal the
    entry's recorded roots.

    Defensive depth behind the corpus_dir_path selector component: doc identity
    in the engine is the absolute path, so an adopted index whose
    ``watched_roots.json`` names a path this run does not expect would, after
    the run's own ingest adds its root, serve a polluted doc universe. Local
    file read, no HTTP. Both-absent passes (a scrubbed/rootless entry adopts
    into a rootless dir); any asymmetry or path mismatch fails with the paths
    named.
    """
    def _roots(source) -> list[str] | None:
        try:
            if isinstance(source, dict):
                raw = source
            else:
                p = Path(source) / "watched_roots.json"
                if not p.is_file():
                    return []
                raw = json.loads(p.read_text(encoding="utf-8"))
            return sorted(
                _norm_path(r["path"]) for r in raw.get("roots", []) if r.get("path")
            )
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as e:
            fail("watched_roots.unreadable: " + type(e).__name__)
            return None

    expected = entry_doc.get("attestation", {}).get("watched_roots")
    if expected is None:
        # Entry predates the field (pre-F-A entries): treat recorded-roots as
        # unknown and compare against nothing -- but an adopted dir with roots
        # and no record is exactly the foreign-path blind spot, so fail closed.
        adopted = _roots(adopted_data_dir)
        if adopted is None:
            checks["watched_roots"] = {"ok": False}
            return
        if adopted:
            fail(
                "watched_roots.unrecorded: adopted dir has roots "
                + ",".join(adopted) + " but entry records none",
            )
            checks["watched_roots"] = {"ok": False, "adopted": adopted}
        else:
            checks["watched_roots"] = {"ok": True, "roots": []}
        return

    recorded = sorted(_norm_path(p) for p in expected)
    adopted = _roots(adopted_data_dir)
    if adopted is None:
        checks["watched_roots"] = {"ok": False}
        return
    if adopted == recorded:
        checks["watched_roots"] = {"ok": True, "roots": adopted}
    else:
        fail(
            "watched_roots.mismatch: adopted=" + (",".join(adopted) or "<none>")
            + " recorded=" + (",".join(recorded) or "<none>"),
        )
        checks["watched_roots"] = {"ok": False, "adopted": adopted, "recorded": recorded}


def _confirm_binding(client, adopted_data_dir, fail, checks) -> None:
    """Check 2: the process is bound to the adopted data dir (751 sec O.8.1)."""
    doc, err = _get_json(client, "/api/debug/state")
    if err is not None:
        fail("binding.debug_state: " + err)
        checks["binding"] = {"ok": False, "reason": err}
        return
    bound = _deep_find(doc, "justsearch.data.dir")
    if bound is _MISSING or not isinstance(bound, str):
        fail("binding.data_dir: justsearch.data.dir not found in /api/debug/state")
        checks["binding"] = {"ok": False, "reason": "config value not found"}
        return
    if _norm_path(bound) == _norm_path(adopted_data_dir):
        checks["binding"] = {"ok": True, "data_dir": bound}
    else:
        fail("binding.data_dir: bound=" + str(bound) + " adopted=" + str(adopted_data_dir))
        checks["binding"] = {"ok": False, "bound": bound, "adopted": str(adopted_data_dir)}


def _confirm_generation(status, status_err, entry_doc, fail, checks) -> None:
    """Check 3: adopted generation serving, no migration in flight (751 sec M.2)."""
    if status_err is not None:
        fail("generation.status: " + status_err)
        checks["generation"] = {"ok": False, "reason": status_err}
        return
    detail: dict = {}
    ok = True
    entry_gen = ((entry_doc.get("attestation") or {}).get("generation_id"))
    active = _deep_find(status, "activeGenerationId")
    if active is _MISSING:
        fail("generation.active: activeGenerationId not found in /api/status")
        ok = False
    elif entry_gen is None:
        fail("generation.entry: attestation.generation_id missing")
        ok = False
    elif active != entry_gen:
        fail("generation.active: entry=" + str(entry_gen) + " live=" + str(active))
        ok = False
    detail["activeGenerationId"] = None if active is _MISSING else active

    building = _deep_find(status, "buildingGenerationId")
    if building is not _MISSING and building not in (None, ""):
        fail("generation.building: buildingGenerationId nonempty (" + str(building) + ")")
        ok = False
    detail["buildingGenerationId"] = None if building is _MISSING else building

    mstate = _find_migration_state(status)
    if mstate is not _MISSING and mstate not in (None, "") and str(mstate).upper() != "IDLE":
        fail("generation.migration: migration state not IDLE (" + str(mstate) + ")")
        ok = False
    detail["migrationState"] = None if mstate is _MISSING else mstate

    checks["generation"] = {"ok": ok, **detail}


def _confirm_counts(status, status_err, entry_doc, fail, checks) -> None:
    """Check 4: attestation counts match; chunk-vector coverage >= 99.9%."""
    if status_err is not None:
        fail("counts.status: " + status_err)
        checks["counts"] = {"ok": False, "reason": status_err}
        return
    entry_counts = ((entry_doc.get("attestation") or {}).get("counts")) or {}
    ok = True
    detail: dict = {}
    for f in ("embeddingDocCount", "spladeDocCount", "chunkDocCount"):
        live_v = _deep_find(status, f)
        exp = entry_counts.get(f)
        detail[f] = None if live_v is _MISSING else live_v
        if live_v is _MISSING:
            fail("counts." + f + ": not found in /api/status")
            ok = False
        elif exp is None:
            fail("counts." + f + ": entry attestation missing " + f)
            ok = False
        elif live_v != exp:
            fail("counts." + f + ": entry=" + str(exp) + " live=" + str(live_v))
            ok = False
    # Coverage is a float that can drift below 100 legitimately -- require the
    # live index to clear 99.9% rather than exact equality (751 spec).
    cov = _deep_find(status, "chunkVectorCoveragePercent")
    detail["chunkVectorCoveragePercent"] = None if cov is _MISSING else cov
    if cov is _MISSING:
        fail("counts.chunkVectorCoveragePercent: not found in /api/status")
        ok = False
    else:
        try:
            covf = float(cov)
        except (TypeError, ValueError):
            fail("counts.chunkVectorCoveragePercent: non-numeric (" + str(cov) + ")")
            ok = False
        else:
            if covf < 99.9:
                fail("counts.chunkVectorCoveragePercent: live=" + str(covf) + " < 99.9")
                ok = False
    checks["counts"] = {"ok": ok, **detail}


def _confirm_canary(client, entry_doc, fail, checks) -> None:
    """Check 5: behavioral canary -- required stages executed, no degradation.

    Parses the search response's ``searchTrace`` directly (not via
    provenance.extract_query_evidence) so the defensive fail-closed parsing this
    step needs is explicit and self-contained; synthetic fixtures are not
    trusted as shape truth (751 spec).
    """
    canary = (entry_doc.get("attestation") or {}).get("canary") or {}
    query = canary.get("query")
    required = canary.get("required_stages") or []
    if not query:
        fail("canary.query: attestation.canary.query missing")
        checks["canary"] = {"ok": False, "reason": "no query"}
        return
    payload = {"query": query, "mode": "hybrid", "limit": 5, "debug": True}
    # Warm-up tolerance (live re-validation finding, 2026-07-17): a freshly
    # booted backend can 504/502 the first search while the Worker warms its
    # search path (reranker warm-up + model init take ~10-20s) -- observed
    # live as a one-shot canary HTTP 504 at boot+11s that voided an otherwise
    # valid adoption. Transient statuses and transport errors retry with
    # backoff until the deadline; any 200 (or non-transient status) settles
    # the check immediately. The retry only defers the verdict -- it never
    # weakens it (a persistent failure still fails, with the last error named).
    deadline = time.monotonic() + _CANARY_WARMUP_SEC
    last_err = None
    resp = None
    while True:
        try:
            resp = client.post("/api/knowledge/search", json=payload)
        except httpx.HTTPError as e:
            last_err = "request failed (" + type(e).__name__ + ")"
            resp = None
        else:
            if resp.status_code == 200:
                break
            last_err = "HTTP " + str(resp.status_code)
            if resp.status_code not in (502, 503, 504):
                break
        if time.monotonic() >= deadline:
            break
        time.sleep(2.0)
    if resp is None or resp.status_code != 200:
        fail("canary.http: " + str(last_err))
        checks["canary"] = {"ok": False, "reason": last_err}
        return
    try:
        body = resp.json()
    except ValueError:
        fail("canary.body: non-JSON search response")
        checks["canary"] = {"ok": False, "reason": "non-JSON"}
        return

    ok = True
    trace = body.get("searchTrace") or {}
    stages = {s.get("id"): s.get("status") for s in (trace.get("stages") or [])}
    for sid in required:
        st = stages.get(sid)
        if st != "executed":
            fail("canary.stage." + str(sid) + ": status=" + str(st))
            ok = False
    degr = trace.get("degradation") or {}
    truthy = sorted(k for k, v in degr.items() if v)
    if truthy:
        fail("canary.degradation: " + ",".join(truthy))
        ok = False
    total = body.get("totalHits")
    if not isinstance(total, int) or total <= 0:
        fail("canary.totalHits: " + str(total))
        ok = False
    checks["canary"] = {"ok": ok, "stages": stages, "totalHits": total}


# --------------------------------------------------------------------------- #
# Small helpers for confirm-side parsing.
# --------------------------------------------------------------------------- #


def _get_json(client, path: str):
    """GET ``path`` -> ``(doc, None)`` on 200 JSON, else ``(None, reason)``."""
    try:
        resp = client.get(path)
    except httpx.HTTPError as e:
        return None, "request failed (" + type(e).__name__ + ")"
    if resp.status_code != 200:
        return None, "HTTP " + str(resp.status_code)
    try:
        return resp.json(), None
    except ValueError:
        return None, "non-JSON body"


def _deep_find(obj: Any, key: str):
    """First value for ``key`` anywhere in a nested dict/list, else ``_MISSING``."""
    if isinstance(obj, dict):
        if key in obj:
            return obj[key]
        for v in obj.values():
            r = _deep_find(v, key)
            if r is not _MISSING:
                return r
    elif isinstance(obj, list):
        for v in obj:
            r = _deep_find(v, key)
            if r is not _MISSING:
                return r
    return _MISSING


def _find_migration_state(status: Any):
    """Locate the Blue/Green migration state under a few known key spellings."""
    for k in ("migrationState", "migration_state"):
        v = _deep_find(status, k)
        if v is not _MISSING:
            return v
    mig = _deep_find(status, "migration")
    if isinstance(mig, dict):
        for k in ("state", "migrationState", "status"):
            if k in mig:
                return mig[k]
    return _MISSING


def _norm_path(p) -> str:
    """Case-normalized, resolved path string (Windows-aware comparison)."""
    try:
        return os.path.normcase(str(Path(p).resolve()))
    except (OSError, ValueError):  # pragma: no cover - defensive
        return os.path.normcase(os.path.abspath(str(p)))


def _short(v: Any, limit: int = 80) -> str:
    """Compact stringification for miss-reason messages."""
    s = v if isinstance(v, str) else json.dumps(v, sort_keys=True, default=str)
    if len(s) > limit:
        return s[: limit - 3] + "..."
    return s
