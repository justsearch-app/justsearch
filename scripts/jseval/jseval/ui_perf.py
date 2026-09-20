"""UI performance timing — keystroke-to-paint and click-to-preview via Playwright."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from pathlib import Path

import httpx

from . import suite_stats

log = logging.getLogger(__name__)

# SLO targets (milliseconds)
SLO_KEYSTROKE_MS = 300
SLO_CLICK_MS = 200

# CSS selectors (match data-testid attributes in the React app)
SEL_SEARCH_INPUT = '[data-testid="search-input"]'
SEL_RESULT_ROW = '[data-testid="search-result-row"]'
SEL_INSPECTOR = '[data-testid="inspector-pane"]'

# Browser-injected timing harness — runs inside Chromium, not in Python.
# Installs globalThis.__jsUiPerf with armKeydown, armClick, markPoint,
# finishAfterNextPaint. All timing uses performance.mark/measure + rAF.
_UI_PERF_HARNESS_JS = r"""
(() => {
  try {
    if (globalThis.__jsUiPerf) return;
    const states = new Map();
    const keyOf = (kind) => String(kind || '').trim();
    const startName = (kind) => 'js_ui_perf:' + kind + ':start';
    const endName = (kind) => 'js_ui_perf:' + kind + ':end';
    const measureName = (kind) => 'js_ui_perf:' + kind + ':measure';
    const clear = (kind) => {
      const k = keyOf(kind); if (!k) return;
      const prev = states.get(k);
      if (prev && typeof prev.cleanup === 'function') try { prev.cleanup(); } catch {}
      try { performance.clearMarks(startName(k)); } catch {}
      try { performance.clearMarks(endName(k)); } catch {}
      try { performance.clearMeasures(measureName(k)); } catch {}
      states.delete(k);
    };
    const ensure = (kind) => {
      const k = keyOf(kind); if (!k) throw new Error('Missing kind');
      let s = states.get(k);
      if (!s) { s = { kind: k, start_recorded: false, start_event: null, start_ms: null, points: Object.create(null), cleanup: null }; states.set(k, s); }
      return s;
    };
    const markStart = (kind, startEvent) => {
      const s = ensure(kind); s.start_recorded = true; s.start_event = startEvent || null;
      s.start_ms = performance.now(); s.points = Object.create(null);
      try { performance.clearMarks(startName(s.kind)); } catch {}
      try { performance.clearMeasures(measureName(s.kind)); } catch {}
      try { performance.clearMarks(endName(s.kind)); } catch {}
      try { performance.mark(startName(s.kind)); } catch {}
    };
    const markPoint = (kind, label) => {
      const s = ensure(kind); const l = String(label || '').trim(); if (!l) return false;
      if (!s.start_recorded) { s.start_recorded = true; s.start_event = null; s.start_ms = performance.now(); s.points = Object.create(null); try { performance.mark(startName(s.kind)); } catch {} }
      if (!s.points || typeof s.points !== 'object') s.points = Object.create(null);
      s.points[l] = performance.now(); return true;
    };
    const afterNextPaint = async () => { await new Promise(r => requestAnimationFrame(() => r(true))); await new Promise(r => requestAnimationFrame(() => r(true))); };
    const safeDelta = (a, b) => { const an = typeof a === 'number' ? a : null; const bn = typeof b === 'number' ? b : null; if (an == null || bn == null) return null; if (!Number.isFinite(an) || !Number.isFinite(bn)) return null; const d = an - bn; return d >= 0 ? d : null; };
    const finishAfterNextPaint = async (kind) => {
      const s = ensure(kind); await afterNextPaint(); const endNow = performance.now();
      try { performance.mark(endName(s.kind)); } catch {}
      let dur = null;
      try { performance.measure(measureName(s.kind), startName(s.kind), endName(s.kind)); const entries = performance.getEntriesByName(measureName(s.kind), 'measure'); if (entries && entries.length) dur = entries[entries.length - 1].duration; } catch { dur = null; }
      const startNow = typeof s.start_ms === 'number' ? s.start_ms : null;
      const pts = s.points && typeof s.points === 'object' ? s.points : null;
      const responseNow = pts ? pts.response : null; const visibleNow = pts ? pts.dom_visible : null;
      const breakdown = {};
      const e2r = safeDelta(responseNow, startNow); const r2v = safeDelta(visibleNow, responseNow); const v2p = safeDelta(endNow, visibleNow);
      if (e2r != null) breakdown.event_to_response_ms = e2r; if (r2v != null) breakdown.response_to_dom_visible_ms = r2v; if (v2p != null) breakdown.dom_visible_to_next_paint_ms = v2p;
      if (s.cleanup && typeof s.cleanup === 'function') { try { s.cleanup(); } catch {} s.cleanup = null; }
      return { kind: s.kind, duration_ms: typeof dur === 'number' ? dur : (startNow != null ? endNow - startNow : null), start_recorded: Boolean(s.start_recorded), start_event: s.start_event, ...(Object.keys(breakdown).length > 0 ? { breakdown_ms: breakdown } : {}) };
    };
    globalThis.__jsUiPerf = {
      reset(kind) { clear(kind); },
      armKeydown(kind, el) { clear(kind); const s = ensure(kind); if (!el || typeof el.addEventListener !== 'function') throw new Error('armKeydown requires element'); const handler = () => { try { markStart(kind, 'keydown'); } catch {} }; el.addEventListener('keydown', handler, { capture: true }); s.cleanup = () => { try { el.removeEventListener('keydown', handler, { capture: true }); } catch {} }; return true; },
      armClick(kind, el) { clear(kind); ensure(kind); if (!el || typeof el.addEventListener !== 'function') throw new Error('armClick requires element'); el.addEventListener('click', () => { try { markStart(kind, 'click'); } catch {} }, { capture: true, once: true }); return true; },
      markPoint, finishAfterNextPaint,
    };
  } catch {}
})();
"""


def _setup_test_corpus(api_base_url: str) -> tuple[str, str, str]:
    """Create test documents and ingest them. Returns (query, alpha_phrase, beta_phrase)."""
    query = "jseval_ui_perf_shared"
    alpha_phrase = "jseval_ui_perf_alpha_unique"
    beta_phrase = "jseval_ui_perf_beta_unique"

    with httpx.Client(base_url=api_base_url, timeout=30) as client:
        # Get data dir
        resp = client.get("/api/debug/effective-config")
        resp.raise_for_status()
        config = resp.json()
        data_dir = None
        if isinstance(config, list):
            for entry in config:
                if entry.get("key") == "justsearch.data.dir":
                    data_dir = entry.get("value")
                    break
        elif isinstance(config, dict):
            data_dir = config.get("justsearch.data.dir")
        if not data_dir:
            raise RuntimeError("Cannot resolve justsearch.data.dir")

        # Write test files
        test_dir = Path(data_dir) / "testdata" / "jseval_ui_perf"
        test_dir.mkdir(parents=True, exist_ok=True)
        (test_dir / "alpha.txt").write_text(
            f"{query}\n{alpha_phrase}\nUI perf test fixture.\n", encoding="utf-8",
        )
        (test_dir / "beta.txt").write_text(
            f"{query}\n{beta_phrase}\nUI perf test fixture.\n", encoding="utf-8",
        )

        # Ingest
        resp = client.post(
            "/api/knowledge/ingest",
            json={"paths": [str(test_dir)]},
        )
        resp.raise_for_status()

        # Wait for indexing
        invocation = resp.json()
        if (invocation.get("success") is not True
                or not invocation.get("structuredData", {}).get("operationKey")):
            raise RuntimeError(f"Ingest operation was not accepted: {invocation}")

        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            status = client.get("/api/status").json()
            if (status.get("indexState") == "IDLE"
                    and status.get("pendingJobs", 1) == 0):
                break
            time.sleep(0.5)

        # Verify searchable
        resp = client.post(
            "/api/knowledge/search",
            json={"query": query, "limit": 10},
        )
        resp.raise_for_status()
        hits = resp.json().get("totalHits", 0)
        if hits < 2:
            raise RuntimeError(f"Expected >=2 hits for '{query}', got {hits}")

    return query, alpha_phrase, beta_phrase


async def _measure_iteration(page, query: str, alpha_phrase: str, beta_phrase: str) -> list[dict]:
    """Run one iteration of keystroke + click measurement."""
    measurements = []
    input_el = page.locator(SEL_SEARCH_INPUT)

    # --- keystroke_to_results_paint ---
    await input_el.click()
    await input_el.fill("")

    search_resp = asyncio.ensure_future(
        page.wait_for_response(
            lambda r: "/api/knowledge/search" in r.url and r.status == 200,
            timeout=20000,
        )
    )

    await input_el.evaluate(
        "el => globalThis.__jsUiPerf && globalThis.__jsUiPerf.armKeydown('keystroke_to_results_paint', el)"
    )
    await input_el.type(query)
    await search_resp

    await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.markPoint(kind, 'response')",
        "keystroke_to_results_paint",
    )

    results_area = page.locator('main[aria-label="Search results"]')
    alpha_row = results_area.locator(f'{SEL_RESULT_ROW}[data-file-name="alpha.txt"]').first
    beta_row = results_area.locator(f'{SEL_RESULT_ROW}[data-file-name="beta.txt"]').first
    await alpha_row.wait_for(state="visible", timeout=20000)
    await beta_row.wait_for(state="visible", timeout=20000)

    await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.markPoint(kind, 'dom_visible')",
        "keystroke_to_results_paint",
    )
    ks_measured = await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.finishAfterNextPaint(kind)",
        "keystroke_to_results_paint",
    )
    measurements.append({
        "kind": "keystroke_to_results_paint",
        "duration_ms": ks_measured.get("duration_ms") if ks_measured else None,
        "breakdown_ms": ks_measured.get("breakdown_ms") if ks_measured else None,
    })

    # --- click_to_preview_visible ---
    rows = results_area.locator(SEL_RESULT_ROW)
    target_row = rows.nth(0)
    target_phrase = alpha_phrase  # first row is typically alpha

    preview_resp = asyncio.ensure_future(
        page.wait_for_response(
            lambda r: "/api/preview" in r.url and r.status == 200,
            timeout=20000,
        )
    )

    await target_row.evaluate(
        "el => globalThis.__jsUiPerf && globalThis.__jsUiPerf.armClick('click_to_preview_visible', el)"
    )
    await target_row.click()
    await preview_resp

    await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.markPoint(kind, 'response')",
        "click_to_preview_visible",
    )

    inspector = page.locator(SEL_INSPECTOR)
    await inspector.get_by_text(target_phrase).first.wait_for(state="visible", timeout=20000)

    await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.markPoint(kind, 'dom_visible')",
        "click_to_preview_visible",
    )
    click_measured = await page.evaluate(
        "kind => globalThis.__jsUiPerf && globalThis.__jsUiPerf.finishAfterNextPaint(kind)",
        "click_to_preview_visible",
    )
    measurements.append({
        "kind": "click_to_preview_visible",
        "duration_ms": click_measured.get("duration_ms") if click_measured else None,
        "breakdown_ms": click_measured.get("breakdown_ms") if click_measured else None,
    })

    return measurements


async def _run_browser_measurements(
    ui_url: str,
    query: str,
    alpha_phrase: str,
    beta_phrase: str,
    iterations: int,
    warmup: int,
    timeout_ms: int,
) -> list[dict]:
    """Launch Playwright, inject harness, run iterations."""
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise RuntimeError(
            "playwright not installed. Install with: pip install playwright && playwright install chromium"
        )

    all_measurements: list[dict] = []

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
        context = await browser.new_context(
            viewport={"width": 1280, "height": 720},
        )
        await context.add_init_script(script=_UI_PERF_HARNESS_JS)
        page = await context.new_page()
        await page.goto(ui_url, wait_until="domcontentloaded", timeout=timeout_ms)

        # Wait for search input
        await page.locator(SEL_SEARCH_INPUT).wait_for(state="visible", timeout=30000)
        # Wait for initial /api/status connection
        try:
            await page.wait_for_response(
                lambda r: "/api/status" in r.url and r.status == 200,
                timeout=15000,
            )
        except Exception:
            pass

        for i in range(warmup + iterations):
            record = i >= warmup
            label = "warmup" if not record else "timed"
            log.info("UI perf iteration %d/%d (%s)", i + 1, warmup + iterations, label)
            measurements = await _measure_iteration(page, query, alpha_phrase, beta_phrase)
            if record:
                for m in measurements:
                    m["iteration"] = i - warmup
                all_measurements.extend(measurements)

        await browser.close()

    return all_measurements


def execute_ui_perf(
    ui_url: str = "http://localhost:5173",
    api_base_url: str = "http://127.0.0.1:8080",
    *,
    iterations: int = 4,
    warmup: int = 1,
    timeout_ms: int = 30000,
) -> dict:
    """Measure UI latency: keystroke-to-paint and click-to-preview."""
    log.info("Setting up test corpus...")
    query, alpha_phrase, beta_phrase = _setup_test_corpus(api_base_url)

    log.info("Running %d iterations (%d warmup)...", iterations, warmup)
    measurements = asyncio.run(
        _run_browser_measurements(
            ui_url, query, alpha_phrase, beta_phrase,
            iterations, warmup, timeout_ms,
        )
    )

    return _build_result(measurements, iterations, warmup)


def _build_result(measurements: list[dict], iterations: int, warmup: int) -> dict:
    """Build the result dict with per-metric percentiles."""
    ks_durations = sorted(
        m["duration_ms"] for m in measurements
        if m["kind"] == "keystroke_to_results_paint" and m.get("duration_ms") is not None
    )
    click_durations = sorted(
        m["duration_ms"] for m in measurements
        if m["kind"] == "click_to_preview_visible" and m.get("duration_ms") is not None
    )

    def _summarize(durations: list[float], slo_ms: int) -> dict:
        if not durations:
            return {}
        return {
            "count": len(durations),
            "p50_ms": round(suite_stats.percentile(durations, 50), 1),
            "p95_ms": round(suite_stats.percentile(durations, 95), 1),
            "p99_ms": round(suite_stats.percentile(durations, 99), 1),
            "mean_ms": round(sum(durations) / len(durations), 1),
            "slo_ms": slo_ms,
            "slo_pass_rate": round(sum(1 for d in durations if d <= slo_ms) / len(durations), 4),
        }

    return {
        "schema": "ui-perf.v1",
        "iterations": iterations,
        "warmup": warmup,
        "measurements": measurements,
        "summary": {
            "keystroke_to_results_paint": _summarize(ks_durations, SLO_KEYSTROKE_MS),
            "click_to_preview_visible": _summarize(click_durations, SLO_CLICK_MS),
        },
    }


def format_console(result: dict) -> str:
    """Human-readable output for UI perf results."""
    lines = [f"UI Perf: {result['iterations']} iterations ({result['warmup']} warmup)"]
    for kind, label in [
        ("keystroke_to_results_paint", "Keystroke → Paint"),
        ("click_to_preview_visible", "Click → Preview"),
    ]:
        s = result.get("summary", {}).get(kind, {})
        if not s:
            lines.append(f"  {label}: no data")
            continue
        slo_status = "PASS" if s.get("slo_pass_rate", 0) >= 1.0 else "FAIL"
        lines.append(
            f"  {label}: p50={s['p50_ms']:.0f}ms  p95={s['p95_ms']:.0f}ms  "
            f"p99={s['p99_ms']:.0f}ms  SLO({s['slo_ms']}ms)={slo_status}"
        )
        for m in result.get("measurements", []):
            if m["kind"] == kind and m.get("breakdown_ms"):
                bd = m["breakdown_ms"]
                lines.append(
                    f"    iter {m.get('iteration', '?')}: "
                    f"event→response={bd.get('event_to_response_ms', '?'):.0f}ms  "
                    f"response→DOM={bd.get('response_to_dom_visible_ms', '?'):.0f}ms  "
                    f"DOM→paint={bd.get('dom_visible_to_next_paint_ms', '?'):.0f}ms"
                )
    return "\n".join(lines)
