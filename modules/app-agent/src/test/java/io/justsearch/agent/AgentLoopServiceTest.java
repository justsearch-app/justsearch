package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("unchecked")
class AgentLoopServiceTest {
  @TempDir Path tempDir;

  // ---------------------------------------------------------------------------
  // Text-only response
  // ---------------------------------------------------------------------------

  @Test
  void textOnlyResponse_emitsAgentDone() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Hello there")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "result"));

    var events = run(service, userMessage("hi"), 1);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    assertEquals("Hello there", done.finalResponse());
    assertEquals(1, done.iterationsUsed());
    assertEquals(0, done.toolCallsExecuted());
    // Agent loop must use AGENT sampling (balanced temp for tool calling + multi-step reasoning).
    // Lane F PR 0b: this doubles as the absent-override pin — no `sampling` field on the request
    // must be byte-identical to the constant, not merely "close to" it.
    assertEquals(List.of(SamplingParams.AGENT), ai.recordedSampling);
  }

  // ---------------------------------------------------------------------------
  // Lane F PR 0b — the request's optional sampling override
  // ---------------------------------------------------------------------------

  /** An AgentRequest carrying a sampling override, single-agent, everything else defaulted. */
  private static AgentRequest requestWithSampling(
      List<Map<String, Object>> messages,
      int maxIterations,
      List<AgentProfile> profiles,
      String initialAgentId,
      AgentRequest.SamplingOverride sampling) {
    return new AgentRequest(
        messages, List.of(), maxIterations, profiles, initialAgentId,
        null, null, null, List.of(), null, false, sampling);
  }

  @Test
  @DisplayName("PR 0b: the request's sampling override reaches the ordinary agent turn")
  void samplingOverride_appliedToTheOrdinaryTurn() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Hello there")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "result"));

    runWithRequest(
        service,
        requestWithSampling(
            userMessage("hi"), 1, List.of(), null,
            new AgentRequest.SamplingOverride(0.0, 0.5, 20260907L)));

    assertEquals(1, ai.recordedSampling.size());
    var used = ai.recordedSampling.get(0);
    assertEquals(0.0, used.temperature(), 1e-9, "override temperature must reach the LLM call");
    assertEquals(0.5, used.topP(), 1e-9, "override top_p must reach the LLM call");
    assertEquals(20260907L, used.seed(), "override seed must reach the LLM call");
  }

  @Test
  @DisplayName("PR 0b: a partial override keeps the AGENT preset's other knobs")
  void samplingOverride_partialKeepsThePresetsOtherKnobs() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    runWithRequest(
        service,
        requestWithSampling(
            userMessage("hi"), 1, List.of(), null,
            new AgentRequest.SamplingOverride(null, null, 7L)));

    var used = ai.recordedSampling.get(0);
    assertEquals(SamplingParams.AGENT.temperature(), used.temperature(), 1e-9);
    assertEquals(SamplingParams.AGENT.topP(), used.topP(), 1e-9);
    assertEquals(7L, used.seed());
  }

  /**
   * PR 0b — the override must reach the two turns whose sampling {@code AgentStepRunner} builds
   * INLINE from {@code SamplingParams.AGENT}, not just the ones that go through {@code
   * AgentLlmCaller.resolveAgentSampling}.
   *
   * <p>This run exercises the Direction F escalation retry (the inline site at the PRIMARY
   * text-only handoff) alongside two ordinary PRIMARY turns and the Organizer's E0a forced turn.
   * A version that threaded the override through {@code AgentLlmCaller} only would pass every
   * other test in this file and still leave calls 2 and 3 unpinned — a capture would report a
   * pinned seed while the forced turns kept drifting, which is exactly the drift PR 0b exists to
   * remove.
   */
  @Test
  @DisplayName("PR 0b: the override reaches the escalation retry and the E0a forced turn")
  void samplingOverride_appliedToTheEscalationAndE0aForcedTurns() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("sc-1", "core_search_index", "{\"query\":\"architecture\"}"),
        ScriptedResponse.textOnly("Here are the top candidates: 01-system-overview.md"),
        ScriptedResponse.toolCall(
            "hc-1", "handoff_to_organizer", "{\"reason\":\"01-system-overview.md\"}"),
        ScriptedResponse.toolCall(
            "tc-1", "core_ingest_files", "{\"paths\":[\"01-system-overview.md\"]}"),
        ScriptedResponse.textOnly("Ingested 01-system-overview.md."));
    var service = buildService(
        ai,
        new StubTool("search_index", RiskTier.LOW, "{\"hits\":[]}"),
        new StubTool("ingest_files", RiskTier.LOW, "ok"));

    runWithRequest(
        service,
        requestWithSampling(
            userMessage("Find and ingest the architecture overview"), 10, profiles, "primary",
            new AgentRequest.SamplingOverride(0.0, null, 4242L)));

    assertEquals(5, ai.recordedSampling.size(), "Expected 5 LLM calls");
    // Precondition: this run really did take the two forced-turn branches, or the assertion below
    // would be proving nothing about them.
    assertEquals("required", ai.recordedSampling.get(2).toolChoice(),
        "precondition: call 2 is the Direction F escalation retry (AgentStepRunner inline site)");
    assertEquals("required", ai.recordedSampling.get(3).toolChoice(),
        "precondition: call 3 is the Organizer's E0a forced turn");
    for (int i = 0; i < ai.recordedSampling.size(); i++) {
      var used = ai.recordedSampling.get(i);
      assertEquals(4242L, used.seed(), "call " + i + " must carry the override seed");
      assertEquals(0.0, used.temperature(), 1e-9,
          "call " + i + " must carry the override temperature");
      assertEquals(SamplingParams.AGENT.topP(), used.topP(), 1e-9,
          "call " + i + " keeps the preset top_p (the override left it absent)");
    }
  }

  /**
   * PR 0b — the step-ceiling finalize is the turn that produces the run's VISIBLE answer, and the
   * one place sampling is deliberately NOT resolved from the session (878 review B2: resolving it
   * there let a no-tools call inherit {@code tool_choice=required} plus the tool-call grammar and
   * stream a raw {@code <tool_call>} blob out as the answer). It must still honour the run's
   * sampling override, and must still not become tool-forced — both halves asserted here, because
   * a fix for either one that broke the other would look green in the other's test.
   */
  @Test
  @DisplayName("PR 0b: the ceiling finalize carries the override but stays unconstrained")
  void samplingOverride_appliedToTheStepCeilingFinalize() {
    var session = new AgentSession(new ArrayList<>(userMessage("q")), 8000);
    session.recordHandoff("primary", "organizer", "delegating the ingest");
    assertTrue(
        AgentTurnPolicy.shouldForceToolCall(session),
        "precondition: this session IS in the forced-tool state, or the test proves nothing");
    session.setSamplingOverride(new AgentRequest.SamplingOverride(0.0, null, 555L));

    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Partial answer.")));
    new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(true, 200, 1))
        .attemptStepCeilingFinalize(session, event -> {});

    assertEquals(1, ai.recordedSampling.size(), "the finalize made exactly one call");
    var used = ai.recordedSampling.get(0);
    assertEquals(555L, used.seed(), "the finalize must carry the run's override seed");
    assertEquals(0.0, used.temperature(), 1e-9, "and the run's override temperature");
    assertNull(used.grammar(), "878 B2 must still hold: the finalize is never grammar-constrained");
    assertNull(used.toolChoice(), "878 B2 must still hold: the finalize is never tool-forced");
  }

  /**
   * PR 0b — the other inline {@code AgentStepRunner} site: the DECIDING commit turn. Same scenario
   * as {@code budgetExhausted_decidingBypassesFinalizeAndProceedsToLlmCall}, which is the one setup
   * in this file that reaches {@code AgentState.DECIDING}.
   */
  @Test
  @DisplayName("PR 0b: the override reaches the DECIDING commit turn")
  void samplingOverride_appliedToTheDecidingTurn() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("sc-1", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(45, 5),
        ScriptedResponse.toolCall("sc-2", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(55, 5),
        ScriptedResponse.toolCall("sc-3", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(95, 5),
        ScriptedResponse.toolCall("hc-1", "handoff_to_organizer", "{\"reason\":\"doc.md\"}"),
        ScriptedResponse.toolCall("ic-1", "core_ingest_files", "{\"paths\":[\"doc.md\"]}"),
        ScriptedResponse.textOnly("Done."));
    var service = buildServiceWithSmallBudgetAndProfiles(ai, 500);

    runWithRequest(
        service,
        requestWithSampling(
            userMessage("Ingest doc.md"), 10, profiles, "primary",
            new AgentRequest.SamplingOverride(null, null, 99L)));

    assertEquals(6, ai.recordedSampling.size(), "Expected 6 LLM calls");
    assertEquals("required", ai.recordedSampling.get(3).toolChoice(),
        "precondition: call 3 is the DECIDING commit turn (AgentStepRunner inline site)");
    assertEquals(99L, ai.recordedSampling.get(3).seed(),
        "the DECIDING turn must carry the override seed");
    for (var used : ai.recordedSampling) {
      assertEquals(99L, used.seed(), "every turn in the run must carry the override seed");
    }
  }

  // ---------------------------------------------------------------------------
  // Slice 447 §X.11.5 Phase 5 + 447-followup-live-wiring §X.12.8 Item 1.3 —
  // agent retrospection: when setConditionContextSupplier wires a non-null
  // supplier, AgentLoopService prepends its output to the system prompt.
  // This is the integration test §X.12 noted was missing — the unit test on
  // renderConditionContext covered the rendering side; this one covers the
  // wiring through to the actual LLM message stream.
  // ---------------------------------------------------------------------------

  @Test
  void conditionContextSupplier_appendedToSystemPrompt() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    service.setConditionContextSupplier(
        () -> "Currently asserted conditions: schema.reindex-required → core.reindex");

    run(service, userMessage("hi"), 1);

    assertEquals(1, ai.recordedMessages.size(), "LLM should be called once");
    List<Map<String, Object>> firstCall = ai.recordedMessages.get(0);
    var systemMessage = firstCall.get(0);
    assertEquals("system", systemMessage.get("role"), "first message should be system");
    String content = String.valueOf(systemMessage.get("content"));
    assertTrue(
        content.contains(
            "Currently asserted conditions: schema.reindex-required → core.reindex"),
        "system prompt should include the condition context the supplier returned;"
            + " actual content: " + content);
  }

  @Test
  void conditionContextSupplier_nullSuppressesAppend() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    // Default state: no supplier wired; baseline behavior preserved.

    run(service, userMessage("hi"), 1);

    String content = String.valueOf(ai.recordedMessages.get(0).get(0).get("content"));
    assertFalse(
        content.contains("Currently asserted conditions:"),
        "no supplier wired → no condition section appended");
  }

  @Test
  void conditionContextSupplier_emptyStringSuppressesAppend() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    // Empty/blank result short-circuits the append branch in
    // appendConditionContext (the empty-store renderConditionContext result).
    service.setConditionContextSupplier(() -> "");

    run(service, userMessage("hi"), 1);

    String content = String.valueOf(ai.recordedMessages.get(0).get(0).get("content"));
    // Compare against the baseline-with-no-supplier text (a control run would
    // be redundant). Sufficient to assert no condition-context marker leaked in.
    assertFalse(
        content.contains("Currently asserted conditions:"),
        "blank supplier output → no condition section appended");
  }

  @Test
  void conditionContextSupplier_throwingSupplierFailsClosed() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    // Per AgentLoopService.appendConditionContext, supplier exceptions are
    // logged and swallowed; the agent loop never breaks because retrospection
    // degraded.
    service.setConditionContextSupplier(
        () -> {
          throw new RuntimeException("synthetic supplier failure");
        });

    var events = run(service, userMessage("hi"), 1);
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "agent should still complete despite supplier throwing");
  }

  @Test
  void emittedEvents_includeTraceIdentityEnvelope() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Hello there")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "result"));

    var events = run(service, userMessage("trace test"), 1);

    assertFalse(events.isEmpty(), "Should emit at least session/progress/done events");
    String runId = null;
    String previousSpanId = null;
    for (int i = 0; i < events.size(); i++) {
      AgentEvent event = events.get(i);
      assertNotNull(event.trace(), "Every event should include trace context");
      assertTrue(event.trace().hasIdentity(), "Trace context should carry identity fields");
      assertNotNull(event.trace().runId(), "runId should be present");
      assertNotNull(event.trace().spanId(), "spanId should be present");
      assertNotNull(event.trace().stepId(), "stepId should be present");
      assertTrue(event.trace().iteration() >= 0, "iteration should be non-negative");
      assertEquals("primary", event.trace().agentId(), "agentId should be stable");
      if (runId == null) {
        runId = event.trace().runId();
      } else {
        assertEquals(runId, event.trace().runId(), "runId should be stable across events");
      }
      // Span chain integrity: each event's parentSpanId = previous event's spanId
      assertEquals(previousSpanId, event.trace().parentSpanId(),
          "event[" + i + "] parentSpanId should equal previous spanId");
      previousSpanId = event.trace().spanId();
    }
  }

  // ---------------------------------------------------------------------------
  // Telemetry counters fire during empty-response retry path
  // ---------------------------------------------------------------------------

  @Test
  void telemetryCounters_incrementOnEmptyResponseRetry() {
    // Both initial and retry produce empty responses -> triggers retry + exhausted counters.
    // Tempdoc 417 Phase 2d: AgentTelemetry now wraps typed catalogs; assertions go through
    // TestMetricRegistry instead of a legacy Telemetry mock.
    try (var agentRegistry =
            new io.justsearch.telemetry.catalog.TestMetricRegistry(AgentMetricCatalog.DEFINITIONS);
        var genAiRegistry =
            new io.justsearch.telemetry.catalog.TestMetricRegistry(
                GenAiMetricCatalog.DEFINITIONS)) {
      var agentTelemetry =
          new AgentTelemetry(
              new AgentMetricCatalog(agentRegistry), new GenAiMetricCatalog(genAiRegistry));
      var ai = new ScriptedAiService(List.of(ScriptedResponse.empty(), ScriptedResponse.empty()));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      run(service, userMessage("trigger empty retry"), 3);

      assertTrue(
          agentRegistry.counterValue(
                  AgentMetricCatalog.RETRY_TOTAL,
                  new AgentTags.AgentRetryTags(
                      AgentErrorCode.EMPTY_RESPONSE, "1"))
              >= 1,
          "Should record retry on first empty response");
      assertTrue(
          agentRegistry.counterValue(
                  AgentMetricCatalog.RETRY_EXHAUSTED_TOTAL,
                  new AgentTags.AgentRetryExhaustedTags(
                      AgentErrorCode.EMPTY_RESPONSE))
              >= 1,
          "Should record retry exhausted after max retries");
      assertTrue(
          agentRegistry.counterValue(
                  AgentMetricCatalog.ERROR_TOTAL,
                  new AgentTags.AgentErrorTags(
                      AgentErrorCode.EMPTY_RESPONSE,
                      io.justsearch.agent.api.AgentErrorClass.TRANSIENT))
              >= 1,
          "Should record error counter on terminal empty response");
    }
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 415 F3c: per-disposition session-lifecycle tests
  // ---------------------------------------------------------------------------

  /** Builds a single combined registry holding both Agent + GenAi catalog DEFINITIONS. */
  private static io.justsearch.telemetry.catalog.TestMetricRegistry combinedRegistry() {
    var defs = new ArrayList<>(AgentMetricCatalog.DEFINITIONS);
    defs.addAll(GenAiMetricCatalog.DEFINITIONS);
    return new io.justsearch.telemetry.catalog.TestMetricRegistry(defs);
  }

  @Test
  void sessionEnd_completed_emitsTerminateTotalCompleted() {
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Hello there")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      run(service, userMessage("hi"), 1);

      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_START_TOTAL,
              io.justsearch.telemetry.catalog.EmptyTags.INSTANCE));
      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
              new AgentTags.SessionEndedTags(TerminalDisposition.COMPLETED, null, null)));
      assertEquals(
          1L,
          registry.histogramCount(
              AgentMetricCatalog.SESSION_DURATION_MS,
              io.justsearch.telemetry.catalog.EmptyTags.INSTANCE));
    }
  }

  @Test
  void sessionEnd_maxIterations_emitsTerminateTotalMaxIterations() {
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      // Model always returns tool calls — never produces a final text response.
      var ai = new ScriptedAiService(List.of(
          ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
          ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      run(service, userMessage("loop"), 2);

      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
              new AgentTags.SessionEndedTags(TerminalDisposition.MAX_ITERATIONS, null, null)));
    }
  }

  @Test
  void markTerminated_isFirstWins_soACatchPathReMarkCannotCorruptDisposition() {
    // Tempdoc 415 latent risk L2 / F1 ("state-first, durability-second"): at every terminal return
    // site runAgent calls session.markTerminated(...) BEFORE checkpoint(...). AgentRunStore
    // .updateCheckpoint currently catches all exceptions internally, so checkpoint() never throws and
    // runAgent's catch block (which re-marks ERRORED/INTERNAL_ERROR) is never reached via a checkpoint
    // failure. The ONLY thing that would keep the real disposition intact if a future change un-caught
    // updateCheckpoint — letting a throwing terminal checkpoint fall into that catch — is
    // markTerminated's first-wins guard. Pin it so the F1 ordering stays protective by construction.
    var session = new AgentSession(List.of(), 1000);
    session.markTerminated(TerminalDisposition.MAX_ITERATIONS, null, null);
    // Simulate the catch-path re-mark a throwing terminal checkpoint would trigger.
    session.markTerminated(
        TerminalDisposition.ERRORED, AgentErrorCode.INTERNAL_ERROR, null);
    assertEquals(
        TerminalDisposition.MAX_ITERATIONS,
        session.disposition(),
        "first disposition wins; the catch-path ERRORED re-mark must not overwrite it");
    assertNull(
        session.terminationCode(), "the first mark carried no error code; it must be preserved");
  }

  @Test
  void toolTurn_emitsToolBatchProposed_beforePerCallProposed() {
    // Tempdoc 550 N1: the runner announces the turn's tool-call batch ONCE, before the per-call
    // gate/proposed events — a heads-up plan preview. (Single-call scripted responses → size-1
    // batches; the ordering + emission are what matter.)
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      var ai =
          new ScriptedAiService(
              List.of(
                  ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                  ScriptedResponse.textOnly("done")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      List<AgentEvent> events = run(service, userMessage("go"), 3);

      int batchIdx = -1;
      int proposedIdx = -1;
      for (int i = 0; i < events.size(); i++) {
        if (batchIdx < 0 && events.get(i) instanceof AgentEvent.ToolBatchProposed) {
          batchIdx = i;
        }
        if (proposedIdx < 0 && events.get(i) instanceof AgentEvent.ToolCallProposed) {
          proposedIdx = i;
        }
      }
      assertTrue(batchIdx >= 0, "a ToolBatchProposed is emitted for the tool turn");
      assertTrue(
          proposedIdx >= 0 && batchIdx < proposedIdx,
          "the batch is announced before the first per-call proposed event");
      var batch = (AgentEvent.ToolBatchProposed) events.get(batchIdx);
      assertEquals(1, batch.calls().size(), "the batch carries the turn's call");
      assertEquals("core_search", batch.calls().get(0).toolName());
    }
  }

  // ---------------------------------------------------------------------------
  // Tempdoc S7 — docIds scope (FE "scope chips") threaded onto the search tool call
  // ---------------------------------------------------------------------------

  @Test
  void docIdsScope_mergesIntoSearchToolArguments() {
    // AgentRequest.docIds() carries the FE's scope-chip selection; when non-empty,
    // AgentToolDispatcher.scopeToolCall must merge it into the search tool's OWN call
    // arguments — regardless of what the LLM's tool call itself asked for — so the
    // dispatched handler receives a "docIds" array alongside the LLM's "query".
    var searchTool = new StubTool("search_index", RiskTier.LOW, "r");
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search_index", "{\"query\":\"taxes\"}"),
        ScriptedResponse.textOnly("done")));
    var service = buildService(ai, searchTool);

    var request = new AgentRequest(
        userMessage("find taxes"),
        List.of(),
        3,
        List.of(),
        null,
        null,
        null,
        null,
        List.of("/docs/taxes.md", "/docs/invoices.md"));

    runWithRequest(service, request);

    assertNotNull(searchTool.lastArgs, "the search tool should have been invoked");
    assertTrue(
        searchTool.lastArgs.contains("\"docIds\""),
        "docIds scope should be merged into the search tool's arguments; got: "
            + searchTool.lastArgs);
    assertTrue(searchTool.lastArgs.contains("/docs/taxes.md"));
    assertTrue(searchTool.lastArgs.contains("/docs/invoices.md"));
    // The LLM's own argument must survive the merge unchanged.
    assertTrue(searchTool.lastArgs.contains("\"query\":\"taxes\""));
  }

  @Test
  void docIdsScope_absent_leavesSearchToolArgumentsUnscoped() {
    // Absent-case: no docIds on the request (the default/back-compat AgentRequest shape) means
    // every search call dispatches with the LLM's own arguments, unmodified — the pre-S7 behavior.
    var searchTool = new StubTool("search_index", RiskTier.LOW, "r");
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search_index", "{\"query\":\"taxes\"}"),
        ScriptedResponse.textOnly("done")));
    var service = buildService(ai, searchTool);

    runWithRequest(service, new AgentRequest(userMessage("find taxes"), List.of(), 3));

    assertNotNull(searchTool.lastArgs, "the search tool should have been invoked");
    assertEquals("{\"query\":\"taxes\"}", searchTool.lastArgs, "unscoped run: arguments pass through verbatim");
  }

  @Test
  void docIdsScope_doesNotLeakIntoUnrelatedToolCalls() {
    // The scope is gated on the search operation id — a docIds-scoped run must NOT inject the
    // filter into a different tool's arguments.
    var otherTool = new StubTool("other_tool", RiskTier.LOW, "r");
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_other_tool", "{\"x\":1}"),
        ScriptedResponse.textOnly("done")));
    var service = buildService(ai, otherTool);

    var request = new AgentRequest(
        userMessage("do the other thing"),
        List.of(),
        3,
        List.of(),
        null,
        null,
        null,
        null,
        List.of("/docs/taxes.md"));

    runWithRequest(service, request);

    assertNotNull(otherTool.lastArgs);
    assertEquals("{\"x\":1}", otherTool.lastArgs, "docIds scope must not leak into a non-search tool call");
  }

  @Test
  void sessionEnd_unknownTool_emitsTerminateTotalErrored() {
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      // Model proposes a tool the registry doesn't know about.
      var ai = new ScriptedAiService(List.of(
          ScriptedResponse.toolCall("call_1", "nonexistent_tool", "{}")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      run(service, userMessage("call missing"), 2);

      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
              new AgentTags.SessionEndedTags(
                  TerminalDisposition.ERRORED,
                  AgentErrorCode.UNKNOWN_TOOL,
                  null)));
    }
  }

  @Test
  void sessionEnd_emptyResponseRetryExhausted_emitsTerminateTotalErrored() {
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      // Both initial + retry produce empty → terminal disposition is ERRORED/EMPTY_RESPONSE.
      var ai = new ScriptedAiService(List.of(ScriptedResponse.empty(), ScriptedResponse.empty()));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      run(service, userMessage("empty retry exhausted"), 3);

      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
              new AgentTags.SessionEndedTags(
                  TerminalDisposition.ERRORED,
                  AgentErrorCode.EMPTY_RESPONSE,
                  null)));
    }
  }

  @Test
  void sessionEnd_userCancelledMidLoop_emitsTerminateTotalCancelled() {
    // Tempdoc 415 latent risk L1: this test cancels from INSIDE an event consumer running on the loop
    // thread and relies on cancel() setting a volatile flag that the NEXT iteration's synchronous
    // isCancelled check observes. It is correct only while event delivery stays single-threaded and
    // synchronous (the wrapEventConsumer → eventHub.publish fan-out is in-thread today). If a future
    // refactor moves event delivery off-thread, the cancel may race the loop and this test can go
    // flaky — at that point switch to a deterministic injection (cancel before run, or a latch).
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      // 1) tool call → executed → loop checks isCancelled at start of iteration 2.
      // 2) follow-up tool call (never reached because we cancel after the first ToolExecutionCompleted).
      var ai = new ScriptedAiService(List.of(
          ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
          ScriptedResponse.textOnly("never reached")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));

      // Capture sessionId from SessionStarted; cancel after the first ToolExecutionCompleted.
      var capturedSessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      Consumer<AgentEvent> sink = event -> {
        if (event instanceof AgentEvent.SessionStarted s) {
          capturedSessionId.set(s.sessionId());
        }
        if (event instanceof AgentEvent.ToolExecutionCompleted) {
          var sid = capturedSessionId.get();
          if (sid != null) {
            service.cancelSession(sid);
          }
        }
      };
      service.runAgent(new AgentRequest(userMessage("cancel me"), List.of(), 5), sink);

      assertEquals(
          1L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TERMINATE_TOTAL,
              new AgentTags.SessionEndedTags(
                  TerminalDisposition.CANCELLED, null, CancelTrigger.USER)));
    }
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 550 Tier-0 (T0-a): the human safety gate strictly precedes tool
  // dispatch. The in-process consent-capsule mint lives inside
  // dispatchToolCall, which is reached only AFTER handleSafetyGate returns
  // true (AgentStepRunner: the `if (!approved) continue;` guard). These two
  // tests pin that ordering: a rejected MEDIUM gate must mean the operation is
  // NEVER dispatched (callCount stays 0), and an approved gate dispatches it
  // exactly once. A refactor that moved the mint/execute ahead of the gate —
  // re-opening the "AI self-approves" hole — would flip callCount and fail.
  // ---------------------------------------------------------------------------

  @Test
  void safetyGate_rejectedMediumRiskCall_isNeverDispatched() throws Exception {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_danger", "{}"),
        ScriptedResponse.textOnly("acknowledged rejection")));
    var dangerTool = new StubTool("danger", RiskTier.MEDIUM, "should-not-run");
    var service = buildService(ai, dangerTool);

    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var rejected = new java.util.concurrent.atomic.AtomicBoolean(false);
    var pendingSeen = new java.util.concurrent.CountDownLatch(1);
    var loopDone = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> sink = event -> {
      if (event instanceof AgentEvent.SessionStarted s) sessionId.set(s.sessionId());
      if (event instanceof AgentEvent.ToolCallPendingApproval) pendingSeen.countDown();
      if (event instanceof AgentEvent.ToolCallRejected) rejected.set(true);
      if (event instanceof AgentEvent.AgentDone || event instanceof AgentEvent.AgentError) {
        loopDone.complete(true);
      }
    };

    var loopThread = new Thread(() -> service.runAgent(
        new AgentRequest(userMessage("do the dangerous thing"), List.of(), 3), sink));
    loopThread.setDaemon(true);
    loopThread.start();

    // The approval gate is created immediately AFTER ToolCallPendingApproval is emitted, then
    // the loop thread blocks on it. Poll-reject (a no-op until the gate exists) until the loop
    // unblocks — bounded state-polling on loopDone, not a fixed-duration coordination sleep.
    assertTrue(pendingSeen.await(5, java.util.concurrent.TimeUnit.SECONDS),
        "MEDIUM-risk call must raise a human approval gate");
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!loopDone.isDone() && System.nanoTime() < deadlineNanos) {
      service.rejectToolCall(sessionId.get(), "call_1", "test-reject");
      try {
        loopDone.get(20, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException ignored) {
        // gate not yet completed — re-attempt
      }
    }
    loopThread.join(2000);

    assertTrue(loopDone.isDone(), "loop should unblock once the gate is rejected");
    assertTrue(rejected.get(), "a rejected gate must emit ToolCallRejected");
    assertEquals(0, dangerTool.callCount.get(),
        "a rejected safety gate must mean the operation is NEVER dispatched"
            + " (T0-a: the human gate strictly precedes the capsule mint / execute)");
  }

  @Test
  void safetyGate_backgroundRun_rejectsMediumRiskCallFast_withoutWaitingForAWatcher()
      throws Exception {
    // Tempdoc 561 P-D: a BACKGROUND (non-interactive) run is safe-by-default. A MEDIUM/HIGH op has no
    // watcher to approve it, so the gate rejects it IMMEDIATELY — the run completes on its own, with NO
    // ToolCallPendingApproval and without blocking for the (5-min) approval timeout an interactive run
    // would. Contrast safetyGate_rejectedMediumRiskCall_isNeverDispatched, which needs a manual reject.
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_danger", "{}"),
        ScriptedResponse.textOnly("acknowledged")));
    var dangerTool = new StubTool("danger", RiskTier.MEDIUM, "should-not-run");
    var service = buildService(ai, dangerTool);

    var rejected = new java.util.concurrent.atomic.AtomicBoolean(false);
    var pendingSeen = new java.util.concurrent.atomic.AtomicBoolean(false);
    var loopDone = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> sink = event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval) pendingSeen.set(true);
      if (event instanceof AgentEvent.ToolCallRejected) rejected.set(true);
      if (event instanceof AgentEvent.AgentDone || event instanceof AgentEvent.AgentError) {
        loopDone.complete(true);
      }
    };

    // background=true. On a daemon thread so a regression (blocking on a non-existent approver) bounds
    // to the assert timeout instead of hanging the suite for the full approval timeout.
    var loopThread = new Thread(() -> service.runAgent(
        new AgentRequest(userMessage("do the dangerous thing"), List.of(), 3), sink, true));
    loopThread.setDaemon(true);
    loopThread.start();

    assertTrue(
        loopDone.get(8, java.util.concurrent.TimeUnit.SECONDS),
        "a background run must complete WITHOUT a watcher — fail-fast, not a 5-min approval-timeout wait");
    loopThread.join(2000);
    assertTrue(rejected.get(), "the MEDIUM op is rejected (safe-by-default for an unwatched run)");
    assertFalse(pendingSeen.get(), "a background run must NOT raise a human approval gate (no watcher)");
    assertEquals(0, dangerTool.callCount.get(), "the rejected MEDIUM op is never dispatched");
  }

  @Test
  void attachToRun_replaysHistoryThenStreamsToASecondObserverUntilTerminal() throws Exception {
    // Tempdoc 577 §2.14 Root I (#13) — the run is an OBSERVED entity: a SECOND observer attaches
    // mid-run, REPLAYS the run's history (SessionStarted, the pending approval), then receives the
    // ongoing events through to the terminal — exactly what a reattaching tab does. The original
    // observer is unaffected.
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_danger", "{}"),
        ScriptedResponse.textOnly("done")));
    var dangerTool = new StubTool("danger", RiskTier.MEDIUM, "ran");
    var service = buildService(ai, dangerTool);

    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var pendingSeen = new java.util.concurrent.CountDownLatch(1);
    var loopDone = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> primary = event -> {
      if (event instanceof AgentEvent.SessionStarted s) sessionId.set(s.sessionId());
      if (event instanceof AgentEvent.ToolCallPendingApproval) pendingSeen.countDown();
      if (event instanceof AgentEvent.AgentDone || event instanceof AgentEvent.AgentError) {
        loopDone.complete(true);
      }
    };

    var loopThread = new Thread(() -> service.runAgent(
        new AgentRequest(userMessage("do the thing"), List.of(), 3), primary));
    loopThread.setDaemon(true);
    loopThread.start();
    assertTrue(pendingSeen.await(5, java.util.concurrent.TimeUnit.SECONDS), "run is live + parked");

    // A second observer attaches to the LIVE run on its own thread (the attach blocks until terminal).
    // Tempdoc 834 §1.3.2 — the journal carries the WIRE-projected (name, payload) pair, not typed
    // events, so this observer asserts on event NAMES. That is what lets the native attach write
    // frames straight to the socket and the AG-UI attach re-project them, with no second vocabulary.
    var attachEvents =
        new CopyOnWriteArrayList<io.justsearch.agent.api.RunObservation.WireFrame>();
    var attachReturned = new CompletableFuture<Boolean>();
    var attachThread = new Thread(() ->
        attachReturned.complete(service.attachToRun(sessionId.get(), attachEvents::add)));
    attachThread.setDaemon(true);
    attachThread.start();

    // The replay must deliver the already-emitted history to the late observer (poll — the attach
    // runs on another thread). It must include the SessionStarted + the pending approval.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (attachEvents.stream().noneMatch(e -> "tool_call_pending".equals(e.name()))
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(
        attachEvents.stream().anyMatch(e -> "state_snapshot".equals(e.name())),
        "the attach received the act-on-the-run primer BEFORE the replay (§6.1)");
    assertEquals(
        "state_snapshot",
        attachEvents.get(0).name(),
        "and the primer is FIRST — the ring evicts oldest, so it cannot sit behind the replay");
    // Tempdoc 834 §15.1.4 — ALL EIGHT StateSnapshot components must survive the move onto the
    // channel. Dropping back to the pre-S1 five would silently undo S1's whole point (§6.1): a
    // reattacher would see a stopped run with no gate to answer, which is exactly the failure the
    // snapshot-not-ring recovery law exists to prevent.
    Map<String, Object> primer = attachEvents.get(0).payload();
    for (String component :
        List.of(
            "iteration",
            "budgetRemaining",
            "toolCallsExecuted",
            "messageCount",
            "activeAgentId",
            "pendingApprovals",
            "autonomyLevel",
            "park")) {
      assertTrue(
          primer.containsKey(component),
          "the primer dropped the '" + component + "' component: " + primer);
    }
    assertFalse(
        ((List<?>) primer.get("pendingApprovals")).isEmpty(),
        "and the held gate is ACTIONABLE from the primer alone — it carries the pending approval");
    assertTrue(
        attachEvents.stream().anyMatch(e -> "session_started".equals(e.name())),
        "the attach replayed the run's history (session_started)");
    assertTrue(
        attachEvents.stream().anyMatch(e -> "tool_call_pending".equals(e.name())),
        "the attach replayed the pending approval");

    // Drive the run to terminal; BOTH observers must receive the terminal and the attach must return.
    long rd = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!loopDone.isDone() && System.nanoTime() < rd) {
      service.rejectToolCall(sessionId.get(), "call_1", "test-reject");
      try {
        loopDone.get(20, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException ignored) {
        // gate not yet completed — re-attempt
      }
    }
    assertTrue(loopDone.isDone(), "the run terminated");
    assertEquals(
        Boolean.TRUE, attachReturned.get(5, java.util.concurrent.TimeUnit.SECONDS),
        "attachToRun returned true (a live run was observed) and unblocked on the terminal event");
    assertTrue(
        attachEvents.stream().anyMatch(e -> "done".equals(e.name()) || "error".equals(e.name())),
        "the attach observer received the terminal event");
  }

  @Test
  void attachToRun_returnsFalseForANonLiveSession() {
    // The reattach 404 path: an unknown / terminal / evicted session is not a live run, so the
    // controller falls back to the persisted record instead of holding an empty stream.
    var service = buildService(new ScriptedAiService(List.of(ScriptedResponse.textOnly("x"))));
    assertFalse(service.attachToRun("no-such-session", e -> {}));
  }

  @Test
  void zeroObserverPark_watchRunWithNoWatcher_parks_andAReattacherReplaysTheNarration()
      throws Exception {
    // Tempdoc 577 §2.14 Root I (#13) — the END-TO-END park (the gap the live verification found).
    // A WATCH run whose only observer's SSE write FAILS is evicted from the hub (exactly what the
    // AgentController eviction seam now triggers by THROWING on a disconnected write) ⇒
    // observerCount()==0 ⇒ the loop PARKS at the next iteration boundary and narrates
    // "run_unobserved_parked". A reattaching observer replays that buffered narration AND, by
    // restoring observerCount>0, releases the park so the run proceeds to terminal. Before the seam,
    // the dead observer lingered, observerCount never dropped, and the run proceeded UNWATCHED.
    String prev = System.getProperty("justsearch.agent.zeroObserverParkTimeoutSec");
    System.setProperty("justsearch.agent.zeroObserverParkTimeoutSec", "10"); // park HOLDS (test default is 0)
    try {
      var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("done")));
      var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

      var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      // A DEAD socket: it records the sessionId from SessionStarted, then THROWS on every delivery —
      // so the hub evicts it on the first event (mirrors AgentSseWriter.SseObserverGoneException on a
      // false write). After eviction the run has ZERO live observers.
      Consumer<AgentEvent> deadSocket = event -> {
        if (event instanceof AgentEvent.SessionStarted s) {
          sessionId.set(s.sessionId());
        }
        throw new RuntimeException("socket closed");
      };
      var loopThread = new Thread(() -> service.runAgent(
          new AgentRequest(userMessage("watch me"), List.of(), 3, List.of(), null, null, null, "watch"),
          deadSocket));
      loopThread.setDaemon(true);
      loopThread.start();

      long sd = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (sessionId.get() == null && System.nanoTime() < sd) {
        Thread.onSpinWait();
      }
      assertNotNull(sessionId.get(), "the run started (SessionStarted emitted before eviction)");

      // Let the loop reach the iteration-0 boundary with observerCount==0 (the dead socket evicted)
      // so it PARKS, before we attach. The park HOLDS for the 10s timeout, so this delay lands the
      // reattach safely inside the hold; attaching too early would race the park check and the
      // observerCount>0 reattacher would (correctly) keep the run PROCEEDing.
      Thread.sleep(700);

      // Reattach: replays the buffered history INCLUDING the park narration, and releases the park.
      var replay =
          new CopyOnWriteArrayList<io.justsearch.agent.api.RunObservation.WireFrame>();
      var attachReturned = new CompletableFuture<Boolean>();
      var attachThread = new Thread(() ->
          attachReturned.complete(service.attachToRun(sessionId.get(), replay::add)));
      attachThread.setDaemon(true);
      attachThread.start();

      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
      while (replay.stream().noneMatch(AgentLoopServiceTest::isUnobservedPark)
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(
          replay.stream().anyMatch(AgentLoopServiceTest::isUnobservedPark),
          "the WATCH run parked (observerCount==0 after the dead-socket eviction) and narrated "
              + "run_unobserved_parked; the reattacher replayed it. Saw: " + replay);
      assertEquals(Boolean.TRUE, attachReturned.get(8, java.util.concurrent.TimeUnit.SECONDS),
          "the reattach restored a watcher, released the park, and the run reached terminal");
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.agent.zeroObserverParkTimeoutSec");
      } else {
        System.setProperty("justsearch.agent.zeroObserverParkTimeoutSec", prev);
      }
    }
  }

  private static boolean isUnobservedPark(io.justsearch.agent.api.RunObservation.WireFrame frame) {
    return "progress".equals(frame.name())
        && "run_unobserved_parked".equals(frame.payload().get("phase"));
  }

  @Test
  void safetyGate_approvedMediumRiskCall_dispatchesExactlyOnce() throws Exception {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_danger", "{}"),
        ScriptedResponse.textOnly("done")));
    var dangerTool = new StubTool("danger", RiskTier.MEDIUM, "ran");
    var service = buildService(ai, dangerTool);

    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var approved = new java.util.concurrent.atomic.AtomicBoolean(false);
    var pendingSeen = new java.util.concurrent.CountDownLatch(1);
    var loopDone = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> sink = event -> {
      if (event instanceof AgentEvent.SessionStarted s) sessionId.set(s.sessionId());
      if (event instanceof AgentEvent.ToolCallPendingApproval) pendingSeen.countDown();
      if (event instanceof AgentEvent.ToolCallApproved) approved.set(true);
      if (event instanceof AgentEvent.AgentDone || event instanceof AgentEvent.AgentError) {
        loopDone.complete(true);
      }
    };

    var loopThread = new Thread(() -> service.runAgent(
        new AgentRequest(userMessage("do the dangerous thing"), List.of(), 3), sink));
    loopThread.setDaemon(true);
    loopThread.start();

    assertTrue(pendingSeen.await(5, java.util.concurrent.TimeUnit.SECONDS),
        "MEDIUM-risk call must raise a human approval gate");
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!loopDone.isDone() && System.nanoTime() < deadlineNanos) {
      service.approveToolCall(sessionId.get(), "call_1");
      try {
        loopDone.get(20, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException ignored) {
        // gate not yet completed — re-attempt
      }
    }
    loopThread.join(2000);

    assertTrue(loopDone.isDone(), "loop should unblock once the gate is approved");
    assertTrue(approved.get(), "an approved gate must emit ToolCallApproved");
    assertEquals(1, dangerTool.callCount.get(),
        "an approved safety gate dispatches the operation exactly once"
            + " (the gate is the sole determinant of whether the op runs)");
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 565 §30 — DIRECTION authority: a mid-run steering directive (interject) is drained at
  // the step boundary, folded into the next LLM call as a system note, and acknowledged.
  // ---------------------------------------------------------------------------

  @Test
  void steeringDirective_injectedMidRun_foldsIntoLlmContextAndAcknowledges() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search", "{}"),
        ScriptedResponse.textOnly("done")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "result"));

    var acknowledged = new java.util.concurrent.atomic.AtomicReference<String>();
    var done = new java.util.concurrent.atomic.AtomicBoolean(false);
    var ackedMidRun = new java.util.concurrent.atomic.AtomicBoolean(false);
    Consumer<AgentEvent> sink = event -> {
      // Inject synchronously on SessionStarted (the loop thread) so the directive is queued before a
      // step boundary's drain — deterministic, no race with the blocking loop.
      if (event instanceof AgentEvent.SessionStarted s) {
        assertTrue(service.injectSteeringDirective(s.sessionId(), "Focus only on Q3 results."),
            "injecting into a live session succeeds");
      }
      if (event instanceof AgentEvent.DirectiveAcknowledged d) {
        acknowledged.set(d.directiveText());
        if (!done.get()) ackedMidRun.set(true);
      }
      if (event instanceof AgentEvent.AgentDone) done.set(true);
    };

    service.runAgent(new AgentRequest(userMessage("hi"), List.of(), 3), sink);

    assertEquals("Focus only on Q3 results.", acknowledged.get(),
        "the injected directive is acknowledged at the step boundary");
    assertTrue(ackedMidRun.get(), "the directive is acknowledged MID-run, before AgentDone");
    assertTrue(
        ai.recordedMessages.stream().anyMatch(
            msgs -> msgs.stream().anyMatch(
                m -> String.valueOf(m.get("content")).contains("Focus only on Q3 results."))),
        "the steering text is folded into an LLM call's messages — it enters the agent's context");
  }

  @Test
  void steeringDirective_unknownSession_returnsFalse() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    assertFalse(service.injectSteeringDirective("no-such-session", "steer"),
        "injecting into an unknown/finished session returns false (404)");
  }

  @Test
  void agentSession_drainInterject_isExactlyOnce() {
    var session = new AgentSession(List.of(), 8000);
    assertNull(session.drainInterject(), "no directive queued initially");
    session.setInterject("steer me");
    assertEquals("steer me", session.drainInterject(), "drain returns the queued directive");
    assertNull(session.drainInterject(), "a drained directive is consumed exactly once");
    session.setInterject("   ");
    assertNull(session.drainInterject(), "blank directives are ignored");
  }

  // Tempdoc 565 §33 — a steer queued AFTER the last step boundary (here: during the final answer of a
  // single-LLM-call run, with no next iteration to drain at) is still recorded via the end-of-run drain.
  @Test
  void steeringDirective_afterLastBoundary_drainedAtRunEnd() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("the answer")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var injected = new java.util.concurrent.atomic.AtomicBoolean(false);
    var acked = new java.util.concurrent.atomic.AtomicReference<String>();
    Consumer<AgentEvent> sink = event -> {
      if (event instanceof AgentEvent.SessionStarted s) {
        sessionId.set(s.sessionId());
      }
      // Inject on the FIRST text chunk — this fires AFTER iteration 0's boundary drain (the drain ran at
      // the start of executeIteration, before the LLM call), and the run is single-iteration, so no later
      // boundary drains it. Only the end-of-run (finally) drain can catch it.
      if (event instanceof AgentEvent.TextChunk && !injected.get() && sessionId.get() != null) {
        injected.set(true);
        service.injectSteeringDirective(sessionId.get(), "too late to change the answer");
      }
      if (event instanceof AgentEvent.DirectiveAcknowledged d) {
        acked.set(d.directiveText());
      }
    };

    service.runAgent(new AgentRequest(userMessage("hi"), List.of(), 3), sink);

    assertTrue(injected.get(), "the directive was injected after iteration 0's boundary drain");
    assertEquals("too late to change the answer", acked.get(),
        "the end-of-run drain emits DirectiveAcknowledged so a late steer is recorded, not silently lost");
  }

  @Test
  void toolCallEmits_excludeHandoffNames() {
    try (var registry = combinedRegistry()) {
      var agentTelemetry =
          new AgentTelemetry(new AgentMetricCatalog(registry), new GenAiMetricCatalog(registry));
      // Multi-agent with two profiles; first model response is a handoff_to_secondary call.
      var profiles = List.of(
          new AgentProfile("primary", "Primary", null, List.of("core_search")),
          new AgentProfile("secondary", "Secondary", null, List.of("core_search")));
      var ai = new ScriptedAiService(List.of(
          ScriptedResponse.toolCall("call_1", "handoff_to_secondary", "{\"reason\":\"need help\"}"),
          ScriptedResponse.textOnly("done")));
      var service =
          buildServiceWithAgentTelemetry(
              ai, agentTelemetry, new StubTool("search", RiskTier.LOW, "r"));
      var request = new AgentRequest(
          List.of(Map.of("role", "user", "content", "go")),
          List.of(), 3, profiles, "primary");
      service.runAgent(request, e -> {});

      // Cardinality invariant: handoff_to_* tool names must not appear on tool_call_total.
      assertEquals(
          0L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TOOL_CALL_TOTAL,
              new AgentTags.ToolCallTags("handoff_to_secondary")));
      assertEquals(
          0L,
          registry.counterValue(
              AgentMetricCatalog.SESSION_TOOL_CALL_TOTAL,
              new AgentTags.ToolCallTags("handoff_to_primary")));
    }
  }

  // ---------------------------------------------------------------------------
  // Message ordering: assistant(tool_calls) before tool results
  // ---------------------------------------------------------------------------

  @Test
  void singleToolCall_correctMessageOrder() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search", "{\"query\":\"test\"}"),
        ScriptedResponse.textOnly("Found it")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "3 results"));

    run(service, userMessage("find docs"), 3);

    // Second LLM call should have correct message order
    assertEquals(2, ai.recordedMessages.size(), "LLM should be called twice");
    List<Map<String, Object>> secondCallMessages = ai.recordedMessages.get(1);

    // Expected: [system, user, assistant(tool_calls), tool_result]
    assertEquals("system", secondCallMessages.get(0).get("role"));
    assertEquals("user", secondCallMessages.get(1).get("role"));
    assertEquals("assistant", secondCallMessages.get(2).get("role"));
    assertNotNull(secondCallMessages.get(2).get("tool_calls"), "Assistant msg should have tool_calls");
    assertEquals("tool", secondCallMessages.get(3).get("role"));
    assertEquals("call_1", secondCallMessages.get(3).get("tool_call_id"));
  }

  // ---------------------------------------------------------------------------
  // Multi-step chain
  // ---------------------------------------------------------------------------

  @Test
  void multiStepChain_correctMessageOrder() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_browse", "{\"parent_path\":\"/\"}"),
        ScriptedResponse.toolCall("call_2", "core_search", "{\"query\":\"report\"}"),
        ScriptedResponse.textOnly("Done")));
    var service = buildService(ai,
        new StubTool("browse", RiskTier.LOW, "3 folders"),
        new StubTool("search", RiskTier.LOW, "5 results"));

    run(service, userMessage("organize"), 5);

    assertEquals(3, ai.recordedMessages.size(), "LLM should be called 3 times");

    // Third call messages: system, user, asst(call_1), tool(call_1), asst(call_2), tool(call_2)
    List<Map<String, Object>> thirdCall = ai.recordedMessages.get(2);
    assertEquals(6, thirdCall.size());
    assertEquals("system", thirdCall.get(0).get("role"));
    assertEquals("user", thirdCall.get(1).get("role"));
    assertEquals("assistant", thirdCall.get(2).get("role"));
    assertEquals("tool", thirdCall.get(3).get("role"));
    assertEquals("call_1", thirdCall.get(3).get("tool_call_id"));
    assertEquals("assistant", thirdCall.get(4).get("role"));
    assertEquals("tool", thirdCall.get(5).get("role"));
    assertEquals("call_2", thirdCall.get(5).get("tool_call_id"));
  }

  // ---------------------------------------------------------------------------
  // System prompt
  // ---------------------------------------------------------------------------

  @Test
  void systemPromptPrepended_whenMissing() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    run(service, userMessage("hi"), 1);

    List<Map<String, Object>> firstCall = ai.recordedMessages.get(0);
    assertEquals("system", firstCall.get(0).get("role"));
    assertEquals(AgentPromptComposer.DEFAULT_SYSTEM_PROMPT, firstCall.get(0).get("content"));
  }

  @Test
  void existingSystemPrompt_notDuplicated() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var messages = new ArrayList<Map<String, Object>>();
    messages.add(Map.of("role", "system", "content", "Custom system prompt"));
    messages.add(Map.of("role", "user", "content", "hi"));
    var request = new AgentRequest(messages, List.of(), 1);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add);

    List<Map<String, Object>> firstCall = ai.recordedMessages.get(0);
    assertEquals("system", firstCall.get(0).get("role"));
    assertEquals("Custom system prompt", firstCall.get(0).get("content"));
    // No second system message
    long systemCount = firstCall.stream()
        .filter(m -> "system".equals(m.get("role")))
        .count();
    assertEquals(1, systemCount, "Should not duplicate system prompt");
  }

  @Test
  void systemPromptIncludesRoots_whenSupplied() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            () -> List.of("D:\\Documents", "D:\\Projects"));

    run(service, userMessage("hi"), 1);

    List<Map<String, Object>> firstCall = ai.recordedMessages.get(0);
    String systemContent = (String) firstCall.get(0).get("content");
    assertTrue(
        systemContent.contains("D:\\Documents"),
        "System prompt should contain root path: " + systemContent);
    assertTrue(
        systemContent.contains("D:\\Projects"),
        "System prompt should contain root path: " + systemContent);
    assertTrue(
        systemContent.contains("indexed root folders"),
        "System prompt should label the roots: " + systemContent);
  }

  @Test
  void systemPromptFallsBack_whenRootsSupplierReturnsEmpty() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            List::of);

    run(service, userMessage("hi"), 1);

    List<Map<String, Object>> firstCall = ai.recordedMessages.get(0);
    assertEquals(AgentPromptComposer.DEFAULT_SYSTEM_PROMPT, firstCall.get(0).get("content"));
  }

  // ---------------------------------------------------------------------------
  // Approval gate lookup robustness (tempdoc 565 §15.J unified-dispatch enforcement)
  // ---------------------------------------------------------------------------

  @Test
  void tryApproveReject_withNullOrUnknownSession_returnFalse_noNpe() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        new AgentLoopService(
            ai, stubCatalog(searchTool), stubExecutor(searchTool), stubEmitter(), null, List::of);

    // The unified /api/chat/approve dispatch calls these to decide whether to fall through to the
    // workflow gate. A null/blank sessionId (a workflow-only approve) must report "no agent gate"
    // WITHOUT NPEing on ConcurrentHashMap.get(null) — surfaced by a live null-sessionId probe.
    assertFalse(service.tryApproveToolCall(null, "call-x"));
    assertFalse(service.tryRejectToolCall(null, "call-x", "reason"));
    assertFalse(service.tryApproveToolCall("", "call-x"));
    assertFalse(service.tryRejectToolCall("  ", "call-x", "reason"));
    // An unknown (non-null) session also reports no gate.
    assertFalse(service.tryApproveToolCall("no-such-session", "call-x"));
  }

  // ---------------------------------------------------------------------------
  // Max iterations
  // ---------------------------------------------------------------------------

  @Test
  void maxIterationsReached_emitsAgentDone() {
    // Model always returns tool calls — never stops voluntarily
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
        ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("loop"), 2);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone after max iterations");
    assertEquals(2, done.iterationsUsed());
    assertEquals(2, done.toolCallsExecuted());
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 865 §7.1 — evidence survives every terminal (the incremental mint)
  // ---------------------------------------------------------------------------

  /** A chunk-precise search hit, as {@code SearchTool.buildSearchEvidence} shapes it. */
  private static Map<String, Object> hit(String parentDocId) {
    return Map.of(
        "parentDocId", parentDocId,
        "chunkIndex", 0,
        "path", "/docs/" + parentDocId + ".md",
        "title", "Doc " + parentDocId,
        "excerpt", "an excerpt",
        "startLine", 1,
        "endLine", 5,
        "headingText", "");
  }

  private static Map<String, Object> searchEvidence(String... parentDocIds) {
    return Map.of("searchResults", Arrays.stream(parentDocIds).map(AgentLoopServiceTest::hit).toList());
  }

  /**
   * Every {@code parentDocId} the run's {@code tool_exec_completed} events carried under the
   * grounding key, in emission order — the FE's reconstruction, read off the same events it reads.
   */
  private static List<String> deltaDocIds(List<AgentEvent> events) {
    var out = new ArrayList<String>();
    for (AgentEvent e : events) {
      if (!(e instanceof AgentEvent.ToolExecutionCompleted c)) {
        continue;
      }
      Object raw = c.result().structuredData().get(OperationResult.GROUNDING_KEY);
      if (!(raw instanceof List<?> delta)) {
        continue;
      }
      for (Object item : delta) {
        out.add(String.valueOf(((Map<?, ?>) item).get("parentDocId")));
      }
    }
    return out;
  }

  /**
   * Tempdoc 865 §7.1 A5 — TERMINAL EQUIVALENCE, pinned against the {@code AgentDone} EVENT (§7.9
   * A9: asserting over the projected thread would make this sensitive to 863's plane changes and
   * start measuring the wrong thing).
   *
   * <p>Why this is the slice's one catastrophic failure mode: {@code AgentSentenceCite.sourceIndex}
   * is a POSITION into {@code done.sources()}, resolved on the FE as {@code sources[sourceIndex]}.
   * If the incremental order and the terminal order ever diverge, every inline mark points at the
   * wrong document — with no error anywhere. Both come from one ordered accumulator, so the property
   * holds BY CONSTRUCTION; this test exists precisely because a later refactor could re-derive
   * either side independently and nothing else would notice.
   *
   * <p>Tempdoc 878 §D.1 — this used to be SCOPED to two terminals, because the max-iterations
   * ceiling emitted through an ungrounded factory that could not carry sources. The ceiling now
   * finalizes through {@code groundedDone} like the other two, so the property covers all THREE
   * dispositions; {@link #maxIterationsTerminal_carriesTheEvidenceItEstablished} asserts it there.
   */
  @Test
  @DisplayName("865 §7.1 A5: on a COMPLETED terminal the concatenated deltas equal done.sources(), in order")
  void concatenatedDeltas_equalTheGroundedTerminalsSources() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}"),
                ScriptedResponse.textOnly("Here is the answer")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, "r")
                // Call 2 re-returns d1 and adds d3 — so the deltas can only match if the run-wide
                // dedup is applied incrementally, not per call.
                .returningStructuredData(
                    List.of(searchEvidence("d1", "d2"), searchEvidence("d1", "d3"))));

    var events = run(service, userMessage("two searches"), 4);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the run reaches a grounded terminal");
    assertEquals(
        TerminalDisposition.COMPLETED.name(),
        done.disposition(),
        "this fixture must exercise the COMPLETED terminal specifically");
    assertEquals(
        List.of("d1", "d2", "d3"),
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList());
    assertEquals(
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        deltaDocIds(events),
        "the concatenated per-call deltas ARE the terminal source list — same members, same order."
            + " A divergence here is a silently misdirected inline mark on every answer."
            + " (865 §7.5: the compared identity is the document, deliberately NOT the inclusion"
            + " axis — a delta is minted absent and the terminal resolves it, so the two SHOULD"
            + " differ there and equivalence is a claim about which sources, in which order.)");
  }

  /**
   * Tempdoc 865 §7.5 — the RUNNER's half of the inclusion producer, which nothing else can see.
   *
   * <p>{@code AgentSession} resolves inclusion from the receipt it holds, and {@code
   * AgentContextCompressor} produces the receipt — both unit-tested in {@code
   * AgentGroundingInclusionTest}. Between them sits one line per compression site: {@code
   * session.recordCompression(compressor.compressToolMessages(...))}. Delete the {@code
   * recordCompression} wrapper and every one of those unit tests still passes while the whole
   * feature silently reverts to "no badge" — which is also what the plane did before 865, so nobody
   * would notice. This runs the real loop and asserts the state reaches the terminal event.
   */
  @Test
  @DisplayName("865 §7.5: the loop records the compressor's receipt, so a terminal source can say its passage was never sent")
  void groundedTerminal_carriesTheInclusionResolvedAgainstTheFinalPrompt() {
    // A tool message shaped like SearchTool's output: indented `Excerpt:` lines (what Layer 3
    // strips) and long enough to clear the compressor's minimum-length floor.
    String message =
        "Found 1 result\n  /docs/d.md\n    Lines 1-5\n    Excerpt: \"the passage text, padded so"
            + " this message clears the compressor's minimum-length floor and is therefore a"
            + " message that compression will actually rewrite rather than skip over\"\n";
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}"),
                ScriptedResponse.textOnly("Here is the answer")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, message)
                .returningStructuredData(
                    List.of(searchEvidence("d1"), searchEvidence("d2"))));

    var events = run(service, userMessage("two searches"), 4);
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the run reaches a grounded terminal");
    assertEquals(2, done.sources().size());

    // The FIRST search's tool message is outside the compressor's keep-window under every
    // configured `keepLastResults`, so its source's passage is provably not in the final prompt.
    assertEquals(
        "dropped",
        done.sources().get(0).contextInclusion(),
        "the terminal reports what the compressor did to the prompt the answer was written from —"
            + " the input 849's `suppressGroundingFor` has been waiting for on this plane");
    assertEquals(0, done.sources().get(0).contextIncludedChars());
  }

  /**
   * Tempdoc 865 §7.1 / §3.8b, extended by 878 §D.1 — the sharpest unhappy terminal.
   *
   * <p>865 could only assert that the run's evidence survived on the TOOL events, because the
   * ceiling emitted {@code AgentDone.ofDisposition} — an ungrounded factory whose sources were a
   * hardcoded {@code List.of()}. It was exempt from terminal equivalence by construction: the
   * canonical constructor takes {@code List} arguments and {@code AgentGroundingSeamAuditTest}'s
   * discriminator is a {@code java.util.List} SIGNATURE SUBSTRING, so draining the accumulator there
   * would have tripped the grounding-seam audit for a reason unrelated to grounding.
   *
   * <p>878 §D.1 removed the exemption's cause rather than the exemption: the ceiling finalizes
   * through {@code AgentStepRunner.groundedDone}, so it carries the evidence like every other
   * terminal, and the factory is deleted. This test therefore asserts the STRONGER property — the
   * terminal's sources ARE the concatenated deltas — where it used to assert their absence.
   *
   * <p>The finalize call here fails (the script is exhausted), which is deliberate: it pins the
   * FAIL-OPEN half. An unanswerable ceiling still lands on an empty answer stamped {@code
   * MAX_ITERATIONS}, exactly the behaviour this replaced, and still carries its evidence.
   */
  @Test
  @DisplayName("878 §D.1: a MAX_ITERATIONS terminal carries the evidence it established (865 §7.1 equivalence, third disposition)")
  void maxIterationsTerminal_carriesTheEvidenceItEstablished() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, "r")
                .returningStructuredData(
                    List.of(searchEvidence("d1", "d2"), searchEvidence("d1", "d3"))));

    var events = run(service, userMessage("loop"), 2);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals(TerminalDisposition.MAX_ITERATIONS.name(), done.disposition());
    assertEquals(
        "",
        done.finalResponse(),
        "the finalize attempt failed (script exhausted), so the terminal is answerless — the"
            + " fail-open floor is byte-for-byte the pre-878 behaviour, never worse");
    assertEquals(
        List.of("d1", "d2", "d3"),
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        "878 §D.1: the ceiling terminal now CARRIES the run's evidence. RED BEFORE: ofDisposition"
            + " hardcoded an empty list, so a reader reloading this run saw a terminal that claimed"
            + " nothing while the deltas said otherwise.");
    assertEquals(
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        deltaDocIds(events),
        "and terminal equivalence (865 §7.1 A5) now covers this disposition too — same members,"
            + " same order, so a sentence cite's sourceIndex resolves correctly here as well");
  }

  /**
   * Tempdoc 878 review B2 — a NO-TOOLS finalize must never inherit a TOOL-FORCING sampling profile.
   *
   * <p>{@code attemptFinalize} used the 3-argument {@code callLlmWithTools}, which resolves sampling
   * from the SESSION via {@code resolveAgentSampling}. In the E0a forced-tool state that returns
   * {@code tool_choice=required} PLUS {@code TOOL_CALL_GRAMMAR} — and the server applies a grammar
   * exactly when the tools list is empty, which it always is for a finalize. The model would be
   * CONSTRAINED to emit {@code <tool_call>{…}</tool_call>} with no tools to call, and
   * {@code recoverInlineToolCalls} cannot strip it (its name set is empty when tools is empty), so
   * the raw JSON blob streams out as the ANSWER of a truncated run.
   *
   * <p>Reachable, not theoretical: {@code recordHandoff} zeroes {@code agentIterationsSinceHandoff}
   * and the counter only increments AFTER the LLM call, so a handoff on the last allowed iteration
   * leaves the session forced when the loop falls through to the ceiling.
   *
   * <p>The budget wall never met this because its outer gate excludes forced-tool turns for an
   * unrelated reason. Asserted here at the FINALIZE, because that is where the fix lives: a no-tools
   * call pins its own unconstrained profile instead of every call site remembering a guard.
   */
  @Test
  @DisplayName("878 B2: the ceiling finalize is never grammar-constrained, even from a forced-tool session")
  void theFinalizeNeverInheritsATellingToolForcingProfile() {
    var session = new AgentSession(new ArrayList<>(userMessage("q")), 8000);
    // E0a: a handoff to a non-primary agent, whose very next turn is forced to call a tool.
    session.recordHandoff("primary", "organizer", "delegating the ingest");
    assertTrue(
        AgentTurnPolicy.shouldForceToolCall(session),
        "precondition: this session IS in the forced-tool state, or the test proves nothing");
    var forced = AgentLlmCaller.resolveAgentSampling(session);
    assertEquals(
        "required",
        forced.toolChoice(),
        "precondition: session-resolved sampling really does force a tool call here — this is the"
            + " half a reader cannot check by reading attemptFinalize");
    assertNotNull(forced.grammar(), "and really does carry the tool-call grammar");

    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Partial answer.")));
    new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(true, 200, 1))
        .attemptStepCeilingFinalize(session, event -> {});

    assertEquals(1, ai.recordedSampling.size(), "the finalize made exactly one call");
    var used = ai.recordedSampling.get(0);
    assertNull(
        used.grammar(),
        "RED BEFORE the B2 fix: the finalize resolved sampling from the session and inherited"
            + " TOOL_CALL_GRAMMAR, so the model was constrained to emit a <tool_call> blob with no"
            + " tools to call — machine syntax presented as the run's answer");
    assertNotEquals(
        "required",
        used.toolChoice(),
        "and it must not force a tool choice either: there are no tools in this call to choose");
    assertTrue(ai.recordedTools.get(0).isEmpty(), "the finalize is a no-tools call by construction");
  }

  /**
   * Tempdoc 878 review B1 — the END-TO-END measurement, through the real Layer-2 cut.
   *
   * <p>The two unit-level tests hand-pass a count, so neither could see that the PRODUCER was
   * measuring the wrong string: {@code AgentContextCompressor.truncate} returns the prefix plus a
   * {@code [... truncated, N chars omitted]} marker, and measuring its return value reported ~35
   * characters MORE than the model received. This runs a real over-cap tool result through the real
   * loop and asserts the relationship that must hold — the count is what the prompt got, and it is
   * a fraction of what the tool returned.
   */
  @Test
  @DisplayName("878 B1: an over-cap tool result reports the chars the PROMPT got, not the marker's length")
  void anOverCapToolResultReportsWhatThePromptActuallyGot() {
    String bigOutput = "y".repeat(9000);
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.textOnly("done")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, bigOutput));

    var events = run(service, userMessage("big result"), 4);

    var completed =
        events.stream()
            .filter(AgentEvent.ToolExecutionCompleted.class::isInstance)
            .map(AgentEvent.ToolExecutionCompleted.class::cast)
            .filter(e -> "call_1".equals(e.callId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no completion for call_1"));

    assertEquals(
        bigOutput.length(),
        completed.result().message().length(),
        "`output` on the wire stays the tool's WHOLE answer — the reader is not context-bound");
    assertTrue(
        completed.outputCharsToModel() < bigOutput.length(),
        "and the model got strictly less: "
            + completed.outputCharsToModel()
            + " vs "
            + bigOutput.length());
    assertEquals(
        layerTwo().toolResultCapChars(),
        completed.outputCharsToModel(),
        "RED BEFORE the B1 fix: the producer measured truncate()'s RETURN, which carries the"
            + " `[... truncated, N chars omitted]` marker, so it reported ~35 chars more than the"
            + " prompt received — a number larger than the output for a small overflow, which"
            + " inverted truncatedForModel into a measured 'the model got all of it'");
    assertTrue(completed.truncatedForModel(), "and the derived flag agrees with the count");
  }

  /**
   * Tempdoc 878 §D.1 — the ceiling ATTEMPTS synthesis, and the attempt is a real no-tools LLM call.
   *
   * <p>The defect: {@code AgentLoopService} emitted an empty answer and returned, so a run that had
   * done all its work and hit the step ceiling threw that work away. The budget wall has always made
   * this call; the ceiling never did.
   *
   * <p>The assertions are split deliberately. That a THIRD call happened, with NO tools and the
   * step-limit instruction, is the structural claim — it is what "attempted synthesis" means and it
   * cannot be talked out of. That the returned text reaches the terminal is the visible consequence.
   * What is NOT claimed anywhere: that a real model produces useful text here (859 §7 watched one
   * decline). The disposition is asserted independently for exactly that reason.
   */
  @Test
  @DisplayName("878 §D.1: the iteration ceiling makes one no-tools finalize call and its answer reaches the terminal")
  void maxIterationsTerminal_attemptsFinalizeAndCarriesItsAnswer() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}"),
                ScriptedResponse.textOnly("Partial: I found d1 and d2 but ran out of steps.")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, "r")
                .returningStructuredData(List.of(searchEvidence("d1", "d2"))));

    var events = run(service, userMessage("loop"), 2);

    assertEquals(
        3,
        ai.recordedTools.size(),
        "two iterations plus ONE finalize call — the ceiling no longer stops without asking");
    assertTrue(
        ai.recordedTools.get(2).isEmpty(),
        "the finalize call passes NO tools: it is a synthesis turn, not another step");
    List<Map<String, Object>> finalizePrompt = ai.recordedMessages.get(2);
    String lastUserMessage =
        String.valueOf(finalizePrompt.get(finalizePrompt.size() - 1).get("content"));
    assertTrue(
        lastUserMessage.startsWith(AgentLlmCaller.STEP_CEILING_FINALIZE_INSTRUCTION),
        "and it names the STEP limit, not the token budget — 859 D5's rule (a run stopped by the"
            + " ceiling with budget to spare must not be told tokens stopped it) applied to the"
            + " text handed to the MODEL, not only to the FE notice");

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals(
        "Partial: I found d1 and d2 but ran out of steps.",
        done.finalResponse(),
        "RED BEFORE 878: the ceiling emitted \"\" with no LLM call at all, so 8/8 recorded"
            + " MAX_ITERATIONS runs answered nothing despite having done the work");
    assertEquals(
        TerminalDisposition.MAX_ITERATIONS.name(),
        done.disposition(),
        "the truncation stays disclosed: a partial answer does not make this a COMPLETED run");
  }

  /**
   * Tempdoc 865 §7.1 — the cancel case, after TWO searches. Same property as MAX_ITERATIONS with a
   * sharper edge: a cancelled run emits no {@code done} at all, so before this slice the evidence
   * had no carrier of any kind.
   */
  @Test
  @DisplayName("865 §7.1: a run cancelled after two searches keeps both calls' evidence")
  void cancelledRun_keepsTheEvidenceEstablishedBeforeTheCancel() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}"),
                ScriptedResponse.textOnly("never reached")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, "r")
                .returningStructuredData(
                    List.of(searchEvidence("d1", "d2"), searchEvidence("d1", "d3"))));

    var events = new CopyOnWriteArrayList<AgentEvent>();
    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var completed = new java.util.concurrent.atomic.AtomicInteger();
    // Cancel from inside the consumer after the SECOND tool completes — the same in-thread
    // synchronous-delivery assumption `sessionEnd_userCancelledMidLoop` documents.
    Consumer<AgentEvent> sink =
        event -> {
          events.add(event);
          if (event instanceof AgentEvent.SessionStarted s) {
            sessionId.set(s.sessionId());
          }
          if (event instanceof AgentEvent.ToolExecutionCompleted
              && completed.incrementAndGet() == 2
              && sessionId.get() != null) {
            service.cancelSession(sessionId.get());
          }
        };
    service.runAgent(new AgentRequest(userMessage("cancel me"), List.of(), 5), sink);

    assertNull(
        lastEventOfType(events, AgentEvent.AgentDone.class),
        "a cancelled run emits no grounded terminal — which is exactly why the terminal could not"
            + " be the mint");
    assertEquals(
        List.of("d1", "d2", "d3"),
        deltaDocIds(events),
        "everything both searches established is on the tool events, durable on both planes");
  }

  /**
   * Tempdoc 865 PR-1 review F-2 — terminal equivalence holds for a run whose tool calls go through
   * BOTH dispatch channels.
   *
   * <p>{@code handleVirtualToolCall} is the second channel: the {@code vop_*} result arrives from the
   * FE rather than the synchronous executor, and it calls {@code recordExecution} like the main seam
   * does. It shipped recording WITHOUT stamping, which the grounding-seam audit cannot see — that
   * rule forbids stamping outside a seam, not recording without stamping. The failure it would
   * produce is not an error anywhere: a source lands in the accumulator and in no delta, the
   * concatenation stops equalling the terminal list, and every position after the gap shifts —
   * silently redirecting the inline marks that resolve through those positions.
   *
   * <p><b>Honest scope, stated rather than implied.</b> The virtual channel builds its result with
   * {@code OperationResult.success(String)} / {@code failure(String)}, neither of which carries
   * {@code structuredData}, so a virtual call establishes nothing TODAY and its delta is empty. This
   * case therefore pins that a virtual call in the middle of a run perturbs neither the deltas nor
   * their ORDER — not that the virtual channel carries evidence. Writing it as if it did would be an
   * unreachable seed; the stamp itself is held in place by
   * {@code everyDispatchSeamStillStampsTheGroundingDelta}.
   */
  @Test
  @DisplayName("865 F-2: terminal equivalence holds across BOTH dispatch channels (a vop_ call between two searches)")
  void virtualToolRun_keepsTerminalEquivalence() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{\"q\":\"a\"}"),
                ScriptedResponse.toolCall("call_v", "vop_open_folder", "{\"path\":\"x\"}"),
                ScriptedResponse.toolCall("call_2", "core_search", "{\"q\":\"b\"}"),
                ScriptedResponse.textOnly("Here is the answer")));
    var service =
        buildService(
            ai,
            new StubTool("search", RiskTier.LOW, "r")
                .returningStructuredData(
                    List.of(searchEvidence("d1", "d2"), searchEvidence("d1", "d3"))));

    var events = new CopyOnWriteArrayList<AgentEvent>();
    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    // Answer the virtual call from inside the consumer. Delivery is in-thread and synchronous, so
    // the future is already complete when the loop reaches its `get`.
    Consumer<AgentEvent> sink =
        event -> {
          events.add(event);
          if (event instanceof AgentEvent.SessionStarted s) {
            sessionId.set(s.sessionId());
          }
          if (event instanceof AgentEvent.ToolCallVirtual v && sessionId.get() != null) {
            service.completeVirtualToolCall(sessionId.get(), v.callId(), true, "opened", null);
          }
        };
    service.runAgent(new AgentRequest(userMessage("mixed channels"), List.of(), 6), sink);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the run reaches a grounded terminal");
    assertEquals(
        List.of("d1", "d2", "d3"),
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList());
    assertEquals(
        done.sources().stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        deltaDocIds(events),
        "the virtual call sits between the two searches and contributes nothing — the concatenated"
            + " deltas still equal the terminal list, same members, same order");
    // The virtual call really did execute through the second channel (otherwise this case would be
    // asserting the single-channel property under a longer name).
    assertNotNull(
        lastEventOfType(events, AgentEvent.ToolCallVirtual.class),
        "the vop_ branch must have been taken");

    // Tempdoc 878 §D.5 — the virtual channel's result carries a text-provenance stamp, like the
    // synchronous channel's has since 577. RED BEFORE: this seam skipped `withLineage` entirely, and
    // the FE's fail-open default (unknown ⇒ runtime ⇒ no frame) rendered the result exactly as an
    // unstamped one, so nothing downstream could tell "classified runtime" from "never classified".
    var virtualCompletion =
        events.stream()
            .filter(AgentEvent.ToolExecutionCompleted.class::isInstance)
            .map(AgentEvent.ToolExecutionCompleted.class::cast)
            .filter(e -> "call_v".equals(e.callId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no completion for the virtual call"));
    assertEquals(
        io.justsearch.agent.api.registry.OutputLineage.RUNTIME.wireToken(),
        virtualCompletion.result().structuredData().get(OperationResult.LINEAGE_KEY),
        "a vop_ tool's output is a runtime value by classification — which is a DIFFERENT fact from"
            + " being unclassified, even though the two render identically");

    // And the same seam reports what the model received (878 §D.4).
    assertEquals(
        "opened".length(),
        virtualCompletion.outputCharsToModel(),
        "the virtual seam measures too — a measurement present at some seams and absent at others"
            + " leaves the FE unable to tell 'not truncated' from 'not measured'");
  }

  /**
   * Tempdoc 865 PR-1 review F-5 — the premise behind the mint's missing success guard, pinned.
   *
   * <p>{@code AgentSession.contributeGroundingSources} deliberately applies no success guard, and its
   * javadoc justifies that by asserting a failed result cannot carry search evidence. That was an
   * argument in a comment; this is the test behind it. If a failure factory ever gains a
   * {@code structuredData} channel, the guard question genuinely reopens — and this fails, which is
   * the point.
   */
  @Test
  @DisplayName("865 F-5: no OperationResult failure factory can carry structuredData")
  void failureResultsCannotCarryStructuredData() {
    assertTrue(
        OperationResult.failure("boom").structuredData().isEmpty(),
        "the failure factory carries no structured payload");
    assertTrue(
        OperationResult.failure("boom", "SOME_CODE", Map.of("k", "v"), Boolean.TRUE)
            .structuredData()
            .isEmpty(),
        "the typed-error failure factory carries errorDetails, NOT structuredData — so a failed tool"
            + " cannot report searchResults and the mint needs no success guard");
    // The reflective half: no OTHER public factory returns a non-success result at all, so the two
    // above are the whole failure surface. A new one would have to be added here to be reachable.
    List<String> failureFactories =
        Arrays.stream(OperationResult.class.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            .filter(m -> m.getReturnType() == OperationResult.class)
            .map(java.lang.reflect.Method::getName)
            .filter(name -> name.equals("failure"))
            .toList();
    assertEquals(
        2,
        failureFactories.size(),
        "exactly two failure factories exist; a third would need its structuredData checked above");
  }

  // ---------------------------------------------------------------------------
  // Empty output retry
  // ---------------------------------------------------------------------------

  @Test
  void emptyResponse_retries() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.empty(),
        ScriptedResponse.textOnly("Success after retry")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("hi"), 3);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    assertEquals("Success after retry", done.finalResponse());

    assertEquals(2, ai.recordedMessages.size(), "LLM should be called twice (original + retry)");
  }

  // ---------------------------------------------------------------------------
  // Empty response after retry — emits error
  // ---------------------------------------------------------------------------

  @Test
  void emptyResponseAfterRetry_emitsAgentError() {
    // Both initial and retry produce empty responses (simulates B6 bug)
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.empty(),
        ScriptedResponse.empty()));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("complex query"), 3);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error, "Should emit AgentError on double-empty response");
    assertEquals("EMPTY_RESPONSE", error.errorCode());
    assertEquals("TRANSIENT", error.errorClass());
    assertEquals("ABORT", error.retryAction());
    assertEquals(1, error.retryAttempt());
    // Tempdoc 881 §C.3 — the fake reports no finish reason, so the message must SAY that rather
    // than fill the gap with the old "possible reasoning token exhaustion" hypothesis.
    assertTrue(error.error().contains("no finish reason"),
        "an unknown cause must be reported as unknown: " + error.error());
    assertFalse(error.error().contains("exhaustion"),
        "the refuted diagnosis must not come back — 881 §A.2 measured finish_reason=stop at 35-55"
            + " of 1024 completion tokens, so 'exhaustion' was never the cause: " + error.error());

    // Should NOT emit AgentDone with empty text
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNull(done, "Should not emit AgentDone on empty response");
  }

  // ---------------------------------------------------------------------------
  // Tempdoc 881 — the standard profile's leaked tool call
  // ---------------------------------------------------------------------------

  /**
   * The whole 881 defect, end to end through the loop: the model answers a tool-planning turn by
   * putting a well-formed tool call inside its thinking block and stopping. Before this slice the
   * loop saw an empty turn, retried it identically, and errored the run — measured 0/3 on the
   * standard profile (881 §A.1). The call must now be recovered and EXECUTED.
   *
   * <p>The reasoning string is verbatim from the live capture (881 §A.2), down to the newlines.
   */
  @Test
  void executesTheToolCallTheModelLeakedIntoItsReasoningChannel() {
    var tool = new StubTool("search", RiskTier.LOW, "found it");
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.reasoningOnly(
            "I need to first see what's at the top level of the indexed folders before I can list"
                + " files. Let me call core_search without the list_files parameter.\n\n"
                + "<tool_call>\n<function=core_search>\n</function>\n</tool_call>",
            "stop"),
        ScriptedResponse.textOnly("Here is the summary.")));
    var service = buildService(ai, tool);

    var events = run(service, userMessage("read three documents"), 3);

    assertEquals(1, tool.callCount.get(),
        "the leaked call is the model's actual decision and must run, not be discarded");
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the run completes instead of erroring");
    assertEquals("Here is the summary.", done.finalResponse());
    assertNull(lastEventOfType(events, AgentEvent.AgentError.class),
        "no EMPTY_RESPONSE error is emitted for a turn that in fact expressed a call");
  }

  /**
   * The other half of the gate, and the one claim 881 §C.1's safety rests on: reasoning-channel
   * recovery runs ONLY when the turn would otherwise be discarded. A turn that produced an ANSWER
   * must not have a tool call pulled out of its thinking — that is a model weighing an option while
   * writing prose, and executing it would be worse than the bug the recovery fixes.
   *
   * <p>881 review §G finding 5: only the positive branch had a loop-level test.
   */
  @Test
  void doesNotRecoverFromReasoningWhenTheTurnAlsoProducedText() {
    var tool = new StubTool("search", RiskTier.LOW, "found it");
    var ai = new ScriptedAiService(List.of(
        new ScriptedResponse(
            "Here is the answer, and I decided not to search.",
            "I considered searching.\n<tool_call>\n<function=core_search>\n</function>\n</tool_call>",
            List.of(),
            null,
            "stop")));
    var service = buildService(ai, tool);

    var events = run(service, userMessage("q"), 3);

    assertEquals(0, tool.callCount.get(),
        "a wrapped call inside the thinking of a turn that ANSWERED must not execute");
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals("Here is the answer, and I decided not to search.", done.finalResponse(),
        "and the answer is delivered untouched");
  }

  /**
   * The retry has to CHANGE something (881 §C.2). An empty turn is a property of the prompt shape,
   * not of transient server state — an identical re-issue failed 3/3 on replay and 2/2 end-to-end in
   * 868 — so the second attempt suppresses the thinking prompt, the one change measured to move it
   * (40/40 turns returned a structured call under {@code enable_thinking:false}, 881 §A.3).
   */
  @Test
  void emptyResponseRetrySuppressesThinkingInsteadOfReissuingIdentically() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.empty(),
        ScriptedResponse.textOnly("Success after the thinking-suppressed retry")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    run(service, userMessage("hi"), 3);

    assertEquals(2, ai.recordedSampling.size(), "original + retry");
    assertNull(ai.recordedSampling.get(0).enableThinking(),
        "the FIRST attempt keeps the model's reasoning — that is why the standard profile exists");
    assertEquals(Boolean.FALSE, ai.recordedSampling.get(1).enableThinking(),
        "the retry is the one that gives it up, because the alternative is a known-deterministic"
            + " repeat of the same empty turn");
  }

  /** A truncated completion says so, and names the remedy that would actually work. */
  @Test
  void aLengthTruncatedEmptyTurnIsReportedAsATokenLimitNotAsAModelChoice() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.empty().withFinishReason("length"),
        ScriptedResponse.empty().withFinishReason("length")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("q"), 3);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertTrue(error.error().contains("finish_reason=length"),
        "the runtime's own reason is quoted, not paraphrased: " + error.error());
    assertTrue(error.error().contains("max_completion_tokens"),
        "and THIS is the branch where raising the token budget is the right advice: " + error.error());
  }

  /** A model that simply stopped is not described as having hit a limit. */
  @Test
  void aStoppedEmptyTurnIsNotBlamedOnTheTokenBudget() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.empty().withFinishReason("stop"),
        ScriptedResponse.empty().withFinishReason("stop")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("q"), 3);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertTrue(error.error().contains("finish_reason=stop"), error.error());
    assertTrue(error.error().contains("not a token limit"),
        "the reader must be steered AWAY from the budget lever here — following it is what 868 §D.3"
            + " did: " + error.error());
    assertFalse(error.error().contains("max_completion_tokens"),
        "and must not be handed the lever that cannot help: " + error.error());
  }

  // ---------------------------------------------------------------------------
  // Reasoning chunks accumulated without affecting text
  // ---------------------------------------------------------------------------

  @Test
  void reasoningChunks_doNotAffectTextResponse() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.withReasoning("Final answer", "Step 1: think... Step 2: decide...")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("think about this"), 1);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    assertEquals("Final answer", done.finalResponse());
  }

  // ---------------------------------------------------------------------------
  // Think tag hygiene lives upstream, in the inference layer
  // ---------------------------------------------------------------------------

  @Test
  void thinkTags_areNotStrippedHere_becauseTheStreamFilterOwnsThat() {
    // Tempdoc 835 §5.3: the agent used to run its own strip over the accumulated text — a second
    // authority over the same fact, and one that could only see whole tags after the stream ended.
    // The single authority is now ThinkTagStreamFilter in OnlineModeOps' streaming parse (tag
    // straddles across SSE frames, reasoning rerouted to the reasoning channel), so by the time
    // text reaches this accumulator it carries no tags. This scripted service bypasses that layer,
    // which is exactly what makes it able to witness that the agent no longer double-strips.
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.textOnly("<think>internal reasoning here</think>Clean answer")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("answer this"), 1);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    assertEquals("<think>internal reasoning here</think>Clean answer", done.finalResponse());
  }

  // ---------------------------------------------------------------------------
  // Tool result truncation
  // ---------------------------------------------------------------------------

  /**
   * A compressor budgeting against the no-server window, which is what these tests run under.
   *
   * <p>Tempdoc 883 decision 3 — the Layer-2 cap is no longer a class-init constant, so these
   * assertions are written against {@code toolResultCapChars()} rather than the literal 4000 they
   * used to hardcode. That is the stronger pin: it states the RELATIONSHIP (prefix kept, overflow
   * counted) at whatever cap the window derives, instead of a number that silently stopped being
   * the cap the moment the window changed.
   */
  private static AgentContextCompressor layerTwo() {
    return new AgentContextCompressor(false, 200, 1);
  }

  @Test
  void truncateForContext_shortOutput_passesThrough() {
    String shortOutput = "Found 3 results (took 15ms).";
    assertEquals(shortOutput, layerTwo().truncate(shortOutput));
  }

  @Test
  void truncateForContext_nullOutput_returnsNull() {
    assertNull(layerTwo().truncate(null));
  }

  @Test
  void truncateForContext_longOutput_isTruncated() {
    int cap = layerTwo().toolResultCapChars();
    String longOutput = "x".repeat(cap + 1000);
    String result = layerTwo().truncate(longOutput);
    assertTrue(result.length() < longOutput.length(), "Should be shorter than original");
    assertTrue(result.startsWith("x".repeat(cap)), "Should preserve start of output");
    assertTrue(result.contains("[... truncated,"), "Should include truncation notice");
    assertTrue(result.contains("1000 chars omitted]"), "Should report omitted char count");
  }

  @Test
  void truncateForContext_exactlyAtLimit_passesThrough() {
    String exactOutput = "x".repeat(layerTwo().toolResultCapChars());
    assertEquals(exactOutput, layerTwo().truncate(exactOutput));
  }

  @Test
  void contextCompression_enabled_compressesOlderToolOutputs() {
    withSysProps(
        Map.of(
            "justsearch.agent.context_compression.enabled", "true",
            "justsearch.agent.context_compression.min_chars", "100",
            "justsearch.agent.context_compression.keep_last_results", "1"),
        () -> {
          var ai =
              new ScriptedAiService(
                  List.of(
                      ScriptedResponse.toolCall("call_1", "core_search_one", "{}"),
                      ScriptedResponse.toolCall("call_2", "core_search_two", "{}"),
                      ScriptedResponse.textOnly("done")));
          var service =
              buildService(
                  ai,
                  new StubTool("search_one", RiskTier.LOW, largeToolOutput("first")),
                  new StubTool("search_two", RiskTier.LOW, largeToolOutput("second")));

          run(service, userMessage("compress please"), 5);

          List<Map<String, Object>> thirdCall = ai.recordedMessages.get(2);
          String firstToolContent =
              thirdCall.stream()
                  .filter(
                      m ->
                          "tool".equals(m.get("role"))
                              && "call_1".equals(String.valueOf(m.get("tool_call_id"))))
                  .map(m -> String.valueOf(m.get("content")))
                  .findFirst()
                  .orElse("");
          String secondToolContent =
              thirdCall.stream()
                  .filter(
                      m ->
                          "tool".equals(m.get("role"))
                              && "call_2".equals(String.valueOf(m.get("tool_call_id"))))
                  .map(m -> String.valueOf(m.get("content")))
                  .findFirst()
                  .orElse("");

          assertTrue(
              firstToolContent.startsWith("[compressed-tool-output"),
              "older tool output should be compressed");
          assertFalse(
              secondToolContent.startsWith("[compressed-tool-output"),
              "latest tool output should stay uncompressed");
        });
  }

  @Test
  void contextCompression_disabled_keepsToolOutputsUncompressed() {
    withSysProps(
        Map.of(
            "justsearch.agent.context_compression.enabled", "false",
            "justsearch.agent.context_compression.min_chars", "100",
            "justsearch.agent.context_compression.keep_last_results", "1"),
        () -> {
          var ai =
              new ScriptedAiService(
                  List.of(
                      ScriptedResponse.toolCall("call_1", "core_search_one", "{}"),
                      ScriptedResponse.toolCall("call_2", "core_search_two", "{}"),
                      ScriptedResponse.textOnly("done")));
          var service =
              buildService(
                  ai,
                  new StubTool("search_one", RiskTier.LOW, largeToolOutput("first")),
                  new StubTool("search_two", RiskTier.LOW, largeToolOutput("second")));

          run(service, userMessage("no compression"), 5);

          List<Map<String, Object>> thirdCall = ai.recordedMessages.get(2);
          String firstToolContent =
              thirdCall.stream()
                  .filter(
                      m ->
                          "tool".equals(m.get("role"))
                              && "call_1".equals(String.valueOf(m.get("tool_call_id"))))
                  .map(m -> String.valueOf(m.get("content")))
                  .findFirst()
                  .orElse("");
          assertFalse(firstToolContent.startsWith("[compressed-tool-output"));
        });
  }

  @Test
  void stripSearchExcerpts_removesExcerptLines() {
    String input =
        "[1] Architecture Overview (score: 0.95)\n"
            + "    Path: /docs/architecture.md\n"
            + "    Excerpt: \"This document describes the system architecture...\"\n"
            + "[2] Setup Guide (score: 0.80)\n"
            + "    Path: /docs/setup.md\n"
            + "    Excerpt: \"Follow these steps to install and configure...\"\n"
            + "\nFound 2 results (took 15ms).";
    String result = AgentContextCompressor.stripSearchExcerpts(input);
    assertFalse(result.contains("Excerpt:"), "Excerpts should be removed");
    assertTrue(result.contains("[1] Architecture Overview"), "Title should be preserved");
    assertTrue(result.contains("Path: /docs/architecture.md"), "Path should be preserved");
    assertTrue(result.contains("[2] Setup Guide"), "Second title should be preserved");
    assertTrue(result.contains("Found 2 results"), "Footer should be preserved");
  }

  @Test
  void stripSearchExcerpts_noExcerpts_passesThrough() {
    String input = "No matching results found.";
    assertEquals(input, AgentContextCompressor.stripSearchExcerpts(input));
  }

  // ---------------------------------------------------------------------------
  // No tools
  // ---------------------------------------------------------------------------

  @Test
  void noToolsAvailable_emitsError() {
    var ai = new ScriptedAiService(List.of());
    var service =
        new AgentLoopService(
            ai,
            OperationCatalog.of("core", List.of()),
            stubExecutor(),
            stubEmitter(),
            null,
            null);

    var events = run(service, userMessage("hi"), 1);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("NO_TOOLS", error.errorCode());
    assertEquals("PERMANENT", error.errorClass());
    assertEquals("ABORT", error.retryAction());
    assertEquals("No tools available", error.error(), "no selection ⇒ the unqualified message");
  }

  /** An emitter that offers nothing — the availability filter's state, not a broken selection. */
  private static io.justsearch.agent.api.registry.AgentToolEmitter offersNothingEmitter() {
    return new io.justsearch.agent.api.registry.AgentToolEmitter() {
      @Override
      public List<Operation> offer(
          OperationCatalog catalog, java.util.Collection<String> selectedNames) {
        return List.of();
      }

      @Override
      public List<Map<String, Object>> emit(
          OperationCatalog catalog, java.util.Collection<String> selectedNames) {
        return List.of();
      }
    };
  }

  @Test
  @DisplayName("868 D.4: a selection of REAL tools that are withheld says so, and names them")
  void noToolsWithSelectionOfExistingTools_namesThemAsUnavailable() {
    // The live 868 §C.3 shape: `tools: ["core_search_index"]` matched the catalog, then the
    // availability filter (index.unavailable asserted) dropped the survivor, so the offering was
    // empty. The run said "No tools available" — indistinguishable from a typo'd tool name, and
    // pointing at the wrong remedy.
    var service =
        new AgentLoopService(
            new ScriptedAiService(List.of()),
            stubCatalog(new StubTool("search_index", RiskTier.LOW, "ok")),
            stubExecutor(),
            offersNothingEmitter(),
            null,
            null);

    var events =
        runWithRequest(
            service, new AgentRequest(userMessage("hi"), List.of("core_search_index"), 1));

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("NO_TOOLS", error.errorCode());
    assertTrue(
        error.error().contains("not available right now"),
        "the message must name the cause, not just the symptom: " + error.error());
    assertTrue(error.error().contains("core_search_index"), error.error());
  }

  @Test
  @DisplayName("868 D.4: the OperationRef form of the same selection is diagnosed identically")
  void noToolsWithRefIdSelection_namesItAsUnavailable() {
    var service =
        new AgentLoopService(
            new ScriptedAiService(List.of()),
            stubCatalog(new StubTool("search_index", RiskTier.LOW, "ok")),
            stubExecutor(),
            offersNothingEmitter(),
            null,
            null);

    var events =
        runWithRequest(
            service, new AgentRequest(userMessage("hi"), List.of("core.search-index"), 1));

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertTrue(
        error.error().contains("not available right now"),
        "both accepted identifier spaces resolve against the catalog: " + error.error());
    assertTrue(error.error().contains("core.search-index"), error.error());
  }

  @Test
  @DisplayName("868 D.4: a selection naming nothing that exists still fires NO_TOOLS, and says so")
  void noToolsWithUnknownSelection_saysTheNamesAreUnknown() {
    var service =
        new AgentLoopService(
            new ScriptedAiService(List.of()),
            stubCatalog(new StubTool("search_index", RiskTier.LOW, "ok")),
            stubExecutor(),
            offersNothingEmitter(),
            null,
            null);

    var events =
        runWithRequest(service, new AgentRequest(userMessage("hi"), List.of("core_nope"), 1));

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("NO_TOOLS", error.errorCode());
    assertEquals("PERMANENT", error.errorClass());
    assertEquals("ABORT", error.retryAction());
    assertTrue(
        error.error().contains("None of the selected tools exist"),
        "the guard must still fire, for the unknown-name reason: " + error.error());
    assertTrue(error.error().contains("core_nope"), error.error());
  }

  @Test
  @DisplayName("868 D.4: a mixed selection reports the ones that exist as unavailable")
  void noToolsMessage_partitionsKnownFromUnknown() {
    OperationCatalog catalog = stubCatalog(new StubTool("search_index", RiskTier.LOW, "ok"));

    String message =
        AgentLoopService.noToolsMessage(catalog, List.of("core_search_index", "core_nope"));

    assertTrue(message.contains("not available right now"), message);
    assertTrue(message.contains("core_search_index"), message);
    assertFalse(
        message.contains("core_nope"),
        "an unknown name alongside a known one must not be reported as withheld: " + message);
  }

  // ---------------------------------------------------------------------------
  // Tool execution throws exception
  // ---------------------------------------------------------------------------

  @Test
  void toolExecutionThrows_returnsFailureResult() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_failing", "{}"),
        ScriptedResponse.textOnly("ok")));
    // Per Phase 11 of tempdoc 429: throwing-handler exercises the executor's fallback
    // branch. We build a custom catalog + executor where the handler raises a
    // RuntimeException; the agent loop's executeOperationWithPolicy converts it to
    // an OperationResult.failure(...).
    var failingOpId = new OperationRef("core.failing");
    var failingOp = new Operation(
        failingOpId,
        new Presentation(
            new I18nKey("test.failing.label"),
            new I18nKey("test.failing.description"),
            Optional.empty(),
            Optional.empty()),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE,
            RetryPolicy.noRetry(), Set.of(), false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(failingOpId),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
    var catalog = OperationCatalog.of("core", List.of(failingOp));
    var handlers = new HandlerRegistry();
    handlers.register(failingOpId, args -> { throw new RuntimeException("Tool crashed"); });
    var executor =
        new OperationDispatcher() {
          @Override
          public OperationResult dispatch(Operation op, String argumentsJson) {
            return handlers
                .resolve(new OperationRef(op.binding().handlerId()))
                .orElseThrow()
                .execute(argumentsJson);
          }

          @Override
          public OperationResult undo(Operation op, String executionId) {
            if (!op.policy().undoSupported()) {
              return OperationResult.failure("Undo not supported by " + op.id().value());
            }
            return handlers
                .resolve(new OperationRef(op.binding().handlerId()))
                .orElseThrow()
                .undo(executionId);
          }
        };
    var service = observed(new AgentLoopService(ai, catalog, executor, stubEmitter(), null, null));

    var events = run(service, userMessage("go"), 3);

    var completed = lastEventOfType(events, AgentEvent.ToolExecutionCompleted.class);
    assertNotNull(completed, "Should emit ToolExecutionCompleted even on exception");
    assertFalse(completed.result().success());
    assertTrue(completed.result().message().contains("Tool crashed"),
        "Should contain exception message: " + completed.result().message());
  }

  // ---------------------------------------------------------------------------
  // Assistant text preserved with tool calls
  // ---------------------------------------------------------------------------

  @Test
  void assistantTextPreserved_withToolCalls() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.textAndToolCall("Let me search", "call_1", "core_search", "{\"q\":\"x\"}"),
        ScriptedResponse.textOnly("Found it")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "result"));

    run(service, userMessage("find"), 3);

    // Second call: the assistant message should include "Let me search" as content
    List<Map<String, Object>> secondCall = ai.recordedMessages.get(1);
    Map<String, Object> assistantMsg = secondCall.stream()
        .filter(m -> "assistant".equals(m.get("role")) && m.containsKey("tool_calls"))
        .findFirst().orElse(null);
    assertNotNull(assistantMsg, "Should have assistant message with tool_calls");
    assertEquals("Let me search", assistantMsg.get("content"));
  }

  // ---------------------------------------------------------------------------
  // Budget tracking
  // ---------------------------------------------------------------------------

  @Test
  void budgetTracking_normalFlow_tracksTokens() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.textOnly("Hello").withUsage(50, 10)));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("hi"), 1);

    // Verify budget update events emitted
    var budgetEvents = eventsOfType(events, AgentEvent.AgentBudgetUpdate.class);
    assertEquals(2, budgetEvents.size(), "Should emit iteration_start + llm_response budget events");

    var iterationStartEvent = budgetEvents.get(0);
    assertEquals("iteration_start", iterationStartEvent.phase());
    assertTrue(iterationStartEvent.tokensRemaining() > 0, "Should have budget remaining");

    var llmResponseEvent = budgetEvents.get(1);
    assertEquals("llm_response", llmResponseEvent.phase());
    assertEquals(60, llmResponseEvent.tokensConsumed(), "Should consume 50+10=60 tokens");

    // Verify AgentDone includes token metrics
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertEquals(60, done.totalTokensUsed(), "Should track total tokens used");
  }

  @Test
  void budgetTracking_multipleIterations_accumulatesTokens() {
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(30, 5),
        ScriptedResponse.textOnly("Done").withUsage(40, 10)));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("query"), 3);

    // First iteration: 30+5=35, second iteration: 40+10=50
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertEquals(85, done.totalTokensUsed(), "Should accumulate tokens across iterations");

    // Verify budget decreased
    var budgetEvents = eventsOfType(events, AgentEvent.AgentBudgetUpdate.class);
    assertTrue(budgetEvents.size() >= 2, "Should have multiple budget events");

    var firstResponse = budgetEvents.get(1); // First llm_response event
    var lastEvent = budgetEvents.get(budgetEvents.size() - 1);
    assertTrue(
        lastEvent.tokensRemaining() < firstResponse.tokensRemaining(),
        "Budget should decrease over iterations");
  }

  @Test
  void budgetTracking_noUsageData_handlesGracefully() {
    // Simulate llama-server not providing usage data
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("Response")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));

    var events = run(service, userMessage("hi"), 1);

    // Should still emit AgentDone, with zero totalTokensUsed
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone even without usage data");
    assertEquals(0, done.totalTokensUsed(), "Should report zero tokens when no usage data");
  }

  @Test
  void budgetTracking_exhaustion_terminatesEarly() {
    // Create AI service that would normally run many iterations
    var ai = new ScriptedAiService(List.of(
        ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(50, 30),
        ScriptedResponse.toolCall("call_2", "core_search", "{}").withUsage(50, 30),
        ScriptedResponse.toolCall("call_3", "core_search", "{}").withUsage(50, 30),
        ScriptedResponse.textOnly("Done").withUsage(10, 5)));

    // Tempdoc 859 §D §2.1 — the budget is now `n_ctx * effortMultiplier` (Standard 8x), not
    // `n_ctx - 256`. A zero context window is what yields the zero budget this scenario needs: the
    // very first projection already exceeds it, so the run gates before any LLM call. (0 also
    // disables the context-pressure gate by its own `contextWindow > 0` guard, which keeps this test
    // on the one axis it is about.)
    var service = buildServiceWithSmallBudget(ai, 0);

    var events = run(service, userMessage("search"), 10);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("BUDGET_EXHAUSTED", error.errorCode());
    assertEquals("BUDGET", error.errorClass());
    assertEquals("ABORT", error.retryAction());
    assertTrue(error.error().contains("budget limit"),
        "Should mention budget in termination message");

    // Agent should terminate before first LLM call.
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNull(done);
  }

  @Test
  void resumeLastSession_unsupportedState_emitsTypedError() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("unused")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(userMessage("resume"), List.of(), 3);
    runStore.startRun("session_unsupported", request, request.messages(), 1000);
    runStore.updateCheckpoint(
        "session_unsupported",
        "TOOL_EXECUTING",
        request.messages(),
        1,
        0,
        12,
        "tool executing");

    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            null,
            runStore,
            null);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.resumeLastSession(events::add);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("UNSUPPORTED_RESUME_STATE", error.errorCode());
    assertEquals("ABORT", error.retryAction());
  }

  @Test
  void resumeLastSession_supportedState_runsAgentFromSnapshot() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("resumed")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(userMessage("resume"), List.of(), 3);
    runStore.startRun("session_supported", request, request.messages(), 1000);
    runStore.updateCheckpoint(
        "session_supported",
        "READY_FOR_LLM",
        request.messages(),
        0,
        0,
        0,
        "");

    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            null,
            runStore,
            null);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.resumeLastSession(events::add);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals("resumed", done.finalResponse());
  }

  // ---------------------------------------------------------------------------
  // resumeSession (by id) — tempdoc 415 follow-up (C20)
  // Mirrors resumeLastSession but addressed by sessionId. Inherits the same
  // state-gate (WAITING_APPROVAL / READY_FOR_LLM / AFTER_TOOL_RESULT only).
  // ---------------------------------------------------------------------------

  @Test
  void resumeSession_byId_runsAgentFromSpecificSnapshot() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("resumed-by-id")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs"));

    // Create two persisted sessions; resume the older one explicitly.
    var olderRequest = new AgentRequest(userMessage("older"), List.of(), 3);
    runStore.startRun("older_session", olderRequest, olderRequest.messages(), 1000);
    runStore.updateCheckpoint(
        "older_session", "READY_FOR_LLM", olderRequest.messages(), 0, 0, 0, "");
    var newerRequest = new AgentRequest(userMessage("newer"), List.of(), 3);
    runStore.startRun("newer_session", newerRequest, newerRequest.messages(), 1000);
    runStore.updateCheckpoint(
        "newer_session", "READY_FOR_LLM", newerRequest.messages(), 0, 0, 0, "");

    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            null,
            runStore,
            null);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.resumeSession("older_session", events::add);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Resuming the older session must reach AgentDone");
    assertEquals("resumed-by-id", done.finalResponse());
  }

  @Test
  void resumeSession_unknownId_emitsTypedError() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("nope")));
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs"));
    var service =
        new AgentLoopService(
            ai,
            OperationCatalog.of("core", List.of()),
            stubExecutor(),
            stubEmitter(),
            null,
            null,
            runStore,
            null);

    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.resumeSession("does-not-exist", events::add);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error, "Unknown sessionId must emit an AgentError");
    assertEquals("UNSUPPORTED_RESUME_STATE", error.errorCode());
    assertEquals("ABORT", error.retryAction());
  }

  @Test
  void resumeSession_unsupportedState_emitsTypedError() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("nope")));
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs"));
    var request = new AgentRequest(userMessage("resume"), List.of(), 3);
    runStore.startRun("session_unsupported_id", request, request.messages(), 1000);
    runStore.updateCheckpoint(
        "session_unsupported_id",
        "TOOL_EXECUTING",
        request.messages(),
        1,
        0,
        12,
        "tool executing");

    var service =
        new AgentLoopService(
            ai,
            OperationCatalog.of("core", List.of()),
            stubExecutor(),
            stubEmitter(),
            null,
            null,
            runStore,
            null);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.resumeSession("session_unsupported_id", events::add);

    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error);
    assertEquals("UNSUPPORTED_RESUME_STATE", error.errorCode());
  }

  // ---------------------------------------------------------------------------
  // Loop detection — consecutive identical tool calls
  // ---------------------------------------------------------------------------

  @Test
  void loopDetection_threeIdenticalCalls_blocksThirdBeforeExecution() {
    // Calls 1-2 execute normally, call 3 is blocked BEFORE execution (pre-execution guard)
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}"),
                ScriptedResponse.toolCall("c2", "core_search", "{}"),
                ScriptedResponse.toolCall("c3", "core_search", "{}"),
                ScriptedResponse.textOnly("Final answer")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("search"), 5);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone after loop recovery");
    assertEquals("Final answer", done.finalResponse());
    assertEquals(2, done.toolCallsExecuted(), "Only 2 calls should execute; 3rd is blocked");

    // Verify the loop-blocked message uses role:"tool" (OpenAI format contract)
    assertEquals(4, ai.recordedMessages.size(), "LLM should be called 4 times");
    List<Map<String, Object>> fourthCallMessages = ai.recordedMessages.get(3);
    boolean hasLoopMessage =
        fourthCallMessages.stream()
            .anyMatch(
                m ->
                    "tool".equals(m.get("role"))
                        && m.get("content") != null
                        && String.valueOf(m.get("content")).contains("Tool call blocked"));
    assertTrue(hasLoopMessage, "Should inject loop-blocked tool message with tool_call_id");
  }

  @Test
  void loopDetection_twoIdenticalThenDifferent_noBlocking() {
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}"),
                ScriptedResponse.toolCall("c2", "core_search", "{}"),
                ScriptedResponse.toolCall("c3", "core_search", "{\"query\":\"other\"}"),
                ScriptedResponse.textOnly("Done")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("search"), 5);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    assertEquals("Done", done.finalResponse());
    assertEquals(3, done.toolCallsExecuted(), "All 3 tool calls should execute without blocking");

    // Verify no loop-blocked message was injected
    List<Map<String, Object>> fourthCallMessages = ai.recordedMessages.get(3);
    boolean hasLoopMessage =
        fourthCallMessages.stream()
            .anyMatch(
                m ->
                    "tool".equals(m.get("role"))
                        && m.get("content") != null
                        && String.valueOf(m.get("content")).contains("Tool call blocked"));
    assertFalse(hasLoopMessage, "Should NOT inject loop-blocked message when args differ");
  }

  @Test
  void loopDetection_reorderedJsonArgs_treatedAsIdentical() {
    // JSON keys in different order should normalize to the same canonical form
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{\"query\":\"test\",\"limit\":10}"),
                ScriptedResponse.toolCall(
                    "c2", "core_search", "{\"limit\":10,\"query\":\"test\"}"),
                ScriptedResponse.toolCall("c3", "core_search", "{\"query\":\"test\",\"limit\":10}"),
                ScriptedResponse.textOnly("Done")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("search"), 5);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone after loop recovery");
    assertEquals(2, done.toolCallsExecuted(), "Third call blocked due to JSON normalization");
  }

  @Test
  void loopDetection_persistentLoopEscalation_terminatesAfterFiveBlocks() {
    // Calls 1-2 execute, then calls 3-7 are all blocked (5 blocks), triggering TOOL_LOOP abort
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}"),
                ScriptedResponse.toolCall("c2", "core_search", "{}"),
                ScriptedResponse.toolCall("c3", "core_search", "{}"),
                ScriptedResponse.toolCall("c4", "core_search", "{}"),
                ScriptedResponse.toolCall("c5", "core_search", "{}"),
                ScriptedResponse.toolCall("c6", "core_search", "{}"),
                ScriptedResponse.toolCall("c7", "core_search", "{}"),
                ScriptedResponse.textOnly("Should never reach")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("search"), 10);

    // Should terminate with TOOL_LOOP error, not AgentDone
    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error, "Should emit AgentError for persistent loop");
    assertEquals("TOOL_LOOP", error.errorCode());

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNull(done, "Should NOT emit AgentDone when terminated by loop escalation");
  }

  @Test
  void loopDetection_multiBatchWithIdenticalCalls_blocksThirdAcrossBatches() {
    // Batch 1: two identical calls in one response (consecutive count reaches 2)
    // Batch 2: one more identical call → would reach 3 → blocked before execution
    // Batch 3: LLM recovers with text answer
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.multiToolCall(
                    "c1", "core_search", "{}", "c2", "core_search", "{}"),
                ScriptedResponse.toolCall("c3", "core_search", "{}"),
                ScriptedResponse.textOnly("Recovered")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

    var events = run(service, userMessage("search"), 5);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone after multi-batch loop recovery");
    assertEquals("Recovered", done.finalResponse());
    assertEquals(2, done.toolCallsExecuted(), "Only first 2 calls in batch 1 should execute");

    // Verify blocked call got role:"tool" response (maintains OpenAI format)
    List<Map<String, Object>> thirdCallMessages = ai.recordedMessages.get(2);
    boolean hasToolBlockMessage =
        thirdCallMessages.stream()
            .anyMatch(
                m ->
                    "tool".equals(m.get("role"))
                        && String.valueOf(m.getOrDefault("content", ""))
                            .contains("Tool call blocked"));
    assertTrue(hasToolBlockMessage, "Blocked call should have role:tool response");
  }

  // ---------------------------------------------------------------------------
  // Budget-edge graceful finalize
  // ---------------------------------------------------------------------------

  @Test
  void budgetEdgeFinalize_synthesizesFromToolResults() {
    // First call: tool call that succeeds (establishes tool results)
    // Second call: finalize synthesis (text-only, triggered by budget exhaustion)
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                ScriptedResponse.textOnly("Synthesized answer from search results")));

    // Budget math (tempdoc 859 §D §2.1 — initialBudget = contextWindow * effortMultiplier, and the
    // 256-token safety margin is gone; this run names no rung, so it is Standard, resized to 8x by
    // live leg L1 on 2026-08-25 — hence the window below, chosen to keep this fixture's arithmetic):
    //   initialBudget = 250 * 8 = 2000
    // Iteration 1:
    //   countPromptTokens = messages.size() * 10 = 2 * 10 = 20 (ScriptedAiService simulation)
    //   20 < 2000 → budget OK → LLM call proceeds
    //   withUsage(100, 2000) → consumed 2100 tokens → budgetRemaining = 2000 - 2100 = -100
    // Iteration 2:
    //   countPromptTokens = 4 messages * 10 = 40 (system+user+assistant+tool)
    //   40 >= -100 → budget exhausted → attemptBudgetEdgeFinalize triggered
    //
    // The spend sits on the COMPLETION axis on purpose. Exhausting a Standard budget in one call
    // means spending several windows, and a REPORTED PROMPT that large would legitimately trip the
    // context-pressure gate too (859 §D §2.7(c) now reads the reported prompt) — which would leave
    // this test straddling two mechanisms. recordUsage decrements identically for either axis, so
    // the budget arithmetic under test is unchanged and the context axis stays quiet.
    var service = buildServiceWithSmallBudget(ai, 250);

    var events = run(service, userMessage("search"), 5);

    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone from budget-edge finalize");
    assertEquals("Synthesized answer from search results", done.finalResponse());

    // Should NOT emit BUDGET_EXHAUSTED error
    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNull(error, "Should not emit error when finalize succeeds");
  }

  // ---------------------------------------------------------------------------
  // Budget GATE (tempdoc 577 §2.12 Move 2) — interactive runs PARK at the boundary
  // ---------------------------------------------------------------------------

  // 577 Move 2 — an interactive budget-exhausted run PARKS (BudgetGatePending), then the 0s test
  // timeout falls through to the legacy finalize (behaviour preserved).
  @Test
  void budgetGate_interactiveRun_emitsPendingThenTimesOutToLegacyFinalize() {
    // Same budget setup as budgetEdgeFinalize_synthesizesFromToolResults: iter 2 exhausts budget.
    // The test JVM sets justsearch.agent.budgetGateTimeoutSec=0, so the gate emits its park event
    // then immediately times out → FINALIZE → exactly the legacy behaviour the prior test asserts.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                ScriptedResponse.textOnly("Synthesized answer from search results")));
    var service = buildServiceWithSmallBudget(ai, 250);

    var events = run(service, userMessage("search"), 5);

    // The run PARKED: a BudgetGatePending was emitted (the held decision point the FE renders).
    assertFalse(
        eventsOfType(events, AgentEvent.BudgetGatePending.class).isEmpty(),
        "an interactive budget-exhausted run must emit BudgetGatePending (the held gate)");
    // And the 0s timeout fell through to the legacy finalize — behaviour preserved.
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the timeout decision finalizes from tool results (legacy fallback)");
    assertEquals("Synthesized answer from search results", done.finalResponse());
  }

  // 577 Move 2 — a BACKGROUND budget-exhausted run never parks (no watcher to decide).
  @Test
  void budgetGate_backgroundRun_neverParks() throws Exception {
    // Tempdoc 859 §D §2.9 — a background run is pinned at 1x (400 here, not Standard's 2000), which
    // this fixture over-spends several times over, so the assertion below still fails for the RIGHT
    // reason: the run really did exhaust its budget and still did not park.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                ScriptedResponse.textOnly("Synthesized answer from search results")));
    var service = buildServiceWithSmallBudget(ai, 400);

    var events = new CopyOnWriteArrayList<AgentEvent>();
    var done = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> sink =
        e -> {
          events.add(e);
          if (e instanceof AgentEvent.AgentDone || e instanceof AgentEvent.AgentError) {
            done.complete(true);
          }
        };
    var t =
        new Thread(
            () -> service.runAgent(new AgentRequest(userMessage("search"), List.of(), 5), sink, true));
    t.setDaemon(true);
    t.start();
    assertTrue(done.get(8, java.util.concurrent.TimeUnit.SECONDS), "background run completes");
    t.join(2000);

    assertTrue(
        eventsOfType(events, AgentEvent.BudgetGatePending.class).isEmpty(),
        "a background run must NOT park at a budget gate (no watcher to decide)");
  }

  @Test
  void budgetEdgeFinalize_fallsBackToError_whenFinalizeReturnsEmpty() {
    // First call: tool call (establishes tool results)
    // Second call: finalize returns empty (best-effort fails)
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                ScriptedResponse.empty()));

    var service = buildServiceWithSmallBudget(ai, 250);

    var events = run(service, userMessage("search"), 5);

    // Empty finalize → fall through to BUDGET_EXHAUSTED error
    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNotNull(error, "Should emit BUDGET_EXHAUSTED when finalize fails");
    assertEquals("BUDGET_EXHAUSTED", error.errorCode());
  }

  // ---------------------------------------------------------------------------
  // Effort-mapped initial budgets (tempdoc 859 §D §2.1 / §3.3 T2)
  // ---------------------------------------------------------------------------

  /** The initial budget a run was dispatched with, read off its FIRST budget event. */
  private static int initialBudgetFrom(List<AgentEvent> events) {
    var first = eventsOfType(events, AgentEvent.AgentBudgetUpdate.class);
    assertFalse(first.isEmpty(), "every run emits an iteration_start budget event");
    // The first update fires BEFORE any usage is charged, so `tokensRemaining` is the ceiling.
    return first.get(0).tokensRemaining();
  }

  private static List<AgentEvent> runWithEffort(AgentLoopService service, String effort) {
    var request =
        new AgentRequest(
            userMessage("do the thing"), List.of(), 1, List.of(), null, null, null, null,
            List.of(), effort);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add);
    return events;
  }

  @Test
  @DisplayName("859 §D T2 — the EFFORT rung reaches the sizing policy and sets the run's budget")
  void effortRungReachesTheBudgetPolicy() {
    // The threading assertion, not the sizing one (T1 in AgentBudgetPolicyTest owns sizing): does
    // the rung the FE names actually arrive at AgentLoopService, or is it dropped at some boundary
    // and silently defaulted? Dropped-at-the-boundary is exactly how 561 P-D's autonomy level was
    // lost, so it is the failure this checks for.
    int contextWindow = 400;
    var quick = runWithEffort(freshBudgetService(contextWindow), "quick");
    var standard = runWithEffort(freshBudgetService(contextWindow), "standard");
    var thorough = runWithEffort(freshBudgetService(contextWindow), "thorough");
    var absent = runWithEffort(freshBudgetService(contextWindow), null);

    int quickBudget = initialBudgetFrom(quick);
    int standardBudget = initialBudgetFrom(standard);
    int thoroughBudget = initialBudgetFrom(thorough);

    assertTrue(
        quickBudget < standardBudget && standardBudget < thoroughBudget,
        "each rung must buy strictly more room: " + quickBudget + " / " + standardBudget + " / "
            + thoroughBudget);
    assertEquals(
        standardBudget,
        initialBudgetFrom(absent),
        "a caller that names no rung gets Standard — the deliberate default (859 §D §2.9)");
    assertTrue(
        quickBudget > contextWindow - 256,
        "even the smallest rung raises the pre-859 `n_ctx - 256` allowance (859 §D §2.2)");
  }

  @Test
  @DisplayName("859 §D T2 — the dispatched budget is what the run RECORD persists as initialBudget")
  void effortRungIsPersistedAsTheRunsInitialBudget() {
    // The wire figure and the persisted figure must be the same number: the FE reads one and the
    // lifecycle projection (AgentLifecycleProjection) reads the other.
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs-effort"));
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("answered")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        observed(
            new AgentLoopService(
                smallContextAi(ai, 400),
                stubCatalog(searchTool),
                stubExecutor(searchTool),
                stubEmitter(),
                null,
                null,
                runStore,
                null));
    var events = runWithEffort(service, "quick");
    var started = lastEventOfType(events, AgentEvent.SessionStarted.class);
    assertNotNull(started, "the run announced its session");

    var meta = runStore.readSnapshot(started.sessionId());
    assertNotNull(meta, "the run was persisted");
    assertEquals(
        initialBudgetFrom(events),
        ((Number) meta.get("initialBudget")).intValue(),
        "the persisted ceiling and the streamed ceiling are the same fact, not two");
  }

  /**
   * Tempdoc 859 §D §2.1 (review F1) — persist a run at {@code effort}, then reconstruct it through
   * {@code reconstruct} and report the budget the reconstructed run was given.
   */
  private int budgetAfterReconstruction(
      String sessionId,
      String effort,
      java.util.function.BiConsumer<AgentLoopService, Consumer<AgentEvent>> reconstruct) {
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs-" + sessionId));
    var request =
        new AgentRequest(
            userMessage("do the thing"), List.of(), 3, List.of(), null, null, null, null,
            List.of(), effort);
    runStore.startRun(sessionId, request, request.messages(), 1234);
    runStore.updateCheckpoint(sessionId, "READY_FOR_LLM", request.messages(), 0, 0, 0, "");

    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("answered")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        observed(
            new AgentLoopService(
                smallContextAi(ai, 400),
                stubCatalog(searchTool),
                stubExecutor(searchTool),
                stubEmitter(),
                null,
                null,
                runStore,
                null));
    var events = new CopyOnWriteArrayList<AgentEvent>();
    reconstruct.accept(service, events::add);
    return initialBudgetFrom(events);
  }

  @Test
  @DisplayName("owner 2026-08-26 — a cancel that arrives AFTER the run finished is still recorded")
  void lateCancelIsRecordedOnTheRun() {
    // The presentation race: the reader presses Stop as the run reaches its terminal. The registry
    // has already evicted the session, so `cancelSession` used to be a total no-op — the run's
    // disposition stayed COMPLETED and the reader's act survived nowhere. Cold-loading the
    // conversation then showed "finished", telling them the thing they did never happened.
    var runStore = new AgentRunStore(tempDir.resolve("agent-runs-late-cancel"));
    var request = new AgentRequest(userMessage("do the thing"), List.of(), 3);
    runStore.startRun("session_late_cancel", request, request.messages(), 1000);

    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("answered")));
    var searchTool = new StubTool("search", RiskTier.LOW, "r");
    var service =
        new AgentLoopService(
            ai,
            stubCatalog(searchTool),
            stubExecutor(searchTool),
            stubEmitter(),
            null,
            null,
            runStore,
            null);

    // No run is registered under this id — exactly the state a finished run leaves behind.
    service.cancelSession("session_late_cancel");

    var stopNotes =
        runStore.readEvents("session_late_cancel").stream()
            .filter(r -> "progress".equals(r.get("eventType")))
            .filter(
                r ->
                    r.get("payload") instanceof Map<?, ?> p
                        && AgentEvent.AgentProgress.PHASE_STOP_REQUESTED.equals(p.get("phase")))
            .toList();
    assertEquals(1, stopNotes.size(), "the reader's Stop must be on the run's own journal");

    // An id no run was ever written under mints nothing — the guard is on the run existing, not on
    // the caller being careful.
    service.cancelSession("session_never_existed");
    assertTrue(runStore.readEvents("session_never_existed").isEmpty());

    // Review F2 — ONCE PER RUN. A stop from a second window, or one re-sent against an already
    // stopped run, is the same fact arriving twice; a second note would draw a second row in the
    // reloaded feed saying exactly what the first one says.
    service.cancelSession("session_late_cancel");
    service.cancelSession("session_late_cancel");
    assertEquals(1, stopNotesOn(runStore, "session_late_cancel").size());
  }

  /** The reader's-stop notes on a run's journal (review F2 / owner decision 2026-08-26). */
  private static List<Map<String, Object>> stopNotesOn(AgentRunStore runStore, String sessionId) {
    return runStore.readEvents(sessionId).stream()
        .filter(r -> "progress".equals(r.get("eventType")))
        .filter(
            r ->
                r.get("payload") instanceof Map<?, ?> p
                    && AgentEvent.AgentProgress.PHASE_STOP_REQUESTED.equals(p.get("phase")))
        .toList();
  }

  @Test
  @DisplayName("review F1 — a cancel at a HELD BUDGET GATE stops the run, with no finalize answer")
  void cancelAtHeldBudgetGateTerminatesCancelledWithoutFinalizing() throws Exception {
    // The gate parks the loop on `future.get(timeout)`. `AgentSession.cancel()` completed the
    // approval gates and the virtual-tool waits but NOT this one, so a cancel during a budget park
    // did nothing at all for the length of the timeout — and the undecided fallback is FINALIZE, so
    // the run then synthesised and emitted an answer to a question the reader had abandoned.
    // The timeout is held LONG so the wait is what the cancel ends: with it short this would pass
    // for the wrong reason (the gate falling through on its own).
    String prev = System.getProperty("justsearch.agent.budgetGateTimeoutSec");
    System.setProperty("justsearch.agent.budgetGateTimeoutSec", "5");
    try {
      var ai =
          new ScriptedAiService(
              List.of(
                  ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                  ScriptedResponse.textOnly("the abandoned answer").withUsage(50, 20)));
      var service = buildServiceWithSmallBudget(ai, 250);

      var events = new CopyOnWriteArrayList<AgentEvent>();
      var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      var parked = new CompletableFuture<AgentEvent.BudgetGatePending>();
      Consumer<AgentEvent> sink =
          e -> {
            events.add(e);
            if (e instanceof AgentEvent.SessionStarted s) {
              sessionId.set(s.sessionId());
            }
            if (e instanceof AgentEvent.BudgetGatePending p) {
              parked.complete(p);
            }
          };
      var loopThread =
          new Thread(
              () -> service.runAgent(new AgentRequest(userMessage("search"), List.of(), 5), sink));
      loopThread.setDaemon(true);
      loopThread.start();

      parked.get(8, java.util.concurrent.TimeUnit.SECONDS);
      service.cancelSession(sessionId.get());
      loopThread.join(3000);
      assertFalse(loopThread.isAlive(), "the cancel must release the park, not wait out its timeout");

      // 1. The run ended as the reader's own stop…
      var error = lastEventOfType(events, AgentEvent.AgentError.class);
      assertNotNull(error);
      assertEquals(AgentErrorCode.CANCELLED.name(), error.errorCode());
      // 2. …and produced NO answer. This is the assertion the old behaviour fails: the budget-edge
      // finalize had a successful tool result to synthesise from, so it really would have spoken.
      assertNull(
          lastEventOfType(events, AgentEvent.AgentDone.class),
          "a run the reader stopped must not answer the question they abandoned");
    } finally {
      restoreProperty("justsearch.agent.budgetGateTimeoutSec", prev);
    }
  }

  @Test
  @DisplayName("review F1 — the same for a HELD CONTEXT GATE: the cancel releases the park")
  void cancelAtHeldContextGateTerminatesCancelled() throws Exception {
    // The cognitive sibling, and the same defect: both gates are held decisions the cancel did not
    // release. The context gate's undecided fallback is CONTINUE, so the run carried on rather than
    // finalizing — a different wrong outcome from the same root, which is why both are pinned.
    String prev = System.getProperty("justsearch.agent.contextGateTimeoutSec");
    System.setProperty("justsearch.agent.contextGateTimeoutSec", "5");
    try {
      var ai =
          new ScriptedAiService(
              List.of(
                  ScriptedResponse.toolCall("c1", "core_search", "{}").withUsage(20, 10),
                  ScriptedResponse.textOnly("the abandoned answer").withUsage(20, 10)));
      var service = buildServiceWithSmallBudget(ai, 50);

      var events = new CopyOnWriteArrayList<AgentEvent>();
      var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      var parked = new CompletableFuture<AgentEvent.ContextGatePending>();
      Consumer<AgentEvent> sink =
          e -> {
            events.add(e);
            if (e instanceof AgentEvent.SessionStarted s) {
              sessionId.set(s.sessionId());
            }
            if (e instanceof AgentEvent.ContextGatePending p) {
              parked.complete(p);
            }
          };
      var request =
          new AgentRequest(
              userMessage("search"), List.of(), 4, List.of(), null, null, null, null, List.of(),
              "thorough");
      var loopThread = new Thread(() -> service.runAgent(request, sink));
      loopThread.setDaemon(true);
      loopThread.start();

      parked.get(8, java.util.concurrent.TimeUnit.SECONDS);
      service.cancelSession(sessionId.get());
      loopThread.join(3000);
      assertFalse(loopThread.isAlive(), "the cancel must release the park, not wait out its timeout");

      var error = lastEventOfType(events, AgentEvent.AgentError.class);
      assertNotNull(error);
      assertEquals(AgentErrorCode.CANCELLED.name(), error.errorCode());
      assertNull(
          lastEventOfType(events, AgentEvent.AgentDone.class),
          "a run the reader stopped must not answer the question they abandoned");
    } finally {
      restoreProperty("justsearch.agent.contextGateTimeoutSec", prev);
    }
  }

  @Test
  @DisplayName("859 §D F1 — a RESUMED run keeps the rung it was dispatched with")
  void resumedRunKeepsItsEffortRung() {
    // The defect: resume rebuilt a fresh AgentRequest through the short constructor, which carries
    // no rung — and an absent rung IS Standard by design, so a Thorough run silently re-sized from
    // 15x to Standard with nothing anywhere saying so. Asserted as a CONSEQUENCE (the budget the resumed
    // run actually got), not by reading the meta key back.
    int thorough = budgetAfterReconstruction("resume_thorough", "thorough",
        (service, sink) -> service.resumeSession("resume_thorough", sink));
    int standard = budgetAfterReconstruction("resume_standard", "standard",
        (service, sink) -> service.resumeSession("resume_standard", sink));
    assertTrue(
        thorough > standard,
        "a resumed Thorough run must still be Thorough (got " + thorough + " vs Standard's "
            + standard + ")");
    assertEquals(
        AgentBudgetPolicy.initialBudget("thorough", 400, false),
        thorough,
        "and it must be sized by the SAME policy a fresh dispatch uses");
  }

  @Test
  @DisplayName("859 §D F1 — a FORKED run keeps the rung too (the second reconstruction site)")
  void forkedRunKeepsItsEffortRung() {
    // Two sites drifted apart by one caller forgetting a field, so both are pinned. A fork rewinds
    // to the last user turn and re-runs — same durable intent, same rung.
    int thorough = budgetAfterReconstruction("fork_thorough", "thorough",
        (service, sink) -> service.forkSession("fork_thorough", "", sink));
    assertEquals(AgentBudgetPolicy.initialBudget("thorough", 400, false), thorough);
  }

  @Test
  @DisplayName("859 §D F1 — a PRE-859 record carries no rung, and reads as Standard")
  void reconstructedRunWithoutAPersistedRungIsStandard() {
    // Absence stays meaningful: a record written before the field existed is not corrupt, it simply
    // predates the rung, and the documented fallback is exactly the right reading of it. This is why
    // the persistence change needs no schema bump.
    int absent = budgetAfterReconstruction("resume_legacy", null,
        (service, sink) -> service.resumeSession("resume_legacy", sink));
    assertEquals(AgentBudgetPolicy.initialBudget(null, 400, false), absent);
  }

  // ---------------------------------------------------------------------------
  // The SIZED raise and its narration (tempdoc 859 §D §2.5 / §3.3 T3)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("859 §D T3 — a sized raise adds exactly N, resolves CONTINUE, and is NARRATED")
  void sizedRaiseResolvesTheGateAndEmitsABudgetRaisedNote() throws Exception {
    // The raise endpoint has always been amount-bearing; what it never did was SAY anything. A run
    // that quietly acquires more room is the shape that turns per-run auto-continue into standing
    // autonomy, so the note is the guard rail (859 §D P3), not decoration.
    String prev = System.getProperty("justsearch.agent.budgetGateTimeoutSec");
    System.setProperty("justsearch.agent.budgetGateTimeoutSec", "10"); // the gate must HOLD
    try {
      var ai =
          new ScriptedAiService(
              List.of(
                  ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                  ScriptedResponse.textOnly("Answer after the raise").withUsage(50, 20)));
      var service = buildServiceWithSmallBudget(ai, 250); // Standard (8x) ⇒ 2000, over-spent to -100

      var events = new CopyOnWriteArrayList<AgentEvent>();
      var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      var parked = new CompletableFuture<AgentEvent.BudgetGatePending>();
      Consumer<AgentEvent> sink =
          e -> {
            events.add(e);
            if (e instanceof AgentEvent.SessionStarted s) {
              sessionId.set(s.sessionId());
            }
            if (e instanceof AgentEvent.BudgetGatePending p) {
              parked.complete(p);
            }
          };
      var loopThread =
          new Thread(() -> service.runAgent(new AgentRequest(userMessage("search"), List.of(), 5), sink));
      loopThread.setDaemon(true);
      loopThread.start();

      var gate = parked.get(8, java.util.concurrent.TimeUnit.SECONDS);
      int remainingAtGate = gate.tokensRemaining();
      final int raise = 17_500;
      assertTrue(service.raiseSessionBudget(sessionId.get(), raise), "the raise reached the session");
      loopThread.join(8000);

      // 1. The raise ADDED EXACTLY N — read from a wire event, not from an accessor. The resumed
      // iteration proceeds without a fresh iteration_start (CONTINUE falls through INTO the same
      // iteration), so the next figure the run publishes is the post-call llm_response one.
      var afterGate =
          eventsOfType(events, AgentEvent.AgentBudgetUpdate.class).stream()
              .filter(u -> "llm_response".equals(u.phase()))
              .toList();
      var resumed = afterGate.get(afterGate.size() - 1);
      final int finalizeCallSpend = 50 + 20; // the scripted usage of the post-raise call
      assertEquals(
          remainingAtGate + raise - finalizeCallSpend,
          resumed.tokensRemaining(),
          "the resumed call must see exactly the granted amount more than the gate did — no more"
              + " (a re-derived ceiling) and no less (a silently clamped grant)");

      // 2. The gate resolved CONTINUE — the run carried on and answered rather than finalizing.
      var done = lastEventOfType(events, AgentEvent.AgentDone.class);
      assertNotNull(done);
      assertEquals("Answer after the raise", done.finalResponse());

      // 3. And it SAID SO, naming the amount.
      var note =
          eventsOfType(events, AgentEvent.AgentProgress.class).stream()
              .filter(p -> "budget_raised".equals(p.phase()))
              .findFirst()
              .orElse(null);
      assertNotNull(note, "a resumed run must narrate the raise it resumed on");
      assertTrue(
          note.message().contains("17,500") || note.message().contains("17500"),
          "the note must name the amount it granted, got: " + note.message());
    } finally {
      restoreProperty("justsearch.agent.budgetGateTimeoutSec", prev);
    }
  }

  @Test
  @DisplayName("859 §D F5 — a MID-RUN raise (no gate held) is narrated at the next step boundary")
  void midRunRaiseIsNarratedToo() throws Exception {
    // The half of D7 the first pass missed: raising the budget while the run is NOT parked makes
    // `resolveBudgetGate` a no-op, so the gate's CONTINUE branch never fires and the grant landed in
    // total silence — the run just kept going with more room and nothing said so. That silence is
    // exactly what the narration exists to remove.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("call_2", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.textOnly("Done").withUsage(20, 10)));
    var service = buildServiceWithSmallBudget(ai, 4000); // Standard (8x) ⇒ 32,000; never exhausted

    var events = new CopyOnWriteArrayList<AgentEvent>();
    var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
    var raised = new java.util.concurrent.atomic.AtomicBoolean(false);
    var finished = new CompletableFuture<Boolean>();
    Consumer<AgentEvent> sink =
        e -> {
          events.add(e);
          if (e instanceof AgentEvent.SessionStarted s) {
            sessionId.set(s.sessionId());
          }
          // Raise ONCE, from inside the stream, while the run is healthy and unparked — the exact
          // condition under which the old code said nothing.
          if (e instanceof AgentEvent.ToolExecutionCompleted && raised.compareAndSet(false, true)) {
            service.raiseSessionBudget(sessionId.get(), 12_345);
          }
          if (e instanceof AgentEvent.AgentDone || e instanceof AgentEvent.AgentError) {
            finished.complete(true);
          }
        };
    var loopThread =
        new Thread(() -> service.runAgent(new AgentRequest(userMessage("search"), List.of(), 5), sink));
    loopThread.setDaemon(true);
    loopThread.start();
    assertTrue(finished.get(8, java.util.concurrent.TimeUnit.SECONDS), "the run completes");
    loopThread.join(4000);

    assertTrue(raised.get(), "the test really did raise the budget mid-run");
    var notes =
        eventsOfType(events, AgentEvent.AgentProgress.class).stream()
            .filter(p -> "budget_raised".equals(p.phase()))
            .toList();
    assertEquals(1, notes.size(), "exactly one note per grant — drained, never announced twice");
    assertTrue(
        notes.get(0).message().contains("12,345"),
        "and it names the amount granted, got: " + notes.get(0).message());
    // No gate was involved: this is the mid-run path, not the parked one.
    assertTrue(
        eventsOfType(events, AgentEvent.BudgetGatePending.class).isEmpty(),
        "the run was never parked — so the gate's CONTINUE branch cannot be what narrated this");
  }

  // ---------------------------------------------------------------------------
  // Terminal disposition on the wire (tempdoc 859 §D §2.6 / §3.3 T4)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("859 §D T4 — a normal completion declares COMPLETED")
  void normalCompletionDeclaresItsDisposition() {
    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("A complete answer.")));
    var events = run(buildService(ai, new StubTool("search", RiskTier.LOW, "r")), userMessage("hi"), 3);
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals("COMPLETED", done.disposition());
  }

  @Test
  @DisplayName("859 §D T4 — a budget-edge finalize declares BUDGET_EDGE_FINALIZE, whatever it wrote")
  void budgetEdgeFinalizeDeclaresItsDisposition() {
    // THE fail-closed guarantee. The scripted answer below is deliberately confident and
    // complete-sounding and says nothing about being cut short — exactly the 859 §7 failure. The
    // badge fires anyway, because the disposition is written by the loop after and independently of
    // the model's text. A model cannot talk its way out of this.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("call_1", "core_search", "{}").withUsage(100, 2000),
                ScriptedResponse.textOnly("Here is the complete and thorough answer to your question.")));
    var events = run(buildServiceWithSmallBudget(ai, 250), userMessage("search"), 5);
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done);
    assertEquals(
        "BUDGET_EDGE_FINALIZE",
        done.disposition(),
        "the truncation is declared structurally, not left to the answer's own wording");
    assertFalse(
        done.finalResponse().toLowerCase(java.util.Locale.ROOT).contains("cut short"),
        "and the guarantee must not be resting on the model having said it");
  }

  @Test
  @DisplayName("859 §D T4 — hitting the iteration ceiling declares MAX_ITERATIONS")
  void iterationCeilingDeclaresItsDisposition() {
    // The other truncating terminal. Closing this in the same PR was the point — the FE derives
    // cut-short from both values, so leaving one unstamped would have left the same hole under a
    // different name.
    //
    // Tempdoc 878 §D.1 — this used to say the ceiling "produces no answer text at all", and the
    // empty-response assertion below rested on that. It no longer does: the ceiling now makes one
    // no-tools synthesis call. THIS FIXTURE deliberately exercises the ANSWERLESS arm — the
    // two-response script is exhausted by the time the finalize runs, so the call fails and the
    // terminal falls back to exactly the pre-878 behaviour. That is the fail-open floor, and
    // asserting it here is what keeps the floor from silently rising.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}"),
                ScriptedResponse.toolCall("c2", "core_search", "{}")));
    var events = run(buildService(ai, new StubTool("search", RiskTier.LOW, "r")), userMessage("loop"), 2);
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the ceiling terminal still emits a done");
    assertEquals("MAX_ITERATIONS", done.disposition());
    assertEquals(
        "",
        done.finalResponse(),
        "the finalize could not run (script exhausted), so the terminal is answerless — and the"
            + " disposition is stamped anyway, which is the whole fail-closed guarantee");
  }

  // ---------------------------------------------------------------------------
  // The context gate RE-ARMS (tempdoc 859 §D §2.7 / §3.3 T6)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("859 §D T6 — a SECOND context crossing auto-compacts, narrated, with no second park")
  void contextGateReArmsAsAutoCompactionRatherThanASecondPark() {
    // At 1x this was unreachable: the budget wall always arrived first, which is what the retired
    // rationale on AgentSession.contextGateFired asserted. At the effort multipliers it is
    // reachable, and doing nothing would let the prompt grow past n_ctx — voiding the structural
    // bound Thorough is justified by. The gate still ASKS once; later crossings are re-applied.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c2", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c3", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c4", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c5", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c6", "core_search", "{}").withUsage(20, 10),
                ScriptedResponse.toolCall("c7", "core_search", "{}").withUsage(20, 10)));
    // n_ctx 50 ⇒ pressure threshold 40, and countPromptTokens = messages.size() * 10, so every
    // iteration from the 4-message mark onward crosses. Each tool round adds 2 messages, and
    // CONTEXT_COMPACT_KEEP_RECENT is 6, so a compaction only has something to drop from the
    // 10-message mark — hence seven rounds rather than three. Thorough ⇒ budget 750, which the ~210
    // total spend never reaches: the CONTEXT axis is the only one that can act here.
    var service = buildServiceWithSmallBudget(ai, 50);
    var request =
        new AgentRequest(
            userMessage("search"), List.of(), 7, List.of(), null, null, null, null, List.of(),
            "thorough");
    var events = runWithRequest(service, request);

    assertEquals(
        1,
        eventsOfType(events, AgentEvent.ContextGatePending.class).size(),
        "the gate ASKS exactly once per run — re-parking is what 577 was right to avoid");
    assertTrue(
        eventsOfType(events, AgentEvent.ContextCompacted.class).size() >= 2,
        "but every later crossing must still be ACTED on: "
            + eventsOfType(events, AgentEvent.ContextCompacted.class).size() + " compactions");
    assertTrue(
        eventsOfType(events, AgentEvent.AgentProgress.class).stream()
            .anyMatch(p -> "context_gate_reapplied".equals(p.phase())),
        "and the run must SAY it re-applied the decision — bounded autonomy, never silent");
    // Tempdoc 859 §D (F6 follow-up) — the FIRST crossing parks, nobody answers (the test JVM sets
    // contextGateTimeoutSec=0), and the run proceeds on its own. That is a silent continue by the
    // same definition the budget raise is one, and it narrated NOTHING before this.
    assertTrue(
        eventsOfType(events, AgentEvent.AgentProgress.class).stream()
            .anyMatch(p -> "context_gate_unanswered".equals(p.phase())),
        "an UNANSWERED gate that proceeds must say so too — it is the same guard rail");
    // The compaction note names HOW MUCH it dropped: "history was shortened" without the number is
    // not an accountability record, by the same standard the raise note is held to.
    assertTrue(
        eventsOfType(events, AgentEvent.AgentProgress.class).stream()
            .filter(p -> "context_compacted".equals(p.phase()))
            .allMatch(p -> p.message().matches("Compacted \\d+ earlier turns? .*")),
        "every compaction note must carry its dropped count");
  }

  @Test
  @DisplayName("859 §D F6 — an ANSWERED context gate does NOT emit the unanswered note")
  void answeredContextGateEmitsNoUnansweredNote() throws Exception {
    // The discriminating half: the note must key on the TIMEOUT fallback, not merely on the gate
    // having been opened. Held long enough that the resolve below is what ends the wait — with
    // timeout 0 the gate falls straight through and this test would pass for the wrong reason.
    String prev = System.getProperty("justsearch.agent.contextGateTimeoutSec");
    System.setProperty("justsearch.agent.contextGateTimeoutSec", "10");
    try {
      var ai =
          new ScriptedAiService(
              List.of(
                  ScriptedResponse.toolCall("c1", "core_search", "{}").withUsage(20, 10),
                  ScriptedResponse.textOnly("done").withUsage(20, 10)));
      var service = buildServiceWithSmallBudget(ai, 50);

      var events = new CopyOnWriteArrayList<AgentEvent>();
      var sessionId = new java.util.concurrent.atomic.AtomicReference<String>();
      var parked = new CompletableFuture<AgentEvent.ContextGatePending>();
      Consumer<AgentEvent> sink =
          e -> {
            events.add(e);
            if (e instanceof AgentEvent.SessionStarted s) {
              sessionId.set(s.sessionId());
            }
            if (e instanceof AgentEvent.ContextGatePending p) {
              parked.complete(p);
            }
          };
      var request =
          new AgentRequest(
              userMessage("search"), List.of(), 4, List.of(), null, null, null, null, List.of(),
              "thorough");
      var loopThread = new Thread(() -> service.runAgent(request, sink));
      loopThread.setDaemon(true);
      loopThread.start();

      parked.get(8, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(
          service.resolveContextGate(sessionId.get(), "continue"),
          "the decision reached the session — otherwise the gate times out and this asserts nothing");
      loopThread.join(8000);

      assertTrue(
          eventsOfType(events, AgentEvent.AgentProgress.class).stream()
              .noneMatch(p -> "context_gate_unanswered".equals(p.phase())),
          "the reader answered — the run did not decide for itself, so it must not say it did");
    } finally {
      restoreProperty("justsearch.agent.contextGateTimeoutSec", prev);
    }
  }

  @Test
  @DisplayName("859 §D T6(c) — the trigger reads the REPORTED prompt, not just the projection")
  void contextPressureTriggerUsesTheReportedPromptSize() {
    // countPromptTokens is schema-blind and ~40% low (577). Here the projection stays well under
    // the threshold while the provider-reported prompt is far over it — the exact case where the
    // old trigger fired only after the real prompt had already exceeded the window, i.e. after the
    // damage. Projection = messages.size() * 10 = 20..40; threshold = 0.8 * 200 = 160.
    //
    // The reported prompt is 175, deliberately NOT 190: D4's unservable line is 0.95 * 200 = 190, so
    // a 190 here would sit exactly ON it and this test would be exercising the compact-before-
    // continue path while still being named for the trigger. 175 clears the 0.8 trigger it is about
    // and stays clear of the 0.95 one it is not.
    var ai =
        new ScriptedAiService(
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}").withUsage(175, 10),
                ScriptedResponse.textOnly("Done")));
    var service = buildServiceWithSmallBudget(ai, 200); // thorough ⇒ budget 3000, never binding
    var request =
        new AgentRequest(
            userMessage("search"), List.of(), 3, List.of(), null, null, null, null, List.of(),
            "thorough");
    var events = runWithRequest(service, request);

    assertFalse(
        eventsOfType(events, AgentEvent.ContextGatePending.class).isEmpty(),
        "a reported prompt of 175 against a 200-token window IS context pressure, even though the"
            + " projection (max 40) never sees it");
  }

  @Test
  @DisplayName("859 D4 — CONTINUE into an UNSERVABLE prompt compacts first, and says so")
  void continueAtAnUnservableCrossingCompactsBeforeCallingTheModel() {
    // THE LIVE DEFECT (2026-08-25, L5 attempt 1). A run with a large opening message crossed the
    // context gate; the reader's CONTINUE was honoured literally, the loop called the provider with
    // a prompt of 4172 against a 4096 window, llama-server 400'd three times, and the run ERRORED —
    // so §2.7's auto-compacting SECOND crossing never got its chance. CONTINUE means "don't stop";
    // continuing into a guaranteed rejection is not continuing.
    //
    // The fixture reproduces the mechanism, not the numbers: `countPromptTokens` under-counts (577),
    // so the loop can hold a projection it believes is fine while the REAL prompt is already over.
    //   window 120 ⇒ pressure at 96, unservable at 114
    //   projection = messages * 10, real (and reported) = messages * 15
    //   iterations reach the model at 2, 4, 6, 8 messages (real 30/60/90/120 — all servable),
    //   and the 5th sees pressure 120 (the REPORTED prompt) ⇒ gate ⇒ CONTINUE (0 s test timeout)
    //   ⇒ without the fix the call goes out at 10 messages = real 150 > 120 and is REFUSED.
    var ai =
        new WindowBoundAiService(
            120,
            List.of(
                ScriptedResponse.toolCall("c1", "core_search", "{}"),
                ScriptedResponse.toolCall("c2", "core_search", "{}"),
                ScriptedResponse.toolCall("c3", "core_search", "{}"),
                ScriptedResponse.toolCall("c4", "core_search", "{}"),
                ScriptedResponse.textOnly("Answered after compacting to fit")));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));
    var request =
        new AgentRequest(
            userMessage("a large opening task"), List.of(), 6, List.of(), null, null, null, null,
            List.of(), "thorough");
    var events = runWithRequest(service, request);

    // 1. The gate DID fire — otherwise this test would be passing without exercising the path.
    var gate = lastEventOfType(events, AgentEvent.ContextGatePending.class);
    assertNotNull(gate, "the run must actually reach the context gate for this test to mean anything");
    // 859 D3, same event: the gate SHOWS the quantity it triggered on (the reported prompt), not the
    // projection that would have read 100 against a 120 window and justified nothing.
    assertEquals(
        120,
        gate.promptTokens(),
        "the gate must publish the pressure figure it fired on, not the schema-blind projection");

    // 2. It compacted rather than calling into the refusal, and SAID so — the reader chose CONTINUE,
    // so a departure from that has to be narrated, not silently substituted.
    assertFalse(
        eventsOfType(events, AgentEvent.ContextCompacted.class).isEmpty(),
        "an unservable CONTINUE must compact before the call");
    assertTrue(
        eventsOfType(events, AgentEvent.AgentProgress.class).stream()
            .anyMatch(p -> "context_compacted_to_fit".equals(p.phase())),
        "and it must say it compacted to fit — bounded autonomy, never silent");

    // 3. THE OUTCOME THAT MATTERS: no 400, and a real answer.
    assertTrue(
        ai.refusals.isEmpty(),
        "the provider must never have been handed a prompt it would refuse, got: " + ai.refusals);
    var error = lastEventOfType(events, AgentEvent.AgentError.class);
    assertNull(error, "the run must not error — it errored live, three refusals deep");
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "the run completes");
    assertEquals("Answered after compacting to fit", done.finalResponse());
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  // ---------------------------------------------------------------------------
  // Budget bypass for forced-tool-call turns (E0a and DECIDING)
  // ---------------------------------------------------------------------------

  @Test
  void budgetExhausted_e0aBypassesFinalizeAndProceedsToLlmCall() {
    // Scenario: PRIMARY hands off after consuming nearly all the token budget.
    // At the Organizer's FIRST turn (E0a), budget is exhausted (projected >= remaining).
    // Expected: E0a bypass skips attemptBudgetEdgeFinalize; Organizer still gets its LLM call
    // with tool_choice=required rather than a finalize call with empty tools.
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        // PRIMARY: handoff_to_organizer, consuming enough tokens to exhaust budget
        ScriptedResponse.toolCall("hc-1", "handoff_to_organizer", "{\"reason\":\"doc.md\"}")
            .withUsage(35, 1600), // 1635 tokens → remaining = 1600 - 1635 = -35
        // Organizer E0a: ingest_files (should be called, NOT replaced by finalize)
        ScriptedResponse.toolCall("ic-1", "core_ingest_files", "{\"paths\":[\"doc.md\"]}"),
        // Organizer done
        ScriptedResponse.textOnly("Ingested doc.md."));
    // Tempdoc 859 §D §2.1 — contextWindow=200, no rung named ⇒ Standard 8x ⇒ budget = 1600.
    //
    // THE FIXTURE HAS TO BE RE-FITTED WHENEVER THE MULTIPLIER MOVES, and this test is the reason
    // that is not a chore: at the pre-L1 5x it read `contextWindow=290 ⇒ budget 1450` against a
    // 1485-token spend, and at 8x that same window bought 2320 — so `budgetExhausted` was FALSE, the
    // E0a branch was never entered, and the test passed while proving nothing. Deleting the
    // `shouldForceToolCall` guard from AgentStepRunner left it GREEN. The window is therefore chosen
    // so `window * multiplier` is exact (200 * 8 = 1600) and the spend clears it by a visible margin.
    //
    // PRIMARY consumes 1635 → remaining = -35 (the spend rides the completion axis so the
    // context-pressure gate stays out of this test — the reported prompt of 35 is far below both
    // 0.8*200=160 and D4's 0.95*200=190; see budgetEdgeFinalize_synthesizesFromToolResults).
    // Organizer's iteration_start: projected = messages.size() * 10 = some positive value >= -35
    // → budgetExhausted=true, but shouldForceToolCall=true → bypass → Organizer LLM call fires.
    var service = buildServiceWithSmallBudgetAndProfiles(ai, 200);
    var request = new AgentRequest(
        userMessage("Ingest doc.md"), List.<String>of(), 10, profiles, "primary");
    runWithRequest(service, request);

    // Verify: 3 LLM calls made (PRIMARY, Organizer E0a, Organizer done) — NOT 2 (PRIMARY, finalize)
    assertEquals(3, ai.recordedSampling.size(),
        "Should make 3 LLM calls; budget-edge finalize should be bypassed for E0a");
    // Verify: Organizer's E0a call has tool_choice=required (not the default null of finalize)
    assertEquals("required", ai.recordedSampling.get(1).toolChoice(),
        "Organizer E0a call must use tool_choice=required, not finalize's null");
    // Verify: Organizer's E0a call has non-empty tools (not finalize's List.of())
    assertFalse(ai.recordedTools.get(1).isEmpty(),
        "Organizer E0a call must receive tool list; finalize uses empty List.of()");
    // Direction 7a: E0a tool list contains ingest_files but excludes search/browse tools.
    var e0aToolNames = ai.recordedTools.get(1).stream()
        .map(t -> getToolFunctionName(t))
        .toList();
    assertTrue(e0aToolNames.contains("core_ingest_files"),
        "E0a must include core_ingest_files");
    assertFalse(
        e0aToolNames.stream().anyMatch(
            n -> n.equals("core_search_index") || n.equals("core_browse_folders") || n.equals("core_file_operations")),
        "E0a must not include search or browse tools");
    // Direction D: E0a suppresses thinking.
    assertEquals(false, ai.recordedSampling.get(1).enableThinking(),
        "Organizer E0a call must suppress thinking (enableThinking=false)");
  }

  @Test
  void budgetExhausted_decidingBypassesFinalizeAndProceedsToLlmCall() {
    // Scenario: PRIMARY performs 3 searches (triggering DECIDING after the 3rd), with
    // budget calibrated so that it is exhausted at the DECIDING iteration_start check.
    // Expected: DECIDING bypass skips attemptBudgetEdgeFinalize; PRIMARY still gets its
    // forced-handoff LLM call with tool_choice=required.
    //
    // Budget math (contextWindow=500 → budget=244):
    //   Search 1: usage(45,5)=50 → remaining=194. Iter 2 projected=~20 < 194 → OK
    //   Search 2: usage(55,5)=60 → remaining=134. Iter 3 projected=~40 < 134 → OK
    //   Search 3: usage(95,5)=100 → remaining=34. DECIDING projected=~40 >= 34 → exhausted!
    //   DECIDING bypass fires → handoff call proceeds despite budget exhaustion.
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("sc-1", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(45, 5),
        ScriptedResponse.toolCall("sc-2", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(55, 5),
        ScriptedResponse.toolCall("sc-3", "core_search_index", "{\"query\":\"doc\"}")
            .withUsage(95, 5),
        // PRIMARY DECIDING call (budget exhausted but bypass active): forced handoff
        ScriptedResponse.toolCall("hc-1", "handoff_to_organizer", "{\"reason\":\"doc.md\"}"),
        // Organizer E0a + done
        ScriptedResponse.toolCall("ic-1", "core_ingest_files", "{\"paths\":[\"doc.md\"]}"),
        ScriptedResponse.textOnly("Done."));
    var service = buildServiceWithSmallBudgetAndProfiles(ai, 500);
    var request = new AgentRequest(
        userMessage("Ingest doc.md"), List.<String>of(), 10, profiles, "primary");
    runWithRequest(service, request);

    // Verify: 6 LLM calls (3 searches + DECIDING handoff + Org E0a + Org done)
    // NOT 4 (3 searches + finalize-that-replaces-DECIDING + truncated)
    assertEquals(6, ai.recordedSampling.size(),
        "Should make 6 LLM calls; budget-edge finalize must be bypassed for DECIDING state");
    // Verify: DECIDING call (index 3) has tool_choice=required
    assertEquals("required", ai.recordedSampling.get(3).toolChoice(),
        "DECIDING call must use tool_choice=required, not finalize's null");
    // Direction D: DECIDING state suppresses thinking.
    assertEquals(false, ai.recordedSampling.get(3).enableThinking(),
        "DECIDING call must suppress thinking (enableThinking=false)");
  }

  // ---------------------------------------------------------------------------
  // OTel span export (OBS-005)
  // ---------------------------------------------------------------------------

  @Test
  void otelSpansExported_whenTracingBootstrapActive() throws Exception {
    // Reset global OTel state so we can install a real SDK
    io.opentelemetry.api.GlobalOpenTelemetry.resetForTest();
    try {
      // TracingBootstrap.close() calls tracerProvider.close() which synchronously flushes
      // all pending spans via BatchSpanProcessor.shutdown() — no Thread.sleep needed.
      var bootstrapResource = new io.justsearch.telemetry.TracingBootstrap(tempDir);
      try (bootstrapResource) {
        var ai = new ScriptedAiService(List.of(
            ScriptedResponse.toolCall("call_1", "core_search", "{\"query\":\"test\"}")
                .withUsage(100, 50),
            ScriptedResponse.textOnly("Done").withUsage(80, 30)));
        var service = buildService(ai, new StubTool("search", RiskTier.LOW, "results"));

        run(service, userMessage("test otel"), 5);
      }

      String traces = java.nio.file.Files.readString(
          tempDir.resolve("telemetry").resolve("traces.ndjson"));

      // Parse NDJSON lines into JSON objects for structural verification
      var mapper = new tools.jackson.databind.ObjectMapper();
      var spans = new HashMap<String, tools.jackson.databind.JsonNode>();
      for (String line : traces.strip().split("\n")) {
        var node = mapper.readTree(line);
        spans.put(node.get("name").asText(), node);
      }

      // Verify all 4 expected spans are present (2 chat spans: one per LLM call)
      assertTrue(spans.containsKey("invoke_agent primary"), "Should export invoke_agent span");
      assertTrue(spans.containsKey("chat"), "Should export chat span");
      assertTrue(spans.containsKey("execute_tool core_search"), "Should export execute_tool span");

      // Verify shared trace_id across all spans
      String traceId = spans.get("invoke_agent primary").get("trace_id").asText();
      for (var entry : spans.entrySet()) {
        assertEquals(traceId, entry.getValue().get("trace_id").asText(),
            "Span '" + entry.getKey() + "' should share the same trace_id");
      }

      // Verify hierarchy: root span has "0000000000000000" parent
      String rootParent = spans.get("invoke_agent primary").get("parent_span_id").asText();
      assertEquals("0000000000000000", rootParent,
          "Root invoke_agent span should have zero parent_span_id");

      // Verify child spans have the root span's span_id as parent
      String rootSpanId = spans.get("invoke_agent primary").get("span_id").asText();
      assertEquals(rootSpanId, spans.get("chat").get("parent_span_id").asText(),
          "chat span should be child of invoke_agent");
      assertEquals(rootSpanId, spans.get("execute_tool core_search").get("parent_span_id").asText(),
          "execute_tool span should be child of invoke_agent");

      // Verify gen_ai attributes
      assertEquals("invoke_agent",
          spans.get("invoke_agent primary").get("attrs").get("gen_ai.operation.name").asText());
      assertEquals("primary",
          spans.get("invoke_agent primary").get("attrs").get("gen_ai.agent.id").asText());
      assertEquals("core_search",
          spans.get("execute_tool core_search").get("attrs").get("gen_ai.tool.name").asText());

      // Verify span status reflects success
      assertEquals("OK", spans.get("invoke_agent primary").get("status").asText(),
          "Successful agent run should have OK status");
      assertEquals("OK", spans.get("chat").get("status").asText(),
          "Successful chat should have OK status");
    } finally {
      // Restore noop state so other tests are unaffected
      io.opentelemetry.api.GlobalOpenTelemetry.resetForTest();
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private static List<Map<String, Object>> userMessage(String text) {
    return List.of(Map.of("role", "user", "content", text));
  }

  private static String largeToolOutput(String prefix) {
    var sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append(prefix)
          .append(" result line ")
          .append(i)
          .append(" path /abs/path/")
          .append(i)
          .append('\n');
    }
    return sb.toString();
  }

  private static void withSysProps(Map<String, String> values, Runnable assertion) {
    var original = new HashMap<String, String>();
    for (var entry : values.entrySet()) {
      original.put(entry.getKey(), System.getProperty(entry.getKey()));
      System.setProperty(entry.getKey(), entry.getValue());
    }
    // Set up ConfigStore so migrated code reads from the ordinal chain
    ConfigStore prev = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
    try {
      assertion.run();
    } finally {
      for (var entry : original.entrySet()) {
        if (entry.getValue() == null) {
          System.clearProperty(entry.getKey());
        } else {
          System.setProperty(entry.getKey(), entry.getValue());
        }
      }
      TestResolvedConfigHelper.restoreGlobal(prev);
    }
  }

  /**
   * Tempdoc 834 §1.3 — installs the REAL run observation substrate on a test service. The
   * session-local hub is gone, so without this a run has no journal, no replay and no second
   * observer; a test double for evict-on-throw + bounded replay would be a reimplementation of the
   * exact mechanism the attach and park tests exist to pin.
   */
  // Package-private (tempdoc 875 Move 3): AgentToolAuthorityBoundaryTest reuses this harness
  // rather than standing up a parallel one.
  static AgentLoopService observed(AgentLoopService service) {
    service.setRunObservation(
        new io.justsearch.app.observability.stream.run.RunChannelObservation(
            new io.justsearch.app.observability.stream.run.RunChannelRegistry()));
    return service;
  }

  private static AgentLoopService buildService(OnlineAiService ai, StubTool... tools) {
    return observed(new AgentLoopService(
        ai,
        stubCatalog(tools),
        stubExecutor(tools),
        stubEmitter(),
        null,
        null,
        null,
        null));
  }

  private static AgentLoopService buildServiceWithAgentTelemetry(
      OnlineAiService ai, AgentTelemetry agentTelemetry, StubTool... tools) {
    return observed(AgentLoopService.forTesting(
        ai,
        stubCatalog(tools),
        stubExecutor(tools),
        stubEmitter(),
        null,
        null,
        null,
        agentTelemetry));
  }

  private static AgentLoopService buildServiceWithSmallBudgetAndProfiles(
      ScriptedAiService baseAi, int contextWindow) {
    // Create a wrapper that delegates to baseAi but overrides the context window size.
    // Tempdoc 491 §C5: streamSummary + streamAnswer overrides removed (deleted from interface).
    var aiWithSmallContext = new OnlineAiService() {
      @Override
      public CompletableFuture<String> summarize(String content) {
        return baseAi.summarize(content);
      }

      @Override
      public CompletableFuture<String> askQuestion(String question, String context) {
        return baseAi.askQuestion(question, context);
      }

      @Override
      public boolean isAvailable() {
        return baseAi.isAvailable();
      }

      @Override
      public boolean isStartingUp() {
        return baseAi.isStartingUp();
      }

      @Override
      public void streamChatWithTools(
          List<Map<String, Object>> messages,
          List<Map<String, Object>> tools,
          int maxTokens,
          StreamCallbacks callbacks,
          SamplingParams sampling) {
        baseAi.streamChatWithTools(messages, tools, maxTokens, callbacks, sampling);
      }

      @Override
      public Optional<Integer> countPromptTokens(
          List<Map<String, Object>> messages) {
        return baseAi.countPromptTokens(messages);
      }

      @Override
      public Integer llmContextTokens() {
        return contextWindow;
      }

      @Override
      public Integer configuredContextTokens() {
        return contextWindow;
      }
    };
    var searchTool = new StubTool("search_index", RiskTier.LOW, "results");
    var ingestTool = new StubTool("ingest_files", RiskTier.LOW, "ok");
    return observed(new AgentLoopService(
        aiWithSmallContext,
        stubCatalog(searchTool, ingestTool),
        stubExecutor(searchTool, ingestTool),
        stubEmitter(),
        null,
        null,
        null,
        null));
  }

  private static AgentLoopService buildServiceWithSmallBudget(
      ScriptedAiService baseAi, int contextWindow) {
    var searchTool = new StubTool("search", RiskTier.LOW, "results");
    return observed(new AgentLoopService(
        smallContextAi(baseAi, contextWindow),
        stubCatalog(searchTool),
        stubExecutor(searchTool),
        stubEmitter(),
        null,
        null,
        null,
        null));
  }

  /**
   * Tempdoc 859 §D T2 — a throwaway service at a given context window, one per rung, so the four
   * dispatches in {@code effortRungReachesTheBudgetPolicy} cannot contaminate each other.
   */
  private static AgentLoopService freshBudgetService(int contextWindow) {
    return buildServiceWithSmallBudget(
        new ScriptedAiService(List.of(ScriptedResponse.textOnly("answered"))), contextWindow);
  }

  /** The {@link OnlineAiService} wrapper: delegates everything, but pins the context window. */
  private static OnlineAiService smallContextAi(ScriptedAiService baseAi, int contextWindow) {
    // Create wrapper that delegates to baseAi but overrides context window methods.
    // Tempdoc 491 §C5: streamSummary + streamAnswer overrides removed.
    return new OnlineAiService() {
      @Override
      public CompletableFuture<String> summarize(String content) {
        return baseAi.summarize(content);
      }

      @Override
      public CompletableFuture<String> askQuestion(String question, String context) {
        return baseAi.askQuestion(question, context);
      }

      @Override
      public boolean isAvailable() {
        return baseAi.isAvailable();
      }

      @Override
      public boolean isStartingUp() {
        return baseAi.isStartingUp();
      }

      @Override
      public void streamChatWithTools(
          List<Map<String, Object>> messages,
          List<Map<String, Object>> tools,
          int maxTokens,
          StreamCallbacks callbacks,
          SamplingParams sampling) {
        baseAi.streamChatWithTools(messages, tools, maxTokens, callbacks, sampling);
      }

      @Override
      public Optional<Integer> countPromptTokens(
          List<Map<String, Object>> messages) {
        return baseAi.countPromptTokens(messages);
      }

      @Override
      public Integer llmContextTokens() {
        return contextWindow;
      }

      @Override
      public Integer configuredContextTokens() {
        return contextWindow;
      }
    };
  }

  private static List<AgentEvent> run(
      AgentLoopService service, List<Map<String, Object>> messages, int maxIterations) {
    var request = new AgentRequest(messages, List.of(), maxIterations);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add);
    return events;
  }

  private static List<AgentEvent> runWithRequest(AgentLoopService service, AgentRequest request) {
    var events = new CopyOnWriteArrayList<AgentEvent>();
    service.runAgent(request, events::add);
    return events;
  }

  private static String getToolFunctionName(Map<String, Object> tool) {
    return ((Map<String, Object>) tool.get("function")).get("name").toString();
  }

  // ===========================================================================
  // Tempdoc 876 §B.2b — the offering re-evaluates within a run, monotonically
  // ===========================================================================

  @Test
  @DisplayName("876 B.2b: a tool that becomes available mid-run reaches the model")
  void offeringGrowsMidRun_newToolIsAdopted() {
    var search = new StubTool("search", RiskTier.LOW, "hit");
    var read = new StubTool("read_document", RiskTier.LOW, "text");
    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("c1", "core_search", "{}"),
            ScriptedResponse.textOnly("done"));
    // Iteration 1 sees only core_search; by iteration 2 the backend behind core_read_document has
    // recovered (this is the Worker-restart-mid-run case the once-per-run emit could never see).
    var emitter =
        new ScriptedOfferingEmitter(
            List.of(Set.of("core_search"), Set.of("core_search", "core_read_document")));
    var service = serviceWithEmitter(ai, emitter, search, read);

    run(service, userMessage("Read the file"), 5);

    assertEquals(2, ai.recordedTools.size(), "expected a two-iteration run");
    assertTrue(
        emitter.emitCalls.get() >= 2,
        // Tempdoc 875 added a SECOND emit consumer — AgentStepRunner authorizes a tool call
        // against offeredWireNames(), which projects emit(). So the exact total is now a
        // function of another workstream's dispatch checks, not of this one's re-evaluation.
        // A LOWER BOUND still witnesses what 876 B.2b is about: 1 would mean the offering was
        // sampled once per run. What the model actually saw each iteration is asserted below,
        // and that is the sharper check.
        "the offering must be re-evaluated per iteration, not sampled once per run; emits="
            + emitter.emitCalls.get());
    assertEquals(List.of("core_search"), toolNamesOf(ai.recordedTools.get(0)),
        "iteration 1 sees the t=0 offering");
    assertEquals(
        List.of("core_search", "core_read_document"),
        toolNamesOf(ai.recordedTools.get(1)),
        "iteration 2 must see the recovered tool, in catalog order");
    // Wholesale replacement, not an append: EVERY entry comes from ONE fresh emit. An append would
    // leave core_search stamped with the FIRST emit's sequence number while the newcomer carried a
    // later one, so a mixed list is the failure this detects.
    //
    // Asserted as "all equal, and later than iteration 1's" rather than "== 2": tempdoc 875 added a
    // second emit consumer (AgentStepRunner authorizes a tool call through offeredWireNames, which
    // projects emit), so the adopting emit's ordinal is no longer a fixed number. The invariant —
    // one emit produced the whole list — is what this is about, and it is now stated directly.
    Object firstSeq = ai.recordedTools.get(0).get(0).get(ScriptedOfferingEmitter.EMIT_SEQ);
    Object adoptedSeq = ai.recordedTools.get(1).get(0).get(ScriptedOfferingEmitter.EMIT_SEQ);
    assertNotEquals(
        firstSeq, adoptedSeq, "iteration 2 must be served by a LATER emit than iteration 1");
    for (Map<String, Object> tool : ai.recordedTools.get(1)) {
      assertEquals(
          adoptedSeq,
          tool.get(ScriptedOfferingEmitter.EMIT_SEQ),
          "adoption replaces the whole list from one emit: " + getToolFunctionName(tool));
    }
  }

  @Test
  @DisplayName("876 B.2b: a tool that becomes unavailable mid-run does NOT disappear")
  void offeringShrinksMidRun_toolStaysOffered() {
    var search = new StubTool("search", RiskTier.LOW, "hit");
    var read = new StubTool("read_document", RiskTier.LOW, "text");
    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("c1", "core_read_document", "{}"),
            ScriptedResponse.textOnly("done"));
    // The backend behind core_read_document goes down after iteration 1 — the model has already
    // been shown (and called) that tool, so withdrawing it would only invite improvisation.
    var emitter =
        new ScriptedOfferingEmitter(
            List.of(Set.of("core_search", "core_read_document"), Set.of("core_search")));
    var service = serviceWithEmitter(ai, emitter, search, read);

    run(service, userMessage("Read the file"), 5);

    assertEquals(2, ai.recordedTools.size(), "expected a two-iteration run");
    assertTrue(
        emitter.emitCalls.get() >= 2,
        // Tempdoc 875 added a SECOND emit consumer — AgentStepRunner authorizes a tool call
        // against offeredWireNames(), which projects emit(). So the exact total is now a
        // function of another workstream's dispatch checks, not of this one's re-evaluation.
        // A LOWER BOUND still witnesses what 876 B.2b is about: 1 would mean the offering was
        // sampled once per run. What the model actually saw each iteration is asserted below,
        // and that is the sharper check.
        "the offering must still be re-evaluated; emits=" + emitter.emitCalls.get());
    // Right-reason guard: the second emit really did drop the tool — the test is not passing
    // because the emitter kept offering it.
    assertEquals(
        List.of("core_search"),
        emitter.emittedNames.get(1),
        "the second emit must be the shrunken one");
    assertEquals(
        List.of("core_search", "core_read_document"),
        toolNamesOf(ai.recordedTools.get(1)),
        "a shrunken offering must not be adopted");
    for (Map<String, Object> tool : ai.recordedTools.get(1)) {
      assertEquals(
          Integer.valueOf(1),
          tool.get(ScriptedOfferingEmitter.EMIT_SEQ),
          "the run keeps the list it already had: " + getToolFunctionName(tool));
    }
  }

  @Test
  @DisplayName("876 B.2b: an unchanged offering is re-evaluated but not churned")
  void offeringUnchangedMidRun_listIsNotChurned() {
    var search = new StubTool("search", RiskTier.LOW, "hit");
    var read = new StubTool("read_document", RiskTier.LOW, "text");
    var ai =
        new ScriptedAiService(
            ScriptedResponse.toolCall("c1", "core_search", "{}"),
            ScriptedResponse.textOnly("done"));
    // One entry ⇒ every call sees the same set.
    var emitter =
        new ScriptedOfferingEmitter(List.of(Set.of("core_search", "core_read_document")));
    var service = serviceWithEmitter(ai, emitter, search, read);

    run(service, userMessage("Search"), 5);

    assertEquals(2, ai.recordedTools.size(), "expected a two-iteration run");
    assertTrue(
        emitter.emitCalls.get() >= 2,
        // Tempdoc 875 added a SECOND emit consumer — AgentStepRunner authorizes a tool call
        // against offeredWireNames(), which projects emit(). So the exact total is now a
        // function of another workstream's dispatch checks, not of this one's re-evaluation.
        // A LOWER BOUND still witnesses what 876 B.2b is about: 1 would mean the offering was
        // sampled once per run. What the model actually saw each iteration is asserted below,
        // and that is the sharper check.
        "the offering is re-evaluated every iteration; emits=" + emitter.emitCalls.get());
    assertEquals(
        List.of("core_search", "core_read_document"),
        emitter.emittedNames.get(1),
        "the second emit really did produce the same set");
    assertEquals(
        toolNamesOf(ai.recordedTools.get(0)),
        toolNamesOf(ai.recordedTools.get(1)),
        "an equal set changes nothing the model sees");
    for (Map<String, Object> tool : ai.recordedTools.get(1)) {
      assertEquals(
          Integer.valueOf(1),
          tool.get(ScriptedOfferingEmitter.EMIT_SEQ),
          "an equal set must not replace the list: " + getToolFunctionName(tool));
    }
  }

  private static List<String> toolNamesOf(List<Map<String, Object>> tools) {
    return tools.stream().map(AgentLoopServiceTest::getToolFunctionName).toList();
  }

  private static AgentLoopService serviceWithEmitter(
      OnlineAiService ai,
      io.justsearch.agent.api.registry.AgentToolEmitter emitter,
      StubTool... tools) {
    return observed(
        new AgentLoopService(
            ai, stubCatalog(tools), stubExecutor(tools), emitter, null, null, null, null));
  }

  /**
   * Tempdoc 876 §B.2b — an emitter whose OFFERING changes between calls: the Nth call gets the Nth
   * name set, the last entry repeating once exhausted (the {@code perCallStructuredData} idiom).
   * That is how a backend recovering — or failing — mid-run reaches the loop.
   *
   * <p>Every emitted tool carries the sequence number of the emit it came from, so an assertion can
   * tell "the loop kept the list it had" apart from "the loop re-adopted an identical-looking one",
   * and a wholesale replacement apart from an append.
   */
  private static final class ScriptedOfferingEmitter
      implements io.justsearch.agent.api.registry.AgentToolEmitter {
    static final String EMIT_SEQ = "__emit_seq";

    private final io.justsearch.agent.api.registry.AgentToolEmitter delegate = stubEmitter();
    private final List<Set<String>> perCall;
    /** The names each emit call actually returned — the right-reason witness. */
    final List<List<String>> emittedNames = new ArrayList<>();

    final java.util.concurrent.atomic.AtomicInteger emitCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger offerCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    ScriptedOfferingEmitter(List<Set<String>> perCall) {
      this.perCall = List.copyOf(perCall);
    }

    private Set<String> availableOn(int nth) {
      return perCall.get(Math.min(nth - 1, perCall.size() - 1));
    }

    @Override
    public List<Operation> offer(
        OperationCatalog catalog, java.util.Collection<String> selectedNames) {
      Set<String> available = availableOn(offerCalls.incrementAndGet());
      return delegate.offer(catalog, selectedNames).stream()
          .filter(op -> available.contains(OperationCatalog.toWireName(op.id())))
          .toList();
    }

    @Override
    public List<Map<String, Object>> emit(
        OperationCatalog catalog, java.util.Collection<String> selectedNames) {
      int nth = emitCalls.incrementAndGet();
      Set<String> available = availableOn(nth);
      List<Map<String, Object>> emitted = new ArrayList<>();
      for (Map<String, Object> tool : delegate.emit(catalog, selectedNames)) {
        if (!available.contains(getToolFunctionName(tool))) {
          continue;
        }
        var stamped = new java.util.LinkedHashMap<String, Object>(tool);
        stamped.put(EMIT_SEQ, nth);
        emitted.add(Map.copyOf(stamped));
      }
      emittedNames.add(toolNamesOf(emitted));
      return List.copyOf(emitted);
    }
  }

  // ===========================================================================
  // Multi-agent handoff scenarios
  // ===========================================================================

  @Test
  void singleHandoff_eventOrderingAndTraceAgentIds() {
    var profiles = List.of(
        new AgentProfile("planner", "Planner", "You are a planner.", List.of()),
        new AgentProfile("executor", "Executor", "You are an executor.", List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_executor", "{\"reason\":\"Ready\"}"),
        ScriptedResponse.textOnly("Execution complete"));
    var service = buildService(ai);
    var request = new AgentRequest(
        userMessage("Do the task"), List.<String>of(), 5, profiles, "planner");
    var events = runWithRequest(service, request);

    // Filter to significant events (skip AgentProgress / AgentBudgetUpdate)
    var significant = events.stream()
        .filter(e -> !(e instanceof AgentEvent.AgentProgress)
            && !(e instanceof AgentEvent.AgentBudgetUpdate))
        .toList();
    // Tempdoc 550 N1 + F2: a handoff-only turn emits NO ToolBatchProposed (handoff calls are
    // filtered out of the batch — they're control flow, not user-approvable tools), so the
    // significant-event sequence is unchanged at 9.
    assertEquals(9, significant.size(), "Expected 9 significant events: " + significant);
    assertInstanceOf(AgentEvent.SessionStarted.class, significant.get(0));
    assertInstanceOf(AgentEvent.ToolCallProposed.class, significant.get(1));
    assertInstanceOf(AgentEvent.ToolCallApproved.class, significant.get(2));
    assertInstanceOf(AgentEvent.ToolExecutionStarted.class, significant.get(3));
    assertInstanceOf(AgentEvent.HandoffProposed.class, significant.get(4));
    assertInstanceOf(AgentEvent.HandoffExecuted.class, significant.get(5));
    assertInstanceOf(AgentEvent.ToolExecutionCompleted.class, significant.get(6));
    assertInstanceOf(AgentEvent.TextChunk.class, significant.get(7));
    assertInstanceOf(AgentEvent.AgentDone.class, significant.get(8));

    // Events 0-5 carry agentId "planner"
    for (int i = 0; i <= 5; i++) {
      var tc = significant.get(i).trace();
      assertNotNull(tc, "event " + i + " should have trace");
      assertEquals("planner", tc.agentId(), "event " + i + " agentId");
    }
    // Events 6-8 carry agentId "executor"
    for (int i = 6; i <= 8; i++) {
      var tc = significant.get(i).trace();
      assertNotNull(tc, "event " + i + " should have trace");
      assertEquals("executor", tc.agentId(), "event " + i + " agentId");
    }
  }

  @Test
  void multiAgent_handoffToolsArePerActiveAgent() {
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_executor", "{\"reason\":\"Handoff\"}"),
        ScriptedResponse.textOnly("Done"));
    var service = buildService(ai);
    var request = new AgentRequest(
        userMessage("Task"), List.<String>of(), 5, profiles, "planner");
    runWithRequest(service, request);

    // Iteration 1 (planner active): has handoff_to_executor, not handoff_to_planner
    var iter1Tools = ai.recordedTools.get(0);
    assertTrue(iter1Tools.stream().anyMatch(
        t -> "handoff_to_executor".equals(getToolFunctionName(t))),
        "planner iteration should have handoff_to_executor");
    assertFalse(iter1Tools.stream().anyMatch(
        t -> "handoff_to_planner".equals(getToolFunctionName(t))),
        "planner iteration should NOT have handoff_to_planner");

    // Iteration 2 (executor active): has handoff_to_planner, not handoff_to_executor
    var iter2Tools = ai.recordedTools.get(1);
    assertFalse(iter2Tools.stream().anyMatch(
        t -> "handoff_to_executor".equals(getToolFunctionName(t))),
        "executor iteration should NOT have handoff_to_executor");
    assertTrue(iter2Tools.stream().anyMatch(
        t -> "handoff_to_planner".equals(getToolFunctionName(t))),
        "executor iteration should have handoff_to_planner");
  }

  @Test
  void handoff_swapsSystemPrompt() {
    var profiles = List.of(
        new AgentProfile("planner", "Planner", "You are a planner.", List.of()),
        new AgentProfile("executor", "Executor", "You are an executor.", List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_executor", "{\"reason\":\"Delegate\"}"),
        ScriptedResponse.textOnly("Done"));
    var service = buildService(ai);
    var request = new AgentRequest(
        userMessage("Task"), List.<String>of(), 5, profiles, "planner");
    runWithRequest(service, request);

    // Iteration 1 (planner): system prompt contains profile text
    var iter1Msgs = ai.recordedMessages.get(0);
    assertEquals("system", iter1Msgs.get(0).get("role"));
    assertTrue(iter1Msgs.get(0).get("content").toString().endsWith("You are a planner."),
        "planner system prompt should contain profile text");

    // Iteration 2 (executor): system prompt contains profile text + merged handoff note
    var iter2Msgs = ai.recordedMessages.get(1);
    assertEquals("system", iter2Msgs.get(0).get("role"));
    String iter2Sys = iter2Msgs.get(0).get("content").toString();
    assertTrue(iter2Sys.contains("You are an executor."),
        "executor system prompt should contain profile text");
    assertTrue(iter2Sys.contains("Handoff from planner to executor"),
        "executor system prompt should contain merged handoff note");
  }

  @Test
  void singleAgent_noHandoffToolsInjected() {
    var ai = new ScriptedAiService(ScriptedResponse.textOnly("Done"));
    var service = buildService(ai, new StubTool("search", RiskTier.LOW, "r"));
    // No agentProfiles — single-agent mode
    var request = new AgentRequest(userMessage("Task"), List.of(), 1);
    runWithRequest(service, request);

    var iter1Tools = ai.recordedTools.get(0);
    assertFalse(iter1Tools.stream().anyMatch(
        t -> getToolFunctionName(t).startsWith("handoff_to_")),
        "Single-agent request should have no handoff tools");
  }

  @Test
  void handoff_unknownTarget_emitsAgentError() {
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_nonexistent", "{\"reason\":\"Bad\"}"));
    var service = buildService(ai);
    var request = new AgentRequest(
        userMessage("Task"), List.<String>of(), 5, profiles, "planner");
    var events = runWithRequest(service, request);

    var error = events.stream()
        .filter(e -> e instanceof AgentEvent.AgentError)
        .map(e -> (AgentEvent.AgentError) e)
        .findFirst();
    assertTrue(error.isPresent(), "Expected AgentError for unknown agent target");
    assertEquals(AgentErrorCode.UNKNOWN_TOOL.name(), error.get().errorCode());
  }

  @Test
  void handoff_approvalBoundaryReset() {
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_executor", "{\"reason\":\"Delegate\"}"),
        // Executor calls search_index (READ_ONLY) — auto-approved fresh
        ScriptedResponse.toolCall("tc-2", "core_search_index", "{\"query\":\"q\"}"));
    var service = buildService(ai, new StubTool("search_index", RiskTier.LOW, "r"));
    var request = new AgentRequest(
        userMessage("Task"), List.<String>of(), 5, profiles, "planner");
    var events = runWithRequest(service, request);

    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.HandoffExecuted),
        "Expected HandoffExecuted event");
    // hc-1 (handoff, READ_ONLY auto-approved) + tc-2 (search, READ_ONLY auto-approved)
    var approved = events.stream()
        .filter(e -> e instanceof AgentEvent.ToolCallApproved)
        .toList();
    assertEquals(2, approved.size(), "Expected 2 ToolCallApproved events");
  }

  @Test
  void nullInitialAgentId_usesFirstProfile() {
    // profiles: [planner, executor]; initialAgentId = null → should start as "planner"
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(ScriptedResponse.textOnly("done"));
    var service = buildService(ai);
    var request = new AgentRequest(userMessage("hi"), List.<String>of(), 1, profiles, null);
    var events = runWithRequest(service, request);

    // All events before AgentDone should carry "planner" as agentId
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Should emit AgentDone");
    var sessionStarted = events.stream()
        .filter(e -> e instanceof AgentEvent.SessionStarted)
        .findFirst();
    assertTrue(sessionStarted.isPresent(), "Should emit SessionStarted");
    assertEquals("planner", sessionStarted.get().trace().agentId(),
        "SessionStarted should carry 'planner' agentId when initialAgentId is null");
  }

  @Test
  void handoff_resetsLoopGuardCounters() {
    // Planner calls search_index twice with identical args, then hands off.
    // Executor calls search_index with the same args — should NOT be blocked (loop guard reset).
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("tc-1", "core_search_index", "{\"query\":\"q\"}"),
        ScriptedResponse.toolCall("tc-2", "core_search_index", "{\"query\":\"q\"}"),
        ScriptedResponse.toolCall("hc-1", "handoff_to_executor", "{\"reason\":\"done\"}"),
        ScriptedResponse.toolCall("tc-3", "core_search_index", "{\"query\":\"q\"}"),
        ScriptedResponse.textOnly("Result"));
    var service = buildService(ai, new StubTool("search_index", RiskTier.LOW, "found"));
    var request = new AgentRequest(
        userMessage("search"), List.<String>of(), 10, profiles, "planner");
    var events = runWithRequest(service, request);

    // tc-3 (executor's search) should complete successfully, not be loop-blocked
    var completions = events.stream()
        .filter(e -> e instanceof AgentEvent.ToolExecutionCompleted)
        .map(e -> (AgentEvent.ToolExecutionCompleted) e)
        .toList();
    // Expect: tc-1, tc-2, hc-1 (handoff), tc-3 all completed
    assertEquals(4, completions.size(),
        "executor's search should not be loop-blocked; expected tc-1, tc-2, hc-1, tc-3");
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.AgentDone),
        "Session should complete successfully");
  }

  @Test
  void handoff_mixedBatch_assistantMessageTrimmedToHandoff() {
    // Model returns [search_call, handoff_to_executor] in a single batch.
    // The assistant message sent to context should only include the search_call,
    // not the handoff call followed by cancelled-before-it tools.
    var profiles = List.of(
        new AgentProfile("planner", "Planner", null, List.of()),
        new AgentProfile("executor", "Executor", null, List.of()));
    var ai = new ScriptedAiService(
        // Batch: [search_index, handoff_to_executor] in one LLM response
        ScriptedResponse.multiToolCall(
            "sc-1", "core_search_index", "{\"query\":\"q\"}",
            "hc-1", "handoff_to_executor", "{\"reason\":\"done\"}"),
        ScriptedResponse.textOnly("Executor done"));
    var service = buildService(ai, new StubTool("search_index", RiskTier.LOW, "found"));
    var request = new AgentRequest(
        userMessage("search then handoff"), List.<String>of(), 10, profiles, "planner");
    var events = runWithRequest(service, request);

    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.HandoffExecuted),
        "Expected HandoffExecuted event");
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.AgentDone),
        "Session should complete successfully");

    // Verify executor's iteration messages contain NO "Cancelled" tool results
    var iter2Msgs = ai.recordedMessages.get(1);
    boolean hasCancelledMsg = iter2Msgs.stream().anyMatch(m ->
        "tool".equals(m.get("role"))
            && m.get("content") != null
            && String.valueOf(m.get("content")).contains("Cancelled"));
    assertFalse(hasCancelledMsg,
        "Executor iteration should not see Cancelled messages for un-executed planner tools");
  }

  // ===========================================================================
  // pruneHandoffMessages unit tests
  // ===========================================================================

  /**
   * Builds a minimal OpenAI-format assistant message carrying one tool call.
   * The "tool_calls" structure must match what AgentLoopService's pruneHandoffMessages
   * loop reads: list of maps, each with a "function" map holding "name" and "arguments".
   */
  private static Map<String, Object> assistantCallMsg(String callId, String toolName, String args) {
    return Map.of(
        "role", "assistant",
        "tool_calls", List.of(Map.of(
            "id", callId,
            "function", Map.of("name", toolName, "arguments", args))));
  }

  private static Map<String, Object> toolResultMsg(String callId, String content) {
    return Map.of("role", "tool", "tool_call_id", callId, "content", content);
  }

  private static Map<String, Object> systemMsg(String content) {
    return Map.of("role", "system", "content", content);
  }

  private static Map<String, Object> userMsg(String content) {
    return Map.of("role", "user", "content", content);
  }

  @Test
  void pruneHandoffMessages_noExploration_isNoOp() {
    // Layout: [sys][user][handoff-call][handoff-sys][handoff-tool]
    // handoffAssistantIdx == 2 → early return, list unchanged
    var session = new AgentSession(List.of(), 1000);
    List<Map<String, Object>> msgs = session.messages();
    msgs.add(systemMsg("system"));
    msgs.add(userMsg("hi"));
    msgs.add(assistantCallMsg("h1", "handoff_to_organizer", "{\"reason\":\"go\"}"));
    msgs.add(systemMsg("Handoff from primary to organizer."));
    msgs.add(toolResultMsg("h1", "Handoff to organizer confirmed."));

    AgentHandoff.pruneHandoffMessages(session);

    assertEquals(5, msgs.size(), "No exploration: list must stay unchanged");
    assertEquals("assistant", msgs.get(2).get("role"));
  }

  @Test
  void pruneHandoffMessages_singleToolPair_strippedAndBriefInjected() {
    // Layout: [sys][user][browse-call][browse-result][handoff-call][handoff-sys][handoff-tool]
    // After:  [sys][user][brief-sys][handoff-call][handoff-sys][handoff-tool]
    var session = new AgentSession(List.of(), 1000);
    List<Map<String, Object>> msgs = session.messages();
    msgs.add(systemMsg("system"));
    msgs.add(userMsg("find stuff"));
    msgs.add(assistantCallMsg("c1", "browse_folders", "{\"parent_path\":\"/docs\"}"));
    msgs.add(toolResultMsg("c1", "Folder listing: /docs/a, /docs/b"));
    msgs.add(assistantCallMsg("h1", "handoff_to_organizer", "{\"reason\":\"done\"}"));
    msgs.add(systemMsg("Handoff from primary to organizer."));
    msgs.add(toolResultMsg("h1", "Handoff to organizer confirmed."));

    AgentHandoff.pruneHandoffMessages(session);

    assertEquals(6, msgs.size(), "Single pair stripped: 7 → 6 messages");
    // Index 0 = system, 1 = user, 2 = brief, 3 = handoff-call, 4 = handoff-sys, 5 = handoff-tool
    assertEquals("system", msgs.get(2).get("role"), "Index 2 should be brief (system role)");
    String brief = String.valueOf(msgs.get(2).get("content"));
    assertTrue(brief.contains("browse_folders"), "Brief should mention browse_folders: " + brief);
    // Handoff call is still at index 3
    assertNotNull(msgs.get(3).get("tool_calls"), "Index 3 must still be handoff assistant call");
    // Handoff tool result at index 5
    assertEquals("h1", msgs.get(5).get("tool_call_id"), "Handoff tool result must be preserved");
  }

  @Test
  void pruneHandoffMessages_multipleToolPairs_allStrippedBriefHasAll() {
    // Layout: [sys][user][search-call][search-result][browse-call][browse-result]
    //           [handoff-call][handoff-sys][handoff-tool]  (9 messages)
    // After:  [sys][user][brief-sys][handoff-call][handoff-sys][handoff-tool]  (6 messages)
    var session = new AgentSession(List.of(), 1000);
    List<Map<String, Object>> msgs = session.messages();
    msgs.add(systemMsg("system"));
    msgs.add(userMsg("search then browse"));
    msgs.add(assistantCallMsg("c1", "search_index", "{\"query\":\"cats\"}"));
    msgs.add(toolResultMsg("c1", "Found 3 results about cats"));
    msgs.add(assistantCallMsg("c2", "browse_folders", "{\"parent_path\":\"/\"}"));
    msgs.add(toolResultMsg("c2", "Root folders: /docs, /data"));
    msgs.add(assistantCallMsg("h1", "handoff_to_organizer", "{\"reason\":\"ready\"}"));
    msgs.add(systemMsg("Handoff from primary to organizer."));
    msgs.add(toolResultMsg("h1", "Handoff to organizer confirmed."));

    AgentHandoff.pruneHandoffMessages(session);

    assertEquals(6, msgs.size(), "Both pairs stripped: 9 → 6 messages");
    String brief = String.valueOf(msgs.get(2).get("content"));
    assertTrue(brief.contains("search_index"), "Brief should mention search_index: " + brief);
    assertTrue(brief.contains("browse_folders"), "Brief should mention browse_folders: " + brief);
    assertNotNull(msgs.get(3).get("tool_calls"), "Index 3 must be handoff assistant call");
    assertEquals("h1", msgs.get(5).get("tool_call_id"), "Handoff tool result must be preserved");
  }

  @Test
  void pruneHandoffMessages_handoffToStarInRange_excludedFromBrief() {
    // Simulates a second handoff: intermediate range contains a previous handoff_to_organizer call.
    // That call must NOT appear in the research brief — only real tool calls do.
    // Layout: [sys][user][prev-brief-sys][old-handoff-call][old-handoff-sys][old-handoff-tool]
    //           [browse-call][browse-result][new-handoff-call][new-handoff-sys][new-handoff-tool]
    var session = new AgentSession(List.of(), 1000);
    List<Map<String, Object>> msgs = session.messages();
    msgs.add(systemMsg("system"));
    msgs.add(userMsg("do stuff"));
    msgs.add(systemMsg("Research findings:\n- search_index: {\"query\":\"x\"}\n  → 5 results"));
    msgs.add(assistantCallMsg("ho1", "handoff_to_organizer", "{\"reason\":\"first pass\"}"));
    msgs.add(systemMsg("Handoff from primary to organizer."));
    msgs.add(toolResultMsg("ho1", "Handoff to organizer confirmed."));
    msgs.add(assistantCallMsg("c1", "browse_folders", "{\"parent_path\":\"/data\"}"));
    msgs.add(toolResultMsg("c1", "Data folders: /data/raw, /data/processed"));
    msgs.add(assistantCallMsg("h2", "handoff_to_primary", "{\"reason\":\"back\"}"));
    msgs.add(systemMsg("Handoff from organizer to primary."));
    msgs.add(toolResultMsg("h2", "Handoff to primary confirmed."));

    AgentHandoff.pruneHandoffMessages(session);

    String brief = String.valueOf(msgs.get(2).get("content"));
    assertFalse(brief.contains("handoff_to_organizer"),
        "Brief must NOT include previous handoff calls: " + brief);
    assertTrue(brief.contains("browse_folders"),
        "Brief must include real tool calls: " + brief);
  }

  // ---------------------------------------------------------------------------
  // Cycle detection: total handoffs
  // ---------------------------------------------------------------------------

  @Test
  void handoff_cycleDetection_firesAfterMaxTotalHandoffs() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of()),
        new AgentProfile("organizer", "Organizer", null, List.of()));
    // maxHandoffs=2: 3rd total handoff triggers detection
    var ai = new ScriptedAiService(
        ScriptedResponse.toolCall("hc-1", "handoff_to_organizer", "{\"reason\":\"a\"}"),
        ScriptedResponse.toolCall("hc-2", "handoff_to_primary",   "{\"reason\":\"b\"}"),
        ScriptedResponse.toolCall("hc-3", "handoff_to_organizer", "{\"reason\":\"c\"}"));
    var service = buildService(ai);
    var request = new AgentRequest(
        userMessage("cycle"), List.<String>of(), 10, profiles, "primary", 2);
    var events = runWithRequest(service, request);

    var errors = eventsOfType(events, AgentEvent.AgentError.class);
    assertFalse(errors.isEmpty(), "Expected AgentError for handoff cycle");
    assertEquals(AgentErrorCode.HANDOFF_CYCLE_DETECTED.name(), errors.get(0).errorCode());
    // Exactly 2 HandoffExecuted events before detection fires on the 3rd attempt
    assertEquals(2, eventsOfType(events, AgentEvent.HandoffExecuted.class).size());
  }

  @Test
  void handoff_organizerFirstTurnGetsToolChoiceRequired() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of()),
        new AgentProfile("organizer", "Organizer", null, List.of()));
    var ai = new ScriptedAiService(
        // PRIMARY turn 1: handoff to organizer
        ScriptedResponse.toolCall("hc-1", "handoff_to_organizer", "{\"reason\":\"ingest\"}"),
        // ORGANIZER turn 1 (E0a — restricted to ingest_files): ingest_files
        ScriptedResponse.toolCall("tc-1", "core_ingest_files", "{\"paths\":[\"doc.md\"]}"),
        // ORGANIZER turn 2: text confirmation (loop terminates)
        ScriptedResponse.textOnly("Done"));
    var service = buildService(ai, new StubTool("ingest_files", RiskTier.LOW, "ok"));
    var request = new AgentRequest(
        userMessage("Ingest a file"), List.<String>of(), 10, profiles, "primary");
    runWithRequest(service, request);

    assertEquals(3, ai.recordedSampling.size(),
        "expected exactly 3 LLM calls: PRIMARY + 2 Organizer turns");
    // Call 0 = PRIMARY (should use AGENT sampling, no tool_choice)
    assertNull(ai.recordedSampling.get(0).toolChoice(),
        "PRIMARY should not force tool_choice");
    // Call 1 = ORGANIZER first turn (should force tool_choice=required)
    assertEquals("required", ai.recordedSampling.get(1).toolChoice(),
        "Organizer first turn should force tool_choice=required");
    // Call 2 = ORGANIZER second turn (should NOT force — E0a first-turn-only)
    assertNull(ai.recordedSampling.get(2).toolChoice(),
        "Organizer second turn should not force tool_choice (E0a)");
    // Grammar: Direction I — set on E0a (call 1); absent on normal turns and turns after E0a.
    // Grammar is stored in SamplingParams but omitted by the server when tools list is non-empty.
    assertNull(ai.recordedSampling.get(0).grammar(), "PRIMARY should not apply grammar");
    assertNotNull(ai.recordedSampling.get(1).grammar(),
        "Organizer first turn (E0a) should carry GBNF grammar in SamplingParams");
    assertNull(ai.recordedSampling.get(2).grammar(), "Organizer second turn should not apply grammar");
    // Direction D: E0a suppresses thinking-prompt; non-forced turns don't override.
    assertNull(ai.recordedSampling.get(0).enableThinking(),
        "PRIMARY should not set enableThinking");
    assertEquals(false, ai.recordedSampling.get(1).enableThinking(),
        "Organizer first turn (E0a) must suppress thinking (enableThinking=false)");
    assertNull(ai.recordedSampling.get(2).enableThinking(),
        "Organizer second turn should not set enableThinking");
    // Direction 7a: E0a tool list contains ingest_files but excludes search/browse tools.
    var e0aToolNames = ai.recordedTools.get(1).stream()
        .map(t -> getToolFunctionName(t))
        .toList();
    assertTrue(e0aToolNames.contains("core_ingest_files"),
        "E0a must include core_ingest_files");
    assertFalse(
        e0aToolNames.stream().anyMatch(
            n -> n.equals("core_search_index") || n.equals("core_browse_folders") || n.equals("core_file_operations")),
        "E0a must not include search or browse tools — only core_ingest_files + handoff");
  }

  // ===========================================================================
  // Direction F: tool_choice escalation for PRIMARY text-only after tool use
  // ===========================================================================

  @Test
  void primaryTextAfterToolUse_escalatesToForcedHandoff() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        // PRIMARY turn 1: search_index tool call
        ScriptedResponse.toolCall("sc-1", "core_search_index", "{\"query\":\"architecture\"}"),
        // PRIMARY turn 2: text-only (triggers Direction F escalation)
        ScriptedResponse.textOnly("Here are the top candidates: 01-system-overview.md"),
        // Escalation retry: forced handoff_to_organizer
        ScriptedResponse.toolCall(
            "hc-1", "handoff_to_organizer", "{\"reason\":\"01-system-overview.md\"}"),
        // ORGANIZER turn 1: ingest_files
        ScriptedResponse.toolCall(
            "tc-1", "core_ingest_files", "{\"paths\":[\"01-system-overview.md\"]}"),
        // ORGANIZER turn 2: text confirmation
        ScriptedResponse.textOnly("Ingested 01-system-overview.md."));
    var service = buildService(
        ai,
        new StubTool("search_index", RiskTier.LOW, "{\"hits\":[]}"),
        new StubTool("ingest_files", RiskTier.LOW, "ok"));
    var request = new AgentRequest(
        userMessage("Find and ingest the architecture overview"),
        List.<String>of(), 10, profiles, "primary");
    var events = runWithRequest(service, request);

    // 5 LLM calls: PRIMARY(search) + PRIMARY(text) + escalation(handoff) + ORG(ingest) + ORG(done)
    assertEquals(5, ai.recordedSampling.size(), "Expected 5 LLM calls");
    // Calls 0, 1: PRIMARY normal calls — no forced tool_choice
    assertNull(ai.recordedSampling.get(0).toolChoice(), "PRIMARY turn 1 should not force");
    assertNull(ai.recordedSampling.get(1).toolChoice(), "PRIMARY turn 2 should not force");
    // Call 2: escalation retry — forced tool_choice=required
    assertEquals("required", ai.recordedSampling.get(2).toolChoice(),
        "Escalation retry should force tool_choice=required");
    // Call 2 tools: only handoff tool, no search_index
    var escalationToolNames = ai.recordedTools.get(2).stream()
        .map(t -> getToolFunctionName(t))
        .toList();
    assertEquals(List.of("handoff_to_organizer"), escalationToolNames,
        "Escalation should offer only handoff tools");
    // Call 3: ORGANIZER first turn — E0a forces
    assertEquals("required", ai.recordedSampling.get(3).toolChoice(),
        "Organizer first turn should force tool_choice (E0a)");
    // Direction 7a: E0a tool list contains ingest_files but excludes search/browse tools.
    var e0aToolNames = ai.recordedTools.get(3).stream()
        .map(t -> getToolFunctionName(t))
        .toList();
    assertTrue(e0aToolNames.contains("core_ingest_files"),
        "E0a must include core_ingest_files");
    assertFalse(e0aToolNames.contains("core_search_index"),
        "E0a must not include core_search_index");
    // Call 4: ORGANIZER second turn — no force
    assertNull(ai.recordedSampling.get(4).toolChoice(),
        "Organizer second turn should not force");
    // Grammar: Direction I — set on escalation (call 2) and E0a (call 3); absent on normal turns.
    // Grammar is stored in SamplingParams but omitted by the server when tools list is non-empty.
    assertNull(ai.recordedSampling.get(0).grammar(), "PRIMARY turn 1 should not apply grammar");
    assertNull(ai.recordedSampling.get(1).grammar(), "PRIMARY turn 2 should not apply grammar");
    assertNotNull(ai.recordedSampling.get(2).grammar(),
        "Escalation retry should carry GBNF grammar in SamplingParams");
    assertNotNull(ai.recordedSampling.get(3).grammar(),
        "Organizer first turn (E0a) should carry GBNF grammar in SamplingParams");
    assertNull(ai.recordedSampling.get(4).grammar(), "Organizer second turn should not apply grammar");
    // Direction D: escalation and E0a suppress thinking; other turns don't override.
    assertNull(ai.recordedSampling.get(0).enableThinking(),
        "PRIMARY turn 1 should not set enableThinking");
    assertNull(ai.recordedSampling.get(1).enableThinking(),
        "PRIMARY turn 2 should not set enableThinking");
    assertEquals(false, ai.recordedSampling.get(2).enableThinking(),
        "Escalation retry (Direction F) must suppress thinking (enableThinking=false)");
    assertEquals(false, ai.recordedSampling.get(3).enableThinking(),
        "Organizer first turn (E0a) must suppress thinking (enableThinking=false)");
    assertNull(ai.recordedSampling.get(4).enableThinking(),
        "Organizer second turn should not set enableThinking");
    // Handoff event emitted
    assertFalse(eventsOfType(events, AgentEvent.HandoffExecuted.class).isEmpty(),
        "Expected HandoffExecuted event from escalation");
    // Final event is AgentDone (not error)
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Expected AgentDone at end");
  }

  @Test
  void primaryTextOnFirstIteration_noEscalation() {
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    var ai = new ScriptedAiService(
        // PRIMARY turn 1: immediate text-only response (no prior tool use)
        ScriptedResponse.textOnly("The available tools are search and browse."));
    var service = buildService(
        ai, new StubTool("search_index", RiskTier.LOW, "{\"hits\":[]}"));
    var request = new AgentRequest(
        userMessage("What tools do you have?"),
        List.<String>of(), 10, profiles, "primary");
    var events = runWithRequest(service, request);

    // Only 1 LLM call — no escalation retry
    assertEquals(1, ai.recordedSampling.size(), "Expected 1 LLM call (no escalation)");
    assertNull(ai.recordedSampling.get(0).toolChoice(), "Should not force tool_choice");
    // AgentDone with original text
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Expected AgentDone");
    assertTrue(done.finalResponse().contains("available tools"),
        "AgentDone should contain original text");
    // No handoff
    assertTrue(eventsOfType(events, AgentEvent.HandoffExecuted.class).isEmpty(),
        "Should not have any handoff events");
  }

  @Test
  void singleAgentTextAfterToolUse_noEscalation() {
    // Single-agent mode (no profiles) — escalation must not fire
    var ai = new ScriptedAiService(
        // Turn 1: tool call
        ScriptedResponse.toolCall("sc-1", "core_search_index", "{\"query\":\"test\"}"),
        // Turn 2: text-only
        ScriptedResponse.textOnly("Found 3 results about testing."));
    var service = buildService(
        ai, new StubTool("search_index", RiskTier.LOW, "{\"hits\":[]}"));
    var events = run(service, userMessage("Search for test docs"), 10);

    // 2 LLM calls — no escalation
    assertEquals(2, ai.recordedSampling.size(), "Expected 2 LLM calls (no escalation)");
    // AgentDone with text
    var done = lastEventOfType(events, AgentEvent.AgentDone.class);
    assertNotNull(done, "Expected AgentDone");
    assertTrue(done.finalResponse().contains("Found 3 results"),
        "AgentDone should contain original text");
    // No handoff
    assertTrue(eventsOfType(events, AgentEvent.HandoffExecuted.class).isEmpty(),
        "Should not have any handoff events");
  }

  // ---------------------------------------------------------------------------
  // Direction E: state machine force-commit after search threshold
  // ---------------------------------------------------------------------------

  @Test
  void primaryForceCommit_afterThresholdToolRounds_restrictsToHandoffAndForcesToolChoice() {
    // PRIMARY does PRIMARY_FORCE_COMMIT_ITERATIONS tool calls without handing off.
    // On the next iteration (DECIDING state), tools must be restricted to handoff_to_organizer
    // and sampling must apply tool_choice=required.
    var profiles = List.of(
        new AgentProfile("primary", "Primary", null, List.of("core_search_index")),
        new AgentProfile("organizer", "Organizer", null, List.of("core_ingest_files")));
    int threshold = AgentTurnPolicy.PRIMARY_FORCE_COMMIT_ITERATIONS;
    // Build responses: threshold search_index calls, then a forced handoff, then Organizer flow
    var scriptedResponses = new ArrayList<ScriptedResponse>();
    for (int i = 0; i < threshold; i++) {
      scriptedResponses.add(ScriptedResponse.toolCall("sc-" + i, "core_search_index", "{\"query\":\"arch\"}"));
    }
    scriptedResponses.add(ScriptedResponse.toolCall("hc-0", "handoff_to_organizer", "{\"reason\":\"arch doc\"}"));
    scriptedResponses.add(ScriptedResponse.toolCall("tc-0", "core_ingest_files", "{\"paths\":[\"arch.md\"]}"));
    scriptedResponses.add(ScriptedResponse.textOnly("Done."));
    var ai = new ScriptedAiService(scriptedResponses);
    var service = buildService(
        ai,
        new StubTool("search_index", RiskTier.LOW, "{\"hits\":[]}"),
        new StubTool("ingest_files", RiskTier.LOW, "ok"));
    var request = new AgentRequest(
        userMessage("Find and ingest the architecture overview"),
        List.<String>of(), 10, profiles, "primary");
    var events = runWithRequest(service, request);

    // Expected calls: threshold PRIMARY searches + 1 forced handoff + 2 Organizer turns
    int expectedCalls = threshold + 1 + 2;
    assertEquals(expectedCalls, ai.recordedSampling.size(),
        "Expected " + expectedCalls + " LLM calls total");

    // PRIMARY SEARCHING turns (0..threshold-1): no forced tool_choice
    for (int i = 0; i < threshold; i++) {
      assertNull(ai.recordedSampling.get(i).toolChoice(),
          "PRIMARY SEARCHING turn " + i + " should not force tool_choice");
    }

    // PRIMARY DECIDING turn (index = threshold): tool_choice=required, handoff-only tools
    assertEquals("required", ai.recordedSampling.get(threshold).toolChoice(),
        "PRIMARY DECIDING turn should force tool_choice=required");
    var decidingToolNames = ai.recordedTools.get(threshold).stream()
        .map(t -> getToolFunctionName(t))
        .toList();
    assertEquals(List.of("handoff_to_organizer"), decidingToolNames,
        "DECIDING state should offer only handoff tools");

    // ORGANIZER turn 1 (E0a): tool_choice=required
    assertEquals("required", ai.recordedSampling.get(threshold + 1).toolChoice(),
        "Organizer first turn should force tool_choice (E0a)");
    // ORGANIZER turn 2: no force
    assertNull(ai.recordedSampling.get(threshold + 2).toolChoice(),
        "Organizer second turn should not force");

    // Grammar: Direction I — set on E0a (first Organizer turn); absent on all other calls.
    // Grammar is stored in SamplingParams but omitted by the server when tools list is non-empty.
    for (int i = 0; i < threshold; i++) {
      assertNull(ai.recordedSampling.get(i).grammar(),
          "Grammar should be null on PRIMARY SEARCHING call " + i);
    }
    // DECIDING turn uses callLlmWithTools with explicit sampling — no grammar (not E0a)
    assertNull(ai.recordedSampling.get(threshold).grammar(),
        "Grammar should be null on PRIMARY DECIDING call");
    // Organizer first turn (E0a): Direction I sets grammar
    assertNotNull(ai.recordedSampling.get(threshold + 1).grammar(),
        "Grammar should be set on Organizer first turn (E0a)");
    // Organizer second turn: no forced tool_choice, no grammar
    assertNull(ai.recordedSampling.get(threshold + 2).grammar(),
        "Grammar should be null on Organizer second turn");

    // Direction D: DECIDING and E0a suppress thinking; SEARCHING turns don't override.
    for (int i = 0; i < threshold; i++) {
      assertNull(ai.recordedSampling.get(i).enableThinking(),
          "PRIMARY SEARCHING turn " + i + " should not set enableThinking");
    }
    assertEquals(false, ai.recordedSampling.get(threshold).enableThinking(),
        "PRIMARY DECIDING turn must suppress thinking (enableThinking=false)");
    assertEquals(false, ai.recordedSampling.get(threshold + 1).enableThinking(),
        "Organizer first turn (E0a) must suppress thinking (enableThinking=false)");
    assertNull(ai.recordedSampling.get(threshold + 2).enableThinking(),
        "Organizer second turn should not set enableThinking");

    // Handoff event emitted
    assertFalse(eventsOfType(events, AgentEvent.HandoffExecuted.class).isEmpty(),
        "Expected HandoffExecuted event from force-commit");
    // Final event is AgentDone (not error)
    assertNotNull(lastEventOfType(events, AgentEvent.AgentDone.class),
        "Expected AgentDone at end");
  }

  private static <T extends AgentEvent> T lastEventOfType(List<AgentEvent> events, Class<T> type) {
    T last = null;
    for (AgentEvent e : events) {
      if (type.isInstance(e)) {
        last = type.cast(e);
      }
    }
    return last;
  }

  private static <T extends AgentEvent> List<T> eventsOfType(
      List<AgentEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  // ===========================================================================
  // StubTool
  // ===========================================================================

  /**
   * Per Phase 11 of tempdoc 429: replaces the legacy {@code stubTool(...)} helper.
   * Produces an {@link Operation} (substrate type) with the matching {@link RiskTier},
   * a stable execute callback, and a wireName preserving the LLM-facing surface
   * ({@code "search"}, {@code "ingest_files"}, etc.). The {@link #execute(String)} method
   * is invoked by {@link OperationExecutor} via the {@link HandlerRegistry} wiring built
   * by {@link #buildSubstrate(StubTool...)}.
   */
  static final class StubTool {
    final String wireName;
    final RiskTier risk;
    final String returnValue;
    final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
    // Tempdoc S7 — records the raw JSON arguments this stub actually received, so a test can
    // assert whether AgentToolDispatcher.scopeToolCall merged a docIds scope into them.
    volatile String lastArgs;

    StubTool(String wireName, RiskTier risk, String returnValue) {
      this.wireName = wireName;
      this.risk = risk;
      this.returnValue = returnValue;
    }

    Operation toOperation() {
      return new Operation(
          new OperationRef("core." + wireName.replace('_', '-')),
          new Presentation(
              new I18nKey("test." + wireName + ".label"),
              new I18nKey("test." + wireName + ".description"),
              Optional.empty(),
              Optional.empty()),
          Interface.of(
              "{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
          new OperationPolicy(
              risk,
              ConfirmStrategy.None.INSTANCE,
              AuditPolicy.NONE,
              RetryPolicy.noRetry(),
              Set.of(),
              false),
          OperationAvailability.empty(),
          OperationLineage.empty(),
          Binding.of(new OperationRef("core." + wireName.replace('_', '-'))),
          Provenance.core("1.0"),
          Set.of(ExecutorTag.AGENT));
    }

    /**
     * Tempdoc 865 §7.1 — a structured payload per call (the Nth call gets the Nth entry, the last
     * entry repeating once exhausted). Empty ⇒ the stub reports no structured data at all, which is
     * the pre-865 behaviour every other test in this file relies on.
     */
    private final List<Map<String, Object>> perCallStructuredData = new ArrayList<>();

    StubTool returningStructuredData(List<Map<String, Object>> perCall) {
      perCallStructuredData.addAll(perCall);
      return this;
    }

    OperationResult execute(String args) {
      int nth = callCount.incrementAndGet();
      lastArgs = args;
      if (perCallStructuredData.isEmpty()) {
        return OperationResult.success(returnValue);
      }
      return OperationResult.success(
          returnValue,
          perCallStructuredData.get(Math.min(nth - 1, perCallStructuredData.size() - 1)));
    }
  }

  /** Builds the OperationCatalog + OperationExecutor + AgentToolEmitter trio. */
  static io.justsearch.agent.api.registry.AgentToolEmitter stubEmitter() {
    // Use the concrete AgentOperationEmitter via reflection-free import path: tests in
    // app-agent live in the same JVM as app-services classes (test classpath only). To
    // keep app-agent tests independent of app-services, we provide an inline emitter
    // mirroring AgentOperationEmitter's deterministic-transliteration + identity-resolver behavior.
    // Tempdoc 876 §B.1: AgentToolEmitter is no longer a @FunctionalInterface (it gained offer()),
    // so this stub is a class whose emit() projects its own offer() — mirroring the split the real
    // emitter now has, so the stub cannot disagree with itself either.
    return new io.justsearch.agent.api.registry.AgentToolEmitter() {
      @Override
      public List<Operation> offer(
          OperationCatalog catalog, java.util.Collection<String> selectedNames) {
        List<Operation> offered = new ArrayList<>();
        for (Operation op : catalog.definitions()) {
          if (!op.executors().contains(ExecutorTag.AGENT)) continue;
          String wire = OperationCatalog.toWireName(op.id());
          if (selectedNames != null && !selectedNames.isEmpty() && !selectedNames.contains(wire)) {
            continue;
          }
          offered.add(op);
        }
        return List.copyOf(offered);
      }

      @Override
      public List<Map<String, Object>> emit(
          OperationCatalog catalog, java.util.Collection<String> selectedNames) {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Operation op : offer(catalog, selectedNames)) {
          try {
            var function = mapper.createObjectNode();
            function.put("name", OperationCatalog.toWireName(op.id()));
            function.put("description", op.presentation().descriptionKey().value());
            function.set("parameters", mapper.readTree(op.intf().inputs()));
            var toolObj = mapper.createObjectNode();
            toolObj.put("type", "function");
            toolObj.set("function", function);
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = mapper.convertValue(toolObj, Map.class);
            result.add(new java.util.LinkedHashMap<>(entry));
          } catch (Exception e) {
            throw new IllegalStateException("Failed to emit " + op.id(), e);
          }
        }
        return result;
      }
    };
  }

  static OperationCatalog stubCatalog(StubTool... tools) {
    return OperationCatalog.of(
        "core",
        Arrays.stream(tools).map(StubTool::toOperation).toList());
  }

  /**
   * Test-local {@link OperationDispatcher} implementation per tempdoc 429 §C decision A:
   * tests in {@code app-agent} cannot import the production
   * {@code OperationExecutorImpl} from {@code app-services} without crossing the
   * type/behavior boundary, so the test provides its own minimal dispatcher that
   * mirrors the CORE-tier dispatch path (the only path tests exercise here).
   */
  static OperationDispatcher stubExecutor(StubTool... tools) {
    HandlerRegistry handlers = new HandlerRegistry();
    for (StubTool tool : tools) {
      OperationHandler handler =
          new OperationHandler() {
            @Override
            public OperationResult execute(String args) {
              return tool.execute(args);
            }
          };
      handlers.register(new OperationRef("core." + tool.wireName.replace('_', '-')), handler);
    }
    return new OperationDispatcher() {
      @Override
      public OperationResult dispatch(Operation op, String argumentsJson) {
        OperationHandler handler =
            handlers
                .resolve(new OperationRef(op.binding().handlerId()))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "No handler registered for binding " + op.binding().handlerId()));
        return handler.execute(argumentsJson);
      }

      @Override
      public OperationResult undo(Operation op, String executionId) {
        if (!op.policy().undoSupported()) {
          return OperationResult.failure("Undo not supported by " + op.id().value());
        }
        OperationHandler handler =
            handlers
                .resolve(new OperationRef(op.binding().handlerId()))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "No handler registered for binding " + op.binding().handlerId()));
        return handler.undo(executionId);
      }
    };
  }

  // ===========================================================================
  // ScriptedAiService — replays pre-defined responses synchronously
  // ===========================================================================

  static final class ScriptedAiService implements OnlineAiService {
    private final List<ScriptedResponse> responses;
    final List<List<Map<String, Object>>> recordedMessages = new ArrayList<>();
    final List<SamplingParams> recordedSampling = new ArrayList<>();
    final List<List<Map<String, Object>>> recordedTools = new ArrayList<>();
    private int callIndex;

    ScriptedAiService(List<ScriptedResponse> responses) {
      this.responses = new ArrayList<>(responses);
    }

    ScriptedAiService(ScriptedResponse... responses) {
      this.responses = new ArrayList<>(List.of(responses));
    }

    // Tempdoc 491 §C5: streamSummary + streamAnswer overrides removed (deleted from interface).

    @Override
    public CompletableFuture<String> summarize(String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public void streamChatWithTools(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        int maxTokens,
        StreamCallbacks callbacks,
        SamplingParams sampling) {

      // Record a snapshot of the messages, tools, and sampling for assertion
      recordedMessages.add(List.copyOf(messages));
      recordedTools.add(List.copyOf(tools));
      recordedSampling.add(sampling);

      if (callIndex >= responses.size()) {
        callbacks.onError().accept(new IllegalStateException("No more scripted responses"));
        return;
      }

      ScriptedResponse response = responses.get(callIndex++);

      // Emit reasoning chunks (before text, matching real model behavior)
      if (response.reasoning != null && !response.reasoning.isEmpty()) {
        callbacks.onReasoningChunk().accept(response.reasoning);
      }

      // Emit text chunks
      if (response.text != null && !response.text.isEmpty()) {
        callbacks.onChunk().accept(response.text);
      }

      // Emit tool call deltas
      for (String deltaJson : response.toolCallDeltas) {
        callbacks.onToolCallDelta().accept(deltaJson);
      }

      // Emit usage if present
      if (response.usage != null) {
        callbacks.onUsage().accept(response.usage);
      }

      // Tempdoc 881 §C.3 — the terminal callback carries the runtime's finish_reason; the loop
      // reads it now, so the fake has to be able to report one.
      callbacks.onComplete().accept(response.finishReason);
    }

    @Override
    public Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
      // Best-effort simulation: 10 tokens per message
      return Optional.of(messages.size() * 10);
    }

    @Override
    public Integer llmContextTokens() {
      return 4096; // Simulated default context window
    }

    @Override
    public Integer configuredContextTokens() {
      return 4096; // Match llmContextTokens for testing
    }
  }

  /**
   * Tempdoc 859 D live-defect D4 — a provider that REFUSES a prompt bigger than its window, the way
   * llama-server does ("request (N tokens) exceeds the available context size"), paired with a
   * projection that UNDER-COUNTS it, the way {@code countPromptTokens} does (577: schema-blind,
   * measured ~40% low).
   *
   * <p>The gap between the two IS the defect: the loop can hold a projection it believes is servable
   * while the real prompt is already over the window. A fixture where the two agree could not
   * reproduce it, and would pass whether or not the fix existed.
   */
  private static final class WindowBoundAiService implements OnlineAiService {
    /** What the loop's projection sees per message — the under-count. */
    private static final int PROJECTED_PER_MESSAGE = 10;

    /** What the message actually costs the provider (and what usage reports back). */
    private static final int REAL_PER_MESSAGE = 15;

    private final int contextWindow;
    private final List<ScriptedResponse> responses;

    /** Every prompt the provider was handed that it had to refuse. Empty is the passing state. */
    final List<Integer> refusals = new ArrayList<>();

    private int callIndex;

    WindowBoundAiService(int contextWindow, List<ScriptedResponse> responses) {
      this.contextWindow = contextWindow;
      this.responses = new ArrayList<>(responses);
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public void streamChatWithTools(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        int maxTokens,
        StreamCallbacks callbacks,
        SamplingParams sampling) {
      int realPrompt = messages.size() * REAL_PER_MESSAGE;
      if (realPrompt > contextWindow) {
        refusals.add(realPrompt);
        callbacks
            .onError()
            .accept(
                new IllegalStateException(
                    "request (" + realPrompt + " tokens) exceeds the available context size"));
        return;
      }
      if (callIndex >= responses.size()) {
        callbacks.onError().accept(new IllegalStateException("No more scripted responses"));
        return;
      }
      ScriptedResponse response = responses.get(callIndex++);
      if (response.text != null && !response.text.isEmpty()) {
        callbacks.onChunk().accept(response.text);
      }
      for (String deltaJson : response.toolCallDeltas) {
        callbacks.onToolCallDelta().accept(deltaJson);
      }
      // The provider reports the REAL prompt — which is what the context-pressure trigger reads.
      callbacks.onUsage().accept(new OnlineAiService.AiUsage(realPrompt, 5, realPrompt + 5));
      callbacks.onComplete().accept(null);
    }

    @Override
    public Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
      return Optional.of(messages.size() * PROJECTED_PER_MESSAGE);
    }

    @Override
    public Integer llmContextTokens() {
      return contextWindow;
    }

    @Override
    public Integer configuredContextTokens() {
      return contextWindow;
    }
  }

  // ===========================================================================
  // ScriptedResponse — describes what one LLM call returns
  // ===========================================================================

  record ScriptedResponse(
      String text,
      String reasoning,
      List<String> toolCallDeltas,
      OnlineAiService.AiUsage usage,
      String finishReason) {

    /** Tempdoc 881 — a response that reports no finish reason, the pre-881 fake's only shape. */
    ScriptedResponse(
        String text, String reasoning, List<String> toolCallDeltas, OnlineAiService.AiUsage usage) {
      this(text, reasoning, toolCallDeltas, usage, null);
    }

    static ScriptedResponse textOnly(String text) {
      return new ScriptedResponse(text, null, List.of(), null);
    }

    static ScriptedResponse empty() {
      return new ScriptedResponse(null, null, List.of(), null);
    }

    /**
     * Tempdoc 881 §A.2 — the shape the standard profile actually produces on a failing turn: no
     * text, no structured call, everything in the reasoning channel, and {@code finish_reason=stop}.
     */
    static ScriptedResponse reasoningOnly(String reasoning, String finishReason) {
      return new ScriptedResponse(null, reasoning, List.of(), null, finishReason);
    }

    /** The runtime's terminal reason for this scripted completion. */
    ScriptedResponse withFinishReason(String finishReason) {
      return new ScriptedResponse(text, reasoning, toolCallDeltas, usage, finishReason);
    }

    static ScriptedResponse withReasoning(String text, String reasoning) {
      return new ScriptedResponse(text, reasoning, List.of(), null);
    }

    static ScriptedResponse toolCall(String callId, String toolName, String arguments) {
      String delta = buildToolCallDeltaJson(callId, toolName, arguments);
      return new ScriptedResponse(null, null, List.of(delta), null);
    }

    static ScriptedResponse textAndToolCall(
        String text, String callId, String toolName, String arguments) {
      String delta = buildToolCallDeltaJson(callId, toolName, arguments);
      return new ScriptedResponse(text, null, List.of(delta), null);
    }

    /**
     * Creates a response with multiple tool calls in a single batch. Each triplet of arguments
     * represents (callId, toolName, arguments). Each tool call gets a unique index for the parser.
     */
    static ScriptedResponse multiToolCall(String... callSpecs) {
      if (callSpecs.length % 3 != 0) {
        throw new IllegalArgumentException("callSpecs must be triplets of (callId, toolName, args)");
      }
      List<String> deltas = new ArrayList<>();
      for (int i = 0; i < callSpecs.length; i += 3) {
        deltas.add(
            buildToolCallDeltaJson(
                i / 3, callSpecs[i], callSpecs[i + 1], callSpecs[i + 2]));
      }
      return new ScriptedResponse(null, null, deltas, null);
    }

    /** Creates a response with simulated token usage. */
    ScriptedResponse withUsage(int promptTokens, int completionTokens) {
      int total = promptTokens + completionTokens;
      return new ScriptedResponse(
          this.text, this.reasoning, this.toolCallDeltas,
          new OnlineAiService.AiUsage(promptTokens, completionTokens, total),
          this.finishReason);
    }

    /**
     * Build a JSON string matching the SSE chunk format that ToolCallParser expects:
     * {"choices":[{"delta":{"tool_calls":[{"index":N,"id":"...","function":{"name":"...","arguments":"..."}}]}}]}
     */
    private static String buildToolCallDeltaJson(String callId, String toolName, String arguments) {
      return buildToolCallDeltaJson(0, callId, toolName, arguments);
    }

    private static String buildToolCallDeltaJson(
        int index, String callId, String toolName, String arguments) {
      // Escape the arguments JSON string for embedding
      String escapedArgs = arguments.replace("\"", "\\\"");
      return "{\"choices\":[{\"delta\":{\"tool_calls\":[{"
          + "\"index\":" + index + ","
          + "\"id\":\"" + callId + "\","
          + "\"function\":{\"name\":\"" + toolName + "\","
          + "\"arguments\":\"" + escapedArgs + "\"}"
          + "}]}}]}";
    }
  }

  // Tempdoc 417 Phase 2d/3e: legacy FakeTelemetry deleted. Counter assertions now go through
  // a TestMetricRegistry-backed catalog supplied via AgentLoopService.forTesting(...).

  // ---------------------------------------------------------------------------
  // operationHistory contract — tempdoc 415 follow-up (C44)
  // Backend now emits "failedCount" (was "failureCount") to match the frontend.
  // ---------------------------------------------------------------------------

  @Test
  void operationHistory_emitsFailedCountKey() throws Exception {
    // Write a batch JSON file directly so we don't need package-private FileOperationLog
    // mutators. The contract under test is the toBatchSummary() projection over
    // FileOperationLog.listBatches(), reached via AgentLoopService.operationHistory().
    var fileOpsDir = tempDir.resolve("file-ops");
    java.nio.file.Files.createDirectories(fileOpsDir);
    String batchJson =
        "{"
            + "\"batchId\":\"batch-c44\","
            + "\"timestamp\":\"2026-04-28T10:00:00Z\","
            + "\"explanation\":\"Mixed-status batch\","
            + "\"operations\":["
            + "  {\"op\":\"MOVE\",\"src\":\"/a.txt\",\"dst\":\"/b.txt\"},"
            + "  {\"op\":\"MOVE\",\"src\":\"/c.txt\",\"dst\":\"/d.txt\"},"
            + "  {\"op\":\"MOVE\",\"src\":\"/e.txt\",\"dst\":\"/f.txt\"}"
            + "],"
            + "\"executed\":["
            + "  {\"index\":0,\"status\":\"OK\"},"
            + "  {\"index\":1,\"status\":\"FAILED\",\"error\":\"io\"},"
            + "  {\"index\":2,\"status\":\"SKIPPED\",\"reason\":\"user\"}"
            + "],"
            + "\"finalized\":\"2026-04-28T10:00:01Z\""
            + "}";
    java.nio.file.Files.writeString(fileOpsDir.resolve("batch-c44.json"), batchJson);

    var ai = new ScriptedAiService(List.of(ScriptedResponse.textOnly("ok")));
    var fileOpLog = new FileOperationLog(fileOpsDir);
    var service =
        new AgentLoopService(
            ai,
            OperationCatalog.of("core", List.of()),
            stubExecutor(),
            stubEmitter(),
            fileOpLog,
            null);

    var summaries = service.operationHistory(10);
    assertEquals(1, summaries.size(), "Expected exactly one batch summary");
    var summary = summaries.get(0);

    assertTrue(summary.containsKey("failedCount"),
        "Summary must carry the renamed 'failedCount' key (tempdoc 415 follow-up C44)");
    assertFalse(summary.containsKey("failureCount"),
        "Old 'failureCount' key must not be re-introduced");
    assertEquals(1L, summary.get("failedCount"));
    assertEquals(1L, summary.get("successCount"));
    assertEquals(1L, summary.get("skippedCount"));
  }
}
