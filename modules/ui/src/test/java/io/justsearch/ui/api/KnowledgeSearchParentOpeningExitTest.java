/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Exercises the public delivery adapters over the real Head search mapping, with Worker doubles. */
final class KnowledgeSearchParentOpeningExitTest {
  @TempDir Path tempDir;
  private ConfigStore previousConfig;
  private KnowledgeClient client;
  private KnowledgeSearchController controller;

  @BeforeEach
  void setUp() throws Exception {
    previousConfig = ConfigStore.globalOrNull();
    Path reranker = Files.createDirectories(tempDir.resolve("reranker"));
    Files.writeString(reranker.resolve("model.onnx"), "stand-in");
    Files.writeString(reranker.resolve("tokenizer.json"), "{}");
    var config = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.models.dir", tempDir.resolve("models").toString(),
        "justsearch.search.query_classification.enabled", "false",
        "justsearch.rerank.enabled", "true",
        "justsearch.rerank.model_path", reranker.toString(),
        "justsearch.rerank.min_hits", "1")));
    ConfigStore.setGlobal(config);
    client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(10_000L);
    when(client.getStatus(any())).thenReturn(io.justsearch.ipc.StatusResponse.getDefaultInstance());
    var wire = SearchResponse.newBuilder();
    for (int i = 0; i < 4; i++) {
      wire.addResults(SearchResult.newBuilder().setId("doc" + i).setScore(4 - i)
          .putFields("title", "T").putFields("path", "/doc" + i + ".pdf")
          .putFields("chunk_index", "1").putFields("content_preview", "chunk" + i)
          .putFields("parent_opening", "private-opening" + i));
    }
    when(client.search(any(SearchRequest.class), any())).thenReturn(wire.build());
    when(client.search(any(SearchRequest.class), eq(true), any())).thenAnswer(call ->
        client.search((SearchRequest) call.getArgument(0), call.getArgument(2)));
    when(client.rerankSearch(anyString(), anyList(), anyLong(), any())).thenAnswer(call -> {
      List<String> passages = call.getArgument(1);
      assertEquals(7, passages.size(), "four match passages plus only three openings");
      assertEquals("T private-opening2", passages.get(6));
      return RerankResponse.newBuilder().addAllScores(List.of(4f, 3f, 2f, 1f, 0f, 0f, 9f))
          .addAllSortedIndices(List.of(6, 0, 1, 2, 3, 4, 5)).build();
    });
    var server = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(server.publicationLock()).thenReturn(config.publicationLock());
    when(server.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(call ->
        ((java.util.function.Function<KnowledgeClient, ?>) call.getArgument(0)).apply(client));
    controller = new KnowledgeSearchController(server, mock(SearchPerSourceExecutor.class),
        null, OnlineAiService.unavailable(), null, null, config);
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void webSearchResponseContainsTheWinningHitWithoutTheWireField() {
    var context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(context.path()).thenReturn("/api/knowledge/search");
    when(context.bodyAsClass(Map.class)).thenReturn(Map.of("query", "quokka", "pipeline",
        Map.of("sparseEnabled", true, "denseEnabled", true, "crossEncoderEnabled", true)));
    when(context.status(anyInt())).thenReturn(context);

    controller.handleSearch(context);

    var delivered = ArgumentCaptor.forClass(Object.class);
    verify(context).json(delivered.capture());
    assertPublicOutput(delivered.getValue());
  }

  @Test
  void mcpAnswerProjectsTheRagPreSearchWithoutTheWireField() {
    var documents = documents();
    var facade = mock(io.justsearch.app.services.HeadAssembly.class);
    var capture = mock(io.justsearch.app.services.HeadAssembly.ServingCapture.class);
    when(facade.captureServingView()).thenReturn(capture);
    when(capture.documents()).thenReturn(documents);
    when(capture.config()).thenReturn(ConfigStore.global().get());
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());

    var delivered = surface.callTool("justsearch_answer", Map.of("query", "quokka"),
        "opening-test", TestRequestContexts.mcp("opening-test"));

    assertNotEquals(Boolean.TRUE, delivered.get("isError"), delivered.toString());
    assertRetrievalOutput(delivered, false);
    verify(capture).close();
  }

  @Test
  void webAnswerRetrievalProjectsThePreSearchWithoutTheWireField() {
    var retrieval = new RetrieveContextController(null, documents(), OnlineAiService.unavailable(), () -> "");
    var context = mock(Context.class);
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(context.bodyAsClass(Map.class)).thenReturn(Map.of("query", "quokka"));
    when(context.status(anyInt())).thenReturn(context);

    retrieval.handleRetrieveContext(context);

    var delivered = ArgumentCaptor.forClass(Object.class);
    verify(context).json(delivered.capture());
    assertRetrievalOutput(delivered.getValue(), true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"justsearch://index/top-sources", "justsearch://index/top-entities"})
  void mcpFacetResourcesProjectOnlyFacets(String uri) {
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> null, Clock.systemUTC());

    var delivered = surface.readResource(uri, TestRequestContexts.mcp("opening-test"));

    verify(client).search(any(SearchRequest.class), any());
    String output = delivered.toString();
    assertTrue(output.contains("application/json"), output);
    assertFalse(output.contains("parent_opening"), output);
    assertFalse(output.contains("private-opening"), output);
    assertFalse(output.contains("doc2"), output);
  }

  private io.justsearch.app.services.worker.RemoteDocumentService documents() {
    org.mockito.stubbing.Answer<io.justsearch.ipc.RetrieveContextResponse> retrieval = call -> {
          io.justsearch.app.api.RetrieveContextParams params = call.getArgument(0);
          assertEquals(4, params.docIds().size());
          assertFalse(params.toString().contains("private-opening"));
          return io.justsearch.ipc.RetrieveContextResponse.newBuilder()
              .setContext("retrieved evidence").setChunksFound(1).build();
        };
    when(client.retrieveContext(any(io.justsearch.app.api.RetrieveContextParams.class), any()))
        .thenAnswer(retrieval);
    when(client.retrieveContext(any(io.justsearch.app.api.RetrieveContextParams.class),
        eq(false), eq(true), any())).thenAnswer(retrieval);
    return new io.justsearch.app.services.worker.RemoteDocumentService(
        Runnable::run, Runnable::run, () -> client);
  }

  private void assertRetrievalOutput(Object delivered, boolean includePrivateData) {
    verify(client).search(any(SearchRequest.class), any());
    if (includePrivateData) {
      verify(client).retrieveContext(any(io.justsearch.app.api.RetrieveContextParams.class),
          eq(false), eq(true), any());
    } else {
      verify(client).retrieveContext(any(io.justsearch.app.api.RetrieveContextParams.class), any());
    }
    String output = delivered.toString();
    assertTrue(output.contains("retrieved evidence"), output);
    assertFalse(output.contains("parent_opening"), output);
    assertFalse(output.contains("private-opening"), output);
  }

  @Test
  void mcpSearchTextAndStructuredOutputExcludeTheWireField() {
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> null, Clock.systemUTC());

    var delivered = surface.callTool("justsearch_search", Map.of("query", "quokka"),
        "opening-test", TestRequestContexts.mcp("opening-test"));

    assertNotEquals(Boolean.TRUE, delivered.get("isError"), delivered.toString());
    assertPublicOutput(delivered);
  }

  private void assertPublicOutput(Object delivered) {
    verify(client).rerankSearch(anyString(), anyList(), anyLong(), any());
    String output = delivered.toString();
    assertTrue(output.contains("doc2"), output);
    assertFalse(output.contains("parent_opening"), output);
    assertFalse(output.contains("private-opening"), output);
  }
}
