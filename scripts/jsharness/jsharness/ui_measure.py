"""Structured measurement companion for ui-shot / ui-check (tempdoc 615 §6.2).

The tempdoc's thesis is *measurement-first*: capture FACTS alongside every
screenshot — the accessibility tree, axe WCAG violations, key-element
geometry/computed-style, overflow flags, and console errors — so a correctness
judgment can target the measurement instead of the pixels. The screenshot
demotes to a non-asserting gestalt attachment (§6.2 / P2).

Reuses, rather than reinvents:
- Playwright's NATIVE `page.accessibility.snapshot()` as the live perception
  channel (§6.3 "reuse the a11y substrate").
- the SAME axe-core bundle the Node e2e harness uses
  (`modules/ui-web/node_modules/axe-core/axe.min.js`), injected via
  `add_script_tag` — mirrors `e2e/ai-harness.ts` `checkAccessibility()`.
- the console-collector pattern from `e2e/ai-harness.ts` `setupConsoleCollector`.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def _find_axe() -> Path | None:
    """Locate the axe-core standalone bundle shipped in ui-web's node_modules."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "modules" / "ui-web" / "node_modules" / "axe-core" / "axe.min.js"
        if cand.exists():
            return cand
    return None


_AXE_PATH = _find_axe()


def _find_a11y_baseline() -> dict[str, list[str]]:
    """Load the shared a11y baseline register (tempdoc 615 §13 Move 2): the ONE
    authority for per-surface known/accepted axe rules. Returns {uiShotStep ->
    [knownRuleId]}. Best-effort: an absent/garbled register yields an empty map
    (every violation then reports as a raw count, never crashes a capture)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-a11y-baseline.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                return {
                    s["uiShotStep"]: list(s.get("knownRules") or [])
                    for s in reg.get("surfaces", [])
                    if s.get("uiShotStep")
                }
            except Exception:
                return {}
    return {}


_A11Y_BASELINE = _find_a11y_baseline()


def _find_proportion_baseline() -> dict[str, list[str]]:
    """Load the shared chrome-proportion baseline register (tempdoc 697): the ONE
    authority for which element selectors are tracked per ui-shot step. Returns
    {uiShotStep -> [selector]}. Best-effort: an absent/garbled register yields an empty
    map (geometry capture is unaffected — the extra selectors are simply not unioned in,
    mirroring `_find_a11y_baseline`'s fail-open contract).

    A `mustNotOverlapSelector` counterpart is collected too: an overlap constraint is a
    RELATION, so the gate needs BOTH rects out of the one capture, and a counterpart that
    is only ever named (never registered as its own row) would otherwise be missing from
    the geometry and read as a capture error. Tempdoc 814 (W4 activation) extends the same
    union to `minShareOfSelector`'s denominator selector (also a RELATION — the share
    assertion needs both rects) and to a step-level `absentSelectors` list (an element that
    must NOT be present still needs to be *probed* so its absence is a capture fact, not
    a missing key indistinguishable from a typo)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-proportion-baseline.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                out: dict[str, list[str]] = {}
                for s in reg.get("steps", []):
                    step = s.get("uiShotStep")
                    if not step:
                        continue
                    sels: list[str] = []
                    for el in s.get("elements") or []:
                        for key in ("selector", "mustNotOverlapSelector"):
                            sel = el.get(key)
                            if sel and sel not in sels:
                                sels.append(sel)
                        share = el.get("minShareOfSelector")
                        if isinstance(share, dict):
                            sel = share.get("selector")
                            if sel and sel not in sels:
                                sels.append(sel)
                    for sel in s.get("absentSelectors") or []:
                        if sel and sel not in sels:
                            sels.append(sel)
                    for sel in s.get("requiredSelectors") or []:
                        if sel and sel not in sels:
                            sels.append(sel)
                    # Tempdoc 814 §D8 — a `nonScrollableSelectors` entry is judged off its OWN
                    # captured element (`scrollable`), so it must be in the probe's union too.
                    for sel in s.get("nonScrollableSelectors") or []:
                        if sel and sel not in sels:
                            sels.append(sel)
                    out[step] = sels
                return out
            except Exception:
                return {}
    return {}


