/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.justsearch.agent.api.registry.TransportTag;
import org.junit.jupiter.api.Test;

final class EngineOriginatorProjectionTest {
  @Test
  void autonomousWorkflowAndAgentTransportsShareOneOriginatorMapping() {
    for (var transport : new TransportTag[] {TransportTag.WORKFLOW, TransportTag.AGENT_LOOP,
        TransportTag.MCP, TransportTag.LLM_EMISSION}) {
      assertEquals("agent", ActionLedgerProjection.originatorOf(transport));
    }
    assertEquals("user", ActionLedgerProjection.originatorOf(TransportTag.BUTTON));
    assertEquals("system", ActionLedgerProjection.originatorOf(TransportTag.SYSTEM_INTERNAL));
  }
}
