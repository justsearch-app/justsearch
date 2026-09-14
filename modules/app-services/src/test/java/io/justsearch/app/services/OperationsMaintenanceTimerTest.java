/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Coverage for the Head-owned durable operations maintenance scheduler. */
final class OperationsMaintenanceTimerTest {
  @TempDir Path temp;

  @Test
  void existingTickRunsIngestionFirstAndKeepsCheckpointCadenceAfterRefusal() throws Exception {
    CapturingRegistry executors = new CapturingRegistry();
    OperationStore operations = mock(OperationStore.class);
    List<String> order = new ArrayList<>();
    org.mockito.Mockito.doAnswer(invocation -> { order.add("checkpoint"); return null; })
        .when(operations).checkpointDurableOperations();
    var handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {
      order.add("ingestion");
      throw new IllegalStateException("receipt temporarily unavailable");
    });
    try (handle) {
      var registration = executors.registrations.getFirst();
      assertEquals(2, registration.tasks.size(), "reuse checkpoint/retention owner without another timer");
      registration.scheduler.advanceTo(60, TimeUnit.SECONDS);
      assertEquals(List.of("ingestion", "checkpoint", "ingestion", "checkpoint"), order);
    }
  }

  @Test
  void registersCheckpointAndRetentionTasksAndAdvancesThemByDueTime() throws Exception {
    CapturingRegistry executors = new CapturingRegistry();
    OperationStore operations = mock(OperationStore.class);

    AutoCloseable handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {});
    try {
      CapturingRegistration registration = executors.registrations.getFirst();
      assertEquals("head.operations-maintenance", registration.spec.name());
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec.kind());
      assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec.mode());
      assertEquals(1, registration.spec.threadCount());
      assertEquals(37, registration.spec.queueCapacity());
      assertEquals(1, registration.spec.maxInstances());

      assertEquals(2, registration.tasks.size());
      ControlledTask checkpoint = registration.tasks.get(0);
      assertEquals(30, checkpoint.initialDelay);
      assertEquals(30, checkpoint.period);
      assertEquals(TimeUnit.SECONDS, checkpoint.unit);
      assertTrue(checkpoint.fixedRate);
      ControlledTask retention = registration.tasks.get(1);
      assertEquals(1, retention.initialDelay);
      assertEquals(1, retention.period);
      assertEquals(TimeUnit.HOURS, retention.unit);
      assertFalse(retention.fixedRate);

      registration.scheduler.advanceTo(29, TimeUnit.SECONDS);
      verify(operations, never()).checkpointDurableOperations();
      verify(operations, never()).pruneHistory();

      registration.scheduler.advanceTo(30, TimeUnit.SECONDS);
      verify(operations).checkpointDurableOperations();
      verify(operations, never()).checkpoint(anyLong(), anyString(), anyLong(), anyLong());

      registration.scheduler.advanceTo(60, TimeUnit.SECONDS);
      verify(operations, times(2)).checkpointDurableOperations();
      verify(operations, never()).checkpoint(anyLong(), anyString(), anyLong(), anyLong());

      registration.scheduler.advanceTo(3599, TimeUnit.SECONDS);
      verify(operations, never()).pruneHistory();
      registration.scheduler.advanceTo(3600, TimeUnit.SECONDS);
      verify(operations).pruneHistory();
    } finally {
      handle.close();
    }

    CapturingRegistration registration = executors.registrations.getFirst();
    assertTrue(registration.tasks.get(0).isCancelled());
    assertTrue(registration.tasks.get(1).isCancelled());
    assertTrue(registration.scheduler.isShutdown());
    assertTrue(registration.scheduler.isTerminated());
    assertTrue(registration.closed);
    assertFalse(executors.closed);
    executors.close();
  }

  @Test
  void capturedCheckpointRunnableUpdatesTheRealDurableIngestRowByDueTime() throws Exception {
    Path path = temp.resolve("operations.db");
    Clock systemClock = Clock.systemUTC();
    EngineContext context =
        new EngineContext(
            EngineContext.ClientKind.INTERNAL,
            "maintenance-test",
            Optional.empty(),
            Optional.empty(),
            "system",
            "SYSTEM_INTERNAL",
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND);
    String key = OperationKeys.generate(systemClock);
    try (var operations = new SqliteOperationStore(path)) {
      var accepted =
          operations
              .accept(
                  key,
                  OperationDescriptor.invocation(
                      OperationKind.INGEST, "core.ingest", "{}", false),
                  context,
                  null)
              .record();
      assertTrue(operations.start(accepted.id()));
      assertTrue(operations.checkpoint(accepted.id(), "unit-1", 1, 0));
      var original = operations.find(key).orElseThrow();
      setUpdatedAt(path, accepted.id(), 1);

      CapturingRegistry executors = new CapturingRegistry();
      AutoCloseable handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {});
      try {
        var scheduler = executors.registrations.getFirst().scheduler;
        scheduler.advanceTo(29, TimeUnit.SECONDS);
        var beforeDue = operations.find(key).orElseThrow();
        assertEquals(1, beforeDue.updatedAt());
        assertSameDurableProgress(original, beforeDue);

        scheduler.advanceTo(30, TimeUnit.SECONDS);
        var firstCheckpoint = operations.find(key).orElseThrow();
        assertSameDurableProgress(original, firstCheckpoint);
        long firstStamp = firstCheckpoint.updatedAt();
        assertTrue(firstStamp > 1);

        setUpdatedAt(path, accepted.id(), 1);
        scheduler.advanceTo(60, TimeUnit.SECONDS);
        var secondCheckpoint = operations.find(key).orElseThrow();
        assertTrue(secondCheckpoint.updatedAt() > 1);
        assertSameDurableProgress(original, secondCheckpoint);
      } finally {
        handle.close();
      }
    }
  }

  @Test
  void logsCheckpointFailureAtErrorAndRetriesOnTheNextDueTick() throws Exception {
    OperationStore operations = mock(OperationStore.class);
    doThrow(new IllegalStateException("checkpoint unavailable"))
        .doNothing()
        .when(operations)
        .checkpointDurableOperations();
    CapturingRegistry executors = new CapturingRegistry();
    Logger logger = (Logger) LoggerFactory.getLogger(HeadAssembly.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    AutoCloseable handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {});
    try {
      CapturingRegistration registration = executors.registrations.getFirst();
      registration.scheduler.advanceTo(30, TimeUnit.SECONDS);
      registration.scheduler.advanceTo(60, TimeUnit.SECONDS);
    } finally {
      handle.close();
      logger.detachAppender(appender);
    }

    verify(operations, times(2)).checkpointDurableOperations();
    assertEquals(
        1,
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .filter(
                event ->
                    event.getFormattedMessage().contains("Operations checkpoint failed"))
            .count());
  }

  @Test
  void logsPruneFailureAtErrorAndLeavesTheCapturedRetentionTaskAbleToRetry()
      throws Exception {
    OperationStore operations = mock(OperationStore.class);
    doThrow(new IllegalStateException("retention unavailable"))
        .doNothing()
        .when(operations)
        .pruneHistory();
    Logger logger = (Logger) LoggerFactory.getLogger(HeadAssembly.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    CapturingRegistry executors = new CapturingRegistry();
    AutoCloseable handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {});
    try {
      CapturingRegistration registration = executors.registrations.getFirst();
      registration.scheduler.advanceTo(3600, TimeUnit.SECONDS);
      verify(operations).pruneHistory();
      registration.scheduler.advanceTo(7200, TimeUnit.SECONDS);
    } finally {
      handle.close();
      logger.detachAppender(appender);
    }

    verify(operations, times(2)).pruneHistory();
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
  void propagatesFatalErrorsFromCapturedCheckpointRunnable() throws Exception {
    OperationStore operations = mock(OperationStore.class);
    AssertionError fatal = new AssertionError("fatal checkpoint failure");
    doThrow(fatal).when(operations).checkpointDurableOperations();
    CapturingRegistry executors = new CapturingRegistry();
    AutoCloseable handle = HeadAssembly.startOperationsMaintenanceTimer(operations, executors, () -> {});
    try {
      CapturingRegistration registration = executors.registrations.getFirst();
      assertSame(
          fatal,
          assertThrows(
              AssertionError.class,
              () -> registration.scheduler.advanceTo(30, TimeUnit.SECONDS)));
    } finally {
      handle.close();
    }
  }

  @Test
  void closeCancelsBothTasksAndRemainsRetryableAfterTimeoutAndInterruption() {
    CapturingRegistry timeoutRegistry = new CapturingRegistry();
    timeoutRegistry.timeoutFirst = true;
    AutoCloseable timeoutHandle =
        HeadAssembly.startOperationsMaintenanceTimer(mock(OperationStore.class), timeoutRegistry, () -> {});
    CapturingRegistration timeoutRegistration = timeoutRegistry.registrations.getFirst();
    assertTrue(
        assertThrows(IllegalStateException.class, timeoutHandle::close)
            .getMessage()
            .contains("did not terminate"));
    assertTrue(timeoutRegistration.tasks.get(0).isCancelled());
    assertTrue(timeoutRegistration.tasks.get(1).isCancelled());
    assertFalse(timeoutRegistration.closed);
    assertTrue(timeoutRegistration.scheduler.isShutdown());
    assertDoesNotThrow(timeoutHandle::close);
    assertTrue(timeoutRegistration.closed);

    CapturingRegistry interruptionRegistry = new CapturingRegistry();
    interruptionRegistry.interruptFirst = true;
    AutoCloseable interruptionHandle =
        HeadAssembly.startOperationsMaintenanceTimer(
            mock(OperationStore.class), interruptionRegistry, () -> {});
    CapturingRegistration interruptionRegistration =
        interruptionRegistry.registrations.getFirst();
    assertTrue(
        assertThrows(IllegalStateException.class, interruptionHandle::close)
            .getMessage()
            .contains("Interrupted"));
    assertTrue(interruptionRegistration.tasks.get(0).isCancelled());
    assertTrue(interruptionRegistration.tasks.get(1).isCancelled());
    assertFalse(interruptionRegistration.closed);
    assertTrue(Thread.interrupted(), "the failed close must preserve interruption");
    assertDoesNotThrow(interruptionHandle::close);
    assertTrue(interruptionRegistration.closed);
  }

  private static void setUpdatedAt(Path path, long id, long updatedAt) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        var statement = connection.prepareStatement(
            "UPDATE operations SET updated_at = ? WHERE id = ?")) {
      statement.setLong(1, updatedAt);
      statement.setLong(2, id);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void assertSameDurableProgress(OperationRecord expected, OperationRecord actual) {
    assertEquals(expected.checkpointCursor(), actual.checkpointCursor());
    assertEquals(expected.unitsCompleted(), actual.unitsCompleted());
    assertEquals(expected.unitsFailed(), actual.unitsFailed());
    assertEquals(expected.state(), actual.state());
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

  private static final class CapturingRegistration
      implements EngineExecutorRegistry.Registration {
    private final EngineExecutorSpec spec;
    private final List<ControlledTask> tasks = new ArrayList<>();
    private ControlledScheduler scheduler;
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
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory factory) {
      scheduler = new ControlledScheduler(this);
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

  private static final class ControlledTask implements ScheduledFuture<Object> {
    private final Runnable command;
    private final boolean fixedRate;
    private final long periodNanos;
    private final long initialDelay;
    private final long period;
    private final TimeUnit unit;
    private long nextDueNanos;
    private boolean cancelled;

    private ControlledTask(
        Runnable command, long initialDelay, long period, TimeUnit unit, boolean fixedRate) {
      this.command = command;
      this.initialDelay = initialDelay;
      this.period = period;
      this.unit = unit;
      this.fixedRate = fixedRate;
      this.periodNanos = unit.toNanos(period);
      this.nextDueNanos = unit.toNanos(initialDelay);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled;
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getDelay(TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ControlledScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {
    private final CapturingRegistration owner;
    private long nowNanos;
    private int awaitCalls;
    private boolean shutdown;

    private ControlledScheduler(CapturingRegistration owner) {
      this.owner = owner;
    }

    @Override
    public void execute(Runnable command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(
        java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      ControlledTask task = new ControlledTask(command, initialDelay, period, unit, true);
      owner.tasks.add(task);
      return task;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      ControlledTask task = new ControlledTask(command, initialDelay, delay, unit, false);
      owner.tasks.add(task);
      return task;
    }

    private void advanceTo(long amount, TimeUnit unit) {
      long targetNanos = unit.toNanos(amount);
      while (true) {
        ControlledTask next = null;
        for (ControlledTask task : owner.tasks) {
          if (!task.cancelled && task.nextDueNanos <= targetNanos
              && (next == null || task.nextDueNanos < next.nextDueNanos)) {
            next = task;
          }
        }
        if (next == null) {
          break;
        }
        nowNanos = next.nextDueNanos;
        next.command.run();
        next.nextDueNanos =
            next.fixedRate ? next.nextDueNanos + next.periodNanos : nowNanos + next.periodNanos;
      }
      nowNanos = targetNanos;
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      owner.tasks.forEach(task -> task.cancel(true));
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (awaitCalls++ == 0 && owner.timeoutFirst) {
        return false;
      }
      if (awaitCalls == 1 && owner.interruptFirst) {
        throw new InterruptedException("test interruption");
      }
      return true;
    }
  }
}
