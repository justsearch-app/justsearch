"""Backend-free behavioural checks through Search's real controls and open SSE stream.

All checkpoints are bounded DOM assertions or observed HTTP requests. Measurement
companions are diagnostic only; these setup functions throw to fail ui-shot/CI.
"""
from __future__ import annotations

import asyncio
import re

from . import ui_fixtures as fixtures
from . import ui_selectors as S


DISPATCH = "/api/chat/dispatch"
STEER = "/api/chat/agent/steer"
APPROVE = "/api/chat/approve"
BUDGET = "/api/chat/agent/budget-decision"
TIMEOUT = 10_000


def expect(locator):
    # Keep step discovery available to the Python-only CI lane without the UI extra.
    from playwright.async_api import expect as playwright_expect
    return playwright_expect(locator)


def run(page):
    return page.get_by_test_id("sv3-run")


async def start(page):
    page.set_default_timeout(TIMEOUT)
    await page.emulate_media(reduced_motion="reduce")
    fixture = fixtures.agent_fixture(page.context)
    await page.locator(S.CSS_SV3_WINDOW).wait_for(state="visible")
    await page.get_by_test_id("sv3-composer-tier").click()
    await page.locator('[data-testid="sv3-composer-tier-option"][data-tier="delegate"]').click()
    await page.locator('jf-sv3-composer[tier="delegate"]').wait_for(state="visible")
    field = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA)
    await field.fill("Investigate fixture documents")
    await expect(page.get_by_test_id("sv3-composer-send")).to_have_attribute("data-unavailable", "false", timeout=TIMEOUT)
    await field.press("Enter")
    request = await fixture.wait_request("POST", DISPATCH)
    assert request.body["shapeId"] == "core.agent-run", request
    await fixture.stream.emit("session_started", {"sessionId": fixture.session_id})
    await expect(run(page)).to_have_attribute("data-phase", "running", timeout=TIMEOUT)
    return fixture


async def send(page, text, key="Enter"):
    field = page.locator(S.CSS_SV3_COMPOSER_TEXTAREA)
    await field.fill(text)
    await field.press(key)


async def note(page, text):
    await expect(page.get_by_test_id("sv3-run-note").filter(has_text=text)).to_be_visible(timeout=TIMEOUT)


async def activity(fixture, page, text):
    await fixture.stream.emit("reasoning_chunk", {"text": text})
    block = page.get_by_test_id("sv3-run-reasoning").last
    await expect(block).to_be_visible(timeout=TIMEOUT)
    disclosure = block.get_by_role("button", name="Model reasoning trace")
    if await disclosure.get_attribute("aria-expanded") == "false":
        await disclosure.click()
    await expect(block.get_by_text(text, exact=False)).to_be_visible(timeout=TIMEOUT)


async def held_budget(fixture, page):
    await fixture.stream.emit("budget_update", {"phase": "llm_response", "tokensConsumed": 8192,
        "tokensRemaining": 0, "totalTokensConsumed": 8192, "promptTokens": 3072, "contextWindow": 4096})
    await fixture.stream.emit("budget_gate", {"tokensNeeded": 2048, "tokensRemaining": 0,
        "totalTokensConsumed": 8192})
    await expect(run(page)).to_have_attribute("data-phase", "holding", timeout=TIMEOUT)
    await expect(page.get_by_test_id("sv3-run-prompt")).to_have_attribute("data-kind", "budget")


def only_run(fixture):
    dispatches = fixture.matching("POST", DISPATCH)
    assert len(dispatches) == 1 and dispatches[0].body["shapeId"] == "core.agent-run", dispatches
    # Ask's other transport must not bypass the dispatch assertion.
    assert not [r for r in fixture.requests if r.method == "POST" and r.path in (
        "/api/chat", "/api/chat/stream", "/api/ask", "/api/ask/stream")], fixture.requests


async def report(fixture, text, outcome, directive_id, request_id=None):
    payload = {"directiveText": text, "outcome": outcome, "directiveId": directive_id}
    if request_id:
        payload["clientRequestId"] = request_id
    if outcome == "not_applied":
        payload["reason"] = "step_limit"
    await fixture.stream.emit("directive_acknowledged", payload)


