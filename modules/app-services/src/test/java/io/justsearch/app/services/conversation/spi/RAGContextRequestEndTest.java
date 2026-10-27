/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.TestEngineContexts;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** D1/D2/D6/D10, P2/P3: classify types, and retain chosen-file deadline fallback. */
final class RAGContextRequestEndTest {
  @org.junit.jupiter.api.Test
  void remoteFallbackDeadlineMustNotStartAnotherBatchFetch() {
    var client = mock(io.justsearch.app.services.worker.KnowledgeClient.class);
    when(client.retrieveContext(any(RetrieveContextParams.class), any()))
        .thenThrow(new IllegalStateException("ordinary retrieval failure"));
    when(client.fetchDocuments(anyList(), any()))
        .thenThrow(new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "neutral"))
        .thenReturn(io.justsearch.ipc.FetchDocumentsResponse.newBuilder().addDocuments(
            io.justsearch.ipc.DocumentContent.newBuilder().setDocId("doc").setContent("second fallback succeeds")).build());
    var remote = new io.justsearch.app.services.worker.RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
    var ctx = mock(ConversationContext.class);
    when(ctx.requestBody()).thenReturn(Map.of("question", "q", "docIds", List.of("doc")));
    when(ctx.attributes()).thenReturn(new HashMap<>());
    when(ctx.engineContext()).thenReturn(TestEngineContexts.internal());

    var result = new RAGContext(remote).inject(ctx);

