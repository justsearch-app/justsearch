/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static io.justsearch.agent.AgentLoopServiceTest.*;
import static io.justsearch.agent.ModelRequestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.RiskTier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * WP6.2 — steering, handoff, compaction, fork and resume keep the user's directions, report what
 * happened to each one, and send the model a valid conversation. Requests are inspected at the
 * model boundary through {@link CapturingAi}, which refuses what a strict template refuses.
 */
class AgentSteeringResumeTest {
  @TempDir Path tempDir;

  // ---- S8.1 several directions before one step ----

  @Test
  void severalDirectionsBeforeOneStepAllReachTheModelOnceInOrder() {
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("c1", "core_search", "{}"),
        ScriptedResponse.textOnly("done"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        for (String text : List.of("Focus on Q3.", "Ignore drafts.", "Focus on Q3.", "Answer in French.")) {
          assertTrue(service.injectSteeringDirective(run.id(), text));
        }
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(2, ai.sent().size());
    assertEquals(List.of(), directionsIn(ai.sent().get(0)));
    assertEquals(List.of("Focus on Q3.", "Ignore drafts.", "Focus on Q3.", "Answer in French."),
        directionsIn(ai.sent().get(1)), "every direction reaches the next request, once, in order");
    assertEquals(List.of("Focus on Q3.", "Ignore drafts.", "Focus on Q3.", "Answer in French."),
        run.texts("applied"));
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals(ai.sent(), ai.counted, "counting measures the list that is sent");
  }

  @Test
  void concurrentDirectionsDuringStepsAreNeitherLostNorDuplicated() throws Exception {
    var responses = new ArrayList<ScriptedResponse>();
    for (int i = 0; i < 6; i++) responses.add(ScriptedResponse.toolCall("c" + i, "core_search", "{}"));
    responses.add(ScriptedResponse.textOnly("done"));
    var ai = new CapturingAi(responses.toArray(ScriptedResponse[]::new));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    var accepted = new CopyOnWriteArrayList<String>();
    var threads = new ArrayList<Thread>();
    var start = new CountDownLatch(1);
    ai.beforeStream = index -> {
      if (index == 0) {
        for (int t = 0; t < 4; t++) {
          int thread = t;
          var submitter = new Thread(() -> {
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            for (int n = 0; n < 25; n++) {
              String text = "t" + thread + "-" + n;
              if (service.injectSteeringDirective(run.id(), text)) accepted.add(text);
              Thread.yield();
            }
          });
          threads.add(submitter);
          submitter.start();
        }
        start.countDown();
      }
      // Give the submitters time to race the drains of the following steps.
      if (index > 0 && index < 6) sleepQuietly(2);
    };
    service.runAgent(request(history(), 10), run, EngineContextTestFixtures.AGENT_LOOP);
    for (var thread : threads) thread.join(TimeUnit.SECONDS.toMillis(10));

    assertDone(run.events);
    var last = directionsIn(ai.sent().getLast());
    assertEquals(new HashSet<>(last).size(), last.size(), "no direction is applied twice: " + last);
    var applied = run.texts("applied");
    var notApplied = run.texts("not_applied");
    assertEquals(applied, last, "the last request carries exactly the applied directions, in order");
    for (String text : accepted) {
      int seen = Collections.frequency(applied, text) + Collections.frequency(notApplied, text);
      assertEquals(1, seen, "accepted direction " + text + " must be applied or reported not applied, once");
      assertEquals(1, Collections.frequency(run.texts("queued"), text));
    }
    assertEquals(accepted.size(), applied.size() + notApplied.size());
    for (int t = 0; t < 4; t++) {
      String prefix = "t" + t + "-";
      var order = applied.stream().filter(s -> s.startsWith(prefix))
          .map(s -> Integer.parseInt(s.substring(prefix.length()))).toList();
      assertEquals(order.stream().sorted().toList(), order, "one submitter's directions keep their order");
    }
  }

  // ---- S8.2 applied only when sent; otherwise reported not applied ----

  @Test
  void directionIsReportedQueuedThenAppliedWhenTheModelAcceptsItsRequest() {
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("c1", "core_search", "{}"),
        ScriptedResponse.textOnly("done"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    var requestsWhenApplied = new ArrayList<Integer>();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Focus on Q3."));
      }
      if (event instanceof AgentEvent.DirectiveAcknowledged ack && "applied".equals(outcome(ack))) {
        requestsWhenApplied.add(ai.sent().size());
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    var acks = run.acks();
    assertEquals(List.of("queued", "applied"), run.outcomes());
    assertNotNull(idOf(acks.get(0)));
    assertEquals(idOf(acks.get(0)), idOf(acks.get(1)), "both reports name the same direction");
    assertEquals(List.of(2), requestsWhenApplied, "applied is reported once the second request is accepted");
    assertEquals(List.of("Focus on Q3."), directionsIn(ai.sent().get(1)));
  }

  @Test
  void directionInARequestTheModelRefusesIsNotReportedApplied() {
    // The model refuses every request (as a template error would): the direction never reached it.
    var ai = new CapturingAi(ScriptedResponse.toolCall("c1", "core_search", "{}"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Focus on Q3."));
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(ai.sent().size() > 1, "the direction was in the refused requests");
    assertEquals(List.of("queued", "not_applied"), run.outcomes());
    assertEquals("run_failed", reasonOf(run.acks().getLast()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"completed", "llm-failure", "step-limit"})
  void directionArrivingDuringTheLastModelCallIsReportedNotApplied(String ending) {
    var ai = switch (ending) {
      case "completed" -> new CapturingAi(ScriptedResponse.textOnly("done"));
      // No scripted response: the stream errors, the loop retries and fails.
      case "llm-failure" -> new CapturingAi();
      case "step-limit" -> new CapturingAi(ScriptedResponse.toolCall("c1", "core_search", "{}"),
          ScriptedResponse.textOnly("partial answer"));
      default -> throw new AssertionError(ending);
    };
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    ai.beforeStream = index -> {
      if (index == 0) assertTrue(service.injectSteeringDirective(run.id(), "Too late."));
    };
    service.runAgent(request(history(), 1), run, EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(List.of("queued", "not_applied"), run.outcomes());
    String reason = switch (ending) {
      case "completed" -> "run_finished";
      case "llm-failure" -> "run_failed";
      default -> "step_limit";
    };
    assertEquals(reason, reasonOf(run.acks().get(1)));
    ai.sent().forEach(sent -> assertEquals(List.of(), directionsIn(sent)));
  }

  @ParameterizedTest
  @CsvSource({"context-stop,run_stopped", "budget-stop,budget_limit", "cancel,run_stopped"})
  void directionDrainedButEndedBeforeTheModelCallIsReportedNotApplied(String ending, String reason) {
    // The first call spends nearly the whole budget (budget-stop) or the second projection crosses
    // the context-pressure threshold: a gate holds the step after the direction was drained.
    var search = ScriptedResponse.toolCall("c1", "core_search", "{}");
    var first = ending.equals("budget-stop")
        ? new ScriptedResponse(null, null, search.toolCallDeltas(),
            new io.justsearch.app.api.OnlineAiService.AiUsage(0, budgetFor4096() - 200, budgetFor4096() - 200))
        : search;
    var ai = new CapturingAi(first, ScriptedResponse.textOnly("never sent"));
    ai.counts = ending.equals("budget-stop") ? List.of(100, 1000) : List.of(100, 3500);
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolExecutionCompleted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Stop after this."));
      }
      if (event instanceof AgentEvent.ContextGatePending) {
        // A cancel releases the held gate itself.
        if (ending.equals("cancel")) service.cancelSession(run.id());
        else assertTrue(service.resolveContextGate(run.id(), "stop"));
      }
      if (event instanceof AgentEvent.BudgetGatePending) {
        assertTrue(service.resolveBudgetGate(run.id(), "stop"));
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(1, ai.sent().size(), "the drained direction never went out");
    assertEquals(List.of("queued", "not_applied"), run.outcomes());
    assertEquals(reason, reasonOf(run.acks().get(1)));
  }

  @Test
  void directionAfterTheRunStoppedTakingThemIsRefusedNotAcceptedAndDropped() {
    var ai = new CapturingAi(ScriptedResponse.textOnly("done"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    var lateAccepted = new AtomicReference<Boolean>();
    ai.beforeStream = index -> assertTrue(service.injectSteeringDirective(run.id(), "late"));
    run.onEvent = event -> {
      // The final report is emitted after the run's last drain, while it is still registered.
      if (event instanceof AgentEvent.DirectiveAcknowledged ack && !"queued".equals(outcome(ack))
          && lateAccepted.get() == null) {
        lateAccepted.set(service.injectSteeringDirective(run.id(), "later still"));
      }
    };
    service.runAgent(request(history(), 2), run, EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(Boolean.FALSE, lateAccepted.get(),
        "once the run has reported its last outcomes it must refuse, not accept and drop");
    assertTrue(run.acks().stream().noneMatch(a -> a.directiveText().equals("later still")));
    assertFalse(service.injectSteeringDirective(run.id(), "after the end"));
  }

  // ---- S8.6 steering while a write waits for approval ----

  @ParameterizedTest
  @ValueSource(strings = {"approve", "reject", "stop"})
  void directionWhileApprovalIsHeldNeitherDecidesItNorClaimsEarlierTiming(String decision) {
    var ai = new CapturingAi(ScriptedResponse.toolCall("w1", "core_write", "{}"),
        ScriptedResponse.textOnly("done"));
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var service = service(ai, null, write);
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval pending) {
        assertTrue(service.injectSteeringDirective(run.id(), "Be brief."));
        assertEquals(0, write.callCount.get(), "a direction is not an approval");
        assertFalse(run.events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallRejected),
            "a direction is not a rejection");
        switch (decision) {
          case "approve" -> service.approveToolCall(run.id(), pending.callId());
          case "reject" -> service.rejectToolCall(run.id(), pending.callId(), "no");
          default -> service.cancelSession(run.id());
        }
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(decision.equals("approve") ? 1 : 0, write.callCount.get());
    var acks = run.acks();
    assertEquals("queued", outcome(acks.get(0)));
    var last = acks.getLast();
    if (decision.equals("stop")) {
      assertEquals("not_applied", outcome(last));
      assertEquals("run_stopped", reasonOf(last));
      return;
    }
    assertEquals("applied", outcome(last));
    int decided = indexOf(run.events, e -> decision.equals("approve")
        ? e instanceof AgentEvent.ToolExecutionCompleted : e instanceof AgentEvent.ToolCallRejected);
    assertTrue(decided >= 0 && decided < run.events.indexOf(last),
        "the direction is reported applied only after the held action was decided and ran");
    assertEquals(List.of("Be brief."), directionsIn(ai.sent().get(1)));
  }

  // ---- S8.3 handoff keeps directions and the resume warning ----

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void firstHandoffAfterSteeringKeepsTheDirectionForTheReceivingAgent(boolean explored) {
    var scripted = new ArrayList<ScriptedResponse>();
    if (explored) scripted.add(ScriptedResponse.toolCall("s1", "core_search", "{}"));
    scripted.add(ScriptedResponse.toolCall("h1", "handoff_to_executor", "{\"reason\":\"go\"}"));
    scripted.add(ScriptedResponse.textOnly("done"));
    var ai = new CapturingAi(scripted.toArray(ScriptedResponse[]::new));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.SessionStarted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Only PDFs."));
      }
    };
    service.runAgent(new AgentRequest(history(), List.of(), 6, profiles("planner", "executor"), "planner"),
        run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    var afterHandoff = ai.sent().getLast();
    assertTrue(system(afterHandoff).startsWith("executor prompt"));
    assertEquals(List.of("Only PDFs."), directionsIn(afterHandoff));
    if (explored) assertTrue(system(afterHandoff).contains("Research findings:"), "brief still built");
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
    assertEquals(ai.sent(), ai.counted);
  }

  @ParameterizedTest
  @CsvSource({"reviewer", "planner"})
  void secondHandoffAndReturnKeepTheDirections(String third) {
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("h1", "handoff_to_executor", "{\"reason\":\"go\"}"),
        ScriptedResponse.toolCall("s1", "core_search", "{}"),
        ScriptedResponse.toolCall("h2", "handoff_to_" + third, "{\"reason\":\"next\"}"),
        ScriptedResponse.textOnly("done"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.SessionStarted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Only PDFs."));
      }
      if (event instanceof AgentEvent.ToolExecutionCompleted done && done.callId().equals("s1")) {
        assertTrue(service.injectSteeringDirective(run.id(), "Skip 2023."));
      }
    };
    service.runAgent(new AgentRequest(history(), List.of(), 8,
        profiles("planner", "executor", "reviewer"), "planner"), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    var last = ai.sent().getLast();
    assertTrue(system(last).startsWith(third + " prompt"), system(last));
    assertEquals(List.of("Only PDFs.", "Skip 2023."), directionsIn(last));
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void resumeAfterHandoffKeepsTheDirectionAndAddsTheResumeWarning(boolean byId) {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("h1", "handoff_to_executor", "{\"reason\":\"go\"}"),
        ScriptedResponse.toolCall("w1", "core_write", "{}"));
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var service = service(ai, store, write);
    var run = new Run();
    var interrupted = new AtomicReference<Map<String, Object>>();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.SessionStarted) {
        assertTrue(service.injectSteeringDirective(run.id(), "Only PDFs."));
      }
      if (event instanceof AgentEvent.ToolCallPendingApproval) {
        // The checkpoint an Engine restart would leave behind at this moment.
        interrupted.set(store.readSnapshot(run.id()));
        service.cancelSession(run.id());
      }
    };
    service.runAgent(new AgentRequest(history(), List.of(), 6, profiles("planner", "executor"), "planner"),
        run, EngineContextTestFixtures.AGENT_LOOP);
    assertEquals("WAITING_APPROVAL", interrupted.get().get("state"));
    @SuppressWarnings("unchecked")
    var saved = (List<Map<String, Object>>) interrupted.get().get("messages");
    store.startRun("old", new AgentRequest(saved, List.of(), 6, profiles("planner", "executor"), "planner"),
        saved, 10000);
    store.setHandoffState("old", "executor", List.of());
    store.updateCheckpoint("old", "WAITING_APPROVAL", saved, 2, 0, 0, "");

    var resumedAi = new CapturingAi(ScriptedResponse.textOnly("resumed"));
    var resumed = service(resumedAi, store, write);
    var events = new ArrayList<AgentEvent>();
    if (byId) resumed.resumeSession("old", events::add, EngineContextTestFixtures.AGENT_LOOP);
    else resumed.resumeLastSession(events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(events);
    var first = resumedAi.sent().getFirst();
    assertTrue(system(first).startsWith("executor prompt"), system(first));
    assertEquals(List.of("Only PDFs."), directionsIn(first));
    assertEquals(1, resumeNotesIn(first));
    assertValidRequest(first);
    assertNotExecuted(toolResults(first).get("w1"));
    assertEquals(0, write.callCount.get());
  }

  // ---- S8.4 compaction keeps directions and the resume warning ----

  @Test
  void compactionKeepsOlderDirectionsAndTheResumeWarning() {
    var ai = new CapturingAi(ScriptedResponse.textOnly("done"));
    ai.counts = List.of(3900, 100); // above the servable threshold: compaction runs
    var messages = history();
    messages.add(message("system", steering("Older direction.")));
    messages.add(message("system", RESUME_NOTE));
    for (int i = 0; i < 12; i++) {
      messages.add(message("user", "old question " + i));
      messages.add(message("assistant", "old answer " + i));
    }
    messages.add(message("user", "current question"));
    var service = service(ai, null, new StubTool("search", RiskTier.LOW, "result"));
    var events = new ArrayList<AgentEvent>();
    service.runAgent(request(messages, 3), events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(events);
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ContextCompacted));
    var sent = ai.sent().getFirst();
    assertValidRequest(sent);
    assertEquals(List.of("Older direction."), directionsIn(sent));
    assertEquals(1, resumeNotesIn(sent));
    assertFalse(sent.contains(message("user", "old question 0")), "older turns are still dropped");
    assertEquals(sent, ai.counted.getLast());
  }

  @Test
  void compactOlderTurnsReportsOnlyWhatItDropped() {
    var messages = history();
    messages.add(message("system", steering("Keep.")));
    for (int i = 0; i < 4; i++) {
      messages.add(message("user", "q" + i));
      messages.add(message("assistant", "a" + i));
    }
    var session = new AgentSession(messages, 10000, EngineContextTestFixtures.AGENT_LOOP);
    int dropped = session.compactOlderTurns(2);
    assertEquals(6, dropped);
    assertEquals(List.of(message("system", "base"), message("user", "task"),
        message("system", steering("Keep.")), message("user", "q3"), message("assistant", "a3")),
        session.messages());
  }

  // ---- S8.5 resume with outstanding tool calls ----

  @ParameterizedTest
  @CsvSource({
    "WAITING_APPROVAL,true", "WAITING_APPROVAL,false",
    "READY_FOR_LLM,true", "READY_FOR_LLM,false",
    "AFTER_TOOL_RESULT,true", "AFTER_TOOL_RESULT,false"})
  void resumeWithOutstandingCallsSendsAValidConversationAndRepeatsNoWrite(String state, boolean byId) {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = history();
    // One batch: a completed read, a completed write, then the call the run stopped at, then one
    // it never reached. READY_FOR_LLM is the checkpoint written right after a rejection.
    saved.add(assistantCalls("r1", "core_search", "w1", "core_write", "w2", "core_write", "r2", "core_search"));
    saved.add(toolResult("r1", "read result"));
    saved.add(toolResult("w1", "written once"));
    if (state.equals("READY_FOR_LLM")) saved.add(toolResult("w2", "Tool call rejected by user"));
    if (state.equals("AFTER_TOOL_RESULT")) saved.add(toolResult("w2", "written twice"));
    var original = List.copyOf(saved);
    store.startRun("old", request(saved, 4), saved, 10000);
    store.updateCheckpoint("old", state, saved, 1, 2, 0, "");
    var ai = new CapturingAi(ScriptedResponse.textOnly("resumed"));
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var search = new StubTool("search", RiskTier.LOW, "result");
    var service = service(ai, store, search, write);
    var events = new ArrayList<AgentEvent>();
    if (byId) service.resumeSession("old", events::add, EngineContextTestFixtures.AGENT_LOOP);
    else service.resumeLastSession(events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(events);
    assertEquals(List.of(), ai.rejected);
    var first = ai.sent().getFirst();
    assertValidRequest(first);
    var results = toolResults(first);
    assertEquals(List.of("r1", "w1", "w2", "r2"), List.copyOf(results.keySet()), "answers in call order");
    assertEquals("read result", results.get("r1"));
    assertEquals("written once", results.get("w1"));
    if (state.equals("WAITING_APPROVAL")) assertNotExecuted(results.get("w2"));
    if (state.equals("READY_FOR_LLM")) assertEquals("Tool call rejected by user", results.get("w2"));
    if (state.equals("AFTER_TOOL_RESULT")) assertEquals("written twice", results.get("w2"));
    assertNotExecuted(results.get("r2"));
    assertEquals(state.equals("WAITING_APPROVAL") ? 1 : 0, resumeNotesIn(first));
    if (state.equals("WAITING_APPROVAL")) {
      assertEquals(RESUME_NOTE_SENT, first.getLast().get("content"), "the warning follows the closed calls");
    }
    assertEquals(0, write.callCount.get(), "completed writes are not repeated by resuming");
    assertEquals(0, search.callCount.get());
    assertEquals(ai.sent(), ai.counted);
    assertEquals(original, store.readSnapshot("old").get("messages"), "the saved snapshot is not rewritten");
  }

  @ParameterizedTest
  @ValueSource(strings = {"approve", "reject", "stale"})
  void pendingWriteRunsOnlyAfterAFreshValidApproval(String decision) {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = history();
    saved.add(assistantCalls("old-pending", "core_write"));
    store.startRun("old", request(saved, 4), saved, 10000);
    store.updateCheckpoint("old", "WAITING_APPROVAL", saved, 1, 0, 0, "");
    var ai = new CapturingAi(ScriptedResponse.toolCall("fresh", "core_write", "{}"),
        ScriptedResponse.textOnly("done"));
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var service = service(ai, store, write);
    var run = new Run();
    var staleAccepted = new ArrayList<Boolean>();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval pending) {
        assertEquals("fresh", pending.callId());
        switch (decision) {
          case "approve" -> service.approveToolCall(run.id(), "fresh");
          case "reject" -> service.rejectToolCall(run.id(), "fresh", "no");
          default -> {
            staleAccepted.add(service.tryApproveToolCall(run.id(), "old-pending"));
            staleAccepted.add(service.tryApproveToolCall("old", "old-pending"));
            assertEquals(0, write.callCount.get());
            service.rejectToolCall(run.id(), "fresh", "no");
          }
        }
      }
    };
    service.resumeSession("old", run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(decision.equals("approve") ? 1 : 0, write.callCount.get());
    if (decision.equals("stale")) assertEquals(List.of(false, false), staleAccepted);
    assertNotExecuted(toolResults(ai.sent().getFirst()).get("old-pending"));
    ai.sent().forEach(ModelRequestFixtures::assertValidRequest);
  }

  @Test
  void snapshotWrittenBeforeTheChangeResumesWithItsStraySystemsAndDanglingCall() {
    // The shape a pre-WP6.2 approval checkpoint had: a steering note after the tool round, then an
    // assistant call that was never answered.
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = history();
    saved.add(assistantCalls("c1", "core_search"));
    saved.add(toolResult("c1", "result"));
    saved.add(message("system", steering("Old direction.")));
    saved.add(assistantCalls("p1", "core_write"));
    var original = List.copyOf(saved);
    store.startRun("old", request(saved, 4), saved, 10000);
    store.updateCheckpoint("old", "WAITING_APPROVAL", saved, 2, 1, 0, "");
    var ai = new CapturingAi(ScriptedResponse.textOnly("resumed"));
    var service = service(ai, store, new StubTool("write", RiskTier.MEDIUM, "written"));
    var events = new ArrayList<AgentEvent>();
    service.resumeSession("old", events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(events);
    var first = ai.sent().getFirst();
    assertValidRequest(first);
    assertEquals(List.of("Old direction."), directionsIn(first));
    assertNotExecuted(toolResults(first).get("p1"));
    assertEquals(original, store.readSnapshot("old").get("messages"));
  }

  // ---- S8.7 fork keeps directions and the resume warning ----

  @Test
  void forkOfASteeredResumedRunKeepsDirectionsAndSendsValidCalls() {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var saved = history();
    saved.add(assistantCalls("x1", "core_search")); // an earlier dangling call
    saved.add(message("assistant", "first answer"));
    saved.add(message("user", "second task"));
    saved.add(message("system", steering("Only PDFs.")));
    saved.add(message("system", RESUME_NOTE));
    saved.add(assistantCalls("c2", "core_search"));
    saved.add(toolResult("c2", "result"));
    saved.add(message("assistant", "second answer"));
    store.startRun("done-run", request(saved, 4), saved, 10000);
    store.updateCheckpoint("done-run", "DONE", saved, 2, 1, 0, "");
    var ai = new CapturingAi(ScriptedResponse.textOnly("forked"));
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"));
    var events = new ArrayList<AgentEvent>();
    service.forkSession("done-run", "second task, edited", events::add, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(events);
    var first = ai.sent().getFirst();
    assertValidRequest(first);
    assertEquals(List.of("Only PDFs."), directionsIn(first));
    assertEquals(1, resumeNotesIn(first));
    assertTrue(first.contains(message("user", "second task, edited")));
    assertFalse(first.contains(message("assistant", "second answer")), "the prior response is dropped");
    assertNotExecuted(toolResults(first).get("x1"));
    assertEquals(ai.sent(), ai.counted);
  }

  // ---- N8.1 / N8.2 preservation ----

  @Test
  void unsteeredApprovalRunSendsAndSavesTheSameMessages() {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var ai = new CapturingAi(ScriptedResponse.multiToolCall("r1", "core_search", "{}", "w1", "core_write", "{}"),
        ScriptedResponse.textOnly("done"));
    var write = new StubTool("write", RiskTier.MEDIUM, "written");
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"), write);
    var run = new Run();
    run.onEvent = event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval pending) {
        service.approveToolCall(run.id(), pending.callId());
      }
    };
    service.runAgent(request(history(), 4), run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    assertEquals(1, write.callCount.get());
    assertTrue(run.acks().isEmpty());
    @SuppressWarnings("unchecked")
    var savedMessages = (List<Map<String, Object>>) store.readSnapshot(run.id()).get("messages");
    assertEquals(savedMessages.subList(0, ai.sent().get(1).size()), ai.sent().get(1),
        "a valid unsteered history reaches the model exactly as saved");
    assertEquals(List.of("r1", "w1"), List.copyOf(toolResults(ai.sent().get(1)).keySet()));
  }

  @Test
  void unsteeredHandoffPrunesAndBriefsAsBefore() {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var ai = new CapturingAi(
        ScriptedResponse.toolCall("s1", "core_search", "{\"q\":\"x\"}"),
        ScriptedResponse.toolCall("h1", "handoff_to_executor", "{\"reason\":\"go\"}"),
        ScriptedResponse.textOnly("done"));
    var service = service(ai, store, new StubTool("search", RiskTier.LOW, "result"));
    var run = new Run();
    service.runAgent(new AgentRequest(history(), List.of(), 6, profiles("planner", "executor"), "planner"),
        run, EngineContextTestFixtures.AGENT_LOOP);

    assertDone(run.events);
    var afterHandoff = ai.sent().getLast();
    assertEquals(4, afterHandoff.size());
    assertEquals("executor prompt\n\nResearch findings:\n- core_search: {\"q\":\"x\"}\n  → result"
        + "\n\nHandoff from planner to executor. Reason: go Any write or destructive actions must be"
        + " re-approved.", system(afterHandoff));
    assertEquals(List.of("system", "user", "assistant", "tool"),
        afterHandoff.stream().map(m -> m.get("role")).toList());
    @SuppressWarnings("unchecked")
    var savedMessages = (List<Map<String, Object>>) store.readSnapshot(run.id()).get("messages");
    assertEquals(afterHandoff, savedMessages.subList(0, 4), "saved post-handoff history is unchanged");
  }

  // ---- helpers ----

  private static int indexOf(List<AgentEvent> events, java.util.function.Predicate<AgentEvent> match) {
    for (int i = 0; i < events.size(); i++) if (match.test(events.get(i))) return i;
    return -1;
  }

  private static AgentLoopService service(CapturingAi ai, AgentRunStore store, StubTool... tools) {
    return observed(new AgentLoopService(ai, stubCatalog(tools), stubExecutor(tools), stubEmitter(),
        null, null, store, null));
  }

  private static AgentRequest request(List<Map<String, Object>> messages, int iterations) {
    return new AgentRequest(messages, List.of(), iterations);
  }

  private static List<AgentProfile> profiles(String... ids) {
    return java.util.Arrays.stream(ids)
        .map(id -> new AgentProfile(id, id, id + " prompt", List.of())).toList();
  }

  /** An interactive Standard run's budget on the fake's 4096-token window. */
  private static int budgetFor4096() {
    return AgentBudgetPolicy.initialBudget(null, 4096, false);
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
