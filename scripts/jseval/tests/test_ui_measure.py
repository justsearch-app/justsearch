"""Regression tests for the ui-measure console classifier (tempdoc 615 §12 #1 / §14 F1).

The classifier's failure mode is a SILENT WRONG VALUE: if it mis-buckets the
no-backend 502 noise as a real defect (or vice-versa), the summary `console-real`
flag lies and the agent learns to ignore it — exactly the dogfood finding §14
set out to fix. These pin the buckets so that can't regress.
"""

from __future__ import annotations

import pytest

from jseval.ui_measure import _classify_console, _find_a11y_baseline, _JS_STATUS_FACTS


class TestClassifyConsole:
    def test_no_backend_502_is_env(self):
        # The dominant noise in default no-backend auto-serve mode.
        err = {
            "type": "console.error",
            "text": "Failed to load resource: the server responded with a status of 502 (Bad Gateway)",
            "location": "http://127.0.0.1:5174/api/status",
        }
        assert _classify_console(err) == "env-network"

    def test_api_fetch_failure_is_env(self):
        err = {"type": "console.error", "text": "GET http://127.0.0.1:5174/api/indexing-jobs/stream net::ERR_FAILED", "location": ""}
        assert _classify_console(err) == "env-network"

    def test_vite_hmr_is_dev_noise(self):
        err = {"type": "console.error", "text": "[vite] failed to connect to websocket", "location": ""}
        assert _classify_console(err) == "dev-noise"

    def test_real_js_console_error_is_app(self):
        err = {"type": "console.error", "text": "Uncaught TypeError: Cannot read properties of undefined (reading 'foo')", "location": "jf-search-surface.ts:42"}
        assert _classify_console(err) == "app"

    def test_pageerror_is_app(self):
        err = {"type": "pageerror", "text": "ReferenceError: x is not defined", "location": ""}
        assert _classify_console(err) == "app"

    def test_pageerror_that_is_a_fetch_failure_is_env(self):
        # A thrown fetch rejection against /api is still environment, not a UI defect.
        err = {"type": "pageerror", "text": "TypeError: fetch failed for /api/health", "location": ""}
        assert _classify_console(err) == "env-network"

    def test_wirecontract_mismatch_is_app_not_env(self):
        # A WireContract schema mismatch mentions /api/ but is a REAL contract signal
        # (backend drift), NOT env noise — it must win over the env-network marker.
        # (Regression for the bug the route-mock experiment surfaced.)
        err = {
            "type": "console.error",
            "text": "[WireContract] GET /api/registry/operations did not match the generated schema (contract drift)",
            "location": "",
        }
        assert _classify_console(err) == "app"

    def test_empty_entry_defaults_to_app(self):
        # Unknown/empty → conservative 'app' so a genuine error is never silently dropped.
        assert _classify_console({"type": "console.error", "text": "", "location": ""}) == "app"

    def test_jf_control_selfcheck_is_not_app(self):
        # Tempdoc 615 §43: jf-control's DEV self-check FALSE-POSITIVES on the nested slot-text-only
        # jf-button pattern (its own textContent is the empty forwarded <slot>), while the REAL
        # accessible name flattens correctly and axe does NOT flag button-name. It is an unreliable,
        # false-positive-prone heuristic, so it must NOT count as a real ('app') defect — else it
        # pollutes console_real with phantoms (the §33 trust-pollution class). Real nameless controls
        # remain caught by axe on captured surfaces (§43 review caveat:
        # we demote because it's noisy, not because coverage is proven complete).
        err = {
            "type": "console.error",
            "text": "[jf-control] no accessible name — set `operation-id`, a non-empty `label`, or slot text "
                    "(559 Authority V §11: a nameless control is unrepresentable through the primitive).",
            "location": "",
        }
        assert _classify_console(err) == "framework-selfcheck"

    def test_wirecontract_still_wins_over_selfcheck(self):
        # A genuine app-tier signal must still classify 'app' (the selfcheck demotion is narrow).
        err = {"type": "console.error",
               "text": "[WireContract] GET /api/x did not match the generated schema", "location": ""}
        assert _classify_console(err) == "app"


class TestA11yBaseline:
    """The shared baseline register (§13 Move 2) — the loader maps ui-shot steps to
    their known/accepted axe rules so the summary can flag NEW-vs-known."""

    def test_loads_register_keyed_by_uishot_step(self):
        base = _find_a11y_baseline()
        assert isinstance(base, dict)
        # The structural view steps must be present (a missing 'home' would silently
        # drop the NEW-vs-known signal for the search surface).
        for step in ("home", "library", "settings", "health", "ai-brain", "help"):
            assert step in base, f"baseline missing ui-shot step '{step}'"
            assert isinstance(base[step], list)

    def test_known_rules_are_rule_id_strings(self):
        base = _find_a11y_baseline()
        for step, rules in base.items():
            for r in rules:
                assert isinstance(r, str) and r, f"{step} has a non-string knownRule"

    def test_unmapped_step_is_absent(self):
        # A step with no baseline entry must be ABSENT (→ raw-count path, no false
        # 'all-known' claim), not present-with-empty.
        base = _find_a11y_baseline()
        assert "inspector-open" not in base


