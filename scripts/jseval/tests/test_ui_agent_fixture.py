"""Exact fixture routing and context ownership, independent of the browser checks."""
import asyncio
import json
from types import SimpleNamespace

import pytest

from jseval.ui_fixtures import AgentUIFixture, FixtureReply


class Route:
    async def fulfill(self, **kwargs):
        self.result = kwargs

    async def abort(self, reason):
        self.result = reason


def record(fixture, method, path, body=None):
    return fixture.record(SimpleNamespace(method=method, url="http://fixture" + path,
        post_data=json.dumps(body) if body is not None else None))


@pytest.mark.parametrize("outcome", ["success", "refusal", "abort", "delayed", "pending"])
def test_halt_outcomes_are_observed_and_context_owned(outcome):
    async def check():
        fixture = AgentUIFixture(None)
        path = f"/api/chat/sessions/{fixture.session_id}"
        entered = asyncio.Event()

        class Gate(asyncio.Event):
            async def wait(self):
                entered.set()
                return await super().wait()

        gate = Gate()
        reply = FixtureReply(status=503 if outcome == "refusal" else 200,
                             abort=outcome == "abort", gate=gate if outcome in ("delayed", "pending") else None)
        fixture.replies[("DELETE", path)] = reply
        route = Route()
        request = await record(fixture, "DELETE", path)
        task = asyncio.create_task(fixture.route(route, request))
        if reply.gate:
            await asyncio.wait_for(entered.wait(), 1)
            assert not request.answered.is_set()
            if outcome == "pending":
                await fixture.close()
                assert task.cancelled() and not request.answered.is_set()
                return
            gate.set()
        assert await task
        assert request.answered.is_set()
        if outcome == "abort":
            assert route.result == "connectionfailed"
        else:
            assert route.result["status"] == reply.status
        assert len(fixture.matching("DELETE", path)) == 1
        assert not await fixture.route(Route(), await record(fixture, "POST", path))
        assert not await fixture.route(Route(), await record(fixture, "DELETE", path + "/other"))
        await fixture.close()
    asyncio.run(check())


def test_steering_identity_live_detail_and_unmapped_requests():
    async def check():
        fixture = AgentUIFixture(None)
        body = {"sessionId": fixture.session_id, "text": "direction", "clientRequestId": "client-1"}
        request = await record(fixture, "POST", "/api/chat/agent/steer", body)
        assert request is await fixture.wait_request("POST", request.path)
        assert request.body == body
        assert await fixture.route(Route(), request)
        detail = await record(fixture, "GET", f"/api/chat/approval?sessionId={fixture.session_id}&callId=call-1")
        route = Route()
        assert await fixture.route(route, detail)
        assert json.loads(route.result["body"]) == {
            "callId": "call-1", "operationId": "core.search", "argsSummary": "Search fixture documents",
            "riskTier": "LOW", "gateBehavior": "INLINE_CONFIRM"}
        assert not await fixture.route(Route(), await record(fixture, "GET", "/api/unmapped"))
        assert not await fixture.route(Route(), await record(fixture, "GET", request.path))
        assert len(fixture.requests) == 4
    asyncio.run(check())
