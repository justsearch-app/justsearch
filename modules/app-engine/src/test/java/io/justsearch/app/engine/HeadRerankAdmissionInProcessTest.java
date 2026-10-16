/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.EngineWorkHandle;
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
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.Composition;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scenario P2 under concurrent CPU-only hybrid load (verify d14b, finding F1): a rerank the head
 * cuts at the search's deadline must not keep holding the engine while its native run goes on.
 *
 * <ul>
 *   <li>When the head answers a search whose rerank it cut, the search leaves no engine admission
 *       behind: a keyword search made right after is admitted even when that slot is the engine's
 *       last.
 *   <li>When every one of the reranker's own run threads holds a run that earlier searches left
 *       behind, the next search skips its rerank at once, with {@code SEARCH_DEADLINE_EXCEEDED},
 *       instead of queueing for the deadline.
 * </ul>
 *
 * <p>The head is the production {@code KnowledgeHttpApiAdapter} over the in-process client that
 * {@link EngineRoot} composes, so each search goes through the real engine admission, the head's
 * rerank stage, the in-process rerank RPC and the worker's {@code WorkerSearchService.rerank}, down
 * to a real {@link CrossEncoderReranker} over a real native session handle built by {@link
 * OrtSessionAssembler}. Only the ONNX session underneath is a test double, whose {@code run}
 * blocks until the test lets it return, as a stalled native call does.
 */
@Timeout(240)
final class HeadRerankAdmissionInProcessTest {

  /** The request deadline D: the client's base deadline, as {@code requestDeadlineMs}. */
  private static final long REQUEST_DEADLINE_MS = 5_000L;

  /** How long a stalled native call stays stalled unless the test releases it first. */
  private static final long STALL_MS = 120_000L;

  /**
   * A search whose rerank is skipped at once answers well inside the time a rerank that queues
   * for its deadline would take (D less the head's 500 ms respond margin).
   */
  private static final long AT_ONCE_MS = 2_000L;

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
  void aSearchWhoseRerankWasCutLeavesNoEngineAdmissionBehind(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = bootIndexed(tempDir);
    KnowledgeHttpApiAdapter head = withReranker(tempDir);
    EngineAdmissionController admission = admission(root);

    CountDownLatch mayReturn = new CountDownLatch(1);
    AtomicInteger nativeRuns = new AtomicInteger();
    SessionHandle sessions = stallingRerankerSessions(tempDir, mayReturn, nativeRuns);
    List<EngineWorkHandle> otherWork = new ArrayList<>();
    try (var view = server.captureServingView()) {
      view.services().searchService().setSearchReranker(reranker(sessions));
      try {
        awaitNoLiveEngineWork(admission);
        // Other clients' work in flight holds every admission slot but one: the searches below
        // run in the engine's last slot.
        int others = admission.limits().aggregateLimit() - 1;
        for (int i = 0; i < others; i++) otherWork.add(admission.admit(otherClient(i), false));

        long startNs = System.nanoTime();
        KnowledgeSearchResponse hybrid =
            head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        assertEquals(1, nativeRuns.get(), "the rerank reached the reranker's native call");
        assertRerankSkippedForTheDeadline(hybrid);
        assertTrue(elapsedMs <= REQUEST_DEADLINE_MS,
            "the search answers within D = " + REQUEST_DEADLINE_MS + " ms; took " + elapsedMs);

        // The rerank's native run has not returned, and must not keep the search's slot.
        KnowledgeSearchResponse keyword;
        try {
          keyword = head.search(keywordOnly(), TestEngineContexts.FOREGROUND);
        } catch (RuntimeException refused) {
          throw new AssertionError("a keyword search right after the cut search was refused while"
              + " the cut rerank's native run is still running (live engine work "
              + admission.activeWorkCount() + " of " + admission.limits().aggregateLimit() + "): "
              + refused, refused);
        }
        assertTrue(keyword.results().stream()
                .anyMatch(hit -> hit.id().contains("rerank-admission-probe")),
            "the keyword search is admitted and answers: " + keyword.results());
        assertEquals(1, nativeRuns.get(), "the keyword search ran no rerank");
      } finally {
        otherWork.forEach(EngineWorkHandle::close);
        mayReturn.countDown();
        awaitQuiet(admission, sessions);
      }
    } finally {
      sessions.close();
    }
  }

