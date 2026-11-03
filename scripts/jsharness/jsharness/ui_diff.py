"""ui-diff — the DIFF capability (tempdoc 615 §11 DIFF): a SEMANTIC perceptual
changelog between two `ui-measure.v1` captures, not a pixel diff.

Answers "what MEANINGFULLY changed" — a landmark/role removed, an element moved or
resized past a threshold, a NEW axe rule appeared (or one was fixed), overflow flipped,
real console errors changed — over the structured facts the harness already records.
This is the industry-consensus DOM/a11y-structure diff (§4/§11), reusing the
schema-versioned artifact `capture_measure` writes; no new capture machinery.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

_GEOM_THRESHOLD = 4  # px — below this, a rect delta is noise (sub-4px-grid jitter)


def _landmark_key(lm: dict[str, Any]) -> tuple:
    return (lm.get("tag"), lm.get("role"), lm.get("label"), lm.get("text"))


def _axe_rule_ids(measure: dict[str, Any]) -> set[str]:
    return {v.get("id") for v in (measure.get("axe") or {}).get("violations") or []}


def _console_real(measure: dict[str, Any]) -> int:
    return sum(1 for e in measure.get("console_errors") or [] if e.get("category") == "app")


def diff_measures(
    before: dict[str, Any], after: dict[str, Any], *, geom_threshold: int = _GEOM_THRESHOLD,
) -> dict[str, Any]:
    """Semantic diff of two measure dicts. Returns a changelog with ``changed`` plus
    per-dimension deltas; ``changed`` is False when nothing meaningful moved (which
    two `--fixtures` captures of the same step prove — determinism = empty diff)."""
    # a11y landmarks — multiset diff by (tag, role, label, text)
    bc = Counter(_landmark_key(lm) for lm in before.get("a11y_landmarks") or [])
    ac = Counter(_landmark_key(lm) for lm in after.get("a11y_landmarks") or [])
    lm_added = [list(k) for k in (ac - bc).elements()]
    lm_removed = [list(k) for k in (bc - ac).elements()]

    # geometry — rect deltas over the threshold, plus appeared/disappeared elements
    b_els = (before.get("geometry") or {}).get("elements") or {}
    a_els = (after.get("geometry") or {}).get("elements") or {}
    moved = []
    for sel in sorted(set(b_els) & set(a_els)):
        br = b_els[sel].get("rect") or {}
        ar = a_els[sel].get("rect") or {}
        delta = {k: round(ar.get(k, 0) - br.get(k, 0)) for k in ("x", "y", "w", "h")}
        if any(abs(v) >= geom_threshold for v in delta.values()):
            moved.append({"selector": sel, "delta": delta})

    # axe rule-id set delta
    b_rules, a_rules = _axe_rule_ids(before), _axe_rule_ids(after)

    # overflow flips
    b_doc = (before.get("geometry") or {}).get("document") or {}
    a_doc = (after.get("geometry") or {}).get("document") or {}
    overflow = {k: [b_doc.get(k), a_doc.get(k)]
                for k in ("overflowX", "overflowY") if b_doc.get(k) != a_doc.get(k)}

    console_delta = _console_real(after) - _console_real(before)

    out = {
        "landmarks": {"added": lm_added, "removed": lm_removed},
        "geometry": {
            "moved": moved,
            "appeared": sorted(set(a_els) - set(b_els)),
            "disappeared": sorted(set(b_els) - set(a_els)),
        },
        "axe": {"new": sorted(a_rules - b_rules), "fixed": sorted(b_rules - a_rules)},
        "overflow": overflow,
        "console_real_delta": console_delta,
    }
    out["changed"] = bool(
        lm_added or lm_removed or moved or out["geometry"]["appeared"]
        or out["geometry"]["disappeared"] or out["axe"]["new"] or out["axe"]["fixed"]
        or overflow or console_delta
    )
    return out


def diff_files(before_path: str, after_path: str) -> dict[str, Any]:
    before = json.loads(Path(before_path).read_text(encoding="utf-8"))
    after = json.loads(Path(after_path).read_text(encoding="utf-8"))
    report = diff_measures(before, after)
    report["before"] = before_path
    report["after"] = after_path
    return report


def format_diff(report: dict[str, Any]) -> str:
    """One-screen human changelog. The summary IS the product; JSON is the drill-down."""
    if not report.get("changed"):
        return "no semantic change (identical facts -- determinism holds)"
    lines: list[str] = []
    ax = report["axe"]
    if ax["new"]:
        lines.append(f"! axe NEW: {', '.join(ax['new'])}")
    if ax["fixed"]:
        lines.append(f"+ axe fixed: {', '.join(ax['fixed'])}")
    g = report["geometry"]
    for m in g["moved"]:
        d = m["delta"]
        lines.append(f"~ moved {m['selector']}: dx={d['x']} dy={d['y']} dw={d['w']} dh={d['h']}")
    if g["appeared"]:
        lines.append(f"+ appeared: {', '.join(g['appeared'])}")
    if g["disappeared"]:
        lines.append(f"- disappeared: {', '.join(g['disappeared'])}")
    lm = report["landmarks"]
    if lm["removed"]:
        lines.append(f"- landmarks removed: {len(lm['removed'])}")
    if lm["added"]:
        lines.append(f"+ landmarks added: {len(lm['added'])}")
    if report["overflow"]:
        lines.append(f"! overflow changed: {report['overflow']}")
    if report["console_real_delta"]:
        lines.append(f"! real console errors d{report['console_real_delta']:+d}")
    # UI-sourced selectors/labels may carry non-ASCII; keep the human line safe on any
    # console encoding (Windows cp1252) without losing structure.
    return "\n".join(lines).encode("ascii", "replace").decode("ascii")
