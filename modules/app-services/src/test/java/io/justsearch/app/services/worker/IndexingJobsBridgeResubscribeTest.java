/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.ipc.IndexingJobView;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lane F review, blocker 2 — a failed indexing-jobs flow re-subscribes and re-hydrates.
 *
 * <p><b>The defect this pins.</b> Review item B4 gave this flow a FAIL_FAST backpressure policy,
 * because its producer runs inside SQLite's commit hook holding {@code SqliteJobQueue}'s write
 * lock: a consumer that stops draining must fail the flow rather than block it, or one wedged SSE
 * client stalls indexing for the whole machine. But failing the flow closes it, and closing it
 * cancels the worker's change-feed subscription — and the bridge's error path did nothing except
 * log "will rely on caller-driven reconnect". There is exactly one caller of {@code start()}
 * ({@code HeadAssembly}, once at boot), so nothing ever re-called it. Net effect: one wedged SSE
 * consumer killed the indexing-jobs feed for every client until the process restarted.
 *
 * <p>So FAIL_FAST without this is not a degradation, it is an outage with extra steps. The two
 * halves have to be read together, which is why this test asserts the recovery rather than the
 * failure: that a listener which was there before the failure receives a FRESH SNAPSHOT afterwards,
 * because the stale one is exactly what a keyed cache must not keep.
 */
