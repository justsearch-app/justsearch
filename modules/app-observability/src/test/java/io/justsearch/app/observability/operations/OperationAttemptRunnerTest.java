/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationAttemptRunnerTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  @TempDir Path temp;

  @Test
  void closingWaitsForActualBodyExitAndAsyncReceipt() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      var body = new Thread(() -> runner.start(attempt, ignored -> {
        entered.countDown();
        boolean released = false;
        while (!released) {
          try { released = release.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException cancelled) { /* Native-style work may ignore cancellation. */ }
        }
        return OperationExecution.finished(OperationResult.success("complete"));
      }));
      body.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      runner.beginClosing();
      body.interrupt();
      assertFalse(runner.awaitDrained(Duration.ofMillis(20)));
      release.countDown();
      body.join(5_000);
      assertFalse(body.isAlive());
      assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
    }
  }

  @Test
  void closingRetainsStoreUntilAsyncEffectReceiptFinishes() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var actual = new CompletableFuture<OperationResult>();
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.DURABLE));
      runner.start(attempt, ignored -> new OperationExecution(OperationResult.success("started"), actual));
      runner.beginClosing();
      assertFalse(runner.awaitDrained(Duration.ofMillis(20)));
      actual.complete(OperationResult.success("complete"));
      assertTrue(runner.awaitDrained(Duration.ofSeconds(1)));
      assertEquals(OperationState.COMPLETE, attempt.completion().toCompletableFuture().join().state());
    }
  }

  @Test
  void durableIngestionHandoffReleasesOnlyTheOldBodyAndPreservesTheRunningRow()
      throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.REINDEX));
      var effect = new CompletableFuture<OperationResult>();
      var handle = new java.util.concurrent.atomic.AtomicReference<
          io.justsearch.agent.api.registry.OperationRecordHandle>();
      var attempt = runner.accept(request(OperationKind.REINDEX, EngineContext.Survival.DURABLE));
      runner.start(attempt, issued -> {
        handle.set(issued);
        return new OperationExecution(OperationResult.success("started"), effect);
      });

      runner.beginClosing();
      assertFalse(runner.awaitDrained(Duration.ZERO));
      runner.handoffPendingForShutdown(handle.get());
      assertTrue(runner.awaitDrained(Duration.ZERO));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertFalse(attempt.completion().toCompletableFuture().isDone());

      // An abandoned physical owner must not write a late terminal result into the durable row.
      effect.complete(OperationResult.success("late result"));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertFalse(attempt.completion().toCompletableFuture().isDone());
    }
  }

  @Test
  void durableHandoffRefusesWhileThePhysicalBodyIsStillExecuting() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.REINDEX));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var handle = new java.util.concurrent.atomic.AtomicReference<
          io.justsearch.agent.api.registry.OperationRecordHandle>();
      var effect = new CompletableFuture<OperationResult>();
      var attempt = runner.accept(request(OperationKind.REINDEX, EngineContext.Survival.DURABLE));
      var body = new Thread(() -> runner.start(attempt, issued -> {
        handle.set(issued);
        entered.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Test body interrupted", interrupted);
        }
        return new OperationExecution(OperationResult.success("started"), effect);
      }));
      body.start();
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        runner.beginClosing();
        assertThrows(IllegalStateException.class,
            () -> runner.handoffPendingForShutdown(handle.get()));
      } finally {
        release.countDown();
        body.join(5_000);
      }
      assertFalse(body.isAlive());
      assertTrue(runner.handoffPendingForShutdown(handle.get()));
      assertTrue(runner.awaitDrained(Duration.ZERO));
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void acceptancePrecedesBodyAndStartedResponseDoesNotCompleteAsyncWork() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE);
      var attempt = runner.accept(request);
      assertEquals(OperationState.ACCEPTED, store.find(request.key()).orElseThrow().state());
      var actual = new CompletableFuture<OperationResult>();
      var result = runner.start(attempt, record -> {
        assertEquals(OperationState.RUNNING, store.find(record.key()).orElseThrow().state());
        record.checkpoint("unit-1", 1, 0);
        return new OperationExecution(OperationResult.success("started"), actual);
      });
      assertEquals("started", result.response().message());
      assertFalse(result.completion().toCompletableFuture().isDone());
      assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
      actual.complete(OperationResult.success("private result body", "batch-123"));
      var complete = result.completion().toCompletableFuture().join();
      assertEquals(OperationState.COMPLETE, complete.state());
      assertEquals(1, complete.unitsCompleted());
      assertEquals(new OperationReceipt("SUCCESS", "batch-123"), complete.receipt());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void evictionAfterTerminalWriteCannotEraseLiveCompletion(boolean refusal) throws Exception {
    var clock = new OperationTestClock(CLOCK.millis());
    try (var store = new SqliteOperationStore(temp.resolve("eviction.db"), clock, step -> {})) {
      var port = (io.justsearch.app.api.operations.OperationStore) java.lang.reflect.Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class<?>[] {io.justsearch.app.api.operations.OperationStore.class},
          (proxy, method, args) -> {
            final Object result;
            try { result = method.invoke(store, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            if (method.getName().equals(refusal ? "rejectBeforeStart" : "finish")) {
              clock.setMillis(CLOCK.millis() + Duration.ofDays(31).toMillis());
              store.pruneHistory();
            }
            return result;
          });
      var runner = new OperationAttemptRunnerImpl(port, CLOCK, Set.of());
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      if (refusal) assertDoesNotThrow(() -> runner.rejectBeforeStart(attempt, "CONTEXT_LIMIT"));
      else assertDoesNotThrow(() -> runner.start(attempt,
          handle -> OperationExecution.finished(OperationResult.success("done"))));
      var terminal = attempt.completion().toCompletableFuture().join();
      assertEquals(refusal ? OperationState.FAILED : OperationState.COMPLETE, terminal.state());
      assertTrue(store.find(terminal.key()).isEmpty(), "the negative witness really evicts the row");
      assertDoesNotThrow(() -> runner.rejectBeforeStart(attempt, "LATE_REFUSAL"));
    }
  }

  @Test
  void duplicateNeverStartsBodyAndReturnsReceiptWithoutRichContent() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE);
      var first = runner.accept(request);
      var duplicate = runner.accept(request);
      AtomicInteger effects = new AtomicInteger();
      var observed = runner.start(duplicate, handle -> {
        effects.incrementAndGet(); return OperationExecution.finished(OperationResult.success("wrong"));
      });
      assertEquals(0, effects.get());
      assertEquals(OperationState.ACCEPTED, observed.record().state());
      var response = runner.start(first, handle -> {
        effects.incrementAndGet();
        return OperationExecution.finished(OperationResult.success("private result body",
            java.util.Map.of("content", "private result body")));
      });
      assertEquals("private result body", response.response().message());
      assertEquals(OperationState.COMPLETE, observed.completion().toCompletableFuture().join().state());
      var retry = runner.start(runner.accept(request), handle -> {
        effects.incrementAndGet(); return OperationExecution.finished(OperationResult.success("wrong"));
      });
      assertEquals(1, effects.get());
      assertFalse(retry.response().toString().contains("private result body"));
    }
    assertFalse(new String(java.nio.file.Files.readAllBytes(temp.resolve("operations.db")),
        java.nio.charset.StandardCharsets.ISO_8859_1).contains("private result body"));
  }

  @Test
  void failedAcceptanceNeverReachesEffect() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      execute("CREATE TRIGGER refuse_accept BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      AtomicInteger effects = new AtomicInteger();
      assertThrows(OperationStoreException.class, () -> {
        var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
        runner.start(attempt, handle -> {
          effects.incrementAndGet(); return OperationExecution.finished(OperationResult.success("wrong"));
        });
      });
      assertEquals(0, effects.get());
      assertTrue(store.openRecords().isEmpty());
    }
  }

  @Test
  void refusalOnlyBeforeStartAndThrownBodyHasTruthfulFailure() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var refused = runner.accept(request(OperationKind.SCHEDULED_RUN, EngineContext.Survival.INTERACTIVE));
      runner.rejectBeforeStart(refused, "ADMISSION_REFUSED");
      var result = runner.start(refused, handle -> { fail("Refused timer cannot run"); return null; });
      assertEquals(OperationState.FAILED, result.record().state());
      assertEquals("ADMISSION_REFUSED", result.record().failureReason());
      var running = runner.accept(request(OperationKind.SCHEDULED_RUN, EngineContext.Survival.INTERACTIVE));
      var actual = new CompletableFuture<OperationResult>();
      runner.start(running, handle -> new OperationExecution(OperationResult.success("started"), actual));
      runner.rejectBeforeStart(running, "LATE_SCHEDULER_REFUSAL");
      assertEquals(OperationState.RUNNING, store.find(running.accepted().key()).orElseThrow().state());
      actual.complete(OperationResult.success("done"));
      var throwing = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      assertThrows(IllegalStateException.class, () -> runner.start(throwing, handle -> {
        throw new IllegalStateException("private failure detail");
      }));
      assertEquals("UNCAUGHT_EXCEPTION", throwing.completion().toCompletableFuture().join().failureReason());
    }
  }

  @Test
  void completionStorageFailureKeepsRunningAndSurfacesFailure() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      var actual = new CompletableFuture<OperationResult>();
      var result = runner.start(attempt, handle -> new OperationExecution(OperationResult.success("started"), actual));
      execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON operations WHEN NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      actual.complete(OperationResult.success("effect finished"));
      assertThrows(CompletionException.class, () -> result.completion().toCompletableFuture().join());
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void asynchronousEffectAndTerminalWriteFailureRetainsBothCauses() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OperationAttemptRunnerImpl.class);
    var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      var actual = new CompletableFuture<OperationResult>();
      var result = runner.start(attempt,
          handle -> new OperationExecution(OperationResult.success("started"), actual));
      execute("CREATE TRIGGER refuse_failed BEFORE UPDATE ON operations WHEN NEW.state = 'FAILED' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      var effectFailure = new IllegalStateException("private effect failure");
      actual.completeExceptionally(effectFailure);
      var observed = assertThrows(CompletionException.class,
          () -> result.completion().toCompletableFuture().join());
      assertInstanceOf(OperationStoreException.class, observed.getCause());
      assertArrayEquals(new Throwable[] {effectFailure}, observed.getCause().getSuppressed());
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      var degradation = runner.persistenceFailure().toCompletableFuture().join();
      assertEquals(attempt.accepted().key(), degradation.operationKey());
      assertEquals(OperationState.FAILED, degradation.intendedState());
      assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
          && event.getFormattedMessage().contains(attempt.accepted().key())
          && event.getFormattedMessage().contains("intendedState=FAILED")));
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void ignoredTerminalWriteCannotLeaveCompletionPending() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      var actual = new CompletableFuture<OperationResult>();
      runner.start(attempt, handle -> new OperationExecution(OperationResult.success("started"), actual));
      execute("CREATE TRIGGER ignore_complete BEFORE UPDATE ON operations WHEN NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(IGNORE); END");
      actual.complete(OperationResult.success("effect committed"));
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(OperationState.RUNNING, store.find(attempt.accepted().key()).orElseThrow().state());
      assertEquals(OperationState.COMPLETE, runner.persistenceFailure().toCompletableFuture().join().intendedState());
    }
  }

  @Test
  void ignoredBootSweepRefusesStartup() throws Exception {
    try (var store = store()) {
      var request = request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE);
      store.accept(request.key(), request.descriptor(), request.context(), null);
      execute("CREATE TRIGGER ignore_failed BEFORE UPDATE ON operations WHEN NEW.state = 'FAILED' "
          + "BEGIN SELECT RAISE(IGNORE); END");
      assertThrows(OperationStoreException.class, () -> new OperationAttemptRunnerImpl(store, CLOCK, Set.of()));
      assertEquals(OperationState.ACCEPTED, store.find(request.key()).orElseThrow().state());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void ignoredStartOrResumeCannotLeaveCompletionPending(boolean resume) throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OperationAttemptRunnerImpl.class);
    var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try (var store = store()) {
      var request = request(OperationKind.INGEST, EngineContext.Survival.DURABLE);
      if (resume) {
        var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
        assertTrue(store.start(row.id()));
      }
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      var attempt = runner.accept(request);
      execute("CREATE TRIGGER ignore_running BEFORE UPDATE ON operations WHEN NEW.state = 'RUNNING' "
          + "BEGIN SELECT RAISE(IGNORE); END");
      AtomicInteger effects = new AtomicInteger();
      java.util.function.Function<io.justsearch.agent.api.registry.OperationRecordHandle, OperationExecution> body = handle -> {
        effects.incrementAndGet();
        return OperationExecution.finished(OperationResult.success("effect"));
      };
      OperationStoreException failure = assertThrows(OperationStoreException.class, () -> {
        if (resume) {
          runner.reconcile(OperationKind.INGEST, row -> new OperationAttemptRunner.Reconciliation.Resume(body));
        } else {
          runner.start(attempt, body);
        }
      });
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
      assertEquals(0, effects.get());
      assertEquals(resume ? OperationState.RUNNING : OperationState.ACCEPTED,
          store.find(request.key()).orElseThrow().state());
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertSame(failure, assertThrows(CompletionException.class,
          () -> attempt.completion().toCompletableFuture().join()).getCause());
      var degradation = runner.persistenceFailure().toCompletableFuture().join();
      assertEquals(request.key(), degradation.operationKey());
      assertEquals(OperationState.RUNNING, degradation.intendedState());
      assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
          && event.getFormattedMessage().contains(request.key())
          && event.getFormattedMessage().contains("intendedState=RUNNING")));
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void terminalWinnerBeforeResumePublishesReceiptWithoutRunningAgain(boolean prune) throws Exception {
    var clock = new OperationTestClock(CLOCK.millis());
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"), clock, step -> {})) {
      var request = request(OperationKind.INGEST, EngineContext.Survival.DURABLE);
      var accepted = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      assertTrue(store.start(accepted.id()));
      var port = (io.justsearch.app.api.operations.OperationStore) java.lang.reflect.Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class<?>[] {io.justsearch.app.api.operations.OperationStore.class},
          (proxy, method, args) -> {
            final Object result;
            try { result = method.invoke(store, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            if (prune && method.getName().equals("find") && result instanceof Optional<?> optional
                && optional.orElse(null) instanceof io.justsearch.app.api.operations.OperationRecord row
                && row.state().terminal()) {
              clock.setMillis(CLOCK.millis() + Duration.ofDays(31).toMillis());
              store.pruneHistory();
            }
            return result;
          });
      var runner = new OperationAttemptRunnerImpl(port, CLOCK, Set.of(OperationKind.INGEST));
      var attempt = runner.accept(request);
      var receipt = new OperationReceipt("SUCCESS", "existing-effect");
      runner.reconcile(OperationKind.INGEST, row -> {
        assertTrue(store.finish(row.id(), OperationState.COMPLETE, receipt).isPresent());
        return new OperationAttemptRunner.Reconciliation.Resume(handle -> {
          fail("A terminal winner must not repeat its effect");
          return null;
        });
      });
      var terminal = attempt.completion().toCompletableFuture().join();
      assertEquals(OperationState.COMPLETE, terminal.state());
      assertEquals(receipt, terminal.receipt());
      assertEquals(prune, store.find(request.key()).isEmpty());
      assertFalse(runner.persistenceFailure().toCompletableFuture().isDone());
    }
  }

  @Test
  void ignoredPreStartRefusalIsNotMistakenForSuccessfulRefusal() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var attempt = runner.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE));
      execute("CREATE TRIGGER ignore_failed BEFORE UPDATE ON operations WHEN NEW.state = 'FAILED' "
          + "BEGIN SELECT RAISE(IGNORE); END");
      assertThrows(OperationStoreException.class, () -> runner.rejectBeforeStart(attempt, "ADMISSION_REFUSED"));
      assertTrue(attempt.completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(OperationState.ACCEPTED, store.find(attempt.accepted().key()).orElseThrow().state());
    }
  }

  @Test
  void bootOwnersWaitAndReconcileOriginalRowsWithoutTouchingNewWork() throws Exception {
    try (var store = store()) {
      var unowned = store.accept(request(OperationKind.OPERATION, EngineContext.Survival.INTERACTIVE).key(),
          descriptor(OperationKind.OPERATION), context(EngineContext.Survival.INTERACTIVE), null).record();
      var owned = store.accept(request(OperationKind.INGEST, EngineContext.Survival.INTERACTIVE).key(),
          descriptor(OperationKind.INGEST), context(EngineContext.Survival.INTERACTIVE), null).record();
      var durable = store.accept(request(OperationKind.OPERATION, EngineContext.Survival.DURABLE).key(),
          descriptor(OperationKind.OPERATION), context(EngineContext.Survival.DURABLE), null).record();
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      assertEquals("interrupted_by_restart", store.find(unowned.key()).orElseThrow().failureReason());
      assertEquals(OperationState.ACCEPTED, store.find(owned.key()).orElseThrow().state());
      assertEquals(OperationState.ACCEPTED, store.find(durable.key()).orElseThrow().state());
      var fresh = runner.accept(request(OperationKind.INGEST, EngineContext.Survival.DURABLE));
      runner.reconcile(OperationKind.INGEST, row -> new OperationAttemptRunner.Reconciliation.Wait());
      AtomicInteger effects = new AtomicInteger();
      runner.reconcile(OperationKind.INGEST, row -> new OperationAttemptRunner.Reconciliation.Resume(handle -> {
        assertEquals(owned.id(), handle.id()); effects.incrementAndGet();
        return OperationExecution.finished(OperationResult.success("resumed"));
      }));
      runner.reconcile(OperationKind.INGEST, row -> { fail("Completed row cannot reconcile again"); return null; });
      assertEquals(1, effects.get());
      assertEquals(OperationState.COMPLETE, store.find(owned.key()).orElseThrow().state());
      assertEquals(OperationState.ACCEPTED, store.find(fresh.accepted().key()).orElseThrow().state());
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {});
  }

  @Test
  void recoveryCheckpointAtAttemptLimitPreservesEligibilityWithoutExecutingAnAttempt() throws Exception {
    try (var store = store()) {
      var request = request(OperationKind.INGEST, EngineContext.Survival.DURABLE);
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      assertTrue(store.start(row.id()));
      assertTrue(store.resume(row.id()));
      assertTrue(store.resume(row.id()));
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      var completed = new AtomicInteger();
      try (var subscription = store.subscribeCompletions(ignored -> completed.incrementAndGet())) {
        assertNotNull(subscription);
        runner.reconcile(OperationKind.INGEST, ignored -> new OperationAttemptRunner.Reconciliation.CheckpointAndWait(
            "ingest-refusal:1:RECOVERY_SCOPE_REFUSED", 0, 0));
        var checkpointed = store.find(row.key()).orElseThrow();
        assertEquals(OperationState.RUNNING, checkpointed.state());
        assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS, checkpointed.attempts());
        assertEquals("ingest-refusal:1:RECOVERY_SCOPE_REFUSED", checkpointed.checkpointCursor());
        assertEquals(0, completed.get());
        runner.reconcile(OperationKind.INGEST, current -> {
          assertEquals(checkpointed, current);
          return new OperationAttemptRunner.Reconciliation.Failed(new OperationReceipt("RECOVERY_SCOPE_REFUSED", null));
        });
        assertEquals(1, completed.get());
        assertEquals(OperationState.FAILED, store.find(row.key()).orElseThrow().state());
        assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS, store.find(row.key()).orElseThrow().attempts());
      }
    }
  }

  @Test
  void ignoredRecoveryCheckpointSignalsUnresolvedPersistenceAndCannotAdvanceToCleanup() throws Exception {
    try (var store = store()) {
      var request = request(OperationKind.INGEST, EngineContext.Survival.DURABLE);
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      assertTrue(store.start(row.id()));
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      execute("CREATE TRIGGER ignore_checkpoint BEFORE UPDATE OF checkpoint_cursor ON operations "
          + "BEGIN SELECT RAISE(IGNORE); END");
      assertThrows(RuntimeException.class, () -> runner.reconcile(OperationKind.INGEST,
          ignored -> new OperationAttemptRunner.Reconciliation.CheckpointAndWait("decision", 0, 0)));
      assertNull(store.find(row.key()).orElseThrow().checkpointCursor());
      assertEquals(1, store.find(row.key()).orElseThrow().attempts());
      var failure = runner.persistenceFailure().toCompletableFuture().join();
      assertEquals(row.key(), failure.operationKey());
      assertEquals(OperationState.RUNNING, failure.intendedState());
    }
  }

  @Test
  void recoveryCheckpointCannotStartAnAcceptedRow() throws Exception {
    try (var store = store()) {
      var request = request(OperationKind.INGEST, EngineContext.Survival.DURABLE);
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.INGEST));
      assertThrows(IllegalArgumentException.class, () -> runner.reconcile(OperationKind.INGEST,
          ignored -> new OperationAttemptRunner.Reconciliation.CheckpointAndWait("decision", 0, 0)));
      assertEquals(row, store.find(row.key()).orElseThrow());
      assertFalse(runner.persistenceFailure().toCompletableFuture().isDone());
    }
  }
  private static OperationAttemptRunner.Request request(OperationKind kind, EngineContext.Survival survival) {
    return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK), descriptor(kind), context(survival), null);
  }
  private static OperationDescriptor descriptor(OperationKind kind) {
    return OperationDescriptor.invocation(kind, "core.test", "{}", false);
  }
  private static EngineContext context(EngineContext.Survival survival) {
    return new EngineContext(EngineContext.ClientKind.INTERNAL, "test", Optional.empty(), Optional.empty(),
        "system", "SYSTEM_INTERNAL", survival, EngineContext.Urgency.BACKGROUND);
  }
  private void execute(String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
        var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
