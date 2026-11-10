"""Phase 3 observability nightly gate (tempdoc 400 §26.6 Decision 4).

Phase 6 / 6.13: module relocated from ``scripts/ci/phase3_observability_gate.py``
into the ``jseval`` package so operators can invoke it via the same
``jseval`` CLI as every other Phase 3/4/5/6 subcommand. The public
surface (``evaluate`` + ``REQUIRED_PROJECTIONS`` + helper functions)
is unchanged; the argparse shim was dropped in favour of the
``jseval gate`` Click subcommand in :mod:`jseval.cli`.

Validates the latest eval-results run directory produced by the Phase 3
nightly workflow:

1. The run has a readable ``manifest.json`` (its ``manifest_hash`` is
   reported as the run's cohort identity).
2. It has a populated ``projections/`` directory with the LR4-* outputs
   actually produced (no hard-fail on any single projection status, but
   both ``contract_violations`` and ``rate_timeline`` must exist).

Tempdoc 930 §18.1 row 7 removed the σ-band arm: it compared the cohort's
calibrated ``envelope.json`` against a pinned ``--baseline-stdev``, and no
``cohort_baselines/`` directory ever existed on any machine, so that arm
could only ever report the infra exit.

Exit codes (see :func:`jseval.commands.gates.cmd_gate`):

- 0 — gate passed.
- 1 — a hard assertion failed (manifest unreadable, required projection
  absent).
- 2 — data-layout problem (no eval-results run found) — usually an infra
  issue, not a quality drift.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

REQUIRED_PROJECTIONS = (
    "contract_violations",
    "rate_timeline",
)


def _latest_run_dir(data_dir: Path, dataset: str | None = None) -> Path | None:
    """Return the most recently named run directory under data_dir/eval-results.

    ``dataset``, when given, restricts candidates to runs whose own ``summary.json``
    records that dataset (canonical-slug compared, e.g. ``scifact`` == ``beir/scifact`` —
    :func:`jseval.release.canonical_dataset_slug`) — picking the latest MATCHING run
    instead of the latest run overall. Without this filter, a data-dir holding runs for
    two different datasets silently resolves to whichever run sorts latest by directory
    name, regardless of which dataset a gate was asked to check — comparing the wrong
    corpus's metrics against the requested dataset's baseline with no error (a real
    incident: a two-dataset eval-results/ compared the wrong corpus's nDCG@10). Returns
    ``None`` (the caller's existing "no run found" hard-error path) rather than silently
    falling back to the overall-latest run when no candidate matches.
    """
    eval_results = data_dir / "eval-results"
    if not eval_results.is_dir():
        return None
    candidates = [p for p in eval_results.iterdir()
                  if p.is_dir() and (p / "summary.json").is_file()]
    if not candidates:
        return None
    candidates.sort(key=lambda p: p.name, reverse=True)
    if dataset is None:
        return candidates[0]
    from .release import canonical_dataset_slug

    wanted = canonical_dataset_slug(dataset)
    for p in candidates:
        summary = _load_json(p / "summary.json")
        run_dataset = summary.get("dataset") if isinstance(summary, dict) else None
        if run_dataset is not None and canonical_dataset_slug(run_dataset) == wanted:
            return p
    return None


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def evaluate(data_dir: Path) -> dict:
    report: dict = {
        "data_dir": str(data_dir),
        "checks": [],
        "exit_code": 0,
    }

    run_dir = _latest_run_dir(data_dir)
    if run_dir is None:
        report["checks"].append({
            "name": "run-dir-present",
            "status": "fail",
            "detail": "no eval-results run directory with summary.json",
        })
        report["exit_code"] = 2
        return report
    report["run_dir"] = str(run_dir)

    manifest = _load_json(run_dir / "manifest.json")
    if not isinstance(manifest, dict):
        report["checks"].append({
            "name": "run-manifest-present",
            "status": "fail",
            "detail": "run manifest.json missing or unparseable",
        })
        report["exit_code"] = 1
    else:
        report["cohort_hash"] = manifest.get("manifest_hash")
        report["checks"].append({
            "name": "run-manifest-present",
            "status": "ok",
        })

    projections_dir = run_dir / "projections"
    present = set()
    if projections_dir.is_dir():
        present = {p.stem for p in projections_dir.glob("*.json")}
    report["projections_present"] = sorted(present)
    missing = [p for p in REQUIRED_PROJECTIONS if p not in present]
    if missing:
        report["checks"].append({
            "name": "required-projections-present",
            "status": "fail",
            "detail": f"missing projections: {missing}",
        })
        report["exit_code"] = 1
    else:
        report["checks"].append({
            "name": "required-projections-present",
            "status": "ok",
        })

    return report
