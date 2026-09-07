"""Lane F workflow-fixture commands — capture + structural diff.

``python -m jseval workflow-fixture capture`` records the fixture's search queries and
chat turns from a running backend; ``… diff`` compares two captures under the
allowed-difference classes the fixture declared **before** the run. See
:mod:`jseval.workflow_fixture` for the equality relation and
``docs/design/lane-f-engine-jvm/design.md`` section 16 for why the instrument exists.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import click

from ._common import _DEFAULT_BASE_URL

#: Committed fixture definition shipped beside the toolkit.
DEFAULT_FIXTURE = Path(__file__).resolve().parents[2] / "lane-f-workflow-fixture.v1.json"


@click.group("workflow-fixture")
def workflow_fixture_group():
    """Lane F semantic-non-regression workflow fixture (design.md 16 / 17.2)."""


@workflow_fixture_group.command("capture")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True,
              help="Running backend to capture from (mode-agnostic: HTTP only).")
@click.option("--fixture", type=click.Path(exists=True, resolve_path=True),
              default=lambda: str(DEFAULT_FIXTURE),
              show_default="scripts/jseval/lane-f-workflow-fixture.v1.json",
              help="Fixture definition (queries, chat turns, declared field classes).")
@click.option("--out", "out_path", type=click.Path(resolve_path=True), required=True,
              help="Where to write workflow-fixture-capture.v1.json.")
@click.option("--session-token", default=None,
              help="Per-boot mutation token, sent on every request "
                   "(default: $JUSTSEARCH_SESSION_TOKEN).")
@click.option("--corpus-root", default=None,
              help="Absolute root the captured paths are stored RELATIVE to. The backend "
                   "returns absolute indexed paths and the two captures come from two "
                   "worktrees, so this is what makes them comparable. Default: "
                   "$JUSTSEARCH_FIXTURE_CORPUS_ROOT, else the single watched root from "
                   "GET /api/indexing/roots; with zero or several roots the capture "
                   "refuses (exit 2) naming them.")
@click.option("--skip-chat", is_flag=True,
              help="Capture only the search half (AI runtime offline). The result is for "
                   "INSPECTION: `diff` fails the capture-health check when a section is "
                   "empty on both sides, so a --skip-chat capture is not a gate artifact.")
@click.option("--timeout", default=300.0, show_default=True, type=float,
              help="Per-request timeout in seconds.")
@click.pass_context
def cmd_workflow_fixture_capture(ctx, base_url, fixture, out_path, session_token,
                                 corpus_root, skip_chat, timeout):
    """Capture the fixture's evidence, citations and cancellation record from a backend.

    ONE CAPTURE PER FRESH CORPUS. The chat turns index their own agent history, so a
    second capture against the same stack sees a changed index (measured: docCount 91 ->
    102 across one capture's three turns). The search half runs before the chat half
    inside a capture for exactly that reason. Re-running this command is NOT a stability
    check of the fixture — that needs a re-ingested corpus.

    Exit 0 = capture written, 2 = usage/definition error (including an unresolvable
    corpus root: zero or several watched roots and no --corpus-root).
    """
    from .. import workflow_fixture as wf

    try:
        definition = wf.load_fixture(fixture)
        doc = wf.capture(
            base_url, definition, out_path,
            session_token=session_token, skip_chat=skip_chat, timeout=timeout,
            corpus_root=corpus_root,
        )
    except (wf.WorkflowFixtureError, wf.httpx.HTTPError, OSError, ValueError) as exc:
        # httpx.HTTPError covers an unreachable backend (ConnectError) and timeouts, so a
        # backend that is not up exits 2 like every other usage error instead of a traceback.
        click.echo(f"workflow-fixture capture: {exc}", err=True)
        sys.exit(2)

    if ctx.obj.get("json"):
        click.echo(json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False))
    else:
        click.echo(
            f"Captured {len(doc['queries'])} queries + {len(doc['chatTurns'])} chat turns "
            f"-> {out_path}"
        )
        prov = doc["provenance"]
        click.echo(f"  corpus root: {prov['corpusRoot']} (from {prov['corpusRootSource']})")
        click.echo(f"  chat profile: {prov['chatProfile']} "
                   f"(ai runtime {prov['aiRuntimeState']}) — both captures of a paired "
                   "diff must be on the same profile")
        if prov.get("docCountAtStart") != prov.get("docCountAtEnd"):
            click.echo(f"  docCount moved {prov['docCountAtStart']} -> "
                       f"{prov['docCountAtEnd']} (the chat turns indexed agent history) "
                       "— re-ingest before the next capture, do not re-run against this index")
        if prov.get("rawRootExample"):
            click.echo(f"  e.g. {prov['rawRootExample']}  ->  stored relative to that root")
        if prov.get("pathsOutsideCorpusRoot"):
            click.echo(f"  WARNING  {prov['pathsOutsideCorpusRoot']} path(s) outside the "
                       f"corpus root, e.g. {prov['pathsOutsideCorpusRootExamples'][0]} "
                       "— `diff` will fail the capture-health check")


@workflow_fixture_group.command("diff")
@click.option("--baseline", type=click.Path(exists=True, resolve_path=True), required=True,
              help="The split-side capture (main after PR 0).")
@click.option("--candidate", type=click.Path(exists=True, resolve_path=True), required=True,
              help="The single-side capture (the lane branch).")
@click.option("--fixture", type=click.Path(exists=True, resolve_path=True),
              default=lambda: str(DEFAULT_FIXTURE),
              show_default="scripts/jseval/lane-f-workflow-fixture.v1.json",
              help="Fixture definition carrying the declared field classes.")
@click.option("--baseline-noise", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Second SAME-BUILD capture of the baseline side. Fields its own side already "
                   "moves are excluded from the verdict as noise.")
@click.option("--candidate-noise", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Second SAME-BUILD capture of the candidate side (see --baseline-noise).")
@click.option("--report-out", type=click.Path(resolve_path=True), default=None,
              help="Write the full diff result JSON to this path.")
@click.option("--json", "json_out", is_flag=True,
              help="Emit the full diff result JSON on stdout.")
@click.pass_context
def cmd_workflow_fixture_diff(ctx, baseline, candidate, fixture, baseline_noise,
                              candidate_noise, report_out, json_out):
    """Diff two captures under the fixture's declared equality relation.

    A field with no declared class must be byte-equal; an undeclared field is a
    regression by construction; a diff outside a field's declared class is a regression.
    The capture-health check fails the diff when the two captures recorded nothing to
    compare (an empty section on both sides, a non-200 request, a zero-hit query, or a
    declaration the capture never emits) — two identical failures are byte-equal, so
    without it a broken backend would read as "no semantic regression".
    With --baseline-noise / --candidate-noise (each the SECOND fresh-corpus capture of that
    same side, same build) the differ measures determinism instead of assuming it: a field a
    side own noise pair already moves is reported noisy-<side>, counted separately, and
    excluded from the verdict, because a difference the same build produces against itself
    cannot evidence a difference between two builds. A noise pair louder than the fixture
    maxNoisyFraction fails the run outright, so "almost nothing was compared" can never read
    as "nothing regressed".

    Exit 0 = pass, 1 = regression / missing field / unhealthy capture, 2 = usage error.
    """
    from .. import workflow_fixture as wf

    try:
        definition = wf.load_fixture(fixture)
        result = wf.diff(baseline, candidate, definition,
                         baseline_noise=baseline_noise, candidate_noise=candidate_noise)
    except (wf.WorkflowFixtureError, OSError, ValueError) as exc:
        click.echo(f"workflow-fixture diff: {exc}", err=True)
        sys.exit(2)

    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(
            json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

    if json_out or ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
    else:
        counts = result["counts"]
        click.echo(
            f"{'PASS' if result['pass'] else 'FAIL'}  "
            f"equal={counts['equal']} allowed={counts['allowed']} "
            f"noisy={counts.get('noisy', 0)} "
            f"REGRESSION={counts['REGRESSION']} missing={counts['missing']}"
        )
        by_side = result.get("noisy_by_side") or {}
        if any(by_side.values()):
            both = by_side.get("both", 0)
            # Per-side totals INCLUDE `both`, because a field noisy on both sides is noisy on
            # each — the same arithmetic the ceiling uses. Printing only the exclusive counts
            # made a side look quieter than the gate itself judged it.
            click.echo(
                "  noisy (excluded from the verdict): "
                + f"baseline={by_side.get('baseline', 0) + both} "
                + f"candidate={by_side.get('candidate', 0) + both} "
                + f"(of which both={both}) "
                + f"(ceiling {result.get('maxNoisyFraction')})"
            )
            for entry in result["fields"]:
                if str(entry.get("status", "")).startswith("noisy-"):
                    click.echo(
                        f"  {entry['status']}  {entry['record']}  {entry['field']}"
                        f"  (cross-side: {entry.get('crossSideStatus')})"
                    )
        for klass, n in result["allowed_by_class"].items():
            if n:
                click.echo(f"  allowed:{klass} = {n}")
        if result["declared_not_captured"]:
            click.echo("  declared but never captured: "
                       + ", ".join(result["declared_not_captured"]))
        for problem in result["health"]["problems"][:20]:
            click.echo(f"  UNHEALTHY  {problem}")
        for entry in wf.failures(result)[:50]:
            click.echo(f"  {entry['status']}  {entry['record']}  {entry['field']}"
                       f"  — {entry['reason']}")

    sys.exit(0 if result["pass"] else 1)


COMMANDS = [workflow_fixture_group]
