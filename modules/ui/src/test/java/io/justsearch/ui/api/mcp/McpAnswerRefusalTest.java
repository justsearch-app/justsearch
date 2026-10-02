/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

final class McpAnswerRefusalTest {
  private ConfigStore previousConfigStore;

  @BeforeEach
  void configure() {
    // Q4 (f09566a32) exercises real pre-search, including its configured hybrid preset.
    previousConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  static Stream<Arguments> refusalPaths() {
    return Stream.of("preSearch", "retrieval", "fallback")
        .flatMap(path -> Stream.of("engine", "context", "executor")
            .flatMap(kind -> Stream.of(false, true)
                .map(wrapped -> Arguments.of(path, kind, wrapped))));
  }

  @ParameterizedTest
  @MethodSource("refusalPaths")
  void realDocumentProducerRefusalReachesRpcWithHealthyStatus(
      String path, String kind, boolean wrapped) throws Exception {
    RuntimeException refusal = switch (kind) {
      case "engine" -> new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 7);
      case "context" -> new EngineAdmissionException(EngineAdmissionException.Reason.CONTEXT_LIMIT, 7);
      case "executor" -> new EngineExecutorRejectedException(
          EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "answer-retrieval", 7);
      default -> throw new AssertionError(kind);
    };
    RuntimeException failure = wrapped
        ? new CompletionException(new ExecutionException(refusal)) : refusal;
    var engineContext = TestRequestContexts.mcp("s1");
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class)))
        .thenAnswer(ignored -> {
          if (path.equals("preSearch")) throw failure;
          if (path.equals("fallback")) {
            return SearchResponse.newBuilder()
                .addResults(SearchResult.newBuilder().setId("doc").putFields("path", "doc").build())
                .build();
          }
          return SearchResponse.getDefaultInstance();
        });
    when(client.retrieveContext(any(RetrieveContextParams.class), any(EngineContext.class)))
        .thenAnswer(invocation -> {
          RetrieveContextParams params = invocation.getArgument(0);
          if (path.equals("fallback")) {
            assertEquals(Set.of("doc"), params.docIds(), "fallback must fetch the discovered document");
            throw new IllegalStateException("chunks unavailable");
          }
          assertEquals(Set.of(), params.docIds(), "MCP answer starts with open retrieval");
          if (path.equals("retrieval")) throw failure;
          return RetrieveContextResponse.getDefaultInstance();
        });
    when(client.fetchDocuments(anyList(), any(EngineContext.class))).thenThrow(failure);
    var documents = new RemoteDocumentService(Runnable::run, Runnable::run, () -> client);
    var facade = mock(HeadAssembly.class);
    var capture = McpAnswerCaptureFixture.bind(facade, documents);

    // Healthy enrichment must not mask a producer that replaces refusal with empty evidence.
    var healthyStatus = mock(KnowledgeStatus.class);
    when(healthyStatus.extras()).thenReturn(Map.of(
        "embeddingCoveragePercent", 100.0, "spladeCoveragePercent", 100.0));
    var adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status(any(EngineContext.class))).thenReturn(healthyStatus);
    assertSame(healthyStatus, adapter.status(engineContext));
    clearInvocations(adapter);
    var knowledge = mock(KnowledgeSearchController.class);
    when(knowledge.getAdapter()).thenReturn(adapter);
    var clock = Clock.systemUTC();
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> knowledge, () -> facade, clock);
    var admission = mock(EngineAdmissionService.class);
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(engineContext);
    when(admission.admit(any(EngineContext.class), eq(false))).thenReturn(work);
    var protocol = new McpProtocolHandler(surface, List.of(), clock, admission);
    var ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/mcp");
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body()).thenReturn("""
        {"jsonrpc":"2.0","id":"answer-refused","method":"tools/call",
         "params":{"name":"justsearch_answer","arguments":{"query":"q"}}}
        """);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    var response = ArgumentCaptor.forClass(String.class);
    when(ctx.result(response.capture())).thenReturn(ctx);

    protocol.handlePost(ctx);

    var wire = JsonMapper.builder().build().readTree(response.getValue());
    assertEquals("answer-refused", wire.path("id").asText());
    assertFalse(wire.has("result"), "refusal must not become a successful empty evidence pack");
    assertEquals(-32000, wire.path("error").path("code").asInt());
    assertEquals(kind.equals("context") ? "ADMISSION_CONTEXT_LIMIT" : "ADMISSION_ENGINE_LIMIT",
        wire.path("error").path("data").path("errorCode").asText());
    assertFalse(wire.path("error").path("data").path("retrySafe").asBoolean(true));
    verify(ctx).status(429);
    verify(ctx).header("Retry-After", "7");
    verify(admission).admit(any(EngineContext.class), eq(false));
    verify(client).search(any(SearchRequest.class), eq(engineContext));
    if (path.equals("preSearch")) {
      verify(client, never()).retrieveContext(any(RetrieveContextParams.class), any(EngineContext.class));
    } else {
      verify(client).retrieveContext(any(RetrieveContextParams.class), eq(engineContext));
    }
    if (path.equals("fallback")) {
      verify(client).fetchDocuments(eq(List.of("doc")), eq(engineContext));
    } else {
      verify(client, never()).fetchDocuments(anyList(), any(EngineContext.class));
    }
    verify(adapter, never()).status(any(EngineContext.class));
    verify(capture).close();
  }
}
