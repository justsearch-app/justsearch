/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** D3-D6, P3/P10: the scope belongs to the caller, never to discovered IDs. */
final class RemoteDocumentServiceRequestEndTest {
  private ConfigStore previous;

  @BeforeEach void configure() {
    previous = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach void restore() {
    Thread.interrupted();
    TestResolvedConfigHelper.restoreGlobal(previous);
  }

  static Stream<Arguments> endings() {
    return Stream.of("preSearch", "open", "rich", "legacy", "openFallback", "richFallback", "legacyFallback")
        .flatMap(path -> Stream.of("raw", "reason", "port", "interrupted", "deadline", "nullDeadline", "local", "ordinary")
            .flatMap(kind -> Stream.of("direct", "completion", "execution")
                .map(wrapper -> Arguments.of(path, kind, wrapper))));
  }

  @ParameterizedTest @MethodSource("endings")
  void requestEndSurvivesEveryOptionalCatch(String path, String kind, String wrapper) {
    Throwable end = switch (kind) {
      case "interrupted" -> new InterruptedException();
      case "raw" -> new CancellationException();
      case "reason" -> new EngineWorkCancelledException("CONTEXT_CLOSED");
      case "port" -> new KnowledgeClientException(KnowledgeClientException.Status.CANCELLED, "cancelled");
      case "deadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "neutral");
      case "nullDeadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, null);
      case "local" -> new TimeoutException();
      case "ordinary" -> new IllegalStateException("ordinary message says DEADLINE_EXCEEDED");
      default -> throw new AssertionError(kind);
    };
    Throwable failure = switch (wrapper) {
      case "completion" -> new CompletionException(end);
      case "execution" -> new ExecutionException(end);
      default -> end;
    };
    boolean fallback = path.endsWith("Fallback");
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any())).thenAnswer(ignored -> {
      if (path.equals("preSearch")) throw failure;
      return SearchResponse.newBuilder().addResults(
          SearchResult.newBuilder().setId("doc").putFields("path", "doc")).build();
    });
    when(client.retrieveContext(any(RetrieveContextParams.class), any())).thenAnswer(ignored -> {
      if (fallback) throw new IllegalStateException("ordinary failure");
      throw failure;
    });
    when(client.retrieveContext(anyString(), anySet(), anyInt(), anyInt(), any())).thenAnswer(ignored -> {
      if (fallback) throw new IllegalStateException("ordinary failure");
      throw failure;
    });
    when(client.fetchDocuments(anyList(), any())).thenAnswer(ignored -> {
      if (fallback) throw failure;
      return FetchDocumentsResponse.newBuilder().addDocuments(
          DocumentContent.newBuilder().setDocId("doc").setContent("whole document")).build();
    });
    var service = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
    var context = TestEngineContexts.internal();
    var stage = path.startsWith("legacy")
        ? service.retrieveContextWithMeta("q", Set.of("doc"), 3, 1000, context)
        : service.retrieveContext(RetrieveContextParams.of("q", 3, 1000,
            path.equals("preSearch") || path.startsWith("open") ? Set.of() : Set.of("doc"),
            List.of(), List.of()), context);
    boolean chosenDeadline = !fallback && (path.equals("rich") || path.equals("legacy"))
        && (kind.endsWith("Deadline") || kind.equals("deadline") || kind.equals("local"));
    if (kind.equals("ordinary")) {
      var result = stage.toCompletableFuture().join();
      assertTrue(result.citations().isEmpty());
      if (fallback || path.equals("preSearch")) {
        assertEquals("FALLBACK_FAILED", result.retrievalModeReason());
        assertTrue(result.context().isEmpty());
      } else {
        assertEquals("FULLTEXT_FALLBACK", result.retrievalMode());
        assertEquals("GRPC_FAILED", result.retrievalModeReason());
        assertTrue(result.context().contains("whole document"));
      }
      if (path.equals("preSearch")) verify(client).retrieveContext(any(RetrieveContextParams.class), any());
    } else if (chosenDeadline) {
      var result = stage.toCompletableFuture().join();
      assertEquals("FULLTEXT_FALLBACK", result.retrievalMode());
      assertEquals("GRPC_FAILED", result.retrievalModeReason());
      assertTrue(result.context().contains("whole document"));
      assertTrue(result.citations().isEmpty());
    } else {
      // Observe the stage failure before join can copy a CancellationException.
      Throwable thrown = stage.handle((ignored, failureCause) -> failureCause).toCompletableFuture().join();
      assertNotNull(thrown, "request ending must not become a successful fallback");
      while (thrown instanceof CompletionException || thrown instanceof ExecutionException) thrown = thrown.getCause();
      if (kind.equals("interrupted")) {
        var cancelled = assertInstanceOf(EngineWorkCancelledException.class, thrown);
        assertEquals("caller_interrupted", cancelled.reasonCode());
        assertTrue(Thread.currentThread().isInterrupted());
      } else {
        assertSame(end, thrown);
      }
      if (!fallback) verify(client, never()).fetchDocuments(anyList(), any());
      if (path.equals("preSearch")) verify(client, never()).retrieveContext(any(RetrieveContextParams.class), any());
    }
  }
}
