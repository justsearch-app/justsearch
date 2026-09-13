package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationController;
import io.justsearch.agent.api.conversation.IterationDecision;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConversationEngine}'s substrate-driven execution path (Phase C0).
 *
 * <p>Verifies: prompt-fragment ordering by priority, injector chain composition, stream
 * consumer dispatch (onChunk + onDone), message-delta append between iterations, and the
 * three {@link IterationDecision} terminations.
 */
final class SubstrateDrivenEngineTest {

  private static final ConversationShapeRef SHAPE_ID = new ConversationShapeRef("core.test-shape");

  @Test
  @DisplayName("Substrate-driven path assembles prompt, injects, calls LLM, emits done")
  void oneShotPath() {
    var contributor = new RecordingContributor("core.test-contrib", "TEST SYSTEM PROMPT", 10);
    var injector =
        new RecordingInjector(
            "core.test-injector",
            List.of(Map.of("role", "user", "content", "Hello, model.")));
    var consumer = new RecordingConsumer("core.test-consumer");
    var llm = new ScriptedAi(List.of("the response"));
    var engine = newEngine(oneShotShape(List.of(contributor.id()), List.of(injector.id()), List.of(consumer.id())),
        List.of(contributor), List.of(injector), List.of(consumer), List.of(), llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // System prompt assembled with the contributor's text.
    assertEquals(1, llm.calls.size(), "exactly one LLM call");
    var sentMessages = llm.calls.get(0);
    assertEquals("system", sentMessages.get(0).get("role"));
    assertTrue(((String) sentMessages.get(0).get("content")).contains("TEST SYSTEM PROMPT"));
    // Injector message present.
    assertEquals("Hello, model.", sentMessages.get(1).get("content"));

    // Chunk + done events emitted to the sink.
    long chunkCount = events.stream().filter(e -> "chunk".equals(e.name())).count();
    assertEquals(1, chunkCount, "one chunk emitted");
    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("the response", done.payload().get("finalResponse"));
    assertEquals(1, done.payload().get("iterationsUsed"));

    // Consumer received both onChunk and onDone.
    assertEquals(1, consumer.chunkCalls);
    assertEquals(1, consumer.doneCalls);
    assertEquals("the response", consumer.lastDoneText);
    // No schema in the body → no response_format constraint, sampling stays at the engine default.
    assertNull(
        llm.samplingCalls.get(0),
        "absent schema must leave sampling untouched (no response_format injected)");
  }

  @Test
  @DisplayName("done payload carries the full token triple, completionTokens included")
  void donePayloadCarriesCompletionTokens() {
    // Tempdoc 835 §9c.4: AiUsage always carried completionTokens and the engine dropped it, so
    // "how much of the completion budget did reasoning eat?" was unanswerable — reasoning and
    // answer tokens share this number, making it the denominator for every budget decision.
    var llm = new UsageReportingAi("the response", 48, 512, 560);
    var engine = newEngine(oneShotShape(List.of(), List.of(), List.of()),
        List.of(), List.of(), List.of(), List.of(), llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals(48, done.payload().get("promptTokens"));
    assertEquals(512, done.payload().get("completionTokens"));
    assertEquals(560, done.payload().get("totalTokens"));
  }

  @Test
  @DisplayName(
      "tempdoc 569 Phase 5: a request-body schema is promoted to a server-side response_format"
          + " constraint so iteration-1 is schema-valid by construction")
  void schemaPromotedToResponseFormat() {
    var contributor = new RecordingContributor("core.test-contrib", "SYS", 10);
    var llm = new ScriptedAi(List.of("{\"ok\":true}"));
    var engine =
        newEngine(
            oneShotShape(List.of(contributor.id()), List.of(), List.of()),
            List.of(contributor),
            List.of(),
            List.of(),
            List.of(),
            llm);

    String schemaJson = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";
    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of("schema", schemaJson), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(1, llm.samplingCalls.size(), "exactly one LLM call");
    var sampling = llm.samplingCalls.get(0);
    assertNotNull(sampling, "schema present → sampling params constructed");
    assertNotNull(sampling.responseFormat(), "schema must be promoted to response_format");
    assertEquals("json_object", sampling.responseFormat().get("type"));
    // The parsed schema map (not the raw string) rides on response_format.schema.
    @SuppressWarnings("unchecked")
    Map<String, Object> carried = (Map<String, Object>) sampling.responseFormat().get("schema");
    assertEquals("object", carried.get("type"));
    // Default structured-extraction preset: near-deterministic, thinking disabled.
    assertEquals(SamplingParams.DETERMINISTIC.temperature(), sampling.temperature());
    assertEquals(Boolean.FALSE, sampling.enableThinking());
  }

  @Test
  @DisplayName(
      "tempdoc 569 Phase 5: an unparseable schema degrades to validate-retry only (no"
          + " response_format, sampling untouched) rather than failing the request")
  void unparseableSchemaDegradesGracefully() {
    var contributor = new RecordingContributor("core.test-contrib", "SYS", 10);
    var llm = new ScriptedAi(List.of("{}"));
    var engine =
        newEngine(
            oneShotShape(List.of(contributor.id()), List.of(), List.of()),
            List.of(contributor),
            List.of(),
            List.of(),
            List.of(),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of("schema", "}{ not json"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(1, llm.samplingCalls.size());
    assertNull(
        llm.samplingCalls.get(0),
        "unparseable schema must not inject response_format — the validate-retry loop still guards");
  }

  @Test
  @DisplayName("Multiple PromptContributors compose by priority ascending")
  void promptOrdering() {
    var a = new RecordingContributor("core.a", "AAA", 50);
    var b = new RecordingContributor("core.b", "BBB", 10);
    var c = new RecordingContributor("core.c", "CCC", 90);
    var llm = new ScriptedAi(List.of("ok"));
    var engine = newEngine(oneShotShape(List.of(a.id(), b.id(), c.id()), List.of(), List.of()),
        List.of(a, b, c), List.of(), List.of(), List.of(), llm);

    engine.run(SHAPE_ID, Map.of(), Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    String systemPrompt = (String) llm.calls.get(0).get(0).get("content");
    int posA = systemPrompt.indexOf("AAA");
    int posB = systemPrompt.indexOf("BBB");
    int posC = systemPrompt.indexOf("CCC");
    assertTrue(posB < posA && posA < posC, "ascending priority: BBB(10) < AAA(50) < CCC(90)");
  }

  @Test
  @DisplayName("ContextInjectors compose in declaration order")
  void injectorOrdering() {
    var first = new RecordingInjector("core.first", List.of(Map.of("role", "user", "content", "FIRST")));
    var second = new RecordingInjector("core.second", List.of(Map.of("role", "user", "content", "SECOND")));
    var llm = new ScriptedAi(List.of("ok"));
    var engine = newEngine(oneShotShape(List.of(), List.of(first.id(), second.id()), List.of()),
        List.of(), List.of(first, second), List.of(), List.of(), llm);

    engine.run(SHAPE_ID, Map.of(), Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    var messages = llm.calls.get(0);
    // No system prompt (no contributors); both injected messages are present in declaration order.
    assertEquals("FIRST", messages.get(0).get("content"));
    assertEquals("SECOND", messages.get(1).get("content"));
  }

  @Test
  @DisplayName("StreamConsumer messageDeltas are appended before the next iteration")
  void multiIterationWithDeltas() {
    // Bound-iteration: after first call, controller says CONTINUE; after second, STOP_SUCCESS.
    var controller = new BoundedController("core.bounded", 2);
    var consumer =
        new RecordingConsumer("core.delta-consumer") {
          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            super.onDone(fullText, ctx);
            // First onDone reports a "deltaA" message; second reports "deltaB".
            String delta = doneCalls == 1 ? "deltaA" : "deltaB";
            return new StreamConsumerResult(
                List.of(),
                List.of(),
                List.of(Map.of("role", "tool", "content", delta)),
                Map.of());
          }
        };
    var llm = new ScriptedAi(List.of("first", "second"));
    var engine =
        newEngine(
            iteratingShape(List.of(), List.of(), List.of(consumer.id()), controller.id()),
            List.of(),
            List.of(),
            List.of(consumer),
            List.of(controller),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // Two LLM calls.
    assertEquals(2, llm.calls.size());
    // Second call's message list includes the assistant from iter 1, plus deltaA.
    var secondCallMessages = llm.calls.get(1);
    boolean sawDeltaA =
        secondCallMessages.stream().anyMatch(m -> "deltaA".equals(m.get("content")));
    assertTrue(sawDeltaA, "Second iteration must see deltaA appended by the consumer");
    // Final done emitted.
    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("second", done.payload().get("finalResponse"));
    assertEquals(2, done.payload().get("iterationsUsed"));
  }

  @Test
  @DisplayName("Controller returning STOP_ERROR emits an error event and terminates")
  void stopErrorPath() {
    var controller =
        new IterationController() {
          @Override
          public String id() {
            return "core.error-controller";
          }

          @Override
          public IterationDecision next(ConversationContext ctx) {
            return IterationDecision.STOP_ERROR;
          }
        };
    var llm = new ScriptedAi(List.of("ok"));
    var engine =
        newEngine(
            iteratingShape(List.of(), List.of(), List.of(), controller.id()),
            List.of(),
            List.of(),
            List.of(),
            List.of(controller),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("CONTROLLER_STOP", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("LLM unavailable surfaces an AI_OFFLINE error event")
  void llmUnavailable() {
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(oneShotShape(List.of(), List.of(), List.of()))),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            OnlineAiService::unavailable);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("AI_OFFLINE", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("LLM error is surfaced via emitError with LLM_ERROR code")
  void llmCallErrors() {
    var failingAi = new FailingAi(new RuntimeException("connection refused"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(), List.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            failingAi);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("LLM_ERROR", err.payload().get("errorCode"));
    assertNotNull(err.payload().get("error"));
  }

  @Test
  @DisplayName("E2: ContextInjector events are forwarded to sink before the LLM call")
  void injectorEventsForwarded() {
    var emittingInjector =
        new ContextInjector() {
          @Override
          public String id() {
            return "core.emitting-injector";
          }

          @Override
          public io.justsearch.agent.api.conversation.InjectorResult inject(
              ConversationContext ctx) {
            return io.justsearch.agent.api.conversation.InjectorResult.of(
                List.of(Map.of("role", "user", "content", "user-text")),
                List.of(new SseEvent("rag.meta", Map.of("retrieval_mode", "BM25"))));
          }
        };
    var llm = new ScriptedAi(List.of("answer"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(emittingInjector.id()), List.of()),
            List.of(),
            List.of(emittingInjector),
            List.of(),
            List.of(),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    int ragMetaIdx = -1, chunkIdx = -1;
    for (int i = 0; i < events.size(); i++) {
      if ("rag.meta".equals(events.get(i).name())) ragMetaIdx = i;
      if ("chunk".equals(events.get(i).name()) && chunkIdx < 0) chunkIdx = i;
    }
    assertTrue(ragMetaIdx >= 0, "rag.meta event must be emitted");
    assertTrue(chunkIdx > ragMetaIdx, "rag.meta must precede first chunk");
    assertEquals("BM25", events.get(ragMetaIdx).payload().get("retrieval_mode"));
  }

  @Test
  @DisplayName("E2: ContextInjector terminalError aborts before the LLM call")
  void injectorTerminalErrorAborts() {
    var failingInjector =
        new ContextInjector() {
          @Override
          public String id() {
            return "core.failing-injector";
          }

          @Override
          public io.justsearch.agent.api.conversation.InjectorResult inject(
              ConversationContext ctx) {
            return io.justsearch.agent.api.conversation.InjectorResult.terminalError(
                new SseEvent("error", Map.of("error", "missing question", "errorCode", "NO_QUESTION")));
          }
        };
    var llm = new ScriptedAi(List.of("should-not-be-called"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(failingInjector.id()), List.of()),
            List.of(),
            List.of(failingInjector),
            List.of(),
            List.of(),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(0, llm.calls.size(), "LLM must not be called after terminalError");
    SseEvent err =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals("NO_QUESTION", err.payload().get("errorCode"));
  }

  @Test
  @DisplayName("E1: StreamConsumer donePayloadEntries merge into the done event payload")
  void doneEntriesMerged() {
    var enricher =
        new StreamConsumer() {
          @Override
          public String id() {
            return "core.enricher";
          }

          @Override
          public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            return StreamConsumerResult.donePayloadOnly(
                Map.of("docId", "doc-1", "summary", fullText, "hierarchical", false));
          }
        };
    var llm = new ScriptedAi(List.of("the answer"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(), List.of(enricher.id())),
            List.of(),
            List.of(),
            List.of(enricher),
            List.of(),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("doc-1", done.payload().get("docId"));
    assertEquals("the answer", done.payload().get("summary"));
    assertEquals(false, done.payload().get("hierarchical"));
    // Substrate defaults still present and not overridden.
    assertEquals("the answer", done.payload().get("finalResponse"));
    assertEquals(1, done.payload().get("iterationsUsed"));
  }

  @Test
  @DisplayName("E1: Substrate-default keys override consumer entries with the same key")
  void substrateDefaultsTakePrecedence() {
    var collidingEnricher =
        new StreamConsumer() {
          @Override
          public String id() {
            return "core.colliding-enricher";
          }

          @Override
          public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            return StreamConsumerResult.donePayloadOnly(
                Map.of("finalResponse", "consumer-override", "iterationsUsed", 99));
          }
        };
    var llm = new ScriptedAi(List.of("real-answer"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(), List.of(collidingEnricher.id())),
            List.of(),
            List.of(),
            List.of(collidingEnricher),
            List.of(),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("real-answer", done.payload().get("finalResponse"));
    assertEquals(1, done.payload().get("iterationsUsed"));
  }

  @Test
  @DisplayName(
      "ContextInjector.inject() is invoked exactly once even across multiple iterations (iteration-0-only invariant)")
  void injectorIterationZeroOnly() {
    var injectCalls = new AtomicInteger(0);
    var trackingInjector =
        new ContextInjector() {
          @Override
          public String id() {
            return "core.tracking-injector";
          }

          @Override
          public io.justsearch.agent.api.conversation.InjectorResult inject(
              ConversationContext ctx) {
            injectCalls.incrementAndGet();
            return io.justsearch.agent.api.conversation.InjectorResult.of(
                List.of(Map.of("role", "user", "content", "context-msg")), List.of());
          }
        };
    var controller = new BoundedController("core.three-iter", 3);
    var llm = new ScriptedAi(List.of("a", "b", "c"));
    var engine =
        newEngine(
            iteratingShape(
                List.of(), List.of(trackingInjector.id()), List.of(), controller.id()),
            List.of(),
            List.of(trackingInjector),
            List.of(),
            List.of(controller),
            llm);

    engine.run(SHAPE_ID, Map.of(), Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(3, llm.calls.size(), "three iterations ran");
    assertEquals(
        1, injectCalls.get(), "injector must run exactly once (iteration 0), not per-iteration");
  }

  @Test
  @DisplayName("The running shape's id reaches SPIs via ConversationContext.shapeId()")
  void contextCarriesShapeId() {
    var seen = new java.util.concurrent.atomic.AtomicReference<String>("<never-invoked>");
    var probe =
        new ContextInjector() {
          @Override
          public String id() {
            return "core.shape-id-probe";
          }

          @Override
          public io.justsearch.agent.api.conversation.InjectorResult inject(
              ConversationContext ctx) {
            seen.set(ctx.shapeId());
            return io.justsearch.agent.api.conversation.InjectorResult.empty();
          }
        };
    var llm = new ScriptedAi(List.of("ok"));
    var engine =
        newEngine(
            oneShotShape(List.of(), List.of(probe.id()), List.of()),
            List.of(),
            List.of(probe),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            llm);

    engine.run(SHAPE_ID, Map.of(), Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    // A shared SPI serving several shapes (SelectionContextInjector) needs to know which one is
    // asking; the request body is not a reliable source (the per-shape routes carry no shapeId).
    assertEquals(SHAPE_ID.value(), seen.get());
  }

  @Test
  @DisplayName(
      "Engine enforces hard cap of 20 iterations; runaway controller terminates with iterationsUsed=20 done")
  void iterationCapEnforced() {
    var callCount = new AtomicInteger(0);
    var infiniteController =
        new IterationController() {
          @Override
          public String id() {
            return "core.infinite";
          }

          @Override
          public IterationDecision next(ConversationContext ctx) {
            callCount.incrementAndGet();
            return IterationDecision.CONTINUE;
          }
        };
    // Provide enough scripted responses to cover the 20-iteration cap with margin.
    var responses = new ArrayList<String>();
    for (int i = 0; i < 25; i++) {
      responses.add("tick-" + i);
    }
    var llm = new ScriptedAi(responses);
    var engine =
        newEngine(
            iteratingShape(List.of(), List.of(), List.of(), infiniteController.id()),
            List.of(),
            List.of(),
            List.of(),
            List.of(infiniteController),
            llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(
        20, llm.calls.size(), "with a never-stopping controller, exactly the cap of 20 LLM calls must fire");
    // Engine emits a final `done` (not `error`) when the cap is reached — see
    // ConversationEngine.java:310-312. iterationsUsed reports the cap.
    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals(20, done.payload().get("iterationsUsed"));
    // The accumulated text from the last iteration is delivered.
    assertEquals("tick-19", done.payload().get("finalResponse"));
  }

  @Test
  @DisplayName(
      "Wedge regression (836 §9.9): a producer that never calls back ends the turn with an"
          + " LLM_TIMEOUT error instead of parking the request thread forever")
  void stalledProducerEndsTheTurnLoudly() {
    // The wedge shape: streaming shares one executor thread, so a stream task that never returns
    // leaves every later dispatch queued with no callback of any kind. Pre-fix streamLlm awaited an
    // unbounded latch here, so the request thread parked for as long as the process lived.
    var silentAi =
        new OnlineAiService() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public boolean isStartingUp() {
            return false;
          }

          @Override
          public CompletableFuture<String> summarize(String content) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
          }

          @Override
          public CompletableFuture<String> askQuestion(String q, String c) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
          }

          @Override
          public void stream(StreamRequest request, StreamSink sink) {
            // Never calls any sink callback — the queued-behind-a-wedged-thread case.
          }
        };
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(oneShotShape(List.of(), List.of(), List.of()))),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> silentAi);
    engine.llmStallDeadline = Duration.ofMillis(300);

    var events = new ArrayList<SseEvent>();
    long startNanos = System.nanoTime();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

    SseEvent error =
        events.stream().filter(e -> "error".equals(e.name())).findFirst().orElseThrow();
    assertEquals(
        "LLM_TIMEOUT",
        error.payload().get("errorCode"),
        "a stalled producer must surface as its own reason code, not silence");
    assertTrue(
        events.stream().noneMatch(e -> "done".equals(e.name())),
        "an abandoned turn must not also report done");
    assertTrue(elapsedMs < 30_000, "the dispatch must return on the bound, not park: " + elapsedMs + "ms");
  }

  @Test
  @DisplayName(
      "Regression (slice 491 defect 1): finalResponse must be the chunk-accumulator, not the LLM finish_reason — onComplete receives \"stop\"/\"length\", not the response body")
  void finalResponseIgnoresFinishReasonCallback() {
    // OnlineAiService.streamChat's onComplete actually receives the LLM
    // finish_reason ("stop", "length", "tool_calls", etc.) per
    // OnlineModeOps.java:326. The engine must build finalResponse from chunks,
    // not from the onComplete value. Pre-fix, this yielded
    // done.finalResponse == "stop" — observed live during slice 491 C1 happy-path
    // verification on 2026-05-12.
    var finishReasonAi =
        new OnlineAiService() {
          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public boolean isStartingUp() {
            return false;
          }

          @Override
          public CompletableFuture<String> summarize(String content) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
          }

          @Override
          public CompletableFuture<String> askQuestion(String q, String c) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
          }

          @Override
          public void stream(StreamRequest request, StreamSink sink) {
            // Emit the response in two chunks so the chunk-accumulator path is
            // exercised, then deliver the LLM finish_reason ("stop") through
            // onComplete — mirroring OnlineModeOps' real callsite.
            sink.onContent().accept("Hello ");
            sink.onContent().accept("world");
            sink.onComplete().accept("stop");
          }
        };
    var engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(oneShotShape(List.of(), List.of(), List.of()))),
            List.of(),
            PromptContributorRegistry.of(List.of()),
            ContextInjectorRegistry.of(List.of()),
            StreamConsumerRegistry.of(List.of()),
            IterationControllerRegistry.of(List.of()),
            () -> finishReasonAi);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done =
        events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals(
        "Hello world",
        done.payload().get("finalResponse"),
        "finalResponse must be the chunk-accumulator text, not the LLM finish_reason");
  }

  @Test
  @DisplayName(
      "Slice 496 §3.C pre-flight: SUBSTRATE_DRIVEN + WITHIN_TURN_ITERATION + CONTINUE"
          + " drives 2 LLM calls with correction delta injected between iterations")
  void multiIterationSubstrateDrivenPath() {
    // Scenario: LLM returns "INVALID" on iteration 0, "VALID" on iteration 1.
    // A validation consumer checks the output; if INVALID, it writes an attribute +
    // appends a correction-prompt message delta. The controller reads the attribute
    // and returns CONTINUE on iteration 0, STOP_SUCCESS on iteration 1.
    var llm = new ScriptedAi(List.of("INVALID", "VALID"));
    var injector = new RecordingInjector("core.test-injector",
        List.of(Map.of("role", "user", "content", "Produce valid output")));

    // Validation consumer: checks output, sets attribute, appends correction delta.
    StreamConsumer validationConsumer = new StreamConsumer() {
      @Override
      public String id() { return "core.test-validator-consumer"; }

      @Override
      public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
        return StreamConsumerResult.empty();
      }

      @Override
      public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
        boolean valid = "VALID".equals(fullText.trim());
        ctx.attributes().put("validation.passed", valid);
        if (!valid) {
          // Correction message delta — the engine appends it before the next
          // iteration's LLM call, so the LLM sees the correction prompt.
          List<Map<String, Object>> delta = List.of(
              Map.of("role", "user", "content",
                  "Your output was invalid. Please output the exact word VALID."));
          // messageDeltas is the 3rd arg (not 2nd which is sideEffectsExecuted).
          return new StreamConsumerResult(List.of(), List.of(), delta, Map.of());
        }
        return StreamConsumerResult.empty();
      }
    };

    // Validating controller: reads attribute, decides CONTINUE or STOP_SUCCESS.
    IterationController validatingController = new IterationController() {
      @Override
      public String id() { return "core.test-validator-controller"; }

      @Override
      public IterationDecision next(ConversationContext ctx) {
        Object passed = ctx.attributes().get("validation.passed");
        if (Boolean.TRUE.equals(passed)) {
          return IterationDecision.STOP_SUCCESS;
        }
        if (ctx.iteration() >= 3) {
          return IterationDecision.STOP_ERROR;
        }
        return IterationDecision.CONTINUE;
      }
    };

    var engine = newEngine(
        iteratingShape(List.of(), List.of(injector.id()), List.of(validationConsumer.id()),
            validatingController.id()),
        List.of(), List.of(injector), List.of(validationConsumer),
        List.of(validatingController), llm);

    var events = new ArrayList<SseEvent>();
    engine.run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    // Two LLM calls.
    assertEquals(2, llm.calls.size(), "expected 2 LLM calls (INVALID then VALID)");

    // Second LLM call should include the correction delta from the consumer.
    var secondCallMessages = llm.calls.get(1);
    boolean hasCorrectionPrompt = secondCallMessages.stream().anyMatch(
        m -> "user".equals(m.get("role"))
            && m.get("content") != null
            && ((String) m.get("content")).contains("Your output was invalid"));
    assertTrue(hasCorrectionPrompt,
        "second LLM call must include the correction message delta from the validation consumer");

    // Final response is "VALID" (iteration 1's output).
    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertEquals("VALID", done.payload().get("finalResponse"));
    assertEquals(2, done.payload().get("iterationsUsed"));
  }

  // ---------- Test fixtures ----------

  @Test
  @DisplayName(
      "561 P-A/P-B: an EPHEMERAL recordsToThread shape records the CLEAN user turn + assistant turn"
          + " (with citations + producer calibration) under conversationId, WITHOUT loading history")
  void ephemeralRecordsToThreadPersistsEvidenceUnderConversationId() {
    // The injector rewrites the user message into a context-augmented blob for the LLM (as RAGContext
    // does). The thread must record the CLEAN question, never this blob.
    var injector =
        new RecordingInjector(
            "core.rag-like-injector",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    "Documents:\n<retrieved doc blob that must NOT appear in the thread>\n\n"
                        + "Question: what is x?")));
    // A consumer that emits evidence on the done payload, like RAGDoneEnricher.
    var citation =
        Map.<String, Object>of("parentDocId", "doc-7", "startChar", 0, "excerpt", "x is the thing");
    var calibration =
        Map.<String, Object>of("bestChunkScore", 0.91, "retrievalCoverage", 0.5, "chunksConsidered", 4);
    StreamConsumer evidenceConsumer =
        new StreamConsumer() {
          @Override
          public String id() {
            return "core.evidence-consumer";
          }

          @Override
          public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            Map<String, Object> entries = new LinkedHashMap<>();
            entries.put("citations", List.of(citation));
            entries.put("calibration", calibration);
            return StreamConsumerResult.donePayloadOnly(entries);
          }
        };
    var llm = new ScriptedAi(List.of("grounded answer"));
    var store = new RecordingStore();
    var engine =
        newEngineWithStore(
            oneShotShape(List.of(), List.of(injector.id()), List.of(evidenceConsumer.id())),
            List.of(),
            List.of(injector),
            List.of(evidenceConsumer),
            List.of(),
            llm,
            store);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("conversationId", "uc-thread-1");
    body.put("question", "what is x?");
    engine.run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    // EPHEMERAL: history is NEVER loaded (fresh LLM context preserved) ...
    assertEquals(0, store.loadHistoryCount, "EPHEMERAL shape must not load history");
    // ... but BOTH turns are recorded onto the canonical record under the conversationId.
    List<Map<String, Object>> recorded = store.appended.get("uc-thread-1");
    assertNotNull(recorded, "turns recorded under the request conversationId");
    assertEquals(2, recorded.size(), "exactly the clean user turn + the assistant turn");

    Map<String, Object> userTurn = recorded.get(0);
    assertEquals("user", userTurn.get("role"));
    // The CLEAN question — NOT the context-augmented injector blob.
    assertEquals("what is x?", userTurn.get("content"));

    Map<String, Object> assistantTurn = recorded.get(1);
    assertEquals("assistant", assistantTurn.get("role"));
    assertEquals("grounded answer", assistantTurn.get("content"));
    // Evidence first-class on the record: citations + producer calibration.
    assertEquals(List.of(citation), assistantTurn.get("citations"));
    assertEquals(calibration, assistantTurn.get("calibration"));
  }

  @Test
  @DisplayName(
      "610 §J.3: the engine seeds the persisted source-exclusion onto ctx for an EPHEMERAL"
          + " recordsToThread shape — keyed by conversationId (the threadId), not just sessionId."
          + " Regression: the original sessionId-only guard never fired for the documents RAG path.")
  void ephemeralShapeSeedsSourceExclusionFromStore() {
    var captured = new java.util.concurrent.atomic.AtomicReference<Object>();
    var injector =
        new ContextInjector() {
          @Override
          public String id() {
            return "core.capture-injector";
          }

          @Override
          public io.justsearch.agent.api.conversation.InjectorResult inject(
              ConversationContext ctx) {
            captured.set(
                ctx.attributes()
                    .get(
                        io.justsearch.app.services.conversation.spi.RAGContext
                            .ATTR_EXCLUDED_SOURCES));
            return io.justsearch.agent.api.conversation.InjectorResult.of(List.of(), List.of());
          }
        };
    var hidden = List.of("docA" + (char) 0x1f + "2");
    var store = new RecordingStore();
    store.excludedSources.put("uc-thread-excl", hidden);
    var llm = new ScriptedAi(List.of("answer"));
    var engine =
        newEngineWithStore(
            oneShotShape(List.of(), List.of(injector.id()), List.of()),
            List.of(),
            List.of(injector),
            List.of(),
            List.of(),
            llm,
            store);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("conversationId", "uc-thread-excl");
    body.put("question", "q");
    engine.run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(
        hidden,
        captured.get(),
        "RAGContext must see the persisted exclusion seeded from the conversationId/threadId");
  }

  // --- Tempdoc 834 §4.3: the turn-open marker ---------------------------------------------------

  /**
   * The eight exits of {@code dispatchSubstrateDriven}, each as a fully-wired scenario. A spot check
   * on two of them is exactly the bug the design named: clearing at selected exits would leave every
   * cleanly-errored run marked "interrupted", the inverse dishonesty of not marking at all.
   */
  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> theEightExits() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("injector terminated", Exit.INJECTOR_TERMINATED),
        org.junit.jupiter.params.provider.Arguments.of("AI unavailable", Exit.AI_UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of("LlmStreamException", Exit.LLM_STREAM_ERROR),
        org.junit.jupiter.params.provider.Arguments.of("consumer onDone threw", Exit.CONSUMER_THREW),
        org.junit.jupiter.params.provider.Arguments.of("controller next threw", Exit.CONTROLLER_THREW),
        org.junit.jupiter.params.provider.Arguments.of("STOP_SUCCESS", Exit.STOP_SUCCESS),
        org.junit.jupiter.params.provider.Arguments.of("STOP_ERROR", Exit.STOP_ERROR),
        org.junit.jupiter.params.provider.Arguments.of("iteration hard cap", Exit.HARD_CAP));
  }

