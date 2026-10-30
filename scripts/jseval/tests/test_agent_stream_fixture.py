"""Tests for the deterministic agent-run SSE fixture (tempdoc 814 §D8.2).

The point of this module is that a hand-authored wire payload cannot silently drift from
the generated `core.agent-run` catalog projection. These tests pin that: the validator
must NAME the offending event and field, and the shipped `DONE_RUN` must satisfy the real
`scripts/codegen/shapes.fixture.json` (not a stub), so a catalog change breaks here rather
than leaving a capture assertion passing against a stale shape.
"""
from __future__ import annotations

import json
import asyncio

import pytest

from jseval import agent_stream_fixture as asf


class TestSchemaIsTheRealProjection:
    def test_event_schema_comes_from_the_generated_catalog(self):
        schema = asf.load_event_schema()
        # A representative slice of the shape the fixture depends on.
        assert "session_started" in schema
        assert "budget_update" in schema
        assert "done" in schema

    def test_an_unknown_shape_id_raises(self):
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.load_event_schema("core.not-a-shape")
        assert "core.not-a-shape" in str(e.value)


class TestValidatePayload:
    def test_a_valid_payload_passes(self):
        asf.validate_payload("session_started", {"sessionId": "s-1"})

    def test_a_missing_required_field_names_the_event_and_the_field(self):
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.validate_payload("session_started", {})
        assert "session_started" in str(e.value)
        assert "sessionId" in str(e.value)

    def test_a_wrong_typed_field_names_the_event_and_the_field(self):
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.validate_payload("budget_update", {
                "phase": "llm_response", "tokensConsumed": "lots", "tokensRemaining": 1,
            })
        assert "budget_update" in str(e.value)
        assert "tokensConsumed" in str(e.value)

    def test_a_boolean_does_not_satisfy_number(self):
        with pytest.raises(asf.AgentStreamFixtureError):
            asf.validate_payload("budget_update", {
                "phase": "p", "tokensConsumed": True, "tokensRemaining": 1,
            })

    def test_an_optional_field_may_be_omitted(self):
        asf.validate_payload("budget_update", {
            "phase": "p", "tokensConsumed": 1, "tokensRemaining": 2,
        })

    def test_an_undeclared_field_is_rejected(self):
        # The typo case: a field the catalog never declared would be silently dropped by
        # the FE, so the fixture would assert against a payload the app never sees.
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.validate_payload("session_started", {"sessionId": "s", "sessionid": "s"})
        assert "sessionid" in str(e.value)

    def test_an_unknown_event_is_rejected(self):
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.validate_payload("budget_updated", {"phase": "p"})
        assert "budget_updated" in str(e.value)

    def test_an_array_of_non_objects_is_rejected(self):
        with pytest.raises(asf.AgentStreamFixtureError) as e:
            asf.validate_payload("done", {
                "finalResponse": "x", "iterationsUsed": 1, "toolCallsExecuted": 0,
                "totalTokensUsed": 1, "sources": ["not-an-object"],
            })
        assert "sources" in str(e.value)


class TestFrameGrammar:
    def test_frame_matches_the_parsers_grammar(self):
        frame = asf.sse_frame("session_started", {"sessionId": "s-1"})
        assert frame.startswith("event: session_started\ndata: ")
        assert frame.endswith("\n\n")
        payload = json.loads(frame.split("data: ", 1)[1].strip())
        assert payload == {"sessionId": "s-1"}

    def test_sse_frame_validates_before_serializing(self):
        with pytest.raises(asf.AgentStreamFixtureError):
            asf.sse_frame("session_started", {})


class TestDoneRunBody:
    def test_every_frame_validates_against_the_real_schema(self):
        for event, payload in asf.DONE_RUN:
            asf.validate_payload(event, payload)

    def test_the_body_terminates_with_done(self):
        # Without a terminal `done`, `pumpHostAiStream` throws STREAM_INCOMPLETE and the
        # capture paints the "Connection lost" row this fixture exists to remove.
        assert asf.DONE_RUN[-1][0] == "done"
        assert "event: done" in asf.DONE_RUN_BODY

    def test_budget_update_carries_the_context_meter_inputs(self):
        # `projectContextHorizon` returns null unless BOTH are > 0, so the rail's context
        # meter would silently not render — the capture would then assert nothing.
        budget = dict(asf.DONE_RUN)["budget_update"]
        assert budget["promptTokens"] > 0
        assert budget["contextWindow"] > 0

    def test_done_carries_no_sources_so_the_record_stays_the_one_provider(self):
        # §D8.1's split: grounding comes from the thread record, so stripping the record's
        # sources actually turns the `.evidence-rail` row red (the D4 negative control).
        done = dict(asf.DONE_RUN)["done"]
        assert "sources" not in done
        assert "citations" not in done

    def test_the_body_splits_into_exactly_the_declared_frames(self):
        frames = [f for f in asf.DONE_RUN_BODY.split("\n\n") if f.strip()]
        assert len(frames) == len(asf.DONE_RUN)


class _Context:
    def __init__(self):
        self.callbacks = []

    def on(self, event, callback):
        assert event == "close"
        self.callbacks.append(callback)


class TestOpenStream:
    @pytest.mark.parametrize("terminal", ["done", "error", "disconnect", "context-close"])
    def test_real_http_incremental_frames_heartbeat_and_each_terminal(self, terminal):
        async def check():
            ctx = _Context()
            stream = await asf.AgentStreamHandle().start(ctx)
            port = stream._server.sockets[0].getsockname()[1]
            reader, writer = await asyncio.open_connection("127.0.0.1", port)
            try:
                writer.write(b"POST /api/chat/dispatch HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}")
                await writer.drain()
                headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 3)
                assert b"200 OK" in headers and b"text/event-stream" in headers
                assert await reader.readuntil(b"\n\n") == b": connected\n\n"
                await stream.emit("session_started", {"sessionId": "incremental"})
                assert await reader.readuntil(b"\n\n") == asf.sse_frame(
                    "session_started", {"sessionId": "incremental"}).encode()
                # A real pending read: the response has not been fulfilled/closed.
                assert await asyncio.wait_for(reader.readuntil(b"\n\n"), 3) == b": heartbeat\n\n"
                with pytest.raises(asf.AgentStreamFixtureError):
                    await stream.emit("session_started", {})
                await stream.emit("chunk", {"text": "released later"})
                assert await reader.readuntil(b"\n\n") == asf.sse_frame("chunk", {"text": "released later"}).encode()
                if terminal == "done":
                    payload = {"finalResponse": "Finished", "iterationsUsed": 1,
                               "toolCallsExecuted": 0, "totalTokensUsed": 10}
                    await stream.end(**payload)
                    assert await reader.readuntil(b"\n\n") == asf.sse_frame("done", payload).encode()
                elif terminal == "error":
                    await stream.fail("deliberate failure")
                    assert b'"deliberate failure"' in await reader.readuntil(b"\n\n")
                elif terminal == "disconnect":
                    await stream.disconnect()
                else:
                    await ctx.callbacks[0]()
                remainder = await asyncio.wait_for(reader.read(), 3)
                # A heartbeat already in flight at close is allowed, event data is not.
                assert all(not line or line.startswith(b":") for line in remainder.splitlines())
            finally:
                writer.close()
                await writer.wait_closed()
                await stream.close()
            assert not stream._tasks and not stream._writers
            with pytest.raises(ConnectionRefusedError):
                await asyncio.open_connection("127.0.0.1", port)
        asyncio.run(check())
