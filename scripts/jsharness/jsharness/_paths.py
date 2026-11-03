"""Canonical path constants for jsharness.

All paths that jsharness creates or reads are anchored to the repository root,
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

    observations.md fix: when jsharness is pip-installed but invoked from a
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
    """The checkout root the *imported* jsharness package lives in.

    This is fixed at import-resolution time (editable install / PYTHONPATH),
    independent of CWD — the other half of the tempdoc-716 cross-checkout
    check in ``jsharness.cli``.
    """
    return Path(__file__).resolve().parents[3]


def cwd_checkout_root() -> Path | None:
    """The JustSearch checkout root containing CWD, or None.

    Walks up from CWD looking for a ``.git`` entry (file = worktree,
    dir = repo). Sanity-check: an unrelated git repo elsewhere on disk
    shouldn't claim ownership — require a ``scripts/jsharness`` peer directory
    so we only match when CWD looks like a JustSearch checkout.
    """
    cwd = Path.cwd().resolve()
    for ancestor in (cwd, *cwd.parents):
        if (ancestor / ".git").exists():
            if (ancestor / "scripts" / "jsharness").is_dir():
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

    Mirrors ``scripts/dev/dev-runner.cjs`` ``resolveMainRepoRoot`` (tempdoc 618 §2 / 644
    Axis 1). Falls back to ``REPO_ROOT`` when not in a worktree or on any parse failure.
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
    return REPO_ROOT

