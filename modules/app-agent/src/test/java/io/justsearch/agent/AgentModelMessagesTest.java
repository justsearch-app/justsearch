/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static io.justsearch.agent.AgentLoopServiceTest.*;
import static io.justsearch.agent.ModelRequestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.SamplingParams;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * WP6.1 at the model boundary: only the leading system message goes out as system; every later
 * system entry (a direction, the resume warning) goes out as a labelled user turn at its place.
 * {@link CapturingAi} refuses what a strict template refuses; the live template is checked separately.
 */
class AgentModelMessagesTest {
  @TempDir Path tempDir;

  // S6.1 / S6.3 — steering after real tool rounds, twice, at separate steps.
  @Test
  void directionsAfterToolRoundsGoOutAsUserTurnsAfterTheLastToolResult() {
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("c1", "core_search", "{\"q\":1}"),
        ScriptedResponse.toolCall("c2", "core_search", "{\"q\":2}"),
        ScriptedResponse.toolCall("c3", "core_search", "{\"q\":3}"),
        ScriptedResponse.textOnly("done"));
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted completed) {
        if (completed.callId().equals("c1")) {
          assertTrue(service.injectSteeringDirective(run.id(), "Focus on Q3."));
        } else if (completed.callId().equals("c2")) {
          assertTrue(service.injectSteeringDirective(run.id(), "Answer in French instead."));
        }
      }
    };
    service.runAgent(request(history(), 6), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(List.of(), ai.rejected);
    assertEquals(4, ai.sent().size());
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals("base", system(ai.sent().get(0)), "the leading system message is sent as it is");
    assertEquals(List.of(), directionsIn(ai.sent().get(0)));
    assertEquals(List.of("Focus on Q3."), directionsIn(ai.sent().get(1)));
    assertEquals(List.of("Focus on Q3.", "Answer in French instead."), directionsIn(ai.sent().get(2)));
    assertEquals(List.of("Focus on Q3.", "Answer in French instead."), directionsIn(ai.sent().get(3)),
        "each consumed direction appears once per request, in order");
    // The late correction is a user turn directly after the last tool result.
    var third = ai.sent().get(2);
    assertEquals(toolResult("c2", "result"), third.get(third.size() - 2));
    assertEquals(Map.of("role", "user", "content", DIRECTION_LABEL + "Answer in French instead." + DIRECTION_TAIL),
        third.getLast());
    assertEquals(List.of("applied", "applied"),
        run.outcomes().stream().filter(o -> !"queued".equals(o)).toList(),
        "each direction is reported applied exactly once");
    assertEquals(ai.sent(), ai.counted, "counting and completion receive the same list");
    @SuppressWarnings("unchecked")
    var saved = (List<Map<String, Object>>) store.readSnapshot(run.id()).get("messages");
    assertEquals(List.of("base", steering("Focus on Q3."), steering("Answer in French instead.")),
        saved.stream().filter(m -> "system".equals(m.get("role"))).map(m -> m.get("content")).toList(),
        "saved history keeps its system notes unchanged");
  }

  // S6.2 / S6.3 / S6.4 — saved trailing notes resume (both entry points), and steering after resume.
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"READY_FOR_LLM,true", "READY_FOR_LLM,false", "AFTER_TOOL_RESULT,true",
      "AFTER_TOOL_RESULT,false", "WAITING_APPROVAL,true", "WAITING_APPROVAL,false"})
  void savedTrailingNotesResumeAndSteeringAfterResumeReachTheModel(String state, boolean byId) {
    var ai = new CapturingAi(ScriptedResponse.toolCall("new", "core_search", "{}"),
        ScriptedResponse.textOnly("resumed"));
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = toolHistory();
    saved.add(message("system", steering("Saved direction.")));
    if (state.equals("WAITING_APPROVAL")) saved.add(assistantCalls("pending", "core_write"));
    var original = List.copyOf(saved);
    store.startRun("old", request(saved, 4), saved, 10000);
    store.updateCheckpoint("old", state, saved, 1, 1, 0, "");
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        assertTrue(service.injectSteeringDirective(run.id(), "New direction after resume."));
      }
    };
    if (byId) service.resumeSession("old", run, EngineContextTestFixtures.AGENT_LOOP);
    else service.resumeLastSession(run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(List.of(), ai.rejected);
    assertEquals(2, ai.sent().size());
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals("base", system(ai.sent().get(0)));
    assertEquals(List.of("Saved direction."), directionsIn(ai.sent().get(0)));
    assertEquals(List.of("Saved direction.", "New direction after resume."), directionsIn(ai.sent().get(1)));
    long warnings = state.equals("WAITING_APPROVAL") ? 1 : 0;
    assertEquals(warnings, resumeNotesIn(ai.sent().get(0)));
    assertEquals(warnings, resumeNotesIn(ai.sent().get(1)));
    assertEquals(toolResult("old-call", "old result"), ai.sent().get(0).get(3), "ids survive");
    assertEquals(ai.sent(), ai.counted);
    assertEquals(original, store.readSnapshot("old").get("messages"),
        "resuming must not migrate the old snapshot");
  }

  // S6.2 — a write still runs only after a fresh approval; rejection prevents it.
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {true, false})
  void resumedApprovalStillRequiresAFreshDecision(boolean approve) {
    var ai = new CapturingAi(ScriptedResponse.toolCall("fresh", "core_write", "{}"),
        ScriptedResponse.textOnly("done"));
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = history();
    saved.add(assistantCalls("old-pending", "core_write"));
    store.startRun("old", request(saved, 4), saved, 10000);
    store.updateCheckpoint("old", "WAITING_APPROVAL", saved, 1, 0, 0, "");
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var service = service(ai, store, write);
    var approvals = new ArrayList<String>();
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval pending) {
        approvals.add(pending.callId());
        assertEquals(0, write.callCount.get(), "the write cannot run before fresh approval");
        if (approve) service.approveToolCall(run.id(), pending.callId());
        else service.rejectToolCall(run.id(), pending.callId(), "declined");
      }
    };
    service.resumeSession("old", run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(List.of(), ai.rejected);
    assertEquals(List.of("fresh"), approvals);
    assertEquals(approve ? 1 : 0, write.callCount.get());
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals(1, resumeNotesIn(ai.sent().get(0)));
  }

  // S6.6 — the profile prompt, a saved direction, a fresh one and the resume warning all reach the
  // model; a handoff after steering sends a valid list.
  @Test
  void profileKeepsDirectionsAndResumeWarningAndHandoffSendsValidList() {
    var ai = new CapturingAi(ScriptedResponse.toolCall("search", "core_search", "{}"),
        ScriptedResponse.toolCall("handoff", "handoff_to_executor", "{\"reason\":\"Delegate\"}"),
        ScriptedResponse.textOnly("done"));
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var profiles = List.of(new AgentProfile("planner", "Planner", "Planner prompt", List.of()),
        new AgentProfile("executor", "Executor", "Executor prompt", List.of()));
    var saved = history();
    saved.add(message("system", steering("Saved direction.")));
    store.startRun("old", new AgentRequest(saved, List.of(), 5, profiles, "planner"), saved, 10000);
    store.updateCheckpoint("old", "WAITING_APPROVAL", saved, 0, 0, 0, "");
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted completed && completed.callId().equals("search")) {
        assertTrue(service.injectSteeringDirective(run.id(), "Fresh direction."));
      }
    };
    service.resumeSession("old", run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(List.of(), ai.rejected);
    assertEquals(3, ai.sent().size());
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals("Planner prompt", system(ai.sent().get(0)));
    assertEquals(List.of("Saved direction."), directionsIn(ai.sent().get(0)));
    assertEquals(1, resumeNotesIn(ai.sent().get(0)));
    assertEquals(List.of("Saved direction.", "Fresh direction."), directionsIn(ai.sent().get(1)));
    var afterHandoff = ai.sent().get(2);
    assertTrue(system(afterHandoff).startsWith("Executor prompt\n\n"), system(afterHandoff));
    assertTrue(system(afterHandoff).contains("Handoff from planner to executor"));
    assertEquals(List.of("Saved direction.", "Fresh direction."), directionsIn(afterHandoff));
    assertEquals(1, resumeNotesIn(afterHandoff));
    assertEquals(ai.sent(), ai.counted);
  }

  // S6.5 — every completion path sends the same converted list and leaves history alone.
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ordinary", "retry", "budget-finalize", "step-finalize"})
  void everyCompletionPathSendsLaterSystemEntriesAsUserTurns(String path) {
    var ai = path.equals("retry")
        ? new CapturingAi(ScriptedResponse.empty(), ScriptedResponse.textOnly("done"))
        : new CapturingAi(ScriptedResponse.textOnly("done"));
    var messages = toolHistory();
    messages.set(0, Map.of("role", "system", "content", " base ", "name", "original"));
    messages.add(message("system", "  "));
    messages.add(message("system", steering("first direction")));
    messages.add(message("assistant", "intermediate"));
    messages.add(message("system", "An older build's note."));
    var original = List.copyOf(messages);
    var session = new AgentSession(messages, 10000, EngineContextTestFixtures.AGENT_LOOP);
    var caller = new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(false, 200, 1));
    switch (path) {
      // A strict template refuses the request by throwing; that must not happen on any path.
      case "ordinary" -> assertDoesNotThrow(
          () -> caller.callLlmWithTools(session, List.of(), event -> {}, SamplingParams.AGENT));
      case "retry" -> assertDoesNotThrow(() -> caller.callLlmWithRetries(session, List.of(), event -> {}));
      case "budget-finalize" -> assertEquals("done", caller.attemptBudgetEdgeFinalize(session, event -> {}));
      case "step-finalize" -> assertEquals("done", caller.attemptStepCeilingFinalize(session, event -> {}));
      default -> throw new AssertionError(path);
    }
    assertEquals(List.of(), ai.rejected);
    assertEquals(path.equals("retry") ? 2 : 1, ai.sent().size());
    for (var sent : ai.sent()) {
      assertValidRequest(sent);
      assertEquals(messages.get(0), sent.get(0), "the leading system message is sent as it is");
      var history = session.messages();
      var expected = new ArrayList<Map<String, Object>>(history.subList(1, 4));
      expected.add(message("user", DIRECTION_LABEL + "first direction" + DIRECTION_TAIL));
      expected.add(message("assistant", "intermediate"));
      expected.add(message("user", "Note from JustSearch: An older build's note."));
      expected.addAll(history.subList(original.size(), history.size()));
      assertEquals(expected, sent.subList(1, sent.size()));
      for (int i = 1; i < 4; i++) assertSame(history.get(i), sent.get(i), "other messages pass through");
    }
    assertEquals(original, session.messages().subList(0, original.size()));
  }

  // S6.5 — the token count after compaction measures the list that is sent.
  @Test
  void tokenCountAfterCompactionMeasuresTheSentList() {
    var ai = new CapturingAi(ScriptedResponse.textOnly("done"));
    ai.counts = List.of(3900); // Above the 4096-token context's servable threshold; force compaction.
    var messages = history();
    for (int i = 0; i < 12; i++) {
      messages.add(message("user", "old question " + i));
      messages.add(message("assistant", "old answer " + i));
    }
    messages.add(message("system", steering("Keep this recent direction.")));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var events = new ArrayList<AgentEvent>();
    service.runAgent(request(messages, 3), events::add, EngineContextTestFixtures.AGENT_LOOP);
    assertDone(events);
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ContextCompacted));
    assertEquals(2, ai.counted.size(), "exercise the post-compaction recount as well as projection");
    ai.counted.forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals(List.of("Keep this recent direction."), directionsIn(ai.counted.get(1)));
    assertEquals(ai.sent().get(0), ai.counted.get(1));
  }

  // N6.1 / N6.5 — an unsteered history goes out exactly as it is.
  @Test
  void unsteeredMessagesAndToolIdsReachTheModelUnchanged() {
    var ai = new CapturingAi(ScriptedResponse.textOnly("done"));
    var messages = toolHistory();
    var session = new AgentSession(messages, 10000, EngineContextTestFixtures.AGENT_LOOP);
    new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(false, 200, 1))
        .callLlmWithTools(session, List.of(), event -> {}, SamplingParams.AGENT);
    assertEquals(messages, ai.sent().get(0));
    assertEquals(messages, session.messages());
  }

  private static AgentLoopService service(CapturingAi ai, AgentRunStore store, StubTool... tools) {
    return observed(new AgentLoopService(ai, stubCatalog(tools), stubExecutor(tools), stubEmitter(),
        null, null, store, null));
  }

  private static AgentRequest request(List<Map<String, Object>> messages, int iterations) {
    return new AgentRequest(messages, List.of(), iterations);
  }

  private static ArrayList<Map<String, Object>> toolHistory() {
    var messages = history();
    messages.add(AgentLlmCaller.buildAssistantToolCallMessage(new LlmCallResult(null, List.of(), null),
        List.of(new io.justsearch.agent.api.ToolCallRequest("old-call", "core_search", "{\"q\":\"old\"}"))));
    messages.add(toolResult("old-call", "old result"));
    return messages;
  }
}
