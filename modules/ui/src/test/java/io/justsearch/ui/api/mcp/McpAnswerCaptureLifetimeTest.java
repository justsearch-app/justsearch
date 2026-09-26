/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The MCP answer owns one Head capture until retrieval and formatting both exit. */
final class McpAnswerCaptureLifetimeTest {
  @Test
  void answerRetainsItsCaptureUntilHeldRetrievalCompletes() throws Exception {
    var documents = mock(DocumentService.class);
    var pending = new CompletableFuture<DocumentService.ContextResult>();
    var retrievalStarted = new CountDownLatch(1);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenAnswer(ignored -> {
      retrievalStarted.countDown();
      return pending;
    });
    var facade = mock(HeadAssembly.class);
    var capture = McpAnswerCaptureFixture.bind(facade, documents);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());
    AtomicReference<Map<String, Object>> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread request = Thread.ofVirtual().start(() -> {
      try {
        result.set(surface.callTool("justsearch_answer", Map.of("query", "held A"),
            "s1", TestRequestContexts.mcp("s1")));
      } catch (Throwable thrown) { failure.set(thrown); }
    });

    assertTrue(retrievalStarted.await(2, TimeUnit.SECONDS));
    verify(capture, never()).close();
    pending.complete(new DocumentService.ContextResult("held A evidence", 1, 1, 0,
        List.of(), "HYBRID", "OK", false, List.of()));
    request.join(2_000);
    assertFalse(request.isAlive());
    assertNull(failure.get());
    assertFalse(Boolean.TRUE.equals(result.get().get("isError")));
    verify(capture).close();
  }
}
