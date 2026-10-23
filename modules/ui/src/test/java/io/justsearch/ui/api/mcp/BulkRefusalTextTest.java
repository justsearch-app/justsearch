/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BulkRefusalTextTest {
  @Test
  void aSelfContainedExplanationDoesNotGetASecondCodeInMcpText() {
    String code = "BULK_GENERATION_REFUSED";
    String message = "The rebuild or migration could not safely start or resume the new index; "
        + "check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)";
    var dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any()))
        .thenReturn(OperationResult.failure(message, code, Map.of(), false));
    var surface = new McpToolSurface(List.of(new AgentToolsOperationCatalog()), dispatcher,
        () -> null, () -> null, Clock.systemUTC());
    var result = surface.callTool("justsearch_ingest", Map.of("paths", List.of("C:/notes")),
        "refusal", TestRequestContexts.mcp("refusal"));
    var content = (List<?>) result.get("content");
    String text = String.valueOf(((Map<?, ?>) content.getFirst()).get("text"));
    assertTrue(text.contains(message), text);
    assertEquals(text.indexOf(code), text.lastIndexOf(code), text);
    assertEquals(code, ((Map<?, ?>) result.get("structuredContent")).get("errorCode"));
  }
}
