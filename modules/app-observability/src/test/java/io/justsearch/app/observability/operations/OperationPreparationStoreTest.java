/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationPreparationStoreTest {
  @TempDir Path directory;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "preparation-test", Optional.empty(), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  private static final OperationDescriptor IDENTITY = OperationDescriptor.invocation(OperationKind.NOTE,
      "core.file-note", "{\"path\":\"C:/input\"}", false);

  private Path path() { return directory.resolve("operations.db"); }
  private SqliteOperationStore open() throws Exception { return new SqliteOperationStore(path(), clock, step -> {}); }
  private String key() { return OperationKeys.generate(clock); }
  private static OperationStore.Preparation preparation() {
    return new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false, "{\"target\":\"frozen-" + UUID.randomUUID() + "\"}"));
  }
  private void sql(String sql) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }
  private long scalar(String sql) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement();
        var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); }
  }

  @Test
  void pendingSurvivesReopenAndTransfersExactlyOnceWithoutEnteringHistoryEarly() throws Exception {
    String key = key(); var prepared = preparation(); long id;
    try (var store = open()) {
      assertEquals(prepared, store.savePreparation(key, IDENTITY, prepared).orElseThrow());
      assertTrue(store.find(key).isEmpty());
      assertTrue(store.openRecords().isEmpty());
      assertEquals(prepared, store.savePreparation(key, IDENTITY, preparation()).orElseThrow());
    }
    try (var store = open()) {
      assertEquals(prepared, store.pendingPreparation(key, IDENTITY).orElseThrow());
      var accepted = store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce());
      assertTrue(accepted.created()); id = accepted.record().id();
      assertEquals(prepared, store.acceptedPreparation(id).orElseThrow());
      assertEquals(0, scalar("SELECT count(*) FROM operation_preparations"));
      assertFalse(store.acceptPrepared(key, IDENTITY, CONTEXT, null, UUID.randomUUID()).created());
      assertTrue(store.savePreparation(key, IDENTITY, preparation()).isEmpty());
    }
    try (var store = open()) {
      assertEquals(prepared, store.acceptedPreparation(id).orElseThrow());
      assertFalse(store.find(key).orElseThrow().toString().contains("frozen"));
    }
  }

  @Test
  void transferFailureRollsBackAcceptanceAndKeepsPendingPayload() throws Exception {
    String key = key(); var prepared = preparation();
    try (var store = open()) {
      store.savePreparation(key, IDENTITY, prepared);
      sql("CREATE TRIGGER refuse_pending_delete BEFORE DELETE ON operation_preparations BEGIN SELECT RAISE(ABORT,'fixture'); END");
      assertThrows(OperationStoreException.class,
          () -> store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce()));
      assertTrue(store.find(key).isEmpty(), "acceptance and payload transfer must roll back together");
      assertEquals(prepared, store.pendingPreparation(key, IDENTITY).orElseThrow());
      sql("DROP TRIGGER refuse_pending_delete");
      assertTrue(store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce()).created());
    }
  }

  @Test
  void changedPublicInputConflictsAndRawAcceptanceCannotBypassPendingPreparation() throws Exception {
    String key = key(); var prepared = preparation();
    var changed = OperationDescriptor.invocation(IDENTITY.kind(), IDENTITY.operationRef(), "{}", false);
    try (var store = open()) {
      store.savePreparation(key, IDENTITY, prepared);
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> store.pendingPreparation(key, changed)).code());
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> store.savePreparation(key, changed, preparation())).code());
      assertEquals(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE,
          assertThrows(OperationStoreException.class, () -> store.accept(key, IDENTITY, CONTEXT, null)).code());
      assertTrue(store.find(key).isEmpty());
      assertTrue(store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce()).created());
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class,
              () -> store.acceptPrepared(key, changed, CONTEXT, null, prepared.nonce())).code());
    }
  }

  @Test
  void fiveMinuteExpiryDoesNotRefreshOnRetryAndStaleNonceCannotAcceptReplacement() throws Exception {
    String key = key(); var prepared = preparation();
    try (var store = open()) {
      store.savePreparation(key, IDENTITY, prepared);
      clock.setMillis(clock.millis() + 299_999);
      assertEquals(prepared, store.savePreparation(key, IDENTITY, preparation()).orElseThrow());
      clock.setMillis(clock.millis() + 1);
      assertTrue(store.pendingPreparation(key, IDENTITY).isEmpty());
      assertEquals(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE,
          assertThrows(OperationStoreException.class,
              () -> store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce())).code());
      var replacement = preparation();
      store.savePreparation(key, IDENTITY, replacement);
      assertEquals(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE,
          assertThrows(OperationStoreException.class,
              () -> store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce())).code());
      assertEquals(replacement, store.pendingPreparation(key, IDENTITY).orElseThrow());
      assertTrue(store.acceptPrepared(key, IDENTITY, CONTEXT, null, replacement.nonce()).created());
    }
  }

  @Test
  void capEvictsOldestPendingWithoutMovingHistoryFence() throws Exception {
    try (var store = open()) {
      String first = key(); var original = preparation(); store.savePreparation(first, IDENTITY, original);
      for (int i = 0; i < 512; i++) {
        clock.setMillis(clock.millis() + 1); store.savePreparation(key(), IDENTITY, preparation());
      }
      assertEquals(512, scalar("SELECT count(*) FROM operation_preparations"));
      assertTrue(store.pendingPreparation(first, IDENTITY).isEmpty());
      assertEquals(0, store.historySinceMillis());
      assertEquals(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE,
          assertThrows(OperationStoreException.class,
              () -> store.acceptPrepared(first, IDENTITY, CONTEXT, null, original.nonce())).code());
      clock.setMillis(clock.millis() + 300_000); store.pruneHistory();
      assertEquals(0, scalar("SELECT count(*) FROM operation_preparations"));
      assertEquals(0, store.historySinceMillis());
    }
  }

  @Test
  void payloadRetentionFollowsItsTerminalOperationRow() throws Exception {
    String key = key(); var prepared = preparation();
    try (var store = open()) {
      store.savePreparation(key, IDENTITY, prepared);
      long id = store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce()).record().id();
      store.finish(id, OperationState.COMPLETE, new OperationReceipt("COMPLETE", null));
      clock.setMillis(clock.millis() + java.time.Duration.ofDays(30).toMillis() + 1);
      store.pruneHistory();
      assertTrue(store.acceptedPreparation(id).isEmpty());
      assertTrue(store.find(key).isEmpty());
      assertEquals(OperationStoreException.Code.OPERATION_EXPIRED,
          assertThrows(OperationStoreException.class, () -> store.savePreparation(key, IDENTITY, preparation())).code());
    }
  }

  @Test
  void sqliteRejectsOversizedAndUnpairedAcceptedPayloads() throws Exception {
    try (var store = open()) {
      var row = store.accept(key(), IDENTITY, CONTEXT, null).record();
      assertThrows(SQLException.class,
          () -> sql("UPDATE operations SET preparation_payload='unpaired' WHERE id=" + row.id()));
      String key = key(); var prepared = preparation(); store.savePreparation(key, IDENTITY, prepared);
      assertThrows(SQLException.class, () -> sql("UPDATE operation_preparations SET payload=replace(hex(zeroblob(375001)), '0', 'x')"));
      store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce());
      assertThrows(SQLException.class, () -> sql("UPDATE operations SET preparation_payload=replace(hex(zeroblob(375001)), '0', 'x') WHERE preparation_nonce IS NOT NULL"));
    }
  }

  @Test
  void v2MigrationPreservesSequenceAndRollbackRestoresOldSchema() throws Exception {
    try (var resource = getClass().getResourceAsStream("/operations-v2.sql")) {
      assertNotNull(resource);
      for (String statement : new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";")) {
        if (!statement.isBlank()) sql(statement);
      }
    }
    sql("INSERT INTO operations(id,operation_key,kind,survival,urgency,state,identity_json,"
        + "client_kind,client_id,source_tier,transport,accepted_at,updated_at) VALUES(7,'" + key()
        + "','note','INTERACTIVE','FOREGROUND','ACCEPTED','{}','INTERNAL','legacy','SYSTEM','SYSTEM_INTERNAL',1,1)");
    sql("UPDATE sqlite_sequence SET seq=900 WHERE name='operations'");
    assertThrows(java.io.IOException.class, () -> new SqliteOperationStore(path(), clock, step -> {
      if (step.equals("before-schema-commit")) throw new java.io.IOException("fixture");
    }));
    assertEquals(2, scalar("PRAGMA user_version"));
    assertEquals(1, scalar("SELECT count(*) FROM operations"));
    assertEquals(0, scalar("SELECT count(*) FROM pragma_table_info('operations') WHERE name='preparation_payload'"));
    assertEquals(0, scalar("SELECT count(*) FROM sqlite_master WHERE name='operation_preparations'"));
    try (var store = open()) {
      assertEquals(3, scalar("PRAGMA user_version"));
      assertEquals(17, store.historySinceMillis());
      assertEquals(7, store.openRecords().getFirst().id());
      assertTrue(store.acceptedPreparation(7).isEmpty());
      assertEquals(901, store.accept(key(), IDENTITY, CONTEXT, null).record().id());
    }
  }
}
