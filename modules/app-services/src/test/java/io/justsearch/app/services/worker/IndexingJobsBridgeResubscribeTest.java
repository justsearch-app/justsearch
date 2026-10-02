/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.ipc.IndexingJobView;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
  void subscriptionRefusalsSurviveTheRollingBudgetAndRecoverWithoutAnotherStart() throws Exception {
    for (RuntimeException refusal : List.of(
        new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 1),
        new RejectedExecutionException("bounded stream executor is full"))) {
      var calls = new AtomicInteger();
      var closes = new AtomicInteger();
      var error = new AtomicReference<Consumer<Throwable>>();
      int refusals = RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE + 1;
      IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
        int call = calls.incrementAndGet();
        error.set(onError);
        if (call > 1 && call <= refusals + 1) throw refusal;
        onFrame.accept(snapshotFrame(call, call == 1 ? "stale" : "recovered"));
        return () -> closes.incrementAndGet();
      };
      try (var harness = new ReconnectHarness()) {
        var bridge = new RemoteIndexingJobsBridge(harness.registry, () -> source, harness.now::get);
        var seen = new CopyOnWriteArrayList<RemoteIndexingJobsBridge.Delta>();
        bridge.subscribe(seen::add);
        try {
          bridge.start().join();
          error.get().accept(new IllegalStateException("existing feed failed"));
          for (int retry = 1; retry <= refusals; retry++) {
            var next = harness.next();
            if (retry == refusals) {
              assertEquals(RemoteIndexingJobsBridge.RECONNECT_WINDOW_MS
                      + RemoteIndexingJobsBridge.RECONNECT_BASE_DELAY_MS + 1,
                  harness.now.get() + next.delayMs(),
                  "the exhausted budget must wait until a rolling-window slot is available");
            }
            harness.advanceAndRun(next);
            assertEquals(retry + 1, calls.get());
            assertEquals(1L, bridge.latestSnapshotSeq(), "refusals cannot refresh the stale snapshot");
          }
          assertTrue(harness.cooldowns.get() > 0, "the test must exhaust the rolling retry budget");
          harness.runNext();
          assertEquals(refusals + 2, calls.get());
          assertEquals("recovered", bridge.latestSnapshot().getFirst().pathHash());
          assertEquals(refusals + 2L, bridge.latestSnapshotSeq());
          assertEquals(2, seen.size(), "the original listener receives the replacement snapshot");
          assertEquals(1, closes.get(), "the failed stream is retired even during saturation");
          assertTrue(harness.pending.isEmpty(), "recovery leaves no duplicate reconnect obligation");
        } finally {
          bridge.stop();
        }
      }
    }
  }

  @Test
  void asynchronousFailuresKeepOneCooldownAndStopCancelsIt() throws Exception {
    var calls = new AtomicInteger();
    var error = new AtomicReference<Consumer<Throwable>>();
    IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
      int call = calls.incrementAndGet();
      error.set(onError);
      if (call == 1) onFrame.accept(snapshotFrame(1, "stale"));
      return () -> {};
    };
    try (var harness = new ReconnectHarness()) {
      var bridge = new RemoteIndexingJobsBridge(harness.registry, () -> source, harness.now::get);
      try {
        bridge.start().join();
        for (int failure = 0; failure < RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE; failure++) {
          error.get().accept(new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 1));
          harness.runNext();
        }
        error.get().accept(new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 1));
        var cooldown = harness.next();
        assertTrue(cooldown.delayMs() > RemoteIndexingJobsBridge.RECONNECT_MAX_DELAY_MS);
        assertTrue(harness.pending.isEmpty(), "only one reconnect is retained while cooling down");
        bridge.stop();
        int before = calls.get();
        harness.advanceAndRun(cooldown);
        assertEquals(before, calls.get(), "a delivered cooldown callback cannot reopen a stopped bridge");
      } finally {
        bridge.stop();
      }
    }
  }

  @Test
  void replacementSnapshotsResetFailureCapAndKeepAutomaticRecoveryAlive() {
    var reconnects = new ControlledReconnects();
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
    IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
      error.set(onError);
      int attempt = attempts.incrementAndGet();
      onFrame.accept(snapshotFrame(attempt, "snapshot-" + attempt));
      return () -> closed.incrementAndGet();
    };
    var bridge = new RemoteIndexingJobsBridge(reconnects.registry, () -> source);
    try {
      bridge.start().join();
      for (int burst = 0; burst < 10; burst++) {
        error.get().accept(new IllegalStateException("separate overload burst " + burst));
        assertEquals(1, reconnects.pending.size(), "a successful replacement snapshot resets the retry cap");
        reconnects.pending.removeFirst().run();
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

  /** Real owned retry worker; controlled timers advance a monotonic clock without minute-long waits. */
  private static final class ReconnectHarness implements AutoCloseable {
    record Pending(Runnable task, long delayMs) {}

    final AtomicLong now = new AtomicLong();
    final AtomicInteger cooldowns = new AtomicInteger();
    final LinkedBlockingQueue<Pending> pending = new LinkedBlockingQueue<>();
    final TestEngineExecutors owners = TestEngineExecutors.awaitingTermination();
    final EngineExecutorRegistry registry = org.mockito.Mockito.mock(EngineExecutorRegistry.class,
        org.mockito.AdditionalAnswers.delegatesTo(owners));

    ReconnectHarness() {
      var scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
      org.mockito.Mockito.when(scheduler.schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
          org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
          .thenAnswer(invocation -> {
            long delay = invocation.getArgument(1);
            assertTrue(delay > 0, "reconnect must never busy-loop");
            assertTrue(pending.isEmpty(), "only one timer may retain the reconnect obligation");
            if (delay > RemoteIndexingJobsBridge.RECONNECT_MAX_DELAY_MS) cooldowns.incrementAndGet();
            pending.add(new Pending(invocation.getArgument(0), delay));
            return org.mockito.Mockito.mock(ScheduledFuture.class);
          });
      org.mockito.Mockito.doAnswer(invocation -> {
        EngineExecutorSpec spec = invocation.getArgument(0);
        var owner = owners.register(spec);
        if (spec.mode() != EngineExecutorSpec.Mode.SCHEDULED) return owner;
        var observed = org.mockito.Mockito.mock(EngineExecutorRegistry.Registration.class,
            org.mockito.AdditionalAnswers.delegatesTo(owner));
        org.mockito.Mockito.doReturn(scheduler).when(observed).openScheduled(org.mockito.ArgumentMatchers.any());
        return observed;
      }).when(registry).register(org.mockito.ArgumentMatchers.any());
    }

    Pending next() throws InterruptedException {
      var next = pending.poll(2, TimeUnit.SECONDS);
      assertNotNull(next, "the reconnect obligation must survive rolling-budget exhaustion");
      assertTrue(pending.isEmpty(), "there must be no duplicate reconnect timer");
      return next;
    }

    void runNext() throws InterruptedException {
      advanceAndRun(next());
    }

    void advanceAndRun(Pending next) {
      now.addAndGet(next.delayMs());
      next.task().run();
    }

    @Override
    public void close() {
      owners.close();
    }
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
  @DisplayName("a permanently failing flow cools down at the rolling cap instead of spinning")
  void permanentFailureIsCapped() {
    var reconnects = new ControlledReconnects();
    var now = new AtomicLong();
    AtomicInteger subscribes = new AtomicInteger();
    IndexingJobsSource alwaysFails =
        (onFrame, onError, onCompleted) -> {
          subscribes.incrementAndGet();
          // No successful snapshot: snapshots intentionally clear the rolling failure history.
          onError.accept(new IllegalStateException("still wedged"));
          return () -> {};
        };

    var bridge = new RemoteIndexingJobsBridge(reconnects.registry, () -> alwaysFails, now::get);
    try {
      assertThrows(CompletionException.class, () -> bridge.start().join());
      // Drive callbacks immediately; backoff cannot limit this test's attempts. All failures are
      // in the same rolling minute, without depending on scheduler timing or sleeping.
      for (int retry = 0; retry < RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE; retry++) {
        assertEquals(1, reconnects.pending.size(), "retry " + retry + " must be scheduled");
        reconnects.pending.removeFirst().run();
      }
      assertEquals(RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE + 1, subscribes.get());
      assertEquals(1, reconnects.pending.size(), "the exhausted cap retains exactly one cooldown");
      long cooldown = RemoteIndexingJobsBridge.RECONNECT_WINDOW_MS + 1;
      assertEquals(List.of(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, cooldown),
          reconnects.delays, "the next retry must wait until rolling-window capacity is available");
      now.set(cooldown);
      reconnects.pending.removeFirst().run();
      assertEquals(RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE + 2, subscribes.get(),
          "a renewed budget retries without another caller of start()");
      assertEquals(1, reconnects.pending.size(), "a continued failure retains only one retry");
      assertEquals(RemoteIndexingJobsBridge.RECONNECT_MAX_DELAY_MS,
          reconnects.delays.getLast().longValue(), "backoff remains bounded after cooldown");
      bridge.stop();
      reconnects.pending.removeFirst().run();
      assertEquals(RemoteIndexingJobsBridge.RECONNECT_MAX_PER_MINUTE + 2, subscribes.get(),
          "a pending retry cannot reopen a stopped bridge");
    } finally {
      bridge.stop();
    }
  }

  private static final class ControlledReconnects {
    final io.justsearch.core.execution.EngineExecutorRegistry registry =
        org.mockito.Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.class);
    final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();
    final List<Long> delays = new java.util.ArrayList<>();

    ControlledReconnects() {
      var registration = org.mockito.Mockito.mock(io.justsearch.core.execution.EngineExecutorRegistry.Registration.class);
      var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
      org.mockito.Mockito.when(registry.limits(org.mockito.ArgumentMatchers.any()))
          .thenReturn(new io.justsearch.core.execution.EngineExecutorRegistry.Limits(1, 32));
      org.mockito.Mockito.when(registry.register(org.mockito.ArgumentMatchers.any())).thenReturn(registration);
      org.mockito.Mockito.when(registration.openScheduled(org.mockito.ArgumentMatchers.any())).thenReturn(scheduler);
      var retryWorker = org.mockito.Mockito.mock(java.util.concurrent.ExecutorService.class);
      org.mockito.Mockito.when(registration.open(org.mockito.ArgumentMatchers.any())).thenReturn(retryWorker);
      org.mockito.Mockito.doAnswer(invocation -> {
        ((Runnable) invocation.getArgument(0)).run();
        return null;
      }).when(retryWorker).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
      org.mockito.Mockito.when(scheduler.schedule(org.mockito.ArgumentMatchers.any(Runnable.class),
          org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
          .thenAnswer(invocation -> {
            pending.addLast(invocation.getArgument(0));
            delays.add(invocation.getArgument(1));
            return null;
          });
    }
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
