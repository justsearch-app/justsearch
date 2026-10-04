package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for tempdoc 397 §14.28 U3 — the {@code modelReadyLatch} gate in
 * {@link WorkerSearchService}. Verifies that {@code awaitModelsReady} short-circuits when no
 * supplier is wired, passes through when the latch is already counted down, and returns
 * without throwing when the supplier returns null.
 *
 * <p>The "blocks until timeout" path is harder to test cleanly at unit scope — the default
 * timeout is 120 s. We rely on integration coverage (real KnowledgeServer boot with a query
 * arriving before {@code initDeferredModels} completes) for that contract, and pin the
 * short-path cases here.
 */
@DisplayName("WorkerSearchService models-ready latch gate (§14.28 U3)")
class WorkerSearchServiceModelReadyLatchTest {

  @Test
  void effectivePipelineDeterminesModelGate() {
    var text = SearchRequest.newBuilder().setMode(SearchMode.SEARCH_MODE_TEXT).build();
    org.junit.jupiter.api.Assertions.assertFalse(WorkerSearchService.searchNeedsModels(text));
    for (var mode : java.util.List.of(SearchMode.SEARCH_MODE_VECTOR,
        SearchMode.SEARCH_MODE_HYBRID, SearchMode.SEARCH_MODE_SPLADE)) {
      assertTrue(WorkerSearchService.searchNeedsModels(text.toBuilder().setMode(mode).build()));
    }
    for (var pipeline : java.util.List.of(
        PipelineConfig.newBuilder().setDenseEnabled(true).build(),
        PipelineConfig.newBuilder().setDenseAuto(true).build(),
        PipelineConfig.newBuilder().setSpladeEnabled(true).build(),
        PipelineConfig.newBuilder().setSparseEnabled(true).setCrossEncoderEnabled(true).build())) {
      assertTrue(WorkerSearchService.searchNeedsModels(text.toBuilder().setPipeline(pipeline).build()));
    }
    // Explicit pipeline overrides a hybrid mode, as it does in the planner/input capture.
    org.junit.jupiter.api.Assertions.assertFalse(WorkerSearchService.searchNeedsModels(
        text.toBuilder().setMode(SearchMode.SEARCH_MODE_HYBRID)
            .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true)
                .setLambdamartEnabled(true).setExpansionEnabled(true).build()).build()));
  }

  @Test
  void hybridAndLexicalRerankSearchWaitForModelWiring() throws InterruptedException {
    for (var request : java.util.List.of(
        SearchRequest.newBuilder().setQuery("hello").setMode(SearchMode.SEARCH_MODE_HYBRID).build(),
        SearchRequest.newBuilder().setQuery("hello").setMode(SearchMode.SEARCH_MODE_TEXT)
            .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true)
                .setCrossEncoderEnabled(true).build()).build())) {
      var service = buildService();
      var loading = new CountDownLatch(1);
      var entered = new CountDownLatch(1);
      service.setModelReadyLatchSupplier(() -> { entered.countDown(); return loading; });
      var finished = new CountDownLatch(1);
      var waiter = new Thread(() -> {
        try { service.search(request, CallContext.none()); }
        catch (WorkerServiceException ignored) { /* Mock runtime has no searchable index. */ }
        finally { finished.countDown(); }
      }, "model-dependent-search");
      waiter.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS), "search must select its readiness latch");
        org.junit.jupiter.api.Assertions.assertFalse(finished.await(100, TimeUnit.MILLISECONDS),
            "model-dependent search cannot pass the gate before wiring");
      } finally {
        loading.countDown();
        waiter.join(5_000);
      }
      assertTrue(!waiter.isAlive(), "readiness must release the waiting search");
    }
  }

  private WorkerSearchService buildService() {
    RunningRuntime mockLifecycle = Mockito.mock(RunningRuntime.class);
    Mockito.when(mockLifecycle.taskLifetime()).thenReturn(io.justsearch.core.execution.EngineTaskLifetime.NONE);
    Mockito.when(mockLifecycle.executorRegistrations()).thenReturn(
        Mockito.mock(io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations.class));
    return new WorkerSearchService(mockLifecycle, NoOpEmbeddingProvider.INSTANCE);
  }

  @Test
  void noLatchWiredShortCircuits() {
    WorkerSearchService service = buildService();
    // awaitModelsReady with no supplier wired → immediate return (no NPE).
    assertDoesNotThrow(() -> service.awaitModelsReady("test"));
  }

  @Test
  void countedDownLatchReturnsImmediately() {
    WorkerSearchService service = buildService();
    CountDownLatch latch = new CountDownLatch(1);
    latch.countDown();
    service.setModelReadyLatchSupplier(() -> latch);

    long start = System.nanoTime();
    service.awaitModelsReady("test");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    // Well under the 120 s timeout — latch is already ready so await returns at once.
    assertTrue(elapsedMs < 500, "awaitModelsReady returned in " + elapsedMs + " ms");
  }

  @Test
  void supplierReturningNullShortCircuits() {
    WorkerSearchService service = buildService();
    service.setModelReadyLatchSupplier(() -> null);
    assertDoesNotThrow(() -> service.awaitModelsReady("test"));
  }

  @Test
  void latchCountedDownConcurrentlyUnblocksWaiter() throws InterruptedException {
    WorkerSearchService service = buildService();
    CountDownLatch latch = new CountDownLatch(1);
    service.setModelReadyLatchSupplier(() -> latch);

    Thread waiter =
        new Thread(
            () -> service.awaitModelsReady("test"),
            "test-waiter");
    waiter.start();
    // Give the waiter a moment to reach await(), then release the latch.
    Thread.sleep(50);
    assertTrue(waiter.isAlive(), "waiter should be blocked on the latch");
    latch.countDown();
    waiter.join(5_000);
    assertTrue(!waiter.isAlive(), "waiter should have returned after latch.countDown()");
  }

  @Test
  void newServingSetWaitsOnItsOwnLatchAfterPreviousSetWasReady() throws InterruptedException {
    WorkerSearchService service = buildService();
    CountDownLatch a = new CountDownLatch(0);
    CountDownLatch b = new CountDownLatch(1);
    AtomicReference<CountDownLatch> serving = new AtomicReference<>(a);
    CountDownLatch capturedB = new CountDownLatch(1);
    service.setModelReadyLatchSupplier(() -> {
      CountDownLatch selected = serving.get();
      if (selected == b) capturedB.countDown();
      return selected;
    });
    service.awaitModelsReady("A ready");
    serving.set(b);
    Thread waiter = new Thread(() -> service.awaitModelsReady("B loading"),
        "new-serving-set-waiter");
    waiter.start();
    try {
      assertTrue(capturedB.await(5, TimeUnit.SECONDS), "the query must select B's latch");
      assertTrue(waiter.isAlive(), "A's released latch cannot authorize B before model wiring");
      b.countDown();
      waiter.join(5_000);
      assertTrue(!waiter.isAlive(), "B's readiness must release its waiting query");
    } finally {
      b.countDown();
      waiter.join(5_000);
    }
  }
}
