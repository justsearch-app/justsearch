/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationCheckpointTest {
  @TempDir Path temp;
  private final OperationTestClock clock =
      new OperationTestClock(Instant.parse("2026-09-14T04:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "checkpoint-test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);

  @Test
  void eachUnitCommitsBeforeTheTimerAndPeriodicCheckpointKeepsTheLatestCursor() throws Exception {
    Path path = temp.resolve("operations.db");
    String key = OperationKeys.generate(clock);
    try (var store = new SqliteOperationStore(path, clock, step -> {})) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.INGEST));
      var attempt = runner.accept(new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest", "{}", false), CONTEXT, null));
      runner.start(attempt, handle -> {
        clock.setMillis(clock.millis() + 1_000);
        handle.checkpoint("unit-1", 1, 0);
        assertPersisted(path, "unit-1", 1);
        clock.setMillis(clock.millis() + 1_000);
        handle.checkpoint("unit-2", 2, 0);
        assertPersisted(path, "unit-2", 2);
        // Equal-count cursor changes are owner-written; maintenance must not replay an old snapshot.
        handle.checkpoint("next-page", 2, 0);
        return new OperationExecution(OperationResult.success("started"), new CompletableFuture<>());
      });
      var before = store.find(key).orElseThrow();
      clock.setMillis(clock.millis() + 30_000);
      store.checkpointDurableOperations();
      var after = store.find(key).orElseThrow();
      assertEquals("next-page", after.checkpointCursor());
      assertEquals(2, after.unitsCompleted());
      assertEquals(0, after.unitsFailed());
      assertEquals(before.state(), after.state());
      assertEquals(clock.millis(), after.updatedAt());
      assertTrue(after.updatedAt() > before.updatedAt());
      clock.setMillis(clock.millis() - 60_000);
      store.checkpointDurableOperations();
      assertEquals(after, store.find(key).orElseThrow());
    }
    try (var reopened = new SqliteOperationStore(path, clock, step -> {})) {
      var row = reopened.find(key).orElseThrow();
      assertEquals(OperationState.RUNNING, row.state());
      assertEquals("next-page", row.checkpointCursor());
      assertEquals(2, row.unitsCompleted());
      assertTrue(reopened.resume(row.id()));
    }
  }

  @Test
  void checkpointDoesNotWaitForOldWalReaderOrTouchOtherLifetimes() throws Exception {
    Path path = temp.resolve("reader.db");
    try (var store = new SqliteOperationStore(path, clock, step -> {})) {
      long durable = acceptRunning(store);
      var accepted = store.accept(OperationKeys.generate(clock),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest", "{}", false), CONTEXT, null).record();
      var interactiveContext = new EngineContext(CONTEXT.clientKind(), CONTEXT.clientId(),
          CONTEXT.sessionId(), CONTEXT.grantReference(), CONTEXT.sourceTier(), CONTEXT.transport(),
          EngineContext.Survival.INTERACTIVE, CONTEXT.urgency());
      var interactive = store.accept(OperationKeys.generate(clock),
          OperationDescriptor.invocation(OperationKind.OPERATION, "core.test", "{}", false), interactiveContext, null).record();
      assertTrue(store.start(interactive.id()));
      var interactiveBefore = store.find(interactive.key()).orElseThrow();
      long terminalId = acceptRunning(store);
      var terminal = store.finish(terminalId, OperationState.COMPLETE,
          new io.justsearch.app.api.operations.OperationReceipt("SUCCESS", null)).orElseThrow();
      try (var reader = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = reader.createStatement()) {
        reader.setAutoCommit(false);
        try (var result = statement.executeQuery("SELECT * FROM operations")) {
          assertTrue(result.next());
          assertTrue(store.checkpoint(durable, "unit-2", 2, 0));
          clock.setMillis(clock.millis() + 30_000);
          store.checkpointDurableOperations();
          assertEquals(clock.millis(), store.openRecords().stream()
              .filter(row -> row.id() == durable).findFirst().orElseThrow().updatedAt());
        }
        reader.rollback();
      }
      assertEquals(accepted, store.find(accepted.key()).orElseThrow());
      assertEquals(interactiveBefore, store.find(interactive.key()).orElseThrow());
      assertEquals(terminal, store.find(terminal.key()).orElseThrow());
    }
  }

  @Test
  void gapsAwaitingDecisionRetainTheirPositionAtThePeriodicCheckpoint() throws Exception {
    Path path = temp.resolve("gaps.db");
    try (var store = new SqliteOperationStore(path, clock, step -> {})) {
      long id = acceptRunning(store);
      assertTrue(store.checkpoint(id, "last-committed-unit", 4, 2));
      // C2-10 owns production gap publication; seed its already-declared persisted state here.
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) {
        statement.execute("UPDATE operations SET state='COMPLETE_WITH_GAPS'");
      }
      var before = store.openRecords().getFirst();
      clock.setMillis(clock.millis() + 30_000);
      store.checkpointDurableOperations();
      var after = store.openRecords().getFirst();
      assertEquals(clock.millis(), after.updatedAt());
      assertTrue(after.updatedAt() > before.updatedAt());
      assertEquals(OperationState.COMPLETE_WITH_GAPS, after.state());
      assertEquals(before.checkpointCursor(), after.checkpointCursor());
      assertEquals(4, after.unitsCompleted());
      assertEquals(2, after.unitsFailed());
      assertEquals(before.attempts(), after.attempts());
    }
  }

  @Test
  void checkpointFailureRollsBackAllRowsAndClosedStoreRefuses() throws Exception {
    Path path = temp.resolve("failure.db");
    var store = new SqliteOperationStore(path, clock, step -> {});
    try {
      acceptRunning(store);
      acceptRunning(store);
      var before = store.openRecords();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_checkpoint BEFORE UPDATE ON operations "
            + "WHEN OLD.id=" + before.getLast().id()
            + " BEGIN SELECT RAISE(ABORT, 'checkpoint unavailable'); END");
        clock.setMillis(clock.millis() + 30_000);
        var failure = assertThrows(OperationStoreException.class, store::checkpointDurableOperations);
        assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
        assertEquals(before, store.openRecords());
        statement.execute("DROP TRIGGER refuse_checkpoint");
      }
      assertDoesNotThrow(store::checkpointDurableOperations);
      assertTrue(store.openRecords().stream().allMatch(row -> row.updatedAt() == clock.millis()));
    } finally {
      store.close();
    }
    assertThrows(OperationStoreException.class, store::checkpointDurableOperations);
  }

  private long acceptRunning(SqliteOperationStore store) {
    var row = store.accept(OperationKeys.generate(clock),
        OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest", "{}", false), CONTEXT, null).record();
    assertTrue(store.start(row.id()));
    return row.id();
  }

  private static void assertPersisted(Path path, String cursor, long count) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT checkpoint_cursor, units_completed FROM operations")) {
      assertTrue(row.next());
      assertEquals(cursor, row.getString(1));
      assertEquals(count, row.getLong(2));
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
