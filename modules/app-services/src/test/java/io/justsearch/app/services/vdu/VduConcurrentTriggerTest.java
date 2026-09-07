package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for concurrent VDU trigger protection.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Only one offline processing run executes at a time</li>
 *   <li>Concurrent triggers are safely ignored (not queued)</li>
 *   <li>No race conditions in the AtomicBoolean guard</li>
 * </ul>
 *
 * <p>Uses the same test pattern as OfflineCoordinatorTest with stub dependencies
 * and configurable delays to simulate slow processing.
 */
@DisplayName("VDU Concurrent Trigger Protection")
class VduConcurrentTriggerTest {

  private StubInferenceLifecycleManager stubInference;
  private StubKnowledgeClient stubClient;
  private SlowVduBatchProcessor slowBatchProcessor;
  private TestableOfflineCoordinator coordinator;

  private ExecutorService executor;

  @BeforeEach
  void setup() {
    stubInference = new StubInferenceLifecycleManager().withMode(Mode.ONLINE);
    stubClient = new StubKnowledgeClient();
    slowBatchProcessor = new SlowVduBatchProcessor(500);

    coordinator = new TestableOfflineCoordinator(stubInference, slowBatchProcessor, stubClient);
    executor = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void teardown() {
    executor.shutdownNow();
  }

  @Nested
  @DisplayName("Single Execution Guard")
  class SingleExecutionGuard {

    @Test
    @DisplayName("concurrent triggers result in only one execution")
    void concurrentTriggersOnlyOneExecutes() throws Exception {
      stubClient.withPendingVduCount(5);

      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();

      for (int i = 0; i < 5; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          coordinator.startOfflineProcessing();
        }));
      }

