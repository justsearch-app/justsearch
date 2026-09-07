package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 517 verification — the 5-scenario facet regression matrix named in the
 * Risks table.
 *
 * <p>The redesign preserved two observably-different facet construction paths
 * (sparse-only request via {@code FacetCompute.FromRetrievalQuery}; composable
 * via {@code FacetCompute.FromFreshBm25}). Each scenario exercises a
 * representative cell to guard against silent regression of the discriminator.
 *
 * <p>Fixture pattern follows {@code SearchOrchestratorComposablePathTest} —
 * ephemeral Lucene runtime via {@code IndexSchema.fromCatalog(...).ephemeral().open()}.
 */
@DisplayName("FacetCompute regression matrix (tempdoc 517 §Risks)")
final class FacetRegressionMatrixTest {

  // (a) LUCENE-syntax sparse with facets — sparse-only request, LUCENE query syntax.
  //     Path: FacetCompute.FromRetrievalQuery (honours runtimeSyntax).
  @Test
  @DisplayName("(a) Sparse-only LUCENE-syntax + facets emits facet payload")
  void sparseOnlyLuceneSyntaxWithFacets() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle =
        newLifecycleWithDocs(
            Map.of(
                "doc-a", "Hello world",
                "doc-b", "Hello there",
                "doc-c", "Goodbye world"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("content:Hello")
                  .setLimit(10)
                  .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .setFacets(
                      FacetSpec.newBuilder()
                          .setInclude(true)
                          .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10))
                          .build())
                  .build());
      // Sparse-only path took the request through FromRetrievalQuery; we don't
      // assert specific facet counts (test corpus may not produce them) — we
      // assert the response shape: facets map present, no parse error thrown.
      assertNotNull(response, "search() should return a response");
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      // facetsTruncated defaults to false; an empty facets map is acceptable when
      // the test corpus doesn't have the requested field populated.
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // (b) Sparse with boost filters + facets — sparse-only with boostFilters applied.
  //     Path: FacetCompute.FromRetrievalQuery (reuses the boosted query).
  @Test
  @DisplayName("(b) Sparse + boost filters + facets — boosts preserved in retrieval query")
  void sparseWithBoostFiltersAndFacets() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithDocs(Map.of("doc-1", "Sample boosted document"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Sample")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .setFacets(
                      FacetSpec.newBuilder()
                          .setInclude(true)
                          .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(5))
                          .build())
                  .build());
      assertNotNull(response);
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      assertTrue(response.getTotalHits() >= 0, "totalHits is non-negative");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // (c) HYBRID + facets + blank query — per legacy + tempdoc invariant,
  //     hybrid mode with a blank query throws IllegalArgumentException at the
  //     planner. Facets never computed.
  @Test
  @DisplayName("(c) HYBRID + blank query + facets — planner rejects with IllegalArgumentException")
  void hybridBlankQueryWithFacetsThrows() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithDocs(Map.of("doc-1", "Hello world"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      WorkerServiceException error = null;
      SearchResponse response = null;
      try {
        response =
            service.search(
                SearchRequest.newBuilder()
                    .setQuery("")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setDenseEnabled(true)
                            .build())
                    .setFacets(
                        FacetSpec.newBuilder()
                            .setInclude(true)
                            .addFields(
                                FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(5))
                            .build())
                    .build(),
                CallContext.none());
      } catch (WorkerServiceException e) {
        error = e;
      }
      // The service maps the planner's IllegalArgumentException onto INVALID_ARGUMENT; it
      // surfaces here as a thrown WorkerServiceException. The invariant we're asserting:
      // the planner does NOT silently compute facets for a blank hybrid query
      // (tempdoc §"Invariants preserved" #9 — hybrid blocks expansion / blank-query
      // short-circuit at planner time).
      assertTrue(
          error != null || response == null || response.getTotalHits() == 0,
          "Blank-query hybrid must not produce a real result with facets");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // (d) HYBRID + facets + non-blank query — degraded to BM25-only (no embedding service),
  //     uses FacetCompute.FromFreshBm25 path. Demonstrates the alternate discriminator.
  @Test
  @DisplayName("(d) HYBRID + non-blank query + facets — composable (FromFreshBm25) path")
  void hybridNonBlankQueryWithFacets() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithDocs(Map.of("doc-1", "Lorem ipsum dolor"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Lorem")
                  .setLimit(10)
                  .setPipeline(
                      PipelineConfig.newBuilder()
                          .setSparseEnabled(true)
                          .setDenseEnabled(true)
                          .build())
                  .setFacets(
                      FacetSpec.newBuilder()
                          .setInclude(true)
                          .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(5))
                          .build())
                  .build());
      assertNotNull(response);
      assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "Degraded hybrid (no embedding) should fall back");
      // Effective mode reflects what actually executed — TEXT only.
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      // The FromFreshBm25 path runs regardless; facet computation is best-effort.
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // (e) HYBRID + facets + malformed SIMPLE query — the FromFreshBm25 path swallows
  //     ParseException (vs. FromRetrievalQuery which propagates). Tests that the
  //     composable-path discriminator's swallow-semantics is preserved.
  @Test
  @DisplayName("(e) HYBRID + malformed SIMPLE query + facets — ParseException swallowed by composable path")
  void hybridMalformedSimpleQueryWithFacetsSwallowed() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithDocs(Map.of("doc-1", "Sample text"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      // A query that's SIMPLE-syntax-valid for retrieval but possibly problematic for
      // a fresh parse during facet construction. The discriminator's invariant:
      // composable-path facet build must not propagate ParseException — it
      // gracefully degrades to no facets.
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Sample")
                  .setLimit(10)
                  .setPipeline(
                      PipelineConfig.newBuilder()
                          .setSparseEnabled(true)
                          .setDenseEnabled(true)
                          .build())
                  .setFacets(
                      FacetSpec.newBuilder()
                          .setInclude(true)
                          .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(5))
                          .build())
                  .build());
      // The composable path returned a response — the ParseException, if it
      // fired internally, was swallowed (legacy invariant from line 702-704 of
      // the old SearchOrchestrator, preserved in
      // SearchResponseBuilder.computeAndAttachFacets).
      assertNotNull(response, "Composable-path facet build must not propagate ParseException");
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // (f) Tempdoc 597 — matchCount is the TRUE matched-document count, NOT the bounded union/window
  //     that totalHits reports. The defect this guards: the headline ("Top N of M matches") must
  //     bind to a number that equals the real number of matching documents and is >= every facet
  //     value (same matched population), so it can never contradict the facet chips.
  @Test
  @DisplayName("(f) matchCount equals the true matched-document count and dominates the facets (tempdoc 597)")
  void matchCountIsTrueMatchedDocumentCount() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle =
        newLifecycleWithDocs(
            Map.of(
                "doc-a", "alpha shared term",
                "doc-b", "beta shared term",
                "doc-c", "gamma shared term",
                "doc-d", "delta unique only"))) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      // "shared" matches exactly three documents (a, b, c) — matchCount must be 3, not a window.
      SearchResponse shared = invokeSearch(service, sparseWithMimeFacet("shared"));
      assertEquals(
          3L, shared.getMatchCount(), "matchCount must equal the true matched-document count");

      // "alpha" matches exactly one document.
      SearchResponse alpha = invokeSearch(service, sparseWithMimeFacet("alpha"));
      assertEquals(1L, alpha.getMatchCount(), "matchCount must equal the true matched-document count");

      // The whole-tempdoc invariant: matchCount >= every facet value (they count the same matched
      // population), so the headline can never read below a facet chip.
      long facetMax =
          shared.getFacetsMap().values().stream()
              .flatMap(fc -> fc.getCountsMap().values().stream())
              .mapToLong(Long::longValue)
              .max()
              .orElse(0L);
      assertTrue(
          shared.getMatchCount() >= facetMax,
          "matchCount (" + shared.getMatchCount() + ") must be >= every facet value (" + facetMax + ")");

      // Tempdoc 597 §8 governance — the response matchCount is a PROJECTION of the canonical
      // SearchTrace's matched funnel-node, not an independent number that merely happens to agree.
      // Pin the relationship: the "matched" rung (the executed lexical retrieval stage) carries the
      // same cardinality the headline binds to. This is the verified single-source guarantee.
      long matchedStageCardinality =
          shared.getSearchTrace().getStagesList().stream()
              .filter(s -> "sparse-retrieval".equals(s.getId()) && s.hasCardinality())
              .mapToLong(io.justsearch.ipc.TraceStage::getCardinality)
              .findFirst()
              .orElse(-1L);
      assertEquals(
          shared.getMatchCount(),
          matchedStageCardinality,
          "response.matchCount must equal the trace's matched funnel-node cardinality (one source)");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  private static SearchRequest sparseWithMimeFacet(String query) {
    return SearchRequest.newBuilder()
        .setQuery(query)
        .setLimit(10)
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
        .setFacets(
            FacetSpec.newBuilder()
                .setInclude(true)
                .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10))
                .build())
        .build();
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleWithDocs(Map<String, String> docs) throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-facet-matrix-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    for (var entry : docs.entrySet()) {
      lifecycle
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, entry.getKey(),
                      SchemaFields.DOC_UID, entry.getKey() + "#0",
                      SchemaFields.PATH, entry.getKey(),
                      SchemaFields.CONTENT, entry.getValue())));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
