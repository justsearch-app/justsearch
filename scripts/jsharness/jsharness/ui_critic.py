"""ui-critic — the REASON capability (tempdoc 615 §11 REASON / the deferred Judge §6.4).

The one faculty that does NOT reduce to a gate: visual hierarchy / structure / brand
feel. It needs the running render + an LLM + a product reference. This builds the
GROUNDED critique: it captures the deterministic measured facts, assembles a prompt
pairing them with the machine-readable design reference (governance/design-reference.v1.json)
+ a rubric, and asks the LLM whether the rendered UI conforms — returning structured
issues. The model call is INJECTED, so the logic is testable without a stack and the
CLI can wire either the live `/api/chat/agent` model or `--facts-only` (the agent feeds
the prompt to its own model). 2026 grounding (§11): critique from JSON facts grounded in
geometry + an a11y tree; "unit tests for correctness + LLM rubrics for quality".
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Callable


def load_reference() -> dict[str, Any]:
    """The machine-readable design reference (foundation token scales + per-surface
    structural expectations + the critique rubric)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "design-reference.v1.json"
        if cand.exists():
            return json.loads(cand.read_text(encoding="utf-8"))
    raise FileNotFoundError("governance/design-reference.v1.json not found")


def compact_facts(measure: dict[str, Any]) -> dict[str, Any]:
    """The subset of a `ui-measure.v1` capture the critic reasons over — small + dense
    (the token-economics lesson from §12: drill into facts, don't dump the whole blob)."""
    geom = measure.get("geometry") or {}
    doc = geom.get("document") or {}
    return {
        "landmarks": [
            {"role": lm.get("role"), "tag": lm.get("tag"), "label": lm.get("label"), "text": lm.get("text")}
            for lm in measure.get("a11y_landmarks") or []
        ],
        "axe": [
            {"id": v.get("id"), "impact": v.get("impact")}
            for v in (measure.get("axe") or {}).get("violations") or []
        ],
        "geometry": {sel: el.get("rect") for sel, el in (geom.get("elements") or {}).items()},
        "overflow": {"x": bool(doc.get("overflowX")), "y": bool(doc.get("overflowY"))},
    }


def assemble_prompt(measure: dict[str, Any], reference: dict[str, Any], surface: str) -> str:
    """The grounded critique prompt: design reference + measured facts + a strict
    output contract. Asking for JSON keeps the critique machine-parseable (§11: JSON
    won as the design-system encoding)."""
    surface_spec = (reference.get("surfaces") or {}).get(surface, {})
    facts = compact_facts(measure)
    return (
        "You are a UI design critic for the JustSearch desktop app. Judge ONLY from the "
        "facts below — do not invent elements you cannot see in them.\n\n"
        "## Design reference (the bar)\n"
        f"Foundation (always-on scales): {json.dumps(reference.get('foundation'))}\n"
        f"Shell invariants: {json.dumps(reference.get('shell'))}\n"
        f"This surface ('{surface}') expects: {json.dumps(surface_spec)}\n"
        f"Rubric: {json.dumps(reference.get('rubric'))}\n\n"
        "## Measured facts (the rendered UI)\n"
        f"{json.dumps(facts)}\n\n"
        "## Task\n"
        "For each rubric dimension, decide whether the measured facts CONFORM. "
        "Return ONLY a JSON object:\n"
        '{"conforms": <bool>, "issues": [{"rubricId": "<id>", "severity": '
        '"low|medium|high", "evidence": "<cite a specific measured fact>"}]}\n'
        "An empty issues list means full conformance."
    )


def parse_critique(text: str) -> dict[str, Any]:
    """Tolerantly extract the critique JSON from the model's reply. Falls back to a
    raw-text wrapper so a non-JSON reply never crashes the caller."""
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        try:
            obj = json.loads(m.group(0))
            if isinstance(obj, dict):
                obj.setdefault("issues", [])
                obj.setdefault("conforms", not obj["issues"])
                return obj
        except json.JSONDecodeError:
            pass
    return {"conforms": None, "issues": [], "raw": text.strip()[:2000], "parseError": True}


def critique(
    step: str,
    surface: str,
    capture_fn: Callable[[str], dict[str, Any]],
    model_fn: Callable[[str], str],
) -> dict[str, Any]:
    """Capture the step (fixtures), assemble the grounded prompt, ask the model, parse.
    ``capture_fn`` returns a ui-shot result (with measure_path); ``model_fn`` maps the
    prompt to the model's reply text. Both injected for testability."""
    res = capture_fn(step)
    if not res.get("ok"):
        return {"error": f"capture failed: {res.get('error')}"}
    measure_path = (res.get("measure") or {}).get("measure_path")
    if not measure_path:
        return {"error": "no measurement companion"}
    measure = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    prompt = assemble_prompt(measure, load_reference(), surface)
    reply = model_fn(prompt)
    out = parse_critique(reply)
    out["surface"] = surface
    out["step"] = step
    return out
