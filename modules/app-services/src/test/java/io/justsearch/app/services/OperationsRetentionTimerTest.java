/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Coverage for the Head-owned durable operations history retention timer. */
final class OperationsRetentionTimerTest {

  @Test
  void registersOneHourlyBackgroundTimerAndClosesItsOwnedResources() throws Exception {
    CapturingRegistry executors = new CapturingRegistry();
    OperationStore operations = mock(OperationStore.class);

    AutoCloseable handle = HeadAssembly.startOperationsRetentionTimer(operations, executors);
    try {
      CapturingRegistration registration = executors.registrations.getFirst();
      assertEquals("head.operations-retention", registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec.mode());
      assertEquals(1, registration.spec.threadCount());
      assertEquals(37, registration.spec.queueCapacity());
      assertEquals(1, registration.spec.maxInstances());
      assertEquals(1, registration.initialDelay);
      assertEquals(1, registration.period);
      assertEquals(TimeUnit.HOURS, registration.unit);

      registration.tick.run();
      verify(operations).pruneHistory();
    } finally {
      handle.close();
    }

    CapturingRegistration registration = executors.registrations.getFirst();
    assertTrue(registration.task.isCancelled());
    assertTrue(registration.scheduler.isShutdown());
    assertTrue(registration.scheduler.isTerminated());
    assertTrue(registration.closed);
    assertFalse(executors.closed);
    executors.close();
  }

  @Test
  void logsPruneFailureAtErrorAndLeavesTheTimerAbleToRetry() {
    OperationStore operations = mock(OperationStore.class);
    doThrow(new IllegalStateException("retention unavailable"))
        .doNothing()
        .when(operations)
        .pruneHistory();
    Logger logger = (Logger) LoggerFactory.getLogger(HeadAssembly.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      HeadAssembly.pruneOperationsHistory(operations);
      HeadAssembly.pruneOperationsHistory(operations);
    } finally {
      logger.detachAppender(appender);
    }

    verify(operations, org.mockito.Mockito.times(2)).pruneHistory();
    assertEquals(
        1,
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .filter(
                event ->
                    event.getFormattedMessage().contains("Operations history retention failed"))
            .count());
  }

  @Test
  void closeRemainsRetryableAfterTimeoutAndInterruption() {
    CapturingRegistry timeoutRegistry = new CapturingRegistry();
    timeoutRegistry.timeoutFirst = true;
    AutoCloseable timeoutHandle =
        HeadAssembly.startOperationsRetentionTimer(mock(OperationStore.class), timeoutRegistry);
    assertTrue(
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, timeoutHandle::close)
            .getMessage()
            .contains("did not terminate"));
    assertFalse(timeoutRegistry.registrations.getFirst().closed);
    assertTrue(timeoutRegistry.registrations.getFirst().scheduler.isShutdown());
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(timeoutHandle::close);
    assertTrue(timeoutRegistry.registrations.getFirst().closed);

    CapturingRegistry interruptionRegistry = new CapturingRegistry();
    interruptionRegistry.interruptFirst = true;
    AutoCloseable interruptionHandle =
        HeadAssembly.startOperationsRetentionTimer(mock(OperationStore.class), interruptionRegistry);
    assertTrue(
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, interruptionHandle::close)
            .getMessage()
            .contains("Interrupted"));
    assertFalse(interruptionRegistry.registrations.getFirst().closed);
    assertTrue(Thread.interrupted(), "the failed close must preserve interruption");
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(interruptionHandle::close);
    assertTrue(interruptionRegistry.registrations.getFirst().closed);
  }

  private static final class CapturingRegistry implements EngineExecutorRegistry {
    private final List<CapturingRegistration> registrations = new ArrayList<>();
    private boolean closed;
    private boolean timeoutFirst;
    private boolean interruptFirst;

    @Override
    public Registration register(EngineExecutorSpec spec) {
      CapturingRegistration registration = new CapturingRegistration(spec);
      registration.timeoutFirst = timeoutFirst;
      registration.interruptFirst = interruptFirst;
      registrations.add(registration);
      return registration;
    }

    @Override
    public Limits limits(EngineExecutorSpec.Kind kind) {
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, kind);
      return new Limits(4, 37);
    }

    @Override
    public int maxConcurrentWork() {
      return 64;
    }

    @Override
    public int retryAfterSeconds() {
      return 1;
    }

    @Override
    public EngineExecutorSnapshot snapshot() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      registrations.forEach(CapturingRegistration::close);
    }
  }

  private static final class CapturingRegistration implements EngineExecutorRegistry.Registration {
    private final EngineExecutorSpec spec;
    private RecordingScheduledExecutor scheduler;
    private ScheduledFuture<?> task;
    private Runnable tick;
    private long initialDelay;
    private long period;
    private TimeUnit unit;
    private boolean closed;
    private boolean timeoutFirst;
    private boolean interruptFirst;

    private CapturingRegistration(EngineExecutorSpec spec) {
      this.spec = spec;
    }

    @Override
    public EngineExecutorSpec spec() {
      return spec;
    }

    @Override
    public ExecutorService open(ThreadFactory factory) {
      return Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      scheduler = new RecordingScheduledExecutor(factory, this);
      return scheduler;
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      closed = true;
      if (scheduler != null) {
        scheduler.shutdownNow();
      }
    }
  }

  private static final class RecordingScheduledExecutor extends ScheduledThreadPoolExecutor {
    private final CapturingRegistration owner;
    private int awaitCalls;

    private RecordingScheduledExecutor(ThreadFactory factory, CapturingRegistration owner) {
      super(1, factory);
      this.owner = owner;
      setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      owner.initialDelay = initialDelay;
      owner.tick = command;
      owner.period = delay;
      owner.unit = unit;
      owner.task = super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
      return owner.task;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (awaitCalls++ == 0 && owner.timeoutFirst) {
        return false;
      }
      if (awaitCalls == 1 && owner.interruptFirst) {
        throw new InterruptedException("test interruption");
      }
      return super.awaitTermination(timeout, unit);
    }
  }
}
