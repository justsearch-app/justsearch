"""Baseline-shift-detection convention, ported into jseval's Python ratchets (tempdoc 664).

Ports the *shape* (not the literal mechanism) of the discipline-gate kernel's
changeset-justification protocol (``scripts/governance/lib/git-utils.mjs`` +
``changeset-loader.mjs``, tempdoc 530) into jseval's own ratchet re-pin commands
(``perf-gate --update-baseline``, ``leak-gate-derive``, ``jseval release`` recompose).
Deliberately does NOT pull jseval into the JS kernel itself — jseval's ratchets are
advisory/live-stack-dependent, the JS kernel's gates are fast-CI-blocking; the RUNTIME
boundary between them is intentional (tempdoc 664 §Design). Only the CONVENTION — "a
floor can't be silently relaxed without a classified, justified changeset" — is reused,
reimplemented natively in Python so jseval doesn't need to invoke Node.

Reuses this codebase's own existing idioms rather than inventing new ones:

- git subprocess calls mirror ``manifest.py:_git_sha_full``'s
  ``subprocess.run(["git", ...])`` shape.
- YAML frontmatter parsing reuses the PyYAML dependency already required by
  ``run_config.py`` — no new dependency.

The concrete finding this closes (tempdoc 664 confidence-building pass):
``perf-ratchet-baselines.v1.json`` itself carries a comment admitting a relevance floor was
"re-baselined in the same recompose to the user-accepted post-636 levels (enron/courtlistener
drops)" — an unguarded relaxation, laundered through a re-pin command with no safeguard.

**Honest limit (tempdoc 664 post-review correction):** ``git_diff_added_modified`` implements
the JS kernel's PR-scope anti-replay semantics (a changeset merged long before the relaxation
being checked doesn't count), but none of the three real call sites currently pass a
``baseline_ref`` — they all use ``load_changesets``'s local/fixture mode (every ``.md`` file
present is loaded, regardless of when it was added). This is a deliberate simplification given
jseval's ratchets are locally-run and not scoped to a CI PR the way the JS kernel's gates are —
but it means a single changeset currently justifies EVERY future relaxation of its declared
``(gate, dataset)``, not just the one it was originally written for. A human must still author a
``tempdoc:``-justified changeset before any relaxation is accepted at all; there is currently no
mechanism preventing that same changeset from silently covering a later, unrelated regression.
"""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any


class BaselineRelaxedWithoutJustificationError(ValueError):
    """Raised when a ratchet floor would be relaxed with no matching, justified changeset."""


def git_show(ref: str, path: str, cwd: str | Path) -> str | None:
    """Read a file's content at a given git ref, or ``None`` if it didn't exist there."""
    try:
        result = subprocess.run(
            ["git", "show", f"{ref}:{path}"],
            cwd=str(cwd), capture_output=True, text=True, timeout=10, check=False,
        )
        if result.returncode == 0:
            return result.stdout
    except Exception:
        pass
    return None


def git_diff_added_modified(baseline_ref: str, path_filter: str, cwd: str | Path) -> list[str]:
    """Repo-relative paths added or modified between ``baseline_ref`` and ``HEAD`` under
    ``path_filter`` — PR-scope semantics (mirrors ``git-utils.mjs:diffAddedModifiedFiles``): when
    a caller passes a ``baseline_ref`` through to :func:`load_changesets`, a changeset merged
    long ago from an unrelated change doesn't silently justify a NEW relaxation. Built and
    available, but currently unused by any of jseval's real call sites (module docstring's
    "Honest limit") — they all run in local/fixture mode instead.
    """
    try:
        result = subprocess.run(
            ["git", "diff", "--diff-filter=AM", "--name-only",
             f"{baseline_ref}...HEAD", "--", path_filter],
            cwd=str(cwd), capture_output=True, text=True, timeout=10, check=False,
        )
        if result.returncode == 0:
            return [line.strip() for line in result.stdout.splitlines() if line.strip()]
    except Exception:
        pass
    return []


def _find_repo_root(start: Path) -> Path:
    cur = start.resolve()
    for parent in (cur, *cur.parents):
        if (parent / ".git").exists():
            return parent
    return start


def _parse_frontmatter(text: str) -> dict[str, Any] | None:
    """Parse ``---\\n...yaml...\\n---\\nbody`` frontmatter; ``None`` if absent/malformed."""
    if not text.startswith("---"):
        return None
    parts = text.split("---", 2)
    if len(parts) < 3:
        return None
    import yaml
    fm = yaml.safe_load(parts[1])
    return fm if isinstance(fm, dict) else None


