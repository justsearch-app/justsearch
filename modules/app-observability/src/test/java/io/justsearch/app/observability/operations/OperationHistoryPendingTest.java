/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationHistoryPendingTest {
  @TempDir Path directory;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "projection", Optional.empty(), Optional.empty(), "SYSTEM", "AGENT_LOOP",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private Path path() { return directory.resolve("operations.db"); }
  private SqliteOperationStore open() throws Exception { return new SqliteOperationStore(path(), clock, step -> {}); }
  private OperationRecord accept(SqliteOperationStore store, OperationKind kind, OperationHistoryMode mode) {
    return store.accept(OperationKeys.generate(clock), OperationDescriptor.invocation(kind, "core.test", "{}", false),
        CONTEXT, InvocationProvenance.fromEngineContext(CONTEXT, ExecutorTag.AGENT, clock.instant(), Optional.empty()), mode).record();
  }
  private void sql(String sql) throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement()) { statement.execute(sql); }
  }
  private long scalar(String sql) throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next()); return rows.getLong(1);
    }
  }

  @Test
  void directMemoryNoteAndPrestartRejectionOweProjectionButHiddenRowsDoNot() throws Exception {
    try (var store = open()) {
      var memory = accept(store, OperationKind.MEMORY, OperationHistoryMode.STANDARD);
      var note = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      var hidden = accept(store, OperationKind.OPERATION, OperationHistoryMode.NONE);
      assertTrue(store.pendingHistoryProjection(256).isEmpty());
      store.finish(memory.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      store.rejectBeforeStart(note.id(), new OperationReceipt("DECLINED", null));
      store.finish(hidden.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      var pending = store.pendingHistoryProjection(256);
      assertEquals(List.of(memory.key(), note.key()), pending.stream().map(OperationHistoryRow::key).toList());
      assertEquals(OperationState.FAILED, pending.getLast().state());
      assertTrue(store.acknowledgeHistoryProjection(note.key()));
      assertFalse(store.acknowledgeHistoryProjection(note.key()));
      store.rejectBeforeStart(note.id(), new OperationReceipt("SECOND_REFUSAL", null));
      assertTrue(store.finish(note.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isEmpty());
      assertEquals(List.of(memory.key()), store.pendingHistoryProjection(256).stream().map(OperationHistoryRow::key).toList());
    }
  }

  @Test
  void startupPagesRemainBoundedAndDoNotSkipRowsWhenAcknowledgementsShiftPositions() throws Exception {
    try (var store = open()) {
      for (int i = 0; i < 258; i++) {
        var row = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD);
        store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      }
      var first = store.pendingHistoryProjection(1000);
      assertEquals(256, first.size());
      var cursor = first.getLast();
      for (var row : first) assertTrue(store.acknowledgeHistoryProjection(row.key()));
      var next = store.pendingHistoryProjectionAfter(1000, cursor.completedAt(), cursor.id(), store.historyProjectionUpperId());
      assertEquals(2, next.size(), "keyset paging cannot skip rows when earlier pending positions disappear");
      assertTrue(store.pendingHistoryProjectionAfter(0, cursor.completedAt(), cursor.id(), store.historyProjectionUpperId()).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> store.pendingHistoryProjectionAfter(-1, cursor.completedAt(), cursor.id(), store.historyProjectionUpperId()));
      assertThrows(IllegalArgumentException.class, () -> store.pendingHistoryProjectionAfter(1, cursor.completedAt(), -1, store.historyProjectionUpperId()));
    }
  }

  @Test
  void startupCursorUsesCompletionTimeBeforeIdAndDoesNotIncludeAcknowledgedRows() throws Exception {
    try (var store = open()) {
      var older = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      var first = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD);
      store.finish(first.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      var cursor = store.pendingHistoryProjection(1).getFirst();
      clock.setMillis(clock.millis() + 1);
      store.finish(older.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      assertEquals(List.of(older.key()), store.pendingHistoryProjectionAfter(256, cursor.completedAt(), cursor.id(), store.historyProjectionUpperId())
          .stream().map(OperationHistoryRow::key).toList());
      assertTrue(store.acknowledgeHistoryProjection(older.key()));
      assertTrue(store.pendingHistoryProjectionAfter(256, cursor.completedAt(), cursor.id(), store.historyProjectionUpperId()).isEmpty());
    }
  }

  @Test
  void pendingSurvivesRestartAndAgePruningUntilKeyScopedAcknowledgement() throws Exception {
    String key;
    try (var store = open()) {
      var row = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD); key = row.key();
      store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
    }
    clock.setMillis(clock.millis() + OperationStore.HISTORY_RETENTION.toMillis() + 1);
    try (var store = open()) {
      assertEquals(key, store.pendingHistoryProjection(1).getFirst().key());
      store.pruneHistory(); assertTrue(store.find(key).isPresent());
      assertFalse(store.acknowledgeHistoryProjection(OperationKeys.generate(clock)));
      var before = store.find(key).orElseThrow();
      assertTrue(store.acknowledgeHistoryProjection(key));
      assertEquals(before, store.find(key).orElseThrow(), "Acknowledgement cannot rewrite the accepted outcome");
      store.pruneHistory(); assertTrue(store.find(key).isEmpty());
      assertEquals(OperationOutcomeView.State.EXPIRED, store.outcome(key).state());
    }
  }

  @Test
  void acknowledgementFailureLeavesPendingAndTerminalFailureCannotCreatePending() throws Exception {
    try (var store = open()) {
      var row = accept(store, OperationKind.MEMORY, OperationHistoryMode.STANDARD);
      sql("CREATE TRIGGER refuse_terminal BEFORE UPDATE ON operations WHEN NEW.history_pending = 1 "
          + "BEGIN SELECT RAISE(ABORT, 'terminal fault'); END");
      assertThrows(OperationStoreException.class, () -> store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)));
      assertEquals(OperationState.ACCEPTED, store.find(row.key()).orElseThrow().state());
      assertTrue(store.pendingHistoryProjection(256).isEmpty()); sql("DROP TRIGGER refuse_terminal");
      store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      sql("CREATE TRIGGER refuse_ack BEFORE UPDATE ON operations WHEN OLD.history_pending = 1 AND NEW.history_pending = 0 "
          + "BEGIN SELECT RAISE(ABORT, 'ack fault'); END");
      assertThrows(OperationStoreException.class, () -> store.acknowledgeHistoryProjection(row.key()));
      assertEquals(row.key(), store.pendingHistoryProjection(1).getFirst().key());
      assertEquals(OperationState.COMPLETE, store.find(row.key()).orElseThrow().state());
    }
  }

  @Test
  void pendingCapBackpressuresAdmissionRatherThanEvictingUndeliveredRows() throws Exception {
    try (var store = open()) {
      var first = accept(store, OperationKind.MEMORY, OperationHistoryMode.STANDARD);
      store.finish(first.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      String prefix = OperationKeys.generate(clock).substring(0, 24);
      sql("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<99999) "
          + "INSERT INTO operations(operation_key,kind,survival,urgency,state,identity_json,accepted_at,updated_at,completed_at,history_pending) "
          + "SELECT '" + prefix + "'||printf('%012x',x),'operation','INTERACTIVE','BACKGROUND','COMPLETE','{}',1,1," + clock.millis() + ",1 FROM n");
      assertEquals(OperationStoreException.Code.OPERATIONS_CAPACITY,
          assertThrows(OperationStoreException.class, () -> accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD)).code());
      assertEquals(100000, scalar("SELECT count(*) FROM operations"));
      assertTrue(store.find(first.key()).isPresent()); assertEquals(0, store.historySinceMillis());
      assertTrue(store.acknowledgeHistoryProjection(first.key()));
      clock.setMillis(clock.millis() + 1);
      assertNotNull(accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD));
      assertTrue(store.find(first.key()).isEmpty());
      assertEquals(100000, scalar("SELECT count(*) FROM operations"));
    }
  }

  @Test
  void boundedDrainHasNoRowIdWatermarkAndAcknowledgedRowsDoNotReplayOnRestart() throws Exception {
    String late;
    try (var store = open()) {
      var oldest = accept(store, OperationKind.MEMORY, OperationHistoryMode.STANDARD); late = oldest.key();
      for (int i = 0; i < 260; i++) {
        var row = accept(store, OperationKind.NOTE, OperationHistoryMode.STANDARD);
        store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
        clock.setMillis(clock.millis() + 1);
      }
      var first = store.pendingHistoryProjection(Integer.MAX_VALUE); assertEquals(256, first.size());
      first.forEach(row -> assertTrue(store.acknowledgeHistoryProjection(row.key())));
      assertEquals(4, store.pendingHistoryProjection(256).size());
      store.finish(oldest.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      assertEquals(late, store.pendingHistoryProjection(256).getLast().key());
      assertTrue(store.pendingHistoryProjection(0).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> store.pendingHistoryProjection(-1));
    }
    try (var store = open()) { assertEquals(5, store.pendingHistoryProjection(256).size()); }
  }

  @Test
  void frozenV4MigrationRollsBackAndOnlyFutureTransitionsAcquireDeliveryObligation() throws Exception {
    try (var resource = getClass().getResourceAsStream("/operations-v4.sql")) {
      assertNotNull(resource);
      for (String statement : new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";")) {
        if (!statement.isBlank()) sql(statement);
      }
    }
    String key = OperationKeys.generate(clock);
    sql("INSERT INTO operations(operation_key,kind,survival,urgency,state,identity_json,operation_ref,client_kind,client_id,source_tier,transport,executor,accepted_at,updated_at,history_mode,provenance_occurred_at) "
        + "VALUES('" + key + "','note','INTERACTIVE','BACKGROUND','ACCEPTED','{}','core.test','INTERNAL','legacy','SYSTEM','AGENT_LOOP','AGENT',1,1,'STANDARD','2026-09-13T00:00:00Z')");
    String prior = OperationKeys.generate(clock);
    sql("INSERT INTO operations(operation_key,kind,survival,urgency,state,identity_json,operation_ref,client_kind,client_id,source_tier,transport,executor,accepted_at,updated_at,completed_at,history_mode,provenance_occurred_at) "
        + "VALUES('" + prior + "','note','INTERACTIVE','BACKGROUND','COMPLETE','{}','core.test','INTERNAL','legacy','SYSTEM','AGENT_LOOP','AGENT',1,1," + clock.millis() + ",'STANDARD','2026-09-13T00:00:00Z')");
    assertThrows(java.io.IOException.class, () -> new SqliteOperationStore(path(), clock, step -> {
      if (step.equals("before-schema-commit")) throw new java.io.IOException("migration fault");
    }));
    assertEquals(4, scalar("PRAGMA user_version"));
    assertEquals(0, scalar("SELECT count(*) FROM pragma_table_info('operations') WHERE name='history_pending'"));
    try (var store = open()) {
      assertEquals(5, scalar("PRAGMA user_version")); assertTrue(store.pendingHistoryProjection(256).isEmpty());
      assertEquals(prior, store.recentHistory(200).getFirst().key(), "Existing history remains readable without retroactive ledger replay");
      var row = store.find(key).orElseThrow(); store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      assertEquals(key, store.pendingHistoryProjection(1).getFirst().key());
      assertThrows(SQLException.class, () -> sql("UPDATE operations SET history_pending=2"));
      assertEquals(2, scalar("SELECT count(*) FROM operations"));
    }
  }
}