def _find_proportion_forbidden_text() -> dict[str, list[str]]:
    """Per-step ``forbiddenVisibleText`` phrases from the SAME proportion register
    (tempdoc 814 review pass): literal phrases that must not render VISIBLY in a given
    step's capture. Counted by the same visibility-filtered probe the status-facts
    register feeds, so there is one probe and one output shape (``statusFacts``); only the
    phrase's provenance and its ceiling differ (register-wide `maxPersistentRenders`
    vs. step-scoped 0). Best-effort, mirroring `_find_proportion_baseline`'s fail-open
    contract."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-proportion-baseline.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                out: dict[str, list[str]] = {}
                for s in reg.get("steps", []):
                    step = s.get("uiShotStep")
                    if not step:
                        continue
                    out[step] = [p for p in (s.get("forbiddenVisibleText") or []) if p]
                return out
            except Exception:
                return {}
    return {}


_PROPORTION_BASELINE_SELECTORS = _find_proportion_baseline()
_PROPORTION_FORBIDDEN_TEXT = _find_proportion_forbidden_text()


def _find_status_facts() -> list[str]:
    """Load the shared status-facts register (tempdoc 814 §D5): the ONE authority for
    which literal phrases the D5 single-authority-render rule tracks (e.g. "Reduced
    capability"). Returns the phrase list — the per-phrase `maxPersistentRenders`
    ceiling is a `ui-proportion-gate` concern (it cross-references the same register),
    not this capture's. Best-effort: an absent/garbled register yields an empty list
    (the probe then reports no facts, never crashes a capture), mirroring
    `_find_a11y_baseline` / `_find_proportion_baseline`'s fail-open contract."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "status-facts.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                return [f["phrase"] for f in reg.get("facts") or [] if f.get("phrase")]
            except Exception:
                return []
    return []


_STATUS_FACT_PHRASES = _find_status_facts()


def split_new_vs_known(
    axe_violations: list[dict[str, Any]], known: list[str] | None,
) -> tuple[list[str] | None, int | None]:
    """Baseline-relative split (tempdoc 615 §13 Move 2). Given the axe violations and
    a surface's accepted rule-id list, return ``(new_rule_ids, known_count)`` — the
    rule ids NOT in the baseline (the actionable signal) and how many were known.
    Returns ``(None, None)`` when ``known is None`` (the surface has no baseline →
    caller reports a raw count, makes no NEW/known claim). The ONE implementation
    shared by the capture summary AND the `ui-a11y-gate` (no second copy to drift)."""
    if known is None:
        return None, None
    known_set = set(known)
    new_ids = [v.get("id") for v in axe_violations if v.get("id") not in known_set]
    return new_ids, len(axe_violations) - len(new_ids)


# Console-error categorization (tempdoc 615 §12 fix #1 / §14 F1). In the default
# no-backend auto-serve mode the console is dominated by ENVIRONMENT noise — the
# Vite dev-serve proxies `/api/*` to whatever stack is running (or 502s if none),
# and HMR/dev tooling chatters. Those fire non-deterministically run-to-run and
# are NOT defects in the UI under test. Categorizing them lets the summary flag
# mean "a real JS error", not "the environment has no backend".
_ENV_NETWORK_MARKERS = (
    "failed to load resource",
    "net::err",
    "err_connection",
    "err_network",
    "the server responded with a status of",
    " 502",
    " 503",
    " 504",
    " 500 ",
    " 404",
    "/api/",
    "fetch failed",
    "networkerror",
)
_DEV_NOISE_MARKERS = (
    "[vite]",
    "[hmr]",
    "hot module",
    "websocket connection to 'ws",
    "react devtools",
)
# FE-emitted STRUCTURED errors that mention `/api/` but are REAL app-tier signals,
# not env noise — they must win over `_ENV_NETWORK_MARKERS`. `[WireContract]` is the
# parse-boundary (non-fail-open) schema-mismatch: in production it means backend
# drift, so an agent must see it. (Found dogfooding the route-mock experiment: empty
# stub fixtures tripped it, and it was being hidden as env because it contains `/api/`.)
_APP_MARKERS = (
    "[wirecontract]",
    "did not match the generated schema",
)
# Framework SELF-CHECK signals that are NOT authoritative ground truth (tempdoc 615 §43).
# `[jf-control] no accessible name` is jf-control's DEV-only heuristic: it fires on
# `!resolvedName() && !this.textContent` — but for a nested `jf-button` (which forwards a
# `<slot>` into jf-control) the control's own `textContent` is the EMPTY forwarded slot, so it
# FALSE-POSITIVES on the legitimate slot-text-only button pattern (Button.ts's own recommended
# form). The REAL accessible name flattens through the slots correctly (verified: "Load"/"Grant
# family" ARE named — accname slot-flatten + axe's `button-name` agree it is NOT a violation, §43).
# We demote it because it is an UNRELIABLE, false-positive-prone heuristic — NOT because full
# coverage is proven elsewhere (the §43 independent review's caveat: do not overclaim). Real
# nameless controls are caught by axe's `button-name`/control-name on captured surfaces. (The
# build-time `controls-a11y` gate that also statically forbade a nameless top-level `<jf-control>`
# was retired in tempdoc 930 chunk H.) Acknowledged residual: a nameless control on an *un-captured*
# surface or state is caught by neither — but that case was only ever "caught" by this same
# false-positive-prone self-check, so the right fix is widening axe capture coverage, not trusting a
# noisy signal. Counting it as `app` pollutes `console_real` with phantoms (the §33 trust-pollution class,
# in a11y guise); so it is bucketed informational, excluded from `console_real`.
_SELFCHECK_MARKERS = (
    "[jf-control] no accessible name",
)


def _classify_console(err: dict[str, str]) -> str:
    """Bucket a console entry into 'framework-selfcheck' | 'env-network' | 'dev-noise' | 'app'.

    Only 'app' entries are genuine UI/contract defects worth flagging (counted in
    `console_real`). `pageerror` (uncaught JS) is 'app' unless it is plainly a failed
    backend fetch. 'framework-selfcheck' is an unreliable framework heuristic that a more
    authoritative tier (axe) supersedes — informational, NOT a real defect.
    """
    blob = ((err.get("text") or "") + " " + (err.get("location") or "")).lower()
    if any(m in blob for m in _APP_MARKERS):
        return "app"
    if any(m in blob for m in _SELFCHECK_MARKERS):
        return "framework-selfcheck"
    if any(m in blob for m in _DEV_NOISE_MARKERS):
        return "dev-noise"
    if any(m in blob for m in _ENV_NETWORK_MARKERS):
        return "env-network"
    return "app"


class ConsoleSink:
    """Collects console.error + pageerror over a page's lifetime.

    Port of `setupConsoleCollector` (modules/ui-web/e2e/ai-harness.ts:402). Attach
    once at page creation; the errors accumulate until the measurement is captured.
    """

    def __init__(self) -> None:
        self.errors: list[dict[str, str]] = []

    def attach(self, page) -> None:
        def _on_console(msg) -> None:
            try:
                if msg.type == "error":
                    loc = msg.location or {}
                    self.errors.append({
                        "type": "console.error",
                        "text": (msg.text or "")[:500],
                        "location": loc.get("url", "") if isinstance(loc, dict) else "",
                    })
            except Exception:
                pass

        def _on_pageerror(err) -> None:
            self.errors.append({"type": "pageerror", "text": str(err)[:500], "location": ""})

        page.on("console", _on_console)
        page.on("pageerror", _on_pageerror)


# The shell is a Lit web-components app: its landmarks/roles live inside SHADOW
# roots, which `document.querySelector` (light DOM) cannot see. These probes pierce
# shadow boundaries so the geometry + a11y facts reflect the real rendered UI.
_JS_DEEP = """
    const deepQuery = (selector, root) => {
        root = root || document;
        const hit = root.querySelector(selector);
        if (hit) return hit;
        for (const el of root.querySelectorAll('*')) {
            if (el.shadowRoot) { const f = deepQuery(selector, el.shadowRoot); if (f) return f; }
        }
        return null;
    };
    const deepAll = (root, acc, depth) => {
        root = root || document; acc = acc || []; depth = depth || 0;
        if (depth > 40) return acc;
        for (const el of root.querySelectorAll('*')) {
            acc.push(el);
            if (el.shadowRoot) deepAll(el.shadowRoot, acc, depth + 1);
        }
        return acc;
    };
"""

async def assert_search_state(page, name: str) -> None:
    """A clean axe scan counts only after the promised Search state is visible.

    Run at measurement time too, so deleting content after setup cannot produce an empty pass.
    Non-Search surfaces have their own setup predicates and do not claim Search coverage.
    """
    from .ui_selectors import SEARCH_STATE_WITNESSES, CSS_SV3_WINDOW
    selectors = SEARCH_STATE_WITNESSES.get(name.removesuffix("-light"))
    if selectors is None:
        return
    root = page.locator(CSS_SV3_WINDOW + ":visible")
    if await root.count() != 1:
        raise AssertionError(f"{name}: expected exactly one visible Search window")
    registered = await root.evaluate("el => !!customElements.get(el.localName) && !!el.shadowRoot")
    if not registered:
        raise AssertionError(f"{name}: Search custom element is not registered/rendered")
    for selector in selectors:
        loc = root.locator(selector).first
        if await loc.count() == 0 or not await loc.is_visible():
            raise AssertionError(f"{name}: missing visible Search state witness {selector}")
        valid = await loc.evaluate("""el => {
            const r = el.getBoundingClientRect();
            const text = node => {
                if (node.nodeType === Node.TEXT_NODE) return node.textContent || '';
                if (node instanceof Element && ['STYLE','SCRIPT','TEMPLATE'].includes(node.tagName)) return '';
                return [...node.childNodes].map(text).join(' ') + (node.shadowRoot ? text(node.shadowRoot) : '');
            };
            return r.width > 0 && r.height > 0 && el.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})
                && (el.matches('textarea,input') || text(el).trim().length > 0);
        }""")
        if not valid:
            raise AssertionError(f"{name}: empty/hidden Search state witness {selector}")


    drafts = {"chat-mode": "What is this file about?", "chat-composer-small": "A longer draft remains reachable in a small window."}
    expected = drafts.get(name.removesuffix("-light"))
    if expected is not None and await root.locator('[data-testid="sv3-composer-input"]').input_value() != expected:
        raise AssertionError(f"{name}: missing prepared Search draft witness")


# Geometry + computed-style facts for a generic key set (landmarks, stage, rail,
# inspector, h1, the focused element) + document overflow flags. Shadow-piercing.
# Takes `extraSels` (tempdoc 697): the current step's registered chrome-proportion
# baseline selectors, unioned in so their heights land in the same `elements` map
# under their own selector key — one geometry probe, no second capture path.
#
# Tempdoc 814 (W4 activation): also walks the WHOLE shadow-pierced document for every
# scrollable region — a port of `ui_check.py`'s `_JS_GET_SCROLL_DELTA` (which finds only
# the single BEST scrollable region, scoped to `.zone-stage`/`main`, for the screenshot's
# scroll-to-top step), generalized to (a) shadow-pierce via `deepAll` (the light-DOM-only
# original can't see scrollers inside a component's own shadow root) and (b) enumerate
# EVERY scrollable region, not just the largest — so `geometry.scrollableCount` is a real
# "how many scroll affordances does this surface have" fact for D3's one-scroller rule
# (`ui_proportion_gate.py`'s `maxScrollableRegions` step-level check), not a proxy for it.
_JS_GEOMETRY = """(extraSels) => {
""" + _JS_DEEP + """
    // Tempdoc 814 §D8 — the SAME predicate the scrollableRegions walk below applies, evaluated
    // for a REGISTERED selector: overflow-y auto/scroll AND real overflow. Reported per element so
    // `ui_proportion_gate`'s `nonScrollableSelectors` can say "selector X must not be a scroller"
    // WITHOUT string-matching the region walk's `describeScrollable()` label (which truncates to
    // the first three class tokens and would silently stop matching when a class is added). Same
    // fact, addressed by the registered selector instead of by a rendered description.
    const isScroller = (el) => {
        const oy = getComputedStyle(el).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') return false;
        return Math.round((el.scrollHeight || 0) - (el.clientHeight || 0)) > 1;
    };
    // Tempdoc 816 §4a.1/R2 — the CONTENT-UNIT resolver: how many px is `1ch` in THIS element's own
    // rendered font. A role's bounds are stored in `ch` (a bound follows text flow, not pixels), so the
    // gate needs the per-element conversion to judge a captured rect against them — the same token is a
    // different pixel count on 11px chip chrome and a 16px banner, which is the point.
    //
    // ROUTE: canvas `measureText('0')` over a font string composed from the computed longhands, NOT a
    // DOM probe span. `ch` IS the advance measure of the "0" glyph, so this is the definition rather
    // than an approximation; the shorthand `cs.font` is not used because Chromium returns "" for it
    // whenever the longhands are not all consistent, and a probe ELEMENT cannot be appended inside the
    // element that matters most here (a <textarea> takes no element children). Null when the measure
    // comes back non-positive (no font available) — the gate then falls back to fontSize * 0.5 and
    // says so, rather than silently judging against a wrong number.
    const chPxOf = (el, cs) => {
        try {
            const ctx = (chPxOf._c || (chPxOf._c = document.createElement('canvas'))).getContext('2d');
            ctx.font = cs.fontStyle + ' ' + cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
            const w = ctx.measureText('0').width;
            return w > 0 ? Math.round(w * 100) / 100 : null;
        } catch (e) { return null; }
    };
    const pick = (el) => {
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return {
            tag: el.tagName.toLowerCase(),
            role: el.getAttribute && el.getAttribute('role') || null,
            label: el.getAttribute && el.getAttribute('aria-label') || null,
            rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
            visible: r.width > 0 && r.height > 0 && el.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}),
            z: cs.zIndex, fontSize: cs.fontSize, display: cs.display,
            chPx: chPxOf(el, cs),
            scrollable: isScroller(el),
            scrollDelta: Math.round((el.scrollHeight || 0) - (el.clientHeight || 0)),
        };
    };
    const describeScrollable = (el) => {
        let s = el.tagName.toLowerCase();
        if (el.id) s += '#' + el.id;
        const cls = (el.className && typeof el.className === 'string')
            ? el.className.trim().split(/\\s+/).filter(Boolean) : [];
        if (cls.length) s += '.' + cls.slice(0, 3).join('.');
        const testid = el.getAttribute && el.getAttribute('data-testid');
        if (testid) s += '[data-testid="' + testid + '"]';
        return s;
    };
    const sels = ['[role="banner"]','[role="navigation"]','[role="main"]','main','.zone-stage','h1',
                  '[data-surface-id]','jf-rail','jf-stage','jf-inspector-pane'].concat(extraSels || []);
    const elements = {};
    for (const s of sels) { const e = deepQuery(s); if (e) elements[s] = pick(e); }
    const scrollableRegions = [];
    for (const el of deepAll()) {
        if (!el.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) continue;
        const r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) continue;
        const cs = getComputedStyle(el);
        const oy = cs.overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        const d = Math.round((el.scrollHeight || 0) - (el.clientHeight || 0));
        if (d <= 1) continue;
        scrollableRegions.push({ selector: describeScrollable(el), scrollDelta: d });
    }
    const de = document.documentElement;
    return {
        elements,
        scrollableRegions,
        scrollableCount: scrollableRegions.length,
        focused: pick(document.activeElement),
        document: {
            scrollWidth: de.scrollWidth, clientWidth: de.clientWidth,
            scrollHeight: de.scrollHeight, clientHeight: de.clientHeight,
            overflowX: de.scrollWidth > de.clientWidth + 1,
            overflowY: de.scrollHeight > de.clientHeight + 1,
        },
    };
}"""

# Tempdoc 814 §D5/§D7.3 — the status-fact SINGLETON probe: given a phrase list (loaded
# from governance/status-facts.v1.json, plus any step-scoped `forbiddenVisibleText`),
# count how many times each phrase appears across the shadow-pierced deep DOM text.
# Counts LEAF elements only (elements with zero element children) so a phrase is not
# double-counted through a parent's aggregated textContent — a `<slot>` with nothing
# directly assigned to it as a DOM child is a leaf too (its own textContent is empty
# unless it carries fallback content), so slotted light-DOM text is counted once, at its
# own leaf, not a second time through the slot.
#
# VISIBILITY FILTER (814 review pass, 2026-08-06): the count must mean "renders in
# PERSISTENT CHROME the user can see", so a node the user cannot see is not a render.
# Without this the probe counted the 1x1 `.visually-hidden` aria-live announcer
# (`data-testid="verdict-announcer"`, StatusDeck.ts) — which mirrors the verdict headline
# BY DESIGN — as one of its own "persistent renders". Two consequences, both bad: the
# register's positive case was witnessing an invisible node (vacuity), and any surface
# that visibly showed the same headline would count 2 and FALSE-FAIL a rule it does not
# break. Skipped: `display:none` / `visibility:hidden` (both computed, so an inherited
# `visibility:hidden` is caught at the leaf), and an effectively-zero render box (width or
# height <= 1px — the visually-hidden clip pattern is exactly `width:1px;height:1px`,
# `ambientStyles.ts`). A `display:contents` leaf has no box of its own, so its own text is
# measured with a Range instead of being mistaken for hidden.
_JS_STATUS_FACTS = """(phrases) => {
""" + _JS_DEEP + """
    const isVisible = (el) => {
        const cs = getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') return false;
        let r = el.getBoundingClientRect();
        if ((r.width <= 1 || r.height <= 1) && cs.display === 'contents') {
            try {
                const rng = document.createRange();
                rng.selectNodeContents(el);
                r = rng.getBoundingClientRect();
            } catch (e) { /* fall through to the box test below */ }
        }
        return r.width > 1 && r.height > 1;
    };
    let text = '';
    for (const el of deepAll()) {
        if (el.children && el.children.length === 0) {
            const t = (el.textContent || '').trim();
            if (t && isVisible(el)) text += ' ' + t;
        }
    }
    return (phrases || []).map((p) => ({ phrase: p, count: text.split(p).length - 1 }));
}"""

# Shadow-piercing a11y landmark/role/heading collection — the live "perception
# channel" (§6.3). Playwright's native `accessibility.snapshot()` is unreliable on
# this shadow-DOM app (returns None), so this is the authoritative a11y fact set.
_JS_A11Y = """() => {
""" + _JS_DEEP + """
    const out = [];
    const landmarkTags = new Set(['main','nav','header','footer','aside','section']);
    for (const el of deepAll()) {
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute && el.getAttribute('role');
        const heading = /^h[1-3]$/.test(tag);
        if (role || landmarkTags.has(tag) || heading) {
            out.push({
                tag, role: role || null,
                label: (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('aria-current'))) || null,
                text: (el.textContent || '').trim().slice(0, 48),
            });
        }
    }
    return out;
}"""

_JS_AXE = """async () => {
    try {
        const res = await axe.run(document, { runOnly: ['wcag2a','wcag2aa','wcag21a','wcag21aa'] });
        return {
            passCount: res.passes.length,
            violations: res.violations.map(v => ({
                id: v.id, impact: v.impact, help: v.help,
                nodes: (v.nodes || []).slice(0, 5).map(n => ({
                    selector: (n.target || []).join(' '), summary: n.failureSummary,
                })),
            })),
        };
    } catch (e) { return { error: String(e) }; }
}"""


async def capture_measure(
    page, name: str, output_dir: Path, console_sink: ConsoleSink | None, *, theme: str = "dark",
) -> tuple[str, dict[str, Any]]:
    """Write ``<name>.measure.json`` next to the screenshot. Returns (path, summary).

    The summary is a compact, agent-readable fact sheet (no PNG needed for a
    correctness judgment). Never raises — measurement is best-effort and must not
    fail a capture.
    """
    await assert_search_state(page, name)
    # Authoritative a11y fact set: a shadow-piercing landmark/role/heading collection
    # (the native `accessibility.snapshot()` is unreliable on this shadow-DOM app).
    a11y_landmarks: list[dict[str, Any]] = []
    try:
        a11y_landmarks = await page.evaluate(_JS_A11Y) or []
    except Exception:
        pass
    # NB: Playwright's native `page.accessibility.snapshot()` returns None on this
    # shadow-DOM Lit app, so it was pure JSON bloat — dropped (§12 fix #6). The
    # shadow-piercing `a11y_landmarks` above IS the authoritative perception channel.

    geometry = None
    try:
        # tempdoc 697: union this step's registered chrome-proportion baseline
        # selectors into the fixed geometry probe (fail-open — an empty/absent
        # baseline degrades to the original fixed-selector capture).
        extra_sels = _PROPORTION_BASELINE_SELECTORS.get(name, [])
        geometry = await page.evaluate(_JS_GEOMETRY, extra_sels)
    except Exception:
        pass

    axe: dict[str, Any] | None = None
    if _AXE_PATH is not None:
        try:
            await page.add_script_tag(path=str(_AXE_PATH))
            axe = await page.evaluate(_JS_AXE)
        except Exception as e:
            axe = {"error": str(e)[:200]}

    # Tempdoc 814 §D5/§D7.3 — the status-fact singleton probe (best-effort, empty register
    # or a failed evaluate both degrade to an empty list, never fail the capture). The
    # phrase set is the register-wide status facts UNIONED with this step's own
    # `forbiddenVisibleText` (review pass) — one probe, one output list; the ceiling each
    # phrase is judged against lives in `ui_proportion_gate.py`.
    phrases = list(_STATUS_FACT_PHRASES)
    for p in _PROPORTION_FORBIDDEN_TEXT.get(name, []):
        if p not in phrases:
            phrases.append(p)
    status_facts: list[dict[str, Any]] = []
    if phrases:
        try:
            status_facts = await page.evaluate(_JS_STATUS_FACTS, phrases) or []
        except Exception:
            pass

    console_errors = list(console_sink.errors) if console_sink else []
    for e in console_errors:
        e["category"] = _classify_console(e)
    console_app = [e for e in console_errors if e["category"] == "app"]

    measure: dict[str, Any] = {
        "schema": "ui-measure.v1",
        "name": name,
        "url": page.url,
        "viewport": page.viewport_size or {},
        "theme": theme,
        "a11y_landmarks": a11y_landmarks,
        "axe": axe,
        "geometry": geometry,
        "statusFacts": status_facts,
        "console_errors": console_errors,
    }
    out = output_dir / f"{name}.measure.json"
    out.write_text(json.dumps(measure, indent=2) + "\n", encoding="utf-8")

    axe_v = (axe or {}).get("violations") or []
    serious = sum(1 for v in axe_v if v.get("impact") in ("serious", "critical"))
    # Baseline-relative split (§13 Move 2): a violation whose rule id is NOT in this
    # step's known/accepted set is NEW — the actionable signal. Steps with no baseline
    # entry (`known is None`) report a raw count, no NEW/known claim.
    axe_new, axe_known = split_new_vs_known(axe_v, _A11Y_BASELINE.get(name))
    doc = (geometry or {}).get("document", {}) if geometry else {}
    overflow = [ax for ax, key in (("x", "overflowX"), ("y", "overflowY")) if doc.get(key)]
    geo_els = len((geometry or {}).get("elements", {})) if geometry else 0
    scrollable_count = (geometry or {}).get("scrollableCount") if geometry else None
    dup_facts = [f["phrase"] for f in status_facts if (f.get("count") or 0) > 1]
    summary = {
        "measure_path": str(out),
        "a11y_landmarks": len(a11y_landmarks),
        "geometry_elements": geo_els,
        "scrollable_regions": scrollable_count,
        "axe_violations": len(axe_v),
        "axe_serious": serious,
        # Baseline-relative (§13 Move 2): NEW rule ids vs this surface's accepted set,
        # and how many were known. Both None when the step has no baseline entry.
        "axe_new": axe_new,
        "axe_known": axe_known,
        "console_errors": len(console_errors),
        # Only 'app'-category console errors are real defects; env/dev noise
        # (no-backend 502s, HMR) is counted but NOT flagged (§12 #1 / §14 F1).
        "console_real": len(console_app),
        "console_env": len(console_errors) - len(console_app),
        "overflow": overflow or None,
        "flags": (
            ([f"console-real:{len(console_app)}"] if console_app else [])
            # With a baseline, the loud flag is NEW-only — known debt stays quiet even
            # if serious. Without a baseline (axe_new is None), fall back to axe-serious.
            + ([f"axe-NEW:{','.join(axe_new)}"] if axe_new else [])
            + ([f"axe-serious:{serious}"] if serious and axe_new is None else [])
            # Tempdoc 814 §D5 — an informational flag only; the enforcement decision (does
            # THIS step's persistent chrome duplicate a status fact) is `ui-proportion-gate`'s
            # `statusFactsSingleton` check, which cross-references the phrase's registered
            # `maxPersistentRenders` ceiling. This flag is a raw >1-occurrence signal.
            + ([f"status-fact-dup:{','.join(dup_facts)}"] if dup_facts else [])
        ),
    }
    return str(out), summary