@Timeout(60)
@DisplayName("indexing-jobs bridge — re-subscribe after flow failure (review blocker 2)")
final class IndexingJobsBridgeResubscribeTest {
  @Test
  void replacementSnapshotsResetFailureCapAndKeepAutomaticRecoveryAlive() {
    var registry = org.mockito.Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.class);
    var registration = org.mockito.Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.Registration.class);
    var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
    org.mockito.Mockito.when(registry.limits(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new io.justsearch.core.execution.EngineExecutorRegistry.Limits(1, 32));
    org.mockito.Mockito.when(registry.register(org.mockito.ArgumentMatchers.any())).thenReturn(registration);
    org.mockito.Mockito.when(registration.openScheduled(org.mockito.ArgumentMatchers.any())).thenReturn(scheduler);
    var pending = new java.util.ArrayDeque<Runnable>();
    org.mockito.Mockito.when(scheduler.schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(invocation -> { pending.addLast(invocation.getArgument(0)); return null; });
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
    IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
      error.set(onError);
      int attempt = attempts.incrementAndGet();
      onFrame.accept(snapshotFrame(attempt, "snapshot-" + attempt));
      return () -> closed.incrementAndGet();
    };
    var bridge = new RemoteIndexingJobsBridge(registry, () -> source);
    try {
      bridge.start().join();
      for (int burst = 0; burst < 10; burst++) {
        error.get().accept(new IllegalStateException("separate overload burst " + burst));
        assertEquals(1, pending.size(), "a successful replacement snapshot resets the retry cap");
        pending.removeFirst().run();
      }
      assertEquals(11, attempts.get());
      assertEquals(10, closed.get(), "each failed subscription is closed before replacement");
      assertEquals("snapshot-11", bridge.latestSnapshot().getFirst().pathHash());
    } finally {
      bridge.stop();
    }
  }

  @Test
  void synchronousAdmissionAndExecutorRefusalUseTheExistingRetryPath() throws Exception {
    for (RuntimeException refusal : List.of(
        new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 1),
        new RejectedExecutionException("bounded stream executor is full"))) {
      AtomicInteger attempts = new AtomicInteger();
      CountDownLatch recovered = new CountDownLatch(1);
      IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
        if (attempts.incrementAndGet() == 1) throw refusal;
        onFrame.accept(snapshotFrame(2, "after-synchronous-refusal"));
        recovered.countDown();
        return () -> {};
      };
      RemoteIndexingJobsBridge bridge = new RemoteIndexingJobsBridge(executors(), () -> source);
      List<RemoteIndexingJobsBridge.Delta> seen = new CopyOnWriteArrayList<>();
      var listener = bridge.subscribe(seen::add);
      try {
        var first = bridge.start();
        assertTrue(first.isCompletedExceptionally(), "the opening refusal remains observable");
        assertSame(refusal, assertThrows(CompletionException.class, first::join).getCause());
        assertTrue(recovered.await(10, TimeUnit.SECONDS), "retry needs no second caller of start()");
        assertEquals(2, attempts.get());
        assertTrue(seen.stream()
            .filter(delta -> delta instanceof RemoteIndexingJobsBridge.Delta.SnapshotReplaced)
            .map(delta -> (RemoteIndexingJobsBridge.Delta.SnapshotReplaced) delta)
            .anyMatch(snapshot -> snapshot.items().stream()
                .anyMatch(row -> "after-synchronous-refusal".equals(row.pathHash()))));
      } finally {
        listener.close();
        bridge.stop();
      }
    }
  }


  private final List<TestEngineExecutors> processExecutors = new java.util.ArrayList<>();

  @org.junit.jupiter.api.AfterEach
  void closeProcessExecutors() {
    processExecutors.forEach(TestEngineExecutors::close);
  }

  private TestEngineExecutors executors() {
    TestEngineExecutors value = new TestEngineExecutors();
    processExecutors.add(value);
    return value;
  }

  private static IndexingJobsFrame snapshotFrame(long seq, String pathHash) {
    return IndexingJobsFrame.newBuilder()
        .setSeq(seq)
        .setSnapshot(
            IndexingJobsSnapshot.newBuilder()
                .addItems(IndexingJobView.newBuilder().setPathHash(pathHash).setState("PENDING")))
        .build();
  }

  @Test
  @DisplayName("a failed flow is re-subscribed and the listener gets a fresh snapshot")
  void failedFlowResubscribesAndRehydrates() throws Exception {
    AtomicInteger subscribes = new AtomicInteger();
    AtomicReference<Consumer<Throwable>> lastOnError = new AtomicReference<>();
    CountDownLatch secondSubscribe = new CountDownLatch(2);

    IndexingJobsSource source =
        (onFrame, onError, onCompleted) -> {
          int n = subscribes.incrementAndGet();
          lastOnError.set(onError);
          // Every subscribe opens with a snapshot, exactly as the worker's
          // subscribeIndexingJobs does — that is what makes re-subscribe a re-hydration.
          onFrame.accept(snapshotFrame(n, n == 1 ? "before-failure" : "after-failure"));
          secondSubscribe.countDown();
          return () -> {};
        };

    RemoteIndexingJobsBridge bridge = new RemoteIndexingJobsBridge(executors(), () -> source);
    List<RemoteIndexingJobsBridge.Delta> seen = new CopyOnWriteArrayList<>();
    bridge.subscribe(seen::add);
    bridge.start().join();

    assertEquals(1, subscribes.get(), "precondition: one subscribe at start");

    // What B4's FAIL_FAST does to a wedged consumer: the flow fails.
    lastOnError.get().accept(new IllegalStateException("consumer stopped draining"));

    assertTrue(
        secondSubscribe.await(30, TimeUnit.SECONDS),
        "the bridge must re-subscribe after the flow fails; before this fix it logged and stopped, "
            + "and since HeadAssembly is the only caller of start() the feed stayed dead");

    // The recovery that matters: a listener registered BEFORE the failure is re-hydrated, so its
    // keyed cache is rebuilt rather than left holding rows from a stream that no longer exists.
    assertTrue(
        seen.stream()
            .filter(d -> d instanceof RemoteIndexingJobsBridge.Delta.SnapshotReplaced)
            .map(d -> (RemoteIndexingJobsBridge.Delta.SnapshotReplaced) d)
            .anyMatch(
                s ->
                    s.items().stream()
                        .anyMatch(i -> "after-failure".equals(i.pathHash()))),
        "the re-subscribe must deliver a FRESH snapshot to the existing listener; seen=" + seen);

    bridge.stop();
  }

  @Test
  @DisplayName("a permanently failing flow stops retrying instead of spinning")
  void permanentFailureIsCapped() throws Exception {
    // The failure FAIL_FAST recovers from can be permanent: a consumer that never drains fails the
    // replacement flow the instant it is created. Unbounded retry against that is a busy loop that
    // also re-issues a full snapshot every time, so the cap is part of the fix, not a nicety.
    AtomicInteger subscribes = new AtomicInteger();
    IndexingJobsSource alwaysFails =
        (onFrame, onError, onCompleted) -> {
          subscribes.incrementAndGet();
          onFrame.accept(snapshotFrame(subscribes.get(), "doomed"));
          onError.accept(new IllegalStateException("still wedged"));
          return () -> {};
        };

    RemoteIndexingJobsBridge bridge = new RemoteIndexingJobsBridge(executors(), () -> alwaysFails);
    bridge.start().exceptionally(t -> null).join();

    // Give the backoff room to exhaust its per-minute budget.
    Thread.sleep(3_000);
    int attempts = subscribes.get();
    assertTrue(
        attempts <= RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE + 2,
        "retries must be capped per minute (cap "
            + RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE
            + "); saw "
            + attempts
            + " subscribes, which is a busy loop against a producer that cannot succeed");

    bridge.stop();
  }

  @Test
  @DisplayName("stop() ends the retry loop rather than leaving a scheduler re-subscribing")
  void stopEndsRetries() throws Exception {
    AtomicInteger subscribes = new AtomicInteger();
    AtomicReference<Consumer<Throwable>> lastOnError = new AtomicReference<>();
    IndexingJobsSource source =
        (onFrame, onError, onCompleted) -> {
          subscribes.incrementAndGet();
          lastOnError.set(onError);
          onFrame.accept(snapshotFrame(subscribes.get(), "x"));
          return () -> {};
        };

    RemoteIndexingJobsBridge bridge = new RemoteIndexingJobsBridge(executors(), () -> source);
    bridge.start().join();
    bridge.stop();

    int afterStop = subscribes.get();
    lastOnError.get().accept(new IllegalStateException("failure arriving after stop"));
    Thread.sleep(1_000);

    assertEquals(
        afterStop,
        subscribes.get(),
        "a bridge that has been stopped must not re-subscribe — a reconnect loop outliving stop() "
            + "would re-open the worker's change-feed subscription during shutdown");
  }
}
