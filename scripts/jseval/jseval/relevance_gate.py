"""Q-010 relevance ratchet (tempdoc 580 §4c / register Q-010).

The engine's equivalent of the UI's continuous-servicing discipline gates: it
fails loudly when a search-engine change drops a pinned corpus's nDCG@10 below
its floor, instead of letting relevance silently coast (tempdoc 580 §3 — the
enforcement asymmetry that *caused* the freeze).

Unlike :mod:`jseval.gate` (which checks the *stdev* of nDCG@10 for latency/
non-determinism drift), this checks the *mean* nDCG@10 against a pinned
per-corpus floor in ``gates/relevance-ratchet/baselines.v1.json``.

Exit codes (see :func:`jseval.commands.gates.cmd_relevance_gate`):

- 0 — no regression (or the dataset is not pinned → does not gate).
- 1 — regression: current nDCG@10 < (pinned baseline - tolerance).
- 2 — data problem (run summary missing the pinned mode's nDCG@10).

The core :func:`evaluate` is a pure function over already-parsed dicts so it is
unit-testable without a live eval run.
"""

from __future__ import annotations

from typing import Any


def project_release_to_baselines(
    release: dict,
    *,
    tolerance_default_abs: float = 0.02,
    per_corpus_tolerance: dict | None = None,
) -> dict:
    """Project a ``release.v1`` object into the ratchet's baselines dict shape.

    Tempdoc 623 T-5 (live-read re-rooting): the floor for each corpus is the
    release's *measured* nDCG@10 in the mode the release measured (its
    ``default_mode``, not the product's AUTO default), not a hand-typed
    number. The output is the exact ``{"baselines": {<dataset>: {mode, nDCG@10,
    tolerance_abs, src}}, "tolerance_default_abs": ...}`` shape :func:`evaluate`
    already consumes — so ``evaluate`` is unchanged and the fork becomes
    structurally impossible (there is no table of numbers to hand-edit).

    Corpora measured on a non-nDCG metric family (an extraction-style release)
    are skipped here — this ratchet is the *retrieval* ratchet.
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    cohort = release.get("cohort") or {}
    src_tag = release.get("release_id") or (cohort.get("git_sha") or "")[:10]
    baselines: dict[str, dict] = {}
    for dataset, measured in (release.get("measured") or {}).items():
        ndcg = (measured.get("metrics") or {}).get("nDCG@10")
        if not isinstance(ndcg, (int, float)):
            continue
        entry = {
            "mode": measured.get("config_mode"),
            "nDCG@10": ndcg,
            "tolerance_abs": per_corpus_tolerance.get(dataset, tolerance_default_abs),
            "src": f"projected from release {src_tag}".strip(),
        }
        baselines[dataset] = entry
    return {
        "schema": "relevance-ratchet-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "projected_from_release": True,
        "baselines": baselines,
    }


def _ndcg_for_mode(summary: dict, mode: str) -> Any:
    """Read ``per_mode.<mode>.aggregate_metrics.nDCG@10`` from a run summary.json."""
    per_mode = (summary.get("per_mode") or {}).get(mode) or {}
    return (per_mode.get("aggregate_metrics") or {}).get("nDCG@10")


def evaluate(baselines: dict, summary: dict, dataset: str) -> dict:
    """Compare a run's nDCG@10 against the pinned floor for ``dataset``.

    :param baselines: parsed ``baselines.v1.json`` (``{"baselines": {...}}``).
    :param summary: parsed run ``summary.json``.
    :param dataset: the dataset slug (e.g. ``beir/scifact``).
    :returns: a report dict with ``exit_code`` and ``checks``.
    """
    report: dict = {"dataset": dataset, "checks": [], "exit_code": 0}

    pinned = (baselines.get("baselines") or {}).get(dataset)
    if pinned is None:
        report["checks"].append({
            "name": "baseline-pinned",
            "status": "skip",
            "detail": f"no pinned baseline for {dataset}; not gated",
        })
        return report  # un-pinned datasets do not gate (exit 0)

    mode = pinned.get("mode")
    baseline_ndcg = pinned.get("nDCG@10")
    tolerance = pinned.get("tolerance_abs", baselines.get("tolerance_default_abs", 0.02))
    report["mode"] = mode
    report["baseline"] = baseline_ndcg
    report["tolerance_abs"] = tolerance

    current = _ndcg_for_mode(summary, mode)
    if not isinstance(current, (int, float)):
        report["checks"].append({
            "name": "run-metric-present",
            "status": "fail",
            "detail": f"run summary has no nDCG@10 for mode '{mode}'",
        })
        report["exit_code"] = 2
        return report

    floor = baseline_ndcg - tolerance
    regressed = current < floor
    report["current"] = float(current)
    report["floor"] = floor
    report["checks"].append({
        "name": "ndcg10-no-regression",
        "status": "fail" if regressed else "ok",
        "detail": (
            f"current={current:.4f} baseline={baseline_ndcg:.4f} "
            f"floor={floor:.4f} (tolerance={tolerance})"
        ),
    })
    if regressed:
        report["exit_code"] = 1
    return report
