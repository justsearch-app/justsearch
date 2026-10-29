/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D7: no model input or chosen-file fetch when eligibility is unknown or empty. */
class RAGRestrictionFailureTest {
  @Test
  void richPortFailureAndHeadWaitFailureNeverFetchChosenFiles() {
    for (boolean wait : List.of(false, true)) {
      for (boolean scoped : List.of(false, true)) {
        var docs = documents();
        CompletableFuture<ContextResult> future = wait ? new CompletableFuture<>()
            : CompletableFuture.failedFuture(new IllegalStateException("rich port failure"));
        when(docs.retrieveContext(any(), any())).thenReturn(future);
        var injector = new RAGContext(docs, Duration.ofMillis(5));
        var result = injector.inject(context(scoped, Map.of("pathPrefix", "/inside")));
        assertTrue(result.messages().isEmpty(), "failure must provide no model input");
        assertTrue(result.terminalError().isPresent());
        assertEquals(wait ? "RETRIEVAL_TIMEOUT" : "RETRIEVAL_FAILED",
            result.terminalError().orElseThrow().payload().get("errorCode"));
        verify(docs, never()).fetchBatch(any(), any());
        future.cancel(false);
      }
    }
  }

  @Test
  void overflowSurvivesHeadAsTypedFailureWithoutModelInput() {
    var docs = documents();
    when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.failedFuture(
        new KnowledgeClientException(KnowledgeClientException.Status.RESOURCE_EXHAUSTED,
            "Retrieval filter scope exceeds 10000 eligible parents")));
    var result = new RAGContext(docs).inject(context(false, Map.of("pathPrefix", "/inside")));
    assertEquals("RESOURCE_EXHAUSTED", result.terminalError().orElseThrow().payload().get("errorCode"));
    assertTrue(result.messages().isEmpty());
    verify(docs, never()).fetchBatch(any(), any());
  }

  @Test
  void restrictedEmptyIsNoContentAndSuccessfulFulltextIsKept() {
    var docs = documents();
    when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.completedFuture(
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of())));
    var injector = new RAGContext(docs);
    var empty = injector.inject(context(true, Map.of("sizeBytes", Map.of("toBytes", 0))));
    assertEquals("NO_CONTENT", empty.terminalError().orElseThrow().payload().get("errorCode"));
    assertTrue(empty.terminalError().orElseThrow().payload().get("error").toString().contains("restrictions"));
    when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.completedFuture(
        new ContextResult("ELIGIBLE full document", 1, 0, 0, List.of(), "FULLTEXT_FALLBACK", "NO_CHUNKS_FOUND", false, List.of())));
    var full = injector.inject(context(true, Map.of("pathPrefix", "/inside")));
    assertTrue(full.terminalError().isEmpty());
    assertTrue(full.messages().toString().contains("ELIGIBLE"));
    verify(docs, never()).fetchBatch(any(), any());
  }

  @Test
  void reportedFallbackFailureIsNeverPresentedAsEmptyRestrictedContent() {
    for (boolean scoped : List.of(false, true)) {
      var docs = documents();
      when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.completedFuture(
          new ContextResult("", 0, 0, 0, List.of(), "", "FALLBACK_FAILED", false, List.of())));
      var result = new RAGContext(docs).inject(context(scoped, Map.of("pathPrefix", "/inside")));
      assertEquals("FETCH_FAILED", result.terminalError().orElseThrow().payload().get("errorCode"));
      assertTrue(result.messages().isEmpty());
      verify(docs, never()).fetchBatch(any(), any());
    }
  }

  @Test
  void allRestrictionsReachTheRichPortForOpenAndExplicitIdRetrieval() {
    for (boolean scoped : List.of(false, true)) {
      var docs = documents();
      when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.completedFuture(
          new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of())));
      new RAGContext(docs).inject(context(scoped, Map.of("pathPrefix", "/inside",
          "modifiedAt", Map.of("fromMs", 100, "toMs", 200),
          "sizeBytes", Map.of("fromBytes", 0, "toBytes", 500))));
      var params = ArgumentCaptor.forClass(RetrieveContextParams.class);
      verify(docs).retrieveContext(params.capture(), any());
      assertEquals("/inside", params.getValue().pathPrefix());
      assertEquals(100, params.getValue().modifiedAt().fromMs());
      // Reflection lets the same boundary assertion compile before size support existed.
      try {
        Object range = RetrieveContextParams.class.getMethod("sizeBytes").invoke(params.getValue());
        assertEquals(0L, range.getClass().getMethod("fromBytes").invoke(range));
        assertEquals(500L, range.getClass().getMethod("toBytes").invoke(range));
      } catch (ReflectiveOperationException e) {
        fail("size bounds did not reach the rich port", e);
      }
    }
  }

  @Test
  void actualConversationDispatchEndsOnTypedRestrictedFailureWithoutGeneration() {
    for (boolean overflow : List.of(false, true)) {
      var docs = documents();
      RuntimeException failure = overflow
          ? new KnowledgeClientException(KnowledgeClientException.Status.RESOURCE_EXHAUSTED, "scope exceeds 10000 parents")
          : new IllegalStateException("injected rich retrieval failure");
      when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
      var injector = new RAGContext(docs);
      var id = new io.justsearch.agent.api.registry.ConversationShapeRef("core.rag-ask");
      var shape = new io.justsearch.agent.api.registry.ConversationShape(id,
          new io.justsearch.agent.api.registry.Presentation(
              new io.justsearch.agent.api.registry.I18nKey("test.label"),
              new io.justsearch.agent.api.registry.I18nKey("test.description"),
              java.util.Optional.empty(), java.util.Optional.empty()),
          Audience.USER, io.justsearch.agent.api.registry.Provenance.core("v1"),
          io.justsearch.agent.api.conversation.ExecutionMode.SUBSTRATE_DRIVEN,
          io.justsearch.agent.api.conversation.IterationMode.ONE_SHOT,
          io.justsearch.agent.api.conversation.PersistenceMode.EPHEMERAL,
          List.of(), List.of(injector.id()), List.of(),
          io.justsearch.agent.api.conversation.SingleHopController.ID, List.of(), true);
      var ai = mock(io.justsearch.app.api.OnlineAiService.class);
      when(ai.isAvailable()).thenReturn(true);
      var engine = new io.justsearch.app.services.conversation.ConversationEngine(
          io.justsearch.agent.api.registry.ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
          io.justsearch.app.services.conversation.PromptContributorRegistry.of(List.of()),
          io.justsearch.app.services.conversation.ContextInjectorRegistry.of(List.of(injector)),
          io.justsearch.app.services.conversation.StreamConsumerRegistry.of(List.of()),
          io.justsearch.app.services.conversation.IterationControllerRegistry.of(List.of()), () -> ai);
      var events = new java.util.ArrayList<io.justsearch.agent.api.conversation.SseEvent>();
      assertDoesNotThrow(() -> engine.run(id,
          Map.of("question", "needle", "docIds", List.of("/inside/chosen"),
              "filters", Map.of("pathPrefix", "/inside")), Audience.USER, events::add,
          io.justsearch.app.services.TestEngineContexts.internal()));
      assertTrue(events.stream().anyMatch(e -> e.name().equals("error")
          && (overflow ? "RESOURCE_EXHAUSTED" : "RETRIEVAL_FAILED").equals(e.payload().get("errorCode"))), events.toString());
      verify(ai, never()).stream(any(), any());
      verify(docs, never()).fetchBatch(any(), any());
    }
  }

  private static DocumentService documents() {
    var docs = mock(DocumentService.class);
    when(docs.fetchBatch(any(), any())).thenReturn(CompletableFuture.completedFuture(Map.of()));
    return docs;
  }

  private static ConversationContext context(boolean scoped, Map<String, Object> filters) {
    var ctx = mock(ConversationContext.class);
    var body = new HashMap<String, Object>(Map.of("question", "needle", "filters", filters));
    if (scoped) body.put("docIds", List.of("/inside/chosen"));
    when(ctx.engineContext()).thenReturn(io.justsearch.app.services.TestEngineContexts.internal());
    when(ctx.requestBody()).thenReturn(body);
    when(ctx.attributes()).thenReturn(new HashMap<>());
    when(ctx.messages()).thenReturn(List.of());
    when(ctx.audience()).thenReturn(Audience.USER);
    return ctx;
  }
}
