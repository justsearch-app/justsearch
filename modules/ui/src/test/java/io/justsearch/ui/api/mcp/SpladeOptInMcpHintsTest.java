/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SpladeOptInMcpHintsTest {
  @Test void disabledSparseCoverageDoesNotAdvertisePendingEnrichment() {
    assertNull(content(false, 100, 0).enrichmentHint(),
        "S13: low retained coverage is not pending work when the serving encoder is disabled");
    assertNull(content(false, 100, -1).enrichmentHint(), "unknown sparse counts remain inapplicable");
  }

  @Test void runnableSparseAndDenseBacklogsKeepTheirHints() {
    assertNotNull(content(true, 100, 25).enrichmentHint());
    assertNotNull(content(false, 25, 0).enrichmentHint());
  }

  private McpAnswerResponseContent content(boolean enabled, double dense, double sparse) {
    var status = new KnowledgeStatus("READY", true, 0, 10, 10, 0, "", "", 0, 0, 0, 0,
        0, 0, 0, false, "", 0, true, "READY", Map.of("embeddingCoveragePercent", dense,
            "spladeCoveragePercent", sparse, "spladeEnabled", enabled));
    var adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status(any(EngineContext.class))).thenReturn(status);
    var controller = mock(KnowledgeSearchController.class);
    when(controller.getAdapter()).thenReturn(adapter);
    var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class), () -> controller, () -> null,
        Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
    var framing = McpDeliveryFraming.resolveSettings(TestResolvedConfigHelper.withDefaults());
    return surface.buildAnswerContent(new ContextResult("", 0, 0, 0, List.of(),
        "HYBRID", "NO_CHUNKS_FOUND", false, List.of()), "needle", framing, TestRequestContexts.mcp("off"));
  }
}