    assertTrue(result.terminalError().isPresent(), "deadline must terminate retrieval");
    assertEquals("RETRIEVAL_TIMEOUT", result.terminalError().orElseThrow().payload().get("errorCode"));
    verify(client, times(1)).fetchDocuments(anyList(), any());
  }

  static Stream<Arguments> cases() {
    return Stream.of("open", "chosen", "fallback", "wrappedFallback")
        .flatMap(path -> Stream.of("raw", "reason", "port", "deadline", "nullDeadline", "local", "ordinary", "empty")
            .flatMap(kind -> Stream.of("direct", "completion", "execution")
                .map(wrapper -> Arguments.of(path, kind, wrapper))));
  }

  @ParameterizedTest @MethodSource("cases")
  void everyRetrievalAndBatchCatchPreservesRequestEnd(String path, String kind, String wrapper) {
    Exception end = switch (kind) {
      case "raw" -> new CancellationException();
      case "reason" -> new EngineWorkCancelledException("CONTEXT_CLOSED");
      case "port" -> new KnowledgeClientException(KnowledgeClientException.Status.CANCELLED, "cancelled");
      case "deadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "neutral");
      case "nullDeadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, null);
      case "local" -> new TimeoutException();
      case "ordinary" -> new IllegalStateException("ordinary message says DEADLINE_EXCEEDED");
      default -> null;
    };
    Exception transported = switch (wrapper) {
      case "completion" -> new CompletionException(end);
      case "execution" -> new ExecutionException(end);
      default -> end;
    };
    var docs = mock(DocumentService.class);
    var empty = new DocumentService.ContextResult("", 0, 0, 0, List.of(), "", "", false, List.of());
    boolean fallback = path.endsWith("Fallback") || path.equals("fallback");
    when(docs.retrieveContext(any(RetrieveContextParams.class), any())).thenAnswer(ignored -> {
      if (fallback || kind.equals("empty")) return CompletableFuture.completedFuture(empty);
      return CompletableFuture.failedFuture(transported);
    });
    when(docs.fetchBatch(anyList(), any())).thenAnswer(ignored -> {
      if (fallback && end != null) {
        if (path.equals("wrappedFallback")) throw new DocumentService.UnavailableException("fetch failed", transported);
        throw transported;
      }
      return CompletableFuture.completedFuture(Map.of("doc", new DocumentService.DocumentRecord("doc", "whole document", Map.of())));
    });
    var ctx = mock(ConversationContext.class);
    when(ctx.requestBody()).thenReturn(path.equals("open") ? Map.of("question", "q")
        : Map.of("question", "q", "docIds", List.of("doc")));
    when(ctx.attributes()).thenReturn(new HashMap<>());
    when(ctx.engineContext()).thenReturn(TestEngineContexts.internal());
    var rag = new RAGContext(docs, Duration.ofMillis(1));
    if (kind.equals("raw") || kind.equals("reason") || kind.equals("port")) {
      assertSame(end, assertThrows(RuntimeException.class, () -> rag.inject(ctx)));
      if (!fallback) verify(docs, never()).fetchBatch(anyList(), any());
      return;
    }
    var result = rag.inject(ctx);
    boolean deadline = kind.equals("deadline") || kind.equals("nullDeadline") || kind.equals("local");
    if (deadline) {
      assertTrue(result.terminalError().isPresent(), "deadline must terminate retrieval");
      assertEquals("RETRIEVAL_TIMEOUT", result.terminalError().orElseThrow().payload().get("errorCode"));
      assertTrue(result.events().stream().noneMatch(event -> event.name().equals("rag.citations")));
    } else if (path.equals("open")) {
      assertEquals(kind.equals("empty") ? "NO_CONTENT" : "RETRIEVAL_FAILED",
          result.terminalError().orElseThrow().payload().get("errorCode"));
    } else if (fallback && kind.equals("ordinary")) {
      assertEquals("NO_CONTENT", result.terminalError().orElseThrow().payload().get("errorCode"));
    } else {
      assertTrue(result.terminalError().isEmpty(), result.toString());
      assertTrue(result.messages().toString().contains("whole document"));
      assertTrue(result.events().stream().noneMatch(event -> event.name().equals("rag.citations")));
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void localWaitExpiryStillFetchesChosenDocuments(boolean chosen) {
    var docs = mock(DocumentService.class);
    when(docs.retrieveContext(any(RetrieveContextParams.class), any()))
        .thenReturn(new CompletableFuture<>());
    when(docs.fetchBatch(anyList(), any())).thenReturn(CompletableFuture.completedFuture(
        Map.of("doc", new DocumentService.DocumentRecord("doc", "whole document", Map.of()))));
    var ctx = mock(ConversationContext.class);
    when(ctx.requestBody()).thenReturn(chosen ? Map.of("question", "q", "docIds", List.of("doc"))
        : Map.of("question", "q"));
    when(ctx.attributes()).thenReturn(new HashMap<>());
    when(ctx.engineContext()).thenReturn(TestEngineContexts.internal());

    var result = new RAGContext(docs, Duration.ofMillis(1)).inject(ctx);

    if (!chosen) {
      assertTrue(result.terminalError().isPresent(), "deadline must terminate retrieval");
      assertEquals("RETRIEVAL_TIMEOUT", result.terminalError().orElseThrow().payload().get("errorCode"));
      verify(docs, never()).fetchBatch(anyList(), any());
      return;
    }
    assertTrue(result.terminalError().isEmpty());
    assertTrue(result.messages().toString().contains("whole document"));
    assertTrue(result.events().stream().noneMatch(event -> event.name().equals("rag.citations")));
    verify(docs).fetchBatch(anyList(), any());
  }

  @org.junit.jupiter.api.Test
  void batchFallbacksOwnWaitExpiryIsTerminal() {
    var docs = mock(DocumentService.class);
    when(docs.retrieveContext(any(RetrieveContextParams.class), any()))
        .thenReturn(CompletableFuture.completedFuture(new DocumentService.ContextResult(
            "", 0, 0, 0, List.of(), "", "", false, List.of())));
    when(docs.fetchBatch(anyList(), any())).thenReturn(new CompletableFuture<>());
    var ctx = mock(ConversationContext.class);
    when(ctx.requestBody()).thenReturn(Map.of("question", "q", "docIds", List.of("doc")));
    when(ctx.attributes()).thenReturn(new HashMap<>());
    when(ctx.engineContext()).thenReturn(TestEngineContexts.internal());

    var result = new RAGContext(docs, Duration.ofMillis(1)).inject(ctx);

    assertTrue(result.terminalError().isPresent(), "deadline must terminate retrieval");
    assertEquals("RETRIEVAL_TIMEOUT", result.terminalError().orElseThrow().payload().get("errorCode"));
    assertTrue(result.events().stream().noneMatch(event -> event.name().equals("rag.citations")));
    verify(docs).fetchBatch(anyList(), any());
  }
}
