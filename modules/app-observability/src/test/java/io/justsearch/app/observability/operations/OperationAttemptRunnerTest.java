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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationAttemptRunnerTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  @TempDir Path temp;

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