      startLatch.countDown();

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertEquals(1, slowBatchProcessor.getExecutionCount(),
          "Only one batch processing should execute despite concurrent triggers");
    }

    @Test
    @DisplayName("isProcessing returns true during execution")
    void isProcessingTrueDuringExecution() throws Exception {
      stubClient.withPendingVduCount(5);

      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch completed = new CountDownLatch(1);
      slowBatchProcessor.onStarted(started::countDown);
      slowBatchProcessor.onCompleted(completed::countDown);

      @SuppressWarnings("FutureReturnValueIgnored")
      var unused = executor.submit(coordinator::startOfflineProcessing);

      assertTrue(started.await(5, TimeUnit.SECONDS), "Processing should start");
      assertTrue(coordinator.isProcessing(), "isProcessing() should return true during execution");

      assertTrue(completed.await(5, TimeUnit.SECONDS), "Processing should complete");
      // Small delay to allow AtomicBoolean to be cleared after callback
      Thread.sleep(50);

      assertFalse(coordinator.isProcessing(), "isProcessing() should return false after completion");
    }

    @Test
    @DisplayName("second trigger while processing is ignored immediately")
    void secondTriggerIgnoredImmediately() throws Exception {
      stubClient.withPendingVduCount(5);

      CountDownLatch firstStarted = new CountDownLatch(1);
      CountDownLatch completed = new CountDownLatch(1);
      slowBatchProcessor.onStarted(firstStarted::countDown);
      slowBatchProcessor.onCompleted(completed::countDown);

      @SuppressWarnings("FutureReturnValueIgnored")
      var unused = executor.submit(coordinator::startOfflineProcessing);

      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

      long start = System.currentTimeMillis();
      coordinator.startOfflineProcessing();
      long elapsed = System.currentTimeMillis() - start;

      assertTrue(elapsed < 100, "Second trigger should return immediately, took " + elapsed + "ms");

      assertTrue(completed.await(5, TimeUnit.SECONDS), "Processing should complete");
      assertEquals(1, slowBatchProcessor.getExecutionCount());
    }

    @Test
    @DisplayName("rapid successive triggers all handled correctly")
    void rapidSuccessiveTriggers() throws Exception {
      stubClient.withPendingVduCount(1);
      slowBatchProcessor.setDelayMs(200); // Short delay but long enough to test overlap

      // Start processing in background so it's running during rapid triggers
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch completed = new CountDownLatch(1);
      slowBatchProcessor.onStarted(started::countDown);
      slowBatchProcessor.onCompleted(completed::countDown);

      @SuppressWarnings("FutureReturnValueIgnored")
      var unused = executor.submit(coordinator::startOfflineProcessing);

      // Wait for processing to start
      assertTrue(started.await(5, TimeUnit.SECONDS), "Processing should start");

      // Now fire rapid triggers while first is still processing
      for (int i = 0; i < 100; i++) {
        coordinator.startOfflineProcessing();
      }

      // Wait for completion
      assertTrue(completed.await(5, TimeUnit.SECONDS), "Processing should complete");

      assertEquals(1, slowBatchProcessor.getExecutionCount(),
          "Only one execution despite 100 rapid triggers");
    }
  }

  @Nested
  @DisplayName("Sequential Processing")
  class SequentialProcessing {

    @Test
    @DisplayName("can trigger again after previous completes")
    void canTriggerAgainAfterCompletion() throws Exception {
      stubClient.withPendingVduCount(1);
      slowBatchProcessor.setDelayMs(100);

      // First execution
      CountDownLatch firstCompleted = new CountDownLatch(1);
      slowBatchProcessor.onCompleted(firstCompleted::countDown);

      coordinator.startOfflineProcessing();
      assertTrue(firstCompleted.await(5, TimeUnit.SECONDS), "First processing should complete");
      // Small delay to allow AtomicBoolean to be cleared after callback
      Thread.sleep(50);

      assertFalse(coordinator.isProcessing());

      // Second execution - need to reset callback for new latch
      CountDownLatch secondCompleted = new CountDownLatch(1);
      slowBatchProcessor.onCompleted(secondCompleted::countDown);

      coordinator.startOfflineProcessing();
      assertTrue(secondCompleted.await(5, TimeUnit.SECONDS), "Second processing should complete");

      assertEquals(2, slowBatchProcessor.getExecutionCount(),
          "Should execute twice when triggered sequentially");
    }
  }

  // =========================================================================
  // Test Support Classes
  // =========================================================================

  /**
   * VduBatchProcessor that simulates slow processing.
   */
  static class SlowVduBatchProcessor {
    private long delayMs;
    private final AtomicInteger executionCount = new AtomicInteger(0);
    private Runnable onStartedCallback = () -> {};
    private Runnable onCompletedCallback = () -> {};

    SlowVduBatchProcessor(long delayMs) {
      this.delayMs = delayMs;
    }

    void setDelayMs(long delayMs) {
      this.delayMs = delayMs;
    }

    void onStarted(Runnable callback) {
      this.onStartedCallback = callback;
    }

    void onCompleted(Runnable callback) {
      this.onCompletedCallback = callback;
    }

    int processPendingFiles() {
      executionCount.incrementAndGet();
      onStartedCallback.run();

      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      onCompletedCallback.run();
      return 1;
    }

    int getExecutionCount() {
      return executionCount.get();
    }
  }

  /**
   * Testable version of OfflineCoordinator (same as OfflineCoordinatorTest).
   */
  static class TestableOfflineCoordinator {
    private final StubInferenceLifecycleManager inferenceManager;
    private final SlowVduBatchProcessor vduBatchProcessor;
    private final StubKnowledgeClient knowledgeClient;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    TestableOfflineCoordinator(StubInferenceLifecycleManager inferenceManager,
                               SlowVduBatchProcessor vduBatchProcessor,
                               StubKnowledgeClient knowledgeClient) {
      this.inferenceManager = inferenceManager;
      this.vduBatchProcessor = vduBatchProcessor;
      this.knowledgeClient = knowledgeClient;
    }

    void startOfflineProcessing() {
      if (!processing.compareAndSet(false, true)) {
        return;
      }

      try {
        knowledgeClient.recoverVduProcessing();

        int pendingVdu = knowledgeClient.countPendingVdu();
        knowledgeClient.countPendingEmbeddings();

        if (pendingVdu > 0) {
          processVduPhase();
        }

        int pendingEmbeddings = knowledgeClient.countPendingEmbeddings();
        if (pendingEmbeddings > 0) {
          processEmbeddingPhase();
        }
      } finally {
        processing.set(false);
      }
    }

    private void processVduPhase() {
      if (!inferenceManager.isOnline()) {
        try {
          inferenceManager.switchToOnlineMode();
        } catch (Exception e) {
          return;
        }
      }

      if (inferenceManager.isOnline()) {
        vduBatchProcessor.processPendingFiles();
      }
    }

    private void processEmbeddingPhase() {
      try {
        inferenceManager.switchToIndexingMode();
      } catch (Exception e) {
        // Continue
      }
    }

    boolean isProcessing() {
      return processing.get();
    }
  }
}
