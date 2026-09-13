package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import java.util.concurrent.CountDownLatch;
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
}