async def setup_run_activity_steering(page):
    """F1: released activity, real steering, three outcomes, request-owned uncertainty."""
    fixture = await start(page)
    await activity(fixture, page, "Inspecting fixture documents.")
    call = {"callId": "activity-call", "toolName": "core.search", "arguments": '{"query":"fixture"}', "risk": "low"}
    await fixture.stream.emit("tool_call_pending", {**call, "gateBehavior": "auto"})
    tool = page.get_by_test_id("sv3-run-tool")
    await expect(tool).to_have_count(1)
    await expect(tool.get_by_test_id("tool-card-header")).to_have_attribute("aria-label", re.compile("pending", re.I))
    await fixture.stream.emit("tool_exec_started", {"callId": call["callId"], "toolName": call["toolName"]})
    await expect(tool.get_by_test_id("tool-card-header")).to_have_attribute("aria-label", re.compile("executing", re.I))
    await fixture.stream.emit("tool_exec_completed", {"callId": call["callId"], "success": True,
        "output": "Found fixture document", "executionId": "activity-execution"})
    await expect(tool.get_by_test_id("tool-card-header")).to_have_attribute("aria-label", re.compile("completed", re.I))

    direction = "Prioritize fixture notes"
    await send(page, direction)
    request = await fixture.wait_request("POST", STEER)
    assert request.body["sessionId"] == fixture.session_id and request.body["text"] == direction, request
    assert isinstance(request.body.get("clientRequestId"), str) and request.body["clientRequestId"], request
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    await report(fixture, direction, "queued", "direction-1", request.body["clientRequestId"])
    await note(page, "Direction queued")
    await report(fixture, direction, "applied", "direction-1", request.body["clientRequestId"])
    await note(page, "Steered")
    await expect(page.get_by_test_id("sv3-run-note").filter(has_text="Direction queued")).to_have_count(0)
    await report(fixture, "Keep queued", "queued", "direction-queued")
    await report(fixture, "Cannot apply", "not_applied", "direction-refused")
    await note(page, "Direction queued")
    await note(page, "Not applied")
    await note(page, "Steered")

    fixture.replies[("POST", STEER)] = fixtures.FixtureReply(status=503)
    await send(page, "Use only fixture notes", key="Control+Enter")
    uncertain = await fixture.wait_request("POST", STEER, count=2)
    assert uncertain.body["sessionId"] == fixture.session_id, uncertain
    assert uncertain.body["clientRequestId"] != request.body["clientRequestId"], uncertain
    notice = page.get_by_test_id("sv3-run-note").filter(has_text="Could not confirm your direction")
    await expect(notice).to_be_visible(timeout=TIMEOUT)
    # Same words from a different client cannot clear this send's notice.
    await report(fixture, uncertain.body["text"], "applied", "other-client", "other-client-request")
    await note(page, "Use only fixture notes")
    await expect(notice).to_be_visible()
    await activity(fixture, page, "Activity after the failed steering response.")
    await expect(notice).to_be_visible()
    await report(fixture, uncertain.body["text"], "queued", "own-request", uncertain.body["clientRequestId"])
    await expect(notice).to_have_count(0, timeout=TIMEOUT)
    assert len(fixture.matching("POST", STEER)) == 2, fixture.requests
    only_run(fixture)

    # The facts panel makes omission observable: no progress frame has reported steps.
    await held_budget(fixture, page)
    facts = page.get_by_test_id("sv3-run-budget-facts")
    await expect(facts.locator("div").filter(has=page.locator("dt", has_text="Tool calls")).locator("dd")).to_have_text("1")
    await expect(facts.locator("dt", has_text="Steps")).to_have_count(0)
    await fixture.stream.emit("progress", {"phase": "search", "message": "Fixture step three",
        "iteration": 3, "maxIterations": 10})
    await expect(facts.locator("div").filter(has=page.locator("dt", has_text="Steps")).locator("dd")).to_have_text("3")


