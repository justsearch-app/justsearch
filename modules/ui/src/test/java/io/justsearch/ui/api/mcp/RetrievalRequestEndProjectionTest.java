/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.ui.api.RetrieveContextController;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/** D7/D8: projections are local to retrieve-context and justsearch_answer. */
final class RetrievalRequestEndProjectionTest {
  static Stream<Arguments> endings() {
    return Stream.of("raw", "reason", "port", "deadline", "nullDeadline", "local")
        .flatMap(kind -> Stream.of("direct", "completion", "execution")
            .map(wrapper -> Arguments.of(kind, wrapper)));
  }

  private static Exception failure(String kind, String wrapper) {
    Exception end = switch (kind) {
      case "raw" -> new CancellationException();
      case "reason" -> new EngineWorkCancelledException("CONTEXT_CLOSED");
      case "port" -> new KnowledgeClientException(KnowledgeClientException.Status.CANCELLED, "cancelled");
      case "deadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "neutral");
      case "nullDeadline" -> new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, null);
      default -> new TimeoutException();
    };
    return switch (wrapper) {
      case "completion" -> new CompletionException(end);
      case "execution" -> new ExecutionException(end);
      default -> end;
    };
  }

  private static boolean isDeadline(String kind) {
    return kind.equals("deadline") || kind.equals("nullDeadline") || kind.equals("local");
  }

  @ParameterizedTest @MethodSource("endings")
  void restReportsRequestEndRatherThanEvidence(String kind, String wrapper) {
    boolean deadline = isDeadline(kind);
    String expected = deadline ? "TIMEOUT" : "SERVICE_UNAVAILABLE";
    var docs = mock(DocumentService.class);
    when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.failedFuture(failure(kind, wrapper)));
    var controller = new RetrieveContextController(null, docs, OnlineAiService.unavailable(), () -> "");
    var ctx = mock(Context.class);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("query", "q"));
    when(ctx.attribute(io.justsearch.ui.api.RequestEngineContext.ATTRIBUTE))
        .thenReturn(TestRequestContexts.browser());
    when(ctx.status(anyInt())).thenReturn(ctx);
    assertDoesNotThrow(() -> controller.handleRetrieveContext(ctx));
    var response = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(response.capture());
    var rest = (Map<?, ?>) response.getValue();
    assertEquals(false, rest.get("ok"));
    assertEquals(expected, rest.get("errorCode"));
    verify(ctx).status(deadline ? 504 : 503);
    if (kind.equals("reason")) assertEquals("CONTEXT_CLOSED", rest.get("reasonCode"));
  }

  @ParameterizedTest @MethodSource("endings")
  void mcpReportsRequestEndRatherThanEvidence(String kind, String wrapper) {
    String expected = isDeadline(kind) ? "TIMEOUT" : "SERVICE_UNAVAILABLE";
    var docs = mock(DocumentService.class);
    when(docs.retrieveContext(any(), any())).thenReturn(CompletableFuture.failedFuture(failure(kind, wrapper)));
    var facade = mock(HeadAssembly.class);
    McpAnswerCaptureFixture.bind(facade, docs);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());
    var mcp = assertDoesNotThrow(() -> surface.callTool("justsearch_answer", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1")));
    assertEquals(true, mcp.get("isError"));
    assertEquals(expected, ((Map<?, ?>) mcp.get("structuredContent")).get("errorCode"));
    assertTrue(mcp.get("content").toString().contains(expected));
    assertFalse(mcp.get("content").toString().contains("No relevant passages found"));
  }
}
