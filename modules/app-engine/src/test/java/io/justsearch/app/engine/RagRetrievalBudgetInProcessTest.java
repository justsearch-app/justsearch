/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Spec D (item 5a) test R2: a chat turn's RAG retrieval whose query embedding cannot answer inside
 * its semantic sub-budget still gives the turn BM25 context, and {@code rag.meta} says why, instead
 * of the retrieval failing (S1, S2: the reason reaches the RAG metadata).
 *
 * <p>Real seam: the production {@link RAGContext} injector over the production {@link
 * RemoteDocumentService}, over the in-process client and worker that {@link EngineRoot} composes
 * (the {@code HeadSearchDeadlineInProcessTest} harness). The stall is the worker's models-ready
 * latch, held closed: the models are still loading. This harness has no embedding model, so the
 * worker's embedding-compatibility verdict is cleared, which is the state of a compatible index;
 * otherwise the dense leg would be refused by compatibility before any budget applies.
 *
 * <p>Uses only API that exists before the change, so the check runs on the before-state.
 */
@Timeout(240)
final class RagRetrievalBudgetInProcessTest {

  /** The client's base deadline; the RAG retrieval's request deadline is a multiple of it. */
  private static final long REQUEST_DEADLINE_MS = 5_000L;

  /** How long the injector waits for the retrieval, as a chat turn would. */
  private static final Duration RETRIEVAL_TIMEOUT = Duration.ofSeconds(15);

  private EngineRoot root;
  private ExecutorService executor;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
    if (executor != null) executor.shutdownNow();
  }

  @Test
  void ragTurnWithModelsLoadingGetsBm25ContextAndTheReason(@TempDir Path tempDir)
      throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"),
        Map.of("justsearch.models.dir", modelsDir.toString()));
    Path doc = tempDir.resolve("rag-budget-probe.txt");
    Files.writeString(doc, "The quokka telemetry probe answers the retrieval budget question.");

    KnowledgeServer[] built = new KnowledgeServer[1];
    root = new EngineRoot(mock(io.justsearch.app.api.operations.OperationStore.class),
        mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        (g, executors, ingestion, indexComponent, encoderComponent) -> {
          built[0] = new KnowledgeServer(executors, WorkerConfig.load(),
              new InProcessWorkerSignalBus(g),
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, indexComponent,
              encoderComponent);
          return built[0];
        },
        REQUEST_DEADLINE_MS, 5_000);
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    var admission = new EngineContext(
        EngineContext.ClientKind.MCP_CLIENT, "rag-budget-test",
        java.util.Optional.of("mcp-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    assertTrue(client.submitBatch(List.of(doc), admission).getAcceptedCount() > 0);
    String docId = awaitFindable(client);

    executor = Executors.newVirtualThreadPerTaskExecutor();
    var documents = new RemoteDocumentService(executor, executor, () -> client);
    var injector = new RAGContext(documents, RETRIEVAL_TIMEOUT);

    CountDownLatch loading = new CountDownLatch(1);
    try (var view = built[0].captureServingView()) {
      var searchService = view.services().searchService();
      searchService.setEmbeddingCompatController(null);
      searchService.setModelReadyLatchSupplier(() -> loading);
      try {
        ConversationContext turn =
            turn(Map.of("question", "What does the quokka probe answer?", "docIds",
                List.of(docId)));
        InjectorResult result =
            assertTimeoutPreemptively(RETRIEVAL_TIMEOUT.plusSeconds(15),
                () -> injector.inject(turn));

        assertFalse(result.terminalError().isPresent(),
            () -> "the turn must not end in an error: " + result.terminalError());
        var meta = result.events().stream()
            .filter(event -> event.name().equals("rag.meta"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no rag.meta event: " + result.events()));
        assertEquals("SEMANTIC_BUDGET_EXCEEDED", meta.payload().get("retrieval_mode_reason"),
            () -> "rag.meta: " + meta.payload());
        assertEquals("BM25", meta.payload().get("retrieval_mode"));
        assertTrue(((Number) meta.payload().get("chunks_used")).intValue() >= 1,
            () -> "BM25 context was used: " + meta.payload());
      } finally {
        loading.countDown();
      }
    }
  }

  private static String awaitFindable(KnowledgeClient client) throws InterruptedException {
    long until = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < until) {
      SearchResponse found =
          client.search("quokka", 10, PipelineConfigs.TEXT, TestEngineContexts.FOREGROUND);
      if (found.getResultsCount() > 0) return found.getResults(0).getId();
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
        return TestEngineContexts.FOREGROUND;
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
