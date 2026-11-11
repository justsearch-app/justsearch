"""Shell S6 SP8/SP10: the palette steps measure the app's one palette where it lives.

The palette is not inside Search: it sits in the shell's overlay host, a sibling of every window.
Its forced-colours steps (dark and light) must witness the open palette there before the screenshot
and the axe scan, and a palette step whose palette is not open must fail, never pass on whatever is
beneath it or skip its witness.
"""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

# The app's shape in miniature: <jf-shell> holds the Search window and, as its sibling, the palette.
_SHELL = """
<script>
  const define = (tag, html) => customElements.define(tag, class extends HTMLElement {
    constructor() { super(); this.attachShadow({mode: 'open'}).innerHTML = html; }
  });
  define('jf-sv3-window', '<textarea data-testid="sv3-composer-input">draft</textarea>');
  define('jf-command-palette', `
    <style>:host { display: none } :host([open]) { display: block }</style>
    <div role="dialog" aria-modal="true" aria-label="Command palette">
      <input role="combobox" aria-label="Search" aria-controls="palette-listbox" value="">
      <div role="listbox" id="palette-listbox">
        <div role="option" id="palette-opt-0" aria-selected="false">Toggle Command Palette</div>
        <div role="option" id="palette-opt-1" aria-selected="true">Change indexing rules</div>
      </div>
    </div>`);
  define('jf-shell', '<jf-sv3-window></jf-sv3-window><jf-command-palette></jf-command-palette>');
</script>
<jf-shell></jf-shell>
"""


def _witness(open_palette: bool, step: str) -> str | None:
    """Run the capture-time witness for ``step``; the AssertionError text, or None when it holds."""
    from jsharness.ui_measure import assert_search_state
    async_playwright = pytest.importorskip(
        "playwright.async_api", reason="playwright (the ui-shot harness dependency) not installed",
    ).async_playwright

    async def run() -> str | None:
        async with async_playwright() as runtime:
            browser = await runtime.chromium.launch(headless=True, args=["--disable-gpu"])
            try:
                page = await browser.new_page()
                await page.set_content(_SHELL)
                if open_palette:
                    await page.evaluate("""() => document.querySelector('jf-shell').shadowRoot
                        .querySelector('jf-command-palette').setAttribute('open', '')""")
                try:
                    await assert_search_state(page, step)
                except AssertionError as error:
                    return str(error)
                return None
            finally:
                await browser.close()

    return asyncio.run(run())


@pytest.mark.parametrize("step", ["palette-forced-colors", "palette-forced-colors-light"])
def test_palette_step_witnesses_the_open_palette_beside_search(step):
    assert _witness(True, step) is None


@pytest.mark.parametrize("step", ["palette-forced-colors", "palette-forced-colors-light"])
def test_palette_step_fails_when_the_palette_is_not_open(step):
    error = _witness(False, step)
    assert error is not None and "palette" in error


def test_every_registered_palette_step_runs_with_a_palette_witness_in_both_themes():
    from jsharness import ui_check
    from jsharness.ui_selectors import PALETTE_STATE_WITNESSES
    baseline = json.loads((Path(__file__).resolve().parents[3] / "governance" / "ui-a11y-baseline.v1.json")
                          .read_text(encoding="utf-8"))
    registered = {s["uiShotStep"] for s in baseline["surfaces"] if s.get("surface") == "palette"}
    assert {"palette-forced-colors", "palette-forced-colors-light"} <= registered
    steps = {step.name: step for step in ui_check._build_steps("http://127.0.0.1:1", 0, 1000)}
    for name in registered:
        assert name in steps, f"{name} is registered for axe but is not a harness step"
        assert name.removesuffix("-light") in PALETTE_STATE_WITNESSES, f"{name} would skip its witness"
    light = steps["palette-forced-colors-light"]
    assert light.color_scheme == "light" and light.forced_colors == "active"
