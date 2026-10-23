/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class AgentBulkOutcomeKeyTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";

  @ParameterizedTest
  @CsvSource({"core_bulk_reindex, core.bulk-reindex", "core_rebuild_index, core.rebuild-index",
      "core_activate_installed_models, core.activate-installed-models"})
  void acceptedAgentRowsCarryTheRecordedKeyForLaterOutcomeReads(String wireName, String operationRef) {
    var registry = new ActionLedgerChangeRegistry();
    var projector = new AgentRunLedgerProjector(registry);
    projector.onEvent("session", record("tool_exec_started",
        Map.of("callId", "call", "toolName", wireName)));
    projector.onEvent("session", record("tool_exec_completed", Map.of("callId", "call",
        "success", true, "executionId", "execution", "structuredData", Map.of("operationKey", KEY))));
    var row = ActionLedgerProjection.toWireRow(registry.store().recent().getFirst());
    assertAll(
        () -> assertEquals("operation:" + KEY, row.get("id")),
        () -> assertEquals(operationRef, row.get("operationId")),
        () -> assertEquals("SUCCESS", row.get("outcome"), "The tool accepted the rebuild"),
        () -> assertEquals("session", row.get("correlationId")),
        () -> assertEquals(row, ActionLedgerProjection.toWireRow(
            ActionLedgerProjection.fromWireRow(row).orElseThrow())));
  }

  @ParameterizedTest
  @CsvSource({"bad-key", "0194f72c-0000-4000-8000-000000000001"})
  void malformedOperationKeysKeepTheExistingExecutionIdentity(String key) {
    var registry = new ActionLedgerChangeRegistry();
    var projector = new AgentRunLedgerProjector(registry);
    projector.onEvent("session", record("tool_exec_started",
        Map.of("callId", "call", "toolName", "core_bulk_reindex")));
    projector.onEvent("session", record("tool_exec_completed", Map.of("callId", "call",
        "success", true, "executionId", "execution", "structuredData", Map.of("operationKey", key))));
    assertEquals(1, registry.store().recent().size());
    assertNotEquals("operation:" + key, registry.store().recent().getFirst().id());
  }

  private static Map<String, Object> record(String type, Map<String, Object> payload) {
    return Map.of("timestamp", "2026-10-22T09:00:00Z", "eventType", type, "payload", payload);
  }
}