def _count_phrases(html: str, phrases: list[str]) -> dict[str, int]:
    """Run the REAL `_JS_STATUS_FACTS` probe against a page — the only honest way to test
    a browser-side probe (a Python re-implementation would test the copy, not the probe).

    Skips (never fails) where the ui-shot harness's own browser stack is absent, so a
    checkout without `pip install jseval[ui]` / `playwright install chromium` still runs
    the rest of the suite."""
    sync_playwright = pytest.importorskip(
        "playwright.sync_api", reason="playwright (the ui-shot harness dependency) not installed",
    ).sync_playwright

    with sync_playwright() as p:
        try:
            browser = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 — no browser binary is a SKIP, not a failure
            pytest.skip(f"chromium unavailable for the probe test: {str(e)[:120]}")
        try:
            page = browser.new_page()
            page.set_content(html)
            rows = page.evaluate(_JS_STATUS_FACTS, phrases)
        finally:
            browser.close()
    return {r["phrase"]: r["count"] for r in rows}


class TestStatusFactsVisibility:
    """Tempdoc 814 §D5/§D7.3 review pass — the status-fact probe counts PERSISTENT RENDERS,
    so a node the user cannot see is not one.

    The defect this pins: the probe concatenated every leaf's text with no visibility
    filter, so StatusDeck's 1x1 `.visually-hidden` aria-live announcer
    (`data-testid="verdict-announcer"`) — which mirrors the verdict headline BY DESIGN —
    counted as a render. That made the register's "positive case" a measurement of an
    invisible node, and would have FALSE-FAILED any surface that legitimately showed the
    headline once (count 2 against a ceiling of 1)."""

    def test_visible_leaf_is_counted(self):
        counts = _count_phrases(
            "<div><span>Service degraded</span></div>", ["Service degraded"],
        )
        assert counts["Service degraded"] == 1

    def test_visually_hidden_announcer_is_not_a_render(self):
        # The exact `.visually-hidden` recipe from primitives/ambientStyles.ts.
        html = """
        <style>.visually-hidden{position:absolute;width:1px;height:1px;padding:0;
          margin:-1px;overflow:hidden;clip:rect(0 0 0 0);clip-path:inset(50%);
          white-space:nowrap;border:0;}</style>
        <div class="visually-hidden" data-testid="verdict-announcer">Service degraded</div>
        """
        assert _count_phrases(html, ["Service degraded"])["Service degraded"] == 0

    def test_a_visible_render_beside_the_announcer_counts_once(self):
        # The false-positive hazard, stated as a test: banner + announcer must read 1, not 2.
        html = """
        <style>.visually-hidden{position:absolute;width:1px;height:1px;overflow:hidden;
          clip:rect(0 0 0 0);clip-path:inset(50%);}</style>
        <div class="visually-hidden">Service degraded</div>
        <div><span>Service degraded</span></div>
        """
        assert _count_phrases(html, ["Service degraded"])["Service degraded"] == 1

    def test_display_none_is_not_a_render(self):
        html = "<div style='display:none'><span>Over budget +3</span></div>"
        assert _count_phrases(html, ["Over budget"])["Over budget"] == 0

    def test_visibility_hidden_is_not_a_render(self):
        # Inherited `visibility` is why the check reads the LEAF's computed style.
        html = "<div style='visibility:hidden'><span>Over budget +3</span></div>"
        assert _count_phrases(html, ["Over budget"])["Over budget"] == 0

    def test_display_contents_leaf_is_still_counted(self):
        # A `display:contents` leaf has no box of its own; measuring its text (Range)
        # instead keeps it from being mistaken for hidden.
        html = "<div><span style='display:contents'>Sources · 4</span></div>"
        assert _count_phrases(html, ["Sources ·"])["Sources ·"] == 1

    def test_shadow_dom_is_still_pierced(self):
        html = """
        <div id="host"></div>
        <script>
          const r = document.getElementById('host').attachShadow({mode:'open'});
          r.innerHTML = '<span>Sources · 4</span>';
        </script>
        """
        assert _count_phrases(html, ["Sources ·"])["Sources ·"] == 1

    def test_parent_text_is_not_double_counted(self):
        # The pre-existing leaf-only guarantee must survive the visibility filter.
        html = "<div><p><span>Over budget +3</span></p></div>"
        assert _count_phrases(html, ["Over budget"])["Over budget"] == 1


def test_geometry_rejects_hidden_and_closed_details_and_does_not_count_their_scrollers():
    from jseval.ui_measure import _JS_GEOMETRY
    sync_playwright = pytest.importorskip(
        "playwright.sync_api", reason="playwright (the ui-shot harness dependency) not installed",
    ).sync_playwright
    with sync_playwright() as runtime:
        browser = runtime.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            page = browser.new_page()
            page.set_content("""<style>.scroll {height:50px;width:100px;overflow:auto}.tall {height:200px}</style>
                <div id="visible" class="scroll"><div class="tall">Visible</div></div>
                <div style="display:none"><div id="hidden" class="scroll"><div class="tall">Hidden</div></div></div>
                <details><summary>Closed</summary><div id="closed" class="scroll"><div class="tall">Closed</div></div></details>
                <div id="zero" style="width:0;height:0"></div>""")
            geometry = page.evaluate(_JS_GEOMETRY, ["#visible", "#hidden", "#closed", "#zero"])
            assert geometry["elements"]["#visible"]["visible"] is True
            for selector in ("#hidden", "#closed", "#zero"):
                assert geometry["elements"][selector]["visible"] is False
            assert geometry["scrollableCount"] == 1
        finally:
            browser.close()