async def setup_run_budget(page):
    """F2: ordinary Send is refused; only the budget decision dispatches."""
    fixture = await start(page)
    await held_budget(fixture, page)
    await send(page, "Ordinary send must not dispatch")
    await send(page, "Nor may the delegate chord dispatch", key="Control+Enter")
    await expect(page.get_by_test_id("sv3-run-budget-finalize")).to_be_visible()
    await page.get_by_test_id("sv3-run-budget-finalize").click()
    request = await fixture.wait_request("POST", BUDGET)
    assert request.body == {"sessionId": fixture.session_id, "decision": "finalize"}, request
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    await expect(page.get_by_test_id("sv3-run-prompt")).to_have_count(0, timeout=TIMEOUT)
    await expect(run(page)).to_have_attribute("data-phase", "running", timeout=TIMEOUT)
    await activity(fixture, page, "Resuming after the budget decision.")
    await expect(run(page)).to_have_attribute("data-phase", "running")
    await fixture.stream.end(finalResponse="Fixture finalized", iterationsUsed=0,
                             toolCallsExecuted=0, totalTokensUsed=8192)
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_attribute("data-outcome", "complete", timeout=TIMEOUT)
    only_run(fixture)
    assert not fixture.matching("POST", STEER), fixture.requests
    assert len(fixture.matching("POST", BUDGET)) == 1, fixture.requests


async def approval(page, *, failure):
    fixture = await start(page)
    if failure:
        fixture.replies[("POST", APPROVE)] = fixtures.FixtureReply(status=503)
    pending = {"callId": "approval-call", "toolName": "core.search", "arguments": '{"query":"fixture"}',
        "risk": "low", "gateBehavior": "inline_confirm"}
    await fixture.stream.emit("tool_call_pending", pending)
    ceremony = page.get_by_test_id("authorization-ceremony")
    await expect(ceremony).to_be_visible(timeout=TIMEOUT)
    await fixture.stream.emit("tool_call_pending", pending)
    # A later visible frame proves the replay was consumed before counting ceremonies/lookups.
    await fixture.stream.emit("progress", {"phase": "approval", "message": "Approval replay consumed",
        "iteration": 1, "maxIterations": 10})
    await note(page, "Approval replay consumed")
    await expect(ceremony).to_have_count(1)
    lookups = fixture.matching("GET", "/api/chat/approval")
    assert len(lookups) == 1 and f"sessionId={fixture.session_id}" in lookups[0].query, lookups
    await page.get_by_test_id("authorization-approve").click()
    request = await fixture.wait_request("POST", APPROVE)
    assert request.body == {"sessionId": fixture.session_id, "callId": pending["callId"]}, request
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    if failure:
        await note(page, "the action was not approved")
    else:
        await fixture.stream.emit("tool_call_approved", {"callId": pending["callId"]})
        await fixture.stream.emit("tool_exec_started", {"callId": pending["callId"], "toolName": pending["toolName"]})
        await expect(page.get_by_test_id("sv3-run-tool").get_by_test_id("tool-card-header")).to_have_attribute(
            "aria-label", re.compile("executing", re.I))
        await activity(fixture, page, "Continuing after approval.")
        await expect(run(page)).to_have_attribute("data-phase", "running")
    await expect(ceremony).to_have_count(0)
    assert len(fixture.matching("GET", "/api/chat/approval")) == 1, fixture.requests
    assert len(fixture.matching("POST", APPROVE)) == 1, fixture.requests
    only_run(fixture)


async def setup_run_approval(page):
    """F3: a replay opens one ceremony; approving permits the next released event."""
    await approval(page, failure=False)


async def setup_run_approval_failure(page):
    """F3 variant: failed answer explicitly says the action was not approved."""
    await approval(page, failure=True)


async def setup_run_halt(page):
    """F4: accepted DELETE keeps stopping until the CANCELLED terminal."""
    fixture = await start(page)
    await activity(fixture, page, "Running until Halt.")
    path = f"/api/chat/sessions/{fixture.session_id}"
    release = asyncio.Event()
    fixture.replies[("DELETE", path)] = fixtures.FixtureReply(gate=release)
    await page.get_by_test_id("sv3-composer-stop").click()
    request = await fixture.wait_request("DELETE", path)
    assert not request.answered.is_set()
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    release.set()
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    await activity(fixture, page, "Output still arrives while stopping.")
    await fixture.stream.emit("error", {"error": "Session cancelled", "errorCode": "CANCELLED"})
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_attribute("data-outcome", "halted", timeout=TIMEOUT)
    await expect(page.get_by_test_id("sv3-run-feed")).to_have_count(0)
    assert len(fixture.matching("DELETE", path)) == 1, fixture.requests
    only_run(fixture)


