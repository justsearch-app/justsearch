/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L16 — the permission-allowed (grant ISSUED), permission-used (grant CONSUMED) and completion
 * (operation) rows name the pending request whose Allow they belong to, so a reader joins them to
 * that decision by id alone. The field is optional and additive: rows without it read as before.
 */
final class ApprovalLinkedRowsTest {

  private static final Instant T = Instant.parse("2026-11-05T12:00:00Z");

  private static Map<String, Object> wire(String kind, Map<String, Object> fields) {
    Map<String, Object> row = new LinkedHashMap<>(Map.of("id", kind + "-row", "kind", kind,
        "occurredAt", T.toString(), "originator", "user", "transport", "APPROVAL"));
    row.putAll(fields);
    return row;
  }

  private static Map<String, Object> roundTrip(Map<String, Object> row) {
    return ActionLedgerProjection.toWireRow(ActionLedgerProjection.fromWireRow(row).orElseThrow());
  }

  private static OperationHistoryEntry completed(String key, OperationOutcome outcome, Instant at) {
    return new OperationHistoryEntry(new OperationRef("core.reindex"), "head", at, at.plusMillis(100), outcome,
        Optional.empty(), InvocationProvenance.systemInternal(at), Optional.empty(), Optional.of(key));
  }

  @Test
  void grantAndOperationRowsKeepTheirRequestIdentityAcrossTheJournalAndLegacyRowsHaveNone() {
    for (String action : new String[] {"ISSUED", "CONSUMED"}) {
      var keyed = wire("grant", Map.of("grantId", "g1", "action", action, "subject", "core.reindex",
          "pendingId", "pa-1"));
      assertEquals("pa-1", roundTrip(keyed).get("pendingId"), action + " keeps its request id");
      var legacy = wire("grant", Map.of("grantId", "g1", "action", action, "subject", "core.reindex"));
      assertEquals(legacy, without(roundTrip(legacy), "outcome"), action + " legacy row is unchanged");
    }
    var keyed = wire("operation", Map.of("operationId", "core.reindex", "outcome", "FAILURE", "pendingId", "pa-2"));
    assertEquals("pa-2", roundTrip(keyed).get("pendingId"));
    var legacy = wire("operation", Map.of("operationId", "core.reindex", "outcome", "SUCCESS"));
    assertEquals(legacy, roundTrip(legacy), "a legacy completion row is unchanged");
  }

  @Test
  void eachRunsCompletionRowNamesTheRequestThatAllowedIt(@TempDir Path audit) {
    var registry = new ActionLedgerChangeRegistry(ActionEventJournal.at(audit));
    // Two Allows of the same operation 1.5 s apart; the first run succeeds, the second fails.
    registry.linkApproval("key-first", "pa-first");
    var first = completed("key-first", OperationOutcome.SUCCESS, T);
    registry.publishLiveOperation(first);
    registry.persistOperation(first);
    registry.linkApproval("key-second", "pa-second");
    var second = completed("key-second", OperationOutcome.FAILURE, T.plusMillis(1500));
    registry.publishLiveOperation(second);
    registry.persistOperation(second);
    // A run no request allowed.
    var manual = completed("key-manual", OperationOutcome.SUCCESS, T.plusSeconds(10));
    registry.publishLiveOperation(manual);
    registry.persistOperation(manual);

    Map<String, Object> live = new LinkedHashMap<>();
    for (var event : registry.store().recent()) {
      var row = ActionLedgerProjection.toWireRow(event);
      live.put((String) row.get("id"), row.get("pendingId"));
    }
    assertEquals("pa-first", live.get("operation:key-first"));
    assertEquals("pa-second", live.get("operation:key-second"));
    assertEquals(null, live.get("operation:key-manual"));

    Map<String, Object> journaled = new LinkedHashMap<>();
    for (var event : ActionEventJournal.at(audit).tail(10)) {
      var row = ActionLedgerProjection.toWireRow(event);
      journaled.put((String) row.get("id"), row.getOrDefault("pendingId", "none"));
    }
    assertEquals(Map.of("operation:key-first", "pa-first", "operation:key-second", "pa-second",
        "operation:key-manual", "none"), journaled, "the journal keeps the same ids after a restart");
    assertFalse(ActionLedgerProjection.toWireRow(ActionLedgerProjection.projectOperation(manual))
        .containsKey("pendingId"));
  }

  private static Map<String, Object> without(Map<String, Object> row, String key) {
    Map<String, Object> copy = new LinkedHashMap<>(row);
    copy.remove(key);
    return copy;
  }
}
