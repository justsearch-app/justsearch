"""Shared ratchet-gate kernel (tempdoc 640 K).

The three engine-quality gates — relevance (`relevance_gate`), performance (`perf_gate`), and
recall-leak (`leak_gate`) — and the new LLM-generation gate all duplicate the same CLI orchestration:
load the baselines file (optionally projecting floors from a `current_release` pointer), locate the run
dir, read the run's source artifact, call the family's `evaluate`, then emit a 0/1/2 report. They
differ only in the *source* (per-mode/per-run `summary.json` vs a projection artifact vs a bench file),
the comparator (which lives inside each family's `evaluate`), and a few family-specific options. This
module owns the SHARED parts so a new family reuses them instead of forking a fourth copy; the
per-family `evaluate`/`project` functions stay in their own modules.

The pure helpers (`load_baselines_doc`, `build_summary`) are unit-testable; `resolve_run_dir` /
`finalize_report` do CLI I/O (echo + `sys.exit`) and are exercised by the live gate runs.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, Callable

import click

from . import gate as _gate


def load_baselines_doc(
    baselines_path: str | Path,
    *,
    project_release: Callable[[dict, dict], dict] | None = None,
) -> dict:
    """Load a ratchet baselines file, projecting floors from ``current_release`` when present.

    Faithful lift of the relevance/perf gates' inline logic (tempdoc 623 T-5): a ``current_release``
    pointer + ``fallback_baselines`` means the per-corpus floors are PROJECTED live from the canonical
    release (never hand-typed), so the heterogeneous-commit fork is structurally impossible.
    ``project_release(release_doc, baselines_doc) -> {"baselines": {...}, ...}`` is the family's release
    projector (the second arg lets relevance read its ``tolerance_*`` from the baselines doc; perf ignores
    it). Pass ``None`` for a gate with no release projection (leak / llm-gen), leaving inline baselines as-is.
    """
    path = Path(baselines_path)
    doc = json.loads(path.read_text(encoding="utf-8"))
    current_release = doc.get("current_release")
    if current_release and project_release is not None:
        release_path = (path.parent / current_release).resolve()
        if release_path.is_file():
            release = json.loads(release_path.read_text(encoding="utf-8"))
            if release.get("status") == "superseded":
                raise ValueError(f"superseded release cannot supply regression floors: {release_path}")
            projected = project_release(release, doc)
            merged = dict(doc.get("fallback_baselines") or {})
            merged.update(projected.get("baselines") or {})  # release wins over fallback
            doc = {**doc, **projected, "baselines": merged}
        else:
            # Pointer set but the release file is missing — degrade to fallback, never crash.
            doc = {**doc, "baselines": doc.get("fallback_baselines") or {}}
    return doc


def compare_engine_sets(
    run_engines: list[str] | None, baseline_engines: list[str] | None
) -> tuple[str, str | None]:
    """Pure verdict for the realized-engine-set homogeneity check (tempdoc 644).

    Returns ``(verdict, reason)`` with verdict in ``{"ok", "skip", "mismatch"}``. ``skip`` when
    either side does not record its engine set (an old release, or a run predating the field) —
    so enforcement turns on only once a release is recomposed, never breaking existing baselines.
    A ``mismatch`` is the apples-to-oranges comparison 644 guards (e.g. a CE-on baseline vs a
    cross-encoder-silently-off HEAD run): comparing their nDCG/latency would average two pipelines.
    """
    if run_engines is None or baseline_engines is None:
        return ("skip", "realized engine set not recorded on one side (backward-compatible no-op)")
    if sorted(run_engines) == sorted(baseline_engines):
        return ("ok", None)
    return (
        "mismatch",
        f"the run realized engines {sorted(run_engines)} but the baseline was measured with "
        f"{sorted(baseline_engines)} — comparing them averages two different pipelines "
        f"(e.g. cross-encoder on vs off, tempdoc 644)",
    )


def baseline_engine_set(baselines_path: str | Path) -> list[str] | None:
    """The realized engine set the baseline release was measured under, or ``None`` if not recorded.

    Reads the baselines doc's ``current_release`` pointer → the release's ``cohort.realized_engines``
    (recorded by ``release.compose``). ``None`` (→ skip) when there is no release pointer, the release
    file is missing, or it predates the field — never raises.
    """
    path = Path(baselines_path)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
        current_release = doc.get("current_release")
        if not current_release:
            return None
        release_path = (path.parent / current_release).resolve()
        if not release_path.is_file():
            return None
        release = json.loads(release_path.read_text(encoding="utf-8"))
        return (release.get("cohort") or {}).get("realized_engines")
    except Exception:
        return None


def assert_cohort_engines(
    run_dir: str | Path,
    baselines_path: str | Path,
    *,
    allow_mismatch: bool = False,
) -> None:
    """Refuse a ratchet comparison when the HEAD run's engine set ≠ the baseline's (tempdoc 644).

    Centralized so relevance / perf / leak all inherit one homogeneity check. Reads the run's
    ``realized_engines`` from ``manifest.json`` and the baseline's from its release; on an
    un-overridden ``mismatch`` echoes a legible error and ``sys.exit(2)`` (the infra-issue code, as
    ``run_dataset_ok`` uses). ``skip``/``ok`` return silently. ``--allow-engine-mismatch`` overrides.
    """
    manifest_path = Path(run_dir) / "manifest.json"
    run_manifest = (json.loads(manifest_path.read_text(encoding="utf-8"))
                    if manifest_path.is_file() else None)
    run_engines = ((run_manifest or {}).get("model_fingerprints") or {}).get("realized_engines")
    verdict, reason = compare_engine_sets(run_engines, baseline_engine_set(baselines_path))
    if verdict == "mismatch" and not allow_mismatch:
        click.echo(json.dumps({
            "exit_code": 2,
            "error": f"engine-set mismatch: {reason}. Re-run with the baseline's engine set, "
                     f"recompose the release, or pass --allow-engine-mismatch.",
            "run_engines": sorted(run_engines),
            "baseline_engines": sorted(baseline_engine_set(baselines_path) or []),
        }, indent=2), err=True)
        sys.exit(2)


def assert_chunk_completeness(
    run_dir: str | Path,
    *,
    allow_incomplete: bool = False,
) -> None:
    """Refuse a ratchet comparison when the run's index shipped without its chunk sub-system
    despite the corpus needing one (tempdoc 718 -- the 717 degenerate-build containment).

    Reads the run's already-embedded ``chunk_completeness`` block from ``summary.json``
    (computed once, at run-build time, by ``run._compute_chunk_completeness`` -- this
    function does not recompute the verdict, it enforces the one the run already carries).
    On an un-overridden ``degenerate`` verdict: echoes a legible error + remedy and
    ``sys.exit(2)`` (the infra-issue code, matching ``assert_cohort_engines``).
    ``ok``/``chunk-free`` return silently. A run predating this guard (no
    ``chunk_completeness`` block, or no ``summary.json`` at all) has no verdict to enforce and
    is treated as ``ok`` -- backward-compatible, matches ``compare_engine_sets``'s "skip when
    unrecorded" precedent.

    ``unevaluable`` (tempdoc 821 §3-C3 -- the backend published no chunk threshold, so the
    offline expectation could not be computed) passes, but NOT silently: it warns on stderr like
    the override path below. A silent pass here would be the guard's own failure mode -- on a
    threshold-less backend a genuinely degenerate build would sail through with nothing said,
    which is exactly the "0 reads the same as healthy" class this guard exists to catch.

    Override with ``allow_incomplete=True`` (the CLI's ``--allow-chunk-incompleteness`` flag)
    or the ``JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1`` env var (checked here, once, so all four
    call sites get the env escape hatch without repeating the check) -- mirrors
    ``--allow-engine-mismatch`` / ``JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSEVAL``. Never silent: the
    override still fires, it just downgrades the refusal to a warning.
    """
    summary_path = Path(run_dir) / "summary.json"
    summary = (json.loads(summary_path.read_text(encoding="utf-8"))
               if summary_path.is_file() else {})
    block = summary.get("chunk_completeness") or {}
    verdict = block.get("verdict")
    if verdict == "unevaluable":
        click.echo(json.dumps({
            "warning": "chunk-completeness guard STOOD DOWN — this run was not checked",
            "reasons": block.get("reasons"),
            "remedy": "run against a backend that publishes worker.enrichment.chunkMinChars "
                      "(tempdoc 821 §3-C3) to get a real verdict.",
            "observed": block.get("observed"),
        }, indent=2), err=True)
        return
    if verdict != "degenerate":
        return

    overridden = allow_incomplete or os.environ.get("JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS") == "1"
    if overridden:
        click.echo(json.dumps({
            "warning": "chunk-completeness guard overridden (--allow-chunk-incompleteness / "
                       "JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1)",
            "expected": block.get("expected"), "observed": block.get("observed"),
            "reasons": block.get("reasons"),
        }, indent=2), err=True)
        return

    click.echo(json.dumps({
        "exit_code": 2,
        "error": f"chunk-completeness guard: this corpus has {block.get('expected')} doc(s) "
                 f"expected to chunk, but the index observed {block.get('observed')} chunk "
                 f"doc(s) -- {'; '.join(block.get('reasons') or [])}. The index likely shipped "
                 f"with its chunk sub-system missing (tempdoc 717). Re-ingest (--clean) and "
                 f"re-run, or pass --allow-chunk-incompleteness / set "
                 f"JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1 to certify anyway.",
        "expected": block.get("expected"),
        "observed": block.get("observed"),
        "reasons": block.get("reasons"),
    }, indent=2), err=True)
    sys.exit(2)


def assert_ce_coverage(
    run_dir: str | Path,
    *,
    allow_degraded: bool = False,
) -> None:
    """Refuse a ratchet comparison when the run's cross-encoder silently dropped out on part of
    the query set (register F-052).

    A rerank RPC that misses ``justsearch.rerank.deadline_ms`` leaves the query in pure fusion
    order while every existing gate signal stays green (``comparable: true``, ``ann_proof PASS``,
    ``error_count 0``, ``cross_encoder`` still in the observed legs). The run's metrics are then a
    blend of two pipelines. Reads the run's already-embedded ``ce_coverage`` block from
    ``summary.json`` (computed once, at run-build time, by ``run._compute_ce_coverage`` — this
    function enforces the verdict the run already carries, it does not recompute it). On an
    un-overridden ``degraded-ce`` verdict: echoes a legible error + remedy and ``sys.exit(2)``,
    matching ``assert_chunk_completeness``.

    ``ok``/``not-applicable`` return silently. ``unevaluable`` (the run's artifacts predate
    ``judgeSignals`` or the cross-encoder reason channel) passes but WARNS on stderr — the same
    loud stand-down ``assert_chunk_completeness`` gives its own unevaluable verdict, because a
    silent pass on absent telemetry is this guard's own failure mode. A run predating the guard
    entirely (no ``ce_coverage`` block, or no ``summary.json``) has no verdict to enforce and is
    treated as ``ok``, matching ``compare_engine_sets``'s "skip when unrecorded" precedent.

    Override with ``allow_degraded=True`` (the CLI's ``--allow-ce-degradation`` flag) or
    ``JUSTSEARCH_ALLOW_CE_DEGRADATION=1``. Never silent: the override downgrades the refusal to a
    warning.
    """
    summary_path = Path(run_dir) / "summary.json"
    summary = (json.loads(summary_path.read_text(encoding="utf-8"))
               if summary_path.is_file() else {})
    block = summary.get("ce_coverage") or {}
    verdict = block.get("verdict")
    if verdict == "unevaluable":
        click.echo(json.dumps({
            "warning": "ce-coverage guard STOOD DOWN — this run was not checked",
            "reasons": block.get("reasons"),
            "remedy": "re-run against a jseval that records judgeSignals + the cross-encoder "
                      "stage's status/reason per query (register F-052) to get a real verdict.",
        }, indent=2), err=True)
        return
    if verdict != "degraded-ce":
        return

    overridden = allow_degraded or os.environ.get("JUSTSEARCH_ALLOW_CE_DEGRADATION") == "1"
    if overridden:
        click.echo(json.dumps({
            "warning": "ce-coverage guard overridden (--allow-ce-degradation / "
                       "JUSTSEARCH_ALLOW_CE_DEGRADATION=1)",
            "reasons": block.get("reasons"),
            "per_mode": block.get("per_mode"),
        }, indent=2), err=True)
        return

    click.echo(json.dumps({
        "exit_code": 2,
        "error": "ce-coverage guard: the cross-encoder silently dropped out on part of this "
                 "run's query set — those queries were delivered in pure fusion order with no "
                 "deterministic reason recorded, so the run's metrics blend two pipelines and "
                 "are not comparable. Re-run (raise justsearch.rerank.deadline_ms or reduce "
                 "concurrent load), or pass --allow-ce-degradation / set "
                 "JUSTSEARCH_ALLOW_CE_DEGRADATION=1 to certify anyway. "
                 f"{'; '.join(block.get('reasons') or [])}",
        "tolerance": block.get("tolerance"),
        "reasons": block.get("reasons"),
        "per_mode": block.get("per_mode"),
    }, indent=2), err=True)
    sys.exit(2)


def resolve_run_dir(run_dir: str | None, data_dir: str | Path, dataset: str | None = None) -> Path:
    """Return the explicit ``--run-dir`` or the latest eval-results run; echo + ``exit 2`` if none.

    ``dataset``, when given and ``run_dir`` is not explicit, restricts the auto-resolved
    "latest" run to one whose own ``summary.json`` records that dataset — see
    :func:`jseval.gate._latest_run_dir`. A data-dir holding runs for more than one dataset
    hard-errors here (exit 2) instead of silently gating the wrong corpus's metrics against
    ``dataset``'s baseline when the two datasets' latest runs disagree. An explicit
    ``--run-dir`` is trusted as-is (the caller named it deliberately).
    """
    if run_dir:
        return Path(run_dir)
    rd = _gate._latest_run_dir(Path(data_dir), dataset=dataset)
    if rd is None:
        detail = ("no eval-results run with summary.json matching "
                  f"dataset={dataset!r}" if dataset else "no eval-results run with summary.json")
        click.echo(json.dumps({"exit_code": 2, "error": detail}, indent=2), err=True)
        sys.exit(2)
    return rd


def build_summary(report: dict, summary_fields: tuple[str, ...]) -> dict:
    """The compact gate summary echoed to stderr — pure, so it is unit-testable.

    ``summary_fields`` are the extra report keys a family surfaces beyond exit_code/dataset/checks
    (relevance+leak: ``current``/``baseline``/``floor``; perf: ``mode``).
    """
    return {
        "exit_code": report["exit_code"],
        "dataset": report.get("dataset"),
        **{k: report.get(k) for k in summary_fields},
        "checks": {c["name"]: c["status"] for c in report.get("checks", [])},
    }


def finalize_report(
    report: dict,
    *,
    run_dir: Path,
    baselines_path: str | Path,
    report_out: str | None,
    summary_fields: tuple[str, ...] = (),
) -> None:
    """Attach provenance, optionally write the full report, echo the compact summary, ``sys.exit``."""
    report["run_dir"] = str(run_dir)
    report["baselines_path"] = str(baselines_path)
    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False), encoding="utf-8")
    click.echo(json.dumps(build_summary(report, summary_fields), indent=2), err=True)
    sys.exit(report["exit_code"])


def run_gate(
    *,
    baselines_path: str | Path,
    data_dir: str | Path,
    run_dir: str | None,
    dataset: str,
    read_inputs: Callable[[Path], Any],
    evaluate: Callable[..., dict],
    project_release: Callable[[dict, dict], dict] | None = None,
    report_out: str | None = None,
    summary_fields: tuple[str, ...] = (),
) -> None:
    """Compose the shared flow for a SIMPLE gate (no family-specific guards / --update-baseline):
    load baselines → locate run → ``read_inputs(run_dir)`` → ``evaluate(baselines_doc, *inputs, dataset)``
    → finalize. Gates with extra steps (perf's dataset guard + --update-baseline) call the helpers
    directly instead. ``read_inputs`` returns a tuple spliced as positional args before ``dataset``.
    """
    baselines_doc = load_baselines_doc(baselines_path, project_release=project_release)
    rd = resolve_run_dir(run_dir, data_dir, dataset=dataset)
    inputs = read_inputs(rd)
    args = inputs if isinstance(inputs, tuple) else (inputs,)
    report = evaluate(baselines_doc, *args, dataset)
    finalize_report(report, run_dir=rd, baselines_path=baselines_path,
                    report_out=report_out, summary_fields=summary_fields)
