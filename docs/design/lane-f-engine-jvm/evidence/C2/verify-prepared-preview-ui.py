"""Reproducible lane witness using jseval's real Lit isolated-step/measurement harness.

Run from the lane root: python docs/design/lane-f-engine-jvm/evidence/C2/verify-prepared-preview-ui.py --output-dir tmp/preview-ui
Structural fixtures only; this never approves a backend mutation or claims live API proof.
"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
import json
from pathlib import Path
import sys

ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / "settings.gradle.kts").exists())
sys.path.insert(0, str(ROOT / "scripts" / "jseval"))
from jseval import ui_check, ui_shot  # noqa: E402
from playwright.async_api import async_playwright  # noqa: E402


async def witness(output: Path, url: str) -> dict:
    rows = []
    async with async_playwright() as playwright:
        for width in (1568, 840):
            for size in (420, 8192):
                name = f"prepared-approval-{width}-{size}"
                prefix = "Write new note to F:/designated-notes/"
                summary = prefix + "p" * (size - len(prefix) - 3) + ".md"
                facts = {}

                async def setup(page):
                    await page.set_viewport_size({"width": width, "height": 900})
                    prompt = {"pendingId": "lane-preview-proof", "operationId": "core.file-note",
                              "gateBehavior": "TYPED_CONFIRM", "riskTier": "MEDIUM",
                              "argsSummary": summary, "purpose": "Create a new note at this frozen target"}
                    host = page.locator("jf-authorization-host")
                    await host.wait_for(state="attached", timeout=15000)
                    # Reuse the actual app graph's URL, including a Vite HMR query when present.
                    # Importing a bare URL after HMR would create a second broker singleton.
                    broker_urls = await page.evaluate("""() => [...new Set(performance.getEntriesByType('resource')
                        .map(entry => entry.name).filter(name => new URL(name).pathname.endsWith(
                            '/operations/authorizationBroker.ts')))]""")
                    if len(broker_urls) != 1:
                        raise AssertionError(f"Expected one app broker module, observed {broker_urls}")
                    # SES rejects dynamic import in evaluated code. Normal module loading uses
                    # the existing app singleton without weakening lockdown.
                    await page.add_script_tag(type="module", content=(
                        "import {requestAuthorization} from " + json.dumps(broker_urls[0]) + ";"
                        "void requestAuthorization(" + json.dumps(prompt) + ");"))
                    dialog = host.locator("dialog[open]")
                    await dialog.wait_for(state="visible", timeout=15000)
                    text = host.locator('[data-testid="authorization-args"] code')
                    facts.update(await dialog.evaluate("""dialog => ({
                        clientWidth: dialog.clientWidth, scrollWidth: dialog.scrollWidth,
                        viewport: innerWidth, left: dialog.getBoundingClientRect().left,
                        right: dialog.getBoundingClientRect().right,
                        top: dialog.getBoundingClientRect().top, bottom: dialog.getBoundingClientRect().bottom,
                        height: dialog.getBoundingClientRect().height
                    })"""))
                    facts["fullSummaryPreserved"] = await text.text_content() == summary
                    approve = host.locator('[data-testid="authorization-approve"]')
                    await approve.scroll_into_view_if_needed()
                    typed = host.get_by_role("textbox", name="Type core.file-note to confirm:")
                    await typed.fill("core.file-note")
                    facts["approveEnabledAfterTyping"] = await approve.get_by_role("button").is_enabled()
                    await typed.fill("")
                    await approve.scroll_into_view_if_needed()
                    facts["approveReachable"] = await approve.evaluate("""el => {
                        const r = el.getBoundingClientRect();
                        return r.width > 0 && r.left >= 0 && r.right <= innerWidth
                            && r.top >= 0 && r.bottom <= innerHeight;
                    }""")
                    await dialog.evaluate("el => { el.scrollTop = 0; el.scrollLeft = 0; }")

                step = ui_check.Step(name=name, setup=setup, isolated=True)
                shot = await ui_check._run_isolated_step(step, url, output, demo=False,
                    cooldown_ms=250, timeout_ms=30000, playwright_module=playwright,
                    measure=True, fixtures=True)
                measurement = json.loads(Path(shot.measure_path).read_text(encoding="utf-8")) if shot.measure_path else {}
                axe = measurement.get("axe")
                ok = (shot.ok and isinstance(axe, dict) and "error" not in axe
                      and facts.get("fullSummaryPreserved") and facts.get("approveReachable")
                      and facts.get("approveEnabledAfterTyping")
                      and facts.get("scrollWidth", 1) <= facts.get("clientWidth", 0) + 1
                      and facts.get("left", -1) >= 0 and facts.get("right", 99999) <= width
                      and facts.get("top", -1) >= 0 and facts.get("bottom", 99999) <= 900
                      and (shot.measure_summary or {}).get("axe_serious") == 0
                      and (shot.measure_summary or {}).get("console_errors") == 0)
                rows.append({"name": name, "ok": bool(ok), "facts": facts, "shot": asdict(shot)})
    return {"ok": all(row["ok"] for row in rows), "cases": rows,
            "scope": "Real Lit broker/host, deterministic structural fixtures; no backend effect approval"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output = Path(args.output_dir).resolve()
    output.mkdir(parents=True, exist_ok=True)
    url = ui_shot._resolve_ui_url("http://localhost:5173")
    result = asyncio.run(witness(output, url))
    (output / "witness.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
