"""§14 de-risk EXPERIMENT (throwaway, NOT harness production code) — tempdoc 615.

Question §14 left open: *is a deterministic, zero-env-noise `ui-shot` capture
achievable via Playwright route-mocking* — without a live backend, without
restoring the dead app-level demo-data layer? If yes, the rating moves 4 → ~7
and route-mocked fixtures become §13 Move 1's correct form.

What this does:
  1. Reuse the harness's auto-served Vite (`_resolve_ui_url`).
  2. Intercept EVERY `/api/**` request and fulfill it with a stub 200 (so the
     no-backend 502 storm — §14 F1's env noise — cannot occur).
  3. Seed the walkthrough-dismissed flag so the first-run overlay is gone.
  4. Capture the measurement companion TWICE and diff: env-console must be 0,
     and the structural facts (a11y landmarks + geometry) must be byte-stable
     run-to-run. That is the operational definition of "deterministic".

Run: python -m scripts.jsharness.experiments.route_mock_home   (or invoke directly)
It prints a verdict; it writes nothing into the harness.
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from urllib.parse import urlparse

# Make `jsharness` importable when run as a file.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from jsharness import ui_measure  # noqa: E402
from jsharness import ui_selectors as S  # noqa: E402
from jsharness.ui_shot import _resolve_ui_url  # noqa: E402

# A minimal valid v2 user-state doc with the welcome walkthrough dismissed.
# (id 'welcome' per shell-v0/substrates/manifest/canonicalManifest.ts.)
_SEED = (
    "try {"
    "localStorage.setItem('justsearch-inspector-tab','ai');"
    "localStorage.setItem('justsearch.userState.v2', JSON.stringify({"
    "  version: 2, activeProfileId: 'default', profiles: {},"
    "  walkthroughState: { welcome: { activeStepIndex: 0, completedStepIds: [], dismissed: true } }"
    "}));"
    "} catch (e) {}"
)


# Real captured wire fixtures (schema-valid). KEY FINDING (run 1): the FE parse
# boundary is NON-fail-open for /api/status + /api/knowledge/search, so an empty
# `{}` is WORSE than a 502 (it fails validation → the shell never mounts the rail).
# Fixtures must be schema-valid, so we serve the repo's captured payloads.
_FIX = Path(__file__).resolve().parents[1].parent.parent / "modules" / "ui-web" / "src" / "api" / "__fixtures__"
_BODY_STATUS = (_FIX / "status-response-live.json").read_text(encoding="utf-8")
_BODY_SEARCH = (_FIX / "search-response-live.json").read_text(encoding="utf-8")
_BODY_SETTINGS = (_FIX / "settings-v2-live.json").read_text(encoding="utf-8")


async def _fulfill_api(route):
    """Serve schema-valid fixtures for boot-critical contracts; empty 200 otherwise."""
    url = route.request.url
    if "/stream" in url or "text/event-stream" in (route.request.headers.get("accept") or ""):
        await route.fulfill(status=200, content_type="text/event-stream", body="")
        return
    body = "{}"
    if "/api/status" in url:
        body = _BODY_STATUS
    elif "/api/knowledge/search" in url:
        body = _BODY_SEARCH
    elif "/api/settings" in url:
        body = _BODY_SETTINGS
    await route.fulfill(status=200, content_type="application/json", body=body)


async def _capture_once(p, ui_url: str, out_dir: Path, tag: str) -> dict:
    browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
    try:
        ctx = await browser.new_context(viewport={"width": 1280, "height": 720}, color_scheme="dark")
        await ctx.add_init_script(_SEED)
        # Match the REST root ONLY (path starts with /api/), NOT the FE's own
        # `/src/api/*.ts` Vite modules — a glob `**/api/**` over-matches those and
        # serves them as JSON, breaking module loading (experiment finding, run 2).
        await ctx.route(lambda url: urlparse(url).path.startswith("/api/"), _fulfill_api)
        page = await ctx.new_page()
        sink = ui_measure.ConsoleSink()
        sink.attach(page)
        await page.goto(ui_url, wait_until="domcontentloaded", timeout=30_000)
        try:
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
        except Exception:
            # Diagnostic: why didn't the rail mount? Dump boot errors + DOM presence.
            shell = await page.evaluate("() => !!document.querySelector('jf-shell')")
            rail = await page.evaluate("() => { const w=(r)=>{for(const e of r.querySelectorAll('*')){if(e.tagName.toLowerCase()==='jf-rail')return true;if(e.shadowRoot&&w(e.shadowRoot))return true;}return false;}; return w(document); }")
            errs = [e for e in sink.errors if e.get("category") != "env-network"][:8] if hasattr(sink, "errors") else []
            print(f"  [diag {tag}] jf-shell={shell} jf-rail(deep)={rail} non-env-console={[e['text'][:120] for e in sink.errors][:8]}")
            raise
        await page.screenshot(path=str(out_dir / f"route-mock-home-{tag}.png"))
        _path, summary = await ui_measure.capture_measure(page, f"route-mock-home-{tag}", out_dir, sink)
        env_texts = sorted({e["text"][:90] for e in sink.errors if e.get("category") == "env-network"})
        await ctx.close()
        summary["_env_texts"] = env_texts
        return summary
    finally:
        await browser.close()


def _facts(measure_path: str) -> tuple:
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    landmarks = tuple((l.get("tag"), l.get("role"), l.get("label"), l.get("text")) for l in m["a11y_landmarks"])
    geo = json.dumps(m.get("geometry", {}).get("elements", {}), sort_keys=True)
    return landmarks, geo


async def _main() -> int:
    from playwright.async_api import async_playwright

    ui_url = _resolve_ui_url("http://localhost:5173")
    out_dir = Path("tmp/ui-shot/experiment")
    out_dir.mkdir(parents=True, exist_ok=True)

    async with async_playwright() as p:
        s1 = await _capture_once(p, ui_url, out_dir, "run1")
        s2 = await _capture_once(p, ui_url, out_dir, "run2")

    lm1, geo1 = _facts(s1["measure_path"])
    lm2, geo2 = _facts(s2["measure_path"])

    env_noise = max(s1["console_env"], s2["console_env"])
    real_err = max(s1["console_real"], s2["console_real"])
    landmarks_stable = lm1 == lm2
    geo_stable = geo1 == geo2

    print("\n=== §14 route-mock determinism experiment ===")
    print(f"ui_url:            {ui_url}")
    print(f"run1 console:      {s1['console_real']} real (+{s1['console_env']} env)")
    print(f"run2 console:      {s2['console_real']} real (+{s2['console_env']} env)")
    print(f"env console noise: {env_noise}  (target 0 — the 502 storm gone)")
    print(f"a11y landmarks:    run1={len(lm1)} run2={len(lm2)} stable={landmarks_stable}")
    print(f"geometry:          stable={geo_stable}")
    print(f"axe serious:       run1={s1['axe_serious']} run2={s2['axe_serious']}")

    if s1.get("_env_texts"):
        print("residual env errors:")
        for t in s1["_env_texts"]:
            print(f"  - {t}")

    stable = landmarks_stable and geo_stable
    print("\nVERDICT:", "DETERMINISTIC + stable (route-mocking works -- §13 Move 1 substrate confirmed)"
          if stable and env_noise == 0 else
          ("STABLE but residual env noise (route-mock works; close the residual)" if stable else
           "NOT yet stable (see numbers above)"))
    if real_err:
        print(f"NOTE: {real_err} real (app-category) console error(s) survived -- investigate, not env noise.")
    return 0 if stable else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_main()))
