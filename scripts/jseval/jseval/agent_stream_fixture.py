"""Deterministic agent-run SSE fixture (tempdoc 814 §D8.2).

The browser's agent path is `AgentSessionController.send()` -> `streamViaHost` ->
`host.ai.streamShape` -> **POST `/api/chat/dispatch`** (`plugin-api/capabilities/ai.ts`),
whose RESPONSE is the SSE stream. `ui_fixtures.install_fixtures` used to let that POST
fall through to the unmapped-JSON default `{}`: both stream parsers then saw no terminal
frame, `pumpHostAiStream` threw `STREAM_INCOMPLETE`, and the run ended as the
"Connection lost — the response was interrupted." row visible in the agent-mode captures.
This module supplies the missing body.

Completed captures use the static DONE_RUN_BODY. AgentStreamHandle extends the
same frame/schema authority with a context-owned loopback HTTP server for flows
that need an open response and stepwise event release; route.fulfill closes a body.

SCHEMA AUTHORITY (projection, not fork): every payload here is validated at IMPORT time
against `scripts/codegen/shapes.fixture.json`'s `core.agent-run` `eventSchema` — the
machine-readable projection of the Java conversation-shape catalog, which is off-wire so
the `wire` gate cannot cover this drift. A catalog change that renames/retypes a field
breaks THIS module loudly, naming the event and the field, instead of leaving capture
assertions passing against a stale shape.
"""
from __future__ import annotations

import asyncio
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


class AgentStreamHandle:
    """One context's loopback SSE transport, driven by a step through real HTTP.

    Frames are validated before enqueueing. A pending stream sends comments so the
    real parser/idle watchdog remains active. Closing the owning context closes the
    listener, client sockets and handler tasks, including a disconnected client.
    """

    def __init__(self):
        self.connected = asyncio.Event()
        self._frames = asyncio.Queue()
        self._writers = set()
        self._tasks = set()
        self._closed = False

    async def start(self, ctx):
        self._server = await asyncio.start_server(self._serve, "127.0.0.1", 0)
        port = self._server.sockets[0].getsockname()[1]
        self.url = f"http://127.0.0.1:{port}/api/chat/dispatch"
        ctx.on("close", self.close)
        return self

    def queue(self, event: str, payload: dict[str, Any]) -> None:
        if self._closed:
            raise RuntimeError("agent fixture stream is closed")
        self._frames.put_nowait(sse_frame(event, payload).encode())

    async def emit(self, event: str, payload: dict[str, Any]) -> None:
        self.queue(event, payload)
        await asyncio.wait_for(self.connected.wait(), 10)

    async def end(self, **payload) -> None:
        self.queue("done", payload)
        self._frames.put_nowait(None)

    async def fail(self, message: str = "Fixture run failed") -> None:
        self.queue("error", {"error": message, "errorCode": "FIXTURE_FAILURE"})
        self._frames.put_nowait(None)

    async def disconnect(self) -> None:
        """Close without a terminal frame, exercising the real incomplete-stream path."""
        self._frames.put_nowait(None)

    async def _serve(self, reader, writer):
        task = asyncio.current_task()
        self._tasks.add(task)
        self._writers.add(writer)
        disconnected = None
        try:
            headers = (await reader.readuntil(b"\r\n\r\n")).decode()
            # Consume the POST body before watching for the client closing the socket.
            length = next((int(line.split(":", 1)[1]) for line in headers.split("\r\n")
                           if line.lower().startswith("content-length:")), 0)
            if length:
                await reader.readexactly(length)
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                         b"Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n"
                         b"Connection: close\r\n\r\n: connected\n\n")
            await writer.drain()
            self.connected.set()
            disconnected = asyncio.create_task(reader.read())
            while not self._closed:
                frame = asyncio.create_task(self._frames.get())
                try:
                    done, _ = await asyncio.wait({frame, disconnected}, timeout=1,
                                                 return_when=asyncio.FIRST_COMPLETED)
                    if disconnected in done:
                        break
                    body = frame.result() if frame in done else b": heartbeat\n\n"
                    if body is None:
                        break
                    writer.write(body)
                    await writer.drain()
                finally:
                    if not frame.done():
                        frame.cancel()
                    await asyncio.gather(frame, return_exceptions=True)
        except (ConnectionError, asyncio.IncompleteReadError):
            pass  # A client abort is an expected fixture outcome.
        finally:
            if disconnected:
                disconnected.cancel()
                await asyncio.gather(disconnected, return_exceptions=True)
            writer.close()
            await writer.wait_closed()
            self._writers.discard(writer)
            self._tasks.discard(task)

    async def close(self):
        self._closed = True
        self._server.close()
        await self._server.wait_closed()
        for task in list(self._tasks):
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
