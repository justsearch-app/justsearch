"""jseval quality: offline search-quality checks over stored RunRecords (S27).

One command per action, a one-line outcome on stdout, exit codes PASS 0 / REGRESSION 1 /
INVALID or INCONCLUSIVE 2. ``COLLECTION`` is a config alias (built from its official source, see
``jseval/quality/config/``) or a stored collection directory. Running a search is not done here.
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path

import click

from ..quality import collections as qcoll
from ..quality import compare as qcmp
from ..quality import known_answers, report, scoring
from ..quality.types import Outcome, RunRecord, RunRecordError


def _invalid(ctx, message: str) -> None:
    click.echo(f"INVALID {message}")
    ctx.exit(Outcome.INVALID.exit_code)


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
    """Offline search quality: known answers, scoring, old-vs-new comparison."""


@quality_group.command("known-answers")
@click.argument("collection")
@click.pass_context
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
    click.echo(report.score_line(c, scoring.aggregate(per_query), unj / max(1, slots),
                                 len(per_query)))
    if store_baseline:
        qcmp.write_baseline(run, c, store_baseline)


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
                   "(commit, index_key, request_config, models, index_state[.key]).")
@click.option("--expect-commit", default="HEAD", show_default=True,
              help="The exact candidate commit; a result from another commit is INVALID.")
@click.option("--report", "report_path", type=click.Path(dir_okay=False, path_type=Path),
              help="Write the readable report (Markdown) here.")
@click.pass_context
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
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report.render(result), encoding="utf-8")
    if ctx.find_root().obj and ctx.find_root().obj.get("json"):
        click.echo(json.dumps(result.verdict.to_dict(), indent=1))
    else:
        click.echo(report.outcome_line(result))
    ctx.exit(result.verdict.exit_code)


COMMANDS = [quality_group]
