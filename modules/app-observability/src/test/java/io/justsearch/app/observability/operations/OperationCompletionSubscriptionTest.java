/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationCompletionSubscriptionTest {
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "completion-test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  @TempDir Path temp;

  @Test
  void directMemoryAndNoteCompletionIsCommittedAndPublishedOutsideTheStoreLock() throws Exception {
    Path path = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(path); var reader = Executors.newSingleThreadExecutor()) {
      List<OperationRecord> observed = new ArrayList<>();
      var verificationFailure = new AtomicReference<Throwable>();
      try (var _ = store.subscribeCompletions(row -> {
        try {
          // A second connection proves the transition is committed, not just visible to its writer.
          try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
              var query = db.prepareStatement("SELECT state FROM operations WHERE id = ?")) {
            query.setLong(1, row.id());
            try (var result = query.executeQuery()) {
              assertTrue(result.next());
              assertEquals(row.state().name(), result.getString(1));
            }
          }
          // A different thread using the same owner proves its lock is no longer held.
          assertEquals(row, reader.submit(() -> store.find(row.key()).orElseThrow()).get(2, TimeUnit.SECONDS));
          observed.add(row);
        } catch (Throwable failure) {
          verificationFailure.compareAndSet(null, failure);
        }
      })) {
        for (var kind : List.of(OperationKind.MEMORY, OperationKind.NOTE)) {
          int before = observed.size();
          var row = accept(store, kind);
          assertTrue(store.start(row.id()));
          assertEquals(before, observed.size(), "acceptance/start are not terminal notifications");
          var completed = store.finish(row.id(), OperationState.COMPLETE, receipt()).orElseThrow();
          assertNull(verificationFailure.get(), "Completion must be committed and unlocked");
          assertEquals(completed, observed.getLast());
          assertTrue(store.finish(row.id(), OperationState.COMPLETE, receipt()).isEmpty());
        }
        assertEquals(2, observed.size(), "one notification per actual transition, without a dispatcher");
      }
      var later = accept(store, OperationKind.MEMORY);
      store.finish(later.id(), OperationState.COMPLETE, receipt());
      assertEquals(2, observed.size(), "closing a subscription removes it");
    }
  }

  @Test
  void observerAssertionCannotChangeDirectCompletionOrPreventHealthyObservers() throws Exception {
    assertObserverAssertionIsolated(false);
  }

  @Test
  void observerAssertionCannotChangeWinningRejectionOrPreventHealthyObservers() throws Exception {
    assertObserverAssertionIsolated(true);
  }

  private void assertObserverAssertionIsolated(boolean rejection) throws Exception {
    Path path = temp.resolve("observer-assertion.db");
    List<OperationRecord> before = new ArrayList<>();
    List<OperationRecord> after = new ArrayList<>();
    OperationRecord terminal;
    OperationRecord returned = null;
    AssertionError publicationFailure = null;
    var expectedReceipt = new OperationReceipt(rejection ? "CAPACITY" : "SUCCESS", "committed-effect");
    try (var store = new SqliteOperationStore(path);
        var _ = store.subscribeCompletions(before::add);
        var _ = store.subscribeCompletions(row -> { throw new AssertionError("observer assertion"); });
        var _ = store.subscribeCompletions(after::add)) {
      var accepted = accept(store, OperationKind.NOTE);
      if (!rejection) {
        assertTrue(store.start(accepted.id()));
        assertTrue(store.checkpoint(accepted.id(), "unit-7", 7, 2));
      }
      try {
        returned = rejection ? store.rejectBeforeStart(accepted.id(), expectedReceipt)
            : store.finish(accepted.id(), OperationState.COMPLETE, expectedReceipt).orElseThrow();
      } catch (AssertionError failure) {
        publicationFailure = failure;
      }
      terminal = store.find(accepted.key()).orElseThrow();
      assertEquals(rejection ? OperationState.FAILED : OperationState.COMPLETE, terminal.state());
      assertEquals(expectedReceipt, terminal.receipt());
      assertEquals(rejection ? 0 : 7, terminal.unitsCompleted());
      assertEquals(rejection ? 0 : 2, terminal.unitsFailed());
      assertEquals(rejection ? 0 : 1, terminal.attempts());
      assertCommitted(path, terminal);
      assertEquals(terminal, store.rejectBeforeStart(accepted.id(), receipt()));
      assertTrue(store.finish(accepted.id(), OperationState.CANCELLED, receipt()).isEmpty());
      assertEquals(terminal, store.find(accepted.key()).orElseThrow());
    }
    try (var reopened = new SqliteOperationStore(path)) {
      assertEquals(terminal, reopened.find(terminal.key()).orElseThrow());
    }
    assertNull(publicationFailure, "Observer escaped after durable terminal commit: " + terminal);
    assertEquals(terminal, returned);
    assertEquals(List.of(terminal), before);
    assertEquals(List.of(terminal), after);
  }

  @Test
  void capturedObserverVerificationRejectsADeliberatelyWrongCommittedState() throws Exception {
    var verificationFailure = new AtomicReference<Throwable>();
    try (var store = new SqliteOperationStore(temp.resolve("oracle.db"));
        var _ = store.subscribeCompletions(row -> {
          try {
            assertEquals(OperationState.RUNNING, row.state(), "deliberately violated terminal oracle");
          } catch (Throwable failure) {
            verificationFailure.compareAndSet(null, failure);
          }
        })) {
      var accepted = accept(store, OperationKind.NOTE);
      assertDoesNotThrow(() -> store.finish(accepted.id(), OperationState.COMPLETE, receipt()));
      assertInstanceOf(AssertionError.class, verificationFailure.get());
      assertThrows(AssertionError.class,
          () -> assertNull(verificationFailure.get(), "Completion must be committed and unlocked"));
    }
  }

  private static void assertCommitted(Path path, OperationRecord expected) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
        var query = db.prepareStatement("SELECT * FROM operations WHERE id = ?")) {
      query.setLong(1, expected.id());
      try (var row = query.executeQuery()) {
        assertTrue(row.next());
        assertEquals(expected.key(), row.getString("operation_key"));
        assertEquals(expected.state().name(), row.getString("state"));
        assertEquals(expected.receipt(), new ObjectMapper().readValue(row.getString("result_json"), OperationReceipt.class));
        assertEquals(expected.unitsCompleted(), row.getLong("units_completed"));
        assertEquals(expected.unitsFailed(), row.getLong("units_failed"));
        assertEquals(expected.attempts(), row.getInt("attempts"));
        assertEquals(expected.completedAt().longValue(), row.getLong("completed_at"));
        assertFalse(row.next());
      }
    }
  }

  @Test
  void rejectionNotifiesOnlyItsWinningTransitionAndObserverFailureDoesNotChangeCompletion() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      List<OperationRecord> observed = new ArrayList<>();
      try (var _ = store.subscribeCompletions(row -> { throw new IllegalStateException("projection unavailable"); });
          var _ = store.subscribeCompletions(observed::add)) {
        var rejected = accept(store, OperationKind.MEMORY);
        var terminal = store.rejectBeforeStart(rejected.id(), new OperationReceipt("CAPACITY", null));
        assertEquals(OperationState.FAILED, terminal.state());
        assertEquals(List.of(terminal), observed);
        assertEquals(terminal, store.rejectBeforeStart(rejected.id(), receipt()));
        assertTrue(store.finish(rejected.id(), OperationState.CANCELLED, receipt()).isEmpty());
        assertEquals(1, observed.size());
        var running = accept(store, OperationKind.NOTE);
        assertTrue(store.start(running.id()));
        assertEquals(OperationState.RUNNING, store.rejectBeforeStart(running.id(), receipt()).state());
        assertEquals(1, observed.size(), "refusal cannot complete an already-running effect");
        var cancelled = store.finish(running.id(), OperationState.CANCELLED, receipt()).orElseThrow();
        assertEquals(List.of(terminal, cancelled), observed);
        assertEquals(cancelled, store.find(running.key()).orElseThrow());
      }
    }
  }

  @Test
  void failedTerminalWriteDoesNotNotifyAndClosedStoreRejectsSubscriptions() throws Exception {
    var store = new SqliteOperationStore(temp.resolve("operations.db"));
    List<OperationRecord> observed = new ArrayList<>();
    try (store; var _ = store.subscribeCompletions(observed::add)) {
      var row = accept(store, OperationKind.MEMORY);
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("operations.db"));
          var statement = db.createStatement()) {
        statement.execute("CREATE TRIGGER fail_completion BEFORE UPDATE OF state ON operations "
            + "BEGIN SELECT RAISE(ABORT, 'completion unavailable'); END");
      }
      assertThrows(OperationStoreException.class, () -> store.finish(row.id(), OperationState.COMPLETE, receipt()));
      assertThrows(OperationStoreException.class, () -> store.rejectBeforeStart(row.id(), receipt()));
      assertEquals(OperationState.ACCEPTED, store.find(row.key()).orElseThrow().state());
      assertTrue(observed.isEmpty());
    }
    assertThrows(OperationStoreException.class, () -> store.subscribeCompletions(observed::add));
  }

  private static OperationRecord accept(SqliteOperationStore store, OperationKind kind) {
    return store.accept(OperationKeys.generate(Clock.systemUTC()),
        new OperationDescriptor(kind, "core.completion-fixture", "{}"), CONTEXT, null).record();
  }

  private static OperationReceipt receipt() { return new OperationReceipt("SUCCESS", null); }
}
