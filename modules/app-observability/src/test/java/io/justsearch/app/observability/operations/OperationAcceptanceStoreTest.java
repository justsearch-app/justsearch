/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationAcceptanceStoreTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  @TempDir Path temp;

  @Test
  void concurrentAcceptanceAndStartHaveOneWinner() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {});
        var pool = Executors.newFixedThreadPool(4)) {
      String key = OperationKeys.generate(CLOCK);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<OperationStore.Acceptance>>();
      for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return store.accept(key, descriptor("{}", false), CONTEXT, null);
      }));
      start.countDown();
      int winners = 0;
      long id = -1;
      for (var future : futures) {
        var accepted = future.get(5, TimeUnit.SECONDS);
        if (accepted.created()) winners++;
        if (id == -1) id = accepted.record().id();
        assertEquals(id, accepted.record().id());
      }
      assertEquals(1, winners);
      assertTrue(store.start(id));
      assertFalse(store.start(id));
      assertEquals(1, store.find(key).orElseThrow().attempts());
    }
  }

  @Test
  void canonicalIdentitySeparatesArgumentsOperationAndUndo() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {})) {
      String key = OperationKeys.generate(CLOCK);
      var accepted = store.accept(key, descriptor("{\"a\":1,\"b\":2}", false), CONTEXT, null);
      assertFalse(store.accept(key, descriptor("{ \"b\": 2, \"a\": 1 }", false), CONTEXT, null).created());
      for (OperationDescriptor different : java.util.List.of(descriptor("{\"a\":2}", false),
          descriptor("{\"a\":1,\"b\":2}", true),
          OperationDescriptor.invocation(OperationKind.OPERATION, "core.other", "{\"a\":1,\"b\":2}", false))) {
        var failure = assertThrows(OperationStoreException.class,
            () -> store.accept(key, different, CONTEXT, null));
        assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, failure.code());
      }
      assertEquals(accepted.record(), store.find(key).orElseThrow());
    }
  }

  @Test
  void acceptanceFailureLeavesNoRowAndNeverReturnsSuccess() throws Exception {
    Path db = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      execute(db, "CREATE TRIGGER refuse_accept BEFORE INSERT ON operations "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
      var failure = assertThrows(OperationStoreException.class,
          () -> store.accept(OperationKeys.generate(CLOCK), descriptor("{}", false), CONTEXT, null));
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
      assertTrue(store.openRecords().isEmpty());
      execute(db, "DROP TRIGGER refuse_accept");
      assertTrue(store.accept(OperationKeys.generate(CLOCK), descriptor("{}", false), CONTEXT, null).created());
    }
  }

  @Test
  void terminalReceiptSurvivesReopenAndCannotReverseOrCheckpoint() throws Exception {
    Path db = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      var row = store.accept(key, descriptor("{\"body\":\"private document sentinel\"}", false), CONTEXT, null).record();
      assertTrue(store.start(row.id()));
      assertTrue(store.checkpoint(row.id(), "unit-4", 4, 1));
      assertFalse(store.checkpoint(row.id(), "unit-3", 3, 1));
      assertTrue(store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", "batch-42")));
      assertFalse(store.finish(row.id(), OperationState.FAILED, new OperationReceipt("LATE_ERROR", null)));
      assertFalse(store.checkpoint(row.id(), "unit-5", 5, 1));
      assertFalse(store.start(row.id()));
    }
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      var row = store.find(key).orElseThrow();
      assertEquals(OperationState.COMPLETE, row.state());
      assertEquals(4, row.unitsCompleted());
      assertEquals("batch-42", row.receipt().executionId());
      assertFalse(row.descriptor().identityJson().contains("private document sentinel"));
      assertTrue(store.openRecords().isEmpty());
    }
    assertFalse(new String(java.nio.file.Files.readAllBytes(db), java.nio.charset.StandardCharsets.ISO_8859_1)
        .contains("private document sentinel"));
  }

  @Test
  void rowFirstLookupSurvivesAnAdvancedExpiryFence() throws Exception {
    Path db = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(db, CLOCK, step -> {})) {
      store.accept(key, descriptor("{}", false), CONTEXT, null);
      execute(db, "UPDATE operations_meta SET history_since_ms = " + (CLOCK.millis() + 1));
      assertFalse(store.accept(key, descriptor("{}", false), CONTEXT, null).created());
      var failure = assertThrows(OperationStoreException.class,
          () -> store.accept(OperationKeys.generate(CLOCK), descriptor("{}", false), CONTEXT, null));
      assertEquals(OperationStoreException.Code.OPERATION_EXPIRED, failure.code());
      assertTrue(store.find(key).isPresent());
    }
  }

  @Test
  void futureAndNonV7KeysAreRefusedBeforeAnyRow() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"), CLOCK, step -> {})) {
      for (String key : java.util.List.of(java.util.UUID.randomUUID().toString(),
          OperationKeys.generate(Clock.offset(CLOCK, java.time.Duration.ofMinutes(6))))) {
        var failure = assertThrows(OperationStoreException.class,
            () -> store.accept(key, descriptor("{}", false), CONTEXT, null));
        assertEquals(OperationStoreException.Code.INVALID_OPERATION_KEY, failure.code());
      }
      assertTrue(store.openRecords().isEmpty());
    }
  }

  private static OperationDescriptor descriptor(String arguments, boolean undo) {
    return OperationDescriptor.invocation(OperationKind.OPERATION, "core.test", arguments, undo);
  }

  private static void execute(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
