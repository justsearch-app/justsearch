/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.REQUEST_BUDGET;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.foreground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.gpuHandle;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.holdBackground;
import static io.justsearch.indexerworker.services.WorkerSearchServiceSemanticBudgetTest.query;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ort.DeviceEncoderGate;
import io.justsearch.ort.DeviceEncoderGates;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Finding 1 of the search-gate verification (verify-d810): while the device is busy or stuck with
 * a bulk run, a search's dense leg runs on the embedding handle's warm CPU session within the
 * remaining semantic budget, instead of falling back to keyword results. VECTOR-only searches do
 * the same instead of ending at their deadline. With the device free, the leg runs on the GPU (P1).
 *
 * <p>Real seam: every search enters through {@link WorkerSearchService#search} with the production
 * index and planner, and the dense leg acquires a real {@code NativeSessionHandle} on its device's
 * real gate and runs the lease's session; only the native sessions are mocks, which count their
 * runs, and the bulk run's GPU session blocks until the test lets it return. The CPU session is
 * made warm the way any CPU run makes it warm, so the check uses only API that exists before the
 * change and runs on the before-state, where these searches fall back or end at their deadline.
 */
// This fixture starts Lucene executors and synchronous searches, but no worker health service or
// indexing/pacing loop. Isolation prevents another test's health registry scan during registration.
@Isolated("owns a synthetic-age gate in the process registry")
final class WorkerSearchServiceCpuRecourseTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final long STUCK_AFTER_MS = DeviceGatePolicy.DEFAULTS.stuckAfterMs();

  private static final String BULK_THREAD = "cpu-recourse-bulk-run";

  private String previousConfig;
  private RunningRuntime lifecycle;
  private final AtomicInteger gpuRuns = new AtomicInteger();
  private final AtomicInteger cpuRuns = new AtomicInteger();
  private final CountDownLatch bulkMayReturn = new CountDownLatch(1);
  private final CountDownLatch bulkRunning = new CountDownLatch(1);

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    lifecycle = newLifecycleWithOneVectorDoc();
  }

  @AfterEach
  void closeIndex() throws Exception {
    bulkMayReturn.countDown();
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  void aSearchOnABusyDeviceGetsItsDenseLegFromTheWarmCpuSession() throws Exception {
    try (SessionHandle embeddingHandle = embeddingHandle("cpu-recourse-busy", 52)) {
      var service =
          new WorkerSearchService(
              lifecycle, new HandleBackedEmbeddings(embeddingHandle, /* runsNative= */ true));
      // P1: the device is free, so the dense leg runs on the GPU although a warm CPU session is
      // there.
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));
      SearchResponse idle =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(idle.getSearchTrace().getDegradation().getHybridFallback(), "idle dense leg");
      assertEquals(1, gpuRuns.get(), "the idle dense leg ran on the GPU");
      assertEquals(0, cpuRuns.get(), "the idle dense leg did not run on the CPU");

      try (SessionHandle.Lease _ = holdBackground(embeddingHandle)) {
        long start = System.nanoTime();
        SearchResponse busy =
            service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        var degradation = busy.getSearchTrace().getDegradation();
        assertFalse(
            degradation.getHybridFallback(),
            () -> "the dense leg fell back: " + degradation.getHybridFallbackReason()
                + " (cause " + degradation.getHybridFallbackCause() + ")");
        assertEquals(1, cpuRuns.get(), "the dense leg ran on the CPU session");
        assertTrue(elapsedMs < REQUEST_BUDGET.toMillis(), "answered after D: " + elapsedMs + " ms");
      }
    }
  }

  @Test
  void whileAGpuRunIsStuckSearchesGetTheirVectorsFromTheWarmCpuSession() throws Throwable {
    // The owner, bulk run and JUnit preemptive-search threads terminate after this scenario.
    // No injected wait accounting remains on a reused test/pacing thread.
    FutureTask<Void> scenario = new FutureTask<>(() -> {
      searchWithControlledGateAge();
      return null;
    });
    Thread owner = new Thread(scenario, "cpu-recourse-gate-owner");
    owner.start();
    try {
      scenario.get();
    } catch (ExecutionException failure) {
      throw failure.getCause();
    } finally {
      bulkMayReturn.countDown();
      owner.interrupt();
      joinOwned(owner);
      assertFalse(owner.isAlive(), "the injected-gate owner terminated");
    }
  }

  private void searchWithControlledGateAge() throws Exception {
    int device = 53;
    AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1_000));
    DeviceEncoderGate gate = new DeviceEncoderGate(DeviceGatePolicy.DEFAULTS, clock::get);
    Map<Integer, DeviceEncoderGate> gates = gates();
    assertEquals(null, gates.putIfAbsent(device, gate), "the test owns a fresh device");
    try (SessionHandle embeddingHandle = embeddingHandle("cpu-recourse-stuck", device)) {
      assertTrue(gate == DeviceEncoderGates.forDevice(device), "the registered gate is shared");
      var service =
          new WorkerSearchService(
              lifecycle, new HandleBackedEmbeddings(embeddingHandle, /* runsNative= */ true));
      // Warm the paths untimed. The hybrid search seats the session once, so the bulk run below is
      // not the session's first run.
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));
      SearchResponse warm =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(warm.getSearchTrace().getDegradation().getHybridFallback(), "warm dense leg");

      AtomicReference<Throwable> runFailure = new AtomicReference<>();
      Thread bulk =
          new Thread(
              () -> {
                try (SessionHandle.Lease lease = holdBackground(embeddingHandle);
                    OrtSession.Result ignored = lease.run(Map.of())) {
                  // The run is the stuck one; it returns when the test lets it.
                } catch (Throwable thrown) {
                  runFailure.set(thrown);
                }
              },
              BULK_THREAD);
      bulk.start();
      try {
        assertTrue(bulkRunning.await(10, TimeUnit.SECONDS), "the bulk run started");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(STUCK_AFTER_MS + 1));
        assertTrue(gate.breakerOpenSinceNanos().isPresent(), "the warmed held run is stuck");

        int cpuBefore = cpuRuns.get();
        SearchResponse hybrid =
            assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET)));
        var degradation = hybrid.getSearchTrace().getDegradation();
        assertFalse(
            degradation.getHybridFallback(),
            () -> "the dense leg fell back: " + degradation.getHybridFallbackReason()
                + " (cause " + degradation.getHybridFallbackCause() + ")");
        assertEquals(cpuBefore + 1, cpuRuns.get(), "the hybrid dense leg ran on the CPU session");

        SearchResponse vector =
            assertDoesNotThrow(
                () ->
                    assertTimeoutPreemptively(
                        Duration.ofSeconds(1),
                        () -> service.search(vectorOnly(), foreground(REQUEST_BUDGET))),
                "the VECTOR-only search did not answer from the CPU session");
        assertEquals("VECTOR", vector.getSearchTrace().getEffectiveMode());
        assertTrue(vector.getTotalHits() >= 1, "the vector results came back");
        assertEquals(cpuBefore + 2, cpuRuns.get(), "the VECTOR-only leg ran on the CPU session");
      } finally {
        bulkMayReturn.countDown();
        joinOwned(bulk);
        assertFalse(bulk.isAlive(), "the native run returned before handle cleanup");
        assertEquals(null, runFailure.get(), "the native run completed without a thread failure");
      }
      assertFalse(bulk.isAlive(), "the stuck run returned and released its seat");

      // P1 again: the device is free, so the next leg runs on the GPU.
      int gpuBefore = gpuRuns.get();
      SearchResponse after =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(after.getSearchTrace().getDegradation().getHybridFallback(), "dense after");
      assertEquals(gpuBefore + 1, gpuRuns.get(), "the leg after the stuck run ran on the GPU");
    } finally {
      assertTrue(gates.remove(device, gate), "remove only this test's gate after handle close");
    }
  }

  /** Finish cleanup even when the test owner is interrupted; retain its interrupt afterwards. */
  private static void joinOwned(Thread thread) {
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      while (thread.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) break;
        try {
          TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      assertFalse(thread.isAlive(), "owned thread terminated: " + thread.getName());
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Integer, DeviceEncoderGate> gates() throws Exception {
    Field field = DeviceEncoderGates.class.getDeclaredField("GATES");
    field.setAccessible(true);
    return (Map<Integer, DeviceEncoderGate>) field.get(null);
  }

  /**
   * A GPU embedding handle on {@code device} whose CPU session has run once. Its GPU run blocks on
   * the bulk thread until the test lets it return.
   */
  private SessionHandle embeddingHandle(String name, int device) throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap()))
        .thenAnswer(
            invocation -> {
              if (Thread.currentThread().getName().equals(BULK_THREAD)) {
                bulkRunning.countDown();
                assertTrue(bulkMayReturn.await(120, TimeUnit.SECONDS));
              } else {
                gpuRuns.incrementAndGet();
              }
              return mock(OrtSession.Result.class);
            });
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap()))
        .thenAnswer(
            invocation -> {
              cpuRuns.incrementAndGet();
              return mock(OrtSession.Result.class);
            });
    SessionHandle handle = gpuHandle(name, device, gpu);
    Field cpuSession = handle.getClass().getDeclaredField("cpuSession");
    cpuSession.setAccessible(true);
    cpuSession.set(handle, cpu);
    // A CPU run, as any CPU encode makes; it is not a search leg.
    try (SessionHandle.Lease lease =
            handle.acquireCpu(
                SessionAcquisitionRequest.within(
                    SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30)));
        OrtSession.Result ignored = lease.run(Map.of())) {
      assertTrue(lease.isCpu(), "precondition: the warm-up ran on the CPU session");
    }
    cpuRuns.set(0);
    return handle;
  }

  private static SearchRequest vectorOnly() {
    return SearchRequest.newBuilder()
        .setQuery("Hello")
        .setLimit(10)
        .setMode(SearchMode.SEARCH_MODE_VECTOR)
        .build();
  }

  private RunningRuntime newLifecycleWithOneVectorDoc() throws Exception {
    Path base = Files.createTempDirectory("justsearch-cpu-recourse-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: cpurecourse\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4))
            .ephemeral()
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, "doc-1");
    fields.put(SchemaFields.DOC_UID, "doc-1#0");
    fields.put(SchemaFields.PATH, "doc-1");
    fields.put(SchemaFields.CONTENT, "Hello world");
    fields.put(SchemaFields.VECTOR, new float[] {1f, 0f, 0f, 0f});
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return runtime;
  }
}