  private enum Exit {
    INJECTOR_TERMINATED,
    AI_UNAVAILABLE,
    LLM_STREAM_ERROR,
    CONSUMER_THREW,
    CONTROLLER_THREW,
    STOP_SUCCESS,
    STOP_ERROR,
    HARD_CAP
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "exit: {0}")
  @org.junit.jupiter.params.provider.MethodSource("theEightExits")
  @DisplayName("tempdoc 834 §4.3: the turn-open marker is cleared on every one of the eight exits")
  void turnOpenMarkerIsClearedOnEveryExit(String label, Exit exit) {
    var store = new TurnMarkerStore();
    var engine = engineForExit(exit, store);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    engine.run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(store.open.isEmpty(), label + ": the turn-open marker must be cleared, not left set");
  }

  @Test
  @DisplayName("tempdoc 834 §4.3: an ITERATING shape opens the marker; a ONE_SHOT ask never does")
  void onlyIteratingShapesOpenTheMarker() {
    // The gap is per-iteration persistence: an iterating shape writes one assistant turn per
    // iteration, so a crash at iteration 3 of 8 leaves three turns reading as a finished answer.
    // A ONE_SHOT ask persists once, complete — no gap, so no marker (834 §4.1 vs §4.3).
    var iterating = new TurnMarkerStore();
    var controller = new BoundedController("core.two-iter", 2);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentIteratingShape(controller.id()),
            List.of(),
            List.of(),
            List.of(),
            List.of(controller),
            new ScriptedAi(List.of("a", "b")),
            iterating)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(List.of(true, false), iterating.transitions, "opened before iteration 0, then cleared");

