/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WP4.3 CK1–CK3, HC7 and KN1: observe the real consumer dispatch, with a held scorer. */
final class CitationCheckingStatusTest {
  private static final String START = "rag.citation_checking";
  private static final String TEXT = "Ada owns Project Cobalt [1].";
  @TempDir Path data;

  @Test
  void checkingEndsWithItsResultBeforeALaterFinishingStep() throws Exception {
    var scored = new CompletableFuture<DocumentService.CitationMatchResult>();
    var entered = new CountDownLatch(1);
    var later = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<SseEvent>();
    StreamConsumer finishing = new StreamConsumer() {
      public String id() { return "test.finishing"; }
      public StreamConsumerResult onChunk(String text, ConversationContext ctx) {
        return StreamConsumerResult.empty();
      }
      public StreamConsumerResult onDone(String text, ConversationContext ctx) {
        later.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS), "test must release the later step");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
        return StreamConsumerResult.empty();
      }
    };
    var engine = engine("core.rag-ask", TEXT, true, scored, entered, Duration.ofSeconds(5), finishing);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var run = executor.submit(() -> run(engine, "core.rag-ask", events));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "scorer was called");
        assertEquals(1, events.stream().filter(e -> START.equals(e.name())).count(),
            "the start signal must arrive while the scorer is held, not after onDone returns");
        var cold = new FileConversationStore(data).loadHistory("conv");
        assertEquals(1, cold.size(), "a crash during checking retains the saved question alone");
        assertEquals("Who owns Cobalt?", cold.getFirst().get("content"));
        assertFalse(names(events).contains("rag.citation_matches"));
        scored.complete(complete());
        assertTrue(later.await(5, TimeUnit.SECONDS));
        assertTrue(names(events).contains("rag.citation_matches"), "result ends checking before later work");
        assertFalse(names(events).contains("done"));
      } finally {
        scored.complete(complete());
        release.countDown();
      }
      run.get(5, TimeUnit.SECONDS);
    }
    var names = names(events);
    assertTrue(names.lastIndexOf("chunk") < names.indexOf(START));
    assertTrue(names.indexOf(START) < names.indexOf("rag.citation_matches"));
    assertTrue(names.indexOf("rag.citation_matches") < names.indexOf("done"));
  }

  @ParameterizedTest(name = "unfinishedCheckIsExplicitLiveAndOnTheStoredAnswer({0})")
  @ValueSource(strings = {"timeout", "failure", "null", "no-scorer"})
  void unfinishedCheckIsExplicitLiveAndOnTheStoredAnswer(String outcome) {
      var scored = new CompletableFuture<DocumentService.CitationMatchResult>();
      if (outcome.equals("failure")) scored.completeExceptionally(new IllegalStateException("scorer failed"));
      if (outcome.equals("null")) scored.complete(null);
      if (outcome.equals("no-scorer")) scored.complete(new DocumentService.CitationMatchResult(
          List.of(), 0, 0, 0, 0, DocumentService.ScorerKind.NONE, List.of()));
      var events = new CopyOnWriteArrayList<SseEvent>();
      run(engine("core.rag-ask", TEXT, true, scored, new CountDownLatch(0), Duration.ofMillis(1)),
          "core.rag-ask", events);
      var result = events.stream().filter(e -> e.name().equals("rag.citation_matches")).findFirst();
      assertTrue(result.isPresent(), outcome + ": an unfinished check must report an explicit fact");
      var fact = result.orElseThrow().payload();
      assertEquals(true, fact.get("groundingIncomplete"));
      for (String count : List.of("sentencesTotal", "sentencesScored", "sentencesMatched", "sourceCoverage")) {
        assertFalse(fact.containsKey(count), outcome + ": no invented counts");
      }
      var history = new FileConversationStore(data).loadHistory("conv");
      assertEquals(fact, history.getLast().get("claimMatches"), "cold readers get the same fact");
      assertEquals(TEXT, history.getLast().get("content"));
      scored.cancel(true);
  }

  @Test
  void skippedChecksNeverStartAndSummarizeKeepsItsVocabulary() {
    for (String shape : List.of("core.rag-ask", "core.summarize", "core.free-chat")) {
      for (boolean sources : List.of(false, true)) {
        for (String text : List.of("", TEXT)) {
          var events = new CopyOnWriteArrayList<SseEvent>();
          run(engine(shape, text, sources, CompletableFuture.completedFuture(complete()),
              new CountDownLatch(0), Duration.ofSeconds(5)), shape, events);
          boolean shouldStart = shape.equals("core.rag-ask") && sources && !text.isBlank();
          assertEquals(shouldStart, names(events).contains(START), shape + ": sources=" + sources + ", text=" + text);
        }
      }
    }
  }

  @Test
  void completedZeroMatchesIsDifferentFromAnUnfinishedCheck() {
    var events = new CopyOnWriteArrayList<SseEvent>();
    run(engine("core.rag-ask", TEXT, true, CompletableFuture.completedFuture(complete()),
        new CountDownLatch(0), Duration.ofSeconds(5)), "core.rag-ask", events);
    var fact = events.stream().filter(e -> e.name().equals("rag.citation_matches"))
        .findFirst().orElseThrow().payload();
    assertEquals(1, fact.get("sentencesScored"));
    assertFalse(fact.containsKey("groundingIncomplete"));
    assertEquals(List.of(), fact.get("matches"));
  }

  @Test
  void resultPrecedesALaterFinishingFailure() {
    StreamConsumer failing = new StreamConsumer() {
      public String id() { return "test.failing-finish"; }
      public StreamConsumerResult onChunk(String text, ConversationContext ctx) {
        return StreamConsumerResult.empty();
      }
      public StreamConsumerResult onDone(String text, ConversationContext ctx) {
        throw new IllegalStateException("later finishing step failed");
      }
    };
    var events = new CopyOnWriteArrayList<SseEvent>();
    run(engine("core.rag-ask", TEXT, true, CompletableFuture.completedFuture(complete()),
        new CountDownLatch(0), Duration.ofSeconds(5), failing), "core.rag-ask", events);
    var names = names(events);
    assertTrue(names.contains(START), "the check actually started");
    assertTrue(names.indexOf("rag.citation_matches") > names.indexOf(START));
    assertTrue(names.indexOf("error") > names.indexOf("rag.citation_matches"));
    assertFalse(names.contains("done"));
  }

  @Test
  void unfinishedCheckFactSurvivesALaterFinishingFailure() {
    StreamConsumer failing = new StreamConsumer() {
      public String id() { return "test.failing-after-incomplete"; }
      public StreamConsumerResult onChunk(String text, ConversationContext ctx) {
        return StreamConsumerResult.empty();
      }
      public StreamConsumerResult onDone(String text, ConversationContext ctx) {
        throw new IllegalStateException("later finishing step failed");
      }
    };
    var events = new CopyOnWriteArrayList<SseEvent>();
    run(engine("core.rag-ask", TEXT, true,
        CompletableFuture.failedFuture(new IllegalStateException("scorer failed")),
        new CountDownLatch(0), Duration.ofSeconds(5), failing), "core.rag-ask", events);
    var result = events.stream().filter(e -> e.name().equals("rag.citation_matches")).findFirst();
    assertTrue(result.isPresent(), "the unfinished check reports its fact before the later failure");
    var fact = result.orElseThrow().payload();
    assertEquals(true, fact.get("groundingIncomplete"));
    assertTrue(names(events).contains("error"), "the later failure is still reported");
    var cold = new FileConversationStore(data).loadHistory("conv").getLast();
    assertEquals(fact, cold.get("claimMatches"), "a later failure cannot erase an emitted check fact");
    assertEquals(true, cold.get("terminalError"));
    assertEquals("CONSUMER_ERROR", cold.get("errorCode"));
  }

  private ConversationEngine engine(String shapeId, String text, boolean sources,
      CompletableFuture<DocumentService.CitationMatchResult> scored, CountDownLatch entered,
      Duration timeout, StreamConsumer... later) {
    var documents = mock(DocumentService.class);
    when(documents.matchCitationsAgainst(anyString(), anyList(), anyDouble(), any()))
        .thenAnswer(call -> { entered.countDown(); return scored; });
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    doAnswer(call -> {
      OnlineAiService.StreamSink sink = call.getArgument(1);
      sink.onContent().accept(text);
      sink.onComplete().accept(text);
      return null;
    }).when(ai).stream(any(), any());
    ContextInjector injector = new ContextInjector() {
      public String id() { return "test.sources"; }
      public InjectorResult inject(ConversationContext ctx) {
        if (sources) ctx.attributes().put(RAGContext.ATTR_VERIFICATION_SOURCES,
            List.of(new DocumentService.VerificationSource(null, "Ada owns Project Cobalt.")));
        return InjectorResult.of(List.of(Map.of("role", "user", "content", "Who owns Cobalt?")), List.of());
      }
    };
    var consumers = new java.util.ArrayList<StreamConsumer>();
    consumers.add(new StreamingCitationMatcher(documents, timeout, 0.5));
    consumers.addAll(List.of(later));
    var shape = new ConversationShape(new ConversationShapeRef(shapeId),
        new Presentation(new I18nKey("test.label"), new I18nKey("test.description"), Optional.empty(), Optional.empty()),
        Audience.USER, Provenance.core("v1"), ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL, List.of(), List.of(injector.id()),
        consumers.stream().map(StreamConsumer::id).toList(), SingleHopController.ID, List.of(), true);
    return new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(injector)),
        StreamConsumerRegistry.of(consumers), IterationControllerRegistry.of(List.of()), () -> ai,
        new FileConversationStore(data));
  }

  private static void run(ConversationEngine engine, String shape, List<SseEvent> events) {
    engine.run(new ConversationShapeRef(shape), Map.of("question", "Who owns Cobalt?", "conversationId", "conv"),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
  }

  private static List<String> names(List<SseEvent> events) {
    return events.stream().map(SseEvent::name).toList();
  }

  private static DocumentService.CitationMatchResult complete() {
    return new DocumentService.CitationMatchResult(List.of(), 1, 0, 0, 1,
        DocumentService.ScorerKind.CROSS_ENCODER, List.of());
  }
}
