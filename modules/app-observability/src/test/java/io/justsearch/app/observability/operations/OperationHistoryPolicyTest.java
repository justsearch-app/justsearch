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
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationHistoryPolicyTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "history-policy", Optional.of("session"), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = InvocationProvenance.fromEngineContext(
      CONTEXT, ExecutorTag.AGENT, Instant.parse("2026-09-13T00:00:00.123456789Z"), Optional.of("private-intent"));
  private static final OperationDescriptor DESCRIPTOR = OperationDescriptor.invocation(
      OperationKind.MEMORY, "core.remember", "{}", false);
  @TempDir Path temp;
  private Path path() { return temp.resolve("operations.db"); }

  @Test
  void directAcceptanceFreezesPolicyAndProvenanceAcrossCompletionRetryAndRestart() throws Exception {
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(path())) {
      var row = store.accept(key, DESCRIPTOR, CONTEXT, PROVENANCE, OperationHistoryMode.STANDARD).record();
      assertEquals(OperationHistoryMode.STANDARD, row.historyMode());
      assertEquals(PROVENANCE.occurredAt(), row.provenanceOccurredAt());
      var retry = store.accept(key, DESCRIPTOR, CONTEXT, null, OperationHistoryMode.NONE);
      assertFalse(retry.created());
      assertEquals(row, retry.record(), "The first declaration wins without becoming a retry conflict");
      store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("COMPLETE", "undo-id"));
    }
    try (var store = new SqliteOperationStore(path())) {
      var row = store.find(key).orElseThrow();
      assertEquals(OperationState.COMPLETE, row.state());
      assertEquals(OperationHistoryMode.STANDARD, row.historyMode());
      assertEquals(PROVENANCE.occurredAt(), row.provenanceOccurredAt());
      assertEquals(PROVENANCE.executor().name(), row.executor());
      assertEquals(PROVENANCE.correlationId().orElseThrow(), row.correlationId());
      assertEquals(CONTEXT, row.context());
      assertEquals(row, store.accept(key, DESCRIPTOR, CONTEXT, PROVENANCE, OperationHistoryMode.UNDO).record());
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> store.accept(key,
              OperationDescriptor.invocation(OperationKind.MEMORY, "core.remember", "{\"changed\":true}", false),
              CONTEXT, PROVENANCE, OperationHistoryMode.STANDARD)).code());
    }
  }

  @Test
  void runnerAndPreparedAcceptancePreserveModeWhileDefaultProducersRemainHidden() throws Exception {
    try (var store = new SqliteOperationStore(path())) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, java.util.Set.of(OperationKind.NOTE));
      var descriptor = OperationDescriptor.invocation(OperationKind.NOTE, "core.file-note", "{}", false);
      var input = new OperationAttemptRunner.Request(null, descriptor, CONTEXT, PROVENANCE, OperationHistoryMode.UNDOABLE);
      var stable = runner.withPreparation(input, OperationAttemptRunner.PreparationScope::request);
      assertEquals(OperationHistoryMode.UNDOABLE, stable.historyMode());
      var preparation = new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false, "frozen"));
      runner.savePreparation(stable, preparation);
      var row = runner.acceptPrepared(stable, preparation.nonce()).accepted();
      assertEquals(OperationHistoryMode.UNDOABLE, row.historyMode());
      assertEquals(PROVENANCE.occurredAt(), row.provenanceOccurredAt());
      assertEquals(preparation, store.acceptedPreparation(row.id()).orElseThrow());
      var hidden = runner.accept(new OperationAttemptRunner.Request(null, DESCRIPTOR, CONTEXT, null)).accepted();
      assertEquals(OperationHistoryMode.NONE, hidden.historyMode());
      assertNull(hidden.provenanceOccurredAt());
      assertEquals(OperationHistoryMode.NONE,
          store.accept(hidden.key(), DESCRIPTOR, CONTEXT, PROVENANCE, OperationHistoryMode.STANDARD).record().historyMode());
    }
  }

  @Test
  void auditedAcceptanceRequiresAttributionAndSqliteRejectsUnknownModes() throws Exception {
    try (var store = new SqliteOperationStore(path())) {
      String key = OperationKeys.generate(CLOCK);
      assertThrows(NullPointerException.class,
          () -> store.accept(key, DESCRIPTOR, CONTEXT, null, OperationHistoryMode.STANDARD));
      assertTrue(store.find(key).isEmpty());
      store.accept(key, DESCRIPTOR, CONTEXT, null);
      assertThrows(SQLException.class, () -> sql("UPDATE operations SET history_mode='FULL_PAYLOAD'"));
      assertEquals(0, scalar("SELECT count(*) FROM pragma_table_info('operations') WHERE name LIKE '%token%'"));
    }
  }

  @Test
  void v3MigrationIsAtomicPreservesPreparedDataAndDoesNotInventHistoricalVisibility() throws Exception {
    try (var resource = getClass().getResourceAsStream("/operations-v3.sql")) {
      assertNotNull(resource);
      for (String statement : new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";")) {
        if (!statement.isBlank()) sql(statement);
      }
    }
    String key = OperationKeys.generate(CLOCK);
    String nonce = UUID.randomUUID().toString();
    sql("INSERT INTO operations(id,operation_key,kind,survival,urgency,state,identity_json,"
        + "client_kind,client_id,source_tier,transport,accepted_at,updated_at,preparation_nonce,preparation_sealed,preparation_payload) "
        + "VALUES(7,'" + key + "','note','INTERACTIVE','FOREGROUND','ACCEPTED','{}','INTERNAL','legacy','SYSTEM',"
        + "'SYSTEM_INTERNAL',1,1,'" + nonce + "',0,'frozen')");
    sql("UPDATE sqlite_sequence SET seq=900 WHERE name='operations'");
    assertThrows(java.io.IOException.class, () -> new SqliteOperationStore(path(), CLOCK, step -> {
      if (step.equals("before-schema-commit")) throw new java.io.IOException("migration interrupted");
    }));
    assertEquals(3, scalar("PRAGMA user_version"));
    assertEquals(0, scalar("SELECT count(*) FROM pragma_table_info('operations') WHERE name='history_mode'"));
    assertEquals(1, scalar("SELECT count(*) FROM operations WHERE preparation_payload='frozen'"));
    try (var store = new SqliteOperationStore(path())) {
      assertEquals(5, scalar("PRAGMA user_version"));
      var legacy = store.find(key).orElseThrow();
      assertEquals(OperationHistoryMode.NONE, legacy.historyMode());
      assertNull(legacy.provenanceOccurredAt());
      assertEquals("frozen", store.acceptedPreparation(7).orElseThrow().payload().value());
      assertEquals(901, store.accept(OperationKeys.generate(CLOCK), DESCRIPTOR, CONTEXT, null).record().id());
    }
  }

  private void sql(String sql) throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement();
        var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); }
  }
}
