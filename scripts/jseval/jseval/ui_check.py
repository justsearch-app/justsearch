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
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

from . import ui_fixtures
from . import ui_measure
from . import ui_selectors as S
from . import ui_agent_flows


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


async def assert_recents_fit(page, state: str) -> None:
    """R1a/Y7a: real clipping boxes and every keyboard/pointer-reachable row."""
    async def fully_visible(locator):
        return await locator.evaluate("""el => {
            const r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0 || r.top < -1 || r.bottom > innerHeight + 1) return false;
            for (let a = el.parentElement ?? el.getRootNode().host; a;
                 a = a.parentElement ?? a.getRootNode().host) {
                const c = getComputedStyle(a), b = a.getBoundingClientRect();
                if (['hidden','clip','auto','scroll'].includes(c.overflowY) &&
                    (r.top < b.top - 1 || r.bottom > b.bottom + 1)) return false;
            }
            return true;
        }""")

    recent = page.locator('[data-testid="sv3-recents"]')
    for selector in ['[data-testid="sv3-composer-shell"]', '[data-testid="sv3-recent-clear"]'] + (
            ['[data-testid="sv3-composer-headline"]'] if state == 'hero' else []):
        assert await fully_visible(page.locator(selector)), f"R1a/Y7a clipped heading/bar/control: {selector}"
    assert await fully_visible(recent), "R1a/Y7a Recent extends outside its clipping column"
    options = recent.locator('[role="option"]')
    assert await options.count() == 13, "R1a/Y7a requires all eight searches and five conversations"
    field = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first
    for index in range(13):
        await field.press('ArrowDown')
        selected = recent.locator('[aria-selected="true"]')
        await selected.wait_for(state='visible')
        assert await selected.get_attribute('id') == f'sv3-recent-{index}'
        assert await fully_visible(selected), f"R1a/Y7a keyboard row {index} clipped/unreachable"
    original_viewport = page.viewport_size
    alternate = {"width": 600, "height": 400} if original_viewport['height'] > 400 else {"width": 1280, "height": 720}
    for viewport in (alternate, original_viewport):
        await page.set_viewport_size(viewport)
        await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        # Resizing crosses the existing animated sidebar breakpoint. Judge the settled
        # layout after its finite transitions, then let ResizeObserver publish geometry.
        await page.evaluate("""async () => {
            await Promise.all(document.getAnimations().filter(a =>
                a.effect?.getTiming().iterations !== Infinity).map(a => a.finished.catch(() => {})));
            await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        }""")
        await page.locator('jf-sv3-sidebar').first.evaluate("""async el => {
            await Promise.all(el.getAnimations().map(a => a.finished.catch(() => {})));
            await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
        }""")
        assert await fully_visible(selected), "R1a/Y7a resizing hides the active option"
    # Pointer scrolling must work without asking Playwright to auto-scroll the target row.
    await recent.locator('[role="listbox"]').evaluate('el => { el.scrollTop = 0; }')
    await recent.hover()
    await page.mouse.wheel(0, 10000)
    await page.wait_for_timeout(100)
    assert await fully_visible(options.last), "R1a/Y7a last row unreachable by pointer"
    extra_scrollers = await recent.evaluate("""el => {
        let count = 0;
        for (let a = el.parentElement ?? el.getRootNode().host; a;
             a = a.parentElement ?? a.getRootNode().host) {
            const c = getComputedStyle(a);
            if (['auto','scroll'].includes(c.overflowY) && a.scrollHeight > a.clientHeight + 1) count++;
        }
        return count;
    }""")
    assert extra_scrollers == 0, "R1a/Y7a added a scroller outside Recent"


async def _await_app_ready(page, *, timeout_ms: int = 15_000) -> None:
    """Block until the app shell has mounted (rail button visible), else raise
    AppNotMountedError with the best-available reason (Vite stderr tail / error-overlay
    text / failed resource status / honest fallback). ONE gate, reused by every capture path."""
    try:
        # WP1.5a promotes Search v3; retain the older main rail as a known-good control.
        # Only an actually visible Search rail button establishes shell readiness.
        await page.locator(
            f'{S.rail_css(S.RAIL_SURFACE_SEARCH)}:visible, '
            f'{S.rail_css(S.RAIL_SURFACE_OLDER_MAIN)}:visible'
        ).first.wait_for(state="visible", timeout=timeout_ms)
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
    failed_resources = []
    try:
        diagnostics = await page.evaluate("""() => {
            const overlay = document.querySelector('vite-error-overlay');
            return {
                overlay: overlay
                    ? (overlay.shadowRoot?.textContent || overlay.textContent || 'present').slice(0, 400)
                    : null,
                failedResources: performance.getEntriesByType('resource')
                    .filter(entry => entry.responseStatus >= 400
                        && new URL(entry.name).origin === location.origin)
                    .slice(0, 3)
                    .map(entry => `${entry.responseStatus} ${new URL(entry.name).pathname}`)
            };
        }""")
        overlay = diagnostics["overlay"]
        failed_resources = diagnostics["failedResources"]
    except Exception:
        overlay = None

    if stderr_tail:
        reason = f"app shell never mounted within {secs}s; vite stderr tail: {stderr_tail}"
    elif overlay:
        reason = f"app shell never mounted within {secs}s; vite error overlay: {overlay.strip()}"
    elif failed_resources:
        # Optimizer 504s can prevent every app module from evaluating without an overlay
        # or server stderr. Read the browser's completed requests, including those before
        # this gate began; no extra listener lifetime or retry can hide the failed mount.
        reason = (f"app shell never mounted within {secs}s; failed same-origin resources: "
                  + "; ".join(failed_resources))
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
    # Playwright's `forced_colors` context emulation ("active" | "none"); None leaves the browser
    # default. "active" is the CSS `forced-colors: active` state Windows High Contrast produces in
    # WebView2 (Chromium), so a step can capture the UI as a High Contrast user sees it.
    forced_colors: str | None = None


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
        await ui_measure.assert_search_state(page, name)
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
    # Search v3 owns retrieval. Its composer opens in Search mode (WP1.1): typing lists matching
    # documents and Enter runs the full search at once, on the shared results card.
    await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
    await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).click()
    window = page.locator(S.CSS_SV3_WINDOW)
    await window.first.wait_for(state="visible", timeout=15_000)
    inp = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first
    await inp.wait_for(state="visible", timeout=10_000)
    await inp.fill(query)
    async with page.expect_response("**/api/knowledge/search", timeout=30_000):
        await inp.press("Enter")
    await page.locator(S.CSS_SV3_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)


