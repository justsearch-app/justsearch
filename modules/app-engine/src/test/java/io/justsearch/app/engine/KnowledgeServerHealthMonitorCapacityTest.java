/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerHealthMonitor;
import io.justsearch.app.services.worker.WorkerRecoveryAuthority.Verdict;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real global timer capacity, including competition while a health callback is running. */
@Timeout(15)
final class KnowledgeServerHealthMonitorCapacityTest {
  @Test
  void competingTimerDuringHealthCheckCannotPermanentlyStopPolling() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var nextTick = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(true), 20)) {
      var other = otherScheduler(registry);
      monitor.onTick(() -> {
        if (ticks.incrementAndGet() == 1) {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release tick");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
        } else nextTick.countDown();
      });
      try {
        monitor.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        // Old one-shot scheduling releases its permit before entering the callback: the other
        // owner can then retain it forever, preventing the health monitor from re-arming.
        try {
          var _ = other.schedule(() -> {}, 1, TimeUnit.DAYS);
        } catch (EngineExecutorRejectedException refusal) {
          assertEquals(EngineExecutorRejectedException.Reason.TIMER_LIMIT, refusal.reason());
        }
        release.countDown();
        assertTrue(nextTick.await(3, TimeUnit.SECONDS), "health polling must survive timer contention");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void retainedPulseHonorsChangingSampleDelay() throws Exception {
    var first = new CountDownLatch(1);
    var second = new CountDownLatch(1);
    var third = new CountDownLatch(1);
    var ticks = new AtomicInteger();
    var delay = new java.util.concurrent.atomic.AtomicLong(1_000);
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(true), 3_000)) {
      monitor.tickIntervalSupplier(delay::get);
      monitor.onTick(() -> {
        switch (ticks.incrementAndGet()) {
          case 1 -> first.countDown();
          case 2 -> { delay.set(3_000); second.countDown(); }
          default -> third.countDown();
        }
      });
      monitor.start();
      assertTrue(first.await(5, TimeUnit.SECONDS));
      assertTrue(second.await(2, TimeUnit.SECONDS), "active sampling uses the shorter supplied delay");
      assertFalse(third.await(1_500, TimeUnit.MILLISECONDS), "idle sampling must not run on every pulse");
      assertTrue(third.await(4, TimeUnit.SECONDS), "the retained pulse must eventually sample idle health");
    }
  }

  @Test
  void startupRetriesCapacityAndBeginsPollingAfterCompetingTimerLeaves() throws Exception {
    var retrySeen = new CompletableFuture<Void>();
    var logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);
    var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
      @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
        if (event.getMessage().startsWith("Health monitor start retained for capacity retry")) {
          retrySeen.complete(null);
        }
      }
    };
    appender.start();
    logger.addAppender(appender);
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(true), 20)) {
      var held = otherScheduler(registry).schedule(() -> {}, 1, TimeUnit.DAYS);
      var ticked = new CountDownLatch(1);
      monitor.onTick(ticked::countDown);
      var starting = new FutureTask<Void>(() -> { monitor.start(); return null; });
      Thread caller = Thread.ofVirtual().start(starting);
      try {
        retrySeen.get(2, TimeUnit.SECONDS);
        assertFalse(starting.isDone(), "start must not report success without a timer");
        held.cancel(false);
        starting.get(3, TimeUnit.SECONDS);
        assertTrue(ticked.await(2, TimeUnit.SECONDS));
      } finally {
        caller.interrupt();
        caller.join(2_000);
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void delayedRetryCannotInstallAfterStartupAdmissionDeadline() throws Exception {
    var retrySeen = new CompletableFuture<Void>();
    var resume = new CountDownLatch(1);
    var logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);
    var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
      @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
        if (!event.getMessage().startsWith("Health monitor start retained for capacity retry")) return;
        retrySeen.complete(null);
        try {
          if (!resume.await(8, TimeUnit.SECONDS)) throw new AssertionError("retry pause not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
    };
    appender.start();
    logger.addAppender(appender);
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(true), 20)) {
      var held = otherScheduler(registry).schedule(() -> {}, 1, TimeUnit.DAYS);
      var starting = new FutureTask<Void>(() -> { monitor.start(); return null; });
      Thread caller = Thread.ofVirtual().start(starting);
      try {
        retrySeen.get(2, TimeUnit.SECONDS);
        // Pause after the old pre-sleep deadline check. Capacity becomes free only after the
        // five-second window has elapsed; the resumed caller must not install a late timer.
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> starting.get(5_100, TimeUnit.MILLISECONDS));
        held.cancel(false);
        resume.countDown();
        var failed = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> starting.get(3, TimeUnit.SECONDS));
        assertEquals(EngineExecutorRejectedException.Reason.TIMER_LIMIT,
            ((EngineExecutorRejectedException) failed.getCause()).reason());
        assertEquals(0, registry.snapshot().timerRegistrations(), "expired startup must not install");
      } finally {
        resume.countDown();
        caller.interrupt();
        caller.join(2_000);
      }
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void exhaustedStartupIsVisibleBoundedAndCanBeRetried() throws Exception {
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(true), 20)) {
      var held = otherScheduler(registry).schedule(() -> {}, 1, TimeUnit.DAYS);
      long before = System.nanoTime();
      var refusal = assertThrows(EngineExecutorRejectedException.class, monitor::start);
      assertEquals(EngineExecutorRejectedException.Reason.TIMER_LIMIT, refusal.reason());
      assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(6));
      held.cancel(false);
      var ticked = new CountDownLatch(1);
      monitor.onTick(ticked::countDown);
      monitor.start();
      assertTrue(ticked.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void manualCapacityRefusalKeepsRetryAfterAndReleasesAttemptSlot() throws Exception {
    var bootstrap = bootstrap(false);
    var attempted = new CountDownLatch(1);
    doAnswer(invocation -> { attempted.countDown(); return null; }).when(bootstrap).startForRecovery();
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap, 10_000)) {
      var held = otherScheduler(registry).schedule(() -> {}, 1, TimeUnit.DAYS);
      var refusal = assertThrows(EngineAdmissionException.class, monitor::requestRecoveryNow);
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refusal.reason());
      assertEquals(1, refusal.retryAfterSeconds());
      assertEquals(EngineExecutorRejectedException.Reason.TIMER_LIMIT,
          ((EngineExecutorRejectedException) refusal.getCause()).reason());
      held.cancel(false);
      assertEquals(Verdict.ACCEPTED, monitor.requestRecoveryNow(), "failed handoff must release attempt slot");
      assertTrue(attempted.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void closedExecutorDoesNotClaimRecoveryOrSuccessfulStartup() {
    try (var registry = registry();
        var monitor = new KnowledgeServerHealthMonitor(registry, bootstrap(false), 20)) {
      registry.close();
      assertEquals(EngineExecutorRejectedException.Reason.CLOSED,
          assertThrows(EngineExecutorRejectedException.class, monitor::start).reason());
      assertEquals(Verdict.NOT_APPLICABLE, monitor.requestRecoveryNow());
    }
  }

  private static KnowledgeServerBootstrap bootstrap(boolean hasClient) {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.hasClient()).thenReturn(hasClient);
    when(bootstrap.workerCapability()).thenReturn(new WorkerCapability());
    return bootstrap;
  }

  private static ScheduledExecutorService otherScheduler(DefaultEngineExecutorRegistry registry) {
    return registry.register(new EngineExecutorSpec("competing-health-timer", Kind.BACKGROUND,
        Mode.SCHEDULED, 1, 4, 1)).openScheduled(Thread.ofPlatform().daemon().factory());
  }

  private static DefaultEngineExecutorRegistry registry() {
    return new DefaultEngineExecutorRegistry(new EngineResourcePolicy(Map.of(
        "perContextLimit", 8, "aggregateLimit", 8, "retryAfterSeconds", 1,
        "foregroundThreads", 1, "foregroundQueue", 1, "backgroundThreads", 1,
        "backgroundQueue", 4, "timerRegistrations", 1, "directMemoryMiB", 1),
        new RetainedStateBudget()), Duration.ofSeconds(1));
  }
}
