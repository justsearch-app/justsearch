/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ui.api.RetrieveContextController;
import io.justsearch.ui.api.TestRequestContexts;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real ingestion, worker, RemoteDocumentService and loopback transports; no LLM is composed. */
abstract class MmrEngineFixture {
  static final String QUESTION = "quokka catalyst telemetry";
  static final String OUTSIDE = "OUTSIDE_SCOPE_SECRET";
  static final int K = 3;
  static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path temp;
  ConfigStore store;
  private ConfigStore previous;
  private EngineRoot root;
  private AutoCloseable servingLease;
  private ExecutorService executor;
  private Javalin http;
  final Map<String, String> settings = new LinkedHashMap<>();
  KnowledgeClient client;
  RemoteDocumentService documents;
  WorkerSearchService worker;
  Path article;
  Path outside;

  void start(Path model) throws Exception {
    start(model, "bm25", false);
  }

  void start(Path model, String retrieval, boolean chunkVectors) throws Exception {
    Path data = Files.createDirectories(temp.resolve("data"));
    Path index = Files.createDirectories(data.resolve("index"));
    Path models = Files.createDirectories(temp.resolve("models"));
    settings.put("justsearch.ssot.path", data.resolve("SSOT").toString());
    settings.put("justsearch.home", temp.resolve("home").toString());
    settings.put("justsearch.data.dir", data.toString());
    settings.put("justsearch.index.base_path", index.toString());
    settings.put("justsearch.models.dir", models.toString());
    settings.put("justsearch.gpu.enabled", "false");
    settings.put("justsearch.embed.gpu.enabled", "false");
    settings.put("justsearch.ai.disabled", "false");
    settings.put("justsearch.ai.embed.enabled", Boolean.toString(model != null));
    settings.put("justsearch.ner.enabled", "false");
    settings.put("justsearch.bgem3.enabled", "false");
    settings.put("justsearch.splade.enabled", "false");
    settings.put("justsearch.rerank.enabled", "false");
    settings.put("justsearch.rerank.chunks.enabled", "false");
    settings.put("rag.retrieve.mode", retrieval);
    settings.put("rag.diversify.mode", "mmr");
    settings.put("rag.mmr.max_candidates", "9");
    settings.put("rag.mmr.lambda", "0.5");
    settings.put("rag.retrieve.overretrieve_factor", "3");
    settings.put("rag.chunk_vectors.enabled", Boolean.toString(chunkVectors));
    if (model != null) settings.put("justsearch.embed.onnx.model_path", model.toString());
    previous = ConfigStore.globalOrNull();
    store = new ConfigStore(configuration());
    ConfigStore.setGlobal(store);
    root = EngineRoot.forProcess(
        mock(io.justsearch.app.api.operations.OperationStore.class),
        mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        60_000, 5_000, code -> {},
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), () -> {},
        OperationAuthority.inMemory(), store);
    client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    Object server = field(root, "server");
    Object lease = server.getClass().getMethod("captureServingView").invoke(server);
    servingLease = (AutoCloseable) lease;
    worker = ((WorkerAppServices) lease.getClass().getMethod("services").invoke(lease)).searchService();
    @SuppressWarnings("unchecked")
    var modelsReady = (Supplier<CountDownLatch>) field(worker, "modelReadyLatchSupplier");
    assertNotNull(modelsReady, "real Engine owns the deferred-model latch");
    await("deferred model composition finished before ingestion or provider substitution",
        Duration.ofMinutes(model == null ? 2 : 5), () -> {
          var latch = modelsReady.get();
          return latch != null && latch.getCount() == 0;
        });
    executor = Executors.newVirtualThreadPerTaskExecutor();
    documents = new RemoteDocumentService(executor, executor, () -> client);

