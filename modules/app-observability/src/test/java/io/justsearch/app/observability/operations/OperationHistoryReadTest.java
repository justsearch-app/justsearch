/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerProjection;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OperationHistoryReadTest {
  @TempDir Path directory;
  private final OperationTestClock clock = new OperationTestClock(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli());
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "history-read", Optional.of("session"), Optional.empty(), "SYSTEM", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final InvocationProvenance PROVENANCE = InvocationProvenance.fromEngineContext(
      CONTEXT, ExecutorTag.AGENT, Instant.parse("2026-09-12T23:59:59.123456789Z"), Optional.of("private-intent-token"));
  private Path path() { return directory.resolve("operations.db"); }
  private SqliteOperationStore open() throws Exception { return new SqliteOperationStore(path(), clock, step -> {}); }

  private OperationRecord accept(SqliteOperationStore store, OperationHistoryMode mode) {
    return store.accept(OperationKeys.generate(clock), OperationDescriptor.invocation(OperationKind.MEMORY,
        "core.repeat", "{\"private\":\"private-caller-text\"}", false), CONTEXT, PROVENANCE, mode).record();
  }

  @Test
  void restartReadsOnlyVisibleTerminalRowsWithTheSameLiveProjectionAndNoPrivateValues() throws Exception {
    List<OperationHistoryEntry> expected = new ArrayList<>();
    try (var store = open()) {
      for (var mode : OperationHistoryMode.values()) {
        var row = accept(store, mode);
        var completed = store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", "undo-id")).orElseThrow();
        if (mode != OperationHistoryMode.NONE) expected.add(OperationHistoryProjection.entry(OperationHistoryRow.from(completed)));
        clock.setMillis(clock.millis() + 1);
      }
      for (var state : List.of(OperationState.FAILED, OperationState.CANCELLED)) {
        var row = accept(store, OperationHistoryMode.STANDARD);
        var completed = store.finish(row.id(), state, new OperationReceipt("DECLINED", null)).orElseThrow();
        expected.add(OperationHistoryProjection.entry(OperationHistoryRow.from(completed)));
        clock.setMillis(clock.millis() + 1);
      }
      accept(store, OperationHistoryMode.STANDARD); // open acceptance cannot enter terminal history
      var gaps = accept(store, OperationHistoryMode.STANDARD);
      // D1 owns publishing this reserved nonterminal state; seed it without inventing a terminal write.
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = db.createStatement()) {
        statement.execute("UPDATE operations SET state='COMPLETE_WITH_GAPS' WHERE id=" + gaps.id());
      }
      assertEquals(expected, new OperationHistoryStore(store).recent());
    }
    try (var store = open()) {
      var history = new OperationHistoryStore(store);
      assertEquals(expected, history.recent());
      assertEquals(5, history.size());
      assertEquals(OperationOutcome.SUCCESS, expected.get(0).outcome());
      assertTrue(expected.get(0).executionId().isEmpty());
      assertEquals(Optional.of("undo-id"), expected.get(1).executionId());
      assertEquals(OperationOutcome.UNDONE, expected.get(2).outcome());
      assertTrue(expected.get(2).executionId().isEmpty());
      assertEquals(Optional.of("DECLINED"), expected.get(3).diagnosticsLink());
      assertEquals(Optional.of("cancelled"), expected.get(4).diagnosticsLink());
      for (var entry : history.recent()) {
        assertTrue(entry.operationKey().isPresent());
        assertEquals(PROVENANCE.occurredAt(), entry.provenance().occurredAt());
        assertTrue(entry.provenance().signedIntentToken().isEmpty());
      }
      String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(history.recent());
      assertFalse(json.contains("private-intent-token"));
      assertFalse(json.contains("private-caller-text"));
      assertFalse(json.contains("identityJson"));
      assertFalse(json.contains("preparation"));
    }
  }

  @Test
  void queryIsBoundedAndLateCompletionOfAnOldIdStillAppearsAtTheEnd() throws Exception {
    try (var store = open()) {
      var oldest = accept(store, OperationHistoryMode.STANDARD);
      for (int i = 0; i < 205; i++) {
        var row = accept(store, OperationHistoryMode.STANDARD);
        store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
        clock.setMillis(clock.millis() + 1);
      }
      var history = new OperationHistoryStore(store);
      assertEquals(200, history.recent(Integer.MAX_VALUE).size());
      var last = store.finish(oldest.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null)).orElseThrow();
      assertEquals(last.key(), history.recent().getLast().operationKey().orElseThrow());
      assertEquals(oldest.id(), store.recentHistory(1).getFirst().id());
      assertEquals(200, store.recentHistory(Integer.MAX_VALUE).size());
      assertTrue(store.recentHistory(0).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> store.recentHistory(-1));
      assertEquals(200, history.size());
    }
  }

  @Test
  void repeatedOperationsAtTheSameTimestampHaveDistinctLedgerIdentitiesAndRetriesDeduplicate() throws Exception {
    try (var store = open()) {
      for (int i = 0; i < 2; i++) {
        var row = accept(store, OperationHistoryMode.STANDARD);
        store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      }
      var entries = new OperationHistoryStore(store).recent();
      assertEquals(entries.get(0).operationId(), entries.get(1).operationId());
      assertEquals(entries.get(0).endTime(), entries.get(1).endTime());
      assertNotEquals(ActionLedgerProjection.projectOperation(entries.get(0)).id(),
          ActionLedgerProjection.projectOperation(entries.get(1)).id());
      var registry = new ActionLedgerChangeRegistry();
      for (var entry : entries) { registry.broadcastOperation(entry); registry.broadcastOperation(entry); }
      assertEquals(2, registry.store().recent().size());
    }
  }

  @Test
  void livePersistenceFailureNotifiesListenersWithoutForgingDurableCompletion() throws Exception {
    try (var store = open()) {
      var row = accept(store, OperationHistoryMode.STANDARD);
      store.start(row.id());
      var history = new OperationHistoryStore(store);
      List<OperationHistoryEntry> live = new ArrayList<>();
      history.addAppendListener(entry -> { throw new IllegalStateException("observer failed"); });
      history.addAppendListener(live::add);
      var failure = new OperationHistoryEntry(new OperationRef("core.repeat"), "head", clock.instant(), clock.instant(),
          OperationOutcome.FAILURE, Optional.of("STORAGE_FAILED"), InvocationProvenance.systemInternal(clock.instant()), Optional.empty());
      history.append(failure);
      assertEquals(List.of(failure), live);
      assertTrue(history.recent().isEmpty());
      assertEquals(OperationOutcomeView.State.RUNNING, store.outcome(row.key()).state());
      assertEquals(OperationState.RUNNING, store.find(row.key()).orElseThrow().state());
    }
  }

  @Test
  void privatePreparedPayloadStaysOutOfHistoryAndPruningChangesTheExistingReadView() throws Exception {
    try (var store = open()) {
      String key = OperationKeys.generate(clock);
      var descriptor = OperationDescriptor.invocation(OperationKind.NOTE, "core.file-note", "{}", false);
      var pending = new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false, "private-prepared-target"));
      store.savePreparation(key, descriptor, pending);
      var row = store.acceptPrepared(key, descriptor, CONTEXT, PROVENANCE, pending.nonce(), OperationHistoryMode.STANDARD).record();
      store.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      var history = new OperationHistoryStore(store);
      assertEquals(1, history.recent().size());
      String narrow = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(store.recentHistory(200));
      assertFalse(narrow.contains("private-prepared-target"));
      assertFalse(narrow.contains(pending.nonce().toString()));
      assertTrue(store.acknowledgeHistoryProjection(key));
      clock.setMillis(clock.millis() + OperationStore.HISTORY_RETENTION.toMillis() + 1);
      store.pruneHistory();
      assertTrue(history.recent().isEmpty());
      assertEquals(OperationOutcomeView.State.EXPIRED, store.outcome(key).state());
    }
  }
}
