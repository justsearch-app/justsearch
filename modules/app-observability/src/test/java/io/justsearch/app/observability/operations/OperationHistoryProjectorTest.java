/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.ledger.*;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationHistoryProjectorTest {
  @TempDir Path directory;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "history-projector", Optional.empty(), Optional.empty(), "SYSTEM", "AGENT_LOOP",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);

  private SqliteOperationStore open() throws Exception {
    return new SqliteOperationStore(directory.resolve("operations.db"), clock, step -> {});
  }

  private OperationAttemptRunner.PreparedAttempt accept(OperationAttemptRunner runner, OperationKind kind, OperationHistoryMode mode) {
    return runner.accept(new OperationAttemptRunner.Request(OperationKeys.generate(clock),
        OperationDescriptor.invocation(kind, "core.test-history", "{}", false), CONTEXT,
        InvocationProvenance.fromEngineContext(CONTEXT, ExecutorTag.AGENT, clock.instant(), Optional.empty()), mode));
  }

  private void complete(OperationAttemptRunner runner, OperationAttemptRunner.PreparedAttempt attempt) {
    runner.start(attempt, handle -> OperationExecution.finished(OperationResult.success("done")));
  }

  private static final class Timer {
    final EngineExecutorRegistry executors = mock(EngineExecutorRegistry.class);
    final EngineExecutorRegistry.Registration registration = mock(EngineExecutorRegistry.Registration.class);
    final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private Runnable tick;

    Timer() throws Exception {
      when(executors.register(any())).thenReturn(registration);
      when(registration.openScheduled(any())).thenReturn(scheduler);
      when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
      doAnswer(call -> { tick = call.getArgument(0); return mock(ScheduledFuture.class); })
          .when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS));
    }
    void tick() { tick.run(); }
  }

  @Test
  void directProducerMemoryNoteAndPrestartFailurePublishWithoutDispatchOrProducerJournalIo() throws Exception {
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var history = new OperationHistoryStore(store);
      var observed = new CopyOnWriteArrayList<OperationHistoryEntry>();
      history.addAppendListener(observed::add);
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.at(directory.resolve("audit")));
      var timer = new Timer();
      var projector = new OperationHistoryProjector(store, history, new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        for (var kind : List.of(OperationKind.MEMORY, OperationKind.NOTE, OperationKind.OPERATION)) {
          complete(runner, accept(runner, kind, OperationHistoryMode.STANDARD));
        }
        var declined = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
        runner.rejectBeforeStart(declined, "declined");
        runner.rejectBeforeStart(declined, "again");
        complete(runner, accept(runner, OperationKind.MEMORY, OperationHistoryMode.NONE));
        assertEquals(4, observed.size(), "the source hook emits each visible transition once");
        assertEquals(3, ledger.store().recent().size(), "memory/note bypass agent-loop exclusion; generic agent operation does not");
        assertTrue(ledger.journal().tail(10).isEmpty(), "the producer callback must never write/force the journal");
        assertEquals(4, store.pendingHistoryProjection(256).size());
        timer.tick();
        assertTrue(store.pendingHistoryProjection(256).isEmpty());
        assertEquals(3, ledger.journal().tail(10).size());
        assertEquals(4, observed.size(), "durable retry cannot republish history notifications");
      }
      verify(timer.registration).close();
    }
  }

  @Test
  void disabledSinkStartupTraversesBeyondOneBatchWithoutCyclicReplayAndKeepsTheHook() throws Exception {
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var late = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      long earlyTime = clock.millis();
      clock.setMillis(earlyTime + 1);
      for (int i = 0; i < 520; i++) complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled());
      var observed = new CopyOnWriteArrayList<io.justsearch.app.api.stream.SseEnvelope>();
      ledger.subscribe(observed::add);
      var timer = new Timer();
      var projector = new OperationHistoryProjector(store, new OperationHistoryStore(store),
          new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        for (int i = 0; i < 4; i++) timer.tick();
        assertEquals(520, observed.stream().map(frame -> ((java.util.Map<?, ?>) frame.payload()).get("id")).distinct().count());
        int afterStartup = observed.size();
        for (int i = 0; i < 5; i++) timer.tick();
        assertEquals(afterStartup, observed.size(), "startup enumeration must stop instead of cycling past ring eviction");
        assertEquals(256, store.pendingHistoryProjection(1000).size(), "disabled journal never acknowledges source rows");
        clock.setMillis(earlyTime);
        complete(runner, late);
        assertTrue(observed.stream().anyMatch(frame -> ("operation:" + late.accepted().key()).equals(((java.util.Map<?, ?>) frame.payload()).get("id"))),
            "a new completion behind the startup cursor is delivered by the retained subscription");
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void startupCohortEndsWhileNewCompletionsKeepFillingPages(boolean clockRegressed) throws Exception {
    try (var source = open()) {
      var store = spy(source);
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      for (int i = 0; i < 520; i++) complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.disabled());
      var timer = new Timer();
      var projector = new OperationHistoryProjector(store, new OperationHistoryStore(store),
          new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        long startupTime = clock.millis();
        for (int tick = 0; tick < 6; tick++) {
          clock.setMillis(clockRegressed ? startupTime - 10_000 + tick : clock.millis() + 1);
          for (int i = 0; i < 256; i++) complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
          timer.tick();
        }
        verify(store, times(4)).pendingHistoryProjectionAfter(eq(256), anyLong(), anyLong(), anyLong());
        var latest = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
        complete(runner, latest);
        assertTrue(ledger.store().recent().stream().anyMatch(event -> event.id().equals("operation:" + latest.accepted().key())));
      }
    }
  }

  @Test
  void failedActivationReleasesCapturedCallbackWithoutPublishingOrLeaking() throws Exception {
    try (var source = open(); var workers = Executors.newSingleThreadExecutor()) {
      var store = spy(source);
      var timer = new Timer();
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var attempt = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      var callbackEntered = new CountDownLatch(1);
      var captured = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
      doAnswer(call -> {
        java.util.function.Consumer<OperationRecord> listener = call.getArgument(0);
        return source.subscribeCompletions(row -> {
          callbackEntered.countDown();
          listener.accept(row);
        });
      }).when(store).subscribeCompletions(any());
      doAnswer(call -> {
        captured.set(workers.submit(() -> complete(runner, attempt)));
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> captured.get().get(100, TimeUnit.MILLISECONDS));
        throw new IllegalStateException("upper-bound query failed");
      }).when(store).historyProjectionUpperId();
      var history = new OperationHistoryStore(store);
      var observed = new CopyOnWriteArrayList<OperationHistoryEntry>();
      history.addAppendListener(observed::add);
      assertThrows(IllegalStateException.class, () -> new OperationHistoryProjector(store, history,
          new OperationHistoryChangeRegistry(), new ActionLedgerChangeRegistry(), timer.executors));
      captured.get().get(5, TimeUnit.SECONDS);
      assertTrue(observed.isEmpty());
      assertEquals(1, store.pendingHistoryProjection(1).size());
      verify(timer.registration).close();
      verify(timer.scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }
  }

  @Test
  void acknowledgedPrefixCanDisappearBeforeStartupAndFailedAckRetriesTheSameJournalIdentity() throws Exception {
    try (var store = open()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var row = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      complete(runner, row);
      var ledger = new ActionLedgerChangeRegistry(ActionEventJournal.at(directory.resolve("audit")));
      var timer = new Timer();
      var projector = new OperationHistoryProjector(store, new OperationHistoryStore(store),
          new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        sql("CREATE TRIGGER fail_ack BEFORE UPDATE OF history_pending ON operations WHEN NEW.history_pending=0 "
            + "BEGIN SELECT RAISE(ABORT, 'ack unavailable'); END");
        timer.tick();
        assertEquals(1, store.pendingHistoryProjection(256).size());
        assertEquals(1, ledger.store().recent().size());
        long bytes = Files.size(directory.resolve("audit/action-ledger.jsonl"));
        sql("DROP TRIGGER fail_ack");
        timer.tick();
        assertTrue(store.pendingHistoryProjection(256).isEmpty());
        assertEquals(bytes, Files.size(directory.resolve("audit/action-ledger.jsonl")));
        assertEquals(1, ledger.store().recent().size());
        assertEquals(row.accepted().key(), store.recentHistory(1).getFirst().key());
      }
      assertEquals(1, ActionEventJournal.at(directory.resolve("audit")).tail(10).size());
    }
  }

  @Test
  void realTimerRetriesARepairedSinkWithoutAnotherCompletion() throws Exception {
    Path audit = Files.writeString(directory.resolve("audit"), "blocks audit directory");
    try (var store = open(); var executors = new TestEngineExecutors()) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var ledger = spy(new ActionLedgerChangeRegistry(ActionEventJournal.at(audit)));
      var failed = new CountDownLatch(1);
      doAnswer(call -> { boolean accepted = (boolean) call.callRealMethod(); if (!accepted) failed.countDown(); return accepted; })
          .when(ledger).persistOperation(any());
      var projector = new OperationHistoryProjector(store, new OperationHistoryStore(store),
          new OperationHistoryChangeRegistry(), ledger, executors);
      try (projector) {
        complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertEquals(1, ledger.store().recent().size(), "failed sink does not hide committed live history");
        assertEquals(1, store.pendingHistoryProjection(1).size());
        Files.delete(audit);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!store.pendingHistoryProjection(1).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(store.pendingHistoryProjection(1).isEmpty(), "idle timer must retry without another mutation");
        assertEquals(1, ledger.journal().tail(10).size());
        assertEquals(1, ledger.store().recent().size());
      }
    }
  }

  @Test
  void shutdownTimeoutRetainsRegistrationAndLateCompletionsStayPendingUntilNextOwner() throws Exception {
    try (var store = open()) {
      var timer = new Timer();
      when(timer.scheduler.awaitTermination(anyLong(), any())).thenReturn(false, true);
      var ledger = new ActionLedgerChangeRegistry();
      var projector = new OperationHistoryProjector(store, new OperationHistoryStore(store),
          new OperationHistoryChangeRegistry(), ledger, timer.executors);
      try (projector) {
        assertThrows(IllegalStateException.class, projector::close);
        verify(timer.registration, never()).close();
        var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
        complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
        timer.tick();
        assertEquals(0, ledger.store().recent().size());
        assertEquals(1, store.pendingHistoryProjection(1).size());
        projector.close();
      }
      verify(timer.registration).close();
    }
  }

  @Test
  void rejectedSchedulerStartupUnsubscribesAndReleasesPartialOwnership() throws Exception {
    try (var store = open()) {
      var timer = new Timer();
      var refused = new RejectedExecutionException("scheduler unavailable");
      doThrow(refused).when(timer.scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
      var history = new OperationHistoryStore(store);
      var observed = new CopyOnWriteArrayList<OperationHistoryEntry>();
      history.addAppendListener(observed::add);
      assertSame(refused, assertThrows(RejectedExecutionException.class, () -> new OperationHistoryProjector(store,
          history, new OperationHistoryChangeRegistry(), new ActionLedgerChangeRegistry(), timer.executors)));
      verify(timer.scheduler).shutdownNow();
      verify(timer.registration).close();
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      complete(runner, accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD));
      assertTrue(observed.isEmpty());
    }
  }

  @Test
  void closeWaitsForAnAlreadyRunningCompletionCallbackBeforeReleasingTheOwner() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var shuttingDown = new CountDownLatch(1);
    var callbackActive = new java.util.concurrent.atomic.AtomicBoolean();
    try (var store = open(); var workers = Executors.newFixedThreadPool(2)) {
      var timer = new Timer();
      doAnswer(call -> { shuttingDown.countDown(); return List.of(); }).when(timer.scheduler).shutdownNow();
      doAnswer(call -> { assertFalse(callbackActive.get(), "registration cannot close before callback quiescence"); return null; })
          .when(timer.registration).close();
      var history = new OperationHistoryStore(store);
      history.addAppendListener(entry -> {
        callbackActive.set(true); entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        finally { callbackActive.set(false); }
      });
      var projector = new OperationHistoryProjector(store, history, new OperationHistoryChangeRegistry(),
          new ActionLedgerChangeRegistry(), timer.executors);
      try (projector) {
        try {
          var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
          var attempt = accept(runner, OperationKind.NOTE, OperationHistoryMode.STANDARD);
          var completion = workers.submit(() -> complete(runner, attempt));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(OperationState.COMPLETE, store.find(attempt.accepted().key()).orElseThrow().state());
          var closing = workers.submit(projector::close);
          assertTrue(shuttingDown.await(5, TimeUnit.SECONDS));
          assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
          release.countDown();
          completion.get(5, TimeUnit.SECONDS);
          closing.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
      }
      verify(timer.registration).close();
    }
  }

  private void sql(String command) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var statement = db.createStatement()) { statement.execute(command); }
  }
}
