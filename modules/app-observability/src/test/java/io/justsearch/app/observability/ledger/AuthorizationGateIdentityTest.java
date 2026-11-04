/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthorizationGateIdentityTest {
  @Test
  void optionalPendingIdentitySurvivesJournalRoundTripWithoutRewritingLegacyRows() {
    Map<String, Object> row = new LinkedHashMap<>(Map.of("id", "gate-test", "kind", "gate",
        "occurredAt", "2026-11-04T18:00:00Z", "originator", "agent", "transport", "MCP",
        "operationId", "core.ingest-files", "disposition", "GATED", "gateBehavior", "INLINE_CONFIRM",
        "sourceTier", "UNTRUSTED"));
    var legacy = ActionLedgerProjection.fromWireRow(row).orElseThrow();
    assertEquals(row, ActionLedgerProjection.toWireRow(legacy).entrySet().stream()
        .filter(entry -> !entry.getKey().equals("outcome"))
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    row.put("pendingId", "pa-exact-request");
    var keyed = ActionLedgerProjection.fromWireRow(row).orElseThrow();
    assertEquals("pa-exact-request", ActionLedgerProjection.toWireRow(keyed).get("pendingId"));
    assertFalse(ActionLedgerProjection.toWireRow(legacy).containsKey("pendingId"));
  }
}
