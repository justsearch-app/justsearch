/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.services.worker.IndexingJobsSource;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RemoteIndexingJobsBridgeCapacityTest {
  @Test
  void timerSaturationRetainsReconnectUntilProductionCapacityReturns() throws Exception {
    capacityRecovery(Reason.TIMER_LIMIT, false);
  }

  @Test
  void localQueueSaturationRetainsReconnectUntilProductionCapacityReturns() throws Exception {
    capacityRecovery(Reason.QUEUE_LIMIT, false);
  }

  @Test
  void stoppingDuringSaturationCancelsTheRetainedReconnect() throws Exception {
    capacityRecovery(Reason.TIMER_LIMIT, true);
  }

  private static void capacityRecovery(Reason expected, boolean stopBeforeRelease) throws Exception {
    try (var registry = registry(expected == Reason.QUEUE_LIMIT ? 512 : 256)) {
      var probe = new ScheduleProbe(registry);
      var calls = new AtomicInteger();
      var error = new AtomicReference<Consumer<Throwable>>();
      var replaced = new CountDownLatch(1);
      IndexingJobsSource source = (onFrame, onError, onCompleted) -> {
        int sequence = calls.incrementAndGet();
        error.set(onError);
        onFrame.accept(IndexingJobsFrame.newBuilder().setSeq(sequence)
            .setSnapshot(IndexingJobsSnapshot.newBuilder().addItems(
                io.justsearch.ipc.IndexingJobView.newBuilder().setPathHash("job")
                    .setState(sequence == 1 ? "PENDING" : "DONE"))).build());
        if (sequence == 2) replaced.countDown();
        return () -> {};
      };
      var bridge = new RemoteIndexingJobsBridge(probe.observed, () -> source);
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        ScheduledExecutorService occupied = probe.scheduler.get();
        if (expected == Reason.TIMER_LIMIT) {
          occupied = registry.register(new EngineExecutorSpec("occupied-timers",
              EngineExecutorSpec.Kind.BACKGROUND, EngineExecutorSpec.Mode.SCHEDULED, 1, 256, 1))
              .openScheduled(Thread.ofPlatform().daemon().factory());
        }
        var timers = new ArrayList<ScheduledFuture<?>>();
        for (int i = 0; i < 256; i++) timers.add(occupied.schedule(() -> {}, 1, TimeUnit.DAYS));
        error.get().accept(new IllegalStateException("feed failed"));
        assertTrue(probe.refused.await(2, TimeUnit.SECONDS), "the bridge must actually encounter saturation");
        assertEquals(expected, probe.reason.get());
        assertEquals(1L, bridge.latestSnapshotSeq(), "only the stale initial snapshot exists yet");
        if (stopBeforeRelease) bridge.stop();
        timers.forEach(timer -> timer.cancel(false));
        if (stopBeforeRelease) {
          assertFalse(replaced.await(1500, TimeUnit.MILLISECONDS));
          assertEquals(1, calls.get());
        } else {
          assertTrue(replaced.await(5, TimeUnit.SECONDS), "capacity recovery must reconnect without a caller start()");
          assertEquals(2L, bridge.latestSnapshotSeq(), "a fresh snapshot replaces stale cached state");
          assertEquals("DONE", bridge.latestSnapshot().getFirst().state());
          assertEquals(2, calls.get());
        }
      } finally {
        bridge.stop();
      }
    }
  }

  @Test
  void closedSchedulerStopsRetrying() throws Exception {
    try (var registry = registry(256)) {
      var probe = new ScheduleProbe(registry);
      var error = new AtomicReference<Consumer<Throwable>>();
      var calls = new AtomicInteger();
      var bridge = new RemoteIndexingJobsBridge(probe.observed, () -> (frame, failed, completed) -> {
        calls.incrementAndGet();
        error.set(failed);
        frame.accept(IndexingJobsFrame.newBuilder().setSeq(1)
            .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
        return () -> {};
      });
      try {
        bridge.start().get(2, TimeUnit.SECONDS);
        probe.scheduler.get().shutdownNow();
        error.get().accept(new IllegalStateException("feed failed"));
        assertTrue(probe.refused.await(2, TimeUnit.SECONDS));
        assertEquals(Reason.CLOSED, probe.reason.get());
        int attempts = probe.attempts.get();
        Thread.sleep(1500);
        assertEquals(attempts, probe.attempts.get(), "permanent closure must not poll for capacity");
        assertEquals(1, calls.get());
      } finally {
        bridge.stop();
      }
    }
  }

  private static DefaultEngineExecutorRegistry registry(int timers) {
    return new DefaultEngineExecutorRegistry(new EngineResourcePolicy(Map.of(
        "perContextLimit", 2, "aggregateLimit", 8, "retryAfterSeconds", 1,
        "foregroundThreads", 1, "foregroundQueue", 256,
        "backgroundThreads", 1, "backgroundQueue", 256,
        "timerRegistrations", timers, "directMemoryMiB", 1), new RetainedStateBudget()),
        Duration.ofSeconds(1));
  }

  /** Observes real production scheduling refusals; the executor and all credits remain real. */
  private static final class ScheduleProbe {
    final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>();
    final AtomicReference<Reason> reason = new AtomicReference<>();
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch refused = new CountDownLatch(1);
    final EngineExecutorRegistry observed;

    ScheduleProbe(DefaultEngineExecutorRegistry registry) {
      observed = mock(EngineExecutorRegistry.class, org.mockito.AdditionalAnswers.delegatesTo(registry));
      doAnswer(invocation -> {
        EngineExecutorSpec spec = invocation.getArgument(0);
        var owner = registry.register(spec);
        if (!spec.name().equals("head.indexing-jobs-bridge-reconnect")) return owner;
        var wrapped = mock(EngineExecutorRegistry.Registration.class,
            org.mockito.AdditionalAnswers.delegatesTo(owner));
        doAnswer(open -> {
          var real = owner.openScheduled(open.getArgument(0));
          var scheduled = mock(ScheduledExecutorService.class, org.mockito.AdditionalAnswers.delegatesTo(real));
          doAnswer(schedule -> {
            attempts.incrementAndGet();
            try {
              return real.schedule((Runnable) schedule.getArgument(0),
                  (Long) schedule.getArgument(1), (TimeUnit) schedule.getArgument(2));
            } catch (EngineExecutorRejectedException failure) {
              reason.set(failure.reason());
              refused.countDown();
              throw failure;
            }
          }).when(scheduled).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
          scheduler.set(scheduled);
          return scheduled;
        }).when(wrapped).openScheduled(any());
        return wrapped;
      }).when(observed).register(any());
    }
  }
}