async def setup_run_halt_failure(page):
    """S3: DELETE refusal keeps observing and permits one explicit retry."""
    fixture = await start(page)
    await activity(fixture, page, "Running until Halt is refused.")
    path = f"/api/chat/sessions/{fixture.session_id}"
    fixture.replies[("DELETE", path)] = fixtures.FixtureReply(status=409)
    await page.get_by_test_id("sv3-composer-stop").click()
    request = await fixture.wait_request("DELETE", path)
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text(
        "Could not stop this answer — it goes on. Press Stop to try again.")
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    await activity(fixture, page, "The answer continues after the refused Stop.")
    release = asyncio.Event()
    fixture.replies[("DELETE", path)] = fixtures.FixtureReply(gate=release)
    await page.get_by_test_id("sv3-composer-stop").click()
    await fixture.wait_request("DELETE", path, count=2)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    assert len(fixture.matching("DELETE", path)) == 2, fixture.requests
    only_run(fixture)


async def setup_run_halt_timeout(page):
    """S3 S6: an unanswered DELETE becomes unconfirmed and permits one retry."""
    fixture = await start(page)
    await activity(fixture, page, "Running while Stop gets no answer.")
    path = f"/api/chat/sessions/{fixture.session_id}"
    release = asyncio.Event()
    fixture.replies[("DELETE", path)] = fixtures.FixtureReply(gate=release)
    await page.get_by_test_id("sv3-composer-stop").click()
    await fixture.wait_request("DELETE", path)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text(
        "Could not stop this answer — it goes on. Press Stop to try again.", timeout=15_000)
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    await activity(fixture, page, "Output continues after the unanswered Stop.")
    await page.get_by_test_id("sv3-composer-stop").click()
    await fixture.wait_request("DELETE", path, count=2)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await expect(page.get_by_test_id("sv3-run-receipt")).to_have_count(0)
    assert len(fixture.matching("DELETE", path)) == 2, fixture.requests
    only_run(fixture)


async def setup_ask_stop_late_response(page):
    """R1: Ask keeps waiting past Halt's deadline and accepts the late response."""
    page.set_default_timeout(TIMEOUT)
    await page.emulate_media(reduced_motion="reduce")
    fixture = fixtures.agent_fixture(page.context)
    await page.locator(S.CSS_SV3_WINDOW).wait_for(state="visible")
    await page.get_by_test_id("sv3-composer-tier").click()
    await page.locator('[data-testid="sv3-composer-tier-option"][data-tier="ask"]').click()
    await send(page, "Explain the fixture documents")
    dispatch = await fixture.wait_request("POST", DISPATCH)
    assert dispatch.body["shapeId"] == "core.rag-ask", dispatch
    path = f"/api/chat/conversations/{dispatch.body['conversationId']}/stop"
    release = asyncio.Event()
    fixture.replies[("POST", path)] = fixtures.FixtureReply(
        gate=release, body={"stopped": True, "pending": False})
    await fixture.stream.emit("chunk", {"text": "The answer keeps arriving."})
    await page.get_by_test_id("sv3-composer-stop").click()
    request = await fixture.wait_request("POST", path)
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await asyncio.sleep(15)
    assert not request.answered.is_set()
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    release.set()
    await asyncio.wait_for(request.answered.wait(), TIMEOUT / 1000)
    await page.get_by_test_id("sv3-composer-stop").click()
    await expect(page.get_by_test_id("sv3-turn-stopping")).to_have_text("Stopping…")
    await fixture.stream.emit("error", {"error": "Stopped by the user", "errorCode": "CANCELLED"})
    # Ask's consumeShapeStream reports the terminal when the response reaches EOF;
    # unlike the agent controller, it does not settle inside the event callback.
    await fixture.stream.disconnect()
    await expect(page.get_by_test_id("sv3-turn")).to_have_attribute("data-status", "halted", timeout=TIMEOUT)
    assert len(fixture.matching("POST", path)) == 1, fixture.requests
