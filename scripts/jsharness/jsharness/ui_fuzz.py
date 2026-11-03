"""ui-fuzz — the GENERATE capability (tempdoc 615 §11 GENERATE / the exhaustiveness
superpower §2): capture a surface across a cross-product of states a human won't
patiently render — {data-variant x viewport x theme} — measure each, and surface the
cells with anomalies. UX bugs live in edge states (narrow viewport overflow, light-theme
contrast, empty-data layout); the agent renders them all, in one command.

Reuses the route-mock substrate (data-extreme = a fixture variant, not a backend state),
`capture_measure`, and the shared baseline split — no new capture machinery.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from . import ui_fixtures
from . import ui_measure
from . import ui_selectors as S
from .ui_shot import _resolve_ui_url, ServeStartError

VIEWPORTS: dict[str, tuple[int, int]] = {"desktop": (1280, 720), "narrow": (560, 720)}
THEMES: tuple[str, ...] = ("dark", "light")


def _search_known_rules() -> list[str]:
    from . import ui_a11y_gate
    for s in ui_a11y_gate.load_register_surfaces():
        if s.get("surface") == "search":
            return list(s.get("knownRules") or [])
    return []


async def _capture_cell(browser, ui_url: str, out_dir: Path, variant: str, vp: str, theme: str) -> dict[str, Any]:
    w, h = VIEWPORTS[vp]
    cell = {"variant": variant, "viewport": vp, "theme": theme}
    ctx = await browser.new_context(viewport={"width": w, "height": h}, color_scheme=theme)
    try:
        await ui_fixtures.install_fixtures(ctx, variant=variant)
        page = await ctx.new_page()
        sink = ui_measure.ConsoleSink()
        sink.attach(page)
        await page.goto(ui_url, wait_until="domcontentloaded", timeout=30_000)
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            # The surface couldn't be reached in this cell (e.g. a narrow viewport that
            # collapses the rail) — that IS an anomaly the fuzzer reports, not a crash.
            return {"cell": cell, "render_failed": True}
        name = f"fuzz-{variant}-{vp}-{theme}"
        _path, summary = await ui_measure.capture_measure(page, name, out_dir, sink, theme=theme)
        summary["cell"] = cell
        return summary
    finally:
        await ctx.close()


def cell_anomalies(summary: dict[str, Any], search_known: list[str]) -> list[str]:
    """The actionable findings for one cell: NEW axe vs the search baseline, overflow,
    real console errors. (capture_measure tags axe_new against the fuzz name, which has
    no baseline, so NEW is recomputed here against the search surface's knownRules.)"""
    out: list[str] = []
    measure = json.loads(Path(summary["measure_path"]).read_text(encoding="utf-8"))
    violations = (measure.get("axe") or {}).get("violations") or []
    new, _ = ui_measure.split_new_vs_known(violations, search_known)
    if new:
        out.append(f"axe-NEW:{','.join(new)}")
    if summary.get("overflow"):
        out.append(f"overflow:{','.join(summary['overflow'])}")
    if summary.get("console_real"):
        out.append(f"console-real:{summary['console_real']}")
    return out


async def run_fuzz(out_dir: Path, ui_url: str = "http://localhost:5173") -> dict[str, Any]:
    """Capture the search surface across every {variant x viewport x theme} cell."""
    from playwright.async_api import async_playwright

    try:
        ui_url = _resolve_ui_url(ui_url)
    except ServeStartError as e:
        return {"total_cells": 0, "flagged": 0, "cells": [], "error": f"cannot serve: {e}"}
    out_dir.mkdir(parents=True, exist_ok=True)
    search_known = _search_known_rules()
    cells: list[dict[str, Any]] = []
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            for variant in ui_fixtures.VARIANTS:
                for vp in VIEWPORTS:
                    for theme in THEMES:
                        summary = await _capture_cell(browser, ui_url, out_dir, variant, vp, theme)
                        if summary.get("render_failed"):
                            cells.append({**summary["cell"], "a11y_landmarks": None,
                                          "anomalies": ["render-failed"]})
                            continue
                        anomalies = cell_anomalies(summary, search_known)
                        cells.append({**summary["cell"],
                                      "a11y_landmarks": summary.get("a11y_landmarks"),
                                      "anomalies": anomalies})
        finally:
            await browser.close()
    flagged = [c for c in cells if c["anomalies"]]
    return {"total_cells": len(cells), "flagged": len(flagged), "cells": cells}


def format_matrix(report: dict[str, Any]) -> str:
    lines = [f"state matrix: {report['total_cells']} cells, {report['flagged']} flagged"]
    for c in report["cells"]:
        tag = ("  ! " + "; ".join(c["anomalies"])) if c["anomalies"] else "  ok"
        lines.append(f"  [{c['variant']:7s} {c['viewport']:7s} {c['theme']:5s}]"
                     f" landmarks={c['a11y_landmarks']}{tag}")
    return "\n".join(lines)
