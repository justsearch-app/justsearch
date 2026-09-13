/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationRetentionTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
  private static final OperationDescriptor DESCRIPTOR = OperationDescriptor.invocation(
      OperationKind.OPERATION, "core.test", "{}", false);
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "retention", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  @TempDir Path temp;

  @Test
  void agedTerminalExpiresWhileOlderOpenRowAndBoundaryKeyRemainUsable() throws Exception {
    Path path = temp.resolve("operations.db");
    String terminal = OperationKeys.generate(CLOCK);
    String open = OperationKeys.generate(Clock.offset(CLOCK, Duration.ofDays(-1)));
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      store.accept(open, DESCRIPTOR, CONTEXT, null);
      var row = store.accept(terminal, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
    }
    Clock later = Clock.offset(CLOCK, Duration.ofDays(31));
    try (var store = new SqliteOperationStore(path, later, step -> {})) {
      assertTrue(store.find(terminal).isEmpty(), "aged terminal row must be evicted");
      assertEquals(CLOCK.millis() + 1, store.historySinceMillis());
      assertEquals(OperationStoreException.Code.OPERATION_EXPIRED,
          assertThrows(OperationStoreException.class,
              () -> store.accept(terminal, DESCRIPTOR, CONTEXT, null)).code(), "expired, never unknown");
      assertFalse(store.accept(open, DESCRIPTOR, CONTEXT, null).created(), "present row wins below fence");
      String boundary = OperationKeys.generate(Clock.offset(CLOCK, Duration.ofMillis(1)));
      assertTrue(store.accept(boundary, DESCRIPTOR, CONTEXT, null).created(), "no extra lookup margin");
    }
    try (var store = new SqliteOperationStore(path, later, step -> {})) {
      assertEquals(CLOCK.millis() + 1, store.historySinceMillis(), "fence cannot regress on reopen");
    }
  }

  @Test
  void capRefusesAllOpenRowsAndPreservesExistingKey() throws Exception {
    Path path = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      String existing = OperationKeys.generate(CLOCK);
      store.accept(existing, DESCRIPTOR, CONTEXT, null);
      seed(path, 99999, "ACCEPTED", CLOCK.millis());
      var failure = assertThrows(OperationStoreException.class,
          () -> store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null));
      assertEquals("OPERATIONS_CAPACITY", failure.code().name());
      assertEquals(100000, count(path));
      assertFalse(store.accept(existing, DESCRIPTOR, CONTEXT, null).created());
      assertEquals(0, store.historySinceMillis());
    }
  }

  @Test
  void capEvictsOldestTerminalAndAdvancesFencePastAdmittedFutureKey() throws Exception {
    Path path = temp.resolve("operations.db");
    Clock future = Clock.offset(CLOCK, Duration.ofMinutes(5));
    String evicted = OperationKeys.generate(future);
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      var row = store.accept(evicted, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
      seed(path, 99999, "ACCEPTED", CLOCK.millis());
      // A later admitted key avoids the fence produced by the eviction itself.
    }
    Clock afterFence = Clock.offset(future, Duration.ofMillis(1));
    try (var store = new SqliteOperationStore(path, afterFence, step -> {})) {
      assertTrue(store.accept(OperationKeys.generate(afterFence), DESCRIPTOR, CONTEXT, null).created());
      assertEquals(100000, count(path));
      assertTrue(store.find(evicted).isEmpty());
      assertEquals(future.millis() + 1, store.historySinceMillis());
      assertEquals(OperationStoreException.Code.OPERATION_EXPIRED,
          assertThrows(OperationStoreException.class,
              () -> store.accept(evicted, DESCRIPTOR, CONTEXT, null)).code());
    }
  }

  @Test
  void failedFenceUpdateRollsBackEviction() throws Exception {
    Path path = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      var row = store.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
    }
    execute(path, "CREATE TRIGGER refuse_fence BEFORE UPDATE ON operations_meta "
        + "WHEN NEW.history_since_ms > OLD.history_since_ms BEGIN SELECT RAISE(ABORT, 'fixture'); END");
    assertThrows(Exception.class,
        () -> new SqliteOperationStore(path, Clock.offset(CLOCK, Duration.ofDays(31)), step -> {}));
    assertEquals(1, count(path), "eviction and fence are one transaction");
  }

  @Test
  void sqlRejectsOversizedIdentityAndCheckpoint() throws Exception {
    Path path = temp.resolve("operations.db");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      var row = store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.start(row.id()));
      assertThrows(SQLException.class, () -> execute(path,
          "UPDATE operations SET identity_json = replace(hex(zeroblob(131073)), '0', 'x')"));
      assertThrows(SQLException.class, () -> execute(path,
          "UPDATE operations SET checkpoint_cursor = replace(hex(zeroblob(2049)), '0', 'x')"));
      assertTrue(store.checkpoint(row.id(), "x".repeat(4096), 1, 0));
      assertThrows(OperationStoreException.class, () -> store.checkpoint(row.id(), "é".repeat(2049), 2, 0));
    }
  }

  @Test
  void v1MigrationPreservesRowsFenceAndEvictedOrderingSequence() throws Exception {
    Path path = temp.resolve("operations.db");
    createV1(path);
    seed(path, 2, "ACCEPTED", CLOCK.millis());
    execute(path, "UPDATE operations SET id = 900 WHERE id = 2");
    execute(path, "UPDATE sqlite_sequence SET seq = 901 WHERE name = 'operations'");
    execute(path, "DELETE FROM operations WHERE id = 900");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      assertEquals(1, count(path));
      assertEquals(1, store.openRecords().getFirst().id());
      assertEquals(17, store.historySinceMillis());
      assertEquals(902, store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null).record().id());
      assertThrows(SQLException.class, () -> execute(path,
          "UPDATE operations SET checkpoint_cursor = replace(hex(zeroblob(2049)), '0', 'x')"));
    }
  }

  @Test
  void emptyV1MigrationCannotReuseDeletedIds() throws Exception {
    Path path = temp.resolve("operations.db");
    createV1(path);
    seed(path, 1, "ACCEPTED", CLOCK.millis());
    execute(path, "UPDATE sqlite_sequence SET seq = 900 WHERE name = 'operations'");
    execute(path, "DELETE FROM operations");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      assertEquals(901, store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null).record().id());
    }
  }

  @Test
  void interruptedV1MigrationRollsBackVersionRowsAndBounds() throws Exception {
    Path path = temp.resolve("operations.db");
    createV1(path);
    seed(path, 1, "ACCEPTED", CLOCK.millis());
    assertThrows(java.io.IOException.class, () -> new SqliteOperationStore(path, CLOCK, step -> {
      if (step.equals("before-schema-commit")) throw new java.io.IOException("fixture");
    }));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt(1));
    }
    assertEquals(1, count(path));
    // A true rollback leaves v1's unconstrained column, not a half-migrated v2 table.
    execute(path, "UPDATE operations SET checkpoint_cursor = replace(hex(zeroblob(2049)), '0', 'x')");
    assertThrows(SQLException.class, () -> new SqliteOperationStore(path, CLOCK, step -> {}));
    assertEquals(1, count(path), "invalid legacy content is preserved, never silently discarded");
    execute(path, "UPDATE operations SET checkpoint_cursor = NULL");
    try (var store = new SqliteOperationStore(path, CLOCK, step -> {})) {
      assertEquals(1, store.openRecords().size());
    }
  }

  @Test
  void hourlyPruneKeepsExactThirtyDayBoundaryAndOpenGaps() throws Exception {
    Path path = temp.resolve("operations.db");
    var clock = new OperationTestClock(CLOCK.millis());
    String key = OperationKeys.generate(CLOCK);
    String gaps = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(path, clock, step -> {})) {
      var row = store.accept(key, DESCRIPTOR, CONTEXT, null).record();
      var open = store.accept(gaps, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).isPresent());
      execute(path, "UPDATE operations SET state = 'COMPLETE_WITH_GAPS' WHERE id = " + open.id());
      clock.setMillis(CLOCK.millis() + Duration.ofDays(30).toMillis());
      store.pruneHistory();
      assertTrue(store.find(key).isPresent(), "exact retention boundary survives");
      clock.setMillis(CLOCK.millis() + Duration.ofDays(30).toMillis() + 1);
      store.pruneHistory();
      assertTrue(store.find(key).isEmpty());
      assertEquals(OperationState.COMPLETE_WITH_GAPS, store.find(gaps).orElseThrow().state());
      long fence = store.historySinceMillis();
      clock.setMillis(CLOCK.millis());
      store.pruneHistory();
      assertEquals(fence, store.historySinceMillis(), "clock rollback cannot reset the fence");
    }
  }

  @Test
  void clockCrossingQuarantineFenceRestoresAcceptanceWithoutDuplicate() throws Exception {
    Path path = temp.resolve("operations.db");
    java.nio.file.Files.writeString(path, "corrupt store");
    var clock = new OperationTestClock(CLOCK.millis());
    try (var store = new SqliteOperationStore(path, clock, step -> {})) {
      var refusal = assertThrows(OperationStoreException.class,
          () -> store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null));
      assertEquals(OperationStoreException.Code.OPERATION_EXPIRED, refusal.code());
      assertEquals(300001, refusal.retryAfterMillis());
      assertEquals(0, count(path));
      Clock boundary = Clock.offset(CLOCK, Duration.ofMillis(refusal.retryAfterMillis()));
      clock.setMillis(boundary.millis());
      String key = OperationKeys.generate(boundary);
      assertTrue(store.accept(key, DESCRIPTOR, CONTEXT, null).created());
      assertFalse(store.accept(key, DESCRIPTOR, CONTEXT, null).created());
      assertEquals(1, count(path));
      assertEquals(boundary.millis(), store.historySinceMillis());
    }
  }

  private static void createV1(Path path) throws Exception {
    try (var resource = OperationRetentionTest.class.getResourceAsStream("/operations-v1.sql")) {
      assertNotNull(resource);
      String ddl = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      for (String sql : ddl.split(";")) if (!sql.isBlank()) execute(path, sql);
    }
    execute(path, "INSERT INTO operations_meta VALUES (1, 17, " + CLOCK.millis() + ", NULL)");
  }

  private static void seed(Path path, int count, String state, long timestamp) throws Exception {
    String prefix = OperationKeys.generate(Clock.fixed(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
        .substring(0, 24);
    execute(path, "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x < " + count + ") "
        + "INSERT INTO operations(operation_key, kind, survival, urgency, state, identity_json,"
        + " client_kind, client_id, source_tier, transport, accepted_at, updated_at) "
        + "SELECT '" + prefix + "' || printf('%012x', x), 'operation', 'INTERACTIVE', 'BACKGROUND', '"
        + state + "', '{}', 'INTERNAL', 'seed', 'system', 'SYSTEM_INTERNAL', " + timestamp + ", " + timestamp + " FROM n");
  }

  private static long count(Path path) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM operations")) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static void execute(Path path, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
