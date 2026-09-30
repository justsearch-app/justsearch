/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code resources/read} response-shape coverage (recently: no test anywhere referenced {@code
 * readResource} or a {@code justsearch://} URI). {@link McpToolSurface}'s five {@code
 * resources/read} handlers ({@code readIndexSummary}, {@code readIndexRoots}, {@code
 * readTopFacet}, {@code readTopEntities}, {@code resourceError}) recently switched their inner
 * {@code contents[0]} map from {@code Map.of} (JVM-salted iteration order) to {@code orderedMap}
 * ({@link java.util.LinkedHashMap}, insertion order) — see {@code McpToolSurface.java:1828}'s
 * "Tempdoc 725" doc comment for the same fix already applied to {@code tools/list}. This class
 * pins the regression the fix exists to prevent: every {@code contents[0]} map — success path and
 * {@code resourceError} path alike — must iterate {@code uri}, {@code mimeType}, {@code text} IN
 * THAT ORDER, not merely contain those three keys.
 */
final class McpResourcesReadTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneId.of("UTC"));

  private static final List<String> EXPECTED_KEY_ORDER = List.of("uri", "mimeType", "text");

  private static McpToolSurface surface(KnowledgeSearchController ctrl) {
    return new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> ctrl,
        () -> null,
        FIXED_CLOCK);
  }

  private static KnowledgeStatus statusFixture() {
    return new KnowledgeStatus(
        "ACTIVE", // state
        true, // ready
        0L, // queueDepth
        42L, // docCount
        42L, // activeDocCount
        0L, // buildingDocCount
        "gen-1", // servingSearchGenerationId
        "gen-1", // servingIngestGenerationId
        0L, // switchBufferDepth
        0L, // pendingJobsCount
        0L, // processingJobsCount
        0L, // pendingReadyJobsCount
        0L, // pendingBackoffJobsCount
        0L, // migrationSwitchingAgeMs
        0L, // migrationSwitchingMaxDurationMs
        false, // migrationPaused
        "", // migrationPauseReason
        0L, // migrationPausedAtMs
        true, // healthy
        "READY", // indexState
        Map.of("embeddingCoveragePercent", 87.5, "spladeCoveragePercent", 100.0)); // extras
  }

  private static KnowledgeSearchController controllerWithStatusAndFacets(
      KnowledgeStatus status, Map<String, Map<String, Long>> facets) {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status(any(EngineContext.class))).thenReturn(status);
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            0L, 0L, 0L, List.of(), null, facets, null, null, null, null, null, null, null);
    when(adapter.search(any(), any(EngineContext.class))).thenReturn(resp);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    return ctrl;
  }

  /** Extracts {@code contents[0]}, asserting the contents list has exactly one entry. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> soleContent(Map<String, Object> result) {
    List<Map<String, Object>> contents = (List<Map<String, Object>>) result.get("contents");
    assertEquals(1, contents.size(), "expected exactly one contents entry: " + result);
    return contents.get(0);
  }

  private static void assertKeyOrder(Map<String, Object> content) {
    assertEquals(
        EXPECTED_KEY_ORDER,
        new ArrayList<>(content.keySet()),
        "contents[0] key iteration order must be uri, mimeType, text (regression the orderedMap"
            + " fix pins): " + content);
  }

  // ---------------------------------------------------------------------
  // Success path: each of the four well-known resource URIs
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("index/summary: contents[0] keys iterate uri, mimeType, text")
  void indexSummaryKeyOrder() {
    KnowledgeSearchController ctrl =
        controllerWithStatusAndFacets(statusFixture(), Map.of());
    Map<String, Object> result = surface(ctrl).readResource("justsearch://index/summary", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/summary", content.get("uri"));
    assertEquals("text/plain", content.get("mimeType"));
    assertTrue(((String) content.get("text")).contains("documents: 42"), content.toString());
  }

  @Test
  @DisplayName("index/roots: contents[0] keys iterate uri, mimeType, text")
  void indexRootsKeyOrder() {
    // No "core.browse-folders" operation registered in the empty catalog, so readIndexRoots
    // takes its defensive-text branch rather than throwing -- still exercises the same
    // orderedMap construction the success path uses.
    KnowledgeSearchController ctrl = controllerWithStatusAndFacets(statusFixture(), Map.of());
    Map<String, Object> result = surface(ctrl).readResource("justsearch://index/roots", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/roots", content.get("uri"));
    assertEquals("text/plain", content.get("mimeType"));
  }

  @Test
  @DisplayName("index/top-sources: contents[0] keys iterate uri, mimeType, text")
  void topSourcesKeyOrder() {
    KnowledgeSearchController ctrl =
        controllerWithStatusAndFacets(
            statusFixture(), Map.of("meta_source", Map.of("acme-corp", 5L)));
    Map<String, Object> result = surface(ctrl).readResource("justsearch://index/top-sources", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/top-sources", content.get("uri"));
    assertEquals("application/json", content.get("mimeType"));
    assertTrue(((String) content.get("text")).contains("acme-corp"), content.toString());
  }

  @Test
  @DisplayName("index/top-entities: contents[0] keys iterate uri, mimeType, text")
  void topEntitiesKeyOrder() {
    KnowledgeSearchController ctrl =
        controllerWithStatusAndFacets(
            statusFixture(), Map.of("entity_persons_raw", Map.of("Ada Lovelace", 3L)));
    Map<String, Object> result = surface(ctrl).readResource("justsearch://index/top-entities", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/top-entities", content.get("uri"));
    assertEquals("application/json", content.get("mimeType"));
    assertTrue(((String) content.get("text")).contains("Ada Lovelace"), content.toString());
  }

  @Test
  @DisplayName("justsearch://resource/<id> redirects to readIndexSummary, same key order")
  void catalogResourceUriRedirectsToIndexSummary() {
    KnowledgeSearchController ctrl = controllerWithStatusAndFacets(statusFixture(), Map.of());
    Map<String, Object> result = surface(ctrl).readResource("justsearch://resource/some-id", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    // readIndexSummary always echoes the URI IT was called with, not the summary's own URI --
    // pins that the redirect passes the original uri through rather than rewriting it.
    assertEquals("justsearch://resource/some-id", content.get("uri"));
  }

  // ---------------------------------------------------------------------
  // Error path: resourceError's contents[0] shape
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("index/summary error path (no knowledge server): resourceError key order + shape")
  void indexSummaryErrorPathKeyOrder() {
    Map<String, Object> result = surface(null).readResource("justsearch://index/summary", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/summary", content.get("uri"));
    assertEquals("text/plain", content.get("mimeType"));
    assertEquals("Error: Knowledge server not available", content.get("text"));
  }

  @Test
  @DisplayName("index/top-sources error path (no knowledge server): resourceError key order + shape")
  void topSourcesErrorPathKeyOrder() {
    Map<String, Object> result = surface(null).readResource("justsearch://index/top-sources", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/top-sources", content.get("uri"));
    assertEquals("text/plain", content.get("mimeType"));
    assertEquals("Error: Knowledge server not available", content.get("text"));
  }

  @Test
  @DisplayName("index/top-entities error path (no knowledge server): resourceError key order + shape")
  void topEntitiesErrorPathKeyOrder() {
    Map<String, Object> result = surface(null).readResource("justsearch://index/top-entities", TestRequestContexts.mcp("s1"));

    Map<String, Object> content = soleContent(result);
    assertKeyOrder(content);
    assertEquals("justsearch://index/top-entities", content.get("uri"));
    assertEquals("text/plain", content.get("mimeType"));
    assertEquals("Error: Knowledge server not available", content.get("text"));
  }

  // ---------------------------------------------------------------------
  // Non-matching URIs
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("null uri returns an empty contents list")
  void nullUriReturnsEmptyContents() {
    Map<String, Object> result = surface(null).readResource(null, TestRequestContexts.mcp("s1"));
    assertEquals(List.of(), result.get("contents"));
  }

  @Test
  @DisplayName("unknown uri returns an empty contents list")
  void unknownUriReturnsEmptyContents() {
    Map<String, Object> result = surface(null).readResource("justsearch://not-a-real-resource", TestRequestContexts.mcp("s1"));
    assertEquals(List.of(), result.get("contents"));
  }
}
