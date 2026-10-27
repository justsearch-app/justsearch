/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.DocumentService.UnavailableException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.TestEngineContexts;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class RemoteDocumentServiceSliceFailureTest {
  @Test
  void invalidArgumentKeepsItsIdentityAndReleasesCapturedClient() {
    var failure = new KnowledgeClientException(
        KnowledgeClientException.Status.INVALID_ARGUMENT, "invalid offset");
    var released = new AtomicInteger();
    var docs = failingService(failure, released);
    var thrown = assertThrows(CompletionException.class,
        () -> docs.fetchSlice("doc", 2, 1, TestEngineContexts.internal()).toCompletableFuture().join());
    assertSame(failure, thrown.getCause());
    assertEquals(1, released.get());
  }

  static Stream<RuntimeException> otherFailures() {
    return Stream.concat(
        Stream.of(KnowledgeClientException.Status.values())
            .filter(status -> status != KnowledgeClientException.Status.INVALID_ARGUMENT)
            .map(status -> new KnowledgeClientException(status, "surrogate offset")),
        Stream.of(new IllegalArgumentException("offset splits a surrogate pair"),
            new IllegalStateException("surrogate pair unavailable"),
            new CompletionException(new KnowledgeClientException(
                KnowledgeClientException.Status.INVALID_ARGUMENT, "invalid offset"))));
  }

  @ParameterizedTest(name = "otherFailuresStayWrappedAndReleaseCapturedClient({index})")
  @MethodSource("otherFailures")
  void otherFailuresStayWrappedAndReleaseCapturedClient(RuntimeException failure) {
    var released = new AtomicInteger();
    var thrown = assertThrows(CompletionException.class,
        () -> failingService(failure, released).fetchSlice("doc", 2, 1,
            TestEngineContexts.internal()).toCompletableFuture().join());
    var unavailable = assertInstanceOf(UnavailableException.class, thrown.getCause());
    assertSame(failure, unavailable.getCause());
    assertEquals(1, released.get());
  }

  @Test
  void lazyDelegationAndSelectionProbeKeepLeadingSupplementaryContent() {
    var client = mock(KnowledgeClient.class);
    when(client.fetchDocumentSlice(eq("doc"), eq(0), eq(1), any())).thenReturn(
        io.justsearch.ipc.FetchDocumentSliceResponse.newBuilder().setDocId("doc")
            .setFound(true).setContent("\uD83D\uDE00").setNextOffsetChars(2).setTotalChars(3)
            .setTruncated(true).build());
    var remote = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
    var lazy = new io.justsearch.app.services.conversation.LazyDocumentService(() -> remote);
    var context = TestEngineContexts.internal();
    var page = lazy.fetchSlice("doc", 0, 1, context).toCompletableFuture().join();
    assertEquals("\uD83D\uDE00", page.content());
    assertEquals(2, page.nextOffsetChars());
    var request = mock(io.justsearch.agent.api.conversation.ConversationContext.class);
    when(request.engineContext()).thenReturn(context);
    when(request.shapeId()).thenReturn("core.rag-ask");
    when(request.requestBody()).thenReturn(java.util.Map.of("selection",
        java.util.Map.of("kind", "item", "itemKind", "search-hit", "itemId", "doc")));
    var attributes = new java.util.HashMap<String, Object>();
    when(request.attributes()).thenReturn(attributes);
    var result = new io.justsearch.app.services.conversation.spi.SelectionContextInjector(lazy).inject(request);
    assertTrue(result.terminalError().isEmpty());
    assertEquals(java.util.List.of("doc"), attributes.get("rag.docIds"));
    verify(client, times(2)).fetchDocumentSlice("doc", 0, 1, context);
  }

  private static RemoteDocumentService failingService(RuntimeException failure, AtomicInteger released) {
    var client = mock(KnowledgeClient.class);
    when(client.fetchDocumentSlice(anyString(), anyInt(), anyInt(), any())).thenThrow(failure);
    return new RemoteDocumentService(Runnable::run, Runnable::run, () -> client,
        RagMetricCatalog.noop(), () -> new RemoteDocumentService.ClientCapture() {
          @Override public KnowledgeClient client() { return client; }
          @Override public void close() { released.incrementAndGet(); }
        });
  }
}