    var oneShot = new TurnMarkerStore();
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            new ScriptedAi(List.of("a")),
            oneShot)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(List.of(), oneShot.transitions, "a one-shot ask needs no marker at all");
  }

  @Test
  @DisplayName("tempdoc 834 §4.3: the marker is genuinely SET while generation is in flight")
  void theMarkerIsSetDuringGeneration() {
    // The falsifier for the eight-exit test: if setTurnOpen(true) never happened, "always cleared"
    // would pass vacuously. A process killed at THIS instant — mid-iteration, with an assistant
    // turn already persisted and more to come — leaves the marker set on disk, which is exactly
    // the condition it encodes. That is unreachable in-process because a `finally` also runs on a
    // throw, so the honest test is to observe the state at the moment of maximum exposure.
    var store = new TurnMarkerStore();
    var openDuringGeneration = new java.util.concurrent.atomic.AtomicBoolean();
    var probe =
        new StreamConsumer() {
          @Override
          public String id() {
            return "core.mid-run-probe";
          }

          @Override
          public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
            return StreamConsumerResult.empty();
          }

          @Override
          public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
            openDuringGeneration.set(store.isTurnOpen("conv-1"));
            return StreamConsumerResult.empty();
          }
        };
    var controller = new BoundedController("core.two-iter", 2);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentIteratingShape(controller.id(), List.of(), List.of(probe.id())),
            List.of(),
            List.of(),
            List.of(probe),
            List.of(controller),
            new ScriptedAi(List.of("a", "b")),
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(openDuringGeneration.get(), "the marker must be set while the turn is still open");
    assertTrue(store.open.isEmpty(), "and cleared once the run finishes");
  }

  // ==================== Tempdoc 848 — reasoning is turn data ====================

  @Test
  @DisplayName("tempdoc 848 §2.1: the turn's reasoning round-trips onto the persisted record")
  void reasoningPersistsOnTheAssistantRecord() {
    var store = new RecordingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            new ReasoningAi(List.of(List.of("weigh ", "options")), List.of("the answer")),
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    Map<String, Object> persisted = assistantRecords(store, "conv-1").get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> blocks = (List<Map<String, Object>>) persisted.get("reasoning");
    assertNotNull(blocks, "the thinking the run produced belongs on the record");
    assertEquals(1, blocks.size(), "one LLM call, one block");
    assertEquals("weigh options", blocks.get(0).get("text"), "chunks accumulate like fullText does");
    assertTrue(
        ((Number) blocks.get(0).get("durationMs")).longValue() >= 0,
        "durationMs is a real interval, never negative");
  }

  @Test
  @DisplayName("tempdoc 848 F2: think → answer → think persists TWO blocks, one per thinking region")
  void reasoningRegionsBecomeSeparateBlocks() {
    // The live `ReasoningController` cuts a block at every `endThinking()` — i.e. at the first content
    // token after each thinking region — so a single-block-per-call record would disagree with the
    // live render on BOTH the block count and the duration (which would cover only region one).
    var store = new RecordingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            new FramedAi(
                List.of(
                    Frame.think("first "),
                    Frame.think("region"),
                    Frame.text("Partial answer. "),
                    Frame.think("second region"),
                    Frame.text("Rest of it."))),
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    Map<String, Object> persisted = assistantRecords(store, "conv-1").get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> blocks = (List<Map<String, Object>>) persisted.get("reasoning");
    assertNotNull(blocks);
    assertEquals(2, blocks.size(), "one block per thinking REGION, as the live controller cuts them");
    assertEquals("first region", blocks.get(0).get("text"));
    assertEquals("second region", blocks.get(1).get("text"), "region two is its own block, not appended");
    for (Map<String, Object> block : blocks) {
      assertTrue(
          ((Number) block.get("durationMs")).longValue() >= 0,
          "each region is timed on its own interval, not the whole call");
    }
    assertEquals("Partial answer. Rest of it.", persisted.get("content"), "content is unaffected");
  }

  @Test
  @DisplayName("tempdoc 848 §2.1: a turn that did not think carries NO reasoning key (not an empty list)")
  void noReasoningKeyWhenTheModelDidNotThink() {
    var store = new RecordingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            new ScriptedAi(List.of("plain answer")),
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    Map<String, Object> persisted = assistantRecords(store, "conv-1").get(0);
    assertTrue(
        !persisted.containsKey("reasoning"),
        "absent means 'the model did not think' — an empty array would claim otherwise");
  }

  @Test
  @DisplayName("tempdoc 848 §2.2: an ITERATING shape's turn N carries iteration N's reasoning only")
  void reasoningIsPerIteration() {
    // The falsifier for an accumulator hoisted OUT of streamLlm: a shared builder would leave
    // iteration 2's record carrying iteration 1's thinking as well.
    var store = new RecordingStore();
    var controller = new BoundedController("core.two-iter", 2);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    newEngineWithStore(
            persistentIteratingShape(controller.id()),
            List.of(),
            List.of(),
            List.of(),
            List.of(controller),
            new ReasoningAi(List.of(List.of("first pass"), List.of("second pass")), List.of("a", "b")),
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    List<Map<String, Object>> records = assistantRecords(store, "conv-1");
    assertEquals(2, records.size(), "one persisted turn per iteration");
    assertEquals("first pass", firstBlockText(records.get(0)));
    assertEquals("second pass", firstBlockText(records.get(1)), "no cross-iteration bleed");
  }

  @Test
  @DisplayName("tempdoc 848 §2.2: reasoning is persisted-only — the done payload does NOT carry it")
  void donePayloadCarriesNoReasoning() {
    // It already streamed chunk-by-chunk; shipping it again on `done` would re-send the same fact on
    // the wire and oblige every shape's done-payload vocabulary to declare it.
    var events = new ArrayList<SseEvent>();
    newEngine(
            oneShotShape(List.of(), List.of(), List.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new ReasoningAi(List.of(List.of("thinking")), List.of("the answer")))
        .run(SHAPE_ID, Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());

    SseEvent done = events.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
    assertTrue(!done.payload().containsKey("reasoning"), "persisted-only, never on the done payload");
    long reasoningChunks = events.stream().filter(e -> "reasoning_chunk".equals(e.name())).count();
    assertEquals(1, reasoningChunks, "the live delivery path is unchanged");
  }

  @Test
  @DisplayName(
      "tempdoc 848 §1.7: a schema-constrained call with no explicit enableThinking resolves to"
          + " DETERMINISTIC — it does not think, so nothing is persisted")
  void schemaWithoutExplicitThinkingPersistsNoReasoning() {
    var store = new RecordingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    body.put("schema", "{\"type\":\"object\"}");
    var llm = new ScriptedAi(List.of("{}"));
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            llm,
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(
        Boolean.FALSE,
        llm.samplingCalls.get(0).enableThinking(),
        "no caller-supplied sampling → the schema constraint bases on DETERMINISTIC");
    assertTrue(!assistantRecords(store, "conv-1").get(0).containsKey("reasoning"));
  }

  @Test
  @DisplayName(
      "tempdoc 848 §1.7: the CALLER's explicit request to think wins over the schema's default, and"
          + " that turn's reasoning IS persisted — behaviour, not a leak")
  void explicitEnableThinkingSurvivesTheSchemaAndPersists() {
    // `parseSamplingParams` returns non-null whenever the body carries `enableThinking`, so
    // `applySchemaConstraint` adds a response_format WITHOUT substituting DETERMINISTIC. A future
    // reader porting extract onto the thinking rungs must not "fix" this into a suppression branch:
    // the persisted key follows what the model RECEIVED, never what the request nominally wanted.
    var store = new RecordingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    body.put("schema", "{\"type\":\"object\"}");
    body.put("enableThinking", Boolean.TRUE);
    var llm = new ReasoningAi(List.of(List.of("deliberate")), List.of("{}"));
    newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            llm,
            store)
        .run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(
        Boolean.TRUE,
        llm.samplingCalls.get(0).enableThinking(),
        "the caller's explicit value survives the schema constraint");
    assertEquals("deliberate", firstBlockText(assistantRecords(store, "conv-1").get(0)));
  }

  @Test
  @DisplayName(
      "tempdoc 848 §2.2b: turn N+1's LLM input carries assistant entries of EXACTLY {role, content}")
  void llmInputProjectsAwayPersistenceOnlyKeys() {
    // Without the boundary projection, every persisted extra — `reasoning`, and the pre-existing
    // `id`/`hash`/`ts` — is re-sent to the server inside the OpenAI-compat `messages` body on the
    // next turn of a PERSISTENT conversation: silent prompt inflation proportional to conversation
    // length, in the one shape whose whole point is a long-lived thread.
    var store = new ReplayingStore();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", "conv-1");
    var llm = new ReasoningAi(List.of(List.of("turn one thinking")), List.of("first answer"));
    var engine =
        newEngineWithStore(
            persistentOneShotShape(),
            List.of(),
            List.of(),
            List.of(),
            List.of(SingleHopController.INSTANCE),
            llm,
            store);
    engine.run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    // Sanity: turn 1 really persisted a reasoning array, so turn 2 has something to leak.
    assertNotNull(assistantRecords(store, "conv-1").get(0).get("reasoning"));

    engine.run(SHAPE_ID, body, Audience.USER, ev -> {}, io.justsearch.app.services.TestEngineContexts.internal());

    assertEquals(2, llm.calls.size(), "two turns");
    List<Map<String, Object>> secondInput = llm.calls.get(1);
    List<Map<String, Object>> assistants =
        secondInput.stream().filter(m -> "assistant".equals(m.get("role"))).toList();
    assertEquals(1, assistants.size(), "turn 1's assistant message is in turn 2's context");
    for (Map<String, Object> message : assistants) {
      assertEquals(
          java.util.Set.of("role", "content"),
          message.keySet(),
          "the model contract's keys and nothing else — no reasoning, no id/hash/ts");
    }
  }

  private static List<Map<String, Object>> assistantRecords(RecordingStore store, String sessionId) {
    return store.appended.getOrDefault(sessionId, List.of()).stream()
        .filter(m -> "assistant".equals(m.get("role")))
        .toList();
  }

  private static String firstBlockText(Map<String, Object> persisted) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> blocks = (List<Map<String, Object>>) persisted.get("reasoning");
    assertNotNull(blocks, "expected the record to carry reasoning");
    return (String) blocks.get(0).get("text");
  }

  private ConversationEngine engineForExit(Exit exit, ConversationStore store) {
    List<ContextInjector> injectors = List.of();
    List<StreamConsumer> consumers = List.of();
    OnlineAiService llm = new ScriptedAi(List.of("a", "b", "c"));
    IterationController controller = new BoundedController("core.bounded", 1);

    switch (exit) {
      case INJECTOR_TERMINATED -> injectors = List.of(new TerminalInjector());
      case AI_UNAVAILABLE -> llm = new UnavailableAi();
      case LLM_STREAM_ERROR -> llm = new FailingAi(new IllegalStateException("stream blew up"));
      case CONSUMER_THREW -> consumers = List.of(new ThrowingConsumer());
      case CONTROLLER_THREW -> controller = new ThrowingController();
      case STOP_SUCCESS -> controller = new BoundedController("core.stop-success", 1);
      case STOP_ERROR -> controller = new FixedController("core.stop-error", IterationDecision.STOP_ERROR);
      case HARD_CAP -> {
        controller = new FixedController("core.infinite", IterationDecision.CONTINUE);
        var responses = new ArrayList<String>();
        for (int i = 0; i < 25; i++) {
          responses.add("tick-" + i);
        }
        llm = new ScriptedAi(responses);
      }
    }
    return newEngineWithStore(
        persistentIteratingShape(
            controller.id(),
            injectors.stream().map(ContextInjector::id).toList(),
            consumers.stream().map(StreamConsumer::id).toList()),
        List.of(),
        injectors,
        consumers,
        List.of(controller),
        llm,
        store);
  }

  /** Records every setTurnOpen transition and the current open set. */
  private static final class TurnMarkerStore implements ConversationStore {
    final List<Boolean> transitions = new ArrayList<>();
    final java.util.Set<String> open = new java.util.LinkedHashSet<>();

    @Override
    public void setTurnOpen(String sessionId, boolean isOpen) {
      transitions.add(isOpen);
      if (isOpen) {
        open.add(sessionId);
      } else {
        open.remove(sessionId);
      }
    }

    @Override
    public boolean isTurnOpen(String sessionId) {
      return open.contains(sessionId);
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      return List.of();
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {}

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      return List.of();
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      return Optional.empty();
    }

    @Override
    public void deleteSession(String sessionId) {}

    @Override
    public void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return List.of();
    }

    @Override
    public void compactContext(String sessionId, String floorMessageId, String summaryText) {}

    @Override
    public void excludeMessage(String sessionId, String messageId, boolean excluded) {}

    @Override
    public List<String> excludedMessageIds(String sessionId) {
      return List.of();
    }
  }

  private static final class TerminalInjector implements ContextInjector {
    @Override
    public String id() {
      return "core.terminal-injector";
    }

    @Override
    public io.justsearch.agent.api.conversation.InjectorResult inject(ConversationContext ctx) {
      return io.justsearch.agent.api.conversation.InjectorResult.terminalError(
          new SseEvent("error", Map.of("error", "missing input", "errorCode", "BAD_REQUEST")));
    }
  }

  private static final class ThrowingConsumer implements StreamConsumer {
    @Override
    public String id() {
      return "core.throwing-consumer";
    }

    @Override
    public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
      return StreamConsumerResult.empty();
    }

    @Override
    public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
      throw new IllegalStateException("consumer blew up");
    }
  }

  private static final class ThrowingController implements IterationController {
    @Override
    public String id() {
      return "core.throwing-controller";
    }

    @Override
    public IterationDecision next(ConversationContext ctx) {
      throw new IllegalStateException("controller blew up");
    }
  }

  private static final class FixedController implements IterationController {
    private final String id;
    private final IterationDecision decision;

    FixedController(String id, IterationDecision decision) {
      this.id = id;
      this.decision = decision;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public IterationDecision next(ConversationContext ctx) {
      return decision;
    }
  }

  private static final class UnavailableAi implements OnlineAiService {
    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("offline"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("offline"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      throw new UnsupportedOperationException("the engine must not reach an unavailable AI");
    }
  }

  private static ConversationShape persistentIteratingShape(String controllerId) {
    return persistentIteratingShape(controllerId, List.of(), List.of());
  }

  private static ConversationShape persistentIteratingShape(
      String controllerId, List<String> injIds, List<String> consumerIds) {
    return persistentShape(IterationMode.WITHIN_TURN_ITERATION, controllerId, injIds, consumerIds);
  }

  private static ConversationShape persistentOneShotShape() {
    return persistentShape(IterationMode.ONE_SHOT, SingleHopController.ID, List.of(), List.of());
  }

  private static ConversationShape persistentShape(
      IterationMode mode, String controllerId, List<String> injIds, List<String> consumerIds) {
    return new ConversationShape(
        SHAPE_ID,
        new Presentation(
            new I18nKey("test.label"), new I18nKey("test.desc"), Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        mode,
        PersistenceMode.PERSISTENT,
        List.of(),
        injIds,
        consumerIds,
        controllerId,
        List.of(),
        true);
  }

  private ConversationEngine newEngine(
      ConversationShape shape,
      List<PromptContributor> contributors,
      List<ContextInjector> injectors,
      List<StreamConsumer> consumers,
      List<IterationController> controllers,
      OnlineAiService llm) {
    return new ConversationEngine(
        ConversationShapeCatalog.of("core", List.of(shape)),
        List.of(),
        PromptContributorRegistry.of(contributors),
        ContextInjectorRegistry.of(injectors),
        StreamConsumerRegistry.of(consumers),
        IterationControllerRegistry.of(controllers),
        () -> llm);
  }

  private ConversationEngine newEngineWithStore(
      ConversationShape shape,
      List<PromptContributor> contributors,
      List<ContextInjector> injectors,
      List<StreamConsumer> consumers,
      List<IterationController> controllers,
      OnlineAiService llm,
      ConversationStore store) {
    return new ConversationEngine(
        ConversationShapeCatalog.of("core", List.of(shape)),
        List.of(),
        PromptContributorRegistry.of(contributors),
        ContextInjectorRegistry.of(injectors),
        StreamConsumerRegistry.of(consumers),
        IterationControllerRegistry.of(controllers),
        () -> llm,
        store);
  }

  private static ConversationShape oneShotShape(
      List<String> contribIds, List<String> injIds, List<String> consumerIds) {
    return new ConversationShape(
        SHAPE_ID,
        new Presentation(
            new I18nKey("test.label"),
            new I18nKey("test.desc"),
            Optional.empty(),
            Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        contribIds,
        injIds,
        consumerIds,
        SingleHopController.ID,
        List.of(),
        true);
  }

  private static ConversationShape iteratingShape(
      List<String> contribIds, List<String> injIds, List<String> consumerIds, String controllerId) {
    return new ConversationShape(
        SHAPE_ID,
        new Presentation(
            new I18nKey("test.label"),
            new I18nKey("test.desc"),
            Optional.empty(),
            Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.WITHIN_TURN_ITERATION,
        PersistenceMode.EPHEMERAL,
        contribIds,
        injIds,
        consumerIds,
        controllerId,
        List.of(),
        true);
  }

  /** A PromptContributor with fixed text + priority. */
  private static final class RecordingContributor implements PromptContributor {
    private final String id;
    private final String text;
    private final int priority;

    RecordingContributor(String id, String text, int priority) {
      this.id = id;
      this.text = text;
      this.priority = priority;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public Optional<PromptFragment> contribute(ConversationContext ctx) {
      return Optional.of(new PromptFragment(text, priority));
    }
  }

  /** A ContextInjector that returns a fixed message list. */
  private static final class RecordingInjector implements ContextInjector {
    private final String id;
    private final List<Map<String, Object>> messages;

    RecordingInjector(String id, List<Map<String, Object>> messages) {
      this.id = id;
      this.messages = messages;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public io.justsearch.agent.api.conversation.InjectorResult inject(ConversationContext ctx) {
      return io.justsearch.agent.api.conversation.InjectorResult.messagesOnly(messages);
    }
  }

  /** A StreamConsumer that records onChunk/onDone calls. */
  private static class RecordingConsumer implements StreamConsumer {
    final String id;
    int chunkCalls;
    int doneCalls;
    String lastDoneText;

    RecordingConsumer(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
      chunkCalls++;
      return StreamConsumerResult.empty();
    }

    @Override
    public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
      doneCalls++;
      lastDoneText = fullText;
      return StreamConsumerResult.empty();
    }
  }

  /** An IterationController that returns CONTINUE for the first N calls, then STOP_SUCCESS. */
  private static final class BoundedController implements IterationController {
    private final String id;
    private final int continueCalls;
    private final AtomicInteger calls = new AtomicInteger(0);

    BoundedController(String id, int continueCalls) {
      this.id = id;
      this.continueCalls = continueCalls;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public IterationDecision next(ConversationContext ctx) {
      int n = calls.incrementAndGet();
      return n < continueCalls ? IterationDecision.CONTINUE : IterationDecision.STOP_SUCCESS;
    }
  }

  /** Scripted OnlineAiService that returns a sequence of text responses. */
  /** ScriptedAi that also reports token usage, like a real stream with {@code include_usage}. */
  private static final class UsageReportingAi implements OnlineAiService {
    private final String response;
    private final AiUsage usage;

    UsageReportingAi(String response, Integer prompt, Integer completion, Integer total) {
      this.response = response;
      this.usage = new AiUsage(prompt, completion, total);
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
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      sink.onContent().accept(response);
      sink.onUsage().accept(usage);
      sink.onComplete().accept("stop");
    }
  }

  private static class ScriptedAi implements OnlineAiService {
    final List<String> responses;
    final AtomicInteger callIndex = new AtomicInteger(0);
    final List<List<Map<String, Object>>> calls = new ArrayList<>();
    final List<SamplingParams> samplingCalls = new ArrayList<>();

    ScriptedAi(List<String> responses) {
      this.responses = responses;
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
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("unused in test"));
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      List<Map<String, Object>> snapshot = new ArrayList<>(request.messages().size());
      for (Map<String, Object> m : request.messages()) {
        snapshot.add(new LinkedHashMap<>(m));
      }
      calls.add(snapshot);
      samplingCalls.add(request.sampling());
      int idx = callIndex.getAndIncrement();
      String text = idx < responses.size() ? responses.get(idx) : "";
      emit(sink, idx, text);
      sink.onComplete().accept("stop");
    }

    /** The per-call emission, overridable so a subclass can drive other sink channels first. */
    void emit(StreamSink sink, int callIdx, String text) {
      sink.onContent().accept(text);
    }
  }

  /**
   * Tempdoc 848 — a ScriptedAi that also drives the REASONING callback: per call, the reasoning
   * chunks first (as a thinking model streams them), then the content. Extends the suite's existing
   * capture surface ({@code calls} + {@code samplingCalls}) rather than a second stub shape.
   */
  private static final class ReasoningAi extends ScriptedAi {
    private final List<List<String>> reasoningPerCall;

    ReasoningAi(List<List<String>> reasoningPerCall, List<String> responses) {
      super(responses);
      this.reasoningPerCall = reasoningPerCall;
    }

    @Override
    void emit(StreamSink sink, int callIdx, String text) {
      if (callIdx < reasoningPerCall.size()) {
        for (String reasoning : reasoningPerCall.get(callIdx)) {
          sink.onReasoning().accept(reasoning);
        }
      }
      super.emit(sink, callIdx, text);
    }
  }

  /** One scripted stream frame: thinking or visible text, in the order the model emits them. */
  private record Frame(boolean thinking, String text) {
    static Frame think(String text) {
      return new Frame(true, text);
    }

    static Frame text(String text) {
      return new Frame(false, text);
    }
  }

  /**
   * Tempdoc 848 F2 — a stub that drives an explicit INTERLEAVED frame sequence, which is what a model
   * that thinks between answer segments actually streams (and what a think-tag-leaking build produces
   * via `ThinkTagStreamFilter`). `ReasoningAi` above only covers think-then-answer.
   */
  private static final class FramedAi extends ScriptedAi {
    private final List<Frame> frames;

    FramedAi(List<Frame> frames) {
      super(List.of(""));
      this.frames = frames;
    }

    @Override
    void emit(StreamSink sink, int callIdx, String text) {
      for (Frame frame : frames) {
        if (frame.thinking()) {
          sink.onReasoning().accept(frame.text());
        } else {
          sink.onContent().accept(frame.text());
        }
      }
    }
  }

  /**
   * Tempdoc 848 §2.2b — a store that REPLAYS what was written, enriched the way the real file store
   * enriches ({@code id}/{@code hash}/{@code ts}), so a second turn's context is the same schemaless
   * passthrough production sees. A store that returned {@code List.of()} could not observe the leak.
   */
  private static final class ReplayingStore extends RecordingStore {
    private int seq;

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
      Map<String, Object> enriched = new LinkedHashMap<>(message);
      enriched.put("id", "msg-" + (++seq));
      enriched.put("hash", "h" + seq);
      enriched.put("ts", System.currentTimeMillis());
      super.appendMessage(sessionId, shapeId, enriched);
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      return List.copyOf(appended.getOrDefault(sessionId, List.of()));
    }
  }

  /** OnlineAiService whose streamChat always fires onError. */
  private static final class FailingAi implements OnlineAiService {
    private final Throwable error;

    FailingAi(Throwable error) {
      this.error = error;
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
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(error);
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(error);
    }

    @Override
    public void stream(StreamRequest request, StreamSink sink) {
      sink.onError().accept(error);
    }
  }
}