  @Test
  void aRerankerWhoseRunThreadsAllHoldAbandonedRunsSkipsTheRerankAtOnce(@TempDir Path tempDir)
      throws Exception {
    KnowledgeServer server = bootIndexed(tempDir);
    KnowledgeHttpApiAdapter head = withReranker(tempDir);
    EngineAdmissionController admission = admission(root);

    CountDownLatch mayReturn = new CountDownLatch(1);
    AtomicInteger nativeRuns = new AtomicInteger();
    SessionHandle sessions = stallingRerankerSessions(tempDir, mayReturn, nativeRuns);
    try (var view = server.captureServingView()) {
      view.services().searchService().setSearchReranker(reranker(sessions));
      try {
        // Searches whose reranks stall, until the reranker's run threads (two per device) all
        // hold a run whose search has answered without it.
        int cutSearches = 0;
        while (runThreadsHeld(sessions) < 2 && cutSearches < 4) {
          KnowledgeSearchResponse cut =
              head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
          assertRerankSkippedForTheDeadline(cut);
          cutSearches++;
        }
        int startedRuns = nativeRuns.get();
        assertTrue(startedRuns >= 2, "the stalled reranks reached their native calls");

        long startNs = System.nanoTime();
        KnowledgeSearchResponse next =
            head.search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertRerankSkippedForTheDeadline(next);
        assertEquals(startedRuns, nativeRuns.get(),
            "no native rerank is started while every reranker run thread holds an abandoned run");
        assertTrue(elapsedMs <= AT_ONCE_MS,
            "a search whose reranker is saturated by abandoned runs skips the rerank at once"
                + " instead of queueing for its deadline; took " + elapsedMs + " ms");
      } finally {
        mayReturn.countDown();
        awaitQuiet(admission, sessions);
      }
    } finally {
      sessions.close();
    }
  }

  private static void assertRerankSkippedForTheDeadline(KnowledgeSearchResponse response) {
    assertTrue(response.results().stream()
            .anyMatch(hit -> hit.id().contains("rerank-admission-probe")),
        "the search answers with its fused results: " + response.results());
    assertNotNull(response.searchTrace(), "the search carries its trace");
    SearchTrace.TraceStage crossEncoder = response.searchTrace().stages().stream()
        .filter(stage -> "cross-encoder".equals(stage.id().wireId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cross-encoder stage in the trace"));
    assertEquals(SearchTrace.StageStatus.SKIPPED, crossEncoder.status());
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
    Path doc = tempDir.resolve("rerank-admission-probe.txt");
    Files.writeString(doc, "quokka telemetry probe for the rerank admission test");

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
    assertTrue(client.submitBatch(List.of(doc), otherClient(-1)).getAcceptedCount() > 0);
    awaitFindable(client);
    // Warm the whole path once, before the head sees a reranker.
    headOver(client).search(hybridWithCrossEncoder(), TestEngineContexts.FOREGROUND);
    this.client = client;
    return built[0];
  }

  /** Publishes a configured reranker to the head (one hit is enough) and returns a fresh head. */
  private KnowledgeHttpApiAdapter withReranker(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path rerankerDir = rerankerDir(tempDir);
    Map<String, String> config = new java.util.HashMap<>();
    config.put("justsearch.models.dir", tempDir.resolve("models").toString());
    config.put("justsearch.rerank.enabled", "true");
    config.put("justsearch.rerank.model_path", rerankerDir.toString());
    config.put("justsearch.rerank.min_hits", "1");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), config);
    return headOver(client);
  }

