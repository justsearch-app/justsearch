/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class DefaultEngineExecutorRegistryTest {
  private static final ThreadFactory DAEMON = runnable -> {
    Thread thread = new Thread(runnable, "registry-test");
    thread.setDaemon(true);
    return thread;
  };

  @Test
  void projectsPolicyAndRejectsSpecsOutsideTheirKindLimits() {
    try (var registry = registry(2, 3, 1, 4, 7, 9)) {
      assertEquals(new EngineExecutorRegistry.Limits(2, 3), registry.limits(Kind.FOREGROUND));
      assertEquals(new EngineExecutorRegistry.Limits(1, 4), registry.limits(Kind.BACKGROUND));
      assertEquals(7, registry.maxConcurrentWork());
      registry.register(EngineExecutorSpec.virtual("per-call", Kind.FOREGROUND, 7));
      assertThrows(IllegalArgumentException.class, () -> registry.register(
          EngineExecutorSpec.virtual("too-many-per-call", Kind.FOREGROUND, 9)));
      // Platform multiplicity is an ownership decision, e.g. active + wedged parser instances.
      registry.register(new EngineExecutorSpec(
          "semantic-multiplicity", Kind.BACKGROUND, Mode.PLATFORM, 1, 1, 9));
      assertThrows(IllegalArgumentException.class, () -> registry.register(
          new EngineExecutorSpec("too-wide", Kind.BACKGROUND, Mode.PLATFORM, 2, 1, 1)));
      assertThrows(IllegalArgumentException.class, () -> registry.register(
          new EngineExecutorSpec("too-deep", Kind.FOREGROUND, Mode.SCHEDULED, 1, 4, 1)));
      assertThrows(IllegalArgumentException.class, () ->
          new EngineExecutorSpec("zero-scheduled", Kind.FOREGROUND, Mode.SCHEDULED, 1, 0, 1));
      assertThrows(IllegalArgumentException.class, () ->
          new EngineExecutorSpec("bounded-virtual", Kind.FOREGROUND, Mode.VIRTUAL, 1, 1, 1));
    }
  }

  @Test
  void boundedPlatformQueueRejectsWithoutRunningOnSubmitter() throws Exception {
    try (var registry = registry(2, 2, 1, 1, 4, 4)) {
      var registration = registry.register(
          new EngineExecutorSpec("bounded", Kind.BACKGROUND, Mode.PLATFORM, 1, 1, 1));
      var executor = registration.open(DAEMON);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      executor.submit(() -> awaitUninterruptibly(entered, release));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      executor.submit(() -> {});
      EngineExecutorRejectedException refused = assertThrows(
          EngineExecutorRejectedException.class, () -> executor.submit(() -> {}));
      assertEquals(Reason.QUEUE_LIMIT, refused.reason());
      assertEquals(1, registry.snapshot().registrations().getFirst().queuedTasks());
      release.countDown();
    }
  }

  @Test
  void activeDuplicateFailsButCleanlyRetiredNameCanBeReconstructed() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 4, 4)) {
      var foreground = registry.register(
          new EngineExecutorSpec("foreground", Kind.FOREGROUND, Mode.PLATFORM, 1, 0, 1));
      var background = registry.register(
          new EngineExecutorSpec("background", Kind.BACKGROUND, Mode.PLATFORM, 1, 0, 1));
      assertThrows(IllegalArgumentException.class, () -> registry.register(foreground.spec()));
      AtomicReference<Thread> first = new AtomicReference<>();
      AtomicReference<Thread> second = new AtomicReference<>();
      foreground.open(DAEMON).submit(() -> first.set(Thread.currentThread())).get();
      background.open(DAEMON).submit(() -> second.set(Thread.currentThread())).get();
      assertFalse(first.get() == second.get());
      foreground.close();
      var reconstructed = registry.register(foreground.spec());
      assertEquals("foreground", reconstructed.spec().name());
    }
  }

  @Test
  void concurrentCloseKeepsNameUnavailableUntilActualTermination() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 4, 4)) {
      EngineExecutorSpec spec =
          new EngineExecutorSpec("restartable", Kind.BACKGROUND, Mode.PLATFORM, 1, 0, 1);
      var registration = registry.register(spec);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      registration.open(DAEMON).submit(() -> awaitUninterruptibly(entered, release));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      Thread closer = Thread.ofPlatform().daemon().start(registration::close);
      assertTrue(awaitClosedRow(registry, "restartable"));
      assertEquals(Reason.INSTANCE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
          () -> registry.register(spec)).reason());
      release.countDown();
      closer.join(1_000);
      assertFalse(closer.isAlive());
      assertEquals("restartable", registry.register(spec).spec().name());
    }
  }

  @Test
  void wedgedClosedOwnerRemainsVisibleAndRefusesSameNameAfterCloseDeadline() throws Exception {
    var registry = registry(1, 1, 1, 1, 4, 4, Duration.ofMillis(20));
    EngineExecutorSpec spec =
        new EngineExecutorSpec("wedged-owner", Kind.BACKGROUND, Mode.PLATFORM, 1, 0, 1);
    var registration = registry.register(spec);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var executor = registration.open(DAEMON);
    executor.submit(() -> awaitUninterruptibly(entered, release));
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    registration.close();
    var row = registry.snapshot().registrations().getFirst();
    assertTrue(row.closed());
    assertEquals(1, row.liveInstances());
    assertEquals(Reason.INSTANCE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
        () -> registry.register(spec)).reason());
    release.countDown();
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    assertEquals("wedged-owner", registry.register(spec).spec().name());
    registry.close();
  }

  @Test
  void shutdownButRunningInstanceRetainsCapUntilActualExit() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 4, 4)) {
      var registration = registry.register(
          new EngineExecutorSpec("replaceable", Kind.BACKGROUND, Mode.PLATFORM, 1, 0, 1));
      var first = registration.open(DAEMON);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      first.submit(() -> awaitUninterruptibly(entered, release));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      first.shutdownNow();
      assertEquals(Reason.INSTANCE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
          () -> registration.open(DAEMON)).reason());
      var row = registry.snapshot().registrations().getFirst();
      assertEquals(1, row.liveInstances());
      assertEquals(1, row.shutdownInstances());
      release.countDown();
      assertTrue(first.awaitTermination(1, TimeUnit.SECONDS));
      var replacement = registration.open(DAEMON);
      assertEquals(1, replacement.submit(() -> 1).get());
    }
  }

  @Test
  void registryCloseUsesOneAggregateDeadlineAndKeepsStillAliveInstancesVisible() throws Exception {
    var registry = registry(1, 1, 1, 1, 4, 4, Duration.ofMillis(100));
    var registration = registry.register(
        new EngineExecutorSpec("wedged", Kind.BACKGROUND, Mode.PLATFORM, 1, 0, 6));
    CountDownLatch entered = new CountDownLatch(6);
    CountDownLatch release = new CountDownLatch(1);
    List<java.util.concurrent.ExecutorService> instances = new ArrayList<>();
    try {
      for (int index = 0; index < 6; index++) {
        var executor = registration.open(DAEMON);
        instances.add(executor);
        executor.submit(() -> awaitUninterruptibly(entered, release));
      }
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      long start = System.nanoTime();
      registry.close();
      // Six independent 100ms waits would exceed this bound; one shared deadline has 400ms slack.
      assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(500)) < 0);
      var row = registry.snapshot().registrations().getFirst();
      assertEquals(6, row.liveInstances());
      assertEquals(6, row.shutdownInstances());
    } finally {
      release.countDown();
      registry.close();
      for (var instance : instances) assertTrue(instance.awaitTermination(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void scheduledBoundsDistinguishLocalQueueAndProcessTimerLimits() {
    try (var local = registry(1, 1, 1, 2, 4, 4)) {
      var registration = local.register(
          new EngineExecutorSpec("local-timers", Kind.BACKGROUND, Mode.SCHEDULED, 1, 1, 1));
      var scheduler = registration.openScheduled(DAEMON);
      scheduler.schedule(() -> {}, 1, TimeUnit.DAYS);
      assertEquals(Reason.QUEUE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
          () -> scheduler.schedule(() -> {}, 1, TimeUnit.DAYS)).reason());
    }
    try (var global = registry(1, 2, 1, 2, 4, 1)) {
      var first = global.register(
          new EngineExecutorSpec("timer-a", Kind.BACKGROUND, Mode.SCHEDULED, 1, 2, 1));
      var second = global.register(
          new EngineExecutorSpec("timer-b", Kind.BACKGROUND, Mode.SCHEDULED, 1, 2, 1));
      first.openScheduled(DAEMON).schedule(() -> {}, 1, TimeUnit.DAYS);
      var scheduler = second.openScheduled(DAEMON);
      assertEquals(Reason.TIMER_LIMIT, assertThrows(EngineExecutorRejectedException.class,
          () -> scheduler.schedule(() -> {}, 1, TimeUnit.DAYS)).reason());
    }
  }

  @Test
  void oneShotReleasesPermitBeforeCallbackCanRearm() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 4, 1)) {
      var registration = registry.register(
          new EngineExecutorSpec("rearm", Kind.BACKGROUND, Mode.SCHEDULED, 1, 1, 1));
      var scheduler = registration.openScheduled(DAEMON);
      CountDownLatch rearmed = new CountDownLatch(1);
      scheduler.execute(() -> scheduler.execute(rearmed::countDown));
      assertTrue(rearmed.await(1, TimeUnit.SECONDS));
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void periodicFailureCancellationAndShutdownReleasePermits() throws Exception {
    try (var registry = registry(1, 3, 1, 3, 4, 3)) {
      var registration = registry.register(
          new EngineExecutorSpec("periodic", Kind.BACKGROUND, Mode.SCHEDULED, 1, 3, 1));
      var scheduler = registration.openScheduled(DAEMON);
      ScheduledFuture<?> failed = scheduler.scheduleAtFixedRate(
          () -> { throw new IllegalStateException("expected"); }, 0, 1, TimeUnit.DAYS);
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> failed.get(1, TimeUnit.SECONDS));
      // Future completion precedes the wrapper's finally; the next task proves actual drain.
      scheduler.submit(() -> {}).get(1, TimeUnit.SECONDS);
      assertEquals(0, registry.snapshot().timerRegistrations());
      ScheduledFuture<?> cancelled = scheduler.scheduleAtFixedRate(() -> {}, 1, 1, TimeUnit.DAYS);
      assertTrue(cancelled.cancel(false));
      assertEquals(0, registry.snapshot().timerRegistrations());
      scheduler.scheduleAtFixedRate(() -> {}, 1, 1, TimeUnit.DAYS);
      scheduler.shutdown();
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void throwingThreadFactoryReleasesOnlyTheFailedScheduledSubmission() {
    try (var registry = registry(2, 3, 1, 1, 4, 3)) {
      var registration = registry.register(
          new EngineExecutorSpec("factory-failure", Kind.FOREGROUND, Mode.SCHEDULED, 2, 3, 1));
      AtomicInteger creations = new AtomicInteger();
      ThreadFactory failsSecond = runnable -> {
        if (creations.incrementAndGet() == 2) throw new IllegalStateException("factory failed");
        return DAEMON.newThread(runnable);
      };
      var scheduler = registration.openScheduled(failsSecond);
      ScheduledFuture<?> accepted = scheduler.schedule(() -> {}, 1, TimeUnit.DAYS);
      assertThrows(IllegalStateException.class,
          () -> scheduler.schedule(() -> {}, 1, TimeUnit.DAYS));
      assertEquals(1, registry.snapshot().timerRegistrations());
      assertEquals(1, registry.snapshot().registrations().getFirst().queuedTasks());
      assertTrue(accepted.cancel(false));
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void reentrantFactoryFailurePreservesOnlyTheAcceptedNestedTimer() {
    try (var registry = registry(2, 3, 1, 1, 4, 3)) {
      var registration = registry.register(
          new EngineExecutorSpec("reentrant-factory", Kind.FOREGROUND, Mode.SCHEDULED, 2, 3, 1));
      AtomicInteger creations = new AtomicInteger();
      AtomicReference<java.util.concurrent.ScheduledExecutorService> schedulerRef = new AtomicReference<>();
      AtomicReference<ScheduledFuture<?>> nested = new AtomicReference<>();
      var scheduler = registration.openScheduled(runnable -> {
        if (creations.incrementAndGet() == 1) {
          nested.set(schedulerRef.get().schedule(() -> {}, 1, TimeUnit.DAYS));
          throw new IllegalStateException("outer factory failed after nested submission");
        }
        return DAEMON.newThread(runnable);
      });
      schedulerRef.set(scheduler);
      assertThrows(IllegalStateException.class,
          () -> scheduler.schedule(() -> {}, 1, TimeUnit.DAYS));
      assertEquals(1, registry.snapshot().timerRegistrations());
      assertEquals(1, registry.snapshot().registrations().getFirst().queuedTasks());
      assertTrue(nested.get().cancel(false));
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void scheduledTerminationRacingRegistrationCloseDoesNotDeadlockOrLeak() throws Exception {
    try (var registry = registry(1, 2, 1, 1, 4, 2)) {
      var registration = registry.register(
          new EngineExecutorSpec("termination-close", Kind.FOREGROUND, Mode.SCHEDULED, 1, 2, 1));
      var scheduler = registration.openScheduled(DAEMON);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      scheduler.execute(() -> awaitUninterruptibly(entered, release));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      Thread closer = Thread.ofPlatform().daemon().start(registration::close);
      release.countDown();
      closer.join(1_000);
      assertFalse(closer.isAlive());
      assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void concurrentScheduleCancelAndShutdownDoesNotLeakTimerCredits() throws Exception {
    try (var registry = registry(2, 16, 1, 16, 8, 16)) {
      var registration = registry.register(
          new EngineExecutorSpec("race", Kind.FOREGROUND, Mode.SCHEDULED, 2, 16, 1));
      var scheduler = registration.openScheduled(DAEMON);
      CountDownLatch start = new CountDownLatch(1);
      ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
      List<Thread> racers = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        racers.add(Thread.ofPlatform().daemon().start(() -> {
          awaitUninterruptibly(null, start);
          for (int attempt = 0; attempt < 100; attempt++) {
            try {
              scheduler.schedule(() -> {}, 1, TimeUnit.DAYS).cancel(attempt % 2 == 0);
            } catch (EngineExecutorRejectedException expected) {
              if (expected.reason() != Reason.QUEUE_LIMIT
                  && expected.reason() != Reason.TIMER_LIMIT && expected.reason() != Reason.CLOSED) {
                unexpected.add(new AssertionError("Unexpected refusal: " + expected.reason()));
              }
            } catch (Throwable failure) {
              unexpected.add(failure);
            }
          }
        }));
      }
      Thread closer = Thread.ofPlatform().daemon().start(() -> {
        awaitUninterruptibly(null, start);
        registration.close();
      });
      start.countDown();
      for (Thread racer : racers) racer.join();
      closer.join();
      assertTrue(unexpected.isEmpty(), () -> unexpected.toString());
      assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(0, registry.snapshot().timerRegistrations());
    }
  }

  @Test
  void virtualOpeningIsRegistryNamedAndInstanceBounded() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 3, 3)) {
      var registration = registry.register(EngineExecutorSpec.virtual("virtual-search", Kind.FOREGROUND, 1));
      var executor = registration.openVirtual();
      Thread thread = executor.submit(Thread::currentThread).get();
      assertTrue(thread.isVirtual());
      assertTrue(thread.getName().startsWith("virtual-search-"));
      assertEquals(Reason.INSTANCE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
          registration::openVirtual).reason());
      assertThrows(IllegalStateException.class, () -> registration.open(DAEMON));
    }
  }

  @Test
  void cancelledFanoutGroupRetainsVirtualInstanceCapacityUntilActualExit() throws Exception {
    try (var registry = registry(1, 1, 1, 1, 3, 3)) {
      var registration = registry.register(EngineExecutorSpec.virtual("owned-fanout", Kind.FOREGROUND, 1));
      var executor = registration.openVirtual();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var ownerReleased = new CountDownLatch(1);
      try (var group = io.justsearch.core.execution.EngineTaskGroup.open(
          () -> executor, () -> ownerReleased::countDown)) {
        group.submit(() -> { awaitUninterruptibly(entered, release); return null; });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        group.close();
        assertEquals(1L, ownerReleased.getCount());
        assertEquals(1, registry.snapshot().registrations().getFirst().liveInstances());
        assertEquals(Reason.INSTANCE_LIMIT, assertThrows(EngineExecutorRejectedException.class,
            registration::openVirtual).reason());
        release.countDown();
        assertTrue(ownerReleased.await(1, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        try (var replacement = registration.openVirtual()) {
          assertTrue(replacement.submit(() -> Thread.currentThread().isVirtual()).get());
        }
      } finally { release.countDown(); }
    }
  }

  @Test
  void bothClosePathsCancelQueuedFuturesAndRetainActuallyRunningInstances() throws Exception {
    for (boolean wholeRegistry : new boolean[] { false, true }) {
      var registry = registry(1, 2, 1, 2, 4, 4, Duration.ofMillis(20));
      var registration = registry.register(new EngineExecutorSpec(
          "queued-close", Kind.BACKGROUND, Mode.PLATFORM, 1, 2, 1));
      var executor = registration.open(DAEMON);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try {
        executor.submit(() -> awaitUninterruptibly(entered, release));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var queued = executor.submit(() -> {});
        if (wholeRegistry) registry.close(); else registration.close();
        assertTrue(queued.isDone());
        assertTrue(queued.isCancelled());
        var row = registry.snapshot().registrations().getFirst();
        assertEquals(1, row.liveInstances());
        assertEquals(1, row.shutdownInstances());
        assertEquals(0, row.queuedTasks());
      } finally {
        release.countDown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        registry.close();
      }
    }
  }

  @Test
  void scheduledWrappersPreserveReflexivityAndDelegateDeadlineOrder() throws Exception {
    try (var registry = registry(1, 8, 1, 8, 4, 8)) {
      var registration = registry.register(new EngineExecutorSpec(
          "timer-order", Kind.BACKGROUND, Mode.SCHEDULED, 1, 8, 1));
      var executor = registration.openScheduled(DAEMON);
      var first = executor.schedule(() -> {}, 1, TimeUnit.DAYS);
      var second = executor.schedule(() -> {}, 2, TimeUnit.DAYS);
      assertEquals(0, first.compareTo(first));
      assertEquals(0, second.compareTo(second));
      assertTrue(first.compareTo(second) < 0);
      assertTrue(second.compareTo(first) > 0);
      first.cancel(false);
      second.cancel(false);
      var order = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      executor.execute(() -> awaitUninterruptibly(entered, release));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      var futures = new ArrayList<ScheduledFuture<?>>();
      try {
        for (int i = 0; i < 5; i++) {
          int value = i;
          futures.add(executor.schedule(() -> order.add(value), 0, TimeUnit.NANOSECONDS));
        }
      } finally {
        release.countDown();
      }
      for (var future : futures) future.get(1, TimeUnit.SECONDS);
      assertEquals(List.of(0, 1, 2, 3, 4), order);
    }
  }

  @Test
  void registryCloseSettlesOwnedAsyncResultWhoseQueuedTaskNeverRan() throws Exception {
    try (var registry = registry(1, 2, 1, 2, 4, 4)) {
      var registration = registry.register(new EngineExecutorSpec(
          "queued-stage", Kind.BACKGROUND, Mode.PLATFORM, 1, 2, 1));
      var executor = registration.open(DAEMON);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var invoked = new AtomicInteger();
      try {
        executor.submit(() -> { entered.countDown(); release.await(); return null; });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var queued = io.justsearch.core.execution.EngineFutures.supplyAsync(
            invoked::incrementAndGet, executor);
        registration.close();
        assertThrows(java.util.concurrent.CancellationException.class,
            () -> queued.get(1, TimeUnit.SECONDS));
        assertEquals(0, invoked.get());
        assertThrows(EngineExecutorRejectedException.class,
            () -> io.justsearch.core.execution.EngineFutures.supplyAsync(() -> 1, executor));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void callerCancellationStopsQueuedSupplierBeforeBlockingCompletionDependentRuns() throws Exception {
    try (var registry = registry(1, 3, 1, 3, 4, 4)) {
      var registration = registry.register(new EngineExecutorSpec(
          "cancel-callback-order", Kind.BACKGROUND, Mode.PLATFORM, 1, 3, 1));
      var executor = registration.open(DAEMON);
      var occupied = new CountDownLatch(1);
      var releaseWorker = new CountDownLatch(1);
      var dependentEntered = new CountDownLatch(1);
      var releaseDependent = new CountDownLatch(1);
      var invoked = new AtomicInteger();
      Thread canceller = null;
      try {
        executor.execute(() -> awaitUninterruptibly(occupied, releaseWorker));
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        var stage = io.justsearch.core.execution.EngineFutures.supplyAsync(invoked::incrementAndGet, executor);
        stage.whenComplete((value, failure) -> awaitUninterruptibly(dependentEntered, releaseDependent));
        canceller = Thread.ofPlatform().daemon().start(() -> stage.cancel(false));
        assertTrue(dependentEntered.await(1, TimeUnit.SECONDS));
        releaseWorker.countDown();
        // FIFO sentinel proves the worker passed the cancelled task while its dependent is held.
        executor.submit(() -> null).get(1, TimeUnit.SECONDS);
        assertEquals(0, invoked.get());
        assertTrue(stage.isCancelled());
      } finally {
        releaseWorker.countDown();
        releaseDependent.countDown();
        if (canceller != null) canceller.join(1_000);
      }
    }
  }

  private static void awaitUninterruptibly(CountDownLatch entered, CountDownLatch release) {
    if (entered != null) entered.countDown();
    if (release == null) return;
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static boolean awaitClosedRow(
      DefaultEngineExecutorRegistry registry, String name) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      var match = registry.snapshot().registrations().stream()
          .filter(row -> row.spec().name().equals(name)).findFirst();
      if (match.isPresent() && match.get().closed()) return true;
      Thread.onSpinWait();
    }
    return false;
  }

  private static DefaultEngineExecutorRegistry registry(
      int foregroundThreads, int foregroundQueue, int backgroundThreads, int backgroundQueue,
      int aggregate, int timers) {
    return registry(foregroundThreads, foregroundQueue, backgroundThreads, backgroundQueue,
        aggregate, timers, Duration.ofSeconds(1));
  }

  private static DefaultEngineExecutorRegistry registry(
      int foregroundThreads, int foregroundQueue, int backgroundThreads, int backgroundQueue,
      int aggregate, int timers, Duration timeout) {
    Map<String, Integer> execution = new LinkedHashMap<>();
    execution.put("perContextLimit", 2);
    execution.put("aggregateLimit", aggregate);
    execution.put("retryAfterSeconds", 1);
    execution.put("foregroundThreads", foregroundThreads);
    execution.put("foregroundQueue", foregroundQueue);
    execution.put("backgroundThreads", backgroundThreads);
    execution.put("backgroundQueue", backgroundQueue);
    execution.put("timerRegistrations", timers);
    execution.put("directMemoryMiB", 1);
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.copyOf(execution), new RetainedStateBudget()), timeout);
  }
}
