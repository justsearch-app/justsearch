package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.context.EngineContext;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridSearchIntegrationTest extends RuntimeTestBase {

  @Test
  void filteredHybridSearchAppliesPrefixExpansion() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: hybridprefixtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-js",
                SchemaFields.DOC_UID, "doc-js#0",
                SchemaFields.CONTENT, "justsearch is a local search engine",
                SchemaFields.VECTOR, new float[] {1.0f, 0.0f, 0.0f, 0.0f})));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Filtered hybrid search with partial word -- this path previously bypassed
    // buildTextQuery and missed prefix expansion entirely.
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    SearchResult result = runtime.hybridSearchOps().searchHybridFiltered(
        "justsearc", queryVector, 10, QueryFilterBuilder.buildFilterQueryOnly(filters), EngineContext.Urgency.FOREGROUND);

    assertNotNull(result);
    assertTrue(
        result.hits().size() >= 1,
        "Filtered hybrid search should find 'justsearch' via prefix expansion of 'justsearc'");

    runtime.close();
  }

  @Test
  void searchHybridCombinesTextAndVectorResults() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: hybridtest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index documents with both text and vectors
    // doc-1: High text relevance for "fox", low vector similarity
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "The quick brown fox jumps over the lazy dog fox fox",
                SchemaFields.VECTOR,
                    new float[] {0.0f, 1.0f, 0.0f, 0.0f} // Orthogonal to query
                )));

    // doc-2: Medium text relevance for "fox", high vector similarity
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.CONTENT, "A fast red fox runs",
                SchemaFields.VECTOR,
                    new float[] {0.9f, 0.1f, 0.0f, 0.0f} // Very close to query
                )));

    // doc-3: No text relevance for "fox", medium vector similarity
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.CONTENT, "The cat sleeps peacefully",
                SchemaFields.VECTOR,
                    new float[] {0.5f, 0.5f, 0.0f, 0.0f} // Medium similarity
                )));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Query: "fox" with vector pointing in +X direction
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};

    SearchResult result = runtime.hybridSearchOps().searchHybrid("fox", queryVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND);

    assertNotNull(result);
    assertTrue(result.hits().size() >= 2, "Should return at least 2 documents");

    // doc-1 and doc-2 should be in top results (both match either text or vector well)
    var docIds = result.hits().stream().map(h -> h.docId()).toList();
    assertTrue(docIds.contains("doc-1"), "doc-1 should be in results (high text relevance)");
    assertTrue(
        docIds.contains("doc-2"), "doc-2 should be in results (high vector + text relevance)");

    // doc-2 should appear in the top 2: it has both text relevance ("fox") and high vector
    // similarity, so RRF should keep it near the top of the fused ranking.
    int doc2Rank = docIds.indexOf("doc-2");
    assertTrue(
        doc2Rank >= 0 && doc2Rank <= 1,
        "doc-2 (text + vector) should be in top 2 results, got rank=" + doc2Rank);

    runtime.close();
  }

  @Test
  void searchHybridCandidateMultipliersControlWhetherCrossSignalOverlapCanEnterTopK()
      throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: hybridcandidates\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    System.setProperty("index.hybrid.fusion_strategy", "rrf");
    // Tempdoc 636 shipped leg-arbitration + recall-complete default-ON (commits 65821feeb/3b534ba73).
    // This test isolates the candidate-multiplier → cross-signal-RRF mechanism it is named for, so the
    // always-on levers (which re-weight fusion and splice each leg's pool) must be disabled here.
    System.setProperty("index.hybrid.leg_arbitration_enabled", "false");
    System.setProperty("index.hybrid.leg_recall_complete_enabled", "false");

    var runtime = createRuntimeWithDim(4);

    // Construct a deterministic scenario:
    // - 10 BM25-only docs (match "apple"), with vectors far from queryVector.
    // - 10 vector-only docs (no "apple"), with vectors very close to queryVector.
    // - 1 overlap doc ("both") that is rank 11 in BM25 and rank 11 in vector, but when included
    //   in both candidate pools it should win via RRF because it has two signals.
    //
    // With candidate multipliers = 1, we retrieve only top 10 from each list => overlap doc is
    // excluded.
    // With candidate multipliers >= 2, overlap doc is included (rank 11) and should become rank
    // 1 fused.

    String queryText = "apple";
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};

    // Helper: fixed-length content with N occurrences of the query token and filler tokens.
    java.util.function.IntFunction<String> contentWithAppleCount =
        (count) -> {
          int total = 100;
          int apples = Math.max(0, Math.min(total, count));
          int filler = total - apples;
          StringBuilder sb = new StringBuilder(total * 6);
          for (int i = 0; i < apples; i++) sb.append("apple ");
          for (int i = 0; i < filler; i++) sb.append("filler ");
          return sb.toString();
        };

    // BM25-only docs: apple count 20..11 (all > overlap's 10), vectors far (orthogonal).
    for (int i = 0; i < 10; i++) {
      int appleCount = 20 - i;
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID,
                  "bm25-" + (i + 1),
                  SchemaFields.DOC_UID,
                  "bm25-" + (i + 1) + "#0",
                  SchemaFields.CONTENT,
                  contentWithAppleCount.apply(appleCount),
                  SchemaFields.VECTOR,
                  new float[] {0.0f, 1.0f, 0.0f, 0.0f})));
    }

    // Vector-only docs: no "apple".
    // - vec-1..vec-10 are exact matches (occupy ranks 1..10)
    // - vec-11..vec-19 are "middle" matches (occupy ranks 12..20) so that when
    //   vectorCandidateLimit=20, the BM25-only docs are still excluded from vector candidates.
    for (int i = 0; i < 19; i++) {
      float[] vec =
          i < 10
              ? new float[] {1.0f, 0.0f, 0.0f, 0.0f}
              : new float[] {0.7f, 0.3f, 0.0f, 0.0f};
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID,
                  "vec-" + (i + 1),
                  SchemaFields.DOC_UID,
                  "vec-" + (i + 1) + "#0",
                  SchemaFields.CONTENT,
                  "filler filler filler",
                  SchemaFields.VECTOR,
                  vec)));
    }

    // Overlap doc: apple count 10 (rank 11 in BM25), vector slightly worse than exact (rank 11
    // in vector).
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID,
                "both",
                SchemaFields.DOC_UID,
                "both#0",
                SchemaFields.CONTENT,
                contentWithAppleCount.apply(10),
                SchemaFields.VECTOR,
                new float[] {0.9f, 0.1f, 0.0f, 0.0f})));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Case 1: multipliers=1 => candidate limits = 10 => overlap excluded.
    System.setProperty("index.hybrid.text_candidate_multiplier", "1");
    System.setProperty("index.hybrid.vector_candidate_multiplier", "1");
    new LifecycleTestAccessor(runtime).refreshConfigForTests();
    var small = runtime.hybridSearchOps().searchHybrid(queryText, queryVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND);
    var smallIds = small.hits().stream().map(h -> h.docId()).toList();
    assertFalse(
        smallIds.contains("both"),
        "Overlap doc should be excluded when candidate pools stop at top-10");
    assertNotNull(small.hits().get(0).docId());
    assertFalse(
        "both".equals(small.hits().get(0).docId()),
        "Top hit should not be 'both' when it is excluded");

    // Case 2: multipliers=2 => candidate limits = 20 => overlap included and should win via RRF
    // (two signals).
    System.setProperty("index.hybrid.text_candidate_multiplier", "2");
    System.setProperty("index.hybrid.vector_candidate_multiplier", "2");
    new LifecycleTestAccessor(runtime).refreshConfigForTests();
    var large = runtime.hybridSearchOps().searchHybrid(queryText, queryVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND);
    assertEquals(
        "both",
        large.hits().get(0).docId(),
        "Overlap doc should win when included in both candidate pools");

    System.clearProperty("index.hybrid.fusion_strategy");
    System.clearProperty("index.hybrid.leg_arbitration_enabled");
    System.clearProperty("index.hybrid.leg_recall_complete_enabled");
    runtime.close();
  }

  @Test
  void searchHybridAppliesLowSignalCapBasedOnBm25Evidence() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: hybridlowsignalbm25\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 4\n"
            + "  hybrid:\n"
            + "    # Disable vectorTop-based low-signal detection so the test exercises BM25-driven gating.\n"
            + "    vector_low_signal_top_score_threshold: 0.0\n"
            + "    # Treat extremely sparse BM25 evidence as low-signal.\n"
            + "    bm25_low_signal_total_hits_threshold: 1\n"
            + "    # When low-signal, allow zero vector-only docs into the fused set.\n"
            + "    vector_only_cap_low_signal: 0\n";

    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());
    System.setProperty("index.hybrid.fusion_strategy", "rrf");
    // Tempdoc 636 shipped recall-complete + leg-arbitration default-ON; the recall-complete pool
    // re-injects each leg's top-N, which overrides the BM25-driven vector_only_cap_low_signal this
    // test exercises. Disable both levers so the low-signal gating mechanism is tested in isolation.
    System.setProperty("index.hybrid.leg_arbitration_enabled", "false");
    System.setProperty("index.hybrid.leg_recall_complete_enabled", "false");

    var runtime = createRuntimeWithDim(4);

    // doc-1: the only lexical match for "uniqueterm"
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID,
                "doc-1",
                SchemaFields.DOC_UID,
                "doc-1#0",
                SchemaFields.CONTENT,
                "uniqueterm",
                SchemaFields.VECTOR,
                new float[] {0.0f, 1.0f, 0.0f, 0.0f})));

    // doc-2/doc-3: no lexical match, but strong vector similarity to the query vector
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID,
                "doc-2",
                SchemaFields.DOC_UID,
                "doc-2#0",
                SchemaFields.CONTENT,
                "unrelated content",
                SchemaFields.VECTOR,
                new float[] {1.0f, 0.0f, 0.0f, 0.0f})));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID,
                "doc-3",
                SchemaFields.DOC_UID,
                "doc-3#0",
                SchemaFields.CONTENT,
                "more unrelated content",
                SchemaFields.VECTOR,
                new float[] {0.9f, 0.1f, 0.0f, 0.0f})));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    SearchResult result = runtime.hybridSearchOps().searchHybrid("uniqueterm", queryVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND);

    assertNotNull(result);

    var docIds = result.hits().stream().map(h -> h.docId()).toList();
    assertTrue(docIds.contains("doc-1"), "BM25 hit should always be included");
    assertFalse(
        docIds.contains("doc-2"),
        "vector-only doc should be excluded under bm25-driven low-signal gating");
    assertFalse(
        docIds.contains("doc-3"),
        "vector-only doc should be excluded under bm25-driven low-signal gating");
    assertEquals(
        1, docIds.size(), "With cap=0 and no overlap requirement, only BM25 hits should remain");

    System.clearProperty("index.hybrid.fusion_strategy");
    System.clearProperty("index.hybrid.leg_arbitration_enabled");
    System.clearProperty("index.hybrid.leg_recall_complete_enabled");
    runtime.close();
  }

  @Test
  void searchHybridWithInvalidInputsThrows() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: invalidtest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    float[] validVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};

    // Null query text should throw
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.hybridSearchOps().searchHybrid(null, validVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND));

    // Blank query text should throw
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.hybridSearchOps().searchHybrid("  ", validVector, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND));

    // Null vector should throw
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.hybridSearchOps().searchHybrid("query", null, 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND));

    // Empty vector should throw
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.hybridSearchOps().searchHybrid("query", new float[0], 10, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND));

    runtime.close();
  }

  /**
   * Tempdoc 821 §P: the HYBRID entry points must forward the caller's syntax to their text leg.
   *
   * <p>This is the headline production path and the one the gRPC-level tests cannot reach (with no
   * embedding service every request degrades to {@code Bm25Only}), so hardcoding SIMPLE at
   * {@code HybridSearchOps}' two text-leg lambdas would pass the whole suite otherwise.
   *
   * <p>The discriminator is per-hit PROVENANCE, not hit count: the dense leg returns all three docs
   * either way, so only "which docs did the BM25 leg contribute" separates a LUCENE parse (required
   * clauses → doc-a alone) from a SIMPLE one (escaped → token OR → all three).
   */
  @Test
  void hybridEntryPointsForwardQuerySyntaxToTheTextLeg() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: hybridsyntaxtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);
    indexVectorDoc(runtime, "doc-a", "alpha shared term", new float[] {1.0f, 0.0f, 0.0f, 0.0f});
    indexVectorDoc(runtime, "doc-b", "beta shared term", new float[] {0.9f, 0.1f, 0.0f, 0.0f});
    indexVectorDoc(runtime, "doc-c", "gamma shared term", new float[] {0.8f, 0.2f, 0.0f, 0.0f});
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var hybridOps = runtime.hybridSearchOps();
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    org.apache.lucene.search.Query passThrough = new org.apache.lucene.search.MatchAllDocsQuery();

    // searchHybridFiltered → searchTextWithFilter (the Bm25Dense leg's non-debug path)
    assertEquals(
        1,
        bm25ContributedDocIds(
                hybridOps.searchHybridFiltered(
                    "+shared +alpha",
                    queryVector,
                    10,
                    passThrough,
                    LuceneRuntimeTypes.QuerySyntax.LUCENE, EngineContext.Urgency.FOREGROUND))
            .size(),
        "LUCENE required clauses must reach the filtered hybrid's text leg");
    assertEquals(
        3,
        bm25ContributedDocIds(
                hybridOps.searchHybridFiltered(
                    "+shared +alpha",
                    queryVector,
                    10,
                    passThrough,
                    LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND))
            .size(),
        "SIMPLE escapes them into a token OR — all three docs come from the text leg");

    // searchHybridWithDebug → searchText (the Bm25Dense leg's debug path, a separate lambda)
    assertEquals(
        1,
        bm25ContributedDocIds(
                hybridOps.searchHybridWithDebug(
                    "+shared +alpha", queryVector, 10, null, LuceneRuntimeTypes.QuerySyntax.LUCENE, EngineContext.Urgency.FOREGROUND))
            .size(),
        "the debug hybrid path must forward the syntax too");
    assertEquals(
        3,
        bm25ContributedDocIds(
                hybridOps.searchHybridWithDebug(
                    "+shared +alpha", queryVector, 10, null, LuceneRuntimeTypes.QuerySyntax.SIMPLE, EngineContext.Urgency.FOREGROUND))
            .size(),
        "and must stay SIMPLE for a SIMPLE caller");

    runtime.close();
  }

  /** Doc ids whose typed provenance shows a BM25 (text-leg) contribution. */
  private static java.util.List<String> bm25ContributedDocIds(SearchResult result) {
    return result.hits().stream()
        .filter(h -> h.provenance() != null && h.provenance().bm25() != null)
        .map(LuceneRuntimeTypes.SearchHit::docId)
        .toList();
  }

  private static void indexVectorDoc(
      RunningRuntime runtime, String docId, String content, float[] vector) throws Exception {
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.CONTENT, content,
                SchemaFields.VECTOR, vector)));
  }
}
