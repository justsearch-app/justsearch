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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.justsearch.ipc.TraceStage;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract X2 of b6d536: a turned-away foreground query leg encodes on the warm CPU session within
 * the remaining semantic budget, the search's trace says which device encoded the leg and whether
 * the CPU recourse took it, and the leg carries a reason code only when the CPU also misses (then
 * S3 holds: SEMANTIC_BUDGET_EXCEEDED with its cause; a VECTOR-only search ends in its typed
 * deadline failure).
 *
 * <p>Real seam, as in {@link WorkerSearchServiceCpuRecourseTest}: every search enters through
 * {@link WorkerSearchService#search} with the production index and planner, and the dense leg
 * acquires a real {@code NativeSessionHandle} on its device's real gate and runs the lease's
 * session; only the native sessions are mocks. The CPU session can be made to stall, as a slow CPU
 * encode would, until the test lets it return.
 */
final class WorkerSearchServiceCpuRecourseVisibilityTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String DENSE = "dense-retrieval";

  private String previousConfig;
  private RunningRuntime lifecycle;
  private final AtomicInteger cpuRuns = new AtomicInteger();
  private final AtomicBoolean cpuStalls = new AtomicBoolean();
  private final CountDownLatch cpuMayReturn = new CountDownLatch(1);

  @BeforeEach
  void openIndex() throws Exception {
    previousConfig = System.getProperty("justsearch.config");
    lifecycle = newLifecycleWithOneVectorDoc();
  }

  @AfterEach
  void closeIndex() throws Exception {
    cpuMayReturn.countDown();
    if (lifecycle != null) lifecycle.close();
    if (previousConfig == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", previousConfig);
  }

  @Test
  void theTraceSaysWhichDeviceEncodedTheDenseLegAndWhetherTheCpuRecourseTookIt()
      throws Exception {
    try (SessionHandle embeddingHandle = embeddingHandle("cpu-recourse-trace", 54)) {
      var service =
          new WorkerSearchService(
              lifecycle, new HandleBackedEmbeddings(embeddingHandle, /* runsNative= */ true));
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));

      SearchResponse idle =
          service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
      assertFalse(idle.getSearchTrace().getDegradation().getHybridFallback(), "idle dense leg");
      assertEquals("device=gpu", stage(idle, DENSE).getDetail(), "the idle leg ran on the GPU");

      try (SessionHandle.Lease bulk = holdBackground(embeddingHandle)) {
        assertFalse(bulk.isCpu(), "precondition: the bulk lease holds the device's seat");
        SearchResponse busy =
            service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
        assertFalse(busy.getSearchTrace().getDegradation().getHybridFallback(), "busy dense leg");
        assertEquals("executed", stage(busy, DENSE).getStatus());
        assertEquals(
            "device=cpu; cpu-recourse=contended",
            stage(busy, DENSE).getDetail(),
            "the turned-away leg ran on the CPU, taken by the recourse");

        // The CPU also misses: the leg falls back with its reason, and the trace still says the
        // recourse took it to the CPU.
        cpuStalls.set(true);
        SearchResponse missed =
            service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET));
        assertKeywordFallback(missed, "SEMANTIC_BUDGET_EXCEEDED");
        assertEquals("device=cpu; cpu-recourse=contended", stage(missed, DENSE).getDetail());
      } finally {
        cpuMayReturn.countDown(); // the detached CPU run returns before the handle closes
      }
    }
  }

  @Test
  void aCpuEncodeThatAlsoMissesTheRemainingBudgetGivesTheReasonAndItsCause() throws Exception {
    try (SessionHandle embeddingHandle = embeddingHandle("cpu-recourse-miss", 55)) {
      var service =
          new WorkerSearchService(
              lifecycle, new HandleBackedEmbeddings(embeddingHandle, /* runsNative= */ true));
      service.search(query(SearchMode.SEARCH_MODE_TEXT), foreground(REQUEST_BUDGET));
      cpuStalls.set(true);

      try (SessionHandle.Lease bulk = holdBackground(embeddingHandle)) {
        assertFalse(bulk.isCpu(), "precondition: the bulk lease holds the device's seat");
        int cpuBefore = cpuRuns.get();
        SearchResponse hybrid =
            assertTimeoutPreemptively(
                REQUEST_BUDGET,
                () -> service.search(query(SearchMode.SEARCH_MODE_HYBRID), foreground(REQUEST_BUDGET)),
                "the hybrid search answered within its deadline");
        assertEquals(cpuBefore + 1, cpuRuns.get(), "the turned-away leg started its CPU run");
        assertKeywordFallback(hybrid, "SEMANTIC_BUDGET_EXCEEDED");
        assertEquals(
            "stuck",
            hybrid.getSearchTrace().getDegradation().getHybridFallbackCause(),
            "a run that started and did not return in the sub-budget is cause stuck");

        WorkerServiceException failure =
            assertTimeoutPreemptively(
                REQUEST_BUDGET,
                () ->
                    assertThrows(
                        WorkerServiceException.class,
                        () -> service.search(vectorOnly(), foreground(REQUEST_BUDGET))),
                "the VECTOR-only search ended within its deadline");
        assertEquals(cpuBefore + 2, cpuRuns.get(), "the VECTOR-only leg started its CPU run");
        assertEquals(WorkerServiceException.Status.DEADLINE_EXCEEDED, failure.status());
        assertTrue(
            failure.getMessage().contains("SEMANTIC_BUDGET_EXCEEDED (cause: stuck)"),
            () -> "the typed deadline names the reason and cause: " + failure.getMessage());
      } finally {
        cpuMayReturn.countDown(); // the detached CPU runs return before the handle closes
      }
    }
  }

  private static TraceStage stage(SearchResponse response, String id) {
    return response.getSearchTrace().getStagesList().stream()
        .filter(s -> s.getId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + id + " stage in the trace"));
  }

  /**
   * A GPU embedding handle on {@code device} whose CPU session has run once. While {@link
   * #cpuStalls} is set, a CPU run stalls until the test ends.
   */
  private SessionHandle embeddingHandle(String name, int device) throws Exception {
    OrtSession gpu = mock(OrtSession.class);
    when(gpu.run(anyMap())).thenAnswer(invocation -> mock(OrtSession.Result.class));
    OrtSession cpu = mock(OrtSession.class);
    when(cpu.run(anyMap()))
        .thenAnswer(
            invocation -> {
              cpuRuns.incrementAndGet();
              if (cpuStalls.get()) cpuMayReturn.await(120, TimeUnit.SECONDS);
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
    Path base = Files.createTempDirectory("justsearch-cpu-recourse-visibility-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: cpurecoursevisibility\n      roots: ['ignored']\n"
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
