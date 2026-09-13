package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.TraceContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConversationEngine} + {@link ToolIteratingShapeRunner} — Phase B
 * skeleton + agent encapsulation verification.
 *
 * <p>The live wire-shape compatibility (FE consumes the same SSE event vocabulary) is
 * verified by these tests' byte-for-byte translation assertions. End-to-end live
 * verification against a running backend is Phase 6 of the implementation slice.
 */
final class ConversationEngineTest {

  @Test
  @DisplayName("Engine routes the agent shape to its ShapeRunner")
  void engineRoutesAgentShapeToRunner() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.SessionStarted("test-session-id", TraceContext.none()));
              sink.accept(
                  new AgentEvent.AgentDone("ok", 1, 0, 42, TraceContext.none()));
            });

    ConversationEngine engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(() -> agentService)));

    var events = new ArrayList<SseEvent>();
    engine.run(
        AgentRunShape.ID,
        Map.of(
            "messages", List.of(Map.of("role", "user", "content", "hi")),
            "tools", List.of(),
            "maxIterations", 1),
        Audience.USER,
        events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(2, events.size(), "expected SessionStarted + AgentDone");
    assertEquals("session_started", events.get(0).name());
    assertEquals("test-session-id", events.get(0).payload().get("sessionId"));
    assertEquals("done", events.get(1).name());
    assertEquals("ok", events.get(1).payload().get("finalResponse"));
    assertEquals(1, events.get(1).payload().get("iterationsUsed"));
    assertEquals(42L, ((Number) events.get(1).payload().get("totalTokensUsed")).longValue());

    assertEquals(1, capturedRequest.get().messages().size());
    assertEquals("hi", capturedRequest.get().messages().get(0).get("content"));
  }

  @Test
  @DisplayName("Engine validates audience and denies USER for an OPERATOR-class shape")
  void engineDeniesInsufficientAudience() {
    // Build a shape catalog with one OPERATOR-class shape (none of the CORE shapes are
    // OPERATOR-class today, so we construct a synthetic one).
    var operatorShape =
        new io.justsearch.agent.api.registry.ConversationShape(
            new ConversationShapeRef("core.operator-only"),
            new io.justsearch.agent.api.registry.Presentation(
                new io.justsearch.agent.api.registry.I18nKey("test.label"),
                new io.justsearch.agent.api.registry.I18nKey("test.desc"),
                java.util.Optional.empty(),
                java.util.Optional.empty()),
            Audience.OPERATOR,
            io.justsearch.agent.api.registry.Provenance.core("v1"),
            io.justsearch.agent.api.conversation.ExecutionMode.SHAPE_DRIVEN,
            io.justsearch.agent.api.conversation.IterationMode.ONE_SHOT,
            io.justsearch.agent.api.conversation.PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(),
            false);
    var catalog =
        io.justsearch.agent.api.registry.ConversationShapeCatalog.of("core", List.of(operatorShape));
    var engine = new ConversationEngine(catalog, List.of());

    assertThrows(
        ConversationEngine.AudienceDeniedException.class,
        () ->
            engine.run(
                operatorShape.id(),
                Map.of(),
                Audience.USER,
                ev -> {
                  /* sink */
                }, io.justsearch.app.services.TestEngineContexts.internal()));
  }

  // ── Tempdoc 863 slice A — the delegate turn on the answer plane ───────────────────────────────

  private static ConversationEngine engineWithStore(
      AgentService agentService, io.justsearch.agent.api.conversation.ConversationStore store) {
    return new ConversationEngine(
        CoreConversationShapeCatalog.catalog(),
        List.of(new ToolIteratingShapeRunner(() -> agentService)),
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of()),
        StreamConsumerRegistry.of(List.of()),
        IterationControllerRegistry.of(List.of()),
        io.justsearch.app.api.OnlineAiService::unavailable,
        store);
  }

  @Test
  @DisplayName(
      "863 §4.A.2: a delegate dispatch records the CLEAN user turn and the answer on the"
          + " conversation record, with the run plane's evidence")
  void delegateDispatchRecordsBothTurnsWithEvidence() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var source =
        new AgentEvent.AgentSource("doc-7", 0, "a/b.md", "B", "the excerpt", 1, 4, "Heading");
    var cite = new AgentEvent.AgentSentenceCite("the answer.", 0, 0.87);
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.SessionStarted("run-1", TraceContext.none()));
              sink.accept(
                  new AgentEvent.AgentDone(
                      "the answer",
                      1,
                      2,
                      42,
                      List.of(source),
                      List.of(cite),
                      "cross-encoder",
                      "BUDGET_EXHAUSTED"));
            });
    var store = new RecordingStore();
    var engine = engineWithStore(agentService, store);

    engine.run(
        AgentRunShape.ID,
        Map.of(
            "messages", List.of(Map.of("role", "user", "content", "delegate this")),
            "conversationId", "uc-delegate-1",
            "maxIterations", 1),
        Audience.USER,
        ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    // THE STAMP reached the request, which is what carries it into the run meta and from there into
    // the thread projection's suppression.
    assertTrue(capturedRequest.get().recordsToThread(), "the engine stamped the dispatch");

    List<Map<String, Object>> recorded = store.appended.get("uc-delegate-1");
    assertEquals(2, recorded.size(), "exactly the user turn + the assistant turn");

    assertEquals("user", recorded.get(0).get("role"));
    // Read from body.messages' last role:"user" entry — the agent body names its input `messages`,
    // not the four scalar fields the ask shapes post.
    assertEquals("delegate this", recorded.get(0).get("content"));

    Map<String, Object> answer = recorded.get(1);
    assertEquals("assistant", answer.get("role"));
    assertEquals("the answer", answer.get("content"), "the done payload's finalResponse");
    // A-3 (evidence parity): the three attributes the run plane carried and the store plane did not.
    assertEquals(List.of(Map.of(
        "parentDocId", "doc-7",
        "chunkIndex", 0,
        "path", "a/b.md",
        "title", "B",
        "excerpt", "the excerpt",
        "startLine", 1,
        "endLine", 4,
        "headingText", "Heading",
        // Tempdoc 868 §B.3 — the acquisition axis rides the store plane too, or a reloaded
        // conversation would silently re-describe an opened document as retrieved.
        "acquisition", AgentEvent.AgentSource.ACQUISITION_RETRIEVED)),
        answer.get("sources"));
    assertEquals("cross-encoder", answer.get("citationScorer"));
    assertEquals("BUDGET_EXHAUSTED", answer.get("disposition"));
    assertEquals(1, ((List<?>) answer.get("citations")).size());
    // ... and the two that the agent `done` genuinely does not produce stay ABSENT, not zeroed.
    assertFalse(answer.containsKey("calibration"), "no calibration key for an agent payload");
    assertFalse(answer.containsKey("claimMatches"), "no claimMatches key for an agent payload");
  }

  @Test
  @DisplayName(
      "863 §4.A.2: an ungrounded delegate answer carries NO evidence keys — absent, not empty")
  void delegateDispatchOmitsEmptyEvidence() {
    var agentService =
        new StubAgentService(
            (request, sink) ->
                // The 4-arg overload: no sources, no citations. The done payload still WRITES those
                // keys as empty lists, which is exactly the shape a plain null-check would persist as
                // a claimed zero.
                sink.accept(new AgentEvent.AgentDone("plain answer", 1, 0, 7)));
    var store = new RecordingStore();

    engineWithStore(agentService, store)
        .run(
            AgentRunShape.ID,
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "q")),
                "conversationId", "uc-delegate-2",
                "maxIterations", 1),
            Audience.USER,
            ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    Map<String, Object> answer = store.appended.get("uc-delegate-2").get(1);
    assertEquals("plain answer", answer.get("content"));
    assertFalse(answer.containsKey("sources"), "an empty sources list is not a fact");
    assertFalse(answer.containsKey("citations"), "an empty citations list is not a fact");
    assertFalse(answer.containsKey("disposition"), "the emitter did not say");
  }

  @Test
  @DisplayName(
      "863 F2: the answer append failing MID-RUN does not kill the run, and the answer is not on"
          + " the record — which is what leaves the run plane's copy standing")
  void answerAppendFailingMidRunLeavesTheUserTurnAndNoAnswer() {
    // The adverse precondition the dispatch-time 423 cannot cover: the store is writable when the
    // run starts and stops being writable before it ends (the reader hits Lock during a long run).
    var doneSeen = new java.util.concurrent.atomic.AtomicBoolean();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              sink.accept(new AgentEvent.AgentDone("the answer", 1, 0, 9));
              doneSeen.set(true);
            });
    var store =
        new RecordingStore() {
          @Override
          public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
            if ("assistant".equals(message.get("role"))) {
              throw new IllegalStateException("store locked mid-run");
            }
            super.appendMessage(sessionId, shapeId, message);
          }
        };

    engineWithStore(agentService, store)
        .run(
            AgentRunShape.ID,
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "q")),
                "conversationId", "uc-midrun",
                "maxIterations", 1),
            Audience.USER,
            ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    // The run completes: a store failure at the terminal event must not abort the agent loop's own
    // bookkeeping after the reader already has the answer on screen.
    assertTrue(doneSeen.get(), "the run reached its terminal done");
    List<Map<String, Object>> recorded = store.appended.get("uc-midrun");
    assertEquals(1, recorded.size(), "the user turn landed; the answer did not");
    assertEquals("user", recorded.get(0).get("role"));
    // And because it did not, the thread controller will not name this run as answered, so
    // AgentRunQueryService keeps its run-plane answer (see that module's F2 test). The two halves
    // together are what make "suppressed but never recorded" unreachable.
  }

  @Test
  @DisplayName(
      "863 F2: a reader who disconnects mid-run still gets the answer RECORDED — the durable write"
          + " does not depend on an audience")
  void aThrowingSinkDoesNotCostTheRecordItsAnswer() {
    // `AgentSseWriter.writeOrEvict` throws by design, so a run drops an observer that went away.
    // Forwarding the terminal `done` before recording it would make the store write conditional on
    // someone still watching: close the tab during a long run and the conversation comes back with a
    // question and no answer, while the run completed normally.
    var agentService =
        new StubAgentService(
            (request, sink) -> sink.accept(new AgentEvent.AgentDone("the answer", 1, 0, 9)));
    var store = new RecordingStore();

    try {
      engineWithStore(agentService, store)
          .run(
              AgentRunShape.ID,
              Map.of(
                  "messages", List.of(Map.of("role", "user", "content", "q")),
                  "conversationId", "uc-gone",
                  "maxIterations", 1),
              Audience.USER,
              ev -> {
                throw new IllegalStateException("observer evicted");
              }, io.justsearch.app.services.TestEngineContexts.internal());
    } catch (RuntimeException expected) {
      // The eviction propagates exactly as it did before; what must not depend on it is the record.
    }

    List<Map<String, Object>> recorded = store.appended.get("uc-gone");
    assertEquals(2, recorded.size(), "the question AND the answer are on the record");
    assertEquals("the answer", recorded.get(1).get("content"));
  }

  @Test
  @DisplayName("863 F2: a recorded answer carries its runId, which is what names it as answered")
  void recordedAnswerCarriesItsRunId() {
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              sink.accept(new AgentEvent.SessionStarted("run-77", TraceContext.none()));
              sink.accept(new AgentEvent.AgentDone("the answer", 1, 0, 9));
            });
    var store = new RecordingStore();

    engineWithStore(agentService, store)
        .run(
            AgentRunShape.ID,
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "q")),
                "conversationId", "uc-runid",
                "maxIterations", 1),
            Audience.USER,
            ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    Map<String, Object> answer = store.appended.get("uc-runid").get(1);
    assertEquals("run-77", answer.get("runId"), "observed off the run's own session_started");
  }

  @Test
  @DisplayName(
      "863 §4.A.3: a delegate dispatch with no conversationId records nothing and is NOT stamped")
  void standaloneDelegateDispatchIsNotStamped() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.AgentDone("ok", 1, 0, 1));
            });
    var store = new RecordingStore();

    engineWithStore(agentService, store)
        .run(
            AgentRunShape.ID,
            Map.of("messages", List.of(Map.of("role", "user", "content", "q")), "maxIterations", 1),
            Audience.USER,
            ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(store.appended.isEmpty(), "no write key, so nothing recorded");
    assertFalse(
        capturedRequest.get().recordsToThread(),
        "an unstamped run keeps BOTH of the thread projection's syntheses — it is their only record");
  }

  @Test
  @DisplayName(
      "863 §4.A.3: the stamp is the ENGINE's, not the caller's — a body that claims it is overridden")
  void clientSuppliedStampIsOverridden() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.AgentDone("ok", 1, 0, 1));
            });
    var store = new RecordingStore();

    // A client posting `recordsToThread: true` on a dispatch the engine records NOTHING for would,
    // if the runner trusted the body, suppress both syntheses for a run with no store rows at all —
    // the delegate turn would vanish from the thread entirely.
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("messages", List.of(Map.of("role", "user", "content", "q")));
    body.put("maxIterations", 1);
    body.put("recordsToThread", true);

    engineWithStore(agentService, store).run(AgentRunShape.ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(capturedRequest.get().recordsToThread(), "the engine's answer, not the caller's");
  }

  @Test
  @DisplayName("863 §4.A.3: the engine will not stamp a run against a store that keeps nothing")
  void noOpStoreIsNeverStamped() {
    var capturedRequest = new AtomicReference<AgentRequest>();
    var agentService =
        new StubAgentService(
            (request, sink) -> {
              capturedRequest.set(request);
              sink.accept(new AgentEvent.AgentDone("ok", 1, 0, 1));
            });
    // The 2-arg constructor's store is ConversationStore.noop(): it accepts every append and keeps
    // none. Stamping against it would suppress the run plane's own record of a turn that was written
    // nowhere.
    var engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(() -> agentService)));

    engine.run(
        AgentRunShape.ID,
        Map.of(
            "messages", List.of(Map.of("role", "user", "content", "q")),
            "conversationId", "uc-delegate-3",
            "maxIterations", 1),
        Audience.USER,
        ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(capturedRequest.get().recordsToThread(), "no recording store, no stamp");
  }

  @Test
  @DisplayName("863 §4.A.1: core.agent-run declares recordsToThread, core.workflow-run does not")
  void agentRunShapeDeclaresRecordsToThread() {
    var catalog = CoreConversationShapeCatalog.catalog();
    assertTrue(
        catalog.findById(AgentRunShape.ID).orElseThrow().recordsToThread(),
        "the shape 863 promoted the component for");
    assertFalse(
        catalog.findById(WorkflowRunShape.ID).orElseThrow().recordsToThread(),
        "a workflow run's answer lives in its per-node outputs; a store turn would double it");
    // Every other core shape keeps exactly what the retired derivation returned.
    for (var shape : catalog.definitions()) {
      if (shape.id().equals(AgentRunShape.ID)) {
        continue;
      }
      boolean derived =
          shape.audience() == Audience.USER
              && shape.executionMode()
                  == io.justsearch.agent.api.conversation.ExecutionMode.SUBSTRATE_DRIVEN;
      assertEquals(
          derived,
          shape.recordsToThread(),
          () -> shape.id().value() + " must keep its pre-863 derived value");
    }
  }

  @Test
  @DisplayName("Engine throws ShapeNotFoundException for unregistered shape ids")
  void engineRejectsUnknownShape() {
    var engine =
        new ConversationEngine(
            CoreConversationShapeCatalog.catalog(),
            List.of(new ToolIteratingShapeRunner(AgentService::unavailable)));

    assertThrows(
        ConversationEngine.ShapeNotFoundException.class,
        () ->
            engine.run(
                new ConversationShapeRef("core.no-such-shape"),
                Map.of(),
                Audience.USER,
                ev -> {
                  /* sink */
                }, io.justsearch.app.services.TestEngineContexts.internal()));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner emits service-unavailable when agent isn't available")
  void runnerReportsUnavailable() {
    var runner = new ToolIteratingShapeRunner(AgentService::unavailable);
    var events = new ArrayList<SseEvent>();
    runner.run(Map.of("messages", List.of()), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(1, events.size());
    assertEquals("error", events.get(0).name());
    assertEquals("SERVICE_UNAVAILABLE", events.get(0).payload().get("errorCode"));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner translates ToolCallProposed -> tool_call_proposed")
  void runnerTranslatesToolCallEvents() {
    var call =
        new io.justsearch.agent.api.ToolCallRequest(
            "call-1", "core_search_index", "{\"query\":\"x\"}");
    var event =
        new AgentEvent.ToolCallProposed(
            call, io.justsearch.agent.api.registry.RiskTier.LOW, TraceContext.none());
    SseEvent sse = AgentEventSseTranslator.translate(event, null, Map.of());
    assertEquals("tool_call_proposed", sse.name());
    assertEquals("call-1", sse.payload().get("callId"));
    assertEquals("core_search_index", sse.payload().get("toolName"));
    assertEquals("low", sse.payload().get("risk"));
  }

  @Test
  @DisplayName("ToolIteratingShapeRunner parses body with profiles + maxHandoffs")
  void runnerParsesBody() {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
    body.put("tools", List.of("core_search_index"));
    body.put("maxIterations", 5);
    body.put(
        "agentProfiles",
        List.of(
            Map.of(
                "agentId",
                "primary",
                "name",
                "Primary",
                "systemPrompt",
                "You are a helpful assistant.",
                "toolSubset",
                List.<String>of())));
    body.put("initialAgentId", "primary");
    body.put("maxHandoffs", 3);

    AgentRequest request = ToolIteratingShapeRunner.parseRequest(body);
    assertEquals(1, request.messages().size());
    assertEquals("core_search_index", request.selectedToolNames().get(0));
    assertEquals(5, request.maxIterations());
    assertEquals(1, request.agentProfiles().size());
    assertEquals("primary", request.initialAgentId());
    assertEquals(3, request.maxHandoffs().intValue());
  }

  /**
   * F1 — stub StreamConsumer that records onDone invocations + emits a caller-supplied
   * SseEvent. The id() value is critical: it MUST match an id declared in
   * {@code AgentRunShape.streamConsumerIds()} for the runner to resolve and invoke it.
   */
  private static final class RecordingStreamConsumer
      implements io.justsearch.agent.api.conversation.StreamConsumer {
    final String id;
    final SseEvent emittedEvent;
    final AtomicReference<String> capturedFullText = new AtomicReference<>();

    RecordingStreamConsumer(String id, SseEvent emitted) {
      this.id = id;
      this.emittedEvent = emitted;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public io.justsearch.agent.api.conversation.StreamConsumerResult onChunk(
        String chunkText, io.justsearch.agent.api.conversation.ConversationContext ctx) {
      return io.justsearch.agent.api.conversation.StreamConsumerResult.empty();
    }

    @Override
    public io.justsearch.agent.api.conversation.StreamConsumerResult onDone(
        String fullText, io.justsearch.agent.api.conversation.ConversationContext ctx) {
      capturedFullText.set(fullText);
      return new io.justsearch.agent.api.conversation.StreamConsumerResult(
          List.of(emittedEvent), List.of(), List.of(), Map.of());
    }
  }

  @Test
  @DisplayName(
      "Slice 491 F1: runner resolves declared streamConsumerId 'core.url-extractor'"
          + " via the registry and fires its onDone on AgentDone")
  void runnerResolvesDeclaredStreamConsumerFromRegistry() {
    var urlExtractor =
        new RecordingStreamConsumer(
            "core.url-extractor",
            new SseEvent(
                "navigate.url_extracted",
                Map.of("index", 0, "target", "core.library-surface")));
    var registry = StreamConsumerRegistry.of(List.of(urlExtractor));

    var agent =
        new StubAgentService(
            (req, sink) -> {
              sink.accept(new AgentEvent.TextChunk("Take me to ", TraceContext.none()));
              sink.accept(
                  new AgentEvent.TextChunk(
                      "justsearch://surface/core.library-surface", TraceContext.none()));
              sink.accept(new AgentEvent.AgentDone("ok", 1, 0, 0, TraceContext.none()));
            });

    var runner = new ToolIteratingShapeRunner(() -> agent, registry);
    var events = new ArrayList<SseEvent>();
    runner.run(
        Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
        Audience.USER,
        events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(
        "Take me to justsearch://surface/core.library-surface",
        urlExtractor.capturedFullText.get());
    // Expected order: chunk, chunk, navigate.url_extracted (from extractor),
    // done (translated AgentDone). Total 4 events.
    assertEquals(4, events.size(), "chunk + chunk + url_extracted + done");
    assertEquals("chunk", events.get(0).name());
    assertEquals("chunk", events.get(1).name());
    assertEquals("navigate.url_extracted", events.get(2).name());
    assertEquals("done", events.get(3).name());
  }

  @Test
  @DisplayName(
      "Slice 491 F1: runner with empty registry warns but doesn't break — declared"
          + " streamConsumerId without registry registration logs + skips, agent run"
          + " completes")
  void runnerWithEmptyRegistryDegradesCleanly() {
    var agent =
        new StubAgentService(
            (req, sink) -> {
              sink.accept(new AgentEvent.TextChunk("hello", TraceContext.none()));
              sink.accept(new AgentEvent.AgentDone("hello", 1, 0, 0, TraceContext.none()));
            });
    var runner = new ToolIteratingShapeRunner(() -> agent);
    var events = new ArrayList<SseEvent>();
    runner.run(
        Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
        Audience.USER,
        events::add, io.justsearch.app.services.TestEngineContexts.internal());
    // The shape declares core.url-extractor; registry doesn't have it → warn + skip.
    // Only the translated AgentEvents appear in the sink.
    assertEquals(2, events.size(), "missing consumer registration → only chunk + done");
    assertEquals("chunk", events.get(0).name());
    assertEquals("done", events.get(1).name());
  }

  // ---------------------- Test stub ----------------------

  /** Stub {@link AgentService} that delegates {@code runAgent} to a caller-supplied lambda. */
  private static final class StubAgentService implements AgentService {

    private final java.util.function.BiConsumer<AgentRequest, Consumer<AgentEvent>> runner;

    StubAgentService(java.util.function.BiConsumer<AgentRequest, Consumer<AgentEvent>> runner) {
      this.runner = runner;
    }

    @Override
    public void runAgent(
        AgentRequest request, Consumer<AgentEvent> eventConsumer,
        io.justsearch.core.context.EngineContext engineContext) {
      runner.accept(request, eventConsumer);
    }

    @Override
    public void approveToolCall(String sessionId, String callId) {}

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {}

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public OperationResult undoOperation(
        String toolName, String executionId,
        io.justsearch.core.context.EngineContext engineContext) {
      return null;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @SuppressWarnings("unused")
    private void noWarnIsAvailable() {
      assertTrue(true);
      assertFalse(false);
    }
  }
}