def load_changesets(changesets_dir: Path | str, *, baseline_ref: str | None = None) -> list[dict]:
    """Load classified ``.md`` changesets under ``changesets_dir``.

    Without a ``baseline_ref``, every ``.md`` file present is loaded (local authoring / fixture
    use — matches the JS kernel's ``fixtureMode``). With a ``baseline_ref``, only files
    added/modified vs. that ref count (PR-scope discovery, mirroring
    ``changeset-loader.mjs:loadChangesets``).
    """
    changesets_dir = Path(changesets_dir)
    if not changesets_dir.is_dir():
        return []

    if baseline_ref is None:
        candidate_paths = sorted(
            p for p in changesets_dir.glob("*.md") if p.name != "README.md"
        )
    else:
        repo_root = _find_repo_root(changesets_dir)
        rel = changesets_dir.relative_to(repo_root).as_posix()
        changed = git_diff_added_modified(baseline_ref, rel, repo_root)
        candidate_paths = [
            repo_root / c for c in changed
            if c.endswith(".md") and not c.endswith("README.md")
        ]

    declarations: list[dict] = []
    for path in candidate_paths:
        if not path.is_file():
            continue
        fm = _parse_frontmatter(path.read_text(encoding="utf-8"))
        if not fm or not fm.get("classification"):
            continue
        entry = dict(fm)
        entry["_file"] = str(path)
        declarations.append(entry)
    return declarations


def _has_justified_changeset(gate: str, dataset: str, changesets_dir: Path | str,
                             baseline_ref: str | None) -> bool:
    for cs in load_changesets(changesets_dir, baseline_ref=baseline_ref):
        if cs.get("classification") != "baseline-relaxation":
            continue
        if cs.get("gate") not in (gate, "*"):
            continue
        if cs.get("dataset") not in (dataset, "*"):
            continue
        # `tempdoc:` is commonly written as a bare number in frontmatter (e.g. `tempdoc: 636`),
        # which YAML type-infers as an int, not a str — accept any non-empty scalar, not just str.
        tempdoc = cs.get("tempdoc")
        if tempdoc is not None and str(tempdoc).strip():
            return True
    return False


def assert_baseline_not_relaxed(
    old_value: float | None,
    new_value: float,
    *,
    lower_is_better: bool,
    gate: str,
    dataset: str,
    changesets_dir: Path | str,
    baseline_ref: str | None = None,
) -> None:
    """Raise :class:`BaselineRelaxedWithoutJustificationError` if ``new_value`` relaxes
    ``old_value`` and no matching, justified changeset covers it.

    A matching changeset must declare ``classification: baseline-relaxation``, a ``gate`` field
    equal to ``gate`` or ``"*"``, a ``dataset`` field equal to ``dataset`` or ``"*"``, and a
    non-empty ``tempdoc:`` justification field (mirroring the JS kernel's
    ``requireJustificationFor`` check). An improvement (or the first-ever pin, ``old_value is
    None``) never needs one.
    """
    if old_value is None:
        return
    relaxed = (new_value > old_value) if lower_is_better else (new_value < old_value)
    if not relaxed:
        return

    if _has_justified_changeset(gate, dataset, changesets_dir, baseline_ref):
        return

    raise BaselineRelaxedWithoutJustificationError(
        f"{gate}/{dataset}: baseline would relax ({old_value!r} -> {new_value!r}) with no "
        f"classified 'baseline-relaxation' changeset (gate={gate!r}, dataset={dataset!r}) "
        f"carrying a non-empty 'tempdoc:' field under {changesets_dir}. "
        f"See scripts/jseval/.changesets/README.md."
    )


def assert_baseline_not_removed(
    *,
    gate: str,
    dataset: str,
    changesets_dir: Path | str,
    baseline_ref: str | None = None,
) -> None:
    """Raise :class:`BaselineRelaxedWithoutJustificationError` when a pinned baseline row
    disappears and no matching, justified changeset covers it.

    Removing a row removes its floor or ceiling, which is the loosest relaxation there is; it
    needs the same ``baseline-relaxation`` changeset as :func:`assert_baseline_not_relaxed`.
    """
    if _has_justified_changeset(gate, dataset, changesets_dir, baseline_ref):
        return
    raise BaselineRelaxedWithoutJustificationError(
        f"{gate}/{dataset}: baseline row would be removed with no classified "
        f"'baseline-relaxation' changeset (gate={gate!r}, dataset={dataset!r}) carrying a "
        f"non-empty 'tempdoc:' field under {changesets_dir}, and no withhold reason recorded "
        f"for it in the new release. See scripts/jseval/.changesets/README.md."
    )
