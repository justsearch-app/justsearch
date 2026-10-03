package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.VerificationSource;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class RemoteDocumentServiceRefusalTest {
  static Stream<Arguments> refusals() {
    return Stream.of("rich", "open", "legacy", "fallback", "fetch", "slice", "ids", "citations")
        .flatMap(path -> Stream.of(false, true).flatMap(admission ->
            Stream.of(false, true).map(wrapped -> Arguments.of(path, admission, wrapped))));
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void documentPortPreservesRefusalBeforeFallback(String path, boolean admission, boolean wrapped) {
    RuntimeException refused = admission
        ? new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3)
        : new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3);
    RuntimeException failure = wrapped
        ? new CompletionException(new ExecutionException(refused)) : refused;
    var client = mock(KnowledgeClient.class, invocation -> {
      throw failure;
    });
    if (path.equals("fallback")) {
      // Retrieval itself fails normally; capacity is exhausted only when fallback fetch begins.
      org.mockito.Mockito.doThrow(new IllegalStateException("index unavailable"))
          .when(client).retrieveContext(any(RetrieveContextParams.class), any(EngineContext.class));
      org.mockito.Mockito.doThrow(failure)
          .when(client).fetchDocuments(anyList(), any(EngineContext.class));
    }
    var docs = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
    var context = TestEngineContexts.internal();
    CompletionStage<?> result = switch (path) {
      case "rich", "fallback" -> docs.retrieveContext(
          RetrieveContextParams.of("q", 5, 1_000, Set.of("doc"), List.of(), List.of()), context);
      case "open" -> docs.retrieveContext(RetrieveContextParams.of("q", 5, 1_000), context);
      case "legacy" -> docs.retrieveContextWithMeta("q", Set.of("doc"), 5, 1_000, context);
      case "fetch" -> docs.fetchBatch(List.of("doc"), context);
      case "slice" -> docs.fetchSlice("doc", 0, 100, context);
      case "ids" -> docs.listAllDocumentIds(0, 10, context);
      case "citations" -> docs.matchCitationsAgainst("answer", List.of(new VerificationSource(
          new ContextCitation("doc", 0, 1, 0, 10, 1f, "excerpt", 0, 0, "", 0, null), "")),
          0.5, context);
      default -> throw new AssertionError(path);
    };

    var thrown = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
    assertSame(refused, thrown.getCause());
  }
}
