"""Regenerate the shared a11y baseline register from the DETERMINISTIC --fixtures
captures (tempdoc 615 §13 Move 2). Throwaway generator, not harness runtime.

The retired e2e `KNOWN_RULE_BASELINE` was calibrated against the now-dead demo
state (§14 U1). This recaptures each structural view in the reproducible route-mock
state and writes `governance/ui-a11y-baseline.v1.json` — the ONE authority the
Python ui-shot measurement and ui-a11y gate consume. Run once after a deliberate
baseline change; commit the JSON.
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


_OUT = _repo_root() / "governance" / "ui-a11y-baseline.v1.json"


def _axe_rule_ids(measure_path: str) -> list[str]:
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    viols = (m.get("axe") or {}).get("violations") or []
    return sorted({v["id"] for v in viols})


def main() -> int:
    current = json.loads(_OUT.read_text(encoding="utf-8"))
    declared_surfaces = current.get("surfaces")
    if not isinstance(declared_surfaces, list) or not declared_surfaces:
        print(f"  ! {_OUT}: expected a non-empty surfaces array")
        return 1
    surfaces_out = []
    for declared in declared_surfaces:
        surface = declared.get("surface")
        step = declared.get("uiShotStep")
        if not isinstance(surface, str) or not isinstance(step, str):
            print(f"  ! {_OUT}: every row needs string surface and uiShotStep")
            return 1
        res = ui_shot.execute_ui_shot(step, fixtures=True)
        if not res.get("ok"):
            print(f"  ! {step}: capture failed: {res.get('error')}")
            return 1
        rules = _axe_rule_ids(res["measure"]["measure_path"])
        print(f"  {surface:9s} (step {step:9s}): knownRules={rules}")
        refreshed = {
            "surface": surface,
            "uiShotStep": step,
            "knownRules": rules,
        }
        if isinstance(declared.get("note"), str):
            refreshed["note"] = declared["note"]
        surfaces_out.append(refreshed)

    register = {
        "$schema": "./ui-a11y-baseline.schema.json",
        "version": 1,
        "description": (
            "Shared a11y known-violation baseline (tempdoc 615 §13 Move 2). ONE authority "
            "for the per-surface axe rules accepted in the DETERMINISTIC route-mock capture "
            "state (jsharness ui-shot --fixtures). Consumed by the Python ui-shot measurement "
            "and ui-a11y governance gate, which flag NEW-vs-known violations. "
            "knownRules = accepted debt; a "
            "violation NOT listed is NEW and must be investigated. Regenerate via "
            "scripts/jsharness/experiments/regen_a11y_baseline.py after a deliberate change."
        ),
        "captureState": "route-mock-fixtures (no backend, deterministic)",
        "surfaces": surfaces_out,
    }
    _OUT.parent.mkdir(parents=True, exist_ok=True)
    _OUT.write_text(json.dumps(register, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nwrote {_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
