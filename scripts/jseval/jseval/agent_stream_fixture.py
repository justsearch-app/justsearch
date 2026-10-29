"""Deterministic agent-run SSE fixture (tempdoc 814 §D8.2).

The browser's agent path is `AgentSessionController.send()` -> `streamViaHost` ->
`host.ai.streamShape` -> **POST `/api/chat/dispatch`** (`plugin-api/capabilities/ai.ts`),
whose RESPONSE is the SSE stream. `ui_fixtures.install_fixtures` used to let that POST
fall through to the unmapped-JSON default `{}`: both stream parsers then saw no terminal
frame, `pumpHostAiStream` threw `STREAM_INCOMPLETE`, and the run ended as the
"Connection lost — the response was interrupted." row visible in the agent-mode captures.
This module supplies the missing body.

WHY A STATIC BODY IS ENOUGH: Playwright's `route.fulfill` can only serve a COMPLETE
body, but both parsers are buffer-based frame splitters (`ai.ts:parseSseStream` splits on
`\\n\\n`; `api/streams.ts` does the same), so one whole multi-frame body yields every
event in order — byte-stable, which is exactly what a capture wants. The cost is recorded
in §D8.3: a stream that never terminates (the PAUSED-awaiting-budget state) is NOT
reachable this way.

SCHEMA AUTHORITY (projection, not fork): every payload here is validated at IMPORT time
against `scripts/codegen/shapes.fixture.json`'s `core.agent-run` `eventSchema` — the
machine-readable projection of the Java conversation-shape catalog, which is off-wire so
the `wire` gate cannot cover this drift. A catalog change that renames/retypes a field
breaks THIS module loudly, naming the event and the field, instead of leaving capture
assertions passing against a stale shape.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class AgentStreamFixtureError(RuntimeError):
    """A fixture payload does not satisfy the generated `core.agent-run` event schema."""


def _find_shapes_fixture() -> Path:
    """Locate `scripts/codegen/shapes.fixture.json` by walking up to the repo root
    (mirrors `ui_fixtures._find_fixtures_dir` / `ui_measure._find_axe`)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "scripts" / "codegen" / "shapes.fixture.json"
        if cand.exists():
            return cand
    raise FileNotFoundError("scripts/codegen/shapes.fixture.json not found from " + str(here))


def load_event_schema(shape_id: str = "core.agent-run") -> dict[str, list[dict[str, Any]]]:
    """`{eventName: [fieldSpec, ...]}` for one conversation shape, read from the generated
    catalog projection. Raises (never degrades to an empty map) — a fixture that cannot find
    its schema must fail loudly, not validate nothing."""
    doc = json.loads(_find_shapes_fixture().read_text(encoding="utf-8"))
    for shape in doc:
        if shape.get("id") == shape_id:
            return {
                ev["name"]: list(ev.get("fields") or [])
                for ev in shape.get("eventSchema") or []
                if ev.get("name")
            }
    raise AgentStreamFixtureError(
        f"shapes.fixture.json declares no shape {shape_id!r} — the conversation-shape catalog "
        f"was renamed or the projection is stale; re-run the codegen and re-point this fixture."
    )


_EVENT_SCHEMA = load_event_schema()

# The generated projection carries only SCALAR type names plus a nested `objectType` NAME
# (the nested object's own field list is not part of the projection), so validation is
# deliberately scoped to what the projection actually declares: presence of every
# non-optional field, no unknown field, and the declared top-level type. An ARRAY's
# elements are checked to the declared `elementType` KIND only (OBJECT -> dict).
_TYPE_CHECKS: dict[str, tuple[type, ...]] = {
    "STRING": (str,),
    "NUMBER": (int, float),
    "BOOLEAN": (bool,),
    "OBJECT": (dict,),
    "ARRAY": (list,),
    "ENUM": (str,),
}


