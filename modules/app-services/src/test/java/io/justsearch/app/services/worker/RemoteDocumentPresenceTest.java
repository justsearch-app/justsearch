/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.conversation.LazyDocumentService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RemoteDocumentPresenceTest {
  @Test
  void forwardsThroughCapturedClientAndLazyFacadeWithoutFetchingContent() {
    var client = mock(KnowledgeClient.class);
    var context = TestEngineContexts.internal();
    var ids = List.of("held", "gone");
    var expected = Map.of("held", DocumentService.Presence.PRESENT, "gone", DocumentService.Presence.ABSENT);
    when(client.checkDocumentPresence(ids, context)).thenReturn(expected);
    AtomicInteger closed = new AtomicInteger();
    var remote = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client,
        RagMetricCatalog.noop(), () -> new RemoteDocumentService.ClientCapture() {
          @Override public KnowledgeClient client() { return client; }
          @Override public void close() { closed.incrementAndGet(); }
        });
    DocumentService lazy = new LazyDocumentService(() -> remote);
    assertEquals(expected, lazy.checkPresence(ids, context).toCompletableFuture().join());
    assertEquals(1, closed.get());
    verify(client).checkDocumentPresence(ids, context);
    verifyNoMoreInteractions(client);
    assertThrows(java.util.concurrent.CompletionException.class, () -> remote.checkPresence(
        java.util.Collections.nCopies(65, "id"), context).toCompletableFuture().join());
    assertEquals(1, closed.get());
  }

  @Test
  void anUnimplementedCapabilityIsUnknownRatherThanInferredFromFetch() {
    DocumentService service = (id, context) -> { throw new AssertionError("must not fetch"); };
    assertTrue(service.checkPresence(List.of("id"), TestEngineContexts.internal())
        .toCompletableFuture().join().isEmpty());
  }
}
