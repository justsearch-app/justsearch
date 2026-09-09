/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 735 W6, step 1 — golden-fixture equivalence baseline. These fixtures were captured by a
 * one-shot harness ({@code ZZZGoldenCapture}, deleted once golden files were written) run against
 * the pre-W6 (0.3.1) {@code McpToolSurface} — before the content-model refactor — via {@code git
 * stash}: the refactor was stashed, the harness captured text output from the unmodified 0.3.1
 * code into {@code src/test/resources/mcp/golden/*.txt}, then the stash was popped to restore the
 * refactor. This test asserts the refactored (0.4.0) text renderer reproduces that captured output
 * BYTE-FOR-BYTE — the equivalence baseline the tempdoc 735 W6 spec requires before any renderer
 * restructuring is trusted.
 *
 * <p>Six representative inputs, matching the spec's named categories: hits with matched terms
 * (+ facet-bearing first call + hints), degradation, truncation, zero-result (search); multi-doc
 * answer with hints, and answer-side truncation (answer). No divergence was found — every fixture
 * is byte-identical between 0.3.1 and 0.4.0's text tier.
 *
 * <p>Still byte-identical at 0.5.0 (tempdoc 770): that increment gated the per-hit provenance tier
 * and removed the answer-path facet round-trip, both of which touch structuredContent and the
 * answer's facet block only — and these fixtures were captured with a null {@code knowledgeLookup},
 * so the sidecar never populated them. The capture harness is deleted, so a text change here means
 * hand-re-pinning byte-exact fixtures; keep text-tier changes out of structured-tier work.
 */
@DisplayName("McpToolSurface: 0.3.1 -> 0.5.0 text-tier golden equivalence (tempdoc 735 W6 / 770)")
final class McpTierEquivalenceGoldenTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private static String golden(String name) {
    String resourcePath = "/mcp/golden/" + name;
    try (InputStream in = McpTierEquivalenceGoldenTest.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Missing golden fixture: " + resourcePath);
      }
      // Defensive against a CRLF-normalized checkout (repo .gitattributes declares eol=lf for
      // *.txt via the `* text=auto eol=lf` default, but normalize here too so this test cannot
      // become a platform-dependent flake).
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static String textOf(Map<String, Object> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return ((String) content.get(0).get("text")).replace("\r\n", "\n");
  }

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

  private static Map<String, Object> invokeAnswer(ContextResult canned, Map<String, Object> args) {
    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenReturn(CompletableFuture.completedFuture(canned));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);
    return surface.callTool("justsearch_answer", args, "s1", TestRequestContexts.mcp("s1"));
  }

  @Test
  @DisplayName("search: matched terms + facet-bearing first call + hints — byte-identical to 0.3.1")
  void search_matchedTermsAndFacets() {
    String regionText = "The cavby8 widget assembly guide explains torque settings.";
    ExcerptRegion region =
        new ExcerptRegion(
            regionText, 0, regionText.length(), 1,
            List.of(new MatchSpan("content_preview", 4, 10, "cavby8")));
    Hit hit1 =
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
    Hit hit2 =
        new Hit(
            "doc-2",
            0.5d,
            Map.of("title", "Semantic Doc", "path", "docs/semantic.md"),
            List.of(),
            List.of(),
            List.of(),
            null);
    Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
    Map<String, Long> sourceFacet = new LinkedHashMap<>();
    sourceFacet.put("docs", 3L);
    facets.put("meta_source", sourceFacet);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            2L, 2L, 12L, List.of(hit1, hit2), null, facets, null, null, null, null, null, null, null);

    assertEquals(
        golden("search-matched-terms-facets.txt"),
        textOf(invokeSearch(canned, Map.of("query", "widget"))));
  }

  @Test
  @DisplayName("search: degradation note — byte-identical to 0.3.1")
  void search_degradation() {
    Hit hit =
        new Hit(
            "doc-3",
            0.6d,
            Map.of("title", "Degraded Hit", "path", "docs/degraded.md"),
            List.of(),
            List.of(),
            List.of(),
            null);
    SearchTrace.Degradation degradation =
        new SearchTrace.Degradation(true, "FINGERPRINT_MISMATCH", false, null, true, null);
    SearchTrace trace =
        new SearchTrace(SearchTrace.SCHEMA_VERSION, "HYBRID", null, null, degradation, List.of());
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 9L, List.of(hit), null, null, null, null, null, null, null, trace, null);

    assertEquals(
        golden("search-degradation.txt"),
        textOf(invokeSearch(canned, Map.of("query", "degraded"))));
  }

  @Test
  @DisplayName("search: truncated (totalHits > shown) — byte-identical to 0.3.1")
  void search_truncated() {
    List<Hit> tenHits = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tenHits.add(
          new Hit(
              "doc-" + i, 0.5d, Map.of("title", "Doc " + i, "path", "docs/doc-" + i + ".md"),
              List.of(), List.of(), List.of(), null));
    }
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            37L, 37L, 15L, tenHits, null, null, null, null, null, null, null, null, null);

    assertEquals(
        golden("search-truncated.txt"), textOf(invokeSearch(canned, Map.of("query", "widget"))));
  }

  @Test
  @DisplayName("search: zero-result — byte-identical to 0.3.1")
  void search_zeroResult() {
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, null, null);

    assertEquals(
        golden("search-zero-result.txt"),
        textOf(invokeSearch(canned, Map.of("query", "nonexistent-widget-xyz"))));
  }

  @Test
  @DisplayName("answer: multi-doc with comparative hint — byte-identical to 0.3.1")
  void answer_multiDocHints() {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-1", 0, 2, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-1", 1, 2, 40, 80, 0.8f, "excerpt-2", 5, 8, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-2", 0, 1, 0, 30, 0.7f, "excerpt-3", 1, 3, "", 0, ContextInclusion.ABSENT));
    ContextResult result =
        new ContextResult(
            "[From: doc-1]\nexcerpt-1\n\n---\n\n[From: doc-2]\nexcerpt-3",
            3,
            3,
            0,
            citations,
            "HYBRID",
            "HYBRID_AVAILABLE",
            false,
            List.of(),
            new QualitySignals(0.9f, 0.1f, 0.5f, 5, 3));

    assertEquals(
        golden("answer-multi-doc-hints.txt"),
        textOf(invokeAnswer(result, Map.of("query", "widget torque"))));
  }

  @Test
  @DisplayName("answer: truncated context, fulltext-fallback quality — byte-identical to 0.3.1")
  void answer_truncated() {
    String context = "[From: doc-a.txt]\ncontent-a\n\n---\n\n[From: doc-b.txt]\ncontent-b";
    List<DocumentService.ContextSection> sections =
        List.of(
            new DocumentService.ContextSection("doc-a.txt", "content-a", false, 0, 0),
            new DocumentService.ContextSection("doc-b.txt", "content-b", false, 1, 1));
    ContextResult result =
        new ContextResult(
            context, 0, 0, 2, List.of(), "FULLTEXT_FALLBACK", "GRPC_FAILED", true, sections);

    assertEquals(
        golden("answer-truncated.txt"),
        textOf(invokeAnswer(result, Map.of("query", "fallback query"))));
  }
}
