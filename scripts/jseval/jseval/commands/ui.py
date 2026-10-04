"""jseval ui commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click
from ._common import _DEFAULT_BASE_URL, _write_bench_output

log = logging.getLogger(__name__)


@click.command("ui-perf")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--api-base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--iterations", default=4, show_default=True)
@click.option("--warmup", default=1, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_perf(ctx, ui_url, api_base_url, iterations, warmup, output_dir):
    """UI latency benchmark (keystroke-to-paint, click-to-preview via Playwright)."""
    from .. import ui_perf

    result = ui_perf.execute_ui_perf(
        ui_url=ui_url,
        api_base_url=api_base_url,
        iterations=iterations,
        warmup=warmup,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_perf.format_console(result))
    _write_bench_output(result, output_dir, "ui-perf.json")


@click.command("ui-check")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--cooldown-ms", default=250, show_default=True,
              help="Cooldown between screenshots (ms).")
@click.option("--timeout-ms", default=120_000, show_default=True,
              help="Overall timeout (ms).")
@click.option("--no-demo", is_flag=True, default=False,
              help="Use real backend instead of demo mode.")
@click.pass_context
def cmd_ui_check(ctx, ui_url, output_dir, cooldown_ms, timeout_ms, no_demo):
    """UI screenshot check (Playwright) — captures and verifies UI states."""
    from .. import ui_check

    result = ui_check.execute_ui_check(
        ui_url=ui_url,
        output_dir=output_dir,
        demo=not no_demo,
        cooldown_ms=cooldown_ms,
        timeout_ms=timeout_ms,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_check.format_console(result))
    _write_bench_output(result, output_dir, "ui-check.json")
    if not result.get("ok", False):
        ctx.exit(1)


@click.command("ui-shot")
@click.argument("step_name", required=False, default=None)
@click.option("--list", "list_steps", is_flag=True, default=False,
              help="List all available steps.")
@click.option("--affected", "affected_path", type=click.Path(), default=None,
              help="Capture steps affected by a file change.")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--cooldown-ms", default=250, show_default=True)
@click.option("--timeout-ms", default=30_000, show_default=True,
              help="Per-step timeout (ms).")
@click.option("--no-demo", is_flag=True, default=False,
              help="Use real backend instead of demo mode.")
@click.option("--no-measure", is_flag=True, default=False,
              help="Skip the structured-measurement companion (615 §6.2); capture the PNG only.")
@click.option("--fixtures", is_flag=True, default=False,
              help="Deterministic mode (615 §16): route-mock every /api/* with schema-valid "
                   "fixtures + dismiss the first-run walkthrough — no backend, byte-stable, zero "
                   "env console noise. For STRUCTURAL steps; omit for the live AI-chain steps.")
@click.option("--trace", is_flag=True, default=False,
              help="TRACE mode (615 §11): also write <step>.trace.json — the {pre,post} measurement "
                   "delta across the step's interaction trajectory (what the flow changed).")
@click.option("--record", is_flag=True, default=False,
              help="Tempdoc 669: also record a video (Playwright's native webm) spanning the whole "
                   "chain replay leading to this step — e.g. `ui-shot citation-highlight --record` "
                   "captures search -> inspector -> cited-answer in one clip. No-op for isolated "
                   "steps. Written under <output-dir>/videos/.")
@click.pass_context
def cmd_ui_shot(ctx, step_name, list_steps, affected_path, ui_url, output_dir,
                cooldown_ms, timeout_ms, no_demo, no_measure, fixtures, trace, record):
    """Single-step UI screenshot for agent feedback loop."""
    from .. import ui_shot

    if list_steps:
        result = ui_shot.execute_ui_shot_list(ui_url=ui_url)
        if ctx.obj.get("json"):
            click.echo(json.dumps(result, indent=2, default=str))
        else:
            click.echo(ui_shot.format_console_list(result))
        return

    if affected_path:
        results = ui_shot.execute_ui_shot_affected(
            affected_path,
            ui_url=ui_url, output_dir=output_dir,
            demo=not no_demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms,
            measure=not no_measure, fixtures=fixtures, trace=trace, record=record,
        )
        if ctx.obj.get("json"):
            click.echo(json.dumps(results, indent=2, default=str))
        else:
            click.echo(ui_shot.format_console_affected(results))
        if any(not result.get("ok", False) for result in results):
            ctx.exit(1)
        return

    if not step_name:
        raise click.UsageError("Provide a step name, --list, or --affected <path>.")

    result = ui_shot.execute_ui_shot(
        step_name,
        ui_url=ui_url, output_dir=output_dir,
        demo=not no_demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms,
        measure=not no_measure, fixtures=fixtures, trace=trace, record=record,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_shot.format_console_shot(result))
    if not result.get("ok", False):
        ctx.exit(1)


@click.command("ui-a11y-gate")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--timeout-ms", default=30_000, show_default=True)
@click.pass_context
def cmd_ui_a11y_gate(ctx, ui_url, output_dir, timeout_ms):
    """ASSERT gate (615 §11): fail on a NEW a11y violation vs the shared baseline.

    Captures every surface in governance/ui-a11y-baseline.v1.json in the deterministic
    --fixtures state and compares its axe violations to the surface's accepted
    knownRules. Exit 0 = clean, 1 = a NEW (non-baselined) violation, 2 = capture error.
    Also runs on PRs as the advisory `Measured axe` job in ci.yml (ADR-0026 amendment
    2026-09-05); that lane is not a required check, so run this locally too.
    """
    from .. import ui_a11y_gate, ui_shot

    def _capture(step: str) -> dict:
        return ui_shot.execute_ui_shot(
            step, ui_url=ui_url, output_dir=output_dir,
            demo=False, timeout_ms=timeout_ms, measure=True, fixtures=True,
        )

    report = ui_a11y_gate.evaluate(_capture)
    click.echo(json.dumps(report, indent=2, default=str), err=True)
    sys.exit(report["exit_code"])


@click.command("ui-proportion-gate")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--timeout-ms", default=30_000, show_default=True)
@click.pass_context
def cmd_ui_proportion_gate(ctx, ui_url, output_dir, timeout_ms):
    """Chrome-proportion shrink-only ratchet gate (tempdoc 697).

    Captures every step in governance/ui-proportion-baseline.v1.json in the
    deterministic --fixtures state and compares each registered persistent-chrome
    element's measured height to its baseline maxHeightPx (+ tolerancePx). Shrinking
    is always allowed; growth beyond tolerance is a violation. Exit 0 = clean, 1 = a
    GROWN element, 2 = capture error. Local-first (ADR-0026): a runnable gate, not
    CI-wired.
    """
    from .. import ui_proportion_gate, ui_shot

    def _capture(step: str) -> dict:
        return ui_shot.execute_ui_shot(
            step, ui_url=ui_url, output_dir=output_dir,
            demo=False, timeout_ms=timeout_ms, measure=True, fixtures=True,
        )

    report = ui_proportion_gate.evaluate(_capture)
    for row in report["rows"]:
        status = row.get("status")
        if status == "GROWN":
            click.echo(
                f"GROWN: step={row['step']} selector={row['selector']} "
                f"measured={row['measuredHeight']}px > baseline={row['maxHeightPx']}px "
                f"(+{row['tolerancePx']}px tolerance)",
                err=True,
            )
        elif status == "UNDER_MIN_HEIGHT":
            click.echo(
                f"UNDER_MIN_HEIGHT: step={row['step']} selector={row['selector']} "
                f"measured={row['measuredHeight']}px < floor={row['minHeightPx']}px "
                f"(-{row['tolerancePx']}px tolerance) — the band this row exists to bound "
                f"collapsed, so its ceiling is measuring the wrong state",
                err=True,
            )
        elif status == "UNDER_SHARE":
            click.echo(
                f"UNDER_SHARE: step={row['step']} selector={row['selector']} "
                f"share={row['measuredShare']} < floor={row['floor']} "
                f"({row['measuredHeight']}px / {row['otherHeight']}px of {row['otherSelector']!r})",
                err=True,
            )
        elif status == "CLIPPED":
            click.echo(
                f"CLIPPED: step={row['step']} selector={row['selector']} "
                f"bottom={row['measuredBottom']}px > maxBottomPx={row['maxBottomPx']}px",
                err=True,
            )
        elif status == "MULTI_SCROLL":
            click.echo(
                f"MULTI_SCROLL: step={row['step']} scrollableCount={row['scrollableCount']} "
                f"> maxScrollableRegions={row['maxScrollableRegions']}",
                err=True,
            )
        elif status == "NO_SCROLLER":
            click.echo(
                f"NO_SCROLLER: step={row['step']} scrollableCount={row['scrollableCount']} "
                f"< minScrollableRegions={row['minScrollableRegions']} — the one-scroller rule "
                f"has nothing to witness on this capture",
                err=True,
            )
        elif status == "DUPLICATE_STATUS_FACT":
            click.echo(
                f"DUPLICATE_STATUS_FACT: step={row['step']} phrase={row['phrase']!r} "
                f"count={row['count']} > maxPersistentRenders={row['maxPersistentRenders']}",
                err=True,
            )
        elif status == "PRESENT_BUT_SHOULD_BE_ABSENT":
            click.echo(
                f"PRESENT_BUT_SHOULD_BE_ABSENT: step={row['step']} selector={row['selector']}",
                err=True,
            )
        elif status == "MISSING_REQUIRED":
            click.echo(
                f"MISSING_REQUIRED: step={row['step']} selector={row['selector']} — the step's "
                f"state did not mount an element the register requires it to",
                err=True,
            )
        elif status == "IS_SCROLLER":
            click.echo(
                f"IS_SCROLLER: step={row['step']} selector={row['selector']} "
                f"scrollDelta={row.get('scrollDelta')} — D3 reserves the surface's one scroll "
                f"region for the conversation; this element must not be a scroller"
                + (f" ({row['error']})" if row.get("error") else ""),
                err=True,
            )
        elif status == "FORBIDDEN_TEXT_VISIBLE":
            click.echo(
                f"FORBIDDEN_TEXT_VISIBLE: step={row['step']} phrase={row['phrase']!r} "
                f"count={row['count']} > 0 — this step's state must not render that wording",
                err=True,
            )
    click.echo(json.dumps(report, indent=2, default=str), err=True)
    sys.exit(report["exit_code"])


@click.command("ui-diff")
@click.argument("before", type=click.Path(exists=True))
@click.argument("after", type=click.Path(exists=True))
@click.pass_context
def cmd_ui_diff(ctx, before, after):
    """DIFF (615 §11): semantic perceptual changelog between two <step>.measure.json.

    Reports what MEANINGFULLY changed — a landmark removed, an element moved/resized
    past 4px, a NEW axe rule, overflow flipped, real console errors — not a pixel diff.
    Exit 0 if no semantic change, 1 if changed (so it composes in scripts).
    """
    from .. import ui_diff

    report = ui_diff.diff_files(before, after)
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2, default=str))
    else:
        click.echo(ui_diff.format_diff(report))
    sys.exit(1 if report["changed"] else 0)


@click.command("ui-critic")
@click.argument("step")
@click.option("--surface", default=None, help="Design-reference surface key (default: from the register).")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_critic(ctx, step, surface, ui_url, output_dir):
    """REASON (615 §11): emit the GROUNDED design-critique prompt for a captured surface.

    Captures STEP in the deterministic --fixtures state, pairs the measured facts with
    governance/design-reference.v1.json + the rubric, and prints the critique prompt.
    Feed it to a model (the agent, or the dev model via `agent_chat`) to get the
    structured critique — the LLM-rubric half of "unit tests + LLM rubrics for quality".
    """
    import json as _json

    from .. import ui_critic, ui_shot

    surface = surface or _step_surface_map().get(step, step)
    res = ui_shot.execute_ui_shot(step, ui_url=ui_url, output_dir=output_dir,
                                  demo=False, measure=True, fixtures=True)
    if not res.get("ok"):
        click.echo(_json.dumps({"error": res.get("error")}), err=True)
        sys.exit(2)
    measure_path = (res.get("measure") or {}).get("measure_path")
    measure = _json.loads(Path(measure_path).read_text(encoding="utf-8"))
    prompt = ui_critic.assemble_prompt(measure, ui_critic.load_reference(), surface)
    # The prompt IS the product here; the model (agent/agent_chat) produces the critique.
    click.echo(prompt)


@click.command("ui-fuzz")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_fuzz(ctx, ui_url, output_dir):
    """GENERATE (615 §11): fuzz the search surface across {data-variant x viewport x theme}.

    Renders every cell of the state matrix a human won't patiently check (narrow-viewport
    overflow, light-theme contrast, empty-data layout), measures each, and flags the cells
    with anomalies (NEW axe vs baseline / overflow / real console errors). Exit 0 = all
    clean, 1 = a cell flagged.
    """
    import asyncio

    from .. import ui_fuzz

    out = Path(output_dir) if output_dir else Path("tmp/ui-fuzz")
    report = asyncio.run(ui_fuzz.run_fuzz(out, ui_url=ui_url))
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2, default=str))
    else:
        click.echo(ui_fuzz.format_matrix(report))
    sys.exit(1 if report["flagged"] else 0)


def _step_surface_map() -> dict:
    """uiShotStep -> surface, from the shared baseline register (the surfaces both the
    a11y baseline and the design reference are keyed by)."""
    from .. import ui_a11y_gate
    return {s["uiShotStep"]: s["surface"] for s in ui_a11y_gate.load_register_surfaces() if s.get("uiShotStep")}


COMMANDS = [cmd_ui_perf, cmd_ui_check, cmd_ui_shot, cmd_ui_a11y_gate, cmd_ui_proportion_gate,
            cmd_ui_diff, cmd_ui_critic, cmd_ui_fuzz]
