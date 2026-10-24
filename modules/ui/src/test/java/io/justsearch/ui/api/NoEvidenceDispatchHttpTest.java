/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.shapes.FreeChatShape;
import io.justsearch.app.services.conversation.shapes.RAGAskShape;
import io.justsearch.app.services.conversation.spi.AnswerShapeGrammar;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer;
import io.justsearch.app.services.conversation.spi.QueryRewriteInjector;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.RAGDoneEnricher;
import io.justsearch.app.services.conversation.spi.RAGQAStyle;
import io.justsearch.app.services.conversation.spi.SelectionContextInjector;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ui.api.routes.AiRoutes;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.mock;

/**
 * WP4.2b NE1–NE6/NE10/F2. Real dispatch routes, production shapes/SPIs and disk store; model output
 * comes only from the repository's separate StreamingFakeLlamaServer process. Each test owns its
 * loopback ports, executors, model process and directory. Restart rebuilds the Engine and HTTP server.
 */
@Timeout(120)
final class NoEvidenceDispatchHttpTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final Documents documents = new Documents();
  private Path directory;
  private Path fakeHome;
  private ConfigStore previousConfig;
  private TestEngineExecutors executors;
  private InferenceLifecycleManager manager;
  private CapturingAi ai;
  private Javalin app;
  private FileConversationStore conversationStore;
  private ChatController chat;
  private InteractionThreadController thread;
  private int retrievals;

  @BeforeEach
  void setup() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("settings.gradle.kts"))) {
      root = root.getParent();
      if (root == null) throw new IllegalStateException("Repository root not found");
    }
    directory = Files.createTempDirectory(Files.createDirectories(root.resolve("tmp/no-evidence-tests")), "http-");
    setup(directory);
  }

  private void setup(Path dataDirectory) throws Exception {
    directory = dataDirectory;
    Files.createDirectories(directory);
    previousConfig = ConfigStore.globalOrNull();
    fakeHome = directory.resolve("fake-model");
    Path executable = StreamingFakeLlamaServer.install(fakeHome);
    Path model = Files.writeString(directory.resolve("fake.gguf"), "fixture only, never a real model");
    var config = new ResolvedConfigBuilder();
    config.putDefault("justsearch.home", directory.toString());
    config.putDefault("justsearch.data.dir", directory.resolve("data").toString());
    config.putDefault("justsearch.server.exe", executable.toString());
    config.put("justsearch.llm.model_path", 400, "env_var", "test", model.toString());
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      config.putDefault("justsearch.server.port", Integer.toString(socket.getLocalPort()));
    }
    config.putDefault("justsearch.context.size", "4096");
    config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false");
    config.putDefault("justsearch.llm.enabled", "true");
    var resolved = config.build();
    ConfigStore.setGlobal(new ConfigStore(resolved));
    executors = TestEngineExecutors.awaitingTermination();
    var admission = new EngineAdmissionController(4, 8, 1);
    manager = BootstrapInferenceFactory.createInferenceManager(executors, true, resolved,
        directory.toString(), null, LoggerFactory.getLogger(getClass()), ManagedChildRegistry.noop());
    manager.switchToOnlineMode();
    assertTrue(manager.isOnline(), "only the private fake model starts");
    ai = new CapturingAi(new OnlineAiServiceImpl(admission, manager));
    StreamingFakeLlamaServer.release(fakeHome);
    start(admission);
  }

  private void start(EngineAdmissionController admission) {
    // The private browser probe can race a real store lock with a displayed action. No key or
    // encrypted user data is involved: ordinary tests keep the disabled plaintext state.
    var keyState = new io.justsearch.agent.api.encryption.DataKeyState() {
      @Override public boolean enabled() { return Files.exists(directory.resolve("locked")); }
      @Override public boolean locked() { return enabled(); }
      @Override public byte[] dek() { throw new io.justsearch.agent.api.encryption.KeyLockedException(); }
    };
    conversationStore = new FileConversationStore(directory.resolve("conversations"),
        new io.justsearch.agent.api.encryption.StoreCipher(keyState));
    var engine = new ConversationEngine(
        ConversationShapeCatalog.of("core", List.of(RAGAskShape.definition(), FreeChatShape.definition())),
        List.of(), PromptContributorRegistry.of(List.of(RAGQAStyle.INSTANCE, AnswerShapeGrammar.INSTANCE)),
        ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai),
            new SelectionContextInjector(documents, () -> ai), new QueryRewriteInjector(() -> ai),
            new RAGContext(documents, 5, () -> ai), UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of(new StreamingCitationMatcher(documents),
            RAGDoneEnricher.INSTANCE, new MemoryExtractionConsumer(MemoryStore.noop()))),
        IterationControllerRegistry.of(List.of()), () -> ai, conversationStore, admission);
    chat = new ChatController(executors, engine, new SseWriter(null), null, conversationStore);
    thread = new InteractionThreadController(mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        conversationStore, AgentService.unavailable(), executors, admission);
    app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    AiRoutes.register(app, mock(PreviewController.class), mock(AiInstallController.class),
        mock(AiPackController.class), mock(AiRuntimeController.class), mock(AiModelsController.class), chat);
    app.get("/api/thread/{id}", thread::handleGet);
    app.start("127.0.0.1", 0);
  }

  private void stopEngine() {
    if (app != null) { app.stop(); app = null; }
    if (thread != null) { thread.shutdown(); thread = null; }
    if (chat != null) { chat.shutdown(); chat = null; }
  }

  @AfterEach
  void cleanup() throws Exception {
    var ownedExecutors = executors;
    var ownedManager = manager;
    try (client; ownedExecutors; ownedManager) {
      stopEngine();
    } finally {
      try {
        if (fakeHome != null) StreamingFakeLlamaServer.endAll(fakeHome);
      } finally {
        TestResolvedConfigHelper.restoreGlobal(previousConfig);
        if (directory != null) try (var paths = Files.walk(directory)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      }
    }
  }

  @Test
  void noEvidenceAndTheSameQuestionAsModelAnswerSurviveColdReadsAndEngineRestart() throws Exception {
    String question = "What is a lunar eclipse?";
    String empty = dispatch(Map.of("shapeId", "core.rag-ask", "question", question, "conversationId", "conv-empty"));
    assertTrue(empty.contains("NO_CONTENT"), empty);
    assertTrue(empty.contains("No matching documents found in the index"), empty);
    assertEquals(0, ai.requests.size(), "no model runs for no evidence");
    assertNoEvidence("conv-empty", false);
    int searched = retrievals;
    String answer = dispatch(Map.of("shapeId", "core.free-chat", "prompt", question, "sessionId", "conv-empty"));
    assertTrue(answer.contains("event: done"), answer);
    assertEquals(searched, retrievals, "the model action performs no search");
    assertEquals(1, ai.requests.size());
    List<Map<String, Object>> saved = coldHistory("conv-empty");
    assertEquals(4, saved.size(), saved.toString());
    assertEquals(question, saved.get(2).get("content"));
    assertEquals("core.free-chat", saved.get(3).get("shapeId"));
    assertFalse(String.valueOf(saved.get(3).get("content")).isBlank());
    assertFalse(saved.get(3).containsKey("citations"), "model answers carry no citation marks");
    List<Object> ids = saved.stream().map(message -> message.get("id")).toList();
    assertNoEvidence("conv-empty", false);
    assertEquals(4, get("/api/thread/conv-empty").path("events").size());
    stopEngine(); start(new EngineAdmissionController(4, 8, 1));
    assertEquals(ids, coldHistory("conv-empty").stream().map(message -> message.get("id")).toList());
    assertEquals(4, get("/api/thread/conv-empty").path("events").size());
    assertNoEvidence("conv-empty", false);
  }

  @Test
  void scopedNoContentAndEveryRetrievalErrorKeepTheirClassificationAcrossRestart() throws Exception {
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "scoped-empty", "docIds", List.of("empty.md"), "conversationId", "scoped"));
    assertNoEvidence("scoped", true);
    conversationStore.createSession("hidden", "core.rag-ask");
    conversationStore.excludeSource("hidden", "empty.md" + (char) 0x1f + "0", true);
    int fetched = documents.fetches;
    String hidden = dispatch(Map.of("shapeId", "core.rag-ask", "question", "all selected files hidden",
        "docIds", List.of("empty.md"), "conversationId", "hidden"));
    assertTrue(hidden.contains("No content in selected files"), hidden);
    assertEquals(fetched, documents.fetches, "hidden selected files never enter whole-document fallback");
    assertNoEvidence("hidden", true);
    for (String outcome : List.of("RETRIEVAL_TIMEOUT", "RETRIEVAL_FAILED", "FETCH_FAILED")) {
      documents.outcome = outcome;
      String stream = dispatch(Map.of("shapeId", "core.rag-ask", "question", outcome, "conversationId", outcome));
      assertTrue(stream.contains(outcome), stream);
      var saved = coldHistory(outcome);
      assertEquals(2, saved.size(), "D5: the question and classified error survive cold reads");
      assertEquals(outcome, saved.getFirst().get("content"));
      assertEquals(outcome, saved.getLast().get("errorCode"));
      assertEquals(2, get("/api/thread/" + outcome).path("events").size());
    }
    stopEngine(); start(new EngineAdmissionController(4, 8, 1));
    assertNoEvidence("scoped", true);
    assertNoEvidence("hidden", true);
    for (String outcome : List.of("RETRIEVAL_TIMEOUT", "RETRIEVAL_FAILED", "FETCH_FAILED")) {
      var history = get("/api/chat/conversations/" + outcome + "/history").path("messages");
      assertEquals(2, history.size());
      assertEquals(outcome, history.get(1).path("errorCode").asString());
      assertEquals("ERROR", get("/api/thread/" + outcome).path("events").get(1).path("kind").asString());
    }
    assertEquals(0, ai.requests.size(), "retrieval errors never become model answers");
  }

  @Test
  void persistentModelUsesFloorExclusionsAndSummaryExactlyOnceAndAppendsOnlyTheNewExchange() throws Exception {
    documents.outcome = "ANSWER";
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "first-ask", "conversationId", "context"));
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "second-ask", "conversationId", "context"));
    documents.outcome = "NO_CONTENT";
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "missing-question", "conversationId", "context"));
    var before = coldHistory("context");
    assertEquals(6, before.size(), before.toString());
    ConversationStore store = conversationStore;
    store.compactContext("context", (String) before.get(2).get("id"), "earlier-summary");
    store.excludeMessage("context", (String) before.get(3).get("id"), true);
    dispatch(Map.of("shapeId", "core.free-chat", "prompt", "missing-question", "sessionId", "context"));
    var messages = ai.requests.getLast().messages();
    assertEquals(1, count(messages, "Summary of earlier conversation:\nearlier-summary"));
    assertEquals(0, count(messages, "first-ask"));
    assertEquals(1, count(messages, "second-ask"));
    assertEquals(2, count(messages, "missing-question"), "one eligible earlier question plus the new prompt, once each");
    assertEquals(0, count(messages, String.valueOf(before.get(3).get("content"))), "the excluded answer stays excluded");
    var after = coldHistory("context");
    assertEquals(8, after.size(), "D6: only the new question and answer were appended");
    assertEquals(before, after.subList(0, 6), "existing messages and ids stay unchanged");
    stopEngine(); start(new EngineAdmissionController(4, 8, 1));
    dispatch(Map.of("shapeId", "core.free-chat", "prompt", "another-model-question", "sessionId", "context"));
    var second = coldHistory("context");
    assertEquals(10, second.size()); assertEquals(after, second.subList(0, 8));
    assertEquals(1, count(ai.requests.getLast().messages(), "Summary of earlier conversation:\nearlier-summary"));
    // N10.9: a subsequent Ask still searches and answers normally.
    documents.outcome = "ANSWER";
    String followup = dispatch(Map.of("shapeId", "core.rag-ask", "question", "later-ask", "conversationId", "context"));
    assertTrue(followup.contains("event: done"), followup);
  }

  @Test
  void oversizedStoredHistoryLeavesAnHonestSavedModelErrorAndTheOriginalNoEvidenceOutcome() throws Exception {
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "original missing question", "conversationId", "overflow"));
    dispatch(Map.of("shapeId", "core.free-chat", "prompt", "large earlier context " + "word ".repeat(10_000), "sessionId", "overflow"));
    var before = coldHistory("overflow");
    assertEquals(4, before.size());
    Files.writeString(fakeHome.resolve("reject-context"), "context window exceeded");
    String failed = dispatch(Map.of("shapeId", "core.free-chat", "prompt", "try the model answer", "sessionId", "overflow"));
    assertTrue(failed.contains("LLM_ERROR"), failed);
    assertFalse(failed.contains("event: done"), failed);
    var after = coldHistory("overflow");
    assertEquals(6, after.size());
    assertEquals(before, after.subList(0, 4));
    assertEquals(true, after.getLast().get("terminalError"));
    assertEquals("LLM_ERROR", after.getLast().get("errorCode"));
    assertTrue(String.valueOf(after.getLast().get("content")).contains("context"), after.toString());
    stopEngine(); start(new EngineAdmissionController(4, 8, 1));
    assertEquals(after, coldHistory("overflow"));
    assertEquals("ERROR", get("/api/thread/overflow").path("events").get(5).path("kind").asString());
    Files.delete(fakeHome.resolve("reject-context"));
    assertTrue(dispatch(Map.of("shapeId", "core.free-chat", "prompt", "retry after failure", "sessionId", "overflow"))
        .contains("event: done"));
  }

  @Test
  void synchronousModelFailureKeepsTheExistingDispatchCodeAndSavesTheFailedAttempt() throws Exception {
    dispatch(Map.of("shapeId", "core.rag-ask", "question", "original missing question", "conversationId", "sync"));
    var before = coldHistory("sync");
    ai.synchronousError = "context window exceeded before streaming";
    String failed = dispatch(Map.of("shapeId", "core.free-chat", "prompt", "try the model", "sessionId", "sync"));
    assertTrue(failed.contains("BAD_REQUEST"), "keep the existing API adapter classification: " + failed);
    assertTrue(failed.contains(ai.synchronousError), failed);
    assertFalse(failed.contains("event: done"), failed);
    var saved = coldHistory("sync");
    assertEquals(4, saved.size(), "a synchronous failure still saves the question and its failed outcome");
    assertEquals(before, saved.subList(0, 2));
    assertEquals(true, saved.getLast().get("terminalError"));
    assertEquals("LLM_ERROR", saved.getLast().get("errorCode"), "the stored attempt names the failed model stage");
    assertEquals(ai.synchronousError, saved.getLast().get("content"));
    stopEngine(); start(new EngineAdmissionController(4, 8, 1));
    assertEquals(saved, coldHistory("sync"));
    assertEquals("ERROR", get("/api/thread/sync").path("events").get(3).path("kind").asString());
    ai.synchronousError = null;
    assertTrue(dispatch(Map.of("shapeId", "core.free-chat", "prompt", "retry the model", "sessionId", "sync"))
        .contains("event: done"));
    assertEquals(saved, coldHistory("sync").subList(0, 4));
  }

  private static long count(List<Map<String, Object>> messages, String text) {
    return messages.stream().filter(message -> text.equals(message.get("content"))).count();
  }

  private void assertNoEvidence(String id, boolean scoped) throws Exception {
    var saved = coldHistory(id);
    assertEquals("NO_CONTENT", saved.get(1).get("errorCode"));
    assertEquals(scoped, saved.get(1).get("retrievalScoped"));
    var error = get("/api/thread/" + id).path("events").get(1);
    assertEquals("ERROR", error.path("kind").asString());
    assertEquals(scoped, error.path("attributes").path("retrievalScoped").asBoolean(!scoped));
  }

  private List<Map<String, Object>> coldHistory(String id) {
    return new FileConversationStore(directory.resolve("conversations")).loadHistory(id);
  }

  private JsonNode get(String path) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return MAPPER.readTree(response.body());
  }

  private String dispatch(Map<String, Object> body) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/chat/dispatch"))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body()); return response.body();
  }

  private final class Documents implements DocumentService {
    volatile String outcome = "NO_CONTENT";
    volatile int fetches;
    @Override public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, EngineContext context) {
      retrievals++;
      if (outcome.equals("RETRIEVAL_TIMEOUT")) return CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException());
      if (outcome.equals("RETRIEVAL_FAILED")) return CompletableFuture.failedFuture(new IllegalStateException("retrieval failed"));
      boolean answer = outcome.equals("ANSWER") || outcome.equals("CHECKING");
      var citations = answer ? List.of(new ContextCitation("notes.md", 0, 1, 0, 15, 0.9f,
          "private context", 1, 1, "", 0, ContextInclusion.ABSENT)) : List.<ContextCitation>of();
      return CompletableFuture.completedFuture(new ContextResult(answer ? "private context" : "", answer ? 1 : 0,
          answer ? 1 : 0, 0, citations, "BM25", outcome.equals("FETCH_FAILED") ? "FALLBACK_FAILED" : outcome.equals("DEGRADED") ? "SEMANTIC_BUDGET_EXCEEDED" : "BM25_CONFIGURED", false, answer ? List.of(new ContextSection("notes.md", "private context", false, 0, 0)) : List.of()));
    }
    @Override public CompletionStage<DocumentRecord> fetch(String id, EngineContext context) {
      fetches++;
      return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletionStage<CitationMatchResult> matchCitationsAgainst(String answer, List<VerificationSource> sources,
        double threshold, EngineContext context) {
      if (outcome.equals("CHECKING")) {
        try { Files.writeString(directory.resolve("checking"), answer); }
        catch (java.io.IOException error) { return CompletableFuture.failedFuture(error); }
        // Private process-loss probe: the owned Engine process is killed during this real consumer call.
        return new CompletableFuture<>();
      }
      return DocumentService.super.matchCitationsAgainst(answer, sources, threshold, context);
    }
  }

  private static final class CapturingAi implements OnlineAiService {
    final OnlineAiService delegate;
    final List<StreamRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile String synchronousError;
    CapturingAi(OnlineAiService delegate) { this.delegate = delegate; }
    @Override public boolean isAvailable() { return delegate.isAvailable(); }
    @Override public boolean isStartingUp() { return delegate.isStartingUp(); }
    @Override public CompletableFuture<String> summarize(String content) { return delegate.summarize(content); }
    @Override public CompletableFuture<String> askQuestion(String question, String context) { return delegate.askQuestion(question, context); }
    @Override public Integer llmContextTokens() { return delegate.llmContextTokens(); }
    @Override public Integer configuredContextTokens() { return delegate.configuredContextTokens(); }
    @Override public void stream(StreamRequest request, StreamSink sink) {
      requests.add(request);
      String failure = synchronousError;
      if (failure != null) throw new IllegalStateException(failure);
      delegate.stream(request, sink);
    }
  }
}
