"""UI screenshot check — captures and verifies UI states via Playwright.

Usage: ``python -m jseval ui-check [--ui-url URL] [--output-dir DIR]``

All screenshots are declared as Steps in a flat registry. Each step has:
- setup: async function to prepare the page state
- isolated: whether it needs its own browser (True) or shares one (False)
- depends_on: which step must succeed first (for shared-browser chains)
- required: whether failure affects the overall pass/fail
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

from . import ui_fixtures
from . import ui_measure
from . import ui_selectors as S


# ---------------------------------------------------------------------------
# App-mounted readiness gate  (tempdoc 615 §27 — the readiness half of the contract)
# ---------------------------------------------------------------------------
# HTTP-200 means the server accepts connections, NOT that the app mounted (615 §28 U4a:
# 200 in ~1s, a real mount needs up to 15s). The robust mount predicate is the rail
# button VISIBLE (615 §28 U5 — chrome-level, survives data corruption; `jf-shell`-in-DOM
# and state="attached" are too weak — the hydration gap). When the shell never mounts, we
# attribute the failure to the SERVE layer with the best-available reason (615 §28 U3),
# instead of letting a generic Playwright timeout read as a phantom "render-failed".

class AppNotMountedError(Exception):
    """The served app never mounted within the deadline. Carries the best-available reason."""


async def _await_app_ready(page, *, timeout_ms: int = 15_000) -> None:
    """Block until the app shell has mounted (rail button visible), else raise
    AppNotMountedError with the best-available reason (Vite stderr tail / error-overlay
    text / honest fallback). ONE gate, reused by every capture path."""
    try:
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=timeout_ms)
        return
    except Exception:
        pass  # fall through to assemble the reason — never let the bare timeout escape

    secs = round(timeout_ms / 1000)
    # (1) Vite boot/compile errors land in the captured server stderr (615 §27 fail-loud).
    # GUARD (615 §34): only trust the server-info stderr when THIS page targets THAT server —
    # else an external `--ui-url` (or a stale server-info) would attach an unrelated server's
    # stderr as the reason. Compare the served port to the recorded port.
    stderr_tail = ""
    try:
        from . import ui_shot
        if ui_shot._SERVER_INFO_PATH.exists():
            info = json.loads(ui_shot._SERVER_INFO_PATH.read_text(encoding="utf-8"))
            try:
                page_port = urlparse(page.url).port
            except Exception:
                page_port = None
            # 615 §35: also require the server-info to be LIVE (pid alive) — a stale dead-pid
            # info that merely shares the page's port (e.g. an external --ui-url) must not
            # supply a misleading stderr tail.
            if (page_port is not None and page_port == info.get("port")
                    and ui_shot._pid_alive(info.get("pid"))):
                stderr_tail = ui_shot._tail_file(info.get("stderr_log", ""), 800)
    except Exception:
        stderr_tail = ""
    # (2) Vite's in-page error overlay (615 §28 U3 — confirmed channel, no false positive).
    overlay = None
    try:
        overlay = await page.evaluate(
            "() => { const o = document.querySelector('vite-error-overlay');"
            " return o ? (o.shadowRoot?.textContent || o.textContent || 'present').slice(0,400) : null; }")
    except Exception:
        overlay = None

    if stderr_tail:
        reason = f"app shell never mounted within {secs}s; vite stderr tail: {stderr_tail}"
    elif overlay:
        reason = f"app shell never mounted within {secs}s; vite error overlay: {overlay.strip()}"
    else:
        reason = (f"app shell never mounted within {secs}s; no Vite stderr or error overlay "
                  "captured (a server may be serving non-app content, or the bundle failed silently)")
    raise AppNotMountedError(reason)


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass
class ShotResult:
    """Result of a single screenshot capture."""
    name: str
    path: str | None = None
    ok: bool = False
    elapsed_ms: float = 0
    error: str | None = None
    required: bool = True
    # tempdoc 615 §6.2 — the structured-measurement companion (facts, not pixels).
    measure_path: str | None = None
    measure_summary: dict[str, Any] | None = None
    # Tempdoc 669 — set when `--record` captured a video spanning this step's chain replay.
    video_path: str | None = None


@dataclass
class EvalResult:
    """Top-level result of a UI eval run."""
    shots: list[ShotResult] = field(default_factory=list)
    output_dir: str | None = None
    elapsed_ms: float = 0

    @property
    def ok(self) -> bool:
        return all(s.ok for s in self.shots if s.required)

    @property
    def total_shots(self) -> int:
        return len(self.shots)

    @property
    def total_passed(self) -> int:
        return sum(1 for s in self.shots if s.ok)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": "ui-check.v1",
            "ok": self.ok,
            "elapsed_ms": round(self.elapsed_ms, 1),
            "output_dir": self.output_dir,
            "total_shots": self.total_shots,
            "total_passed": self.total_passed,
            "shots": [
                {
                    "name": s.name,
                    "ok": s.ok,
                    "required": s.required,
                    "elapsed_ms": round(s.elapsed_ms, 1),
                    **({"error": s.error} if s.error else {}),
                    **({"measure": s.measure_summary} if s.measure_summary else {}),
                }
                for s in self.shots
            ],
        }


# ---------------------------------------------------------------------------
# Step model
# ---------------------------------------------------------------------------

@dataclass
class Step:
    """A declarative screenshot capture step.

    isolated=False: runs in a shared browser context (sequential chain).
    isolated=True: launches its own browser (can run in parallel with others).
    """
    name: str
    setup: Callable[..., Awaitable[None]]
    required: bool = True
    depends_on: str | None = None
    isolated: bool = False
    # For isolated steps: browser config overrides
    color_scheme: str = "dark"
    init_scripts: list[str] = field(default_factory=list)
    # Tempdoc 697 activation — per-step override of the `install_fixtures(ctx, variant=...)`
    # data variant, applied only when the run passes `fixtures=True`. Defaults to "default"
    # (unchanged behavior for every existing step); an isolated step that needs a specific
    # deterministic data/readiness state (e.g. `chat-proportion` needing `degraded`) sets this.
    fixtures_variant: str = "default"


# ---------------------------------------------------------------------------
# Screenshot helpers
# ---------------------------------------------------------------------------

_JS_SCROLL_TO_TOP = """() => {
    const stage = document.querySelector('.zone-stage') || document.querySelector('main');
    if (!stage) return;
    let best = null;
    for (const el of stage.querySelectorAll('*')) {
        const oy = getComputedStyle(el).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        const d = (el.scrollHeight||0) - (el.clientHeight||0);
        if (d <= 1) continue;
        if (!best || (el.clientHeight||0) > best.s) best = { el, s: el.clientHeight||0 };
    }
    if (best?.el) best.el.scrollTop = 0;
}"""

_JS_GET_SCROLL_DELTA = """() => {
    const stage = document.querySelector('.zone-stage') || document.querySelector('main');
    if (!stage) return 0;
    let best = null;
    for (const el of stage.querySelectorAll('*')) {
        const oy = getComputedStyle(el).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        const d = (el.scrollHeight||0) - (el.clientHeight||0);
        if (d <= 1) continue;
        if (!best || (el.clientHeight||0) > best.s) best = { d, s: el.clientHeight||0 };
    }
    return best ? best.d : 0;
}"""


async def _screenshot(page, out_path: str, *, cooldown_ms: int = 250) -> bool:
    base_vp = page.viewport_size or {"width": 1280, "height": 720}
    resized = False
    try:
        await page.evaluate(_JS_SCROLL_TO_TOP)
        delta = await page.evaluate(_JS_GET_SCROLL_DELTA)
        if delta and delta > 1:
            h = min(base_vp["height"] + delta + 32, 4096)
            if h > base_vp["height"] + 1:
                await page.set_viewport_size({"width": base_vp["width"], "height": h})
                resized = True
                await asyncio.sleep(0.1)
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await page.screenshot(path=out_path, full_page=False)
        return True
    finally:
        if resized:
            await page.set_viewport_size(base_vp)
            await asyncio.sleep(0.1)


async def _capture_shot(
    page, name: str, output_dir: Path, *, cooldown_ms: int = 250,
    console_sink: "ui_measure.ConsoleSink | None" = None, measure: bool = True, theme: str = "dark",
) -> ShotResult:
    t0 = time.monotonic()
    out = str(output_dir / f"{name}.png")
    try:
        ok = await _screenshot(page, out, cooldown_ms=cooldown_ms)
        r = ShotResult(name=name, path=out, ok=ok, elapsed_ms=(time.monotonic() - t0) * 1000)
        # tempdoc 615 §6.2 — capture the measurement companion alongside the PNG so a correctness
        # judgment can target facts (a11y/axe/geometry/console), not pixels. Best-effort: never fails.
        if ok and measure:
            try:
                mp, ms = await ui_measure.capture_measure(
                    page, name, output_dir, console_sink, theme=theme,
                )
                r.measure_path, r.measure_summary = mp, ms
            except Exception as e:
                r.measure_summary = {"error": str(e)[:200]}
        return r
    except Exception as e:
        return ShotResult(name=name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000, error=str(e)[:200])


# ---------------------------------------------------------------------------
# Shared interaction helpers
# ---------------------------------------------------------------------------

def _demo_url(ui_url: str, **extra: str) -> str:
    parsed = urlparse(ui_url)
    params = parse_qs(parsed.query)
    params["demo"] = ["true"]
    for k, v in extra.items():
        params[k] = [v]
    return urlunparse(parsed._replace(query=urlencode(params, doseq=True)))


async def _type_and_search(page, query: str = "justsearch") -> None:
    # tempdoc 615 §6.1b: the live Lit shell lands on the chat surface, so navigate to the search
    # surface first (rail click, hash-route fallback) before reaching for the search input.
    await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
    try:
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
    except Exception:
        # Search Thread S5b — the standalone `core.search-surface` rail surface is retired; the
        # retrieve tier folded into the one window (matches S.RAIL_SURFACE_SEARCH above).
        await page.evaluate("() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }")
    # tempdoc 615 §11 HARDEN: resolve the search input by accessible role+name first
    # (stable across testid churn), falling back to the testid.
    inp = await S.SEARCH_INPUT.locate(page)
    await inp.wait_for(state="visible", timeout=10_000)
    await inp.click()
    await inp.type(query, delay=30)
    await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)


async def _navigate_and_search(page, url: str, query: str = "justsearch", *, timeout_ms: int = 60_000) -> None:
    await page.goto(url, wait_until="networkidle", timeout=timeout_ms)
    await _type_and_search(page, query)


async def _await_turn_count(page, expected: int, *, timeout_ms: int = 15_000) -> None:
    """Wait until the conversation has rendered exactly ``expected`` user bubbles.

    Tempdoc 814 review pass — the condition-poll that makes the multi-turn spine step honest:
    the turn count is what `spineItems()` gates on, so photographing a half-loaded timeline
    would register a ceiling/presence assertion against the wrong state. An observed count,
    not a sleep. Shadow-piercing because the bubbles live inside the surface's shadow root.
    """
    await page.wait_for_function(
        """(args) => {
            const deepAll = (root, acc, depth) => {
                root = root || document; acc = acc || []; depth = depth || 0;
                if (depth > 40) return acc;
                for (const el of root.querySelectorAll('*')) {
                    acc.push(el);
                    if (el.shadowRoot) deepAll(el.shadowRoot, acc, depth + 1);
                }
                return acc;
            };
            return deepAll().filter((el) => el.matches(args.sel)).length === args.n;
        }""",
        arg={"sel": S.CSS_MESSAGE_USER, "n": expected},
        timeout=timeout_ms,
    )


# Tempdoc 822 citation-mark presentation §7 — the measured half of `sv3-citation-selected`.
#
# The three probes below run in the page, over COMPUTED style, because the defect they guard is a
# CASCADE-ORDER defect: `.cite-selected` set `color` at the same specificity as the grounding tiers
# and later in source, which no stylesheet-text or source-level check can see. They are the same
# shadow-piercing walk `_await_turn_count` uses (the marks live several shadow roots deep: the
# renderer's, inside the window's, inside the surface's), fed to `wait_for_function` so a wrong
# render RAISES instead of being photographed as a right one.
_JS_DEEP_WALK = """
    const deepAll = (root, acc, depth) => {
        root = root || document; acc = acc || []; depth = depth || 0;
        if (depth > 40) return acc;
        for (const el of root.querySelectorAll('*')) {
            acc.push(el);
            if (el.shadowRoot) deepAll(el.shadowRoot, acc, depth + 1);
        }
        return acc;
    };
    const painted = (c) => !!c && c !== 'transparent' && !/^rgba\\(\\s*0,\\s*0,\\s*0,\\s*0\\s*\\)$/.test(c);
