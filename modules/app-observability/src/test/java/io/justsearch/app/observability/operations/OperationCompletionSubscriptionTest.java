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
        } catch (Exception failure) {
          throw new AssertionError("Completion must be committed and unlocked", failure);
        }
      })) {
        for (var kind : List.of(OperationKind.MEMORY, OperationKind.NOTE)) {
          int before = observed.size();
          var row = accept(store, kind);
          assertTrue(store.start(row.id()));
          assertEquals(before, observed.size(), "acceptance/start are not terminal notifications");
          var completed = store.finish(row.id(), OperationState.COMPLETE, receipt()).orElseThrow();
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
