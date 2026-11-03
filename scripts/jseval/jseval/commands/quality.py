"""jseval quality: offline search-quality checks over stored RunRecords (S27).

One command per action, a one-line outcome on stdout, exit codes PASS 0 / REGRESSION 1 /
INVALID or INCONCLUSIVE 2 — an unexpected error or an interrupt is INVALID too, never the
REGRESSION code an unhandled Python error would exit with. ``COLLECTION`` is a config alias
(built from its official source, see ``jseval/quality/config/``) or a stored collection
directory. check/baseline use the refined Search request on prepared indexes; ablate is explicit.
"""
from __future__ import annotations

import functools
import json
import subprocess
from pathlib import Path

import click

from ..quality import collections as qcoll
from ..quality import compare as qcmp
from ..quality import known_answers, report, scoring
from ..quality.types import Outcome, RunRecordError, atomic_write_text


def _invalid(ctx, message: str) -> None:
    click.echo("INVALID " + report.compact(message))
    ctx.exit(Outcome.INVALID.exit_code)


def _never_crash(command):
    """Any unexpected error (or Ctrl-C) prints a one-line INVALID and exits 2."""

    @functools.wraps(command)
    def run(*args, **kwargs):
        try:
            return command(*args, **kwargs)
        except (click.exceptions.Exit, click.ClickException):
            raise
        except KeyboardInterrupt:
            _invalid(click.get_current_context(), "interrupted before an outcome was complete")
        except Exception as e:
            _invalid(click.get_current_context(), f"unexpected error: {type(e).__name__}: {e}")

    return run


def _checked_collection(ctx, ref: str):
    """Resolve a collection and run its known-answer checks; any failure stops with exit 2."""
    try:
        c, cfg = qcoll.resolve_collection(ref)
        ka = known_answers.check(c, cfg)
    except (qcoll.CollectionError, known_answers.KnownAnswerFailure) as e:
        _invalid(ctx, str(e))
    except Exception as e:  # ir_datasets missing files, unreadable source: never a pass
        _invalid(ctx, f"{ref}: cannot load collection: {type(e).__name__}: {e}")
    return c, cfg, ka


@click.group("quality")
def quality_group() -> None:
    """Search quality: prepared real-engine checks, baselines and comparisons."""


@quality_group.command("known-answers")
@click.argument("collection")
@click.pass_context
@_never_crash
def cmd_known_answers(ctx, collection: str) -> None:
    """Run the known-answer checks of COLLECTION (counts, constructed rankings)."""
    c, _cfg, ka = _checked_collection(ctx, collection)
    counts = ka.counts
    click.echo(f"PASS {c.name} known answers: {ka.checks} checks; {counts['queries']} queries, "
               f"{counts['judgments']} judgments ({counts['positives']} positive), "
               f"{counts['documents']} documents; version {c.version[:16]}")


@quality_group.command("score")
@click.argument("collection")
@click.argument("run", type=click.Path(dir_okay=False, path_type=Path))
@click.option("--store-baseline", type=click.Path(dir_okay=False, path_type=Path),
              help="Also store this run as a baseline (run digest + per-query metrics).")
@click.pass_context
@_never_crash
def cmd_score(ctx, collection: str, run: Path, store_baseline: Path | None) -> None:
    """Score a stored RUN against COLLECTION after validating it completely."""
    c, _cfg, _ka = _checked_collection(ctx, collection)
    record, problems = qcmp.load_run_or_reason(run, "run")
    if record is not None:
        problems = qcmp.validate_run(c, record, {c.version}, "run")
    if problems:
        _invalid(ctx, f"{c.name}: " + "; ".join(problems))
    try:
        per_query = scoring.score_results(c.judgments, record.results)
    except scoring.ScoringError as e:
        _invalid(ctx, f"{c.name}: {e}")
    unj, slots, _pairs = scoring.unjudged_top(c.judgments, record.results)
    if store_baseline:
        qcmp.write_baseline(run, c, store_baseline)
    click.echo(report.score_line(c, scoring.aggregate(per_query), unj / max(1, slots),
                                 len(per_query)))


def _head_commit() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


@quality_group.command("compare")
@click.argument("collection")
@click.option("--baseline", "baseline_path", required=True,
              type=click.Path(dir_okay=False, path_type=Path), help="Stored baseline (.baseline.json).")
@click.option("--candidate", "candidate_path", required=True,
              type=click.Path(dir_okay=False, path_type=Path), help="Candidate RunRecord (.jsonl).")
@click.option("--intended", default="commit", show_default=True,
              help="Comma-separated identity fields the change is meant to differ in "
                   "(commit, index_key, index_build, request_config, models, budget[.key], "
                   "index_state[.key], served[.legs|.reranker|.expected]).")
@click.option("--expect-commit", default="HEAD", show_default=True,
              help="The exact candidate commit; a result from another commit is INVALID.")
@click.option("--report", "report_path", type=click.Path(dir_okay=False, path_type=Path),
              help="Write the readable report (Markdown) here.")
