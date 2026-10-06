/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ui.api.TestRequestContexts;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D (item 5a) R1, the multi-step answer: an open answer (no document ids) runs a pre-search
 * and then a context retrieval. While the search models are loading, both steps must share the
 * one request deadline the head fixed for the answer, so the answer returns inside its limit with
 * keyword context, the reason {@code SEMANTIC_BUDGET_EXCEEDED} and the cause {@code loading} (S1,
 * S2). Before, each step waited out its own semantic sub-budget (8 s CPU cap each), and the MCP
 * answer timed out at 15 s with neither.
 *
 * <p>Real seams: the production {@code justsearch_answer} tool ({@link McpToolSurface#callTool})
 * and the production chat {@link RAGContext} injector, each over the production {@link
 * RemoteDocumentService} over the in-process client and worker that {@link EngineRoot} composes,
 * with the production request deadline (15 000 ms). The stall is the worker's models-ready latch,
 * held closed: the models are still loading. This harness has no embedding model, so the worker's
 * embedding-compatibility verdict is cleared, which is the state of a compatible index; otherwise
 * the dense leg would be refused by compatibility before any budget applies. The worker search
 * service is reached through the root's index half by reflection, because the frontend module may
 * not depend on the index half at compile time.
 *
 * <p>Uses only API that exists before the change, so each check runs on the before-state.
 */
@Timeout(300)
final class McpAnswerModelsLoadingInProcessTest {

  /** The production request deadline D ({@code KnowledgeServerConfig.DEFAULT_DEADLINE_MS}). */
  private static final long REQUEST_DEADLINE_MS = 15_000L;

  /** The MCP answer's own limit ({@code McpToolSurface.RETRIEVE_TIMEOUT_MS}). */
  private static final Duration ANSWER_LIMIT = Duration.ofSeconds(15);

  /**
   * The pre-search's semantic sub-budget while the query session is not warm ({@code
   * SearchBudgetPolicy} CPU cap), plus 3 s for the keyword retrieval and the answer around it. An
   * answer later than this waited for the loading models a second time, in its retrieval step.
   */
  private static final long ONE_MODELS_WAIT_MS = 8_000L + 3_000L;

  private static final String QUESTION = "What does the quokka probe answer?";

  @TempDir Path tempDir;

  private final CountDownLatch loading = new CountDownLatch(1);
  private final List<AutoCloseable> leases = new ArrayList<>();
  private ConfigStore previousConfig;
  private EngineRoot root;
  private ExecutorService executor;

  @AfterEach
  void tearDown() throws Exception {
    loading.countDown();
    for (AutoCloseable lease : leases) lease.close();
    leases.clear();
    if (root != null) root.close();
    if (executor != null) executor.shutdownNow();
    TestResolvedConfigHelper.restoreGlobal(previousConfig);
  }

  @Test
  void anMcpAnswerWhileModelsLoadReturnsKeywordEvidenceWithTheReasonInsideItsLimit()
      throws Exception {
    RemoteDocumentService documents = startEngineWithModelsLoading();
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(new WorkerServices(null, documents, null, null));
    McpAnswerCaptureFixture.bind(facade, documents);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            Clock.systemUTC());

    long start = System.nanoTime();
    Map<String, Object> result =
        assertTimeoutPreemptively(
            ANSWER_LIMIT.plusSeconds(15),
            () ->
                surface.callTool(
                    "justsearch_answer", Map.of("query", QUESTION), "s1",
                    TestRequestContexts.mcp("s1")));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertEquals(Boolean.FALSE, result.get("isError"),
        () -> "the answer failed after " + elapsedMs + " ms: " + result.get("content"));
    assertTrue(elapsedMs < ANSWER_LIMIT.toMillis(),
        () -> "the answer took " + elapsedMs + " ms, past its limit");
    @SuppressWarnings("unchecked")
    Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
    @SuppressWarnings("unchecked")
    Map<String, Object> quality = (Map<String, Object>) structured.get("quality");
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", quality.get("retrievalModeReason"),
        () -> "quality: " + quality);
    assertEquals("loading", quality.get("retrievalModeCause"), () -> "quality: " + quality);
    assertEquals("BM25", quality.get("retrievalMode"), () -> "quality: " + quality);
    assertTrue(((Number) quality.get("chunksUsed")).intValue() >= 1,
        () -> "keyword context was used: " + quality);
    assertTrue(elapsedMs < ONE_MODELS_WAIT_MS,
        () -> "the answer took " + elapsedMs + " ms: its retrieval waited for the models again");
  }

  @Test
  void aChatTurnWithoutDocumentsWhileModelsLoadAnswersInsideTheRequestDeadline()
      throws Exception {
    RemoteDocumentService documents = startEngineWithModelsLoading();
    // The production chat injector with its production retrieval wait (20 s).
    RAGContext injector = new RAGContext(documents);
    ConversationContext turn = turn(Map.of("question", QUESTION));

    long start = System.nanoTime();
    InjectorResult result =
        assertTimeoutPreemptively(ANSWER_LIMIT.plusSeconds(20), () -> injector.inject(turn));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertFalse(result.terminalError().isPresent(),
        () -> "the turn must not end in an error: " + result.terminalError());
    assertTrue(elapsedMs < REQUEST_DEADLINE_MS,
        () -> "the retrieval took " + elapsedMs + " ms, past the request deadline");
    var meta =
        result.events().stream()
            .filter(event -> event.name().equals("rag.meta"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no rag.meta event: " + result.events()));
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", meta.payload().get("retrieval_mode_reason"),
        () -> "rag.meta: " + meta.payload());
    assertEquals("loading", meta.payload().get("retrieval_mode_cause"),
        () -> "rag.meta: " + meta.payload());
    assertEquals("BM25", meta.payload().get("retrieval_mode"));
    assertTrue(((Number) meta.payload().get("chunks_used")).intValue() >= 1,
        () -> "BM25 context was used: " + meta.payload());
    assertTrue(elapsedMs < ONE_MODELS_WAIT_MS,
        () -> "the turn took " + elapsedMs + " ms: its retrieval waited for the models again");
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------

  /**
   * Starts the in-process engine over an index holding one findable document, then holds the
   * worker's models-ready latch closed.
   */
  private RemoteDocumentService startEngineWithModelsLoading() throws Exception {
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path indexDir = Files.createDirectories(dataDir.resolve("index"));
    Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
    previousConfig = ConfigStore.globalOrNull();
    ConfigStore store =
        new ConfigStore(
            new ResolvedConfigBuilder()
                .contributeBaseSources()
                .put("justsearch.ssot.path", ResolvedConfigBuilder.ORDINAL_JVM_ARG,
                    "test_fixture", "isolated_ssot",
                    dataDir.resolve("SSOT").toAbsolutePath().toString())
                .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
                .putDefault("justsearch.index.base_path", indexDir.toAbsolutePath().toString())
                .putDefault("justsearch.models.dir", modelsDir.toAbsolutePath().toString())
                .build());
    ConfigStore.setGlobal(store);

    Path doc = tempDir.resolve("answer-budget-probe.txt");
    Files.writeString(doc, "The quokka telemetry probe answers the retrieval budget question.");

    root =
        EngineRoot.forProcess(
            mock(io.justsearch.app.api.operations.OperationStore.class),
            mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
            REQUEST_DEADLINE_MS,
            5_000,
            code -> {},
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
            () -> {},
            OperationAuthority.inMemory(),
            store);
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    var admission =
        new EngineContext(
            EngineContext.ClientKind.MCP_CLIENT, "answer-budget-test",
            java.util.Optional.of("mcp-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
            EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    assertTrue(client.submitBatch(List.of(doc), admission).getAcceptedCount() > 0);
    awaitFindable(client);

    WorkerSearchService searchService = searchService(root);
    searchService.setEmbeddingCompatController(null);
    searchService.setModelReadyLatchSupplier(() -> loading);

    executor = Executors.newVirtualThreadPerTaskExecutor();
    return new RemoteDocumentService(executor, executor, () -> client);
  }

  /** The worker search service of the root's serving view, held for the test. */
  private WorkerSearchService searchService(EngineRoot engine) throws Exception {
    Field serverField = EngineRoot.class.getDeclaredField("server");
    serverField.setAccessible(true);
    Object server = serverField.get(engine);
    Object lease = server.getClass().getMethod("captureServingView").invoke(server);
    leases.add((AutoCloseable) lease);
    WorkerAppServices services =
        (WorkerAppServices) lease.getClass().getMethod("services").invoke(lease);
    return services.searchService();
  }

  private static void awaitFindable(KnowledgeClient client) throws InterruptedException {
    long until = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < until) {
      SearchResponse found =
          client.search("quokka", 10, PipelineConfigs.TEXT, TestRequestContexts.browser());
      if (found.getResultsCount() > 0) return;
      Thread.sleep(250);
    }
    throw new AssertionError("the submitted document never became findable");
  }

  private static ConversationContext turn(Map<String, Object> body) {
    Map<String, Object> requestBody = new LinkedHashMap<>(body);
    Map<String, Object> attributes = new HashMap<>();
    return new ConversationContext() {
      @Override
      public EngineContext engineContext() {
        return TestRequestContexts.browser();
      }

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return requestBody;
      }

      @Override
      public Map<String, Object> attributes() {
        return attributes;
      }
    };
  }
}
