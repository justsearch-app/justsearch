/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.SearchTrace;
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
import io.justsearch.ort.SessionAcquireDeadlineExceededException;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifier-3c finding 2 (S2: the reason a search carries is the true one): a rerank that gets no
 * reranker session within its own budget, because the device stayed busy with other inference until
 * the acquisition deadline, is a budget skip, {@code DEADLINE_EXCEEDED}, not a failed rerank call
 * ({@code RPC_FAILED}).
 *
 * <p>The harness is the one of {@code HeadRerankDeadlineInProcessTest}: the production {@code
 * KnowledgeHttpApiAdapter} over the in-process client {@link EngineRoot} composes, so the rerank goes
 * through the head's rerank stage, the in-process rerank RPC and {@code WorkerSearchService.rerank},
 * down to a real {@link CrossEncoderReranker}. Only the native layer is a test double: the session
 * handle's acquire waits until the acquisition's deadline and then misses it at the gate, as {@code
 * NativeSessionHandle} does behind another run.
 */
@Timeout(240)
final class HeadRerankAcquireBudgetInProcessTest {

  /** The request deadline D: the client's base deadline, as {@code requestDeadlineMs}. */
  private static final long REQUEST_DEADLINE_MS = 5_000L;

  private EngineRoot root;
  private KnowledgeClient client;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
  }

  @Test
  void aRerankThatGetsNoSessionWithinItsBudgetIsABudgetSkipNotAFailedCall(
      @TempDir Path tempDir) throws Exception {
    KnowledgeServer server = bootIndexed(tempDir);
    KnowledgeHttpApiAdapter head = withReranker(tempDir);

    AtomicInteger acquires = new AtomicInteger();
    try (var view = server.captureServingView()) {
      view.services().searchService().setSearchReranker(contendedReranker(acquires));
      try {
        long startNs = System.nanoTime();
        KnowledgeSearchResponse response =
            head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertEquals(1, acquires.get(), "the rerank waited for a session");
        assertTrue(elapsedMs <= REQUEST_DEADLINE_MS, "answered within D; took " + elapsedMs);
        assertTrue(response.results().stream()
                .anyMatch(hit -> hit.id().contains("rerank-acquire-probe")),
            "the search answers with its fused results: " + response.results());
        assertNotNull(response.searchTrace(), "the search carries its trace");
        SearchTrace.TraceStage crossEncoder = response.searchTrace().stages().stream()
            .filter(stage -> "cross-encoder".equals(stage.id().wireId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no cross-encoder stage in the trace"));
        assertEquals(SearchTrace.StageStatus.SKIPPED, crossEncoder.status());
        assertEquals("DEADLINE_EXCEEDED", crossEncoder.reason(),
            "a session wait that outlasts the rerank budget is the budget's skip");
      } finally {
        awaitNoLiveEngineWork(root);
      }
    }
  }

  /**
   * A real cross-encoder whose device stays busy: the session acquisition waits until its deadline
   * and then misses it at the gate.
   */
  private static CrossEncoderReranker contendedReranker(AtomicInteger acquires) throws Exception {
    SessionHandle sessions = mock(SessionHandle.class);
    when(sessions.environment()).thenReturn(OrtEnvironment.getEnvironment());
    when(sessions.acquire(any())).thenAnswer(invocation -> {
      acquires.incrementAndGet();
      SessionAcquisitionRequest request = invocation.getArgument(0);
      long waitNs = request.deadlineNanos() - System.nanoTime();
      if (waitNs > 0) TimeUnit.NANOSECONDS.sleep(waitNs);
      throw new SessionAcquireDeadlineExceededException(
          "the device stayed busy until the acquisition deadline",
          SessionAcquireDeadlineExceededException.Phase.GATE);
    });
    RerankerTokenizer tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(
            new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1));
    return new CrossEncoderReranker(sessions, new RerankerShape(8, false), tokenizer);
  }

  /** Boots the in-process engine without a reranker and indexes the probe document. */
  private KnowledgeServer bootIndexed(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    // An empty models directory: the worker boots with no reranker of its own.
    Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
    Map<String, String> boot = Map.of("justsearch.models.dir", modelsDir.toString());
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), boot);
    Path doc = tempDir.resolve("rerank-acquire-probe.txt");
    Files.writeString(doc, "quokka telemetry probe for the rerank acquisition test");

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
        io.justsearch.core.context.EngineContext.ClientKind.MCP_CLIENT, "rerank-acquire-test",
        java.util.Optional.of("mcp-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        io.justsearch.core.context.EngineContext.Survival.DURABLE,
        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    assertTrue(client.submitBatch(List.of(doc), admission).getAcceptedCount() > 0);
    awaitFindable(client);
    // Warm the whole path once, before the head sees a reranker.
    headOver(client).search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
    this.client = client;
    return built[0];
  }

  /** Publishes a configured reranker to the head (one hit is enough) and returns a fresh head. */
  private KnowledgeHttpApiAdapter withReranker(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path rerankerDir = Files.createDirectories(tempDir.resolve("reranker"));
    Files.writeString(rerankerDir.resolve("model.onnx"), "stand-in: the session is a test double");
    Files.writeString(rerankerDir.resolve("tokenizer.json"), "{}");
    Map<String, String> config = new java.util.HashMap<>();
    config.put("justsearch.models.dir", tempDir.resolve("models").toString());
    config.put("justsearch.rerank.enabled", "true");
    config.put("justsearch.rerank.model_path", rerankerDir.toString());
    config.put("justsearch.rerank.min_hits", "1");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), config);
    return headOver(client);
  }

  private static void awaitNoLiveEngineWork(EngineRoot root) throws Exception {
    var field = EngineRoot.class.getDeclaredField("admission");
    field.setAccessible(true);
    EngineAdmissionController admission = (EngineAdmissionController) field.get(root);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (admission.activeWorkCount() != 0 && System.nanoTime() < until) {
      Thread.sleep(20);
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
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);
    return new KnowledgeHttpApiAdapter(bootstrap, mock(SearchPerSourceExecutor.class), ai, null,
        ConfigStore.global());
  }

  /** Sparse plus dense with the cross-encoder, no expansion: a hybrid search that reranks. */
  private static KnowledgeSearchRequest hybridWithCrossEncoder() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true, new PipelineConfig(true, true, false, "rrf", false, true, 0, false,
            false));
  }
}