  private static Path rerankerDir(Path tempDir) throws Exception {
    Path rerankerDir = Files.createDirectories(tempDir.resolve("reranker"));
    Path model = rerankerDir.resolve("model.onnx");
    if (!Files.exists(model)) {
      Files.writeString(model, "stand-in: the session is a test double");
      Files.writeString(rerankerDir.resolve("tokenizer.json"), "{}");
    }
    return rerankerDir;
  }

  /** Another client's context: its own admission bucket, so per-client limits do not apply. */
  private static EngineContext otherClient(int i) {
    return new EngineContext(EngineContext.ClientKind.MCP_CLIENT, "rerank-admission-other-" + i,
        Optional.of("mcp-session"), Optional.empty(), "UNTRUSTED", "MCP",
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
  }

  private static EngineAdmissionController admission(EngineRoot root) throws Exception {
    Field field = EngineRoot.class.getDeclaredField("admission");
    field.setAccessible(true);
    return (EngineAdmissionController) field.get(root);
  }

  private static void awaitNoLiveEngineWork(EngineAdmissionController admission)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (admission.activeWorkCount() != 0 && System.nanoTime() < until) {
      Thread.sleep(20);
    }
  }

  /**
   * Waits until the stalled runs have returned and given back the reranker's run threads, and the
   * engine holds no work, so the engine can close and the next test starts from free run threads.
   */
  private static void awaitQuiet(EngineAdmissionController admission, SessionHandle sessions)
      throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < until
        && (admission.activeWorkCount() != 0 || runThreadsInUse(sessions) != 0)) {
      Thread.sleep(20);
    }
  }

  /** The reranker's CPU run threads that hold a run whose caller was released. */
  private static int runThreadsHeld(SessionHandle sessions) throws Exception {
    return runThreadCount(sessions, "detached");
  }

  private static int runThreadsInUse(SessionHandle sessions) throws Exception {
    return runThreadCount(sessions, "live") + runThreadCount(sessions, "detached");
  }

  private static int runThreadCount(SessionHandle sessions, String count) throws Exception {
    Field device = sessions.getClass().getDeclaredField("cpuRunThreads");
    device.setAccessible(true);
    Object counts = device.get(sessions);
    Field field = counts.getClass().getDeclaredField(count);
    field.setAccessible(true);
    return field.getInt(counts);
  }

  /**
   * The reranker's native session handle, as the composition root builds it, whose CPU session is
   * a test double: its native {@code run} blocks until {@code mayReturn} opens.
   */
  private static SessionHandle stallingRerankerSessions(
      Path tempDir, CountDownLatch mayReturn, AtomicInteger nativeRuns) throws Exception {
    Path model = rerankerDir(tempDir).resolve("model.onnx");
    SessionHandle sessions = OrtSessionAssembler.buildManager(
        EncoderRole.RERANKER.consumerName(),
        new Composition(
            RuntimePolicy.defaults(),
            ModelSessionPolicy.forFallback(
                null, null, /* deferCpuSession= */ true, /* gpuRetryEnabled= */ false, 60_000L),
            new ModelArtifacts(model, null)),
        () -> false);
    OrtSession session = mock(OrtSession.class);
    when(session.run(anyMap())).thenAnswer(invocation -> {
      nativeRuns.incrementAndGet();
      mayReturn.await(STALL_MS, TimeUnit.MILLISECONDS);
      return mock(OrtSession.Result.class);
    });
    Field cpuSession = sessions.getClass().getDeclaredField("cpuSession");
    cpuSession.setAccessible(true);
    cpuSession.set(sessions, session);
    return sessions;
  }

  /** A real cross-encoder over {@code sessions}; tokenization is a test double. */
  private static CrossEncoderReranker reranker(SessionHandle sessions) throws Exception {
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
    fail("the submitted document never became findable");
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

  /** Sparse only, no cross-encoder: a keyword search. */
  private static KnowledgeSearchRequest keywordOnly() {
    return new KnowledgeSearchRequest("quokka", 10, null, null, null, List.of(), null, null, null,
        null, false, true, new PipelineConfig(true, false, false, "rrf", false, false, 0, false,
            false));
  }
}
