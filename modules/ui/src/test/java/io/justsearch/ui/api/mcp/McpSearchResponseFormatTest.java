/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 725 (design #2, increment W2c) — pins {@code justsearch_search}'s {@code
 * response_format} density tier: "concise" omits the Preview line only, keeping every other
 * legibility line (rank/title/score, Path, Matched/Match-basis, and the
 * summary/degradation/coverage lines below the per-hit loop) that {@link
 * McpSearchTraceLegibilityTest} already pins for the default "detailed" shape.
 */
@DisplayName("McpToolSurface justsearch_search: response_format concise/detailed (tempdoc 725 W2c)")
final class McpSearchResponseFormatTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private static Map<String, Object> invokeSearch(
      KnowledgeSearchResponse canned, Map<String, Object> args) {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any(), any(EngineContext.class))).thenReturn(canned);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    return surface.callTool("justsearch_search", args, "s1", TestRequestContexts.mcp("s1"));
  }

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  private static KnowledgeSearchResponse fixture() {
    String regionText = "The cavby8 widget assembly guide explains torque settings.";
    MatchSpan nestedSpan = new MatchSpan("content_preview", 4, 10, "cavby8");
    ExcerptRegion region = new ExcerptRegion(regionText, 0, regionText.length(), 1, List.of(nestedSpan));
    Hit hit =
        new Hit(
            "doc-1",
            0.87d,
            Map.of(
                "title", "Widget Assembly",
                "path", "docs/widgets/assembly.md",
                "content_preview", regionText),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", 4, 10, "cavby8")),
            List.of(region),
            null);
    return new KnowledgeSearchResponse(
        1L, 1L, 12L, List.of(hit), null, null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("detailed (default) keeps the Preview line")
  void detailedKeepsPreviewLine() {
    String text = textOf(invokeSearch(fixture(), Map.of("query", "widget")));

    assertTrue(text.contains("    Preview:"), text);
  }

  @Test
  @DisplayName("concise omits the Preview line but keeps rank/title/score, Path, and Matched lines")
  void conciseOmitsPreviewLineOnly() {
    String text =
        textOf(invokeSearch(fixture(), Map.of("query", "widget", "response_format", "concise")));

    assertFalse(text.contains("    Preview:"), text);
    assertTrue(text.contains("[1] Widget Assembly (score: 0.87)"), text);
    assertTrue(text.contains("    Path: docs/widgets/assembly.md"), text);
    assertTrue(text.contains("    Matched: \"cavby8\" in content_preview"), text);
    assertTrue(text.contains("Found 1 results"), text);
  }
}
