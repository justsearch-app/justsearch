/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.TextQueryOps;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.QueryUnderstandingService;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.RequestEngineContext;
import io.justsearch.ui.api.TestRequestContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP -> Head -> Engine -> Worker -> Lucene; no model weights or GPU. */
@Timeout(60)
final class Bm25OnlyCursorHttpTest {
  private static final int WINDOW = 20;
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temp;
  private ConfigStore previous;
  private DefaultEngineExecutorRegistry registry;
  private LuceneExecutorRegistrations luceneExecutors;
  private RunningRuntime runtime;
  private EngineKnowledgeClient client;
  private Javalin http;
  private WorkerSearchService worker;
  private TextQueryOps textQueries;
  private final OnlineAiService ai = mock(OnlineAiService.class);
  private final List<SearchRequest> requests = new CopyOnWriteArrayList<>();
  private final List<SearchResponse> responses = new CopyOnWriteArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void open() throws Exception {
    previous = ConfigStore.globalOrNull();
    Path model = Files.createDirectories(temp.resolve("models/onnx/reranker"));
    Files.writeString(model.resolve("model_manifest.json"), "{}");
    Files.writeString(model.resolve("tokenizer.json"), "{}");
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.home", temp.toString(),
        "justsearch.data.dir", temp.resolve("data").toString(),
        "justsearch.models.dir", temp.resolve("models").toString(),
        "justsearch.rerank.top_k", "20", "justsearch.rerank.min_hits", "1",
        "justsearch.qu.enabled", "true",
        "justsearch.search.query_classification.enabled", "false"))));
    assertTrue(RerankerConfig.fromEnv().isReady());
    assertEquals(WINDOW, RerankerConfig.fromEnv().topK());
    registry = new DefaultEngineExecutorRegistry(new EngineResourcePolicy(Map.of(
        "perContextLimit", 16, "aggregateLimit", 32, "retryAfterSeconds", 1,
        "foregroundThreads", 2, "foregroundQueue", 16,
        "backgroundThreads", 1, "backgroundQueue", 8,
        "timerRegistrations", 32, "directMemoryMiB", 1), new RetainedStateBudget()),
        Duration.ofSeconds(1));
    luceneExecutors = new LuceneExecutorRegistrations(registry);
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(luceneExecutors).open();
    textQueries = spy(runtime.textQueryOps());
    RunningRuntime observed = spy(runtime);
    doReturn(textQueries).when(observed).textQueryOps();
    worker = spy(new WorkerSearchService(observed));
    doAnswer(call -> {
      SearchRequest request = call.getArgument(0);
      requests.add(request);
      SearchResponse response = (SearchResponse) call.callRealMethod();
      responses.add(response);
      return response;
    }).when(worker).search(any(SearchRequest.class), any(CallContext.class));
    // Record Head eligibility while keeping native inference out of this fixture.
    doReturn(RerankResponse.newBuilder().setSkipped(true).setSkipReason("MODEL_NOT_LOADED").build())
        .when(worker).rerank(any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(worker);
    client = new EngineKnowledgeClient(registry, () -> services,
        new ForegroundLoadGate(new ForegroundLoad()), 10_000, 100, IpcTelemetry.noop(),
        () -> {}, new EngineAdmissionController(16, 32, 1), WatchedRootsState.inMemory());
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(call ->
        ((Function<KnowledgeClient, ?>) call.getArgument(0)).apply(client));
    var controller = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class),
        null, ai, null, null, ConfigStore.global());
    http = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    http.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser()));
    http.post("/api/knowledge/search", controller::handleSearch);
    http.start("127.0.0.1", 0);
  }

  @AfterEach
  void close() {
    try {
      if (http != null) http.stop();
      if (client != null) client.close();
      if (runtime != null) runtime.close();
      if (luceneExecutors != null) luceneExecutors.close();
      if (registry != null) registry.close();
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @ParameterizedTest(name = "hybridFallbackWalksEveryParentOnce({0})")
  @ValueSource(ints = {30, 41, 20, 21})
  void hybridFallbackWalksEveryParentOnce(int count) throws Exception {
    seed(count);
    assertWalk(count, "HYBRID", "relevance");
    for (int i = 0; i < requests.size(); i++) {
      assertEquals(WINDOW, requests.get(i).getLimit(), "Head sends its CE window");
      assertEquals(Math.min(WINDOW, count - WINDOW * i), responses.get(i).getResultsCount(),
          "real retrieval is 20, without chunk overfetch");
      var degradation = responses.get(i).getSearchTrace().getDegradation();
      assertTrue(degradation.getHybridFallback());
      assertEquals("NO_EMBEDDING_SERVICE", degradation.getHybridFallbackReason());
    }
    verify(worker, times((count + WINDOW - 1) / WINDOW)).rerank(any(), any());
    var retrievals = mockingDetails(textQueries).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals("searchText")).toList();
    assertFalse(retrievals.isEmpty(), "observe the real Worker lexical retrieval calls");
    for (var call : retrievals) assertEquals(WINDOW, (int) call.getArgument(1), "actual retrievalLimit");
    assertEquals(1, responses.getFirst().getResultsList().stream().map(hit -> hit.getScore())
        .distinct().count(), "the stable parents deliberately tie on relevance");
  }

  @ParameterizedTest(name = "fallbackRejectsInvalidCursorLikeText({0})")
  @ValueSource(strings = {"invalid", "safter-v1:path_asc:L2RvYy0wMC50eHQ:_:_:_",
      "safter-v1:relevance:bWlzc2luZw:1.0:_:_"})
  void fallbackRejectsInvalidCursorLikeText(String cursor) throws Exception {
    seed(30);
    for (String mode : List.of("TEXT", "HYBRID")) {
      var body = body(mode, "relevance", cursor);
      HttpResponse<String> response = post(body);
      assertEquals(400, response.statusCode(), "invalid cursor must not silently restart: " + mode);
      assertEquals("CURSOR_INVALID", JSON.readTree(response.body()).path("errorCode").asText());
    }
  }

  @Test
  void textAndFieldSortedPagesPreserveTheirBoundaries() throws Exception {
    seed(41);
    assertWalk(41, "TEXT", "relevance");
    assertWalk(41, "TEXT", "path_asc");
    assertWalk(41, "TEXT", "path_desc");
  }

  @Test
  void firstPageAndEmptyCursorKeepTheSameLookahead() throws Exception {
    seed(30);
    JsonNode first = search(body("HYBRID", "relevance", null));
    JsonNode empty = search(body("HYBRID", "relevance", ""));
    JsonNode text = search(body("TEXT", "relevance", null));
    assertEquals(ids(first), ids(empty));
    assertEquals(ids(first), ids(text));
    assertEquals(20, ids(first).size());
    assertEquals(first.path("nextCursor"), empty.path("nextCursor"));
    assertEquals(first.path("nextCursor"), text.path("nextCursor"));
    assertEquals(30, first.path("totalHits").asInt());
    assertTrue(first.path("nextCursor").asText().startsWith("safter-v1:relevance:"));
  }

  @Test
  void cursorPagesKeepRerankingEligibleAndExcludeUnderstandingAndExpansion() throws Exception {
    seed(41);
    JsonNode first = search(body("HYBRID", "relevance", null));
    assertTrue(ConfigStore.global().get().search().queryUnderstandingEnabled());
    when(ai.isAvailable()).thenReturn(true);
    assertTrue(new QueryUnderstandingService(ai, () -> true).isAvailable(),
        "QU would be available on an eligible first page");
    clearInvocations(worker, ai);
    var next = body("HYBRID", "relevance", first.path("nextCursor").asText());
    next.put("pipeline", Map.of("sparseEnabled", true, "denseEnabled", true,
        "crossEncoderEnabled", true, "crossEncoderWindow", WINDOW, "expansionEnabled", true));
    search(next);
    verify(worker).rerank(any(), any());
    verify(ai, never()).chatCompletion(any(), anyInt(), any(), any());
  }

  @Test
  void fieldSortKeepsExplicitScopeAndProjectionAcrossPages() throws Exception {
    seed(41);
    List<String> scope = IntStream.range(0, 21).mapToObj(Bm25OnlyCursorHttpTest::id).toList();
    for (String sort : List.of("path_asc", "path_desc")) {
      var request = body("TEXT", sort, null);
      List<String> ordered = sort.equals("path_desc") ? scope.reversed() : scope;
      request.put("filters", Map.of("docIds", scope));
      request.put("projection", List.of("path"));
      JsonNode first = search(request);
      assertEquals(ordered.subList(0, 20), ids(first));
      assertEquals(21, first.path("totalHits").asInt());
      request.put("cursor", first.path("nextCursor").asText());
      JsonNode next = search(request);
      assertEquals(ordered.subList(20, 21), ids(next));
      assertEquals(21, next.path("totalHits").asInt());
      assertFalse(next.has("nextCursor"));
      for (JsonNode page : List.of(first, next)) {
        for (JsonNode hit : page.path("results")) {
          assertEquals(1, hit.path("fields").size());
          assertEquals(hit.path("id").asText(), hit.path("fields").path("path").asText());
        }
      }
    }
  }

  private void assertWalk(int count, String mode, String sort) throws Exception {
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = (count + WINDOW - 1) / WINDOW;
    for (int page = 0; page < pages; page++) {
      JsonNode result = search(body(mode, sort, cursor));
      List<String> ids = ids(result);
      for (String id : ids) assertTrue(seen.add(id), "page " + (page + 1) + " repeats " + id);
      assertEquals(Math.min(WINDOW, count - WINDOW * page), ids.size());
      assertEquals(count, result.path("totalHits").asInt());
      if (sort.startsWith("path_")) {
        List<String> expectedOrder = IntStream.range(0, count).mapToObj(Bm25OnlyCursorHttpTest::id)
            .sorted(sort.equals("path_desc") ? Comparator.reverseOrder()
                : Comparator.naturalOrder()).toList();
        assertEquals(expectedOrder.subList(page * WINDOW, Math.min(count, (page + 1) * WINDOW)), ids);
      }
      cursor = result.path("nextCursor").asText(null);
      if (page + 1 < pages) assertNotNull(cursor, "lookahead must keep another page reachable");
    }
    assertEquals(new HashSet<>(IntStream.range(0, count).mapToObj(Bm25OnlyCursorHttpTest::id).toList()), seen);
    assertNull(cursor, "last page must end without a cursor");
  }

  private void seed(int count) {
    for (int i = 0; i < count; i++) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id(i), SchemaFields.DOC_UID, id(i) + "#0",
          SchemaFields.PATH, id(i), SchemaFields.CONTENT, "quokka telemetry stable parent",
          SchemaFields.IS_CHUNK, "false", SchemaFields.PARENT_TOKEN_COUNT, 5)));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(0, runtime.indexCountOps().getOrComputeCorpusProfile().chunkDocCount());
  }

  private static String id(int i) { return "/doc-%02d.txt".formatted(i); }

  private static Map<String, Object> body(String mode, String sort, String cursor) {
    Map<String, Object> body = new HashMap<>();
    body.put("query", "quokka");
    body.put("limit", WINDOW);
    body.put("mode", mode);
    body.put("sort", sort);
    body.put("includeExcerpts", false);
    body.put("pipeline", Map.of("sparseEnabled", true, "denseEnabled", mode.equals("HYBRID"),
        "crossEncoderEnabled", true, "crossEncoderWindow", WINDOW));
    if (cursor != null) body.put("cursor", cursor);
    return body;
  }

  private JsonNode search(Map<String, Object> body) throws Exception {
    HttpResponse<String> response = post(body);
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> post(Map<String, Object> body) throws Exception {
    try (HttpClient transport = HttpClient.newHttpClient()) {
      return transport.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
          + "/api/knowledge/search")).header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(
              JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    for (JsonNode hit : page.path("results")) ids.add(hit.path("id").asText());
    return ids;
  }
}
