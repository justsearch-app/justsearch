/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.RequestEngineContext;
import io.justsearch.ui.api.TestRequestContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** FP3: bare HTTP/MCP mode=splade through the real preset mapper, worker and response projector.
 * Only the client transport is replaced: it records time already spent queued and calls the
 * real worker. No Engine boot, parser child, dev stack or subprocess is used. The HTTP listener,
 * runtime and executor registrations close cooperatively in this JVM.
 */
final class SpladeOnlyBoundaryFallbackTest {
  @TempDir Path temp;
  private ConfigStore previousStore;
  private String previousConfig;
  private TestEngineExecutors executors;
  private LuceneExecutorRegistrations registrations;
  private RunningRuntime runtime;
  private Javalin http;
  private KnowledgeSearchController controller;
  private final ObservedLatch loading = new ObservedLatch();
  private SearchRequest captured;

  @BeforeEach
  void setup() throws Exception {
    previousStore = ConfigStore.globalOrNull();
    previousConfig = System.getProperty("justsearch.config");
    Path configFile = temp.resolve("config.yaml");
    Files.writeString(configFile, "app:\n  data_dir: '" + temp.toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: boundary\n      roots: ['ignored']\n"
        + "vector:\n  dimension: 4\n", StandardCharsets.UTF_8);
    System.setProperty("justsearch.config", configFile.toString());
    var config = new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.qu.enabled", "false", "justsearch.filter_norm.enabled", "false")));
    ConfigStore.setGlobal(config);
    executors = new TestEngineExecutors();
    registrations = new LuceneExecutorRegistrations(executors);
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(registrations).open();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "doc-1", SchemaFields.DOC_UID, "doc-1#0",
        SchemaFields.PATH, "doc-1", SchemaFields.CONTENT, "Hello world")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    var worker = new WorkerSearchService(runtime, NoOpEmbeddingProvider.starting());
    var compat = new EmbeddingCompatibilityController(
        () -> Map.of(EmbeddingCompatibilityController.COMMIT_META_KEY, "my-model"),
        () -> 1L, () -> 1, () -> Optional.of("my-model"));
    compat.refresh();
    worker.setEmbeddingCompatController(compat);
    worker.setModelReadyLatchSupplier(() -> loading);
    var client = mock(KnowledgeClient.class);
    when(client.requestDeadlineMs()).thenReturn(10_000L);
    when(client.getStatus(any(EngineContext.class))).thenReturn(StatusResponse.getDefaultInstance());
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      EngineContext context = invocation.getArgument(1);
      long now = System.nanoTime();
      var call = new CallContext(null, null, CallContext.CancelSignal.NEVER, context,
          new JobQueue.EnqueueProvenance(io.justsearch.app.services.intent.EngineProvenance.originator(context), context.transport()),
          CallContext.none().childLifetime(), now + Duration.ofSeconds(2).toNanos(),
          now - Duration.ofSeconds(9).toNanos());
      if (request.getPipeline().getSpladeEnabled()) captured = request;
      return worker.search(request, call);
    });
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(config.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.client()).thenReturn(client);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, OnlineAiService.unavailable(), null, null, config);
  }

  @AfterEach
  void close() throws Exception {
    loading.countDown();
    if (http != null) http.stop();
    if (runtime != null) runtime.close();
    if (registrations != null) registrations.close();
    if (executors != null) executors.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void bareHttpSpladeModeReturnsKeywordHitsWithBudgetAndContention() throws Exception {
    http = Javalin.create().post("/api/knowledge/search", ctx -> {
      ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser());
      controller.handleSearch(ctx);
    }).start(0);
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + http.port() + "/api/knowledge/search"))
          .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"Hello\",\"mode\":\"splade\"}"))
          .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      var json = JsonMapper.builder().build().readTree(response.body());
      assertEquals("SEMANTIC_BUDGET_EXCEEDED",
          json.path("searchTrace").path("degradation").path("spladeSkipReason").asText(), response.body());
      assertEquals("contended", json.path("searchTrace").path("degradation")
          .path("spladeSkipCause").asText());
      assertEquals("TEXT", json.path("searchTrace").path("effectiveMode").asText());
      assertTrue(json.path("totalHits").asLong() >= 1, response.body());
    }
    assertSpladeOnlyMapping();
  }

  @Test
  void bareMcpSpladeModeReturnsKeywordHitsAndDegradationNote() {
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> null, Clock.systemUTC());
    var response = surface.callTool("justsearch_search", Map.of("query", "Hello", "mode", "splade"),
        "splade-fallback", TestRequestContexts.mcp("splade-fallback"));
    var text = McpSemanticBudgetNoteTest.textOf(response);
    assertTrue(text.contains("SPLADE SEMANTIC_BUDGET_EXCEEDED (cause: contended)"), text);
    var structured = McpSemanticBudgetNoteTest.structuredOf(response);
    var json = JsonMapper.builder().build().valueToTree(structured);
    assertTrue(json.path("coverage").path("totalHits").asLong() >= 1, json.toString());
    assertEquals("TEXT", json.path("searchTrace").path("effectiveMode").asText());
    assertSpladeOnlyMapping();
  }

  private void assertSpladeOnlyMapping() {
    assertNotNull(captured);
    assertTrue(captured.getPipeline().getSpladeEnabled());
    assertFalse(captured.getPipeline().getSparseEnabled());
    assertFalse(captured.getPipeline().getDenseEnabled());
    assertEquals(0, loading.waits, "expired queue budget must not wait for models");
  }

  private static final class ObservedLatch extends CountDownLatch {
    int waits;
    ObservedLatch() { super(1); }
    @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      waits++;
      return super.await(timeout, unit);
    }
  }
}