async def _check_legacy_token_plugin(page) -> None:
    """Legacy authoring-token compatibility only, through untrusted-plugin installation."""
    origin = urlparse(page.url)
    source_url = f"{origin.scheme}://{origin.netloc}/capture-compat.js"

    async def source(route):
        await route.fulfill(status=200, content_type="text/javascript",
                            body=ui_fixtures.COMPAT_CHAT_PLUGIN_SOURCE)

    async def verify(route):
        await route.fulfill(status=200, content_type="application/json",
                            body=ui_fixtures.COMPAT_CHAT_VERIFY_BODY)

    await page.route(source_url, source)
    await page.route(lambda url: urlparse(url).path == "/api/plugins/verify", verify)
    await page.locator(S.rail_css(S.RAIL_SURFACE_SETTINGS)).click()
    settings = page.locator(S.CSS_SETTINGS_WINDOW_CONTENT)
    await page.locator(S.CSS_SETTINGS_WINDOW_DIALOG).wait_for(state="visible", timeout=10_000)
    await settings.first.wait_for(state="attached", timeout=10_000)
    await settings.locator('[data-settings-category="plugins"]').click()
    await settings.get_by_placeholder("Plugin URL (e.g., http://localhost:3000/plugin.js)").fill(source_url)
    async with page.expect_response(
        lambda response: urlparse(response.url).path == "/api/plugins/verify", timeout=15_000,
    ) as response_info:
        await settings.get_by_role("button", name="Load", exact=True).click()
    response = await response_info.value
    if response.status != 200 or (await response.json()).get("verified") is not False:
        raise AssertionError("compatibility capture requires the explicit negative trust verdict")
    row = settings.locator(".plugin-row").filter(has_text="capture-compat")
    await row.locator("jf-status-badge").get_by_text("untrusted", exact=True).wait_for(
        state="visible", timeout=15_000)
    await page.get_by_test_id("settings-window-close").click()
    await page.locator(S.CSS_SETTINGS_WINDOW_DIALOG).wait_for(state="hidden", timeout=10_000)
    await page.evaluate("(id) => { location.hash = `justsearch://surface/${id}`; }",
                        ui_fixtures.COMPAT_CHAT_SURFACE_ID)
    # Observe genuine browser traversal, rather than emitting a synthetic router event.
    await page.go_back(wait_until="domcontentloaded")
    await page.go_forward(wait_until="domcontentloaded")
    root = page.locator(S.CSS_SV3_WINDOW + ":visible")
    await root.first.wait_for(state="visible", timeout=15_000)
    if await root.count() != 1 or await page.locator("jf-unified-chat-view").count() != 0:
        raise AssertionError("legacy token must construct exactly one registered Search window")
    await root.locator("jf-sv3-composer textarea").wait_for(state="visible", timeout=10_000)
    ownership = await page.locator("jf-stage").evaluate("""stage => ({
        id: stage.surface?.id, audience: stage.surface?.audience, provenance: stage.surface?.provenance,
    })""")
    provenance = ownership.get("provenance") or {}
    if (ownership.get("id") != ui_fixtures.COMPAT_CHAT_SURFACE_ID
            or ownership.get("audience") != "OPERATOR"
            or provenance.get("tier") != "UNTRUSTED_PLUGIN"
            or provenance.get("contributorId") != "capture-compat"
            or provenance.get("version") != "1.0.0"
            or (provenance.get("identity") or {}).get("verified") is True):
        raise AssertionError(f"legacy-token construction changed trust/provenance: {ownership!r}")

    # Installation must never promote this negative-verdict contribution to CORE/TRUSTED.
    await page.locator(S.rail_css(S.RAIL_SURFACE_SETTINGS)).click()
    await settings.locator('[data-settings-category="plugins"]').click()
    await row.locator("jf-status-badge").get_by_text("untrusted", exact=True).wait_for(state="visible")
    await page.get_by_test_id("settings-window-close").click()
    await page.locator(S.CSS_SETTINGS_WINDOW_DIALOG).wait_for(state="hidden")


async def _set_density_in_settings(page, label: str) -> None:
    """Select one FE-local Density stop and prove the control projected it."""
    slider = page.locator(S.CSS_DENSITY_SLIDER).first
    await slider.wait_for(state="visible", timeout=10_000)
    await slider.fill(str({"Compact": 0, "Comfortable": 1, "Spacious": 2}[label]))
    if await slider.get_attribute("aria-valuetext") != label:
        raise AssertionError(f"Density slider did not select {label}")


async def _set_detail_level_in_settings(page, label: str) -> None:
    """Select the header's backend-persisted detail level and await its witnessed receipt."""
    # The reachable authority is Shell's persistent header toggle: a named group of pressed
    # buttons. Settings also projects the value, but this header control is always reachable.
    group = page.get_by_role("group", name="Detail level").first
    btn = group.get_by_role("button", name=label, exact=True)
    await btn.wait_for(state="visible", timeout=10_000)
    if await btn.get_attribute("aria-pressed") == "true":
        return
    async with page.expect_response(
        lambda response: "/api/settings/v2" in response.url
        and response.request.method == "POST",
        timeout=15_000,
    ) as response_info:
        await btn.click()
    response = await response_info.value
    receipt = await response.json()
    if response.status != 200 or receipt.get("state") != "COMPLETE":
        raise AssertionError(f"Detail-level settings write did not complete: {receipt!r}")


async def _navigate_and_search(page, url: str, query: str = "justsearch", *, timeout_ms: int = 60_000) -> None:
    await page.goto(url, wait_until="networkidle", timeout=timeout_ms)
    await _type_and_search(page, query)


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


# A `data-cite-key` is a SOURCE PATH, so on Windows it carries backslashes — and a backslash
# in a CSS selector introduces an escape, string context included: `...\598-...` parses as the
# character U+0598 followed by `-`, so a raw interpolation silently matches nothing and the step
# times out on a page that is rendering correctly. The page's own `CSS.escape` is the authority
# on the transform, so the raw key ships INTO the page and comes back selector-safe rather than
# being re-implemented here.
async def _css_escaped(page, value: str) -> str:
    """CSS.escape `value` in the page, for interpolation into a selector."""
    return await page.evaluate("(v) => CSS.escape(v)", value)


def _cite_card_selector(escaped_key: str) -> str:
    """The Sources-panel card for one cite key. `escaped_key` MUST come from `_css_escaped`."""
    return (
        '[data-testid="sv3-turn-citations"] button.source'
        f'[data-cite-key="{escaped_key}"]'
    )


def _cite_card_selected_selector(escaped_key: str) -> str:
    """That same card once it is marked AND announced. `escaped_key` from `_css_escaped`."""
    return f'button.source[data-cite-key="{escaped_key}"][data-selected][aria-current="true"]'


# ---------------------------------------------------------------------------
# Step registry — all screenshots declared here
# ---------------------------------------------------------------------------

