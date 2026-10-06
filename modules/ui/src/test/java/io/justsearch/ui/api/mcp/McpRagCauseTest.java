/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.DocumentService.ContextResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec D §5.2: the MCP RAG evidence projects the cause of a semantic budget miss next to {@code
 * retrievalModeReason}, through the same projection, and nothing when no cause is carried (S2: the
 * reason, per leg, reaches MCP responses).
 */
final class McpRagCauseTest {

  @Test
  void aBudgetMissProjectsItsCauseNextToTheReason() {
    Map<String, Object> quality = quality(result("SEMANTIC_BUDGET_EXCEEDED", "contended"));

    assertEquals("SEMANTIC_BUDGET_EXCEEDED", quality.get("retrievalModeReason"));
    assertEquals("contended", quality.get("retrievalModeCause"));
  }

  @Test
  void noCauseProjectsNoCauseKey() {
    Map<String, Object> quality = quality(result("HYBRID_AVAILABLE", ""));

    assertEquals("HYBRID_AVAILABLE", quality.get("retrievalModeReason"));
    assertFalse(quality.containsKey("retrievalModeCause"), quality::toString);
  }

  private static ContextResult result(String reason, String cause) {
    return new ContextResult(
        "context", 1, 1, 0, List.of(), "BM25", reason, false, List.of(), null, cause);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> quality(ContextResult result) {
    return (Map<String, Object>) McpEvidenceProjection.answerEvidence(result).get("quality");
  }
}
