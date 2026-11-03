"""Regenerate the shared chrome-proportion baseline register from the DETERMINISTIC
--fixtures captures (tempdoc 697). Throwaway generator, not harness runtime.

Unlike `regen_a11y_baseline.py` (which owns a hardcoded SURFACES list), this script
does NOT invent which {step, selector} pairs to track — the registered `steps[].elements`
entries in `governance/ui-proportion-baseline.v1.json` ARE the registry (the baseline
file is both the ceiling record AND the tracking list). This script only REFRESHES
`maxHeightPx` for pairs already registered there, pulling the ceiling DOWN to the
current measured height after a deliberate chrome-shrink fix — the ratchet direction.
A freshly-registered selector with no prior `maxHeightPx` gets one seeded here on first
run; an unregistered selector is never added automatically (register it manually first).

Run after a deliberate chrome-shrink fix; commit the JSON.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from jsharness import ui_shot  # noqa: E402


def _repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "governance").is_dir() and (parent / "modules").is_dir():
            return parent
    raise FileNotFoundError("repo root (with governance/ + modules/) not found")


_OUT = _repo_root() / "governance" / "ui-proportion-baseline.v1.json"


def _measured_height(measure_path: str, selector: str) -> int | None:
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    el = ((m.get("geometry") or {}).get("elements") or {}).get(selector)
    if not el:
        return None
    return (el.get("rect") or {}).get("h")


def main() -> int:
    if not _OUT.exists():
        print(f"! {_OUT} does not exist — nothing to regenerate")
        return 1

    register = json.loads(_OUT.read_text(encoding="utf-8"))
    steps = register.get("steps", [])
    if not steps:
        print("  no registered steps/elements — nothing to refresh (baseline is a placeholder)")
        return 0

    any_missing = False
    for step_entry in steps:
        step = step_entry.get("uiShotStep")
        elements = step_entry.get("elements") or []
        if not step or not elements:
            continue
        res = ui_shot.execute_ui_shot(step, fixtures=True)
        if not res.get("ok"):
            print(f"  ! {step}: capture failed: {res.get('error')}")
            return 1
        measure_path = res["measure"]["measure_path"]
        for el in elements:
            selector = el.get("selector")
            h = _measured_height(measure_path, selector)
            if h is None:
                print(f"  ! {step}: selector {selector!r} not found in captured geometry")
                any_missing = True
                continue
            prev = el.get("maxHeightPx")
            print(f"  {step:14s} {selector:24s} maxHeightPx: {prev} -> {h}")
            el["maxHeightPx"] = h

    if any_missing:
        print("\n! one or more registered selectors were not found in the capture — baseline NOT written")
        return 1

    _OUT.write_text(json.dumps(register, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nwrote {_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
