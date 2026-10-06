/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.REQUEST_BUDGET;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.assertKeywordFallback;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtSession;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.HandleBackedEmbeddings;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.Composition;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D test K1 (S3), with K2's next search and a P2 guard: while a GPU run, not its session's
 * first, has been stuck past {@code stuckAfterMs}, later hybrid searches skip their dense leg at
 * once with {@code SEMANTIC_BUDGET_EXCEEDED} and cause {@code stuck}.
 *
 * <p>Real seam: every search enters through {@link WorkerSearchService#search} with the production
 * index and planner, and the dense leg acquires a real {@code NativeSessionHandle} on its device's
 * real gate; only the native {@code OrtSession} is a mock, whose run blocks until the test lets it
 * return. The gate's clock is the real one, so the check waits {@code stuckAfterMs} in real time.
 * It uses only API that exists before the change, so it runs on the before-state.
 */
final class WorkerSearchServiceBreakerTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /** Spec D §2.1 {@code stuckAfterMs}, written out so the check runs on the before-state too. */
  private static final long STUCK_AFTER_MS = 10_000L;

  /** A CUDA device id no other test in this JVM uses, so its gate starts empty. */
  private static final int DEVICE = 51;

  private String previousConfig;
  private RunningRuntime lifecycle;

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    lifecycle = newLifecycleWithOneDoc("Hello world");
  }

  @AfterEach
  void closeIndex() throws Exception {
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  void aStuckGpuRunTurnsLaterSearchesAwayAtOnceUntilItReturns() throws Exception {
    CountDownLatch runMayReturn = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    OrtSession stuck = mock(OrtSession.class);
    when(stuck.run(anyMap()))
        .thenAnswer(
            invocation -> {
              running.countDown();
              runMayReturn.await(120, TimeUnit.SECONDS);
              return mock(OrtSession.Result.class);
            });
    try (SessionHandle embeddingHandle = gpuHandle("k1-embedding", DEVICE, stuck);
        SessionHandle cpuLegHandle = cpuLegHandle("k1-cpu-leg", DEVICE)) {
      var service = new WorkerSearchService(lifecycle, new HandleBackedEmbeddings(embeddingHandle));
      var cpuService = new WorkerSearchService(lifecycle, new HandleBackedEmbeddings(cpuLegHandle));
      // Warm the paths untimed. The hybrid search seats the session once, so the bulk run below is
      // not the session's first run.
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));
      SearchResponse warm =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(warm.getSearchTrace().getDegradation().getHybridFallback(), "warm dense leg");

      Thread bulk =
          new Thread(
              () -> {
                try (SessionHandle.Lease lease = holdBackground(embeddingHandle);
                    OrtSession.Result ignored = lease.run(Map.of())) {
                  // The run is the stuck one; it returns when the test lets it.
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              },
              "k1-bulk-run");
      bulk.start();
      try {
        assertTrue(running.await(10, TimeUnit.SECONDS), "the bulk run started");
        long stuckAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STUCK_AFTER_MS + 300);
        while (System.nanoTime() - stuckAt < 0) {
          TimeUnit.NANOSECONDS.sleep(Math.max(1L, stuckAt - System.nanoTime()));
        }

        // The first search after the opening also warms the skip path; it must still not wait.
        SearchResponse first =
            assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET)));
        assertKeywordFallback(first, "SEMANTIC_BUDGET_EXCEEDED");
        for (int i = 0; i < 3; i++) {
          long start = System.nanoTime();
          SearchResponse response =
              service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
          long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
          assertTrue(elapsedMs <= 50, "later search " + i + " took " + elapsedMs + " ms");
          assertKeywordFallback(response, "SEMANTIC_BUDGET_EXCEEDED");
          assertEquals("stuck", response.getSearchTrace().getDegradation().getHybridFallbackCause());
        }

        // P2: a leg that selects CPU never reaches the device's gate, so it keeps working.
        SearchResponse cpu =
            cpuService.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
        assertFalse(cpu.getSearchTrace().getDegradation().getHybridFallback(), "CPU dense leg");
      } finally {
        runMayReturn.countDown();
        bulk.join(10_000);
      }
      assertFalse(bulk.isAlive(), "the stuck run returned and released its seat");

      // K2: the release closed the breaker; the next search uses dense.
      SearchResponse after =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(after.getSearchTrace().getDegradation().getHybridFallback(), "dense after close");
    }
  }

  /**
   * A handle configured for the given CUDA device whose arbitration selects CPU, with a CPU session
   * in place: its legs run on CPU and never wait for the device.
   */
  private static SessionHandle cpuLegHandle(String name, int cudaDevice) throws Exception {
    Path model = Path.of("missing", "model.onnx");
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            new GpuSessionConfig(cudaDevice, 512L * 1024 * 1024),
            null,
            /* deferCpuSession= */ true,
            /* gpuRetryEnabled= */ false,
            60_000L);
    SessionHandle handle =
        OrtSessionAssembler.buildManager(
            name, new Composition(RuntimePolicy.defaults(), policy, new ModelArtifacts(model, model)),
            () -> false);
    Field cpuSession = handle.getClass().getDeclaredField("cpuSession");
    cpuSession.setAccessible(true);
    cpuSession.set(handle, mock(OrtSession.class));
    return handle;
  }

  private RunningRuntime newLifecycleWithOneDoc(String content) throws Exception {
    Path base = Files.createTempDirectory("justsearch-breaker-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: breaker\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.PATH, "doc-1",
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }
}
