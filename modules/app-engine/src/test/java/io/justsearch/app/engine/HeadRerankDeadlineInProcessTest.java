/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
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
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec D §1.2, scenario S1 (rerank half): the rerank stage runs inside the request's one deadline
 * {@code D}. A hybrid search whose reranker's native call stalls still answers within {@code D},
 * with its fused (unreranked) results, and its {@code cross-encoder} trace stage names the cause
 * {@code SEARCH_DEADLINE_EXCEEDED}.
 *
 * <p>The head is the production {@code KnowledgeHttpApiAdapter} over the in-process client that
 * {@link EngineRoot} composes (the {@code HeadSearchDeadlineInProcessTest} harness), so the rerank
 * goes through the real search service seam: the head's rerank stage, the in-process rerank RPC and
 * the worker's {@code WorkerSearchService.rerank}, down to a real {@link CrossEncoderReranker}. Only
 * the native layer is a test double: the reranker's ONNX session is a mock whose {@code run} blocks
 * until the test lets it return, as a stalled native call does.
 */
@Timeout(240)
final class HeadRerankDeadlineInProcessTest {

  /** The request deadline D: the client's base deadline, as {@code requestDeadlineMs}. */
  private static final long REQUEST_DEADLINE_MS = 5_000L;

  /** How long the stalled native call stays stalled unless the test releases it first. */
  private static final long STALL_MS = 120_000L;

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
  void hybridSearchWithAStalledRerankerAnswersWithinTheDeadlineWithFusedResults(
      @TempDir Path tempDir) throws Exception {
    KnowledgeServer server = bootIndexed(tempDir);
    KnowledgeHttpApiAdapter head = withReranker(tempDir);

    CountDownLatch mayReturn = new CountDownLatch(1);
    AtomicInteger nativeRuns = new AtomicInteger();
    try (var view = server.captureServingView()) {
      view.services().searchService().setSearchReranker(
          stallingReranker(mayReturn, nativeRuns));
      try {
        long startNs = System.nanoTime();
        KnowledgeSearchResponse response =
            head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertEquals(1, nativeRuns.get(), "the rerank reached the reranker's native call");
        assertAnsweredInTimeWithFusedResults(response, elapsedMs);
      } finally {
        mayReturn.countDown();
        // The worker's rerank call outlives the search it was released from; let it unwind
        // before the engine is closed.
        awaitNoLiveEngineWork(root);
      }
    }
  }

  /**
   * The worker's rerank RPC first waits for the models-ready latch (once for up to 120 s, now until
   * the call's deadline). A search request reaches that wait whenever its cross-encoder runs while
   * models are still loading; the search itself must still answer within {@code D}.
   */
  @Test
  void hybridSearchWhoseRerankWaitsForLoadingModelsAnswersWithinTheDeadline(
      @TempDir Path tempDir) throws Exception {
    KnowledgeServer server = bootIndexed(tempDir);
    KnowledgeHttpApiAdapter head = withReranker(tempDir);

    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch ready = new CountDownLatch(0);
    AtomicInteger rerankWaits = new AtomicInteger();
    AtomicInteger nativeRuns = new AtomicInteger();
    try (var view = server.captureServingView()) {
      var search = view.services().searchService();
      search.setSearchReranker(stallingReranker(ready, nativeRuns));
      // Models are still loading for the rerank RPC only: the base search finds them ready.
      search.setModelReadyLatchSupplier(() -> {
        if (!calledFromWorkerRerank()) return ready;
        rerankWaits.incrementAndGet();
        return loading;
      });
      try {
        long startNs = System.nanoTime();
        KnowledgeSearchResponse response =
            head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertEquals(1, rerankWaits.get(), "the rerank RPC waited for the loading models");
        assertEquals(0, nativeRuns.get(), "no rerank ran while the models were loading");
        assertAnsweredInTimeWithFusedResults(response, elapsedMs);
      } finally {
        loading.countDown();
        awaitNoLiveEngineWork(root);
      }
    }
  }

  private static boolean calledFromWorkerRerank() {
    return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
        frame.getClassName().endsWith(".WorkerSearchService")
            && frame.getMethodName().equals("rerank")));
  }

  private static void assertAnsweredInTimeWithFusedResults(
      KnowledgeSearchResponse response, long elapsedMs) {
    assertTrue(elapsedMs <= REQUEST_DEADLINE_MS,
        "a search whose rerank does not return must answer within D = " + REQUEST_DEADLINE_MS
            + " ms; took " + elapsedMs + " ms");
    assertTrue(response.results().stream()
            .anyMatch(hit -> hit.id().contains("rerank-deadline-probe")),
        "the search answers with its fused results: " + response.results());
    assertNotNull(response.searchTrace(), "the search carries its trace");
    SearchTrace.TraceStage crossEncoder = response.searchTrace().stages().stream()
        .filter(stage -> "cross-encoder".equals(stage.id().wireId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cross-encoder stage in the trace"));
    assertEquals(SearchTrace.StageStatus.SKIPPED, crossEncoder.status());
    // A string, not the enum member: the cause is named on the wire the trace carries.
    assertEquals("SEARCH_DEADLINE_EXCEEDED", crossEncoder.reason(),
        "the trace says the search's deadline cut the rerank short");
  }

  /** Boots the in-process engine without a reranker and indexes the probe document. */
  private KnowledgeServer bootIndexed(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    // An empty models directory: the worker boots with no reranker of its own.
    Path modelsDir = Files.createDirectories(tempDir.resolve("models"));
    Map<String, String> boot = Map.of("justsearch.models.dir", modelsDir.toString());
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), boot);
    Path doc = tempDir.resolve("rerank-deadline-probe.txt");
    Files.writeString(doc, "quokka telemetry probe for the rerank deadline test");

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
        io.justsearch.core.context.EngineContext.ClientKind.MCP_CLIENT, "rerank-deadline-test",
        java.util.Optional.of("mcp-session"), java.util.Optional.empty(), "UNTRUSTED", "MCP",
        io.justsearch.core.context.EngineContext.Survival.DURABLE,
        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    assertTrue(client.submitBatch(List.of(doc), admission).getAcceptedCount() > 0);
    awaitFindable(client);
    // Warm the whole path once, before the head sees a reranker, so the measured search is quick
    // up to its rerank stage.
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

  /**
   * A real cross-encoder whose ONNX session is a test double: tokenization and tensor preparation
   * run as in production, then the native {@code run} blocks until {@code mayReturn} opens.
   */
  private static CrossEncoderReranker stallingReranker(
      CountDownLatch mayReturn, AtomicInteger nativeRuns) throws Exception {
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> {
      nativeRuns.incrementAndGet();
      mayReturn.await(STALL_MS, TimeUnit.MILLISECONDS);
      throw new ai.onnxruntime.OrtException("stalled native run released by the test");
    });
    SessionHandle sessions = mock(SessionHandle.class);
    when(sessions.environment()).thenReturn(OrtEnvironment.getEnvironment());
    when(sessions.acquire(any())).thenAnswer(invocation -> new SessionHandle.Lease(
        session, null, () -> { }, true, null,
        (SessionAcquisitionRequest) invocation.getArgument(0)));
    RerankerTokenizer tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(
            new long[][] {{1}}, new long[][] {{1}}, new long[][] {{0}}, 1, 1, 0, 1));
    return new CrossEncoderReranker(sessions, new RerankerShape(8, false), tokenizer);
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
