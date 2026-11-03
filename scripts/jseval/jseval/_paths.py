"""Canonical path constants for jseval.

All paths that jseval creates or reads are anchored to the repository root,
so invocation CWD never affects behavior.
"""

from __future__ import annotations

import os
import re
from pathlib import Path


def _resolve_repo_root() -> Path:
    """
    Resolve the repository root.

    Default: derive from this module's location (`parents[3]` ≡ repo root).

    observations.md fix: when jseval is pip-installed but invoked from a
    git worktree (CWD is a different repo root), the module location points
    at the *primary* checkout, not the active worktree. The launcher would
    then run gradle/build artifacts against the wrong tree. Detect a
    worktree-shaped CWD and prefer it. A directory is treated as a repo
    root when it contains a `.git` entry (file or dir — git worktrees use
    a `.git` *file* that points at the main repo's `.git/worktrees/<name>`).

    Override with `JUSTSEARCH_REPO_ROOT=<absolute-path>` to short-circuit
    detection (CI, hermetic test runs).
    """
    override = os.environ.get("JUSTSEARCH_REPO_ROOT")
    if override:
        candidate = Path(override).resolve()
        if candidate.is_dir():
            return candidate

    cwd_root = cwd_checkout_root()
    if cwd_root is not None:
        return cwd_root

    return module_checkout_root()


def module_checkout_root() -> Path:
    """The checkout root the *imported* jseval package lives in.

    This is fixed at import-resolution time (editable install / PYTHONPATH),
    independent of CWD — the other half of the tempdoc-716 cross-checkout
    check in ``jseval.cli``.
    """
    return Path(__file__).resolve().parents[3]


def cwd_checkout_root() -> Path | None:
    """The JustSearch checkout root containing CWD, or None.

    Walks up from CWD looking for a ``.git`` entry (file = worktree,
    dir = repo). Sanity-check: an unrelated git repo elsewhere on disk
    shouldn't claim ownership — require a ``scripts/jseval`` peer directory
    so we only match when CWD looks like a JustSearch checkout.
    """
    cwd = Path.cwd().resolve()
    for ancestor in (cwd, *cwd.parents):
        if (ancestor / ".git").exists():
            if (ancestor / "scripts" / "jseval").is_dir():
                return ancestor
            break  # found a .git but not JustSearch
    return None


REPO_ROOT: Path = _resolve_repo_root()


def main_repo_root() -> Path:
    """Resolve the MAIN checkout root, even when ``REPO_ROOT`` is a linked git worktree.

    A linked worktree's ``.git`` is a *file* containing
    ``gitdir: <main>/.git/worktrees/<name>``. Walk that back to the main checkout so that
    large shared assets (models, native runtime) resolve against the binary-bearing main
    tree rather than the worktree's pointer-only copy.

    Shared clones resolve their source checkout through Git's object alternates.
    Otherwise falls back to ``REPO_ROOT`` on any parse failure. The linked-worktree
    resolution mirrors ``scripts/dev/dev-runner.cjs`` ``resolveMainRepoRoot``.
    """
    git_path = REPO_ROOT / ".git"
    try:
        if git_path.is_file():
            content = git_path.read_text(encoding="utf-8").strip()
            match = re.match(r"^gitdir:\s*(.+)$", content)
            if match:
                # gitdir → <main>/.git/worktrees/<name>; up 3 levels → <main>.
                git_dir = (REPO_ROOT / match.group(1)).resolve()
                return git_dir.parents[2]
    except (OSError, IndexError):
        pass
    # A shared standalone clone has a .git directory, with its source object
    # store recorded in alternates. It shares the same large assets as a linked
    # worktree; its own models/ usually contains only tracked LFS pointers.
    objects = git_path / "objects"
    try:
        for alternate in (objects / "info/alternates").read_text().splitlines():
            store = (objects / alternate).resolve()
            if store.name == "objects" and store.parent.name == ".git":
                source = store.parent.parent
                if (source / "scripts/jseval").is_dir():
                    return source
    except OSError:
        pass
    return REPO_ROOT


def shared_models_dir() -> Path | None:
    """The ``models/`` directory a stack-bound run should use.

    Prefers the MAIN checkout's ``models/`` (which holds the LFS binaries) over a
    worktree's pointer-only copy, so worktree eval discovers the reranker/dense/SPLADE
    models instead of silently disabling them (tempdoc 644 Axis 1; mirrors
    ``dev-runner.cjs:428-434``). Returns the first existing candidate, or ``None`` if
    neither the main nor the local ``models/`` exists.
    """
    main_models = main_repo_root() / "models"
    if main_models.is_dir():
        return main_models
    local_models = REPO_ROOT / "models"
    if local_models.is_dir():
        return local_models
    return None


# Default output directories, all under scripts/jseval/tmp/
_JSEVAL_TMP: Path = REPO_ROOT / "scripts" / "jseval" / "tmp"

# Tempdoc 716: the canonical jseval-owned data root. Every durable artifact
# jseval itself writes (eval-results/ runs, the manifest index, the history DB)
# lives under this one root, so a defaults-only `jseval run` composes with a
# defaults-only gate reader. Distinct from DEFAULT_BACKEND_DATA_DIR below, which the Worker
# process owns and mutates (index/, watched_roots.json, logs/, telemetry/)
# and which `--clean` may wipe wholesale.
DEFAULT_JSEVAL_DATA_DIR: Path = _JSEVAL_TMP

# The eval-mode backend's JUSTSEARCH_DATA_DIR default (mirrors
# backend.start_backend's `<repo>/tmp/headless-eval-data` fallback). Named so
# every consumer that needs the Worker-owned data dir shares one definition.
DEFAULT_BACKEND_DATA_DIR: Path = REPO_ROOT / "tmp" / "headless-eval-data"

DEFAULT_EVAL_RESULTS: Path = DEFAULT_JSEVAL_DATA_DIR / "eval-results"
DEFAULT_BENCH_CLAIM_A: Path = _JSEVAL_TMP / "bench" / "claim-a"
DEFAULT_BENCH_TRACK_G: Path = _JSEVAL_TMP / "bench" / "track-g"


def default_corpus_dir(dataset_name: str) -> Path:
    """Default materialization directory for a dataset."""
    return _JSEVAL_TMP / "eval-corpora" / dataset_name