@click.pass_context
@_never_crash
def cmd_compare(ctx, collection: str, baseline_path: Path, candidate_path: Path, intended: str,
                expect_commit: str, report_path: Path | None) -> None:
    """Compare a CANDIDATE run with a stored BASELINE, question by question."""
    c, cfg, _ka = _checked_collection(ctx, collection)
    invalid: list[str] = []
    baseline = metrics = None
    try:
        baseline, metrics = qcmp.load_baseline(baseline_path)
    except (qcmp.BaselineError, RunRecordError) as e:
        invalid.append(str(e))
    candidate, problems = qcmp.load_run_or_reason(candidate_path, "candidate")
    invalid += problems
    if expect_commit == "HEAD":
        expect_commit = _head_commit()
        if expect_commit is None:
            invalid.append("cannot determine the candidate commit (git rev-parse HEAD failed)")
    result = qcmp.compare(c, cfg, baseline, candidate, baseline_metrics=metrics,
                          intended=[s.strip() for s in intended.split(",") if s.strip()],
                          expect_commit=expect_commit, invalid=invalid)
    if report_path:
        try:
            atomic_write_text(report_path, report.render(result))
        except OSError as e:  # the verdict without its report is an incomplete outcome
            _invalid(ctx, f"{c.name}: cannot write the report {report_path}: {e}")
    if ctx.find_root().obj and ctx.find_root().obj.get("json"):
        click.echo(json.dumps({**result.verdict.to_dict(), "unjudged": result.unjudged,
                               "judgment_requests": [dict(qid=qid, doc_id=doc, arm=arm)
                                   for qid, doc, arm in report.judgment_rows(result)]}, indent=1))
    else:
        click.echo(report.outcome_line(result))
    ctx.exit(result.verdict.exit_code)


COMMANDS = [quality_group]


def _run_options(command=None, *, full_suite=False):
    if command is None:
        return lambda cmd: _run_options(cmd, full_suite=full_suite)
    for option in reversed([
        click.argument('collections', nargs=-1),
        click.option('--baseline-dir', type=click.Path(file_okay=False, path_type=Path),
                     default=Path('scripts/jseval/runs/quality/baselines'), show_default=True),
        click.option('--runs-dir', type=click.Path(file_okay=False, path_type=Path),
                     default=Path('scripts/jseval/runs/quality'), show_default=True),
        click.option('--window-seconds', type=click.FloatRange(min=45, max=720 if full_suite else 600, min_open=True),
                     **({'required': True} if full_suite else {'default': 600}),
                     help='Exclusive window including startup and cleanup; estimate must fit.'),
        click.option('--models-dir', type=click.Path(file_okay=False, path_type=Path)),
        click.option('--documents-dir', type=click.Path(file_okay=False, path_type=Path)),
        click.option('--cache-root', type=click.Path(file_okay=False, path_type=Path),
                     default=Path.home()/'.cache/justsearch-quality/indexes'),
    ]):
        command = option(command)
    return command


def _execute_quality(ctx, action, **kwargs):
    from ..quality.run import execute, RunRefused
    from ..quality.index import IndexRefused
    refs = kwargs.pop('collections')
    try:
        outcome, line = execute(action, refs, **kwargs)
    except (RunRefused, IndexRefused, qcmp.BaselineError, qcoll.CollectionError,
            known_answers.KnownAnswerFailure) as e:
        _invalid(ctx, str(e))
    click.echo(line)
    ctx.exit(outcome.exit_code)


@quality_group.command('check')
@_run_options
@click.pass_context
@_never_crash
def cmd_check(ctx, **kwargs):
    """Run the refined Search request (default: SciFact, MIRACL de, Enron)."""
    _execute_quality(ctx, 'check', **kwargs)


@quality_group.command('full-suite')
@_run_options(full_suite=True)
@click.pass_context
@_never_crash
def cmd_full_suite(ctx, **kwargs):
    """Check the full suite, including MIRACL fr, in an explicitly set window."""
    _execute_quality(ctx, 'check', full_suite=True, **kwargs)


@quality_group.command('baseline')
@_run_options
@click.pass_context
@_never_crash
def cmd_baseline(ctx, **kwargs):
    """Run all COLLECTIONS and store their valid, complete baselines."""
    _execute_quality(ctx, 'baseline', **kwargs)


@quality_group.command('ablate')
@_run_options
@click.option('--mode', required=True, type=click.Choice(['text', 'vector', 'hybrid']),
              help='Explicit-mode sensitivity information, separate from the quick check.')
@click.pass_context
@_never_crash
def cmd_ablate(ctx, **kwargs):
    """Run a labelled explicit-mode ablation on COLLECTIONS."""
    _execute_quality(ctx, 'ablate', **kwargs)


@quality_group.command('prepare')
@click.argument('collection')
@click.option('--documents-dir', type=click.Path(file_okay=False, path_type=Path))
@click.option('--models-dir', type=click.Path(file_okay=False, path_type=Path))
@click.option('--cache-root', type=click.Path(file_okay=False, path_type=Path),
              default=Path.home()/'.cache/justsearch-quality/indexes')
@click.pass_context
@_never_crash
def cmd_prepare(ctx, collection, documents_dir, models_dir, cache_root):
    """Build/confirm an index through pausable background admission, outside scoring."""
    from ..quality.index import IndexConfig, prepare_index
    c, _cfg, _ka = _checked_collection(ctx, collection)
    cfg = IndexConfig(documents_dir=documents_dir, models_dir=models_dir, cache_root=cache_root)
    with prepare_index(c, cfg) as handle:
        handle.wait()
    click.echo(f'PASS {c.name} prepared index ready')
