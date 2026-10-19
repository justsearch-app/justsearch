"""jseval gates commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click

from .._paths import DEFAULT_JSEVAL_DATA_DIR
from ._common import _DEFAULT_BASE_URL_EVAL

log = logging.getLogger(__name__)


@click.command("gate")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_gate(ctx, data_dir, report_out):
    """Phase 3 observability nightly gate (Phase 6 / 6.13).

    Validates that the latest eval-results run has a readable manifest and
    that the required LR4 projections all produced outputs. Exit code
    0 = pass, 1 = layout drift, 2 = infra issue (no run dir). The nightly
    workflow opens a GitHub issue on any non-zero exit.

    Moved from ``scripts/ci/phase3_observability_gate.py`` into the
    jseval package so operators get discovery via ``jseval --help``
    alongside every other Phase 3 subcommand.
    """
    from .. import gate as _gate

    report = _gate.evaluate(Path(data_dir))

    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
            encoding="utf-8",
        )

    # Legible stderr summary for CI logs (full JSON is in --report-out).
    summary = {
        "exit_code": report["exit_code"],
        "cohort_hash": report.get("cohort_hash"),
        "checks": {c["name"]: c["status"] for c in report["checks"]},
    }
    click.echo(json.dumps(summary, indent=2), err=True)
    sys.exit(report["exit_code"])


@click.command("relevance-gate")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's summary.json is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. beir/scifact).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to relevance-ratchet baselines.v1.json "
                   "(default: repo gates/relevance-ratchet/baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with summary.json). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off). Use only when comparing degraded "
                   "numbers deliberately.")
@click.option("--allow-chunk-incompleteness", is_flag=True,
              help="Override the tempdoc-718 chunk-completeness refusal (the run's index "
                   "observed no/incomplete chunk docs despite the corpus needing them — likely "
                   "the tempdoc-717 degenerate build). Also settable via "
                   "JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1. Use only when certifying a "
                   "deliberately chunk-incomplete run.")
@click.option("--allow-ce-degradation", is_flag=True,
              help="Override the register-F-052 ce-coverage refusal (the cross-encoder silently "
                   "dropped out on part of the query set — e.g. the rerank deadline was exceeded "
                   "— so the run's metrics blend a reranked and a fusion-only pipeline). Also "
                   "settable via JUSTSEARCH_ALLOW_CE_DEGRADATION=1.")
@click.pass_context
def cmd_relevance_gate(ctx, data_dir, dataset, baselines, run_dir, report_out,
                       allow_engine_mismatch, allow_chunk_incompleteness, allow_ce_degradation):
    """Q-010 relevance ratchet (tempdoc 580 §4c) — fail on nDCG@10 regression.

    Reads the latest eval-results run's summary.json for DATASET and compares
    its nDCG@10 (in the pinned mode) against the per-corpus floor in
    gates/relevance-ratchet/baselines.v1.json. Unlike ``gate`` (which checks
    nDCG@10 *stdev* for drift), this checks the *mean* against a regression
    floor. Exit 0 = no regression (or un-pinned dataset), 1 = regression,
    2 = run metric missing.
    """
    from .. import ratchet_kernel as _rk
    from .. import relevance_gate as _rgate

    if baselines is None:
        # Baselines live with the jseval consumer (this is a jseval gate, not a kernel gate).
        # tempdoc 664 (post-review fix): this file is scripts/jseval/jseval/commands/gates.py, so
        # parents[0]=commands, parents[1]=jseval (the package dir) -- the baseline .v1.json files
        # actually live one level higher, at scripts/jseval/ (parents[2]). The previous
        # parents[1] computation pointed at a nonexistent path; every default-baselines/--out
        # computation in this file shared the identical off-by-one, fixed uniformly here.
        baselines = Path(__file__).resolve().parents[2] / "relevance-ratchet-baselines.v1.json"
    # Tempdoc 640 K: the shared load→project→locate→evaluate→report flow lives in ratchet_kernel.
    # The `current_release` projection (tempdoc 623 T-5: floors PROJECTED live, never hand-typed)
    # reads this gate's tolerance from the baselines doc (the projector's 2nd arg).
    baselines_doc = _rk.load_baselines_doc(
        baselines,
        project_release=lambda rel, base: _rgate.project_release_to_baselines(
            rel,
            tolerance_default_abs=base.get("tolerance_default_abs", 0.02),
            per_corpus_tolerance=base.get("per_corpus_tolerance"),
        ),
    )
    rd = _rk.resolve_run_dir(run_dir, data_dir, dataset=dataset)
    # tempdoc 644: refuse to compare a run whose realized engine set differs from the baseline's
    # (e.g. a CE-off worktree run vs a CE-on baseline) — apples-to-oranges. Backward-compatible.
    _rk.assert_cohort_engines(rd, baselines, allow_mismatch=allow_engine_mismatch)
    # tempdoc 718: refuse a run whose index shipped without its chunk sub-system (the 717
    # degenerate build) despite the corpus needing one. Backward-compatible (no-verdict skips).
    _rk.assert_chunk_completeness(rd, allow_incomplete=allow_chunk_incompleteness)
    # register F-052: refuse a run whose cross-encoder silently dropped out on part of the query
    # set (rerank deadline exceeded) — its metrics blend two pipelines. Backward-compatible.
    _rk.assert_ce_coverage(rd, allow_degraded=allow_ce_degradation)
    run_summary = json.loads((rd / "summary.json").read_text(encoding="utf-8"))
    report = _rgate.evaluate(baselines_doc, run_summary, dataset)
    _rk.finalize_report(report, run_dir=rd, baselines_path=baselines,
                        report_out=report_out, summary_fields=("current", "baseline", "floor"))


@click.command("perf-gate")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's summary.json is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. beir/scifact).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to perf-ratchet-baselines.v1.json "
                   "(default: repo scripts/jseval/perf-ratchet-baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with summary.json). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--mode", default="hybrid", show_default=True,
              help="Mode to pin when --update-baseline.")
@click.option("--update-baseline", is_flag=True,
              help="Re-pin the floor for --dataset from the selected run (project its "
                   "measured metrics into the baselines file), then exit 0 without gating. "
                   "Use after a deliberate, justified perf change.")
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off).")
@click.option("--allow-chunk-incompleteness", is_flag=True,
              help="Override the tempdoc-718 chunk-completeness refusal (the run's index "
                   "observed no/incomplete chunk docs despite the corpus needing them — likely "
                   "the tempdoc-717 degenerate build). Also settable via "
                   "JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1.")
@click.option("--allow-ce-degradation", is_flag=True,
              help="Override the register-F-052 ce-coverage refusal (the cross-encoder silently "
                   "dropped out on part of the query set — e.g. the rerank deadline was exceeded). "
                   "Also settable via JUSTSEARCH_ALLOW_CE_DEGRADATION=1.")
@click.pass_context
def cmd_perf_gate(ctx, data_dir, dataset, baselines, run_dir, report_out, mode, update_baseline,
                  allow_engine_mismatch, allow_chunk_incompleteness, allow_ce_degradation):
    """Performance ratchet (tempdoc 640) — fail on a latency/throughput/footprint regression.

    The perf-metric-family sibling of ``relevance-gate``. Reads the latest eval-results run's
    summary.json (+ manifest.json for footprint) for DATASET and compares each pinned metric
    against its RELATIVE band in scripts/jseval/perf-ratchet-baselines.v1.json (no absolute
    SLO — tempdoc 640 §C-6). Gate-able metrics: cross-encoder STAGE p50 latency, primary +
    enrichment throughput, retrieval ONNX footprint (best-effort). Exit 0 = no regression
    (or un-pinned dataset), 1 = regression, 2 = a pinned metric missing from the run.
    """
    from .. import perf_gate as _pgate
    from .. import ratchet_kernel as _rk

    if baselines is None:
        # Baselines live with the jseval consumer (a jseval gate, not a kernel gate).
        baselines = Path(__file__).resolve().parents[2] / "perf-ratchet-baselines.v1.json"
    # Tempdoc 640 K: shared load + `current_release` projection (the perf floor projects from the
    # canonical release, closing the per-run fork) via the kernel; the perf-specific dataset guard +
    # --update-baseline stay below.
    baselines_doc = _rk.load_baselines_doc(
        baselines,
        project_release=lambda rel, base: _pgate.project_release_to_perf_baselines(rel),
    )
    rd = _rk.resolve_run_dir(run_dir, data_dir, dataset=dataset)
    run_summary = json.loads((rd / "summary.json").read_text(encoding="utf-8"))
    manifest_path = rd / "manifest.json"
    run_manifest = (json.loads(manifest_path.read_text(encoding="utf-8"))
                    if manifest_path.is_file() else None)

    # Guard against comparing a run of a different corpus against this dataset's baseline
    # (review fix #4); also protects --update-baseline from pinning the wrong run.
    if not _pgate.run_dataset_ok(run_manifest, dataset):
        click.echo(json.dumps({
            "exit_code": 2,
            "error": f"latest run is for '{(run_manifest or {}).get('dataset')}', "
                     f"not '{dataset}' -- pass --run-dir for the right run",
        }, indent=2), err=True)
        sys.exit(2)

    # tempdoc 644: refuse a cross-engine-set comparison (run vs baseline realized engines differ);
    # also protects --update-baseline from pinning a degraded (e.g. CE-off) run. Backward-compatible.
    _rk.assert_cohort_engines(rd, baselines, allow_mismatch=allow_engine_mismatch)
    # tempdoc 718: refuse a run whose index shipped without its chunk sub-system; also protects
    # --update-baseline from pinning a chunk-degenerate run. Backward-compatible (no-verdict skips).
    _rk.assert_chunk_completeness(rd, allow_incomplete=allow_chunk_incompleteness)
    # register F-052: a CE that silently dropped out inflates the ce_p50_ms this gate pins, so the
    # refusal also protects --update-baseline from pinning a half-reranked run.
    _rk.assert_ce_coverage(rd, allow_degraded=allow_ce_degradation)

    if update_baseline:
        # Re-pin the floor from this (green) run -- measured, never hand-typed (review fix #3).
        modes_present = list((run_summary.get("per_mode") or {}).keys())
        use_mode = mode if mode in modes_present else (modes_present[0] if modes_present else mode)
        src = ((run_manifest or {}).get("git_sha") or "")[:10]
        proj = _pgate.project_run_to_perf_baselines(
            run_summary, dataset, use_mode, manifest=run_manifest, src=src)
        entry = proj["baselines"][dataset]

        # tempdoc 664: a re-pin can silently launder a regression into the new "baseline" — a
        # perf-ratchet-baselines.v1.json comment already documented this happening once. Refuse
        # per-metric unless the relaxation carries a classified, justified changeset.
        from .. import baseline_shift as _bshift
        from .. import metric_families as _mf
        lower_is_better = {k: v for fam in _mf.REGISTRY for k, v in fam.lower_is_better.items()}
        old_entry = (baselines_doc.get("baselines") or {}).get(dataset)
        old_metrics = (old_entry or {}).get("metrics") or {}
        changesets_dir = Path(baselines).resolve().parent / ".changesets"
        try:
            for metric, new_value in (entry.get("metrics") or {}).items():
                old_value = old_metrics.get(metric)
                if not isinstance(old_value, (int, float)) or not isinstance(new_value, (int, float)):
                    continue
                _bshift.assert_baseline_not_relaxed(
                    old_value, new_value,
                    lower_is_better=lower_is_better.get(metric, True),
                    gate="perf-gate", dataset=f"{dataset}:{metric}",
                    changesets_dir=changesets_dir,
                )
        except _bshift.BaselineRelaxedWithoutJustificationError as e:
            click.echo(json.dumps({"exit_code": 1, "error": str(e)}, indent=2), err=True)
            sys.exit(1)

        baselines_doc.setdefault("baselines", {})[dataset] = entry
        Path(baselines).write_text(
            json.dumps(baselines_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        click.echo(json.dumps({
            "updated": dataset, "mode": use_mode, "run_dir": str(rd), "entry": entry,
            "baselines_path": str(baselines),
        }, indent=2), err=True)
        sys.exit(0)

    report = _pgate.evaluate(baselines_doc, run_summary, dataset, manifest=run_manifest)
    _rk.finalize_report(report, run_dir=rd, baselines_path=baselines,
                        report_out=report_out, summary_fields=("mode",))


@click.command("leak-gate")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's projection is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. mixed/enron-qa).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to leak-gate-baselines.v1.json "
                   "(default: jseval/leak-gate-baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with projections/). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off).")
@click.option("--allow-chunk-incompleteness", is_flag=True,
              help="Override the tempdoc-718 chunk-completeness refusal (the run's index "
                   "observed no/incomplete chunk docs despite the corpus needing them — likely "
                   "the tempdoc-717 degenerate build). Also settable via "
                   "JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1.")
@click.option("--allow-ce-degradation", is_flag=True,
              help="Override the register-F-052 ce-coverage refusal (the cross-encoder silently "
                   "dropped out on part of the query set — e.g. the rerank deadline was exceeded). "
                   "Also settable via JUSTSEARCH_ALLOW_CE_DEGRADATION=1.")
@click.pass_context
def cmd_leak_gate(ctx, data_dir, dataset, baselines, run_dir, report_out, allow_engine_mismatch,
                  allow_chunk_incompleteness, allow_ce_degradation):
    """Recall-leak ratchet (tempdoc 636 / register D-005) — fail on leak-rate regression.

    Reads the latest eval-results run's staged_recall_accounting projection for
    DATASET and compares its leak_rate against the per-corpus *ceiling* in
    leak-gate-baselines.v1.json. The recall-survival sibling of ``relevance-gate``.
    Exit 0 = no regression (or un-pinned dataset), 1 = regression, 2 = projection
    missing.
    """
    from .. import leak_gate as _lgate
    from .. import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[2] / "leak-gate-baselines.v1.json"

    def _read_projection(rd: Path):
        # Leak's source is a projection artifact (not the run summary) — exit 2 if it's absent.
        pp = rd / "projections" / "staged_recall_accounting.json"
        if not pp.is_file():
            click.echo(json.dumps(
                {"exit_code": 2, "error": f"no staged_recall_accounting projection in {rd}"},
                indent=2), err=True)
            sys.exit(2)
        return json.loads(pp.read_text(encoding="utf-8"))

    # tempdoc 644: refuse a cross-engine-set comparison before gating. resolve_run_dir is
    # deterministic so calling it here + inside run_gate is consistent. Backward-compatible.
    _resolved_rd = _rk.resolve_run_dir(run_dir, data_dir, dataset=dataset)
    _rk.assert_cohort_engines(_resolved_rd, baselines, allow_mismatch=allow_engine_mismatch)
    # tempdoc 718: refuse a run whose index shipped without its chunk sub-system.
    # Backward-compatible (no-verdict skips) -- summary.json (not the projection) carries the block.
    _rk.assert_chunk_completeness(_resolved_rd, allow_incomplete=allow_chunk_incompleteness)
    # register F-052: refuse a run whose cross-encoder silently dropped out on part of the query
    # set — the leak/survival accounting is read off the same contaminated ranking.
    _rk.assert_ce_coverage(_resolved_rd, allow_degraded=allow_ce_degradation)
    # Tempdoc 683: leak now uses the same `current_release` pointer as relevance/perf — ceilings
    # project from the release's optional per-corpus `leak` section, with `fallback_baselines`
    # (the previously pinned measured values) governing any corpus the release doesn't carry.
    _rk.run_gate(
        baselines_path=baselines, data_dir=data_dir, run_dir=run_dir, dataset=dataset,
        read_inputs=_read_projection, evaluate=_lgate.evaluate,
        project_release=lambda rel, base: _lgate.project_release_to_baselines(
            rel,
            tolerance_default_abs=base.get("tolerance_default_abs", _lgate.DEFAULT_TOLERANCE_ABS),
            per_corpus_tolerance=base.get("per_corpus_tolerance"),
        ),
        report_out=report_out, summary_fields=("current", "baseline", "floor"),
    )


@click.command("union-recall-gate")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's projection is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. mixed/enron-qa).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to union-recall-gate-baselines.v1.json "
                   "(default: jseval/union-recall-gate-baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with projections/). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off).")
@click.option("--allow-chunk-incompleteness", is_flag=True,
              help="Override the tempdoc-718 chunk-completeness refusal (the run's index "
                   "observed no/incomplete chunk docs despite the corpus needing them — likely "
                   "the tempdoc-717 degenerate build). Also settable via "
                   "JUSTSEARCH_ALLOW_CHUNK_INCOMPLETENESS=1.")
@click.option("--allow-ce-degradation", is_flag=True,
              help="Override the register-F-052 ce-coverage refusal (the cross-encoder silently "
                   "dropped out on part of the query set — e.g. the rerank deadline was exceeded). "
                   "Also settable via JUSTSEARCH_ALLOW_CE_DEGRADATION=1.")
@click.pass_context
def cmd_union_recall_gate(ctx, data_dir, dataset, baselines, run_dir, report_out,
                          allow_engine_mismatch, allow_chunk_incompleteness,
                          allow_ce_degradation):
    """Recall-completeness ratchet (tempdoc 701 / register D-005) — fail on a union-recall drop.

    Reads the latest eval-results run's staged_recall_accounting projection for DATASET and
    compares its leg_union_recall against the per-corpus *floor* in
    union-recall-gate-baselines.v1.json. The completeness sibling of ``leak-gate`` (same
    projection artifact, opposite direction — leak-gate is a ceiling, this is a floor). Exit 0 =
    no regression (or un-pinned dataset), 1 = regression, 2 = projection missing.
    """
    from .. import union_recall_gate as _urgate
    from .. import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[2] / "union-recall-gate-baselines.v1.json"

    def _read_projection(rd: Path):
        # Union-recall's source is a projection artifact (not the run summary) — exit 2 if absent.
        pp = rd / "projections" / "staged_recall_accounting.json"
        if not pp.is_file():
            click.echo(json.dumps(
                {"exit_code": 2, "error": f"no staged_recall_accounting projection in {rd}"},
                indent=2), err=True)
            sys.exit(2)
        return json.loads(pp.read_text(encoding="utf-8"))

    # tempdoc 644: refuse a cross-engine-set comparison before gating. resolve_run_dir is
    # deterministic so calling it here + inside run_gate is consistent. Backward-compatible.
    _resolved_rd = _rk.resolve_run_dir(run_dir, data_dir, dataset=dataset)
    _rk.assert_cohort_engines(_resolved_rd, baselines, allow_mismatch=allow_engine_mismatch)
    # tempdoc 718: refuse a run whose index shipped without its chunk sub-system.
    # Backward-compatible (no-verdict skips) -- summary.json (not the projection) carries the block.
    _rk.assert_chunk_completeness(_resolved_rd, allow_incomplete=allow_chunk_incompleteness)
    # register F-052: refuse a run whose cross-encoder silently dropped out on part of the query
    # set — the union-recall accounting is read off the same contaminated ranking.
    _rk.assert_ce_coverage(_resolved_rd, allow_degraded=allow_ce_degradation)
    # Mirrors leak-gate's tempdoc 683 pattern: floors project from the release's optional
    # per-corpus `union_recall` section, with `fallback_baselines` governing any corpus the
    # release doesn't carry.
    _rk.run_gate(
        baselines_path=baselines, data_dir=data_dir, run_dir=run_dir, dataset=dataset,
        read_inputs=_read_projection, evaluate=_urgate.evaluate,
        project_release=lambda rel, base: _urgate.project_release_to_baselines(
            rel,
            tolerance_default_abs=base.get("tolerance_default_abs", _urgate.DEFAULT_TOLERANCE_ABS),
            per_corpus_tolerance=base.get("per_corpus_tolerance"),
        ),
        report_out=report_out, summary_fields=("current", "baseline", "floor"),
    )


@click.command("llm-gate")
@click.option("--bench-file", required=True, type=click.Path(exists=True, resolve_path=True),
              help="llm-bench.json produced by `jseval llm-bench` (with AI active).")
@click.option("--baselines", type=click.Path(), default=None,
              help="Path to llm-gen-ratchet-baselines.v1.json (default: jseval/llm-gen-ratchet-baselines.v1.json).")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--update-baseline", is_flag=True,
              help="Re-pin the floor from this (green) bench via project_bench_to_llm_baselines.")
@click.pass_context
def cmd_llm_gate(ctx, bench_file, baselines, report_out, update_baseline):
    """LLM-generation-latency ratchet (tempdoc 640 L) — fail on a TTFT / e2e regression.

    The inference-runtime sibling of ``perf-gate``. Reads an ``llm-bench.json`` (``statistics.<metric>.median``)
    and compares TTFT + end-to-end summarization p50 against the RELATIVE floor in
    llm-gen-ratchet-baselines.v1.json. Per machine/config, not per corpus (the bench is corpus-agnostic).
    Exit 0 = no regression (or un-pinned), 1 = regression, 2 = a pinned metric missing. (tokens/sec is
    deferred — the chat SSE emits no token usage.)
    """
    from .. import llm_gate as _lg
    from .. import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[2] / "llm-gen-ratchet-baselines.v1.json"
    baselines_doc = json.loads(Path(baselines).read_text(encoding="utf-8"))
    bench_doc = json.loads(Path(bench_file).read_text(encoding="utf-8"))

    if update_baseline:
        from ..manifest import _git_sha_full
        proj = _lg.project_bench_to_llm_baselines(bench_doc, src=(_git_sha_full() or "")[:10])
        baselines_doc = {**baselines_doc, **proj}
        Path(baselines).write_text(
            json.dumps(baselines_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        click.echo(json.dumps({"updated": "llm-gen", "metrics": proj["metrics"],
                               "baselines_path": str(baselines)}, indent=2), err=True)
        sys.exit(0)

    report = _lg.evaluate(baselines_doc, bench_doc)
    _rk.finalize_report(report, run_dir=Path(bench_file), baselines_path=baselines,
                        report_out=report_out, summary_fields=())


@click.command("leak-gate-derive")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (each dataset's latest run projection is read).")
@click.option("--datasets", required=True, help="Comma-separated dataset slugs to pin (e.g. beir/scifact,mixed/enron-qa).")
@click.option("--out", type=click.Path(), default=None,
              help="Write the baselines JSON here (default: jseval/leak-gate-baselines.v1.json).")
@click.option("--tolerance", type=float, default=None,
              help="Default tolerance_abs added on top of the measured ceiling (else leak_gate.DEFAULT_TOLERANCE_ABS).")
@click.pass_context
def cmd_leak_gate_derive(ctx, data_dir, datasets, out, tolerance):
    """Derive leak-gate ceilings from each dataset's latest run projection (measured, not hand-typed).

    The recall-survival analogue of the relevance ratchet's release-projection (tempdoc 623 anti-fork):
    a corpus's pinned ``leak_rate_max`` is its *measured* leak rate in the latest multi-mode run, so there
    is no table of numbers to drift. ``evaluate`` adds ``tolerance_abs`` on top.
    """
    from .. import leak_gate as _lgate
    from .. import release as _release

    eval_results = Path(data_dir) / "eval-results"
    raw_slugs = [s.strip() for s in datasets.split(",") if s.strip()]
    projections: dict = {}
    for raw_slug in raw_slugs:
        # Directory lookup MUST use the raw, operator-typed slug: run directories are named from
        # `jseval run --dataset`'s literal argument (e.g. bare "scifact"), never canonicalized —
        # confirmed via artifacts.py's `dataset_slug = summary["dataset"].replace("/", "_")`.
        suffix = "_" + raw_slug.replace("/", "_")
        cands = sorted(
            (p for p in eval_results.iterdir()
             if p.is_dir() and p.name.endswith(suffix)
             and (p / "projections" / "staged_recall_accounting.json").is_file()),
            key=lambda p: p.name, reverse=True)
        if not cands:
            click.echo(f"WARN: no run with a staged_recall_accounting projection for {raw_slug}", err=True)
            continue
        # tempdoc 664 (twelfth pass): canonicalize only the OUTPUT key (e.g. "scifact" ->
        # "beir/scifact") to match relevance-gate/perf-gate's convention — leak-gate-baselines.v1.json
        # had drifted to a bare-name key for scifact specifically because this command never
        # canonicalized its output, forcing `jseval datasets`' coverage check to work around the
        # inconsistency instead of it being fixed at the source.
        slug = _release.canonical_dataset_slug(raw_slug)
        projections[slug] = json.loads(
            (cands[0] / "projections" / "staged_recall_accounting.json").read_text(encoding="utf-8"))

    kwargs = {} if tolerance is None else {"tolerance_default_abs": tolerance}
    derived = _lgate.derive_baselines(projections, **kwargs)
    out_path = Path(out) if out else (Path(__file__).resolve().parents[2] / "leak-gate-baselines.v1.json")

    # tempdoc 664: a higher leak_rate_max ceiling is a relaxation (leak_rate is
    # lower-is-better/ceiling-comparator) — refuse to derive over a prior pin without a
    # classified, justified changeset.
    from .. import baseline_shift as _bshift
    old_doc = json.loads(out_path.read_text(encoding="utf-8")) if out_path.is_file() else {}
    # tempdoc 683: with the pointer pattern the previously pinned values live in
    # `fallback_baselines`; inline `baselines` (pre-migration files) still wins if present.
    old_baselines = {**(old_doc.get("fallback_baselines") or {}), **(old_doc.get("baselines") or {})}
    changesets_dir = out_path.resolve().parent / ".changesets"
    try:
        for slug, entry in derived.get("baselines", {}).items():
            old_ceiling = (old_baselines.get(slug) or {}).get("leak_rate_max")
            new_ceiling = entry.get("leak_rate_max")
            if not isinstance(old_ceiling, (int, float)) or not isinstance(new_ceiling, (int, float)):
                continue
            _bshift.assert_baseline_not_relaxed(
                old_ceiling, new_ceiling, lower_is_better=True,
                gate="leak-gate", dataset=slug, changesets_dir=changesets_dir,
            )
    except _bshift.BaselineRelaxedWithoutJustificationError as e:
        click.echo(json.dumps({"exit_code": 1, "error": str(e)}, indent=2), err=True)
        sys.exit(1)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    # tempdoc 683: when the existing file is a pointer file (`current_release`), the derived
    # measured values are its FALLBACK layer — preserve the pointer + top-level metadata so a
    # derive never silently strips the release projection.
    if old_doc.get("current_release"):
        merged_doc = {**old_doc,
                      "tolerance_default_abs": derived["tolerance_default_abs"],
                      "fallback_baselines": derived["baselines"]}
        merged_doc.pop("baselines", None)
    else:
        merged_doc = derived
    out_path.write_text(
        json.dumps(merged_doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(
        {"out": str(out_path), "pinned": derived["baselines"]}, indent=2), err=True)


@click.command("union-recall-gate-derive")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (each dataset's latest run projection is read).")
@click.option("--datasets", required=True, help="Comma-separated dataset slugs to pin (e.g. beir/scifact,mixed/enron-qa).")
@click.option("--out", type=click.Path(), default=None,
              help="Write the baselines JSON here (default: jseval/union-recall-gate-baselines.v1.json).")
@click.option("--tolerance", type=float, default=None,
              help="Default tolerance_abs subtracted from the measured floor (else "
                   "union_recall_gate.DEFAULT_TOLERANCE_ABS).")
@click.pass_context
def cmd_union_recall_gate_derive(ctx, data_dir, datasets, out, tolerance):
    """Derive union-recall-gate floors from each dataset's latest run projection (measured, not
    hand-typed).

    The completeness analogue of ``leak-gate-derive`` (tempdoc 623 anti-fork): a corpus's pinned
    ``leg_union_recall_min`` is its *measured* union-recall in the latest multi-mode run, so there
    is no table of numbers to drift. ``evaluate`` subtracts ``tolerance_abs`` from it.
    """
    from .. import union_recall_gate as _urgate
    from .. import release as _release

    eval_results = Path(data_dir) / "eval-results"
    raw_slugs = [s.strip() for s in datasets.split(",") if s.strip()]
    projections: dict = {}
    for raw_slug in raw_slugs:
        # Directory lookup MUST use the raw, operator-typed slug: run directories are named from
        # `jseval run --dataset`'s literal argument (e.g. bare "scifact"), never canonicalized —
        # confirmed via artifacts.py's `dataset_slug = summary["dataset"].replace("/", "_")`.
        suffix = "_" + raw_slug.replace("/", "_")
        cands = sorted(
            (p for p in eval_results.iterdir()
             if p.is_dir() and p.name.endswith(suffix)
             and (p / "projections" / "staged_recall_accounting.json").is_file()),
            key=lambda p: p.name, reverse=True)
        if not cands:
            click.echo(f"WARN: no run with a staged_recall_accounting projection for {raw_slug}", err=True)
            continue
        # Canonicalize only the OUTPUT key (e.g. "scifact" -> "beir/scifact") to match
        # relevance-gate/perf-gate/leak-gate's convention (tempdoc 664 twelfth pass lesson).
        slug = _release.canonical_dataset_slug(raw_slug)
        projections[slug] = json.loads(
            (cands[0] / "projections" / "staged_recall_accounting.json").read_text(encoding="utf-8"))

    kwargs = {} if tolerance is None else {"tolerance_default_abs": tolerance}
    derived = _urgate.derive_baselines(projections, **kwargs)
    out_path = Path(out) if out else (
        Path(__file__).resolve().parents[2] / "union-recall-gate-baselines.v1.json")

    # A lower leg_union_recall_min floor is a relaxation (higher-is-better metric) — refuse to
    # derive over a prior pin without a classified, justified changeset (mirrors leak-gate-derive).
    from .. import baseline_shift as _bshift
    old_doc = json.loads(out_path.read_text(encoding="utf-8")) if out_path.is_file() else {}
    # Mirrors leak-gate-derive's tempdoc 683 pointer handling: previously pinned values live in
    # `fallback_baselines`; inline `baselines` (pre-migration files) still wins if present.
    old_baselines = {**(old_doc.get("fallback_baselines") or {}), **(old_doc.get("baselines") or {})}
    changesets_dir = out_path.resolve().parent / ".changesets"
    try:
        for slug, entry in derived.get("baselines", {}).items():
            old_floor = (old_baselines.get(slug) or {}).get("leg_union_recall_min")
            new_floor = entry.get("leg_union_recall_min")
            if not isinstance(old_floor, (int, float)) or not isinstance(new_floor, (int, float)):
                continue
            _bshift.assert_baseline_not_relaxed(
                old_floor, new_floor, lower_is_better=False,
                gate="union-recall-gate", dataset=slug, changesets_dir=changesets_dir,
            )
    except _bshift.BaselineRelaxedWithoutJustificationError as e:
        click.echo(json.dumps({"exit_code": 1, "error": str(e)}, indent=2), err=True)
        sys.exit(1)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    # When the existing file is a pointer file (`current_release`), the derived measured values
    # are its FALLBACK layer — preserve the pointer + top-level metadata so a derive never
    # silently strips the release projection.
    if old_doc.get("current_release"):
        merged_doc = {**old_doc,
                      "tolerance_default_abs": derived["tolerance_default_abs"],
                      "fallback_baselines": derived["baselines"]}
        merged_doc.pop("baselines", None)
    else:
        merged_doc = derived
    out_path.write_text(
        json.dumps(merged_doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(
        {"out": str(out_path), "pinned": derived["baselines"]}, indent=2), err=True)


@click.command("utility-gate")
@click.option("--record", required=True, type=click.Path(exists=True, resolve_path=True),
              help="A parsed utility-comparison.v1(-cross-corpus) JSON file (from `utility-compose`).")
@click.option("--corpus", required=True,
              help="Dataset slug to gate (e.g. golden/util-smoke).")
@click.option("--model", default=None,
              help="Agent model tier to check (default: the pinned baseline's agent_model, "
                   "else the record's sole model for this corpus if unambiguous).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to utility-ratchet-baselines.v1.json "
                   "(default: jseval/utility-ratchet-baselines.v1.json).")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--update-baseline", is_flag=True,
              help="Re-pin corpus/model's c_floor_min (+ cli_version) from this (green) record via "
                   "derive_baselines.")
@click.option("--allow-realistic-corpus", is_flag=True,
              help="Override the tempdoc-673-D8 admission refusal (the record's contamination_class "
                   "is not the fabricated/engineered smoke-corpus marker) — an explicit, deliberate "
                   "opt-in, mirroring leak-gate's --allow-engine-mismatch.")
@click.pass_context
def cmd_utility_gate(ctx, record, corpus, model, baselines, report_out, update_baseline,
                      allow_realistic_corpus):
    """Agent-utility standing regression detection gate (tempdoc 673).

    Reads a composed utility-comparison.v1 RECORD directly (not a projections/ artifact — the utility
    pipeline doesn't emit one) and compares CORPUS's condition-C (JustSearch-only) absolute accuracy
    against the pinned floor in utility-ratchet-baselines.v1.json. Detection, not estimation — gates
    on a high-contrast smoke corpus where the C-floor is low-variance, not the near-null realistic
    with-tool-vs-baseline delta (see tempdoc 673 for why). Exit 0/PASS = no regression (or un-pinned
    corpus), 1/FAIL = a genuine regression, 2/INCONCLUSIVE = a data problem OR a floor drop that
    coincides with the agent's cli_version having changed since the baseline was pinned (D9).
    """
    from .. import utility_gate as _ugate

    if baselines is None:
        baselines = Path(__file__).resolve().parents[2] / "utility-ratchet-baselines.v1.json"
    record_doc = json.loads(Path(record).read_text(encoding="utf-8"))

    if update_baseline:
        schema_error = _ugate.check_schema(record_doc)
        if schema_error is not None:
            click.echo(json.dumps({"exit_code": 2, "error": schema_error}, indent=2), err=True)
            sys.exit(2)
        if not allow_realistic_corpus:
            admission_error = _ugate.check_admission(record_doc)
            if admission_error is not None:
                click.echo(json.dumps({"exit_code": 2, "error": admission_error}, indent=2), err=True)
                sys.exit(2)
        baselines_doc = (
            json.loads(Path(baselines).read_text(encoding="utf-8")) if Path(baselines).is_file() else {}
        )
        proj = _ugate.derive_baselines({"record": record_doc}, allow_realistic_corpus=allow_realistic_corpus)
        # tempdoc 683: with the pointer pattern the previously pinned floor lives in
        # `fallback_baselines`; inline `baselines` (pre-migration files) still wins if present.
        _old_pins = {**(baselines_doc.get("fallback_baselines") or {}),
                     **(baselines_doc.get("baselines") or {})}
        old_floor = (_old_pins.get(corpus) or {}).get("c_floor_min")
        new_floor = (proj.get("baselines") or {}).get(corpus, {}).get("c_floor_min")
        if new_floor is None:
            click.echo(json.dumps(
                {"exit_code": 2, "error": f"record has no resolvable condition-C accuracy for {corpus}"},
                indent=2), err=True)
            sys.exit(2)
        from .. import baseline_shift as _bshift
        changesets_dir = Path(baselines).resolve().parent / ".changesets"
        try:
            # c_floor_min is HIGHER-is-better (unlike leak's ceiling) -> lower_is_better=False: a
            # DROP in the pinned floor is the relaxation to refuse without a justified changeset.
            _bshift.assert_baseline_not_relaxed(
                old_floor, new_floor, lower_is_better=False,
                gate="utility-gate", dataset=corpus, changesets_dir=changesets_dir,
            )
        except _bshift.BaselineRelaxedWithoutJustificationError as e:
            click.echo(json.dumps({"exit_code": 1, "error": str(e)}, indent=2), err=True)
            sys.exit(1)
        # Scoped to exactly `corpus` — `proj` can carry OTHER corpora too (a single record can
        # legitimately measure multiple corpora), but only `corpus` has passed the relaxation guard
        # above; merging the whole `proj["baselines"]` would silently re-pin every other corpus in
        # the record with NO justification check at all (post-implementation review finding #1).
        # tempdoc 683: on a pointer file the pin lands in `fallback_baselines` (the release
        # projection, when a release carries a `utility` section, still wins at load time).
        target_key = "fallback_baselines" if baselines_doc.get("current_release") else "baselines"
        merged_baselines = dict(baselines_doc.get(target_key) or {})
        merged_baselines[corpus] = proj["baselines"][corpus]
        # Preserve the file's own top-level metadata (note/tempdoc/tolerance_default_abs) on an
        # existing file — only seed it from `proj`'s defaults the first time the file is created.
        top_level = baselines_doc if baselines_doc else {k: v for k, v in proj.items() if k != "baselines"}
        baselines_doc = {**top_level, target_key: merged_baselines}
        Path(baselines).write_text(
            json.dumps(baselines_doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
            encoding="utf-8")
        click.echo(json.dumps(
            {"updated": corpus, "c_floor_min": new_floor, "baselines_path": str(baselines)},
            indent=2), err=True)
        sys.exit(0)

    # Tempdoc 683: same `current_release` pointer pattern as relevance/perf/leak — floors project
    # from the release's optional `utility` section; `fallback_baselines` governs otherwise.
    from .. import ratchet_kernel as _rk
    baselines_doc = _rk.load_baselines_doc(
        baselines,
        project_release=lambda rel, base: _ugate.project_release_to_baselines(
            rel,
            tolerance_default_abs=base.get("tolerance_default_abs", _ugate.DEFAULT_TOLERANCE_ABS),
            per_corpus_tolerance=base.get("per_corpus_tolerance"),
        ),
    )
    report = _ugate.evaluate(baselines_doc, record_doc, corpus, model)
    _rk.finalize_report(
        report, run_dir=Path(record), baselines_path=baselines,
        report_out=report_out, summary_fields=("current", "baseline", "floor", "verdict"),
    )


@click.command("utility-gate-derive")
@click.option("--records", required=True,
              help="Comma-separated utility-comparison.v1 JSON file paths to derive from "
                   "(NOT the -cross-corpus.v1 schema, and NOT a realistic corpus — both are skipped "
                   "with a WARN unless --allow-realistic-corpus).")
@click.option("--out", type=click.Path(), default=None,
              help="Write the baselines JSON here (default: jseval/utility-ratchet-baselines.v1.json).")
@click.option("--tolerance", type=float, default=None,
              help="Default tolerance_abs subtracted from each measured floor "
                   "(else utility_gate.DEFAULT_TOLERANCE_ABS).")
@click.option("--allow-realistic-corpus", is_flag=True,
              help="Override the tempdoc-673-D8 admission refusal for every input file — an explicit, "
                   "deliberate opt-in, mirroring leak-gate's --allow-engine-mismatch.")
@click.pass_context
def cmd_utility_gate_derive(ctx, records, out, tolerance, allow_realistic_corpus):
    """Derive utility-gate condition-C floors from one or more measured utility-comparison.v1 records
    (measured, not hand-typed — the utility-gate analogue of leak-gate-derive)."""
    from .. import utility_gate as _ugate

    paths = [p.strip() for p in records.split(",") if p.strip()]
    record_docs = {}
    for p in paths:
        doc = json.loads(Path(p).read_text(encoding="utf-8"))
        schema_error = _ugate.check_schema(doc)
        if schema_error is not None:
            click.echo(f"WARN: skipping {p}: {schema_error}", err=True)
            continue
        if not allow_realistic_corpus:
            admission_error = _ugate.check_admission(doc)
            if admission_error is not None:
                click.echo(f"WARN: skipping {p}: {admission_error}", err=True)
                continue
        record_docs[p] = doc
    kwargs = {} if tolerance is None else {"tolerance_default_abs": tolerance}
    derived = _ugate.derive_baselines(record_docs, allow_realistic_corpus=allow_realistic_corpus, **kwargs)
    out_path = Path(out) if out else (Path(__file__).resolve().parents[2] / "utility-ratchet-baselines.v1.json")

    from .. import baseline_shift as _bshift
    old_doc = json.loads(out_path.read_text(encoding="utf-8")) if out_path.is_file() else {}
    # tempdoc 683: with the pointer pattern the previously pinned floors live in
    # `fallback_baselines`; inline `baselines` (pre-migration files) still wins if present.
    old_baselines = {**(old_doc.get("fallback_baselines") or {}), **(old_doc.get("baselines") or {})}
    changesets_dir = out_path.resolve().parent / ".changesets"
    try:
        for corpus, entry in derived.get("baselines", {}).items():
            old_floor = (old_baselines.get(corpus) or {}).get("c_floor_min")
            new_floor = entry.get("c_floor_min")
            if not isinstance(new_floor, (int, float)):
                continue
            _bshift.assert_baseline_not_relaxed(
                old_floor, new_floor, lower_is_better=False,
                gate="utility-gate", dataset=corpus, changesets_dir=changesets_dir,
            )
    except _bshift.BaselineRelaxedWithoutJustificationError as e:
        click.echo(json.dumps({"exit_code": 1, "error": str(e)}, indent=2), err=True)
        sys.exit(1)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    # tempdoc 683: preserve a pointer file's `current_release` + metadata — the derived measured
    # values are its FALLBACK layer (mirrors leak-gate-derive).
    if old_doc.get("current_release"):
        merged_doc = {**old_doc,
                      "tolerance_default_abs": derived["tolerance_default_abs"],
                      "fallback_baselines": derived["baselines"]}
        merged_doc.pop("baselines", None)
    else:
        merged_doc = derived
    out_path.write_text(
        json.dumps(merged_doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(
        {"out": str(out_path), "pinned": derived["baselines"]}, indent=2), err=True)


@click.command("changeset-new")
@click.option("--gate", required=True,
              type=click.Choice(["perf-gate", "leak-gate", "utility-gate", "release", "*"]),
              help="Which gate's relaxation this justifies.")
@click.option("--dataset", required=True,
              help="\"<dataset>:<metric>\" for perf-gate/release, \"<dataset>\" for leak-gate, or "
                   "\"*\" (any dataset/metric for this gate).")
@click.option("--tempdoc", required=True, help="The tempdoc/decision number that accepted this regression.")
@click.option("--reason", default=None, help="Free-form justification body. Omit to write a placeholder.")
@click.option("--changesets-dir", type=click.Path(), default=None,
              help="Default: jseval/.changesets/ (sibling to the baseline files).")
@click.pass_context
def cmd_changeset_new(ctx, gate, dataset, tempdoc, reason, changesets_dir):
    """Scaffold a `.changesets/*.md` baseline-relaxation-justification file (tempdoc 664).

    Writes the exact frontmatter shape `.changesets/README.md` documents, so authoring a
    justification doesn't require hand-typing YAML — lowering the friction of using the
    baseline-relaxation-justification mechanism correctly. Refuses to overwrite an existing file at
    the same path; the file itself is what `baseline_shift.load_changesets` already parses, so no
    new parser is introduced here.
    """
    dest_dir = Path(changesets_dir) if changesets_dir else (Path(__file__).resolve().parents[2] / ".changesets")
    dest_dir.mkdir(parents=True, exist_ok=True)

    slug = f"{gate}-{dataset}-tempdoc{tempdoc}".replace("/", "_").replace(":", "_").replace("*", "any")
    dest_path = dest_dir / f"{slug}.md"
    if dest_path.exists():
        click.echo(f"Error: {dest_path} already exists — refusing to overwrite.", err=True)
        sys.exit(1)

    body = reason or "TODO: explain why this relaxation was accepted."
    content = (
        "---\n"
        f'classification: "baseline-relaxation"\n'
        f'gate: "{gate}"\n'
        f'dataset: "{dataset}"\n'
        f'tempdoc: "{tempdoc}"\n'
        "---\n\n"
        f"{body}\n"
    )
    dest_path.write_text(content, encoding="utf-8")
    click.echo(f"Wrote {dest_path}")


@click.command("recall-profile")
@click.option("--data-dir", default=lambda: str(DEFAULT_JSEVAL_DATA_DIR),
              show_default="scripts/jseval/tmp", type=click.Path(resolve_path=True),
              help="Data dir containing eval-results/ (the latest run per dataset is re-produced).")
@click.option("--datasets", required=True, help="Comma-separated dataset slugs (e.g. scifact,mixed/enron-qa).")
@click.option("--report-out", type=click.Path(), default=None, help="Write the full profile JSON here.")
@click.pass_context
def cmd_recall_profile(ctx, data_dir, datasets, report_out):
    """Cross-corpus recall-attribution profile (tempdoc 636) — a *profile, not a number*.

    Re-``produce``s the ``staged_recall_accounting`` projection (pure, no backend) for each
    dataset's latest run and aggregates into a regime-blind failure-attribution profile
    (which recall-funnel bucket dominates across the eval set) + a candidate next-lever
    recommendation. Read-only over existing run dirs.
    """
    from .. import recall_profile as _rp
    from ..projections import staged_recall_accounting as _sra

    eval_results = Path(data_dir) / "eval-results"
    slugs = [s.strip() for s in datasets.split(",") if s.strip()]
    projections: dict = {}
    for slug in slugs:
        suffix = "_" + slug.replace("/", "_")
        # Require a multi-mode run (≥1 leg-mode per_query) + qrels — a hybrid-only run can't
        # produce the staged-recall decomposition, so "latest by name" alone picks wrong.
        cands = sorted(
            (p for p in eval_results.iterdir()
             if p.is_dir() and p.name.endswith(suffix) and (p / "qrels.json").is_file()
             and any((p / f"{m}_per_query.json").is_file() for m in _sra.LEG_MODES)),
            key=lambda p: p.name, reverse=True)
        if not cands:
            click.echo(f"WARN: no multi-mode run dir (leg modes + qrels) for {slug}", err=True)
            continue
        projections[slug] = _sra.produce(cands[0])  # re-produce with current code (fp_mapping + fixes)

    git_sha = (ctx.obj or {}).get("git_sha") if ctx.obj else None
    profile = _rp.build_recall_profile(projections, engine_git_sha=git_sha)
    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(
            json.dumps(profile, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(profile, indent=2))


@click.command("ce-replay")
@click.option("--run-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Eval run dir (with leg + final per_query.json, qrels.json, projections/).")
@click.option("--mode", default=None, help="Final mode to read judgeSignals from (default: hybrid/full).")
@click.option("--report-out", type=click.Path(), default=None, help="Write the full report JSON here.")
@click.pass_context
def cmd_ce_replay(ctx, run_dir, mode, report_out):
    """AI-free realizable-headroom probe (tempdoc 643) — replays the ACTUAL production judge.

    Re-ranks a run's leg-union pool by the cross-encoder's own already-captured scores (no
    live model, no LLM, no new eval run) and reports how much of the AI-free
    judge_headroom_ceiling the judge that is actually shipped captures when given the full
    pool. Distinct from judge-ceiling, which asks whether a DIFFERENT (LLM) judge would do
    better — this asks whether the judge already in production would.
    """
    from .. import judge_ceiling as _jc

    rd = Path(run_dir)
    qrels = json.loads((rd / "qrels.json").read_text(encoding="utf-8")) if (rd / "qrels.json").is_file() else {}

    proj_path = rd / "projections" / "staged_recall_accounting.json"
    if proj_path.is_file():
        proj = json.loads(proj_path.read_text(encoding="utf-8"))
    else:
        from ..projections import staged_recall_accounting as _sra
        proj = _sra.produce(rd)
    agg = proj.get("aggregate") or {}
    ceiling = agg.get("judge_headroom_ceiling")
    final_ndcg = agg.get("final_ndcg")

    pool = _jc.assemble_pool(rd)
    ce_scores_by_qid = _jc.load_ce_scores(rd, mode=mode)
    report = _jc.ce_replay_report(pool, ce_scores_by_qid, qrels, final_ndcg=final_ndcg, ceiling=ceiling)
    report["run_dir"] = str(rd)

    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
                                    encoding="utf-8")
    click.echo(json.dumps(report, indent=2))


@click.command("judge-ceiling")
@click.option("--run-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Eval run dir (with leg + final per_query.json, qrels.json, projections/).")
@click.option("--corpus-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Corpus dir with docs.jsonl (for candidate text). Optional (degrades to text-light).")
@click.option("--llm-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True,
              help="Judge base URL (OpenAI-compatible /v1/chat/completions): the Engine's /v1 "
                   "proxy, which holds a generation lease per call.")
@click.option("--engine-generator", default=None,
              help="Engine's generator model name; warns if the judge model matches it (self-preference bias).")
@click.option("--report-out", type=click.Path(), default=None, help="Write the full report JSON here.")
@click.pass_context
def cmd_judge_ceiling(ctx, run_dir, corpus_dir, llm_url, engine_generator, report_out):
    """LLM judge-ceiling probe (tempdoc 636 §5) — realistic judge headroom.

    Reranks a run's leg-union pool with the local LLM and compares nDCG@10 to the
    final, reporting how much of the AI-free ``judge_headroom_ceiling`` a real model
    captures (with an order-swap position-sensitivity band). Degrades to
    ``AI_UNAVAILABLE`` (exit 0) when the model is unreachable — the projection's
    AI-free ceiling already stands.
    """
    from .. import judge_ceiling as _jc

    rd = Path(run_dir)
    qrels = json.loads((rd / "qrels.json").read_text(encoding="utf-8")) if (rd / "qrels.json").is_file() else {}

    # queries + ceiling/final_ndcg from the projection (or recompute).
    proj_path = rd / "projections" / "staged_recall_accounting.json"
    if proj_path.is_file():
        proj = json.loads(proj_path.read_text(encoding="utf-8"))
    else:
        from ..projections import staged_recall_accounting as _sra
        proj = _sra.produce(rd)
    agg = proj.get("aggregate") or {}
    ceiling = agg.get("judge_headroom_ceiling")
    final_ndcg = agg.get("final_ndcg")
    final_mode = proj.get("final_mode") or "hybrid"

    queries: dict = {}
    fpq = rd / f"{final_mode}_per_query.json"
    if fpq.is_file():
        for e in json.loads(fpq.read_text(encoding="utf-8")):
            if e.get("qid"):
                queries[e["qid"]] = e.get("query") or ""

    pool = _jc.assemble_pool(rd)
    texts = _jc.load_doc_texts(Path(corpus_dir)) if corpus_dir else {}
    rank_fn = _jc.make_chat_rank_fn(llm_url)

    # Self-preference guardrail (external-research Finding 2): a judge favours its own
    # generations, so the judge model should differ from the engine's generator. Advisory
    # only — never blocks; the order-swap band + coarse-read remain the load-bearing guards.
    if engine_generator:
        judge_model = _jc.served_model_name(llm_url)
        if judge_model and judge_model == engine_generator:
            click.echo(
                f"WARN: judge model '{judge_model}' == engine generator — self-preference "
                f"bias inflates the ceiling; use a distinct judge model (636 §5 / Finding 2).",
                err=True)

    try:
        report = _jc.judge_ceiling_report(
            pool, queries, qrels, rank_fn, final_ndcg=final_ndcg, ceiling=ceiling, texts=texts)
    except _jc.AIUnavailable as e:
        report = {"status": "AI_UNAVAILABLE", "reason": str(e),
                  "judge_headroom_ceiling": ceiling, "final_ndcg": final_ndcg,
                  "note": "projection's AI-free judge_headroom_ceiling stands"}

    report["run_dir"] = str(rd)
    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
                                    encoding="utf-8")
    click.echo(json.dumps(report, indent=2), err=True)


@click.command("judge-arbitration-report")
@click.option("--run-dir-a", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Eval run dir with {mode}_per_query.json (judgeSignals) — arbitration-on run.")
@click.option("--run-dir-b", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Second run dir for regression_rate comparison (e.g. the arbitration-off run). Optional.")
@click.option("--mode", default="hybrid", show_default=True, help="Retrieval mode to read.")
@click.option("--base-alpha", type=float, default=0.5, show_default=True)
@click.option("--fusion-protect-alpha", type=float, default=0.85, show_default=True)
@click.option("--report-out", type=click.Path(), default=None, help="Write the full report JSON here.")
@click.pass_context
def cmd_judge_arbitration_report(ctx, run_dir_a, run_dir_b, mode, base_alpha, fusion_protect_alpha, report_out):
    """Judge-arbitration decision instrument (tempdoc 643 E3).

    Re-derives computeJudgeArbitrationAlpha's branch split and isFusionDecisiveForSkip's firing
    rate from a run's already-archived judgeSignals — no new eval run needed. With --run-dir-b,
    also reports how many queries' true (predictedDocIds) result order changed between the two
    runs. Promotes the one-off scripts written for 643's §9-4/theorization passes into durable,
    tested tooling.
    """
    from .. import judge_arbitration_report as _jar

    rd_a = Path(run_dir_a)
    records_a = json.loads((rd_a / f"{mode}_per_query.json").read_text(encoding="utf-8"))

    report = {
        "run_dir_a": str(rd_a),
        "mode": mode,
        "alpha_branch_breakdown": _jar.alpha_branch_breakdown(
            records_a, base_alpha=base_alpha, fusion_protect_alpha=fusion_protect_alpha),
        "perf_skip_firing_rate": _jar.perf_skip_firing_rate(records_a),
    }
    if run_dir_b:
        report["regression_rate"] = _jar.regression_rate(rd_a, Path(run_dir_b), mode=mode)

    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
                                    encoding="utf-8")
    click.echo(json.dumps(report, indent=2))


@click.command("extraction-gate")
@click.option("--baseline-run", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) from the BASELINE extraction profile (e.g. qwen-vl).")
@click.option("--candidate-run", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) from the CANDIDATE extraction profile (e.g. paddle-ocr-vl).")
@click.option("--mode", required=True,
              help="Primary retrieval mode whose nDCG@10 decides the ship verdict (e.g. hybrid).")
@click.option("--guard-mode", "guard_modes", multiple=True,
              help="Extra mode that must merely not regress (repeatable).")
@click.option("--min-improvement", type=float, default=0.005, show_default=True,
              help="Minimum nDCG@10 gain on the primary mode to justify the swap.")
@click.option("--regression-tol", type=float, default=0.005, show_default=True,
              help="A drop larger than this on any checked mode is a rejection.")
@click.option("--ocr-baseline", type=float, default=None,
              help="INFORMATIONAL: baseline extract-eval word-overlap (does not decide the verdict).")
@click.option("--ocr-candidate", type=float, default=None,
              help="INFORMATIONAL: candidate extract-eval word-overlap (does not decide the verdict).")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_extraction_gate(ctx, baseline_run, candidate_run, mode, guard_modes,
                        min_improvement, regression_tol, ocr_baseline, ocr_candidate,
                        report_out):
    """Retrieval-aware extraction gate (tempdoc 580 Track D / F-009).

    Compares two eval runs over the SAME extraction-exercising corpus — one indexed
    with the baseline VLM profile, one with the candidate (PaddleOCR-VL) profile — and
    decides the swap on DOWNSTREAM nDCG@10, NOT the OCR word-overlap (§14.4 /
    InduOCRBench: OCR accuracy is a poor proxy for retrieval). If --ocr-baseline/
    --ocr-candidate are supplied, the gate also flags when the OCR signal DISAGREES
    with nDCG (the InduOCRBench trap) — but the verdict always follows nDCG.

    Exit 0 = ship (clear downstream win), 1 = reject (regression), 2 = metric missing,
    3 = neutral-hold (no clear win — don't pay the swap cost).
    """
    from .. import extraction_gate as _xgate

    base_summary = json.loads((Path(baseline_run) / "summary.json").read_text(encoding="utf-8"))
    cand_summary = json.loads((Path(candidate_run) / "summary.json").read_text(encoding="utf-8"))

    ocr_overlap = None
    if ocr_baseline is not None and ocr_candidate is not None:
        ocr_overlap = {"baseline": ocr_baseline, "candidate": ocr_candidate}

    report = _xgate.evaluate(
        base_summary,
        cand_summary,
        mode,
        guard_modes=list(guard_modes),
        min_improvement_abs=min_improvement,
        regression_tol_abs=regression_tol,
        ocr_overlap=ocr_overlap,
    )
    report["baseline_run"] = str(baseline_run)
    report["candidate_run"] = str(candidate_run)

    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
            encoding="utf-8")

    click.echo(json.dumps({
        "exit_code": report["exit_code"],
        "verdict": report.get("verdict"),
        "mode": mode,
        "baseline_ndcg": report.get("baseline_ndcg"),
        "candidate_ndcg": report.get("candidate_ndcg"),
        "delta": report.get("delta"),
        "checks": {c["name"]: c["status"] for c in report["checks"]},
    }, indent=2), err=True)
    sys.exit(report["exit_code"])


COMMANDS = [cmd_gate, cmd_relevance_gate, cmd_perf_gate, cmd_leak_gate, cmd_union_recall_gate, cmd_llm_gate, cmd_leak_gate_derive, cmd_union_recall_gate_derive, cmd_utility_gate, cmd_utility_gate_derive, cmd_changeset_new, cmd_recall_profile, cmd_ce_replay, cmd_judge_ceiling, cmd_judge_arbitration_report, cmd_extraction_gate]