def _check_type(event: str, field_name: str, spec: dict[str, Any], value: Any) -> None:
    declared = spec.get("type")
    expected = _TYPE_CHECKS.get(declared)
    if expected is None:
        return  # an unmodelled kind — nothing the projection lets us assert
    # `bool` is a subclass of `int`: a boolean must not satisfy NUMBER.
    if declared == "NUMBER" and isinstance(value, bool):
        raise AgentStreamFixtureError(
            f"core.agent-run event {event!r}: field {field_name!r} is declared NUMBER but the "
            f"fixture supplies a boolean"
        )
    if not isinstance(value, expected):
        raise AgentStreamFixtureError(
            f"core.agent-run event {event!r}: field {field_name!r} is declared {declared} but the "
            f"fixture supplies {type(value).__name__}"
        )
    if declared == "ENUM":
        allowed = spec.get("enumValues") or []
        if allowed and value not in allowed:
            raise AgentStreamFixtureError(
                f"core.agent-run event {event!r}: field {field_name!r} is declared ENUM "
                f"{allowed} but the fixture supplies {value!r}"
            )
    if declared == "ARRAY" and spec.get("elementType") == "OBJECT":
        for i, el in enumerate(value):
            if not isinstance(el, dict):
                raise AgentStreamFixtureError(
                    f"core.agent-run event {event!r}: field {field_name!r}[{i}] is declared an "
                    f"OBJECT element but the fixture supplies {type(el).__name__}"
                )


def validate_payload(event: str, payload: dict[str, Any]) -> None:
    """Raise {@link AgentStreamFixtureError} naming the offending event + field unless
    ``payload`` satisfies the generated schema for ``event``."""
    fields = _EVENT_SCHEMA.get(event)
    if fields is None:
        raise AgentStreamFixtureError(
            f"core.agent-run declares no event named {event!r} in shapes.fixture.json "
            f"(known: {sorted(_EVENT_SCHEMA)})"
        )
    by_name = {f["name"]: f for f in fields if f.get("name")}
    for name, spec in by_name.items():
        if name in payload:
            _check_type(event, name, spec, payload[name])
        elif not spec.get("optional"):
            raise AgentStreamFixtureError(
                f"core.agent-run event {event!r}: required field {name!r} "
                f"({spec.get('type')}) is missing from the fixture payload"
            )
    for name in payload:
        if name not in by_name:
            raise AgentStreamFixtureError(
                f"core.agent-run event {event!r}: field {name!r} is not declared in "
                f"shapes.fixture.json (known: {sorted(by_name)}) — a typo, or the catalog moved"
            )


def sse_frame(event: str, payload: dict[str, Any]) -> str:
    """One validated SSE frame in the grammar both parsers split on:
    ``event: <name>\\ndata: <json>\\n\\n`` (the same shape `AgentSessionController.test.ts`'s
    `sseChunk` helper writes)."""
    validate_payload(event, payload)
    return f"event: {event}\ndata: {json.dumps(payload, separators=(',', ':'))}\n\n"


# A wire-valid completed run: session identity, measured budget/context, answer, terminal.
# Sources are intentionally absent here. Record-based evidence cameras read canonical thread
# sources; the held budget fixture reuses sse_frame validation with an open transport instead.
DONE_RUN: tuple[tuple[str, dict[str, Any]], ...] = (
    ("session_started", {"sessionId": "fixture-agent-run-0001"}),
    (
        "budget_update",
        {
            "phase": "llm_response",
            "tokensConsumed": 1840,
            "tokensRemaining": 6352,
            "totalTokensConsumed": 1840,
            "promptTokens": 3072,
            "contextWindow": 4096,
        },
    ),
    (
        "chunk",
        {
            "text": "The worker enriches each document, then the head projects the result set "
                    "back into the conversation."
        },
    ),
    (
        "done",
        {
            "finalResponse": "The worker enriches each document, then the head projects the "
                             "result set back into the conversation.",
            "iterationsUsed": 2,
            "toolCallsExecuted": 1,
            "totalTokensUsed": 1840,
        },
    ),
)

# Built at IMPORT time so a schema drift fails at `import jseval.ui_fixtures`, not at the
# first capture (and so the ui-shot step and the unit tests read the same bytes).
DONE_RUN_BODY: str = "".join(sse_frame(event, payload) for event, payload in DONE_RUN)