"""

# The mark this run will drive the selection FROM, chosen BEFORE anything is selected.
#
# Deliberately not "the first one": the state has to survive on the SUBDUED tiers, whose ink sits
# closest to the floor once a wash is painted behind it, so a low tier is picked when the answer
# contains one and the caller is told when it does not. Its ink is captured here so assertion 2 can
# compare against the exact resting value without needing a second same-tier mark on screen, and its
# `data-cite-key` is what ties the card, the mark and the sentence region to ONE source below.
_JS_PICK_CITE_MARK = """() => {
""" + _JS_DEEP_WALK + """
    const marks = deepAll().filter((el) => el.matches('.cite-ref'));
    if (marks.length === 0) return null;
    const pick =
        marks.find((m) => m.classList.contains('cite-ungrounded')) ||
        marks.find((m) => m.classList.contains('cite-weak')) ||
        marks[0];
    return {
        key: pick.dataset.citeKey || null,
        tier: pick.classList.contains('cite-ungrounded')
            ? 'ungrounded'
            : pick.classList.contains('cite-weak') ? 'weak' : 'grounded',
        ink: getComputedStyle(pick).color,
        label: (pick.textContent || '').trim(),
        total: marks.length,
    };
}"""

# 1 + 2: THAT mark — not merely some selected mark — wears a real fill, announces itself current,
# and its INK is untouched.
_JS_SELECTED_MARK_OK = """(args) => {
""" + _JS_DEEP_WALK + """
    const mark = deepAll().find(
        (el) => el.matches('.cite-ref') && el.dataset.citeKey === args.key,
    );
    if (!mark) return false;
    if (!mark.classList.contains('cite-selected')) return false;
    const cs = getComputedStyle(mark);
    if (!painted(cs.backgroundColor)) return false;
    if (mark.getAttribute('aria-current') !== 'true') return false;
    // THE HEADLINE (F2): selection paints SURFACE; `color` belongs to the grounding tier. Before the
    // repair this read the selection's own foreground and the honesty tier was erased on click.
    return cs.color === args.ink;
}"""

# 3: the sentences THAT source supports are tinted — the payload, not just the handle (F4).
_JS_SELECTED_REGION_OK = """(args) => {
""" + _JS_DEEP_WALK + """
    const region = deepAll().find(
        (el) => el.matches('.cite-sentence-selected') && el.dataset.citeKey === args.key,
    );
    return !!region && painted(getComputedStyle(region).backgroundColor);
}"""


# ---------------------------------------------------------------------------
# Step registry — all screenshots declared here
# ---------------------------------------------------------------------------

def _build_steps(ui_url: str, cooldown_ms: int, timeout_ms: int) -> list[Step]:
    """Build the complete flat step list."""
    demo = _demo_url(ui_url)
    ai_init = "localStorage.setItem('justsearch-inspector-tab', 'ai');"

    # === Shared-browser chain (sequential, depends_on linkage) ===

    async def setup_search_results(page):
        await _type_and_search(page)

    async def setup_command_mode(page):
        inp = page.get_by_test_id(S.TID_SEARCH_INPUT)
        await inp.click()
        await inp.fill("/reindex")
        await page.get_by_test_id(S.TID_GLOBAL_COMMAND_CHROME).get_by_text("/reindex", exact=False).wait_for(state="visible", timeout=10_000)

    async def setup_chat_mode(page):
        await page.get_by_test_id(S.TID_SEARCH_INPUT).press("Escape")
        inp = page.get_by_test_id(S.TID_SEARCH_INPUT)
        await inp.wait_for(state="visible", timeout=10_000)
        await inp.click()
        await inp.fill("??")
        await page.locator(S.CSS_SEARCH_INPUT_TEXTAREA).wait_for(state="visible", timeout=10_000)

    async def setup_filters_chips(page):
        # tempdoc 615 §6.1b: live facets render as a `[data-testid=facet-row]` of `.facet-chip` buttons
        # (only when the response carries facet counts), NOT the retired filter-toggle + type dropdown.
        # Clicking a chip toggles it (`.facet-chip.selected`/`aria-pressed=true`) and re-submits. Search
        # Thread S5b — the standalone Search surface's date-filter row (`[data-testid=filter-row]`, the
        # prior always-visible fallback subject) retired with the surface; the result row is the fallback
        # subject when a query has no facets (guaranteed present once _type_and_search has real hits).
        await _type_and_search(page)
        facet = page.locator('[data-testid="facet-row"] .facet-chip').first
        try:
            await facet.wait_for(state="visible", timeout=10_000)
            await facet.click()
            await page.locator('.facet-chip.selected, .facet-chip[aria-pressed="true"]').first.wait_for(
                state="visible", timeout=10_000
            )
        except Exception:
            await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=5_000)

    async def setup_inspector_open(page):
        # tempdoc 615 §6.1b: live Lit opens the inspector by clicking a result ROW (SearchSurface
        # setSelected -> inspectorState -> chrome inspector pane), not the retired filter-toggle +
        # per-row checkbox. The search-results dependency has already populated the rows.
        row = page.locator(S.CSS_SEARCH_RESULT_ROW).first
        await row.wait_for(state="visible", timeout=30_000)
        await row.click(force=True)
        await page.locator(S.CSS_INSPECTOR_PANE).first.wait_for(state="visible", timeout=10_000)

    async def setup_multi_select(page):
        # tempdoc 615 §6.1b: live multi-select is plain-click (replace) + Ctrl/Cmd-click (toggle) on
        # result rows (SearchSurface.handleClick), reflected on the row as `[data-selected="true"]` /
        # `.row.selected`. No bulk-action bar in V1; the selection publishes to the inspector.
        rows = page.locator(S.CSS_SEARCH_RESULT_ROW)
        await rows.first.wait_for(state="visible", timeout=30_000)
        await rows.nth(0).click()
        await rows.nth(1).click(modifiers=["Control"])
        await rows.nth(2).click(modifiers=["Control"])
        # At least two rows now carry the selected marker.
        await page.locator('[data-testid="search-result-row"][data-selected="true"]').nth(1).wait_for(
            state="visible", timeout=10_000
        )

    async def setup_context_menu(page):
        # tempdoc 615 §6.1b: right-click a result row -> openContextMenu mounts the <jf-context-menu>
        # element (role=menu); the retired per-row checkbox + summarize-testid flow is gone.
        first = page.locator(S.CSS_SEARCH_RESULT_ROW).first
        await first.wait_for(state="visible", timeout=30_000)
        await first.scroll_into_view_if_needed()
        await first.hover()
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await first.click(button="right", force=True, timeout=5_000)
        # The <jf-context-menu> host can read as hidden until positioned; wait for its rendered menu
        # items (role=menuitem) — that is the visible content.
        await page.locator('jf-context-menu [role="menuitem"], jf-context-menu .menu').first.wait_for(
            state="visible", timeout=10_000
        )

    async def setup_streaming(page):
        # tempdoc 615 §6.1b: drive the REAL AI Q&A (live `/api/chat/agent` SSE stream), not the retired
        # demo "Summarize" simulation. Switch to the inspector's Ask tab, ask a question, submit with
        # Ctrl+Enter — `sendQuestion()` flips to the Answer tab and streams into <jf-markdown-block>.
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        await pane.get_by_role("button", name="Ask", exact=True).click(timeout=10_000)
        ta = pane.get_by_placeholder("Ask a question about this file...")
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        # tempdoc 615 §6.2: a retrieval-grounded prompt forces the agent to run a SEARCH tool (so
        # `done` attaches `sources`) and to ground its sentences in those passages (so the embedding
        # matcher emits `citations`) — both are required for inline `.cite-ref` marks to render.
        await ta.fill(
            "Search the indexed documents and summarize what this file is about, "
            "citing the specific sources you used."
        )
        await page.keyboard.press("Control+Enter")
        # Mid-stream: the Answer tab shows the "Thinking…" state or the streaming markdown block.
        await pane.locator("jf-markdown-block, .empty").first.wait_for(state="visible", timeout=20_000)
        await asyncio.sleep(0.25)

    async def setup_summarize_done(page):
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        # Wait for the streamed answer to render. The markdown answer block replaces the "Thinking…"
        # placeholder once the first token arrives — that is the robust "answer started" signal. The
        # retrieval-grounded prompt runs a search-tool loop FIRST, so the answer can start late; allow a
        # very generous window (9B model + agent loop, possibly contended GPU).
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=280_000)

    async def setup_citation(page):
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        # tempdoc 615 §6.2: citations attach only on the `done` SSE (AFTER the stream completes) and only
        # when the answer grounded a source — `MarkdownBlock` renders `.cite-ref` marks only when
        # `!is-streaming` AND `citations` is non-empty. So wait for the markdown block to STOP streaming
        # (the `is-streaming` attribute clears), then for the mark (generous window for the 4s embedding
        # matcher); on success click it — it dispatches `citation-select`, routed to a preview-highlight.
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=180_000)
        try:
            await pane.locator("jf-markdown-block:not([is-streaming])").first.wait_for(
                state="visible", timeout=180_000
            )
        except Exception:
            pass
        cite = pane.locator(S.CSS_CITATION_HIGHLIGHT).first
        try:
            await cite.wait_for(state="visible", timeout=20_000)
            # Keep the inline `[n]` mark in frame on the Answer tab (the citation render is the subject).
            # Clicking it dispatches `citation-select` and navigates to the source preview — that
            # highlight is exercised by the live `citation-select` path, but the screenshot here shows
            # the grounded answer WITH its inline citation marks.
            await cite.scroll_into_view_if_needed(timeout=5_000)
            await asyncio.sleep(0.3)
        except Exception:
            # Not every answer grounds a citation; the answer-source chips are the fallback. Capture
            # the answered state regardless.
            pass

    async def setup_skeleton(page):
        await page.goto(_demo_url(ui_url, e2e_view_delay_ms="4000"), wait_until="domcontentloaded", timeout=timeout_ms)
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_LIBRARY)).click(timeout=5_000)
        await page.get_by_test_id(S.TID_SKELETON_LIBRARY).wait_for(state="visible", timeout=5_000)

    async def setup_snippets(page):
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        toggles = page.locator(f'[data-testid="{S.TID_RESULT_ROW_SNIPPET_TOGGLE}"]')
        try:
            await toggles.first.wait_for(state="visible", timeout=5_000)
            for i in range(min(2, await toggles.count())):
                await toggles.nth(i).click(force=True)
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
        except Exception:
            pass

    async def setup_zero_results(page):
        await _type_and_search(page)
        await page.get_by_test_id(S.TID_SEARCH_INPUT).fill("zzz_no_results_xyz")
        await page.get_by_text("No results for", exact=False).wait_for(state="visible", timeout=10_000)

    async def setup_selection_preserved(page):
        # tempdoc 615 §6.1b: live selection is a row CLICK (no per-row checkbox). Select the first row,
        # re-search, and confirm a selected row persists across the new query.
        await _type_and_search(page)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        await page.get_by_test_id(S.TID_SEARCH_INPUT).fill("justsearch")
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)

    # === Isolated steps (own browser each) ===

    def _view_setup(view_name: str, theme: str = "dark"):
        async def setup(page):
            # tempdoc 840 Phase 5: a view step may drill INTO a surface (advanced mode, the component
            # list, the consent dialog). Strip the drill-down suffix to recover the surface it lives on.
            base = view_name
            for suffix in ("-advanced", "-components", "-consent"):
                if base.endswith(suffix):
                    base = base[: -len(suffix)]
                    break
            surface_id = S.VIEWS.get(base)
            # tempdoc 615 §6.1b: the live Lit shell lands on the CHAT surface by default (not search,
            # as the retired React app did), so EVERY view step — including home/search — must navigate
            # to its rail surface first. Previously home/search were excluded on the stale assumption
            # that the app lands on search.
            if surface_id:
                # The bottom rail items (settings/help) sit under the first-run Walkthrough overlay
                # in demo mode, so a coordinate click hits the overlay, not the button. Dispatch the
                # click straight to the resolved button node — its `@click` handler navigates —
                # bypassing the overlay hit-test. Scroll first so the target is in the capture.
                # tempdoc 615 §6.1b: off-rail DEEPLINK surfaces (Health/Help) have no main-rail button,
                # so fall back to the shell's surface hash route (`#justsearch://surface/<id>`).
                try:
                    btn = page.locator(S.rail_css(surface_id))
                    await btn.scroll_into_view_if_needed(timeout=2_000)
                    await btn.dispatch_event("click")
                except Exception:
                    await page.evaluate(
                        "(id) => { location.hash = `justsearch://surface/${id}`; }", surface_id
                    )
                if surface_id in (S.RAIL_SURFACE_SETTINGS, S.RAIL_SURFACE_SECURITY):
                    # tempdoc 855: Settings is MODAL — the affordance opens the <jf-settings-window>
                    # dialog OVER the stage rather than swapping the stage surface, so wait on the
                    # open dialog + its mounted content instead of a stage mount.
                    #
                    # tempdoc 855 §5 item 1 — Security has no rail button any more (off-rail DEEPLINK,
                    # absorbed as a settings member category), so the `rail_css` click above always
                    # falls through to the hash-route except branch; the member→host alias redirect
                    # then opens the settings window at the Security category. Wait for that category
                    # to actually be the one mounted (not just the window/settings-surface shell).
                    await page.locator(S.CSS_SETTINGS_WINDOW_DIALOG).wait_for(
                        state="visible", timeout=10_000
                    )
                    if surface_id == S.RAIL_SURFACE_SECURITY:
                        await page.locator(S.CSS_SETTINGS_WINDOW_SECURITY_CONTENT).first.wait_for(
                            state="attached", timeout=10_000
                        )
                    else:
                        await page.locator(S.CSS_SETTINGS_WINDOW_CONTENT).first.wait_for(
                            state="attached", timeout=10_000
                        )
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
            if view_name == "ai-brain-advanced":
                b = page.get_by_test_id(S.TID_BRAIN_SWITCH_TO_ADVANCED)
                await b.wait_for(state="visible", timeout=10_000)
                await b.click(timeout=5_000)
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
            # tempdoc 840 Phase 5 — the per-component install list: what each piece of the ~7 GB is,
            # what it costs, and what you lose by declining it. Scroll it into the capture.
            #
            # KNOWN LIMITATION: on a profile that has not dismissed it, the first-run walkthrough
            # floats over the lower ~200px and occludes the last rows. Its dismissal lives in
            # UserStateDocument (not a storage key an init_script can set), and a click-through
            # attempt did not reach the button inside the card's shadow root. The required and
            # improves-results groups — what these steps exist to verify — are above the overlay and
            # capture cleanly; axe still reports 0 violations. Left as a limitation rather than a
            # swallowed exception that would look handled.
            if view_name == "ai-brain-components":
                lst = page.get_by_test_id(S.TID_INSTALL_COMPONENT_LIST)
                await lst.wait_for(state="visible", timeout=10_000)
                await lst.scroll_into_view_if_needed(timeout=5_000)
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
            # tempdoc 840 Phase 5 — the consent dialog the primary action opens (size, retained bytes,
            # per-package licence + terms). Reached by the Simple panel's primary action.
            if view_name == "ai-brain-consent":
                b = page.get_by_test_id(S.TID_BRAIN_SIMPLE_ACTION)
                await b.wait_for(state="visible", timeout=10_000)
                await b.click(timeout=5_000)
                await page.get_by_test_id(S.TID_INSTALL_CONSENT_DIALOG).wait_for(
                    state="visible", timeout=10_000
                )
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
        return setup

    async def _goto_surface(page, surface_id: str):
        """Navigate to a surface via its rail button, with the surface-hash route as fallback."""
        try:
            btn = page.locator(S.rail_css(surface_id))
            await btn.scroll_into_view_if_needed(timeout=2_000)
            await btn.dispatch_event("click")
        except Exception:
            await page.evaluate("(id) => { location.hash = `justsearch://surface/${id}`; }", surface_id)

    _DENSITY_LABEL = {"compact": "Compact", "comfort": "Comfortable",
                      "comfortable": "Comfortable", "rich": "Spacious"}
    _MODE_LABEL = {"simple": "Simple", "advanced": "Advanced"}

    def _density_setup(density: str):
        async def setup(page):
            # tempdoc 615 §6.1b: density is a LIVE Settings control (the Accessibility section's
            # `button.option-btn` Compact/Comfortable/Spacious -> applyAdaptationProfile, persisted
            # server-side), not the retired `__JUSTSEARCH_STORES__` global. Set it in Settings, then search.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _goto_surface(page, S.RAIL_SURFACE_SETTINGS)
            # Density lives in the Accessibility section as an `option-btn` (Compact/Comfortable/Spacious);
            # the cards carry sub-labels, so match by leading text on the button class, not the full name.
            label = _DENSITY_LABEL.get(density, "Comfortable")
            btn = page.locator("button.option-btn", has_text=label)
            await btn.first.wait_for(state="visible", timeout=10_000)
            await btn.first.click(timeout=10_000)
            if cooldown_ms > 0:
                await asyncio.sleep(cooldown_ms / 1000)
            await _goto_surface(page, S.RAIL_SURFACE_SEARCH)
            await _type_and_search(page)
        return setup

    def _mode_setup(mode: str):
        async def setup(page):
            # tempdoc 615 §6.1b: UI mode is the live Settings Simple/Advanced `option-btn` (persists via
            # `/api/settings/v2` `ui.mode`), not the retired store + filter toggle. Set it, then search.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _goto_surface(page, S.RAIL_SURFACE_SETTINGS)
            # The Simple/Advanced cards are `option-btn`s with sub-labels; match by leading text.
            btn = page.locator("button.option-btn", has_text=_MODE_LABEL.get(mode, "Simple"))
            await btn.first.wait_for(state="visible", timeout=10_000)
            await btn.first.click()
            if cooldown_ms > 0:
                await asyncio.sleep(cooldown_ms / 1000)
            await _goto_surface(page, S.RAIL_SURFACE_SEARCH)
            await _type_and_search(page)
        return setup

    def _cdp_setup(css_sel: str, pseudo: str):
        async def setup(page):
            await _type_and_search(page, "e")
            ctx = page.context
            cdp = await ctx.new_cdp_session(page)
            await cdp.send("DOM.enable")
            await cdp.send("CSS.enable")
            doc = await cdp.send("DOM.getDocument")
            r = await cdp.send("DOM.querySelector", {"nodeId": doc["root"]["nodeId"], "selector": css_sel})
            if r.get("nodeId", 0):
                await cdp.send("CSS.forcePseudoState", {"nodeId": r["nodeId"], "forcedPseudoClasses": [pseudo]})
                await asyncio.sleep(0.1)
        return setup

    def _inspector_setup(setup_fn):
        """Wrap an inspector setup to navigate + search + ensure AI tab."""
        async def setup(page):
            await setup_fn(page)
        return setup

    async def setup_qa(page):
        # tempdoc 615 §6.1b: live Q&A — open a result in the inspector (row click), ask a question on the
        # Ask tab, submit (Ctrl+Enter) and wait for the streamed answer (real `/api/chat/agent`).
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        await pane.first.wait_for(state="visible", timeout=10_000)
        await pane.get_by_role("button", name="Ask", exact=True).click(timeout=10_000)
        ta = pane.get_by_placeholder("Ask a question about this file...")
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.fill("What is this file about?")
        await page.keyboard.press("Control+Enter")
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=180_000)

    async def setup_chat_proportion(page):
        # Tempdoc 697 activation — the ONE capture where BOTH shrink-only-ratchet-tracked
        # chat-surface elements render together. Registered below with
        # `fixtures_variant="degraded"`, which turns FOUR knobs in ui_fixtures.py (all
        # LIVE-VERIFIED necessary via headless probing — the collapsed pill and the
        # submitted turn each turned out to need more than the one obvious flag):
        #   - `_status_body`: readiness.composites.retrieval -> DEGRADED with a real
        #     LifecycleReasonCode, so the pill (`.degradation-banner-collapsed`) has
        #     something to render; ALSO bumps indexedDocuments off zero, needed below.
        #   - `_inference_body`: reports the model ONLINE (`/api/inference/status` is
        #     otherwise unmapped -> capabilities.chat=false for every other step, correctly,
        #     since there is no dev stack under --fixtures).
        #   - `_settings_body`: flips `ui.mode` to "simple" — the captured default fixture
        #     is "advanced", which force-EXPANDS the banner regardless of severity
        #     (UnifiedChatView.ts:2123), so the pill never renders collapsed without this.
        # Together: capabilities.chat=true AND documents>0 un-pins Ask from plain search
        # (UnifiedChatView.askPinned() / availability.ts:104-143), so submitting a
        # "?"-bearing draft escalates to send() and pushes `.message.user`.
        #
        # Deliberately a NEW isolated step, not a `chat-mode` edit — `chat-mode` documents
        # chat-INPUT mode only (types `??`, never submits) and its own screenshot/a11y
        # baseline must stay undisturbed.
        #
        # CORRECTION vs the original brief: does NOT use `_type_and_search` / `S.SEARCH_INPUT`
        # / `S.TID_SEARCH_INPUT` / `S.CSS_SEARCH_INPUT_TEXTAREA`. Live-probed (headless
        # Playwright against this worktree's --fixtures capture): those all target a
        # `role="searchbox"` / `data-testid="search-input"` that tempdoc 687 ("the Search
        # Thread interaction model") retired when it consolidated search+chat onto the ONE
        # `<jf-composer>` — that composer's textarea carries neither attribute (role is the
        # bare-`<textarea>` default "textbox", no aria-label, no testid). This breaks
        # `_type_and_search` — and therefore `search-results` and every step chained off it —
        # under `--fixtures` in this worktree; logged as a pre-existing, cross-cutting harness
        # finding (out of THIS task's scope to fix broadly). `chat-proportion` instead drives
        # the rail nav directly and locates the composer via `S.CSS_COMPOSER_TEXTAREA`
        # (`jf-composer textarea`), confirmed live to resolve to exactly one element and to
        # drive a real search (fixture rows rendered).
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        # A "?" anywhere in the draft makes routeHeuristic.inferRoute() return 'ask'
        # (routeHeuristic.ts:75), so submitting escalates to UnifiedChatView.escalateAsk()
        # rather than a plain search.
        await ta.fill("?? What is this file about")
        # The composer's default submit-mode is 'enter' (Composer.ts constructor sets
        # `this.submitMode = 'enter'`; UnifiedChatView's <jf-composer> does not override it),
        # so plain Enter — NOT Ctrl+Enter — fires `composer-submit`. In the 'retrieve'
        # affordance that runs `runRoute(currentRoute())`; currentRoute() is 'ask' (the "?"
        # above), so this calls escalateAsk() -> send(), which pushes the turn SYNCHRONOUSLY
        # (UnifiedChatView.ts ~5189 `this.thread = [...this.thread, {role:'user',...}]`)
        # before any network response. Ctrl+Enter would instead flip to the OPPOSITE route
        # ('search', via submitSearch()) — which does not push a thread message — so it is
        # deliberately not used here despite being the gesture `setup_qa` (above) uses for
        # the unrelated inspector Ask textarea.
        await ta.press("Enter")
        await page.locator(S.CSS_MESSAGE_USER).first.wait_for(state="visible", timeout=15_000)
        await page.locator(S.CSS_DEGRADATION_BANNER_COLLAPSED).first.wait_for(
            state="visible", timeout=10_000
        )

    async def setup_chat_occlusion(page):
        # The ONE capture where both Sandbox round-7 layout defects are on screen together:
        #   (1) the RAG answer column starved by the document pane beside it, and
        #   (2) the advisory toast stack growing down over the chat header's control row.
        # Both are RELATIONS between elements, so they need a deterministic state where the
        # crowding surfaces are actually mounted — hence a dedicated isolated step rather
        # than an assertion bolted onto `chat-proportion` (whose own screenshot/a11y
        # baseline must stay undisturbed, same reasoning that step records).
        #
        # 1250x800: just OVER the 64rem (1024px) wide breakpoint AS THE CHAT SURFACE SEES IT.
        # Round 8 corrected the measurement this width was originally derived from: the
        # breakpoint is a `@container` query on the surface box, not a `@media` query on the
        # viewport, and the surface box is the viewport minus the Shell rail (11rem expanded)
        # minus the surface's own 1rem padding. At the old 1050 the surface got ~842px and the
        # wide grid no longer commits at all (the pane moves to the OverlayHost drawer, so
        # `.document-pane` would drop out of this capture entirely). 1250 puts the surface at
        # ~1042px — still the worst case the gate wants (every zone mounted, least room to fit
        # them), now computed against the box the tracks are actually laid out in.
        await page.set_viewport_size({"width": 1250, "height": 800})
        # Same rail-click + composer path `setup_chat_proportion` uses, and for the same
        # reason: `_type_and_search` / `S.SEARCH_INPUT` target a searchbox role + testid
        # that tempdoc 687 retired (see the ui_selectors.py note).
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        # A row click emits `card-open`, which funnels through the shared inspectorState
        # store; UnifiedChatView's ONE subscription derives `readingDocPath` from it and
        # mounts `<jf-document-pane class="document-pane">` in grid column 5.
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        await page.locator(S.CSS_DOCUMENT_PANE).first.wait_for(state="visible", timeout=15_000)
        # A toast burst through the one client-originated message channel (the
        # `jf-advisory-ephemeral` document event AdvisoryStore consumes — emitEphemeralToast's
        # transport). severity 'error' makes each toast sticky (messageClasses
        # presentationForSeverity), and supersede:false opts out of same-class single-occupancy,
        # so the stack is deterministic at capture time instead of racing a 5s auto-dismiss.
        #
        # EXACTLY the cap (MAX_VISIBLE_TOASTS), deliberately: at the cap there is no
        # `+N earlier` summary row, so the toast column sits at its HIGHEST possible
        # position — the worst case for occluding the header band. Over the cap the
        # summary row displaces the column ~31px downward, which would let a re-broken
        # dock offset pass this assertion for an incidental reason (verified: with the
        # dock reverted and an 8-toast burst the residual intersection was 1px, inside
        # tolerance, so the gate reported clean). Stack BOUNDING is asserted at the unit
        # level (AdvisoryToastHost.test.ts); this capture asserts the OCCLUSION relation.
        await page.evaluate(
            """(n) => {
                for (let i = 0; i < n; i += 1) {
                    document.dispatchEvent(new CustomEvent('jf-advisory-ephemeral', {
                        detail: {
                            message: `Occlusion probe toast ${i + 1}`,
                            severity: 'error',
                            supersede: false,
                        },
                        bubbles: true,
                    }));
                }
            }""",
            3,
        )
        await page.locator(S.CSS_TOAST).first.wait_for(state="visible", timeout=10_000)
        await asyncio.sleep(0.3)  # let the toast enter-animation settle before measuring

    async def setup_sv3_composer_occlusion(page):
        # Tempdoc 859 §B — the measured half of "the composer floats over the transcript".
        #
        # The two existing sv3 steps are live-stack-only because a v3 turn's evidence used to be
        # reachable only through a real ask. This one is DETERMINISTIC: the `sv3-sources` fixtures
        # variant serves a `/api/thread/{id}` record whose last assistant message carries populated
        # `attributes.citations`, and seeds the per-tab lastViewedConversation pointer — which is
        # what makes a cold Search v3 window restore that thread, land in the DOCKED composer state
        # and render a turn whose Sources disclosure can actually be opened.
        #
        # 1280x800 pinned: the baseline's `maxBottomPx` on the dock is a statement about a known
        # viewport, and the transcript must overflow it (hence the deliberately long fixture
        # answers) or `minScrollableRegions: 1` witnesses nothing.
        await page.set_viewport_size({"width": 1280, "height": 800})
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        # Hidden DEEPLINK surface, dev audience, no rail entry — the hash route is the only way in.
        await page.evaluate(
            "() => { location.hash = 'justsearch://surface/core.search-v3-surface'; }"
        )
        await page.locator("jf-sv3-window").first.wait_for(state="visible", timeout=20_000)
        # The restored transcript, not merely the window: an empty window would capture the HERO
        # state, where the composer legitimately owns the whole column and there is no occlusion
        # relation to measure at all.
        await page.locator('[data-testid="sv3-turn-sources"]').first.wait_for(
            state="visible", timeout=20_000
        )
        # Open the disclosure — §7's reported defect is about the panel BELOW the answer, so the
        # capture has to contain it. Its mount also grows the transcript, which is what pushes the
        # last rendered element into the band the dock occupies if the padding is wrong.
        await page.locator('[data-testid="sv3-turn-sources"]').first.click(timeout=15_000)
        await page.locator('[data-testid="sv3-turn-citations"]').first.wait_for(
            state="visible", timeout=15_000
        )
        # Measure at MAX SCROLL. At rest the last element is nowhere near the dock and the overlap
        # row would pass for a reason unrelated to the defect; the assertion only means something
        # where "scrolled to the end" is claimed to mean "the last line is above the glass".
        await page.evaluate(
            """() => {
                const w = document.querySelector('jf-sv3-window');
                const m = w && w.shadowRoot && w.shadowRoot.querySelector('jf-sv3-main');
                const s = m && m.shadowRoot && m.shadowRoot.querySelector('.scroller');
                if (s) s.scrollTop = s.scrollHeight;
            }"""
        )
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def setup_tasks_occlusion(page):
        # Tempdoc 813 Slice D — the capture that proves the redesigned Tasks panel cannot cover the
        # rail's bottom controls. Registered in governance/ui-proportion-baseline.v1.json with TWO
        # mustNotOverlapSelector rows (Settings + the Help affordance) AND a `minWidthPx` floor on
        # `jf-task-list` itself: a hidden panel yields a 0x0 rect that satisfies any overlap check
        # vacuously (ui_proportion_gate._rects_overlap), so the non-vacuity companion is what makes
        # the two occlusion rows mean anything.
        #
        # `fixtures_variant="indexing"` is the whole reason this is a dedicated isolated step. Under
        # the default fixture the worker reports indexState "SERVING" — a fallback state the progress
        # projection deliberately refuses to read (indexingProgress.WORKER_REPORTED_INDEX_STATES) —
        # so the panel is correctly invisible on every other step. The `indexing` variant supplies a
        # live INDEXING worker with a real backlog (ui_fixtures._status_body), which is what puts the
        # aggregate card on screen.
        #
        # Note the panel is driven by the POLL projection, NOT the jobs SSE: install_fixtures serves
        # every `/stream` as an empty event stream, so a task-list-gated panel would never render
        # here. That is exactly the §1d defect Slice D removed (the panel used to hide at zero tasks
        # even while enrichment ran), and this step only captures anything BECAUSE it was removed.
        await page.set_viewport_size({"width": 1280, "height": 800})
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        # Land on a plain rail surface rather than the default chat landing: the assertion is about
        # the SHELL's bottom-left corner versus the rail, so the least surface-specific screen is the
        # least likely to drift for reasons unrelated to the relation being measured.
        try:
            btn = page.locator(S.rail_css(S.RAIL_SURFACE_LIBRARY))
            await btn.wait_for(state="visible", timeout=15_000)
            await btn.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "(id) => { location.hash = `justsearch://surface/${id}`; }", S.RAIL_SURFACE_LIBRARY
            )
        # Both sides of the relation must actually be on screen before measuring.
        await page.locator(S.rail_css(S.RAIL_SURFACE_SETTINGS)).first.wait_for(
            state="visible", timeout=15_000
        )
        await page.locator("[data-help-affordance]").first.wait_for(state="visible", timeout=15_000)
        # The aggregate card, not merely the host element: `jf-task-list` exists in the DOM whenever
        # the shell is mounted (it self-hides via [data-empty]), so waiting on the host would wait on
        # nothing. The card is only rendered on a phase the projection can actually speak to.
        await page.locator('[data-testid="task-aggregate"]').first.wait_for(
            state="visible", timeout=15_000
        )
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def setup_library_enriching(page):
        # Tempdoc 813 §4 (Library folder rows) + §5 (the Tasks panel aggregate card) — the ONLY
        # deterministic render of the ENRICHING tier anywhere in the harness. No other fixture
        # reaches it: `default` reports the fallback "SERVING" state the progress projection refuses
        # to read, and `indexing` reports a non-empty job backlog, which wins the phase ternary
        # (indexingProgress.ts:333-334) — so `enriching` is unreachable from both by construction.
        #
        # The `enriching` variant is the only one that transforms BOTH halves the tier needs
        # (ui_fixtures): `/api/status` supplies the index-wide phase + the stage-applicability flags
        # the per-root row cannot carry, and `/api/indexing-roots/substrate` supplies two rows that
        # differ only in coverage — one mid-flight (the caveat + this root's percent) and one fully
        # settled ("fully searchable"). Capturing both arms together is the point: the honest claim
        # is per-root, so a settled folder must read terminal WHILE the index-wide backfill runs.
        await page.set_viewport_size({"width": 1280, "height": 800})
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        try:
            btn = page.locator(S.rail_css(S.RAIL_SURFACE_LIBRARY))
            await btn.wait_for(state="visible", timeout=15_000)
            await btn.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "(id) => { location.hash = `justsearch://surface/${id}`; }", S.RAIL_SURFACE_LIBRARY
            )
        # BOTH rows, not just the first: a one-row wait would pass on a truncated list and the
        # capture would silently be about a single arm. `.card-meta` (the meta LINE, which is what
        # the tier is about) rather than a testid: the row is rendered by the DECLARED path
        # (FolderCardRenderer, active by default via LIBRARY_CARDS_REGION) whose markup carries no
        # testid — `library-folder-name` exists only on LibrarySurface's own quarantine fallback, so
        # waiting on it would time out on the path that actually renders here. Both renderers project
        # the same `folderStatus` meta text and both use this class.
        await page.locator(".card-meta").nth(1).wait_for(state="visible", timeout=15_000)
        # The aggregate card (not the `jf-task-list` host, which exists whenever the shell is mounted
        # and self-hides via [data-empty]) — the same non-vacuity reasoning as `tasks-occlusion`.
        await page.locator('[data-testid="task-aggregate"]').first.wait_for(
            state="visible", timeout=15_000
        )
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def setup_chat_bands(page):
        # Tempdoc 814 §D7.2 — the ONE capture where every persistent chat-surface band
        # renders at once (the finding-12 screen): the degraded readiness pill, a
        # submitted turn, and the agent-mode activity-rail chrome, all under --fixtures
        # at the pinned 1366x768 viewport.
        #
        # Reuses `setup_chat_proportion`'s exact degraded-fixtures + rail-click +
        # composer-submit recipe (same reasoning applies here: `_type_and_search` /
        # `S.SEARCH_INPUT` target a retired searchbox role/testid — see that step's
        # docstring), then ADDS one action: click the "Delegate — the agent works
        # multi-step" escalation rung (`S.CSS_ESCALATION_DELEGATE`, which flips
        # `this.affordance = 'agent'` at UnifiedChatView.ts:2648) to mount `.activity-rail`.
        #
        # FIXTURE-REACHABILITY FINDING (814 W4 investigation — recorded per the tempdoc's
        # own instruction not to silently cap the state reached): the activity rail's BODY
        # rows (`.activity-budget` / `.activity-lifecycle` — budget consumed, turn/tool
        # counts) need a REAL agent run. That routes through
        # `AgentSessionController.send()` -> `dispatchRunControl({kind:'initiate'})` -> a
        # POST to `/api/chat/agent` whose RESPONSE IS an SSE stream consumed by the typed
        # `consumeShapeStream` protocol (modules/ui-web/src/api/streams.ts, a
        # `StreamEventV1` discriminated union with budget/lifecycle/done event shapes).
        # `install_fixtures` stubs EVERY `/stream`-ish request (or `text/event-stream`
        # accept header) with an immediately-closed EMPTY body — reproducing a valid
        # event sequence would mean hand-authoring that typed wire protocol inside
        # `ui_fixtures.py`, well past a "modest route addition" and outside this task's
        # ~1h investigation budget; NOT attempted, to avoid destabilizing every other
        # fixture-backed step sharing `install_fixtures`. What IS fixture-reachable, and
        # what this step exercises instead: the rail's COLLAPSED SUMMARY renders from
        # `affordance === 'agent'` ALONE — UnifiedChatView.ts's own tempdoc-561-C-2
        # comment: "in agent mode the rail (action-plane chrome) is always present,
        # naming the approval posture in its summary — even before a run reports
        # budget[/lifecycle]" — so clicking Delegate (no submit, no network call) mounts
        # the REAL `.activity-rail` element with its real `<summary>` band; only the two
        # budget/lifecycle BODY rows stay unreachable under --fixtures. This is the
        # "activity-rail summary … (whichever renders)" band tempdoc 814 §D7.2
        # anticipated, and a 40px ceiling (this step's baseline registration) is
        # generous for a collapsed `<details><summary>` alone.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        await ta.fill("?? What is this file about")
        await ta.press("Enter")
        await page.locator(S.CSS_MESSAGE_USER).first.wait_for(state="visible", timeout=15_000)
        await page.locator(S.CSS_DEGRADATION_BANNER_COLLAPSED).first.wait_for(
            state="visible", timeout=10_000
        )
        # Mount the activity-rail band WITHOUT a real agent run (see the fixture-
        # reachability finding above) — a plain affordance toggle, availability-gated on
        # `aiState.capabilities.chat` (true under the `degraded` fixtures variant's
        # `_inference_body` transform, same as the ask escalation just above).
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.click(force=True)
        await page.locator(S.CSS_ACTIVITY_RAIL).first.wait_for(state="visible", timeout=10_000)
        await asyncio.sleep(0.2)

    # The overflowing draft `setup_chat_bands_detailed` submits. Long ENOUGH that the one
    # rendered user bubble exceeds the conversation zone at 1366x900 with the expanded
    # banner in flow — the step's `minScrollableRegions: 1` is what keeps that true (see
    # the setup's docstring for why a scroller has to be witnessed, not merely bounded).
    _OVERFLOWING_ASK = "?? " + " ".join(
        f"Part {i}: what does this file say about indexing, retrieval, ranking and "
        "enrichment, and how does the worker hand its results back to the head process "
        "so I can verify the whole path end to end?"
        for i in range(1, 25)
    )

    async def setup_chat_bands_detailed(page):
        # Tempdoc 814 closure (audit findings A + C) — the DETAILED-disclosure sibling of
        # `chat-bands`, and the only capture where the EXPANDED degradation banner exists.
        #
        # WHY A SECOND STEP, not a knob on `chat-bands`: the two disclosure modes are
        # different height regimes, and §D1 states a different floor for each (>= 0.55 in
        # Simple, >= 0.45 in Detailed — "Detailed legitimately spends more, but bounded").
        # `chat-bands` registers the Simple pill's 42px ceiling and the 0.55 share; the
        # expanded banner had NO registered ceiling at all, so the Detailed floor was
        # prose-only. One step per regime keeps each screenshot/a11y baseline undisturbed
        # (the same reasoning `chat-proportion` records for not editing `chat-mode`).
        #
        # 1366x900, not the 1366x768 design basis: 900 is ABOVE the block-axis breakpoint
        # (SHORT_VIEWPORT_MAX_HEIGHT_PX = 820, primitives/compositionLayout.ts), so W1's own
        # gate — `forcedExpanded = severity === 'error' || (isAdvancedMode() && !shortZone)`
        # — actually lets Detailed expand. At 768 the same Detailed state renders the pill
        # first (that IS W1's behaviour, and `chat-bands` no longer distinguishes it), so a
        # 768 capture would register a ceiling for a banner that never expands: a vacuous
        # row. The width stays 1366 so the wide grid and the band set match `chat-bands`.
        #
        # DETAILED-MODE MECHANISM: the `degraded-detailed` fixtures variant. `uiModeState`
        # is seeded at boot from `/api/settings/v2` (themeState.restoreAppearanceOnBoot ->
        # setUiMode(data.ui.mode)), and the captured `settings-v2-live.json` fixture already
        # carries `ui.mode: "advanced"` — so the variant's job is to NOT do what `degraded`
        # does (flip it to "simple"), while keeping that variant's other two transforms
        # (`_status_body`'s DEGRADED retrieval verdict, `_inference_body`'s ONLINE model).
        # Driving the topbar Simple|Detailed control instead would work but adds a second
        # authority for the same fact to the capture; the settings seed is the one the app
        # actually boots from.
        await page.set_viewport_size({"width": 1366, "height": 900})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        # ONE long "?"-bearing draft rather than N short turns: `send()` pushes the user turn
        # synchronously, but each submit also flips `isStreaming` (cleared only when the
        # stubbed, immediately-closed SSE response drains), so a submit loop races that flag
        # and silently drops turns. One overflowing bubble is deterministic.
        await ta.fill(_OVERFLOWING_ASK)
        await ta.press("Enter")
        await page.locator(S.CSS_MESSAGE_USER).first.wait_for(state="visible", timeout=15_000)
        # The EXPANDED form is the assertion this step exists for, so wait on the element that
        # ONLY the expanded branch renders (the worded cause list) — not on `.degradation-banner`,
        # which the collapsed pill also carries (`class="degradation-banner
        # degradation-banner-collapsed"`). A Detailed regression would otherwise capture the pill
        # under the expanded banner's registered ceiling and read as a (very comfortable) pass.
        await page.locator(S.CSS_DEGRADATION_CAUSES).first.wait_for(
            state="visible", timeout=10_000
        )
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.click(force=True)
        await page.locator(S.CSS_ACTIVITY_RAIL).first.wait_for(state="visible", timeout=10_000)
        # EVIDENCE-RAIL REACHABILITY (audit finding C.3, investigated and NOT faked): the docked
        # `.evidence-rail` needs `agentCtrl.answerSources.length > 0` (UnifiedChatView.
        # evidenceRailMounted), and only two things write that field — a real agent SSE `done`
        # payload (the typed protocol `install_fixtures` stubs empty, as `setup_chat_bands`
        # records) or `hydrateAnswerEvidenceFromRecord` off a `/api/thread` record, which is
        # reached only from `refreshUnifiedThread` and no-ops while `agentCtrl` is still null —
        # i.e. it would need an affordance round-trip (retrieve -> agent -> retrieve -> submit ->
        # agent) plus a hand-authored thread fixture. Left unreached; the rail's no-scroll
        # obligation stays asserted by `maxScrollableRegions: 1` the moment it does mount.
        await asyncio.sleep(0.3)

    async def setup_chat_composer_small(page):
        # Tempdoc 814 §D6/§D7.2 — the F5 close: the small-viewport docked-composer step
        # 807 flagged as a coverage gap ("no ui-shot covers the docked composer at a small
        # viewport, which is where round 8's F5 layout defect lived"). Copies
        # `chat-occlusion`'s exact search -> open-document-pane pattern (same reasoning
        # applies: the retired searchbox role/testid), pinned at 1366x768 (this task's
        # pinned viewport — chat-occlusion's own 1250x800 targets its wide-breakpoint
        # worst case, a different probe), then adds the ONE new action: clear the
        # composer draft with the preview still open — tempdoc 734's exact repro
        # (clearing results with the document pane open used to clip the composer below
        # the viewport, because the pane's 24rem min-height floor + fixed chrome exceeded
        # a short window and the composer, bottom of the flex column, paid). D6 closes
        # it: the pane's floor yields below the height breakpoint.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        await page.locator(S.CSS_DOCUMENT_PANE).first.wait_for(state="visible", timeout=15_000)
        # tempdoc 734's F5 repro: clear the draft (and therefore the results — every
        # keystroke feeds `setSearchQuery` via `@composer-input`, UnifiedChatView.ts
        # ~2707-2711) with the document pane still open.
        await ta.fill("")
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="hidden", timeout=10_000)
        await asyncio.sleep(0.3)

    async def setup_chat_wide_docked(page):
        # Tempdoc 816 §5 — the INLINE-axis camera: the docked chat surface at a WIDE viewport.
        #
        # WHY 1920x900, and why a NEW step. Every existing chat camera is pinned at 1250-1366
        # (814's design basis), and full-bleed stretching is least visible exactly there: the
        # defect this step registers GROWS with monitor width, so the instrument set had a
        # structural blind spot rather than a missing assertion. Measured here before the fix,
        # at 1920: the docked `.composer` and `.escalation-strip-docked` spanned the whole
        # 1836px surface and the composer's textarea ran 1760px = 251 characters per line,
        # while `.conversation` beside them was already bound and centred at 800px. 1920 stays
        # under the 2000px screenshot cap at 1x DPI (the ui-check DPI limitation), so no
        # capture-side compromise is needed.
        #
        # The state is reached the way `chat-proportion` reaches it — rail click, a real search,
        # then a "?"-bearing draft submitted with plain Enter, which routes through
        # `escalateAsk()` -> `send()` and pushes the turn synchronously. `degraded` is the
        # fixtures variant for the same reasons that step records: the collapsed pill needs a
        # degraded verdict AND `ui.mode: simple`, and `capabilities.chat` has to be true for the
        # ask to escalate at all. Both waits below are the observed conditions that say the
        # DOCKED state was actually reached: a rendered user turn (the composer only docks once
        # the landing is gone) and the banner whose content box this step bounds.
        await page.set_viewport_size({"width": 1920, "height": 900})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        await ta.fill("?? What is this file about")
        await ta.press("Enter")
        await page.locator(S.CSS_MESSAGE_USER).first.wait_for(state="visible", timeout=15_000)
        await page.locator(S.CSS_DEGRADATION_BANNER_COLLAPSED).first.wait_for(
            state="visible", timeout=10_000
        )
        await asyncio.sleep(0.3)

    async def setup_chat_chip_yield(page):
        # Tempdoc 814 §D5 (review pass 2026-08-06) — the CAPTURE-level witness for the chip
        # yield. `chat-bands` cannot be it: it submits an ask, and under `--fixtures` the
        # stubbed SSE never drains, so `aiState.activity` stays 'thinking' and the status
        # chip reads "Thinking…" whether the yield works or not (measured: "Thinking" 1,
        # "Service degraded" 0 — a green for the WRONG reason). This step reaches the same
        # degraded chat surface with NO activity overlay by simply not submitting: the
        # banner is chrome, not a function of the thread, so navigating to the surface is
        # the whole recipe. With activity idle the chip's label IS the verdict projection —
        # so the register's `forbiddenVisibleText: ["Service degraded"]` discriminates:
        # yield working -> the neutral AI-mode readout (0 renders); yield regressed ->
        # `verdictHeadline(degraded)` back in the bar (1 render, gate red).
        #
        # NON-VACUITY: the assertion is only meaningful while the BANNER owns the fact, so
        # the step also registers `requiredSelectors: [".degradation-banner-collapsed"]` —
        # otherwise a capture that failed to render the surface at all would show neither
        # string and read as a pass.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        await page.locator(S.CSS_DEGRADATION_BANNER_COLLAPSED).first.wait_for(
            state="visible", timeout=15_000
        )
        await asyncio.sleep(0.3)

    async def setup_chat_spine_multi(page):
        # Tempdoc 814 §D7.2 — the POSITIVE half of the spine pair, deferred by W4 and landed
        # in the review pass. `spineItems()` (UnifiedChatView.ts ~3151) mounts the spine on
        # `affordance === 'agent'` AND `wideZone` AND (>= 2 user turns OR >= 2 distinct
        # workflow nodeIds). Node boundaries need a real agent SSE run (unreachable under
        # `--fixtures`, see `setup_chat_bands`), so this step takes the TURNS branch.
        #
        # WHY IT IS A RECORD FIXTURE, not two submits (measured, not assumed — the prior
        # attempt's dead end): `spineItems()` reads `mergedTimeline()`, which merges the
        # canonical RECORD (`projectUnifiedThread(this.unifiedEvents)`, fetched from
        # `/api/thread/{id}`) with the live agent overlay — it never reads `this.thread`, the
        # array plain ask-submits push into. A two-submit capture therefore measured
        # `users: 2, affordance: 'agent', wideZone: true, spine: 0`: the turns were on screen
        # and the spine's own input was still empty. (`send()` also early-returns while
        # `isStreaming`, which the stubbed SSE never clears — a second, independent reason
        # submits are the wrong lever here.) The `degraded-thread` fixtures variant supplies
        # the record instead: two user turns + their answers, auto-loaded on connect via the
        # seeded per-tab `lastViewedConversation` pointer (ui_fixtures._thread_body).
        #
        # DIVISION OF LABOUR (recorded so the step is not read as asserting more than it
        # does): the CAPTURE witnesses spine PRESENCE on a multi-turn conversation — the
        # regression the pair exists for, against `chat-spine-single`'s absence assertion.
        # "Marker count == segment count" stays UNIT-tier (adaptiveSpacing / UnifiedChatView
        # tests): it needs the segmented nodeIds only a real agent SSE run produces.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        # `affordance = 'agent'` FIRST, and not only because it is the spine's first gate: the
        # base `retrieve` tier renders the ephemeral hit-list in the conversation column and
        # owns no thread history (renderAnswerPlane ~2590), so the record's turns are not on
        # screen at all until the affordance is promoted. The Delegate rung is the only
        # fixture-reachable way to flip it (the same finding `chat-bands` records).
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.wait_for(
            state="visible", timeout=15_000
        )
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.click(force=True)
        # The record's two turns must be ON SCREEN before the capture — an observed condition,
        # not a sleep, so a half-loaded timeline cannot be photographed as a full one.
        await _await_turn_count(page, 2)
        await page.locator(S.CSS_RUN_SPINE).first.wait_for(state="visible", timeout=10_000)
        await asyncio.sleep(0.3)

    async def setup_chat_spine_single(page):
        # Tempdoc 814 §D7.2 (Lane 2's home, kept alongside the deferred segmented-spine
        # sibling so the pair travels together; also Round-14 finding 15's regression
        # home): a single-turn conversation must NOT mount the run-spine. `spineItems()`
        # (UnifiedChatView.ts ~3040) returns null unless `affordance === 'agent'` AND
        # `wideZone` AND the merged timeline has more than one turn (or real workflow-node
        # boundaries) — so this drives affordance into 'agent' (the only fixture-reachable
        # way to satisfy the first two thirds of that predicate — see `setup_chat_bands`'s
        # fixture-reachability finding) on top of a single already-submitted ask turn, and
        # asserts the negative: still no `.run-spine`, registered via the gate's new
        # `absentSelectors` step-level check.
        #
        # DEFERRED (this task's brief): a genuine multi-turn SEGMENTED agent run (the
        # sibling assertion tempdoc 814 also names — "a segmented-run spine step asserting
        # marker count == segment count") needs the same real SSE agent-run protocol
        # `chat-bands` found unreachable under --fixtures; left to integration once
        # another worker's DOM changes land, not attempted here.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.type("justsearch", delay=20)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)
        await ta.fill("?? What is this file about")
        await ta.press("Enter")
        await page.locator(S.CSS_MESSAGE_USER).first.wait_for(state="visible", timeout=15_000)
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.click(force=True)
        # No wait_for on `.run-spine` — its absence IS the assertion this step exists to
        # capture. A short settle instead, so the affordance flip's re-render has landed.
        await asyncio.sleep(0.3)

    async def _drive_agent_run_to_done(page):
        # Tempdoc 814 §D8 — the ONE recipe that reaches a COMPLETED agent run under `--fixtures`,
        # shared by the two steps below so they cannot drift into two different "same" states.
        #
        # Order is load-bearing at every step:
        #  1. Delegate FIRST. `escalateAsk()` (the "??"-draft Enter path the older chat steps use)
        #     re-derives the affordance from route 'ask', which would demote agent mode — so the
        #     agent branch of `send()` is reachable only from an ALREADY-agent affordance, where
        #     `handleComposerSubmit` calls `this.send()` directly (UnifiedChatView.ts ~2860).
        #     Clicking Delegate also creates the hosted controller (`ensureAgentCtrl` on the next
        #     render), which the record-hydration in step 4 needs to exist.
        #  2. The submit streams the `agent-run` variant's DONE body from /api/chat/dispatch
        #     (ui_fixtures._handler). `ctrl.available` must already be true or `send()` returns
        #     silently — that is what the variant's `/api/chat/agent/tools` body supplies.
        #  3. `.activity-lifecycle` is the observed condition for "the record is in", not a sleep.
        #  4. The evidence rail arrives LAST and from the RECORD: `send()` resolves after the whole
        #     stream drains, and only then does `.then(() => refreshUnifiedThread())` fire
        #     `hydrateAnswerEvidenceFromRecord`, which is what sets `answerSources` (the DONE frame
        #     deliberately carries none — see agent_stream_fixture.DONE_RUN). Waiting on the rail
        #     therefore witnesses the full record round-trip, not just a rendered frame.
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=15_000
        )
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
        except Exception:
            await page.evaluate(
                "() => { location.hash = 'justsearch://surface/core.unified-chat-surface'; }"
            )
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.wait_for(state="visible", timeout=15_000)
        await page.locator(S.CSS_ESCALATION_DELEGATE).first.click(force=True)
        await page.locator(S.CSS_ACTIVITY_RAIL).first.wait_for(state="visible", timeout=10_000)
        ta = page.locator(S.CSS_COMPOSER_TEXTAREA)
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.fill("How does indexing reach the head process?")
        await ta.press("Enter")
        await page.locator(S.CSS_ACTIVITY_LIFECYCLE).first.wait_for(state="attached", timeout=20_000)
        await page.locator(S.CSS_EVIDENCE_RAIL).first.wait_for(state="visible", timeout=20_000)

    async def setup_chat_evidence_rail(page):
        # Tempdoc 814 §D8.1 — the RECORD-path capture: the docked evidence rail on screen at the
        # pinned 1366x768 viewport, which §V residual 1 recorded as fixture-unreachable ("the rail
        # additionally an affordance round-trip"). What this step registers, and why each row is
        # not vacuous on its own:
        #   - `requiredSelectors: ['.evidence-rail']` — the rail MOUNTED. Every other row below is
        #     about the rail, so without this they would all pass on a capture where it never
        #     rendered.
        #   - `nonScrollableSelectors: ['.evidence-rail']` — §D3's DIRECT witness. The surface-wide
        #     `maxScrollableRegions: 1` cannot say WHICH element may scroll: a regression where
        #     `.conversation` stopped scrolling and the rail started would still count 1.
        #   - `absentSelectors: ['.sources-affordance']` — §D5's source-count single authority, on
        #     camera: while the rail owns the count the in-answer "Sources · N" chip must not
        #     render at all (not merely be CSS-hidden — the review pass's own correction).
        #   - the `.conversation-zone` share floor — §D1 still holds WITH the rail in the grid
        #     (the rail is a column, so a share regression here would mean the rail cost height).
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _drive_agent_run_to_done(page)
        await asyncio.sleep(0.3)

    async def setup_chat_activity_rail_open(page):
        # Tempdoc 814 §D8.2 — the EXPANDED activity-rail body, the other half of §V residual 1.
        # Same completed run, plus one action: open the `<details>`. The three body rows only
        # exist after a real run reports them, which is exactly what the SSE fixture supplies —
        # `.activity-budget` + `.activity-context` come from the stream's single `budget_update`
        # (the context meter needs promptTokens AND contextWindow > 0, else
        # `projectContextHorizon` returns null and the meter silently does not render), and
        # `.activity-lifecycle` from the record's DONE lifecycle.
        #
        # The assertion this step exists for is §D2's BOUNDED EXPANSION: the conversation zone
        # keeps its share floor WITH the expanded body in flow. The closure audit recorded that
        # half as untested precisely because no capture could open a populated rail.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _drive_agent_run_to_done(page)
        # Click the summary rather than setting `open` in JS: the `<details>` binds `?open` to
        # `activityRailExpanded` and records the toggle, so a JS poke would be re-closed by the
        # next render (the state, not the attribute, is the authority — 814 finding 12(a)).
        await page.locator(S.CSS_ACTIVITY_RAIL_SUMMARY).first.click()
        await page.locator(S.CSS_ACTIVITY_BUDGET).first.wait_for(state="visible", timeout=10_000)
        await page.locator(S.CSS_ACTIVITY_CONTEXT).first.wait_for(state="visible", timeout=10_000)
        await asyncio.sleep(0.3)

    async def setup_sv3_citation_selected(page):
        # Tempdoc 822 citation-mark presentation §7.1 — THE STATE NOTHING COULD ENTER.
        #
        # F1/F2 survived eleven slices for one structural reason: no step in this harness ever
        # SELECTED a citation, so no capture, no measurement and no audit could look at the selected
        # mark. (§3: zero tests asserted `.cite-selected`, the fit audit's five measured states
        # contain no selected-citation state, and the inventory has no sv3 entry at all.) This step
        # closes that: it drives the Search v3 window to a grounded answer, opens the Sources
        # disclosure and clicks a source CARD — the far side of the selection — then asserts what the
        # two surfaces render.
        #
        # LIVE-STACK, NOT `--fixtures`, and this is a property of the window rather than a shortcut
        # not taken: a v3 turn's `evidence` is the shared resolver's output over the LIVE stream's
        # claims and is `null` on every record path (`views/search-v3/sv3-record.ts:101-104`), so no
        # thread fixture can mount the panel. Like the `citation-highlight` chain it therefore needs
        # the dev stack + `ai_activate`, and it is deliberately NOT registered in
        # `governance/ui-proportion-baseline.v1.json` — that gate captures under `--fixtures` with no
        # backend, where this step cannot reach its state and would report a capture ERROR.
        #
        # The three assertions below are the regression this slice exists to prevent, evaluated
        # against COMPUTED style in a real engine (the cascade order was the defect, and no
        # source-level check can see cascade order):
        #   1. the selected mark carries a real fill AND `aria-current="true"` (F6 — the state was
        #      visual-only);
        #   2. its `color` is UNCHANGED by selection (F2 — the headline: `.cite-selected` used to set
        #      `color` at the same specificity as `.cite-weak` / `.cite-ungrounded` and later in
        #      source, so clicking the amber "not supported" numeral hid that it was unsupported);
        #   3. the sentence region the source grounds is tinted (§5.3 — the payload, not the handle).
        # Each is a condition-poll that RAISES on timeout, the same mechanism `_await_turn_count`
        # uses, so a wrong render fails the step instead of being photographed as a right one.
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        # Hidden DEEPLINK surface, dev audience, no rail entry — the hash route is the only way in.
        await page.evaluate(
            "() => { location.hash = 'justsearch://surface/core.search-v3-surface'; }"
        )
        await page.locator("jf-sv3-window").first.wait_for(state="visible", timeout=20_000)
        ta = page.locator('[data-testid="sv3-composer-input"]').first
        await ta.wait_for(state="visible", timeout=15_000)
        await ta.click()
        # The same retrieval-grounded prompt shape `setup_streaming` uses: the answer must run a
        # search tool (so the turn gets sources) AND ground its sentences in those passages (so the
        # matcher emits citations) — both are required before a single `.cite-ref` mark renders.
        await ta.fill(
            "Search the indexed documents and summarize how indexing reaches the head process, "
            "citing the specific sources you used."
        )
        await ta.press("Enter")
        # The marks attach only after the stream completes and the matcher has spoken; the 9B model
        # plus an agent loop on a possibly-contended GPU is the reason for the wide window.
        await page.locator(".cite-ref").first.wait_for(state="visible", timeout=300_000)
        # Choose the mark to drive from, preferring a SUBDUED tier: the wash is painted behind the
        # numeral, so the weak/ungrounded inks are the ones whose legibility the state can cost, and
        # a run that exercised only a normal blue mark would watch the wrong thing.
        pick = await page.evaluate(_JS_PICK_CITE_MARK)
        if not pick or not pick.get("key"):
            raise RuntimeError(
                "sv3-citation-selected: no .cite-ref mark carrying a data-cite-key to select from"
            )
        if pick["tier"] == "grounded":
            # Said out loud rather than passed over: this run's answer was fully grounded, so the
            # capture cannot show the low-tier state and the axe pass over it proves nothing about
            # the subdued inks. The unit contrast matrix (Sv3Main.imports.test.ts) still covers them.
            print(
                "sv3-citation-selected: WARNING — no weak/ungrounded mark in this answer "
                f"({pick['total']} mark(s), all grounded); the SUBDUED-tier selection state is NOT "
                "exercised by this run."
            )
        # Open the window's own Sources disclosure, then click the CARD (not the mark): the card is
        # the surface F1 says never rendered the state, so selecting FROM it proves the binding is
        # two-ended rather than the mark simply highlighting itself.
        #
        # By KEY, never `.first`: the panel renders every retrieved source, while marks exist only for
        # the ones a claim referenced — so on the fallback path the first card is routinely a
        # retrieved-but-uncited source with no mark, and all three assertions below would time out
        # against a card that was never going to light anything.
        cite_key = pick["key"]
        await page.locator('[data-testid="sv3-turn-sources"]').first.click(timeout=15_000)
        card = page.locator(
            '[data-testid="sv3-turn-citations"] button.source'
            f'[data-cite-key="{cite_key}"]'
        ).first
        await card.wait_for(state="visible", timeout=15_000)
        await card.click(timeout=10_000)
        # 1 + 2: that mark wears a fill and says it is current, and its INK never moved.
        await page.wait_for_function(
            _JS_SELECTED_MARK_OK, arg={"ink": pick["ink"], "key": cite_key}, timeout=15_000
        )
        # 3: the sentences that source supports are tinted.
        await page.wait_for_function(
            _JS_SELECTED_REGION_OK, arg={"key": cite_key}, timeout=15_000
        )
        # ...and the card itself is marked and ANNOUNCED, which is the whole §5.4 repair — the far
        # side got `data-selected` first, which is a styling hook no screen reader can see.
        await page.locator(
            f'button.source[data-cite-key="{cite_key}"][data-selected][aria-current="true"]'
        ).first.wait_for(state="visible", timeout=10_000)
        # --- Tempdoc 849 slice 3 §D-8: EXTENDED, not duplicated. ---
        # The card click that proved the selection above ALSO opens the window's reading pane, so
        # this step is already standing in the state the evidence reader's header renders in.
        # Minting a parallel `sv3-citation-pane` step would have paid the 5-minute live-ask cost a
        # second time to reach a state this run is already in.
        #
        # It is what gives `Sv3Pane.ts` and `SearchV3View.ts` their first step-index rows: the pane
        # is a region of the window grid and the header's facts are joined in the view, so neither
        # file had any capture that could see it.
        await page.locator('[data-testid="sv3-pane-document"]').first.wait_for(
            state="visible", timeout=15_000
        )
        # The header is a condition-poll, not a screenshot: this turn's citation carries a chunk
        # ordinal and a retrieval mode at minimum, so a pane that renders no header at all is a
        # regression rather than an honest silence. Which FACTS it carries is deliberately not
        # asserted here — the inclusion state depends on whether this run's context fitted, and
        # `sv3-citation-dropped` below is the step that forces the interesting case.
        await page.locator('[data-testid="citation-header"]').first.wait_for(
            state="visible", timeout=15_000
        )
        await asyncio.sleep(0.3)

    async def setup_sv3_citation_dropped(page):
        # Tempdoc 849 slice 3 §D-8 — THE FLAGSHIP STATE: a source that was retrieved and never
        # reached the model.
        #
        # WHY THIS IS ITS OWN STEP rather than another extension of the one above: the two states
        # are mutually exclusive within a turn's citation. `sv3-citation-selected` asks a question
        # whose context fits (that is what makes its grounded marks reliable); this one asks a
        # question whose context provably does NOT. One ask cannot be both.
        #
        # WHAT MAKES IT DETERMINISTIC is 845's budget arithmetic, driven entirely from the UI. The
        # composer's THOROUGH rung sends `topK: 12` AND `maxTokens: 3072` (`sv3-ask.ts:158-168`), and
        # since 845 the input budget is the live context window minus the turn's real completion
        # reserve — so the rung simultaneously maximises the retrieved set and shrinks the room it
        # has to fit in. Twelve passages against a reserve-shrunk budget overflow at the boundary
        # section (`partial`) and beyond it (`dropped`); the step drives that rather than hoping a
        # natural query overflows, which is the §D-8 instruction.
        #
        # LIVE STACK ONLY, for the same reason `sv3-citation-selected` is: a v3 turn's evidence comes
        # only from the live stream, and `fixtures.ts` in that directory is a constants module, not a
        # turn-seeding fixture source. Not registered in `governance/ui-proportion-baseline.v1.json`,
        # whose gate captures under `--fixtures` where this state is unreachable.
        #
        # CAPTURED LIVE 2026-08-19, and `required=True` since — see the Step() registration for the
        # measured numbers. The arithmetic above made overflow plausible rather than proven, because
        # `maxTokens` is the completion reserve; the live capture supplied the missing proof (the
        # reserve leaves ~1024 tokens of input budget against the 4096-token dev context, and the
        # retrieved set does not fit in it).
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await page.evaluate(
            "() => { location.hash = 'justsearch://surface/core.search-v3-surface'; }"
        )
        await page.locator("jf-sv3-window").first.wait_for(state="visible", timeout=20_000)
        # Pick the THOROUGH rung before asking — the whole determinism argument rests on it.
        await page.locator('[data-testid="sv3-composer-effort"]').first.click(timeout=15_000)
        await page.locator(
            '[data-testid="sv3-composer-effort-option"][data-effort="thorough"]'
        ).first.click(timeout=10_000)
        ta = page.locator('[data-testid="sv3-composer-input"]').first
        await ta.wait_for(state="visible", timeout=15_000)
        await ta.click()
        # A BROAD ask, deliberately: the retrieval must return its full topK of long passages, so the
        # question names several unrelated areas rather than one specific fact.
        await ta.fill(
            "Compare everything the indexed documents say about indexing, search ranking, "
            "the inference runtime, the installer and the governance gates, quoting the "
            "relevant passages from each area at length."
        )
        await ta.press("Enter")
        await page.locator('[data-testid="sv3-turn-sources"]').first.wait_for(
            state="visible", timeout=300_000
        )
        await page.locator('[data-testid="sv3-turn-sources"]').first.click(timeout=15_000)
        # THE ASSERTION. A source card carrying a non-included inclusion badge is the state nothing
        # in this product could show before slice 3 — the reader can finally see that a source in
        # the panel never reached the model. A condition-poll that RAISES on timeout, so a run whose
        # context fitted fails the step instead of being photographed as a success.
        await page.wait_for_function(
            """() => {
              // The badge lives inside jf-citations-panel's shadow root, so a flat query cannot
              // see it; walk every open root the way the other shadow-piercing probes here do.
              const seen = [];
              const walk = (node) => {
                for (const el of node.querySelectorAll('.inclusion')) seen.push(el.className);
                for (const el of node.querySelectorAll('*')) if (el.shadowRoot) walk(el.shadowRoot);
              };
              walk(document);
              return seen.some((c) => c.includes('dropped') || c.includes('partial'));
            }""",
            timeout=60_000,
        )
        # …and the same fact reaches the READING pane's header, which is where a reader who follows
        # the citation meets it.
        card = page.locator('[data-testid="sv3-turn-citations"] button.source').first
        await card.click(timeout=10_000)
        await page.locator('[data-testid="citation-header"]').first.wait_for(
            state="visible", timeout=15_000
        )
        await asyncio.sleep(0.3)

    async def setup_responsive(page):
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        await page.set_viewport_size({"width": 780, "height": 720})
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def _shell_demo_goto(page):
        parsed = urlparse(ui_url)
        params = parse_qs(parsed.query)
        params["shell-demo"] = ["1"]
        demo_url = urlunparse(parsed._replace(query=urlencode(params, doseq=True)))
        await page.goto(demo_url, wait_until="domcontentloaded", timeout=timeout_ms)
        # Wait for the dock panel + at least one Lit pane to attach. We
        # use `state="attached"` rather than `visible` because Lumino's
        # initial CSS sets the dock to display:flex via class, but the
        # visibility check depends on layout — Playwright may flag the
        # element hidden during the first frame.
        await page.locator(".jf-shell-dock").wait_for(state="attached", timeout=15_000)
        await page.locator("jf-form").wait_for(state="attached", timeout=15_000)
        # Brief settle for first paint of Lumino's tab bar + Lit's first render.
        await asyncio.sleep(0.5)

    async def setup_shell_demo(page):
        # Bypass the React app via the ?shell-demo=1 branch in main.jsx.
        # The Lit shell mounts directly into #root. Visual verification
        # only — no interaction needed past the initial render (Form
        # pane is the default-active tab).
        await _shell_demo_goto(page)

    async def setup_shell_demo_status(page):
        # Click the Status tab so the screenshot shows the StatusCard
        # render rather than the default Form pane.
        await _shell_demo_goto(page)
        status_tab = page.locator(".lm-TabBar-tab", has_text="Status")
        await status_tab.click()
        await asyncio.sleep(0.3)

    async def setup_shell_demo_action(page):
        # Click the Action tab so the screenshot shows the
        # HIGH-risk ActionButton in its idle state.
        await _shell_demo_goto(page)
        action_tab = page.locator(".lm-TabBar-tab", has_text="Action")
        await action_tab.click()
        await asyncio.sleep(0.3)

    async def setup_shell_demo_table(page):
        # Click the Table tab so the screenshot shows the
        # schema-driven data grid with sortable columns.
        await _shell_demo_goto(page)
        table_tab = page.locator(".lm-TabBar-tab", has_text="Table")
        await table_tab.click()
        await asyncio.sleep(0.3)

    async def setup_presentation_demo(page):
        # 569 — the user-authored frontend demo via the ?presentation-demo=1 branch in main.jsx
        # (engine + §9 spike + interaction statechart + quarantine-to-default). No backend needed.
        parsed = urlparse(ui_url)
        params = parse_qs(parsed.query)
        params["presentation-demo"] = ["1"]
        demo_url = urlunparse(parsed._replace(query=urlencode(params, doseq=True)))
        await page.goto(demo_url, wait_until="domcontentloaded", timeout=timeout_ms)
        await page.locator("jf-declared-surface").first.wait_for(state="attached", timeout=15_000)
        await asyncio.sleep(0.6)

    async def setup_presentation_demo_statechart(page):
        # Drive the Move-8 interaction statechart: guard ON, REQUEST → CONFIRM → state 'done'
        # with the named effects logged. Scroll the section into the viewport for the shot.
        await setup_presentation_demo(page)
        await page.locator("#sc-typed").check()
        await page.get_by_role("button", name="REQUEST", exact=True).click()
        await page.get_by_role("button", name="CONFIRM", exact=True).click()
        await page.locator("#sc-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_quarantine(page):
        # Force a runtime contrast failure so the region quarantines to the built-in (Move 6).
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Force runtime contrast failure").click()
        await page.locator("#q-status").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_authoring(page):
        # Authoring origin + anti-spoof: apply an authored skin, then show the trusted channel is
        # unrepresentable (a declaration mounting jf-authorization-host is REJECTED by the gate).
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Apply a valid authored skin").click()
        await page.get_by_role("button", name="Try to mount the trusted dialog").click()
        await page.locator("#auth-msg").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_editor(page):
        # The in-UI authoring editor: type/paste a declaration → Certify & apply → renders live.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Certify & apply", exact=True).click()
        await page.locator("#authoring-msg").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_llm(page):
        # The LOCAL-LLM-EMITTED SKIN, applied live. This JSON is VERBATIM from a live on-device model
        # run (Llama-3.1-8B / cuda12): a "cool oceanic" theme it authored from the closed token vocab
        # (one dropped closing brace repaired — free chat is not grammar-constrained; /api/chat/extract
        # is). Pasted into the in-UI editor → Certify & apply → the engine recolors the page = the
        # model's skin rendering live.
        await setup_presentation_demo(page)
        ocean = (
            '{"schemaVersion":1,"id":"llm.ocean","displayName":"Oceanic Theme",'
            '"theme":{"tokens":{"accent-tint":"#03A9F4","surface-1":"#2F4F4F","surface-2":"#2F4F4F",'
            '"text-primary":"#FFFFFF","text-secondary":"#C5C5C5"}}}'
        )
        await page.fill("#authoring-editor", ocean)
        await page.get_by_role("button", name="Certify & apply", exact=True).click()
        await asyncio.sleep(0.5)
        await page.evaluate("window.scrollTo(0, 0)")  # show the recoloured page top
        await asyncio.sleep(0.3)

    async def setup_presentation_demo_liveness(page):
        # 569 §14 — the last two co-projected facets (Move 3): the LIVENESS readout (engine derives
        # the live tri-state from the one observed-state authority) + the OVERFLOW strip (engine
        # clips the trailing tail via OverflowController). Section 7 of the demo.
        await setup_presentation_demo(page)
        await page.get_by_text("Co-projected liveness + overflow").scroll_into_view_if_needed()
        # Allow the observed-state poll to complete so the readout reflects the live backend.
        await asyncio.sleep(3.0)

    async def setup_presentation_demo_required(page):
        # 569 §14 — mandatory-region visibility: a present-but-hidden required region (carrying
        # visibleWhen) is quarantined to the default layout. Surface the gate verdict. Section 8.
        await setup_presentation_demo(page)
        await page.get_by_role(
            "button", name="Apply a layout that hides the required region"
        ).click()
        await page.locator("#mr-status").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_appearance(page):
        # 569 §14 — behaviour as operating mode (Move 8): the APPEARANCE_FLOW statechart restyles
        # the page live. Click "Light" (a native button, role=button — not the section-1 radios) →
        # the page recolours + the Effect Journal increments. Section 9.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Light", exact=True).click()
        await page.locator("#ap-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.5)

    async def setup_presentation_demo_library(page):
        # 569 §14 — the Library rollout: the indexed-folder cards rendered through the engine (the
        # 2nd real surface). Scroll section 10 into view. Section 10.
        await setup_presentation_demo(page)
        await page.get_by_text("The Library rendered through the engine").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_ceremony(page):
        # 569 §15 — the BRANCHING, guarded delete-confirm ceremony (Move 8): REQUEST → confirming;
        # CONFIRM is BLOCKED by the `typed == true` guard until "DELETE" is typed; then CONFIRM → done
        # firing the journaled toast. Section 11.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="REQUEST delete", exact=True).click()
        await page.get_by_role("button", name="CONFIRM delete", exact=True).click()  # blocked (empty)
        await page.fill("#ce-typed", "DELETE")
        await page.get_by_role("button", name="CONFIRM delete", exact=True).click()  # guard passes → done
        await page.locator("#ce-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_surfaces(page):
        # 569 §15 — more real surfaces inverted: the declared Help reference (shortcuts table + lists)
        # and the Health stats (metric cards + overflow strip) rendered through the engine. Section 12.
        # Scroll to the Health stats region (heading "Index": the metric cards + the overflow strip).
        await setup_presentation_demo(page)
        await page.get_by_role("heading", name="Index", exact=True).scroll_into_view_if_needed()
        await asyncio.sleep(0.5)

    views = [
        "home",
        "search",
        "library",
        "ai-brain",
        "ai-brain-advanced",
        # tempdoc 840 Phase 5 — the two install screens the phase authored.
        "ai-brain-components",
        "ai-brain-consent",
        "health",
        "settings",
        "security",
        "help",
    ]

    return [
        # --- Shared-browser chain (demo flow) ---
        Step("search-results",       setup=setup_search_results),
        Step("command-mode",         setup=setup_command_mode,       depends_on="search-results"),
        Step("chat-mode",            setup=setup_chat_mode,          depends_on="search-results"),
        Step("filters-chips",        setup=setup_filters_chips,      depends_on="search-results"),
        Step("inspector-open",       setup=setup_inspector_open,     depends_on="search-results"),
        Step("multi-select",         setup=setup_multi_select,       depends_on="search-results"),
        Step("context-menu",         setup=setup_context_menu,       depends_on="search-results"),
        Step("streaming",            setup=setup_streaming,          depends_on="inspector-open"),
        Step("summarize-done",       setup=setup_summarize_done,     depends_on="streaming"),
        Step("citation-highlight",   setup=setup_citation,           depends_on="summarize-done"),
        # tempdoc 615 §6.1b: the React "Action Panel" has NO shell-v0 equivalent (the command palette
        # `core.command-palette` replaced it as a COMMAND-mode surface). The action-panel / -open /
        # -filtered steps are retired rather than repointed.

        # --- Isolated: main views (dark + light) ---
        *[Step(f"{v}", setup=_view_setup(v), isolated=True) for v in views],
        *[Step(f"{v}-light", setup=_view_setup(v, "light"), isolated=True, color_scheme="light") for v in views],

        # --- Isolated: density/mode variants ---
        Step("search-results-light",   setup=_density_setup("comfort"), isolated=True, color_scheme="light"),
        Step("search-results-compact", setup=_density_setup("compact"), isolated=True),
        Step("search-results-rich",    setup=_density_setup("rich"),    isolated=True),
        Step("search-simple-mode",     setup=_mode_setup("simple"),     isolated=True),
        Step("search-advanced-mode",   setup=_mode_setup("advanced"),   isolated=True),

        # --- Isolated: steps that navigate to fresh URLs ---
        Step("skeleton-library",     setup=setup_skeleton,           isolated=True),
        # context-near-limit / context-too-large retired (615 §6.1b): the React-era inspector
        # context-budget pill has no shell-v0 equivalent.
        Step("snippets-expanded",    setup=setup_snippets,           isolated=True),
        Step("zero-results",         setup=setup_zero_results,       isolated=True),
        Step("selection-preserved",  setup=setup_selection_preserved, isolated=True),

        # --- Isolated: CDP pseudo-states ---
        Step("row-hover",    setup=_cdp_setup(S.CSS_SEARCH_RESULT_ROW, "hover"), isolated=True),
        Step("input-focus",  setup=_cdp_setup(S.CSS_SEARCH_INPUT, "focus"),      isolated=True),
        # button-active retired (615 §6.1b): the inspector summarize button (React testid) is gone; the
        # live Ask flow has no equivalent always-present button to force :active on.

        # --- Isolated: inspector edge cases ---
        # error-retryable / context-details-expanded retired (615 §6.1b): demo-error injection
        # (`demo_error`) is inert and the React context-details panel has no shell-v0 equivalent.
        Step("qa-response",              setup=setup_qa,              isolated=True, init_scripts=[ai_init]),
        # --- Tempdoc 822 §7.1: the SELECTED-citation state, in the Search v3 window ---
        # The gap that let F1/F2 survive eleven slices — nothing in this harness ever entered the
        # state, so nothing could see it. Live stack + `ai_activate` (a v3 turn's evidence comes only
        # from the live stream — see the setup), and NOT registered in the proportion baseline, whose
        # gate captures under `--fixtures` where this state is unreachable.
        #
        # REQUIRED. It was declared `required=False` out of the same caution the AI chain gets, but
        # `EvalResult.ok` only consults required steps — so the one step in the harness that can see
        # this regression could not fail a run, which is the property that let the defect survive
        # eleven slices in the first place. A step whose verdict is discarded is a screenshot, not a
        # check.
        Step("sv3-citation-selected", setup=setup_sv3_citation_selected, isolated=True,
             init_scripts=[ai_init]),
        # --- Tempdoc 849 slice 3 §D-8: the retrieved-but-never-sent state ---
        # The one state the evidence reader exists to make visible, and the one the step above
        # cannot reach: its ask is chosen so the context FITS.
        #
        # `required=True` since 2026-08-19, which is where §D-8 always wanted it. It shipped
        # `required=False` deliberately and temporarily, because §D-8's determinism argument was
        # that the THOROUGH rung's `maxTokens: 3072` shrinks the input budget, and slice-3 review
        # established that `maxTokens` is the completion RESERVE — making overflow PLAUSIBLE but not
        # proven. The named reversal trigger was the first live capture; that capture has now run.
        #
        # CONFIRMED LIVE (dev stack, compact chat profile, 4096-token context, 111-doc corpus of
        # docs/{explanation,reference,how-to}, 1149 chunks fully enriched): the THOROUGH rung's ask
        # overflows. The completion reserve leaves ~1024 tokens of input budget against a 4096-token
        # window, and the turn's own context meter read 630 / 4096 — roughly 60% of what the reserve
        # actually leaves, not 15% of the window — so twelve retrieved passages cannot fit. A
        # `partial` inclusion badge ("Partly sent to the model") rendered in the sources panel and
        # the same fact reached the reading pane's header. Two consecutive `jseval ui-shot
        # sv3-citation-dropped` runs reached the state, so the assertion is not a coin flip; the
        # measured numbers are recorded in tempdoc 849 §0.3.
        Step("sv3-citation-dropped", setup=setup_sv3_citation_dropped, isolated=True,
             init_scripts=[ai_init], required=True),
        Step("responsive-collapsed",     setup=setup_responsive,      isolated=True),
        # action-panel-open / action-panel-filtered retired (615 §6.1b) — no shell-v0 equivalent.

        # --- Tempdoc 697 activation: chrome-proportion shrink-only ratchet ---
        # Isolated (own browser) so the shared `chat-mode` chain step's baseline stays
        # untouched; captured with `fixtures_variant="degraded"` so `install_fixtures`
        # serves the DEGRADED status fixture (see ui_fixtures._status_body).
        Step("chat-proportion", setup=setup_chat_proportion, isolated=True,
             fixtures_variant="degraded"),

        # --- Sandbox round 7: the two measured layout-occlusion assertions ---
        # Registered in governance/ui-proportion-baseline.v1.json with a `minWidthPx` floor
        # on the reading column and a `mustNotOverlapSelector` between the toast stack and
        # the chat header row. Isolated + fixtures: structural, no backend.
        Step("chat-occlusion", setup=setup_chat_occlusion, isolated=True),

        # --- Tempdoc 813 Slice D: the Tasks panel vs the rail's bottom controls ---
        # Registered in governance/ui-proportion-baseline.v1.json with two mustNotOverlapSelector
        # rows (Settings / Help) plus a minWidthPx floor on jf-task-list — the non-vacuity companion
        # a 0-rect hidden panel would otherwise slip past. Isolated + the `indexing` fixtures
        # variant: the panel only speaks when the worker reports a state the progress projection
        # accepts, which no other step's fixture does.
        Step("tasks-occlusion", setup=setup_tasks_occlusion, isolated=True,
             fixtures_variant="indexing"),

        # --- Tempdoc 859 §B: the floating composer vs the transcript it covers ---
        # Registered in governance/ui-proportion-baseline.v1.json with a mustNotOverlapSelector row
        # between the transcript and the dock, measured at max scroll, plus the `minHeightPx`
        # non-vacuity companions on BOTH selectors that the baseline's own occlusionNote demands
        # (two 0x0 rects never intersect, so an overlap row passes perfectly on a capture where
        # neither element rendered) and a `maxBottomPx` keeping the dock inside the pinned viewport.
        # The first sv3 step that runs under `--fixtures`: the `sv3-sources` variant serves a thread
        # record with populated `attributes.citations`, which is all the Sources panel ever needed.
        Step("sv3-composer-occlusion", setup=setup_sv3_composer_occlusion, isolated=True,
             fixtures_variant="sv3-sources"),
        # --- Tempdoc 813 §4/§5: the ENRICHING tier (folder rows + the aggregate card) ---
        # The one deterministic capture of the drained-but-enriching state: both folder arms (percent
        # caveat / "fully searchable") and the index-wide aggregate card, from the `enriching`
        # fixtures variant that no other step uses (see the setup for why the other two variants
        # cannot reach this phase).
        Step("library-enriching", setup=setup_library_enriching, isolated=True,
             fixtures_variant="enriching"),
        # --- Tempdoc 814 W4: chrome-allocation gate steps (§D7.2) ---
        # `chat-bands`: the richest all-bands state reachable under --fixtures (degraded
        # pill + submitted turn + activity-rail summary — see its setup's fixture-
        # reachability finding for what stayed unreachable). Needs the `degraded` variant
        # for the same two reasons `chat-proportion` does (the pill AND the Delegate
        # escalation rung's `capabilities.chat` availability gate).
        Step("chat-bands", setup=setup_chat_bands, isolated=True, fixtures_variant="degraded"),
        # `chat-bands-detailed`: the same band family in DETAILED disclosure at 1366x900 (above
        # the 820px block-axis breakpoint, so the banner genuinely expands) with a conversation
        # that actually overflows. Registers the expanded banner's ceiling — the Detailed floor
        # that was prose-only — and is the one capture where the D3 one-scroller rule witnesses a
        # real scroller (min 1 / max 1) instead of passing vacuously on zero.
        Step("chat-bands-detailed", setup=setup_chat_bands_detailed, isolated=True,
             fixtures_variant="degraded-detailed"),
        # `chat-composer-small`: the F5 recipe (807's coverage gap) — default fixtures
        # variant, like `chat-occlusion`; no AI capability needed for a plain search.
        Step("chat-composer-small", setup=setup_chat_composer_small, isolated=True),
        # `chat-spine-single`: single-turn conversation asserts NO run-spine. Needs
        # `degraded` for the same Delegate-availability reason as `chat-bands`.
        Step("chat-spine-single", setup=setup_chat_spine_single, isolated=True,
             fixtures_variant="degraded"),
        # `chat-spine-multi`: the PAIR's positive half (§D7.2, landed in the review pass) —
        # two user turns + agent affordance must MOUNT `.run-spine` (`requiredSelectors`).
        # `degraded-thread` = `degraded` + the two-turn `/api/thread` record the spine's
        # `mergedTimeline()` actually reads (submitted turns never reach it — see the setup).
        Step("chat-spine-multi", setup=setup_chat_spine_multi, isolated=True,
             fixtures_variant="degraded-thread"),
        # `chat-chip-yield`: the D5 chip-yield capture witness — the degraded chat surface
        # with NO activity overlay (no submit), so the status chip's label is the verdict
        # projection and `forbiddenVisibleText` can discriminate yield-on from yield-off.
        Step("chat-chip-yield", setup=setup_chat_chip_yield, isolated=True,
             fixtures_variant="degraded"),
        # `chat-wide-docked`: tempdoc 816 §5's INLINE-axis camera — the docked composer, its
        # escalation strip and the banner's content box at 1920x900, the width class where an
        # unbounded element is actually offensive and where no other step looks. Registers the
        # first role-bound rows (`inlineSizeRole`) alongside physical presence floors. `degraded`
        # for the same pill/capability reasons as `chat-proportion`.
        Step("chat-wide-docked", setup=setup_chat_wide_docked, isolated=True,
             fixtures_variant="degraded"),
        # `chat-evidence-rail` / `chat-activity-rail-open`: tempdoc 814 §D8's two residual-closure
        # captures, both on the `agent-run` variant — `degraded-thread` plus record grounding +
        # a DONE lifecycle plus a REAL terminating SSE body for POST /api/chat/dispatch. It is the
        # only variant under which an agent run completes, so it is also the only one whose
        # captures are free of the spurious "Connection lost" row (asserted, not assumed).
        Step("chat-evidence-rail", setup=setup_chat_evidence_rail, isolated=True,
             fixtures_variant="agent-run"),
        Step("chat-activity-rail-open", setup=setup_chat_activity_rail_open, isolated=True,
             fixtures_variant="agent-run"),

        # --- Slice 3a.1 Phase 6: Lit shell-v0 visual verification ---
        # Mounts the standalone shell demo (Lumino DockPanel + Lit panes)
        # via the `?shell-demo=1` query branch in main.jsx. Bypasses the
        # React app entirely. See modules/ui-web/src/shell-v0/demo/.
        Step("shell-v0-demo",         setup=setup_shell_demo,        isolated=True, required=False),
        Step("shell-v0-demo-status",  setup=setup_shell_demo_status, isolated=True, required=False),
        Step("shell-v0-demo-action",  setup=setup_shell_demo_action, isolated=True, required=False),
        Step("shell-v0-demo-table",   setup=setup_shell_demo_table,  isolated=True, required=False),

        # --- 569: the user-authored frontend demo ---
        Step("presentation-demo",            setup=setup_presentation_demo,            isolated=True, required=False),
        Step("presentation-demo-statechart", setup=setup_presentation_demo_statechart, isolated=True, required=False),
        Step("presentation-demo-quarantine", setup=setup_presentation_demo_quarantine, isolated=True, required=False),
        Step("presentation-demo-authoring",  setup=setup_presentation_demo_authoring,  isolated=True, required=False),
        Step("presentation-demo-editor",     setup=setup_presentation_demo_editor,     isolated=True, required=False),
        Step("presentation-demo-llm",        setup=setup_presentation_demo_llm,        isolated=True, required=False),
        Step("presentation-demo-liveness",   setup=setup_presentation_demo_liveness,   isolated=True, required=False),
        Step("presentation-demo-required",   setup=setup_presentation_demo_required,   isolated=True, required=False),
        Step("presentation-demo-appearance", setup=setup_presentation_demo_appearance, isolated=True, required=False),
        Step("presentation-demo-library",    setup=setup_presentation_demo_library,    isolated=True, required=False),
        Step("presentation-demo-ceremony",   setup=setup_presentation_demo_ceremony,   isolated=True, required=False),
        Step("presentation-demo-surfaces",   setup=setup_presentation_demo_surfaces,   isolated=True, required=False),
    ]


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

async def _run_shared_steps(
    steps: list[Step], page, output_dir: Path, *, cooldown_ms: int, deadline: float | None,
    console_sink: "ui_measure.ConsoleSink | None" = None, measure: bool = True,
    trace_target: str | None = None,
) -> list[ShotResult]:
    """Run shared-browser steps sequentially with dependency tracking.

    trace_target (tempdoc 615 §11 TRACE): if set, snapshot a PRE measure right before
    that step's interaction and write its {pre, post} trajectory delta — so a chain
    step like inspector-open records what its row-click changed, not just the end state.
    """
    shots: list[ShotResult] = []
    completed: set[str] = set()

    for step in steps:
        if deadline and time.monotonic() >= deadline:
            shots.append(ShotResult(name=step.name, ok=False, error="deadline_exceeded", required=step.required))
            continue
        if step.depends_on and step.depends_on not in completed:
            shots.append(ShotResult(name=step.name, ok=False, error=f"skipped: dependency '{step.depends_on}' failed", required=step.required))
            continue
        try:
            if measure and trace_target and step.name == trace_target:
                try:
                    await ui_measure.capture_measure(page, f"{step.name}.pre", output_dir, None)
                except Exception:
                    pass
            await step.setup(page)
            r = await _capture_shot(
                page, step.name, output_dir, cooldown_ms=cooldown_ms,
                console_sink=console_sink, measure=measure,
            )
            r.required = step.required
            shots.append(r)
            if r.ok:
                completed.add(step.name)
                if measure and trace_target and step.name == trace_target and r.measure_path:
                    _write_trace(step.name, output_dir)
        except Exception as e:
            shots.append(ShotResult(name=step.name, ok=False, error=str(e)[:200], required=step.required))
    return shots


def _write_trace(name: str, output_dir: Path) -> None:
    """TRACE (tempdoc 615 §11): write `<name>.trace.json` = the {pre, post} interaction
    delta, reusing the DIFF engine over the two measure captures the step produced."""
    from . import ui_diff
    pre_p = output_dir / f"{name}.pre.measure.json"
    post_p = output_dir / f"{name}.measure.json"
    if not (pre_p.exists() and post_p.exists()):
        return
    pre = json.loads(pre_p.read_text(encoding="utf-8"))
    post = json.loads(post_p.read_text(encoding="utf-8"))
    trace = {
        "schema": "ui-trace.v1",
        "name": name,
        "pre_url": pre.get("url"),
        "post_url": post.get("url"),
        "delta": ui_diff.diff_measures(pre, post),
    }
    (output_dir / f"{name}.trace.json").write_text(json.dumps(trace, indent=2) + "\n", encoding="utf-8")


async def _run_isolated_step(
    step: Step, ui_url: str, output_dir: Path, *, demo: bool, cooldown_ms: int, timeout_ms: int,
    playwright_module, measure: bool = True, fixtures: bool = False, trace: bool = False,
) -> ShotResult:
    """Run a single isolated step in its own browser.

    fixtures=True (tempdoc 615 §13 Move 1 / §16) installs the deterministic
    route-mock + walkthrough seed: every `/api/*` is served a schema-valid fixture
    so the no-backend 502 storm cannot occur and the capture is byte-stable. Use it
    for STRUCTURAL steps (a11y/layout); leave it off for the AI-chain steps, which
    need a real model.
    """
    t0 = time.monotonic()
    try:
        browser = await playwright_module.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            ctx = await browser.new_context(
                viewport={"width": 1280, "height": 720},
                color_scheme=step.color_scheme,
            )
            if fixtures:
                await ui_fixtures.install_fixtures(ctx, variant=step.fixtures_variant)
            for script in step.init_scripts:
                await ctx.add_init_script(script)
            page = await ctx.new_page()
            # tempdoc 615 §6.2 — collect console.error/pageerror over the step's lifetime for the
            # measurement companion (attach before navigation so boot errors are captured).
            console_sink = ui_measure.ConsoleSink() if measure else None
            if console_sink:
                console_sink.attach(page)
            url = _demo_url(ui_url, theme=step.color_scheme) if demo else ui_url
            await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            # tempdoc 615 §27 readiness gate: block until the app shell mounted (rail visible),
            # else raise AppNotMountedError with the serve-layer reason (Vite stderr / overlay) —
            # so a never-mount reads as "cannot capture: <reason>", not a phantom render-failed.
            await _await_app_ready(page)
            # tempdoc 615 §11 TRACE: capture a PRE snapshot before the step's interaction
            # trajectory, so the {pre, post} delta records what the flow changed (network/
            # console/layout shift), not just the at-rest end state.
            if trace and measure:
                try:
                    await ui_measure.capture_measure(page, f"{step.name}.pre", output_dir, None, theme=step.color_scheme)
                except Exception:
                    pass
            await step.setup(page)
            r = await _capture_shot(
                page, step.name, output_dir, cooldown_ms=cooldown_ms,
                console_sink=console_sink, measure=measure, theme=step.color_scheme,
            )
            r.required = step.required
            if trace and measure and r.measure_path:
                _write_trace(step.name, output_dir)
            await ctx.close()
            return r
        finally:
            await browser.close()
    except AppNotMountedError as e:
        # tempdoc 615 §27 — loud, attributed serve-layer failure (full reason, not truncated).
        return ShotResult(name=step.name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000,
                          error=f"cannot capture '{step.name}': {e}", required=step.required)
    except Exception as e:
        return ShotResult(name=step.name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000, error=str(e)[:200], required=step.required)


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------

async def _run_eval(ui_url: str, output_dir: Path, *, demo: bool = True, cooldown_ms: int = 250, timeout_ms: int = 120_000, measure: bool = True) -> EvalResult:
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise RuntimeError("playwright not installed. Install with: pip install playwright && playwright install chromium")

    output_dir.mkdir(parents=True, exist_ok=True)
    result = EvalResult(output_dir=str(output_dir))
    t0 = time.monotonic()
    deadline = t0 + timeout_ms / 1000

    all_steps = _build_steps(ui_url, cooldown_ms, timeout_ms)
    shared = [s for s in all_steps if not s.isolated]
    isolated = [s for s in all_steps if s.isolated]

    base_url = _demo_url(ui_url) if demo else ui_url

    async with async_playwright() as p:
        # Run shared-browser chain first (sequential, one browser)
        browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            ctx = await browser.new_context(viewport={"width": 1280, "height": 720})
            await ctx.add_init_script("localStorage.setItem('justsearch-inspector-tab', 'ai');")
            page = await ctx.new_page()
            console_sink = ui_measure.ConsoleSink() if measure else None
            if console_sink:
                console_sink.attach(page)
            await page.goto(base_url, wait_until="domcontentloaded", timeout=timeout_ms)
            # tempdoc 615 §27 readiness gate: if the shell never mounts, the whole shared chain
            # is moot — fail every shared step with the serve-layer reason rather than letting
            # each setup time out into an opaque "render-failed".
            try:
                await _await_app_ready(page)
                shared_shots = await _run_shared_steps(
                    shared, page, output_dir, cooldown_ms=cooldown_ms, deadline=deadline,
                    console_sink=console_sink, measure=measure,
                )
            except AppNotMountedError as e:
                shared_shots = [ShotResult(name=s.name, ok=False,
                                           error=f"cannot capture '{s.name}': {e}",
                                           required=s.required) for s in shared]
            result.shots.extend(shared_shots)
            await ctx.close()
        finally:
            await browser.close()

        # Run isolated steps in parallel (bounded concurrency)
        sem = asyncio.Semaphore(4)
        async def bounded(step: Step) -> ShotResult:
            async with sem:
                return await _run_isolated_step(step, ui_url, output_dir, demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms, playwright_module=p, measure=measure)

        isolated_results = await asyncio.gather(*[bounded(s) for s in isolated], return_exceptions=True)
        for r in isolated_results:
            if isinstance(r, ShotResult):
                result.shots.append(r)
            else:
                result.shots.append(ShotResult(name="unknown", ok=False, error=str(r)[:200]))

    result.elapsed_ms = (time.monotonic() - t0) * 1000

    # Baseline comparison — detect changed screenshots via file size
    baseline_path = output_dir.parent / "baseline.json"
    drift: list[dict] = []
    sizes: dict[str, int] = {}
    for s in result.shots:
        if s.ok and s.path:
            p = Path(s.path)
            if p.exists():
                sizes[s.name] = p.stat().st_size
    if baseline_path.exists():
        try:
            baseline = json.loads(baseline_path.read_text())
            for name, new_size in sizes.items():
                old_size = baseline.get(name)
                if old_size is None:
                    drift.append({"name": name, "change": "new"})
                elif abs(new_size - old_size) / max(old_size, 1) > 0.10:
                    drift.append({"name": name, "change": "size_drift",
                                  "old_bytes": old_size, "new_bytes": new_size})
            for name in baseline:
                if name not in sizes:
                    drift.append({"name": name, "change": "missing"})
        except Exception:
            pass

    result_dict = result.to_dict()
    if sizes:
        result_dict["file_sizes"] = sizes
    if drift:
        result_dict["drift"] = drift

    (output_dir / "ui-eval.json").write_text(json.dumps(result_dict, indent=2) + "\n")
    # Save current sizes as baseline for next run
    baseline_path.write_text(json.dumps(sizes, indent=2) + "\n")

    return result


def execute_ui_check(
    ui_url: str = "http://localhost:5173",
    *,
    output_dir: str | None = None,
    demo: bool = True,
    cooldown_ms: int = 250,
    timeout_ms: int = 120_000,
    measure: bool = True,
) -> dict:
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    base = Path(output_dir) if output_dir else Path("tmp/ui-check")
    out = base / ts
    return asyncio.run(_run_eval(ui_url, out, demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms, measure=measure)).to_dict()


def format_console(result: dict) -> str:
    lines = []
    ok = result.get("ok", False)
    lines.append(f"UI Check: {'PASS' if ok else 'FAIL'} ({result['total_passed']}/{result['total_shots']} screenshots)")
    lines.append(f"Elapsed: {result['elapsed_ms']:.0f}ms  Output: {result.get('output_dir', 'N/A')}")
    lines.append("")
    for s in result.get("shots", []):
        mark = "+" if s["ok"] else "x"
        req = "" if s.get("required", True) else " (optional)"
        line = f"  [{mark}] {s['name']}{req} ({s['elapsed_ms']:.0f}ms)"
        if s.get("error"):
            line += f" — {s['error'][:80]}"
        lines.append(line)
    lines.append("")

    drift = result.get("drift", [])
    if drift:
        lines.append(f"Drift detected ({len(drift)} screenshots changed):")
        for d in drift:
            if d["change"] == "new":
                lines.append(f"  [NEW] {d['name']}")
            elif d["change"] == "missing":
                lines.append(f"  [GONE] {d['name']}")
            elif d["change"] == "size_drift":
                lines.append(f"  [CHANGED] {d['name']} ({d['old_bytes']}B → {d['new_bytes']}B)")
        lines.append("")
    return "\n".join(lines)