def _build_steps(ui_url: str, cooldown_ms: int, timeout_ms: int) -> list[Step]:
    """Build the complete flat step list."""
    demo = _demo_url(ui_url)
    ai_init = "localStorage.setItem('justsearch-inspector-tab', 'ai');"

    async def setup_engine_recovery(page):
        # Capture the real desktop boot path with a deterministic native command boundary.
        # The initial browser shell satisfies the common harness mount check; reload then
        # starts main.jsx without an Engine binding. This is UI proof, not an installed updater.
        await page.context.add_init_script("""(() => {
          let nextCallback = 0;
          window.__TAURI_INTERNALS__ = {
            transformCallback: () => ++nextCallback,
            unregisterCallback: () => {},
            invoke: async (command) => {
              if (command === 'plugin:event|listen') return ++nextCallback;
              if (command === 'plugin:event|unlisten') return null;
              if (command === 'api_port') return null;
              if (command === 'supervisor_state') return {
                schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri',
                state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3
              };
              if (command === 'app_update_status') return {
                state: 'available', currentVersion: '0.3.0', availableVersion: '0.3.1'
              };
              throw new Error('Unexpected native command in recovery capture: ' + command);
            }
          };
        })();""")
        await page.reload(wait_until="domcontentloaded")
        await page.get_by_test_id("engine-recovery").wait_for(state="visible", timeout=15_000)
        await page.get_by_text("JustSearch could not restart", exact=True).wait_for()
        await page.get_by_text("Install update", exact=True).wait_for()

    async def setup_engine_admission_wait(page):
        """Exercise the real admission-wait emitter and its mounted toast projection."""
        await page.evaluate(
            """() => {
              const events = [];
              window.__justsearchAdmissionWaitEvents = events;
              window.__justsearchAdmissionWaitListener = (event) => {
                const detail = event.detail || {};
                events.push({
                  classId: detail.classId,
                  message: detail.message,
                  severity: detail.severity,
                  supersede: detail.supersede,
                });
              };
              document.addEventListener(
                'jf-advisory-ephemeral', window.__justsearchAdmissionWaitListener
              );
            }"""
        )
        # SES blocks import expressions evaluated as page JavaScript. A module script is the
        # trusted served-module boundary used by the app itself; expose only this named helper for
        # the next page evaluation, which merely invokes it and observes the real event channel.
        await page.add_script_tag(
            type="module",
            content="""
              import { reportAdmissionWait } from '/src/shell-v0/state/admissionWaitNotice.ts';
              globalThis.__justsearchReportAdmissionWait = reportAdmissionWait;
            """,
        )
        emitted = await page.evaluate(
            """() => {
              if (typeof globalThis.__justsearchReportAdmissionWait !== 'function') {
                throw new Error('served admission-wait module did not expose its helper');
              }
              globalThis.__justsearchReportAdmissionWait(false, 250);
              globalThis.__justsearchReportAdmissionWait(true, 500);
              return globalThis.__justsearchAdmissionWaitEvents;
            }"""
        )
        if len(emitted) != 2:
            raise AssertionError(f"expected two admission-wait emissions, got {emitted!r}")
        if any(event.get("classId") != "core.engine.wait" for event in emitted):
            raise AssertionError(f"admission-wait emissions used the wrong class: {emitted!r}")
        if any(event.get("severity") != "info" for event in emitted):
            raise AssertionError(f"admission-wait emissions were not informational: {emitted!r}")
        if any(event.get("supersede") is not True for event in emitted):
            raise AssertionError(f"admission-wait emissions did not supersede: {emitted!r}")

        toast = page.locator(S.CSS_TOAST)
        await toast.first.wait_for(state="visible", timeout=10_000)
        await asyncio.sleep(0.2)
        if await toast.count() != 1:
            raise AssertionError("same-class admission waits must leave one visible toast")
        toast_text = await toast.first.inner_text()
        if "preparing an update" not in toast_text:
            raise AssertionError(f"latest admission-wait notice was not rendered: {toast_text!r}")
        notice = toast.first.locator("jf-system-notice")
        if await notice.get_attribute("tone") != "neutral":
            raise AssertionError("informational admission wait must use the canonical neutral tone")
        if await notice.get_attribute("live") != "status":
            raise AssertionError("admission-wait toast must use polite status announcement")

    # === Shared-browser chain (sequential, depends_on linkage) ===

    async def setup_search_results(page):
        await _type_and_search(page)

    async def setup_search_semantic_paused(page):
        await _view_setup("search")(page)
        headline = page.get_by_text("Semantic search paused; keyword search available.", exact=True)
        await headline.wait_for(state="visible", timeout=10_000)
        if await headline.count() != 1:
            raise AssertionError("semantic-search limitation must be explained exactly once")
        banner = page.get_by_test_id("sv3-degradation")
        if await banner.count() != 1:
            raise AssertionError("semantic-search limitation must have exactly one banner")
        remedy = banner.get_by_test_id("sv3-degradation-remedy").get_by_role("button", name="Open Health", exact=True)
        await remedy.wait_for(state="visible", timeout=10_000)
        if not await remedy.is_enabled():
            raise AssertionError("semantic-search limitation must offer an enabled Health remedy")

    async def setup_command_mode(page):
        inp = page.get_by_test_id(S.TID_SEARCH_INPUT)
        await inp.click()
        await inp.fill("/reindex")
        await page.get_by_test_id(S.TID_GLOBAL_COMMAND_CHROME).get_by_text("/reindex", exact=False).wait_for(state="visible", timeout=10_000)

    async def setup_chat_mode(page):
        await _open_sv3_window(page)
        await _choose_search_tier(page, "ask")
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).fill("What is this file about?")
        await page.locator('jf-sv3-composer[tier="ask"]').wait_for(state="visible")

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
        # This step was unpassable on any code from the initial public release (29579e51) until
        # 2026-09-03: it waited on a `skeleton-library` test id that no element in
        # `modules/ui-web/src` has ever carried, and drove it with an `e2e_view_delay_ms` query
        # param nothing in the FE reads. Both were hooks for a delay mechanism that does not
        # exist, so the step failed in EVERY harness mode (tempdoc 721 `unanchored-drift-20`).
        #
        # The Library surface DOES have an observable loading state, so no test id is added to
        # production to satisfy the harness: `this.loading` renders one `<div class="empty">
        # Loading…</div>` while the `/api/indexing-roots/substrate` fetch is in flight. What was
        # actually missing is a way to HOLD that state open — a fixture (or live) response lands in
        # milliseconds. So the step stubs the route itself and answers late. A PAGE route outranks
        # `install_fixtures`' CONTEXT route, which is what lets this step pass with `--fixtures`,
        # against a live stack, and with no backend at all: `loading` is set before the fetch and
        # cleared only in its `finally`, so holding the response holds the state in all three.
        async def _hold_substrate(route):
            await asyncio.sleep(10)
            try:
                await route.fulfill(
                    status=200,
                    content_type="application/json",
                    body=ui_fixtures.fixture_body(route.request.url),
                )
            except Exception:
                # The capture finished and the page is gone — the hold already did its job.
                pass

        await page.route("**/api/indexing-roots/substrate*", _hold_substrate)
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_LIBRARY)).click(timeout=5_000)
        await page.locator(S.CSS_LIBRARY_SURFACE_HOST).get_by_text(
            S.TEXT_LIBRARY_LOADING, exact=False
        ).first.wait_for(state="visible", timeout=10_000)

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
                    await btn.click()
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
                await _set_detail_level_in_settings(page, "Detailed")
            if view_name in ("ai-brain-components", "ai-brain-consent"):
                # These controls live in the Simple panel; the captured settings fixture starts
                # Detailed. Select the panel through the shared witnessed header control first.
                await _set_detail_level_in_settings(page, "Simple")
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
            await btn.click()
        except Exception:
            await page.evaluate("(id) => { location.hash = `justsearch://surface/${id}`; }", surface_id)

    _DENSITY_LABEL = {"compact": "Compact", "comfort": "Comfortable",
                      "comfortable": "Comfortable", "rich": "Spacious"}
    _MODE_LABEL = {"simple": "Simple", "advanced": "Detailed"}

    async def setup_health_recovery(page):
        # Exercise the actual REST projection and catalog confirmation through transport fixtures.
        catalog = json.loads(ui_fixtures.fixture_body("http://localhost/api/registry/operations"))
        template = catalog["entries"][0]
        catalog["entries"] = []
        for operation_id, high in (("core.reindex", False), ("core.rebuild-index", True)):
            entry = json.loads(json.dumps(template))
            entry["id"] = operation_id
            entry["presentation"]["labelKey"] = f"ops.{operation_id.removeprefix('core.')}.label"
            entry["policy"]["risk"] = "HIGH" if high else "LOW"
            entry["policy"]["confirm"] = {"kind": "INLINE" if high else "NONE"}
            entry["intf"]["inputs"] = {"type": "object"}
            entry["intf"]["result"] = {"type": "object"}
            catalog["entries"].append(entry)
        recovery = {"catalogVersion": 1, "entries": [
            {"target": "core.reindex", "conditions": [{
                "conditionId": "schema.reindex-required", "subject": "worker.schema",
                "severity": "WARNING", "since": "2026-09-14T00:00:00Z",
                "defaultArgsJson": '{"force":true}',
            }]},
            {"target": "core.rebuild-index", "conditions": [{
                "conditionId": "embedding.blocked", "subject": "worker.embedding",
                "severity": "WARNING", "since": "2026-09-14T00:00:00Z",
                "defaultArgsJson": '{}',
            }]},
        ]}
        invoked = []

        async def operations(route):
            await route.fulfill(status=200, content_type="application/json", body=json.dumps(catalog))

        async def recommendations(route):
            await route.fulfill(status=200, content_type="application/json", body=json.dumps(recovery))

        async def empty_stream(route):
            await route.fulfill(status=200, content_type="text/event-stream", body=": ready\n\n")

        async def invoke(route):
            invoked.append(route.request.url)
            await route.fulfill(status=200, content_type="application/json",
                                body=json.dumps({"success": True, "message": "Completed"}))

        await page.route("**/api/registry/operations", operations)
        await page.route("**/api/condition-recovery-index", recommendations)
        await page.route("**/api/condition-recovery-index/stream", empty_stream)
        await page.route("**/api/operations/*/invoke", invoke)
        # Catalog bootstrap must consume the overridden transport, not a cached earlier catalog.
        await page.reload()
        await _goto_surface(page, S.RAIL_SURFACE_HEALTH)
        panel = page.locator("jf-health-surface .recommended")
        low = panel.locator('jf-operation[operation-id="core.reindex"]')
        await low.wait_for(state="visible", timeout=15_000)
        async with page.expect_request("**/api/operations/*/invoke") as request:
            await low.locator("jf-action-button button.invoke").click()
        assert (await request.value).post_data_json["args"] == {"force": True}
        high = panel.locator('jf-operation[operation-id="core.rebuild-index"]')
        await high.locator("jf-action-button button.invoke").click()
        await high.locator(".confirm-row").wait_for(state="visible")
        assert len(invoked) == 1, "Rebuild must wait for catalog inline confirmation"
        await panel.scroll_into_view_if_needed()

    async def setup_health_completion(page):
        # Exercise the real Health SSE consumer and Lit projection with a finite
        # transport fixture. No private component state is patched.
        cases = [
            ("COMPLETED", "completed", "INFO", "The agent finished its response."),
            ("MAX_ITERATIONS", "max-iterations", "WARNING", "The agent reached its step limit."),
            ("BUDGET_EDGE_FINALIZE", "budget-edge-finalize", "WARNING", "The agent reached its response budget"),
            ("ERRORED", "errored", "ERROR", "The agent stopped because an error occurred."),
            ("CANCELLED", "cancelled", "WARNING", "The run was cancelled."),
        ]
        events = [{
            "id": f"agent.session.{suffix}", "timestamp": "2026-09-06T00:00:00Z",
            "source": {"serviceName": "head", "serviceInstanceId": "failure-ux-test", "serviceVersion": "test"},
            "severity": severity,
            "i18nKey": f"health-events.agent.session.{suffix}.message",
            "body": {"kind": "lifecycle", "attributes": {"disposition": code}},
        } for code, suffix, severity, _ in cases]

        async def health_stream(route):
            frame = {"frameKind": "LIFECYCLE", "payload": {
                "kind": "snapshot", "conditions": [], "occurrences": events,
            }}
            await route.fulfill(status=200, content_type="text/event-stream",
                                body="data: " + json.dumps(frame) + "\n\n")

        await page.route("**/api/health/events/stream", health_stream)
        await _goto_surface(page, S.RAIL_SURFACE_HEALTH)
        for code, suffix, severity, wording in cases:
            row = page.locator(f'.event-row[title="agent.session.{suffix}"]')
            await row.wait_for(state="visible", timeout=15_000)
            text = await row.inner_text()
            assert wording in text, f"Missing completion explanation: {code}: {text}"
            assert code not in text, f"Raw completion code: {text}"
            assert await row.get_attribute("data-severity") == severity
        await page.locator('.event-row[title="agent.session.max-iterations"]').scroll_into_view_if_needed()

    async def setup_search_failure(page):
        async def failed_search(route):
            await route.fulfill(status=502, content_type="application/json", body=json.dumps({
                "error": "The search index is not available.",
                "errorCode": "INDEX_UNAVAILABLE", "errorClass": "TRANSIENT",
                "retryable": True, "i18nKey": "errors.INDEX_UNAVAILABLE",
            }))
        await page.route("**/api/knowledge/search", failed_search)
        await _goto_surface(page, "core.search-v3-surface")
        window = page.locator("jf-sv3-window")
        await window.wait_for(state="visible", timeout=15_000)
        composer = window.locator("jf-sv3-composer textarea")
        await composer.fill("failure presentation check")
        async with page.expect_response("**/api/knowledge/search", timeout=15_000) as response:
            await composer.press("Enter")
        assert (await response.value).status == 502
        alert = page.locator('jf-sv3-results [data-testid="sv3-results-failed"]')
        await alert.wait_for(state="visible", timeout=15_000)
        text = await alert.inner_text()
        assert "The search index is not available." in text, text
        assert "HTTP 502" not in text, text
        assert "Try searching again" in text, text
        assert await alert.get_attribute("role") == "alert"
        await alert.scroll_into_view_if_needed()

    async def setup_library_ingestion(page):
        rollups = [
            ("SUCCESS_FULL", "SUCCESS", "NONE", 7),
            ("SKIPPED_POLICY", "UNCHANGED", "NONE", 3),
            ("PARSER_FAILED", "PARSER_FAILED", "NONE", 2),
            ("DEFERRED_POLICY", "CLOUD_PLACEHOLDER", "DEFER_WITHOUT_ATTEMPT", 4),
        ]
        async def summary(route):
            await route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "rollups": [{"outcomeClass": outcome, "reasonCode": reason,
                    "retryPolicy": retry, "count": count, "lastObservedAtMs": 1700000000000}
                    for outcome, reason, retry, count in rollups], "count": len(rollups),
            }))
        await page.route("**/api/diagnostics/ingestion/summary*", summary)
        await _goto_surface(page, S.RAIL_SURFACE_LIBRARY)
        panel = page.locator('[data-testid="ingestion-summary"]')
        await panel.get_by_text("16 recorded indexing outcomes.", exact=True).wait_for(timeout=15_000)
        for label in ("Content indexed", "File unchanged since its last index",
                      "Content extraction failed", "Cloud-only file is not available locally"):
            await panel.get_by_text(label, exact=True).wait_for()
        assert "CLOUD_PLACEHOLDER" not in await panel.inner_text()
        await panel.scroll_into_view_if_needed()

    async def setup_library_gap_decision(page):
        await _goto_surface(page, S.RAIL_SURFACE_LIBRARY)
        decision = page.locator(".gap-decision")
        await decision.get_by_role("heading", name="New index — activation requires your decision").wait_for(
            timeout=15_000)
        await decision.get_by_text("notes/old-draft.txt: CANDIDATE_PROJECTION_MISSING").wait_for()
        await decision.get_by_text("notes/deleted-draft.txt: DELETE_NOT_APPLIED").wait_for()
        await decision.get_by_role("button", name="Accept gaps and activate").wait_for()
        await decision.scroll_into_view_if_needed()

    def _density_setup(density: str):
        async def setup(page):
            # Density is the Accessibility discrete slider and persists in the FE-local profile.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _goto_surface(page, S.RAIL_SURFACE_SETTINGS)
            await page.locator(S.CSS_SETTINGS_WINDOW_CONTENT).first.wait_for(
                state="attached", timeout=10_000
            )
            label = _DENSITY_LABEL.get(density, "Comfortable")
            await _set_density_in_settings(page, label)
            if cooldown_ms > 0:
                await asyncio.sleep(cooldown_ms / 1000)
            await _goto_surface(page, S.RAIL_SURFACE_SEARCH)
            await _type_and_search(page)
        return setup

    def _mode_setup(mode: str):
        async def setup(page):
            # UI mode is the persistent header's "Detail level" pressed-button group. Its async
            # patch is complete only
            # after the witnessed settings POST returns a COMPLETE receipt.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _set_detail_level_in_settings(page, _MODE_LABEL.get(mode, "Simple"))
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

    async def _choose_search_tier(page, tier):
        await page.get_by_test_id("sv3-composer-tier").click()
        await page.locator(f'[data-testid="sv3-composer-tier-option"][data-tier="{tier}"]').click()
        await page.locator(f'jf-sv3-composer[tier="{tier}"]').wait_for(state="visible")

    async def _send_search_ask(page):
        await _open_sv3_window(page)
        await _choose_search_tier(page, "ask")
        ta = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA)
        await ta.fill("How does indexing reach the application?")
        await ta.press("Enter")
        await page.get_by_test_id("sv3-turn-question").first.wait_for(state="visible", timeout=15_000)
        await page.get_by_test_id("sv3-turn-answer").first.wait_for(state="visible", timeout=20_000)
        await page.get_by_test_id('sv3-turn-markdown').first.wait_for(state="visible", timeout=20_000)
        await page.locator('jf-sv3-main:not([streaming])').wait_for(state="visible", timeout=20_000)

    async def setup_qa(page):
        # Search's own Sources disclosure and reader, reached without forced interaction.
        await _send_search_ask(page)
        await page.get_by_test_id("sv3-turn-sources").first.click()
        await page.locator('[data-testid="sv3-turn-citations"] button.source').first.click()
        await page.locator("jf-document-pane :is(.blocks, .source)").wait_for(state="visible", timeout=15_000)

    async def setup_chat_model_only(page):
        await _open_sv3_window(page)
        await _choose_search_tier(page, "ask")
        ta = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA)
        await ta.fill("Explain how indexing works.")
        await ta.press("Enter")
        action = page.get_by_test_id("sv3-answer-without-files")
        await action.wait_for(state="visible", timeout=15_000)
        async with page.expect_request(lambda r: r.method == "POST" and "/api/chat/dispatch" in r.url) as sent:
            await action.click()
        request = await sent.value
        if request.post_data_json.get("shapeId") != "core.free-chat":
            raise AssertionError("Answer without your files did not dispatch the model-only shape")
        await page.get_by_test_id("sv3-turn-markdown").last.wait_for(state="visible", timeout=15_000)
        await page.locator('jf-sv3-main:not([streaming])').wait_for(state="visible")

    async def setup_chat_structured(page):
        await _open_sv3_window(page)
        await _choose_search_tier(page, "structured")
        await page.get_by_test_id("sv3-composer-schema").fill('{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}')
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).fill("Summarize indexing as structured data.")
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).press("Enter")
        await page.get_by_test_id("sv3-turn-json").wait_for(state="visible", timeout=15_000)

    async def setup_chat_proportion(page):
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _send_search_ask(page)
        await page.locator(S.CSS_SV3_DEGRADATION_COMPACT).wait_for(state="visible")

    async def setup_chat_occlusion(page):
        await page.set_viewport_size({"width": 1250, "height": 800})
        await _type_and_search(page)
        await page.locator(S.CSS_SV3_RESULT_ROW).first.click()
        await page.locator("jf-document-pane :is(.blocks, .source)").wait_for(state="visible", timeout=15_000)
        if await page.locator(S.CSS_TOAST + ":visible").count() != 0:
            raise AssertionError("ambient toasts invalidate the exact cap witness")
        await page.evaluate("""() => {
            for (let i = 0; i < 3; i++) document.dispatchEvent(new CustomEvent('jf-advisory-ephemeral', {
                detail: {message: `Occlusion probe toast ${i + 1}`, severity: 'error', supersede: false},
                bubbles: true,
            }));
        }""")
        await page.locator(S.CSS_TOAST).nth(2).wait_for(state="visible")
        await asyncio.sleep(0.3)
        if await page.locator(S.CSS_TOAST + ":visible").count() != 3 or await page.get_by_test_id("toast-more").is_visible():
            raise AssertionError("expected exactly three visible toasts without overflow summary")

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
        await _open_sv3_window(page)
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
        scroller = page.locator('jf-sv3-window [data-testid="sv3-main-scroller"]').first
        at_end = await scroller.evaluate(
            "(s) => { s.scrollTop = s.scrollHeight;"
            " return s.scrollHeight - s.scrollTop - s.clientHeight <= 1 && s.scrollTop > 0; }"
        )
        if not at_end:
            raise AssertionError("sv3-composer-occlusion: the transcript did not overflow and scroll to its end")
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    # --- Forced colors (Windows High Contrast): the Search v3 states a High Contrast reader meets ---
    # Each capture ends with a KEYBOARD focus on screen, because under forced colors box-shadows are
    # dropped and author colours replaced, so the focus indicator is the thing most likely to vanish.
    async def _settle_focus_marks(page):
        # Focus marks here are transitions (the composer frame, the grips' line), and a transition
        # only starts on a rendered frame: without one, the capture IS the first frame and shows the
        # mark at its starting colour. Two animation frames start it; the cooldown then lets it land.
        await page.evaluate(
            "() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))"
        )
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def _open_sv3_window(page):
        # The runner already mounted Search on a fresh context. Re-select only when away:
        # a bare rail navigation can leave an existing conversation and is not a readiness probe.
        window = page.locator(S.CSS_SV3_WINDOW)
        if not await window.is_visible():
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).click()
        await window.wait_for(state="visible", timeout=20_000)
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).wait_for(state="visible", timeout=15_000)

    async def setup_sv3_hero_forced_colors(page):
        await _open_sv3_window(page)
        await page.locator("jf-sv3-window jf-sv3-composer[state='hero']").wait_for(
            state="visible", timeout=15_000
        )
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first.focus()
        await _settle_focus_marks(page)

    async def setup_sv3_docked_forced_colors(page):
        # The `sv3-sources` variant restores a thread, so the window lands DOCKED over a transcript.
        await _open_sv3_window(page)
        await page.locator('[data-testid="sv3-turn-sources"]').first.wait_for(
            state="visible", timeout=20_000
        )
        await page.locator("jf-sv3-window jf-sv3-composer[state='docked']").wait_for(
            state="visible", timeout=15_000
        )
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first.focus()
        await _settle_focus_marks(page)

    async def setup_sv3_palette_forced_colors(page):
        await _open_sv3_window(page)
        await page.locator(S.CSS_SV3_WINDOW).get_by_role(
            "button", name="Open command palette", exact=True
        ).click()
        await page.locator(S.CSS_SV3_PALETTE).get_by_role("option").first.wait_for(
            state="visible", timeout=10_000
        )
        # Move the highlight off the selected option, so the capture shows the selected and the
        # highlighted option as two different rows.
        await page.keyboard.press("ArrowDown")
        await _settle_focus_marks(page)

    _settings_setup = _view_setup("settings")

    def setup_indexing_rules(surface="settings", narrow=False):
        async def setup(page):
            if narrow:
                await page.set_viewport_size({"width": 600, "height": 400})
            await _view_setup(surface)(page)
            if surface == "settings":
                await page.locator('[data-settings-category="indexing"]').click()
                await page.locator('#indexing-hours').wait_for(state="visible")
                await page.locator('#indexing-hours').focus()
            else:
                await page.get_by_role("button", name="Change hours", exact=True).first.wait_for(state="visible")
            await _settle_focus_marks(page)
        return setup

    async def setup_settings_forced_colors(page):
        await _settings_setup(page)
        await page.keyboard.press("Tab")
        await _settle_focus_marks(page)

    def setup_sv3_recents(state, narrow, viewport=None):
        async def setup(page):
            # A full list: eight accepted explicit searches and five saved conversations.
            async def conversations(route):
                await route.fulfill(status=200, content_type="application/json", body=json.dumps({
                    "sessions": [{"sessionId": f"recent-{i}", "title": f"Conversation {i + 1}",
                                  "titleSource": "user", "firstUserMessage": "Saved question",
                                  "createdAtMs": 1, "lastActiveAtMs": 100 - i,
                                  "shapeId": "core.rag-ask", "messageCount": 2} for i in range(5)]
                }))
            await page.route("**/api/chat/conversations?limit=20", conversations)
            await page.set_viewport_size(viewport or {"width": 760 if narrow else 1280, "height": 800})
            await _open_sv3_window(page)
            field = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first
            for query in ["notes", "budget", "title:report", '"exact phrase"', "Ada", "forecast", "plan", "latest"]:
                await field.fill(query)
                async with page.expect_response("**/api/knowledge/search", timeout=15_000):
                    await field.press("Enter")
            await field.fill("")
            if state == "hero":
                # Escape dismisses Recent first, then returns the composer to hero.
                await field.press("Escape")
                await field.press("Escape")
                await page.locator("jf-sv3-composer[state='hero']").wait_for(state="visible")
            await field.blur()
            await field.focus()
            await page.locator('[data-testid="sv3-recents"] [role="option"]').nth(12).wait_for(state="visible")
            if viewport is not None:
                await assert_recents_fit(page, state)
            else:
                await field.press("ArrowDown")
            if await page.evaluate("matchMedia('(forced-colors: active)').matches"):
                selected = page.locator('[data-testid="sv3-recents"] [aria-selected="true"]')
                assert await selected.evaluate("el => getComputedStyle(el).forcedColorAdjust") == "none"
            await _settle_focus_marks(page)
        return setup

    def setup_sv3_filters(state, narrow, viewport=None):
        async def setup(page):
            await setup_sv3_recents(state, narrow, viewport)(page)
            await page.locator('[data-testid="sv3-filters-trigger"]').click()
            for key, value in (("folder", "/inside"), ("from", "2026-03-29"), ("to", "2026-03-29"),
                               ("minimum", "0"), ("maximum", "2")):
                control = page.locator(f'[data-filter-input="{key}"]')
                await control.fill(value)
                await control.press("Enter")
            # Keep the active folder chip while exposing its validation and association.
            await page.locator('[data-filter-input="folder"]').fill("relative/path")
            await page.locator('[data-filter-input="folder"]').press("Enter")
            panel = page.locator('jf-sv3-filters')
            assert await panel.locator('[data-filter]').count() == 3
            assert await page.locator('[data-filter-input="folder"]').get_attribute('aria-invalid') == 'true'
            # Every field, note and error is reachable inside the bounded panel; the window
            # and bar keep their places while only the panel's contents scroll.
            for selector in ('[data-filter-input="folder"]', '[data-filter-input="from"]',
                             '[data-filter-input="to"]', '[data-filter-input="minimum"]',
                             '[data-filter-input="maximum"]', '#folder-error', '.notes'):
                control = panel.locator(selector).first
                await control.scroll_into_view_if_needed()
                assert await control.is_visible(), f"F16 unreachable {selector}"
            await panel.evaluate('el => { el.scrollTop = 0; }')
            await assert_recents_fit(page, state)
            await _settle_focus_marks(page)
        return setup

    async def setup_sv3_search_results(page):
        # WP1.1 S1.12 — Search mode's result list stays reachable beside the floating composer, at a
        # narrow width and with a long list. DETERMINISTIC: the `sv3-results` fixtures variant
        # answers any search with 24 hits, which overflows the pinned 760x720 viewport; a fresh
        # window opens in Search, so Enter in the composer is the whole path in.
        #
        # Measured at MAX SCROLL for the same reason as `sv3-composer-occlusion`: at rest the end of
        # the list is nowhere near the dock, and "the card does not run under the glass" only means
        # something where "scrolled to the end" is claimed to mean "the last row is above it".
        await page.set_viewport_size({"width": 760, "height": 720})
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).wait_for(state="visible", timeout=15_000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).click()
        await page.locator(S.CSS_SV3_WINDOW).first.wait_for(state="visible", timeout=20_000)
        composer = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).first
        await composer.wait_for(state="visible", timeout=10_000)
        await composer.fill("notes")
        await composer.press("Enter")
        rows = page.locator(S.CSS_SV3_RESULT_ROW)
        await rows.nth(23).wait_for(state="attached", timeout=20_000)
        # The refined pass settles the card's meta line; wait for it so the capture is not mid-pass.
        await page.locator('jf-sv3-results [data-testid="meta-refining"]').first.wait_for(
            state="detached", timeout=10_000
        )
        # Through a shadow-piercing locator: the window mounts inside the shell's shadow root, so a
        # `document.querySelector('jf-sv3-window')` walk finds nothing and would scroll nothing.
        scroller = page.locator('jf-sv3-window [data-testid="sv3-main-scroller"]').first
        at_end = await scroller.evaluate(
            "(s) => { s.scrollTop = s.scrollHeight;"
            " return s.scrollHeight - s.scrollTop - s.clientHeight <= 1 && s.scrollTop > 0; }"
        )
        if not at_end:
            raise AssertionError("sv3-search-results: the result list did not overflow and scroll to its end")
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
        # projection deliberately refuses to read (indexingProgress.INDEX_REPORTED_STATES) —
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
            await btn.click()
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
            await btn.click()
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
        # Recorded Delegate activity replaces the retired three-zone geometry camera.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _open_recorded_activity(page)

    async def setup_chat_bands_detailed(page):
        await page.set_viewport_size({"width": 1366, "height": 900})
        await _send_search_ask(page)
        await page.get_by_test_id("sv3-degradation-detail").wait_for(state="visible")

    async def setup_chat_composer_small(page):
        await page.set_viewport_size({"width": 760, "height": 600})
        await _open_sv3_window(page)
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).fill("A longer draft remains reachable in a small window.")
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).focus()

    async def setup_chat_wide_docked(page):
        await page.set_viewport_size({"width": 1920, "height": 900})
        await _send_search_ask(page)
        await page.locator(S.CSS_SV3_DEGRADATION_COMPACT).wait_for(state="visible")

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
        # the step also registers `requiredSelectors: [Search compact degradation]` —
        # otherwise a capture that failed to render the surface at all would show neither
        # string and read as a pass.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _open_sv3_window(page)
        await page.locator(S.CSS_SV3_DEGRADATION_COMPACT).first.wait_for(
            state="visible", timeout=15_000
        )
        await page.get_by_test_id("sv3-degradation-headline").wait_for(state="visible")
        await page.get_by_test_id("sv3-degradation-remedy").wait_for(state="visible")
        await asyncio.sleep(0.3)

    async def _open_recorded_activity(page):
        # The canonical thread record is restored into Search, including its actual tool card.
        await _open_sv3_window(page)
        await page.get_by_test_id("sv3-record-activity").last.wait_for(state="visible", timeout=20_000)
        await page.get_by_test_id("sv3-run-receipt").last.wait_for(state="visible", timeout=20_000)

    async def setup_chat_budget(page):
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _open_sv3_window(page)
        await _choose_search_tier(page, "delegate")
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).fill("Investigate indexing until the budget decision.")
        await page.locator(S.CSS_SV3_COMPOSER_TEXTAREA).press("Enter")
        await page.get_by_test_id("sv3-run-budget-facts").wait_for(state="visible", timeout=20_000)
        await page.get_by_test_id("sv3-run-budget-finalize").wait_for(state="visible")
        await page.get_by_test_id("sv3-run-budget-stop").wait_for(state="visible")

    async def setup_chat_evidence_rail(page):
        # Search exposes evidence on demand beneath the answer, rather than a legacy side rail.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _open_sv3_window(page)
        await page.get_by_test_id("sv3-turn-sources").first.click()
        await page.get_by_test_id("sv3-turn-citations").wait_for(state="visible")
        await page.locator('[data-testid="sv3-turn-citations"] button.source').first.scroll_into_view_if_needed()

    async def setup_chat_activity_rail_open(page):
        # The shared tool card is Search's expandable activity disclosure.
        await page.set_viewport_size({"width": 1366, "height": 768})
        await _open_recorded_activity(page)
        card = page.locator('jf-tool-call-card').last
        await card.get_by_test_id('tool-card-header').click()
        await page.locator('jf-tool-call-card[expanded] .tool-card-body').last.wait_for(state="visible")

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
        # Search is the main rail surface; explicit navigation also works from a secondary view.
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).wait_for(state="visible", timeout=15_000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).click()
        await page.locator("jf-sv3-window").first.wait_for(state="visible", timeout=20_000)
        ta = page.locator('[data-testid="sv3-composer-input"]').first
        await ta.wait_for(state="visible", timeout=15_000)
        await ta.click()
        # The same retrieval-grounded prompt shape `setup_streaming` uses: the answer must run a
        # search tool (so the turn gets sources) AND ground its sentences in those passages (so the
        # matcher emits citations) — both are required before a single `.cite-ref` mark renders.
        await ta.fill(
            "Search the indexed documents and summarize how indexing reaches the application half, "
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
        # The key goes into the JS assertions RAW (they compare `dataset.citeKey` as a string)
        # and into every CSS selector ESCAPED — see `_css_escaped`.
        cite_key_css = await _css_escaped(page, cite_key)
        await page.locator('[data-testid="sv3-turn-sources"]').first.click(timeout=15_000)
        card = page.locator(_cite_card_selector(cite_key_css)).first
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
            _cite_card_selected_selector(cite_key_css)
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
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).wait_for(state="visible", timeout=15_000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).click()
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

    async def setup_help_narrow(page):
        # Tempdoc 899 review closeout: retain a real small-viewport proof for the Help
        # operation controls instead of relying on a one-off screenshot at the default width.
        await page.set_viewport_size({"width": 760, "height": 720})
        await _view_setup("help")(page)
        await page.get_by_text("Copy Diagnostic Summary", exact=True).scroll_into_view_if_needed()
        await asyncio.sleep(0.3)

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

    steps = [
        Step("engine-recovery", setup=setup_engine_recovery, isolated=True),
        Step("engine-admission-wait", setup=setup_engine_admission_wait, isolated=True),
        Step("health-completion", setup=setup_health_completion, isolated=True),
        Step("health-recovery", setup=setup_health_recovery, isolated=True),
        Step("search-failure", setup=setup_search_failure, isolated=True),
        Step("search-semantic-paused", setup=setup_search_semantic_paused, isolated=True,
             fixtures_variant="semantic-paused"),
        Step("search-semantic-paused-live", setup=setup_search_semantic_paused, isolated=True),
        Step("library-ingestion", setup=setup_library_ingestion, isolated=True),
        Step("library-gap-decision", setup=setup_library_gap_decision, isolated=True,
             fixtures_variant="gap-decision"),
        # --- Shared-browser chain (demo flow) ---
        Step("search-results",       setup=setup_search_results),
        Step("command-mode",         setup=setup_command_mode,       depends_on="search-results"),
        Step("chat-mode", setup=setup_chat_mode, isolated=True, fixtures_variant="degraded"),
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
        *[Step(f"{v}", setup=_view_setup(v), isolated=True,
               fixtures_variant="install-preview" if v in ("ai-brain-components", "ai-brain-consent") else "default")
          for v in views],
        *[Step(f"{v}-light", setup=_view_setup(v, "light"), isolated=True, color_scheme="light",
               fixtures_variant="install-preview" if v in ("ai-brain-components", "ai-brain-consent") else "default")
          for v in views],
        Step("help-narrow", setup=setup_help_narrow, isolated=True),

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
        Step("qa-response", setup=setup_qa, isolated=True, fixtures_variant="degraded"),
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

        # Search captures preserve reading, composer, notice, evidence and activity usability.
        # Legacy three-zone shares, full-bleed backgrounds and minimaps are retired (WP1.5b).
        Step("chat-proportion", setup=setup_chat_proportion, isolated=True,
             fixtures_variant="degraded"),

        Step("chat-occlusion", setup=setup_chat_occlusion, isolated=True),

        Step("tasks-occlusion", setup=setup_tasks_occlusion, isolated=True,
             fixtures_variant="indexing"),

        Step("sv3-composer-occlusion", setup=setup_sv3_composer_occlusion, isolated=True,
             fixtures_variant="sv3-sources"),
        Step("sv3-hero-forced-colors", setup=setup_sv3_hero_forced_colors, isolated=True,
             forced_colors="active"),
        Step("sv3-docked-forced-colors", setup=setup_sv3_docked_forced_colors, isolated=True,
             fixtures_variant="sv3-sources", forced_colors="active"),
        Step("sv3-palette-forced-colors", setup=setup_sv3_palette_forced_colors, isolated=True,
             forced_colors="active"),
        *[Step(f"settings-indexing-{theme}", setup=setup_indexing_rules(), isolated=True,
               fixtures_variant="indexing-rules", color_scheme="light" if theme == "light" else "dark",
               forced_colors="active" if theme == "forced" else None)
          for theme in ("dark", "light", "forced")],
        Step("settings-indexing-minimum", setup=setup_indexing_rules(narrow=True), isolated=True,
             fixtures_variant="indexing-rules"),
        Step("library-indexing-held", setup=setup_indexing_rules("library"), isolated=True,
             fixtures_variant="indexing-rules"),
        Step("settings-forced-colors", setup=setup_settings_forced_colors, isolated=True,
             forced_colors="active"),
        *[Step(f"sv3-recents-{state}-{width}-{theme}",
               setup=setup_sv3_recents(state, width == "narrow"), isolated=True,
               color_scheme="light" if theme == "light" else "dark",
               forced_colors="active" if theme == "forced" else None)
          for state in ("hero", "docked") for width in ("normal", "narrow")
          for theme in ("dark", "light", "forced")],
        *[Step(f"sv3-recents-{state}-{width}x{height}-{theme}",
               setup=setup_sv3_recents(state, False, {"width": width, "height": height}), isolated=True,
               color_scheme="light" if theme == "light" else "dark",
               forced_colors="active" if theme == "forced" else None)
          for state in ("hero", "docked") for width, height in ((1280, 720), (760, 720), (600, 400))
          for theme in ("dark", "light", "forced")],
        *[Step(f"sv3-filters-{state}-{width}-{theme}",
               setup=setup_sv3_filters(state, width == "narrow"), isolated=True,
               color_scheme="light" if theme == "light" else "dark",
               forced_colors="active" if theme == "forced" else None)
          for state in ("hero", "docked") for width in ("normal", "narrow")
          for theme in ("dark", "light", "forced")],
        *[Step(f"sv3-filters-{state}-{width}x{height}-{theme}",
               setup=setup_sv3_filters(state, False, {"width": width, "height": height}), isolated=True,
               color_scheme="light" if theme == "light" else "dark",
               forced_colors="active" if theme == "forced" else None)
          for state in ("hero", "docked") for width, height in ((1280, 720), (600, 400))
          for theme in ("dark", "light", "forced")],
        Step("sv3-search-results", setup=setup_sv3_search_results, isolated=True,
             fixtures_variant="sv3-results"),
        Step("library-enriching", setup=setup_library_enriching, isolated=True,
             fixtures_variant="enriching"),
        Step("chat-bands", setup=setup_chat_bands, isolated=True, fixtures_variant="agent-run"),
        Step("chat-bands-detailed", setup=setup_chat_bands_detailed, isolated=True,
             fixtures_variant="degraded-detailed"),
        Step("chat-composer-small", setup=setup_chat_composer_small, isolated=True),
        Step("chat-chip-yield", setup=setup_chat_chip_yield, isolated=True,
             fixtures_variant="degraded"),
        Step("chat-wide-docked", setup=setup_chat_wide_docked, isolated=True,
             fixtures_variant="degraded"),
        Step("chat-evidence-rail", setup=setup_chat_evidence_rail, isolated=True,
             fixtures_variant="sv3-sources"),
        Step("chat-activity-rail-open", setup=setup_chat_activity_rail_open, isolated=True,
             fixtures_variant="agent-run"),

        Step("chat-model-only", setup=setup_chat_model_only, isolated=True, fixtures_variant="no-evidence"),
        Step("chat-structured", setup=setup_chat_structured, isolated=True, fixtures_variant="degraded"),
        Step("chat-budget", setup=setup_chat_budget, isolated=True, fixtures_variant="agent-budget"),
        Step("sv3-run-activity-steering", setup=ui_agent_flows.setup_run_activity_steering,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-budget", setup=ui_agent_flows.setup_run_budget,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-approval", setup=ui_agent_flows.setup_run_approval,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-approval-failure", setup=ui_agent_flows.setup_run_approval_failure,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-ask-stop-late-response", setup=ui_agent_flows.setup_ask_stop_late_response,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-halt", setup=ui_agent_flows.setup_run_halt,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-halt-failure", setup=ui_agent_flows.setup_run_halt_failure,
             isolated=True, fixtures_variant="agent-live"),
        Step("sv3-run-halt-timeout", setup=ui_agent_flows.setup_run_halt_timeout,
             isolated=True, fixtures_variant="agent-live"),
        Step("legacy-token-plugin", setup=_check_legacy_token_plugin, isolated=True),

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

    # Theme is a rendered state. Reuse the same interaction, not a separate camera recipe.
    search_steps = set(S.SEARCH_STATE_WITNESSES) - {
        "home", "legacy-token-plugin", "sv3-citation-selected", "sv3-citation-dropped",
    }
    return steps + [replace(step, name=step.name + "-light", color_scheme="light")
                    for step in steps if step.name in search_steps]


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
                forced_colors=step.forced_colors,
            )
            if fixtures:
                await ui_fixtures.install_fixtures(
                    ctx, variant=step.fixtures_variant, color_scheme=step.color_scheme,
                )
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
            if step.color_scheme == "light":
                active_theme = await page.evaluate(
                    "() => document.documentElement.getAttribute('data-theme')"
                )
                if active_theme != "light":
                    raise RuntimeError(
                        "post-setup color scheme mismatch: requested light, "
                        f"document data-theme is {active_theme!r}"
                    )
            r = await _capture_shot(
                page, step.name, output_dir, cooldown_ms=cooldown_ms,
                console_sink=console_sink, measure=measure, theme=step.color_scheme,
            )
            r.required = step.required
            if trace and measure and r.measure_path:
                _write_trace(step.name, output_dir)
            await ctx.close()
            return r
        except Exception:
            # A behavioural setup failure needs evidence too, before browser teardown.
            import traceback
            traceback.print_exc()
            try:
                await page.screenshot(path=str(output_dir / f"{step.name}.failed.png"))
                fixture = getattr(ctx, "_jseval_agent_fixture", None)
                if fixture is not None:
                    (output_dir / f"{step.name}.requests.json").write_text(json.dumps([
                        {"method": r.method, "path": r.path, "query": r.query, "body": r.body,
                         "answered": r.answered.is_set()} for r in fixture.requests
                    ], indent=2), encoding="utf-8")
            except Exception:
                pass  # Diagnostics cannot replace the original failure.
            raise
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