    article = Files.createDirectories(temp.resolve("included")).resolve("article.txt");
    outside = Files.createDirectories(temp.resolve("excluded")).resolve("decoy.txt");
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 32; i++) {
      text.append("Quokka catalyst telemetry passage ").append(i)
          .append(" records a distinct observation of the river sensor. ")
          .append("The quokka catalyst telemetry team calibrates the copper detector before each ")
          .append("measurement and preserves the numbered reading for the laboratory report.\n\n");
    }
    String articleText = text.toString();
    Files.writeString(article, articleText);
    Files.writeString(outside, (QUESTION + " " + OUTSIDE + ". ").repeat(100));
    assertEquals(2, client.submitBatch(List.of(article, outside), TestRequestContexts.browser())
        .getAcceptedCount());
    await("both ingested documents searchable", Duration.ofMinutes(2), () -> {
      var found = client.search("quokka", 10, PipelineConfigs.TEXT, TestRequestContexts.browser());
      return found.getResultsList().stream().anyMatch(h -> h.getId().equals(article.toString()))
          && found.getResultsList().stream().anyMatch(h -> h.getId().equals(outside.toString()));
    });
    await("more than K real passages, all K fit", Duration.ofMinutes(2), () -> {
      var result = retrieve();
      return result.chunksUsed() == K && result.quality().chunksConsidered() > K;
    });
    var adversarial = client.search(QUESTION, 10, PipelineConfigs.TEXT, TestRequestContexts.browser());
    var decoy = adversarial.getResultsList().stream()
        .filter(hit -> hit.getId().equals(outside.toString())).findFirst().orElseThrow();
    var included = adversarial.getResultsList().stream()
        .filter(hit -> hit.getId().equals(article.toString())).findFirst().orElseThrow();
    assertTrue(decoy.getScore() >= included.getScore(),
        "out-of-scope document must be at least as strong a lexical match as the included parent");
    System.out.println("MMR_ADVERSARIAL_SCOPE outside=" + decoy.getId() + " score=" + decoy.getScore()
        + " included=" + included.getId() + " score=" + included.getScore());
    mountHttp();
  }

  private io.justsearch.configuration.resolved.ResolvedConfig configuration() {
    var builder = new ResolvedConfigBuilder().contributeBaseSources();
    settings.forEach((key, value) -> builder.put(key, ResolvedConfigBuilder.ORDINAL_JVM_ARG,
        "test_fixture", "mmr_private_engine", value));
    return builder.build();
  }

  RetrieveContextParams params() {
    return new RetrieveContextParams(QUESTION, Set.of(article.toString()), K, 6000,
        List.of(), List.of(), List.of(), RetrieveContextParams.TimeRange.UNSET,
        false, article.toString(), List.of(), false, RetrieveContextParams.ContextFormat.LABELED,
        List.of(), List.of(), List.of(), RetrieveContextParams.TimeRange.UNSET,
        false, List.of(), List.of());
  }

  DocumentService.ContextResult retrieve() {
    return documents.retrieveContext(params(), TestRequestContexts.browser())
        .toCompletableFuture().join();
  }

  private void mountHttp() {
    var controller = new RetrieveContextController(null, documents, null, () -> "", store);
    HeadAssembly facade = mock(HeadAssembly.class);
    McpAnswerCaptureFixture.bind(facade, documents);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());
    var protocol = new McpProtocolHandler(surface, List.of(), Clock.systemUTC());
    http = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    http.post("/api/knowledge/retrieve-context", controller::handleRetrieveContext);
    http.post("/mcp", protocol::handlePost);
    http.start("127.0.0.1", 0);
  }

  JsonNode rest() throws Exception {
    return post("/api/knowledge/retrieve-context", Map.of("query", QUESTION, "top_k", K,
        "max_tokens", 6000, "auto_entity_extract", false,
        "filters", Map.of("path_prefix", article.toString())));
  }

  JsonNode mcp() throws Exception {
    var envelope = post("/mcp", Map.of("jsonrpc", "2.0", "id", 11, "method", "tools/call",
        "params", Map.of("name", "justsearch_answer", "arguments",
            Map.of("query", QUESTION, "top_k", K,
                "filters", Map.of("path_prefix", article.toString())))));
    assertFalse(envelope.has("error"), envelope.toString());
    var result = envelope.path("result");
    assertFalse(result.path("isError").asBoolean(true), result.toString());
    return result;
  }

  private JsonNode post(String path, Map<String, Object> body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + path))
        .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    try (var connection = HttpClient.newHttpClient()) {
      var response = connection.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      return JSON.readTree(response.body());
    }
  }

  void assertPassages(DocumentService.ContextResult result) {
    assertEquals(K, result.chunksUsed(), result.toString());
    assertEquals(K, result.citations().size());
    assertEquals(K, result.sections().size());
    assertTrue(result.quality().chunksConsidered() > K, "MMR must have a selection to make");
    assertFalse(result.context().contains(OUTSIDE));
    assertEquals(K, identities(result).stream().distinct().count(), "same-parent passage identity");
    for (int i = 0; i < K; i++) {
      var citation = result.citations().get(i);
      assertEquals(article.toString(), citation.parentDocId());
      assertTrue(citation.endChar() > citation.startChar());
      assertTrue(citation.chunkTotal() > K, "real multi-passage document");
      assertFalse(citation.excerpt().isBlank());
      assertTrue(result.sections().get(i).content().startsWith(literalExcerpt(citation.excerpt())),
          "citation describes its own literal section");
      assertTrue(result.context().contains(literalExcerpt(citation.excerpt())));
    }
  }

  static List<String> identities(DocumentService.ContextResult result) {
    return result.citations().stream().map(c -> c.parentDocId() + "#" + c.chunkIndex()
        + ":" + c.startChar() + ":" + c.endChar()).toList();
  }

  void assertOutward(DocumentService.ContextResult expected) throws Exception {
    assertAll("real REST and MCP evidence",
        () -> assertRest(expected), () -> assertMcp(expected));
  }

  private void assertRest(DocumentService.ContextResult expected) throws Exception {
    var rest = rest();
    System.out.println("MMR_REST " + rest);
    assertTrue(rest.path("ok").asBoolean(), rest.toString());
    assertEquals(K, rest.path("chunks").size(), rest.toString());
    assertEquals(expected.context(), rest.path("context").asText());
    assertFalse(rest.toString().contains(OUTSIDE));
    for (int i = 0; i < K; i++) {
      var c = expected.citations().get(i);
      assertEquals(c.parentDocId(), rest.path("chunks").get(i).path("parent_doc_id").asText());
      assertEquals(c.chunkIndex(), rest.path("chunks").get(i).path("chunk_index").asInt());
      assertEquals(c.startChar(), rest.path("chunks").get(i).path("start_char").asInt());
      assertEquals(c.endChar(), rest.path("chunks").get(i).path("end_char").asInt());
    }
    assertEquals(expected.retrievalMode(), rest.path("quality").path("retrieval_mode").asText());
  }

  private void assertMcp(DocumentService.ContextResult expected) throws Exception {
    var mcp = mcp();
    System.out.println("MMR_MCP " + mcp);
    var citations = mcp.path("structuredContent").path("citations");
    assertEquals(K, citations.size(), mcp.toString());
    for (int i = 0; i < K; i++) {
      var c = expected.citations().get(i);
      assertEquals(c.parentDocId(), citations.get(i).path("parentDocId").asText());
      assertEquals(c.chunkIndex(), citations.get(i).path("chunkIndex").asInt());
      assertEquals(c.startChar(), citations.get(i).path("startChar").asInt());
      assertEquals(c.endChar(), citations.get(i).path("endChar").asInt());
      assertTrue(mcp.path("content").get(0).path("text").asText()
          .contains(literalExcerpt(c.excerpt())));
    }
    assertFalse(mcp.toString().contains(OUTSIDE));
    assertEquals(expected.retrievalMode(),
        mcp.path("structuredContent").path("quality").path("retrievalMode").asText());
  }

  ConversationContext turn() {
    Map<String, Object> attributes = new HashMap<>();
    return new ConversationContext() {
      public EngineContext engineContext() { return TestRequestContexts.browser(); }
      public List<Map<String, Object>> messages() { return List.of(); }
      public int iteration() { return 0; }
      public Audience audience() { return Audience.USER; }
      public String sessionId() { return "mmr-private-turn"; }
      public Map<String, Object> requestBody() {
        return Map.of("question", QUESTION, "docIds", List.of(article.toString()), "topK", K);
      }
      public Map<String, Object> attributes() { return attributes; }
    };
  }

  void assertConversation(DocumentService service) {
    var turn = turn();
    // Window metadata only: no LLM or model session. The scoped worker returns all K; the
    // conversation's smaller independent budget must then make an observable later cut.
    var window = mock(OnlineAiService.class);
    when(window.llmContextTokens()).thenReturn(2048);
    turn.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    var result = new RAGContext(service, Duration.ofSeconds(60), K, () -> window).inject(turn);
    System.out.println("MMR_CONVERSATION_RESULT " + result);
    assertTrue(result.terminalError().isEmpty(), result.toString());
    @SuppressWarnings("unchecked")
    var sources = (List<DocumentService.VerificationSource>)
        turn.attributes().get(RAGContext.ATTR_VERIFICATION_SOURCES);
    @SuppressWarnings("unchecked")
    var citations = (List<DocumentService.ContextCitation>)
        turn.attributes().get(RAGContext.ATTR_CITATIONS);
    assertNotNull(sources, result.toString());
    assertNotNull(citations, result.toString());
    assertEquals(K, sources.size());
    assertEquals(K, citations.size());
    String prompt = result.messages().toString();
    assertFalse(prompt.contains(OUTSIDE));
    boolean retained = false;
    boolean cut = false;
    for (int i = 0; i < sources.size(); i++) {
      var source = sources.get(i);
      var citation = citations.get(i);
      assertEquals(article.toString(), citation.parentDocId());
      assertFalse(citation.inclusion().absent(), "head owns the inclusion verdict");
      assertEquals(citation.inclusion().includedChars(), source.literalText().length());
      cut |= citation.inclusion().wireName().equals("partial")
          || citation.inclusion().wireName().equals("dropped");
      if (!source.literalText().isEmpty()) {
        retained = true;
        assertEquals(citation, source.citation());
        assertTrue(prompt.contains(source.literalText()), "verification reads the retained literal");
      } else {
        assertEquals("dropped", citation.inclusion().wireName());
        assertEquals(DocumentService.ContextCitation.CHUNK_INDEX_ABSENT, source.citation().chunkIndex());
      }
    }
    assertTrue(retained, "conversation retained at least one real passage");
    assertTrue(cut, "conversation budget must actually trim the worker's selected passages");
    System.out.println("MMR_CONVERSATION citations=" + citations + " sources=" + sources
        + " messages=" + result.messages());
  }

  private static String literalExcerpt(String excerpt) {
    // The worker's display excerpt deliberately appends an ellipsis at its word-boundary cut.
    return excerpt.endsWith("...") ? excerpt.substring(0, excerpt.length() - 3) : excerpt;
  }

  static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  static void await(String description, Duration timeout, BooleanSupplier ready)
      throws InterruptedException {
    long until = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < until) {
      if (ready.getAsBoolean()) return;
      Thread.sleep(250);
    }
    fail("Timed out: " + description);
  }

  @AfterEach
  void closeFixture() throws Exception {
    try {
      if (http != null) http.stop();
      if (servingLease != null) servingLease.close();
      if (root != null) root.close();
    } finally {
      if (executor != null) executor.shutdownNow();
      if (store != null) ConfigStore.restoreGlobal(store, previous);
    }
  }
}
