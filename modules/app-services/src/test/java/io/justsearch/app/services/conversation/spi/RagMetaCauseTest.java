/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.core.context.EngineContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Spec D §5.1: {@code rag.meta} carries {@code retrieval_mode_cause} next to the reason when the
 * retrieval's query embedding missed its semantic budget, and only then (S2: the reason, per leg,
 * reaches the RAG metadata).
 */
final class RagMetaCauseTest {

  @Test
  void aBudgetMissCarriesItsCauseAndIsNotAnError() {
    InjectorResult result =
        inject(retrieval("BM25", "SEMANTIC_BUDGET_EXCEEDED", "loading"));

    assertFalse(result.terminalError().isPresent());
    Map<String, Object> meta = ragMeta(result);
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", meta.get("retrieval_mode_reason"));
    assertEquals("loading", meta.get("retrieval_mode_cause"));
  }

  @Test
  void anyOtherReasonCarriesNoCause() {
    Map<String, Object> meta = ragMeta(inject(retrieval("HYBRID", "HYBRID_AVAILABLE", "")));

    assertEquals("HYBRID_AVAILABLE", meta.get("retrieval_mode_reason"));
    assertFalse(meta.containsKey("retrieval_mode_cause"), meta::toString);
  }

  @Test
  void aCauseWithAnotherReasonIsNotEmitted() {
    Map<String, Object> meta =
        ragMeta(inject(retrieval("BM25", "EMBEDDING_GENERATION_FAILED", "stuck")));

    assertFalse(meta.containsKey("retrieval_mode_cause"), meta::toString);
  }

  private static ContextResult retrieval(String mode, String reason, String cause) {
    var citation =
        new DocumentService.ContextCitation(
            "doc-1", 0, 1, 0, 30, 0.8f, "the chunk text", 1, 1, "", 0,
            DocumentService.ContextInclusion.ABSENT);
    var section = new DocumentService.ContextSection("doc-1", "the chunk text", false, 0, 0);
    return new ContextResult(
        "the chunk text", 1, 1, 0, List.of(citation), mode, reason, false, List.of(section),
        null, cause);
  }

  private static Map<String, Object> ragMeta(InjectorResult result) {
    return result.events().stream()
        .filter(event -> event.name().equals("rag.meta"))
        .map(SseEvent::payload)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no rag.meta: " + result.events()));
  }

  private static InjectorResult inject(ContextResult retrieval) {
    DocumentService documents =
        new DocumentService() {
          @Override
          public CompletionStage<ContextResult> retrieveContext(
              RetrieveContextParams params, EngineContext engineContext) {
            return CompletableFuture.completedFuture(retrieval);
          }

          @Override
          public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
            return CompletableFuture.completedFuture(null);
          }
        };
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("question", "what is this?");
    body.put("docIds", List.of("doc-1"));
    Map<String, Object> attributes = new HashMap<>();
    return new RAGContext(documents)
        .inject(
            new ConversationContext() {
              @Override
              public EngineContext engineContext() {
                return io.justsearch.app.services.TestEngineContexts.internal();
              }

              @Override
              public List<Map<String, Object>> messages() {
                return List.of();
              }

              @Override
              public int iteration() {
                return 0;
              }

              @Override
              public Audience audience() {
                return Audience.USER;
              }

              @Override
              public String sessionId() {
                return null;
              }

              @Override
              public Map<String, Object> requestBody() {
                return body;
              }

              @Override
              public Map<String, Object> attributes() {
                return attributes;
              }
            });
  }
}
