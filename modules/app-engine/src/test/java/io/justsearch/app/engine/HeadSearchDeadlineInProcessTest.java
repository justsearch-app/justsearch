/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.2 and §1.6, test H1 (wall-time half): one deadline {@code D} spans every worker call
 * the head makes for a search, so an expansion re-search whose semantic work stalls cannot carry the
 * request past {@code D}; it gets {@code min(EXPANSION_BUDGET_MS, D - now)} instead of a fresh
 * category budget.
 *
 * <p>The head is the production {@code KnowledgeHttpApiAdapter} over the in-process client that
 * {@link EngineRoot} composes (the {@code EngineRootInProcessPortsTest} harness). The stall is the
 * worker's models-ready latch, held closed for the re-search only: the base search finds its models
 * ready, the re-search finds them still loading. This index has no embedding model, so the dense
 * leg is refused by index compatibility before any budget applies; the rule that keeps the base
 * results when the re-search loses a semantic leg is covered at the head seam by
 * {@code KnowledgeSearchEngineHeadDeadlineTest}.
 */
@Timeout(180)
final class HeadSearchDeadlineInProcessTest {

  /** The request deadline D: the client's base deadline, as {@code requestDeadlineMs}. */
  private static final long REQUEST_DEADLINE_MS = 5_000L;

  private EngineRoot root;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
  }

  @Test
  void expansionReSearchWithStalledModelsStaysWithinTheDeadline(
      @TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    // An empty models directory: no reranker, so only the base search and the re-search run.
    Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"),
        Map.of("justsearch.models.dir", modelsDir.toString()));
    Path doc = tempDir.resolve("head-deadline-probe.txt");
    Files.writeString(doc, "quokka telemetry probe for the head deadline test");

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
    var admission = new io.justsearch.core.context.EngineContext(
        io.justsearch.core.context.EngineContext.ClientKind.MCP_CLIENT, "head-deadline-test",
        java.util.Optional.of("mcp-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        io.justsearch.core.context.EngineContext.Survival.DURABLE,
        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    assertTrue(client.submitBatch(List.of(doc), admission).getAcceptedCount() > 0);
    awaitFindable(client);

    KnowledgeHttpApiAdapter head = headOver(client);
    // Warm the whole path once with the models ready, so the measured base search is quick.
    head.search(expandedHybrid(), TestEngineContexts.FOREGROUND);

    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch ready = new CountDownLatch(0);
    AtomicInteger modelSearches = new AtomicInteger();
    try (var view = built[0].captureServingView()) {
      view.services().searchService().setModelReadyLatchSupplier(
          () -> modelSearches.incrementAndGet() == 1 ? ready : loading);
      try {
        long startNs = System.nanoTime();
        KnowledgeSearchResponse response =
            head.search(expandedHybrid(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertTrue(elapsedMs <= REQUEST_DEADLINE_MS,
            "base search plus expansion re-search must answer within D = " + REQUEST_DEADLINE_MS
                + " ms; took " + elapsedMs + " ms");
        assertEquals(2, modelSearches.get(), "the base search and the expansion re-search ran");
        assertTrue(response.results().stream()
                .anyMatch(hit -> hit.id().contains("head-deadline-probe")),
            "the search still answers with its keyword results: " + response.results());
      } finally {
        loading.countDown();
      }
    }
  }

  private static void awaitFindable(KnowledgeClient client) throws InterruptedException {
    long until = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < until) {
      SearchResponse found =
          client.search("quokka", 10, PipelineConfigs.TEXT, TestEngineContexts.FOREGROUND);
      if (found.getResultsCount() > 0) return;
      Thread.sleep(250);
    }
    throw new AssertionError("the submitted document never became findable");
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeHttpApiAdapter headOver(KnowledgeClient client) {
    KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(ConfigStore.global().publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((Function<KnowledgeClient, ?>) invocation.getArgument(0)).apply(client));
    // The expansion model answers at once with a valid variant, so the re-search always runs.
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    when(ai.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("quokkas"));
    return new KnowledgeHttpApiAdapter(bootstrap, mock(SearchPerSourceExecutor.class), ai, null,
        ConfigStore.global());
  }

  /** Sparse plus dense, expansion on, no rerankers: a SIMPLE query that expands. */
  private static KnowledgeSearchRequest expandedHybrid() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true, new PipelineConfig(true, true, false, "rrf", false, false, 0, true, false));
  }
}
