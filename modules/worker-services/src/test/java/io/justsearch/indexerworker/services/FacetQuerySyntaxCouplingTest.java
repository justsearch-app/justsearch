package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §L.3 + §P — the facet scan and the headline match count must tally the SAME parse the
 * hits came from, and (since §P) that parse is the REQUEST's {@code query_syntax} on every leg.
 *
 * <p>Before §P the multi-leg (composable) BM25 leg parsed SIMPLE-only regardless of the request, so
 * these assertions pinned "counts follow the leg's SIMPLE constant". §P is the future they were
 * written for: {@code SearchDecision.MultiLegDecision.runtimeSyntax()} is now the ONE value the leg
 * ({@code SearchExecutor#runMultiLeg}), the facet rebuild and {@code computeMatchCount} all read. So
 * the same bidirectional pin now asserts the LUCENE parse on BOTH sides: a LUCENE-syntax request
 * retrieves the LUCENE population AND reports counts over it.
 *
 * <p>The query below parses to 3 docs under SIMPLE and 1 under LUCENE, so a parse skew shows up as a
 * number rather than a shape. Each test pins a different producer, because {@code matchCount} has
 * two sources ({@code SearchResponseBuilder:170-176}):
 *
 * <ul>
 *   <li><b>with facets</b> — {@code matchCount} binds to {@code facetsResult.matchedDocs()}, so
 *       those cases pin the FACET SCAN's parse (the {@code :243} rebuild);
 *   <li><b>without facets</b> — {@code matchCount} comes from {@code computeMatchCount}, so the
 *       no-facets case is the only one that pins the {@code :308} rebuild.
 * </ul>
 *
 * <p>Each pin stays bidirectional: asserting count == hits fails if either side stops reading the
 * decision's syntax, and the explicit {@code 1 != 3} assertions fail if a regression reverts the leg
 * to a SIMPLE-only parse while the request asked for LUCENE.
 */
@DisplayName("Facet/matchCount parse is coupled to the retrieval leg's parse (tempdoc 821 §L.3 + §P)")
final class FacetQuerySyntaxCouplingTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  /**
   * SIMPLE escapes the operators (tokens {@code shared}, {@code alpha}, default-OR) → 3 docs.
   * LUCENE parses required clauses ({@code +shared +alpha}) → 1 doc. Any divergence between the
   * hits' parse and the counts' parse is therefore visible as a number, not a shape.
   */
  private static final String DIVERGING_QUERY = "+shared +alpha";

  /** Unbalanced parenthesis — a genuine {@code ParseException} under LUCENE, harmless under SIMPLE. */
  private static final String MALFORMED_LUCENE_QUERY = "+shared +(alpha";

  private static final Map<String, String> CORPUS =
      Map.of(
          "doc-a", "alpha shared term",
          "doc-b", "beta shared term",
          "doc-c", "gamma shared term");

  @Test
  @DisplayName("LUCENE-syntax multi-leg request: leg honours LUCENE, facets + matchCount follow it")
  void luceneSyntaxMultiLegCountsMatchTheRetrievedPopulation() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));

      // The composable path is what ran (hybrid degraded to BM25 — no embedding service).
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());

      assertEquals(
          1L,
          response.getTotalHits(),
          "tempdoc 821 §P: the multi-leg BM25 leg parses the request's LUCENE syntax, so the required"
              + " clauses (+shared +alpha) select only doc-a");
      assertNotEquals(
          3L,
          response.getTotalHits(),
          "3 would mean the leg fell back to a SIMPLE parse while the request asked for LUCENE");
      assertEquals(
          response.getTotalHits(),
          response.getMatchCount(),
          "the headline must count the population the hits came from");
      assertEquals(
          1L,
          response.getFacetsMap().get(SchemaFields.MIME).getCountsMap().get("application/pdf"),
          "the facet scan must tally the same single retrieved doc, not the SIMPLE parse's 3");
      assertFalse(
          response.getFacetsTruncated(), "the tiny scan completed — truncated must stay false");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  /**
   * The facets cases above never reach {@code computeMatchCount} — with facets requested,
   * {@code matchCount} binds to the facet scan's {@code matchedDocs}. Only a facet-less multi-leg
   * request exercises the {@code computeMatchCount} rebuild, so only this test can catch a parse
   * skew there.
   */
  @Test
  @DisplayName("No-facets multi-leg LUCENE request: computeMatchCount tallies the leg's parse")
  void luceneSyntaxMultiLegWithoutFacetsCountsTheRetrievedPopulation() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchRequest noFacets =
          SearchRequest.newBuilder()
              .setQuery(DIVERGING_QUERY)
              .setLimit(10)
              .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
              .setPipeline(
                  PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build())
              .build();
      SearchResponse response = invokeSearch(service, noFacets);

      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      assertTrue(
          response.getFacetsMap().isEmpty(),
          "precondition: no facets requested, so matchCount must come from computeMatchCount");
      assertEquals(
          1L,
          response.getTotalHits(),
          "precondition: the BM25 leg parses the request's LUCENE syntax, so only doc-a matches");
      assertEquals(
          response.getTotalHits(),
          response.getMatchCount(),
          "computeMatchCount must re-parse with the decision's syntax — the same one the leg used");
      assertNotEquals(
          3L,
          response.getMatchCount(),
          "3 would mean computeMatchCount re-parsed as SIMPLE while the leg parsed LUCENE");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("SIMPLE-syntax multi-leg request is unchanged — and now diverges from the LUCENE one")
  void simpleSyntaxMultiLegIsUnchanged() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse simple =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE));
      SearchResponse lucene =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));

      assertEquals(
          3L,
          simple.getTotalHits(),
          "the default SIMPLE path escapes the operators and is unchanged by tempdoc 821 §P");
      assertEquals(
          simple.getTotalHits(), simple.getMatchCount(), "SIMPLE counts still follow SIMPLE hits");
      assertEquals(
          3L,
          simple.getFacetsMap().get(SchemaFields.MIME).getCountsMap().get("application/pdf"),
          "the SIMPLE facet tally is unchanged");
      assertNotEquals(
          simple.getMatchCount(),
          lucene.getMatchCount(),
          "tempdoc 821 §P: the two syntaxes now retrieve different populations for this query — equal"
              + " counts would mean one of them ignored its request's syntax");
      assertEquals(
          lucene.getTotalHits(),
          lucene.getMatchCount(),
          "and each request stays internally consistent (counts follow ITS leg)");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("LUCENE quoted phrase retrieves phrase matches, not a token OR — counts follow")
  void luceneQuotedPhraseIsPhraseMatchedNotTokenOred() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      // "alpha shared" is an in-order adjacent phrase in doc-a only. A token OR (the SIMPLE parse
      // of the same text, quotes escaped) matches all 3 docs via `shared`.
      SearchResponse inOrder =
          invokeSearch(
              service, multiLeg("\"alpha shared\"", SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));
      assertEquals(1L, inOrder.getTotalHits(), "the phrase matches doc-a only");
      assertEquals("doc-a", inOrder.getResults(0).getId());
      assertEquals(
          inOrder.getTotalHits(), inOrder.getMatchCount(), "counts follow the phrase-parsed leg");

      // Reversed, the same two tokens are not a phrase anywhere — 0 hits. Under a token OR this
      // would still be 3, so this is the assertion that phrase SEMANTICS (not just term selection)
      // reached the leg.
      SearchResponse reversed =
          invokeSearch(
              service, multiLeg("\"shared alpha\"", SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));
      assertEquals(0L, reversed.getTotalHits(), "no document contains the reversed phrase");
      assertEquals(0L, reversed.getMatchCount(), "the headline agrees with the empty result");

      // Control: the identical text as a SIMPLE request escapes the quotes and ORs the tokens.
      SearchResponse simple =
          invokeSearch(
              service, multiLeg("\"alpha shared\"", SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE));
      assertEquals(3L, simple.getTotalHits(), "SIMPLE still treats the quotes as literal text");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("Malformed LUCENE query: multi-leg mirrors the sparse shortcut's INVALID_ARGUMENT")
  void malformedLuceneQueryDegradesLikeTheSparseShortcut() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      // The reference behaviour: the sparse-only shortcut has always signalled a bad LUCENE parse
      // as INVALID_ARGUMENT rather than answering 0 hits (SearchExecutor:156).
      WorkerServiceException sparseError =
          invokeSearchExpectingError(
              service,
              SearchRequest.newBuilder()
                  .setQuery(MALFORMED_LUCENE_QUERY)
                  .setLimit(10)
                  .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, sparseError.status());

      // Tempdoc 821 §P: now that the multi-leg lexical leg parses LUCENE too, it must fail the same
      // way — a silent 0-hit answer would hide a malformed query behind "no results".
      WorkerServiceException multiLegError =
          invokeSearchExpectingError(
              service, multiLeg(MALFORMED_LUCENE_QUERY, SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));
      assertEquals(
          WorkerServiceException.Status.INVALID_ARGUMENT,
          multiLegError.status(),
          "the multi-leg path must mirror the sparse shortcut, not degrade silently");
      assertTrue(
          String.valueOf(multiLegError.getMessage()).startsWith("Invalid query syntax"),
          "same message shape as the sparse shortcut");

      // The same malformed text under SIMPLE is just literal text — it must still answer normally.
      SearchResponse simple =
          invokeSearch(
              service, multiLeg(MALFORMED_LUCENE_QUERY, SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE));
      assertEquals(
          3L, simple.getTotalHits(), "SIMPLE escapes the operators — no parse failure to signal");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("Malformed LUCENE on a DENSE-ONLY leg still signals — the counts parse it too")
  void malformedLuceneSignalsEvenWithoutALexicalLeg() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);

      // An explicit query vector makes this a MultiLegDecision(DenseOnly) — no lexical leg runs, so
      // an "only probe when a lexical leg exists" gate would let this through. But
      // computeMatchCount re-parses the query regardless, so the response would carry matchCount 0
      // beside real dense hits: a count contradicting its own results.
      SearchRequest denseOnly =
          SearchRequest.newBuilder()
              .setQuery(MALFORMED_LUCENE_QUERY)
              .setLimit(10)
              .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
              .addAllVector(java.util.List.of(0.1f, 0.2f, 0.3f, 0.4f))
              .setPipeline(
                  PipelineConfig.newBuilder().setSparseEnabled(false).setDenseEnabled(true).build())
              .build();

      WorkerServiceException error = invokeSearchExpectingError(service, denseOnly);
      assertEquals(
          WorkerServiceException.Status.INVALID_ARGUMENT,
          error.status(),
          "a leg-less LUCENE parse failure must still be signalled, not answered with a 0 count");

      // Control: the same dense-only request with a WELL-FORMED LUCENE query ANSWERS instead of
      // erroring — so the signal above is about the malformed parse, not about dense-only requests
      // failing. (It answers 0 hits: this corpus is indexed without vectors, so the KNN leg has
      // nothing to match. That is the point — the parse probe fires before, and independently of,
      // whether the leg can retrieve anything.)
      SearchResponse ok =
          invokeSearch(service, denseOnly.toBuilder().setQuery("\"alpha shared\"").build());
      assertEquals(0L, ok.getTotalHits(), "vector-less corpus → empty dense result, but no error");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  private static SearchRequest multiLeg(String query, SearchQuerySyntax syntax) {
    return SearchRequest.newBuilder()
        .setQuery(query)
        .setLimit(10)
        .setQuerySyntax(syntax)
        // Dense enabled with no embedding service → hybrid degrades to the composable Bm25Only leg.
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build())
        .build();
  }

  private static SearchRequest hybridWithMimeFacet(SearchQuerySyntax syntax) {
    return SearchRequest.newBuilder()
        .setQuery(DIVERGING_QUERY)
        .setLimit(10)
        .setQuerySyntax(syntax)
        // Dense enabled with no embedding service → hybrid degrades to the composable Bm25Only leg,
        // i.e. MultiLegDecision + FacetCompute.FromFreshBm25 (the rebuild path under test).
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build())
        .setFacets(
            FacetSpec.newBuilder()
                .setInclude(true)
                .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10))
                .build())
        .build();
  }

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  /** Same call shape as {@link #invokeSearch}, but the failure is the assertion subject. */
  private static WorkerServiceException invokeSearchExpectingError(
      WorkerSearchService service, SearchRequest request) {
    return assertThrows(
        WorkerServiceException.class,
        () -> service.search(request, CallContext.none()),
        "search() answered instead of signalling the malformed query");
  }

  private RunningRuntime newLifecycleWithPdfDocs(Map<String, String> docs) throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-facet-syntax-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    for (var entry : docs.entrySet()) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put(SchemaFields.DOC_ID, entry.getKey());
      fields.put(SchemaFields.DOC_UID, entry.getKey() + "#0");
      fields.put(SchemaFields.PATH, entry.getKey());
      fields.put(SchemaFields.CONTENT, entry.getValue());
      fields.put(SchemaFields.MIME, "application/pdf");
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
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
