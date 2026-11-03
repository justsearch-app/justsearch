/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How deep a search ranks before it cuts the visible page, with and without a cross-encoder (Best
 * match first on or off). Without one the page the user sees is the fused order itself, so a short
 * page must be the prefix of the ten-result page, chunked corpora included. With one the Head
 * reranks a window it asked for, and the Worker's ranking stays what it was before the switch.
 */
final class ShortPageRankingDepthTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final List<Integer> SHORT_LIMITS = List.of(1, 5, 9);
  @TempDir Path temp;
  private String previous;
  private final List<RunningRuntime> runtimes = new ArrayList<>();

  @BeforeEach
  void remember() {
    previous = System.getProperty("justsearch.config");
  }

  @AfterEach
  void close() {
    try {
      for (var runtime : runtimes) runtime.close();
    } finally {
      if (previous == null) System.clearProperty("justsearch.config");
      else System.setProperty("justsearch.config", previous);
    }
  }

  @Test
  void chunkedShortPagesWithoutCrossEncoderArePrefixesOfTheTenResultPage() throws Exception {
    var service = chunked("");
    for (boolean dense : List.of(true, false)) {
      SearchResponse tenPage = search(service, 10, dense, false);
      assertEquals("APPLIED", TraceStageAccess.chunkMergeReason(tenPage),
          "the chunk branch is fused into the page");
      if (dense) assertEquals("HYBRID", tenPage.getSearchTrace().getEffectiveMode());
      List<String> order = ids(tenPage);
      assertEquals(10, order.size(), "the corpus fills a ten-result page");
      for (int limit : SHORT_LIMITS) {
        assertEquals(order.subList(0, limit), ids(search(service, limit, dense, false)),
            (dense ? "hybrid" : "keyword") + " limit " + limit
                + " is the prefix of the ten-result page");
      }
      List<String> longPage = ids(search(service, 50, dense, false));
      assertTrue(longPage.size() > 10, "a long page ranks further");
      assertTrue(longPage.containsAll(order), "the long page keeps every ten-page result");
    }
  }

  @Test
  void chunkRecallCompleteLeavesPagesWithoutCrossEncoderAlone() throws Exception {
    var plain = chunked("");
    var recallComplete = chunked("    chunk_leg_recall_complete_enabled: true\n");
    for (int limit : List.of(1, 5, 10)) {
      assertEquals(ids(search(plain, limit, true, false)),
          ids(search(recallComplete, limit, true, false)),
          "limit " + limit + ": without a cross-encoder nothing is spliced into the page");
    }
    // The lever still works where it exists for: the cross-encoder's window.
    assertNotEquals(ids(search(plain, 1, true, true)), ids(search(recallComplete, 1, true, true)),
        "with a cross-encoder the chunk legs' top parents are spliced into its window");
  }

  /**
   * With a cross-encoder the Worker ranks to the requested limit as it did before the switch
   * (8613d76e02): the depth floor and the stable depth are only for pages without one.
   */
  @Test
  void pagesWithCrossEncoderKeepTheirRankingBeforeTheSwitch() throws Exception {
    // Also 8613d76e02's pages for the round-1 corpus.
    var flat = flat();
    assertEquals(List.of("bm25-1"), ids(search(flat, 1, true, true)));
    assertEquals(List.of("bm25-1", "bm25-2", "bm25-3", "bm25-4", "bm25-5"),
        ids(search(flat, 5, true, true)));
    var chunked = chunked("");
    assertEquals(PINNED_CHUNKED_1, ids(search(chunked, 1, true, true)));
    assertEquals(PINNED_CHUNKED_5, ids(search(chunked, 5, true, true)));
    assertEquals(PINNED_CHUNKED_9, ids(search(chunked, 9, true, true)));
  }

  /**
   * The SPLADE pairs fuse two legs like the other hybrids. A document second on both legs wins
   * the ten-result fusion; the one-result page without a cross-encoder must show it, and not the
   * single-leg winner a fusion of each leg's first hit picks.
   */
  @Test
  void keywordAndSpladeWithoutCrossEncoderRankShortPagesToTheTenResultDepth() throws Exception {
    assertSpladePairRanksToTheTenResultDepth(true);
  }

  @Test
  void meaningAndSpladeWithoutCrossEncoderRankShortPagesToTheTenResultDepth() throws Exception {
    assertSpladePairRanksToTheTenResultDepth(false);
  }

  private void assertSpladePairRanksToTheTenResultDepth(boolean keyword) throws Exception {
    var service = spladePair();
    String pair = keyword ? "keyword+SPLADE" : "meaning+SPLADE";
    SearchResponse tenPage = spladeSearch(service, 10, keyword, false);
    assertTrue(tenPage.getSearchTrace().getDegradation().getSpladeExecuted(), pair);
    assertFalse(tenPage.getSearchTrace().getDegradation().getHybridFallback(), pair);
    List<String> order = ids(tenPage);
    assertEquals("both", order.get(0), pair + ": second on both legs wins the fusion " + order);
    assertEquals(List.of("both"), ids(spladeSearch(service, 1, keyword, false)),
        pair + ": the one-result page is the ten-result page's first hit");
    assertEquals(order.subList(0, 2), ids(spladeSearch(service, 2, keyword, false)), pair);
    // With a cross-encoder the Worker keeps ranking to the requested limit, as on 8613d76e02.
    assertEquals(PINNED_SPLADE_RERANK_1.get(keyword), ids(spladeSearch(service, 1, keyword, true)),
        pair + " with a cross-encoder");
  }

  // The one-result pages of the SPLADE corpus with a cross-encoder on 8613d76e02.
  private static final Map<Boolean, List<String>> PINNED_SPLADE_RERANK_1 =
      Map.of(true, List.of("keyword-top"), false, List.of("meaning-top"));

  // The pages this corpus returned on 8613d76e02, before short pages without a cross-encoder
  // were repaired.
  private static final List<String> PINNED_CHUNKED_1 = List.of("doc-0");
  private static final List<String> PINNED_CHUNKED_5 =
      List.of("doc-13", "doc-8", "doc-0", "doc-22", "doc-11");
  private static final List<String> PINNED_CHUNKED_9 = List.of("doc-13", "doc-26", "doc-19",
      "doc-39", "doc-22", "doc-11", "doc-10", "doc-12", "doc-35");

  private static SearchResponse search(
      WorkerSearchService service, int limit, boolean dense, boolean crossEncoder) {
    var pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(dense)
        .setFusionAlgorithm(dense ? "rrf" : "none").setCrossEncoderEnabled(crossEncoder);
    var request = SearchRequest.newBuilder().setQuery("apple").setLimit(limit).setPipeline(pipeline);
    if (dense) request.addAllVector(List.of(1f, 0f, 0f, 0f));
    return service.search(request.build(), CallContext.none());
  }

  private static SearchResponse spladeSearch(
      WorkerSearchService service, int limit, boolean keyword, boolean crossEncoder) {
    var pipeline = PipelineConfig.newBuilder().setSparseEnabled(keyword).setDenseEnabled(!keyword)
        .setSpladeEnabled(true).setFusionAlgorithm("rrf").setCrossEncoderEnabled(crossEncoder);
    var request = SearchRequest.newBuilder().setQuery("apple").setLimit(limit).setPipeline(pipeline);
    if (!keyword) request.addAllVector(List.of(1f, 0f, 0f, 0f));
    return service.search(request.build(), CallContext.none());
  }

  private static List<String> ids(SearchResponse response) {
    return response.getResultsList().stream().map(hit -> hit.getId()).toList();
  }

  private RunningRuntime open(String name, String hybridYaml) throws Exception {
    return open(name, hybridYaml, FieldCatalogDef.forChunkTesting(4));
  }

  private RunningRuntime open(String name, String hybridYaml, FieldCatalogDef catalog)
      throws Exception {
    Path dir = Files.createDirectories(temp.resolve(name));
    Path config = dir.resolve("config.yaml");
    Files.writeString(config, "app:\n  data_dir: '" + dir.toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: " + name + "\n      roots: ['ignored']\n"
        + "  vector:\n    dimension: 4\n"
        + (hybridYaml.isEmpty() ? "" : "  hybrid:\n" + hybridYaml));
    System.setProperty("justsearch.config", config.toString());
    var runtime = IndexSchema.fromCatalog(catalog).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtimes.add(runtime);
    return runtime;
  }

  /** The round-1 corpus: ten lexical, nineteen dense and one two-signal document, no chunks. */
  private WorkerSearchService flat() throws Exception {
    var runtime = open("flat", "");
    for (int i = 0; i < 10; i++) {
      parent(runtime, "bm25-" + (i + 1), roundOne(20 - i), new float[] {0f, 1f, 0f, 0f});
    }
    for (int i = 0; i < 19; i++) {
      parent(runtime, "vec-" + (i + 1), "filler filler filler",
          i < 10 ? new float[] {1f, 0f, 0f, 0f} : new float[] {0.7f, 0.3f, 0f, 0f});
    }
    parent(runtime, "both", roundOne(10), new float[] {0.9f, 0.1f, 0f, 0f});
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return new WorkerSearchService(runtime);
  }

  /**
   * One leader per leg (keyword, meaning, SPLADE) found by that leg only, and "both", second on
   * every leg. A query SPLADE encoder weighs the one query term.
   */
  private WorkerSearchService spladePair() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef(
        "splade_status", "keyword", true, true, List.of("filter"), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef(
        "splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    var runtime = open("splade", "", new FieldCatalogDef("chunk-test+splade", fields));
    spladeDoc(runtime, "keyword-top", roundOne(30), new float[] {0f, 1f, 0f, 0f}, null);
    spladeDoc(runtime, "meaning-top", "filler filler filler", new float[] {1f, 0f, 0f, 0f}, null);
    spladeDoc(runtime, "splade-top", "filler filler filler", new float[] {0f, 0f, 1f, 0f}, 9f);
    spladeDoc(runtime, "both", roundOne(20), new float[] {0.9f, 0.1f, 0f, 0f}, 5f);
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    var splade = org.mockito.Mockito.mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    org.mockito.Mockito.when(splade.encode(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(io.justsearch.core.execution.InferenceRequest.class)))
        .thenReturn(Map.of("apple", 1f));
    var bindings = new io.justsearch.indexerworker.server.EncoderBindings();
    bindings.bindSpladeEncoder(splade);
    return new WorkerSearchService(runtime,
        io.justsearch.indexerworker.embed.NoOpEmbeddingProvider.INSTANCE, bindings);
  }

  private static void spladeDoc(
      RunningRuntime runtime, String id, String content, float[] vector, Float apple) {
    Map<String, Object> fields = new HashMap<>(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, "/" + id,
        SchemaFields.CONTENT, content, SchemaFields.VECTOR, vector));
    if (apple != null) fields.put(SchemaFields.SPLADE, Map.of("apple", apple));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  /**
   * Forty parents of two chunks each. Term counts and vectors come from a fixed seed, so parents
   * and their passages rank differently on each leg and at each depth.
   */
  private WorkerSearchService chunked(String hybridYaml) throws Exception {
    var runtime = open("chunked" + runtimes.size(), hybridYaml);
    var random = new Random(20261103L);
    for (int p = 0; p < 40; p++) {
      String id = "doc-" + p;
      String first = apples(random.nextInt(12));
      String second = apples(random.nextInt(12));
      String content = first + " " + second;
      parent(runtime, id, content, vector(random));
      int start = 0;
      for (int c = 0; c < 2; c++) {
        String text = c == 0 ? first : second;
        Map<String, Object> fields = new HashMap<>();
        fields.put(SchemaFields.DOC_ID, id + "-chunk-" + c);
        fields.put(SchemaFields.DOC_UID, id + "-chunk-" + c + "#0");
        fields.put(SchemaFields.IS_CHUNK, "true");
        fields.put(SchemaFields.PARENT_DOC_ID, id);
        fields.put(SchemaFields.CHUNK_INDEX, String.valueOf(c));
        fields.put(SchemaFields.CHUNK_TOTAL, "2");
        fields.put(SchemaFields.CHUNK_CONTENT, text);
        fields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
        fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(start + text.length()));
        fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
        fields.put(SchemaFields.PATH, "/" + id);
        fields.put(SchemaFields.CHUNK_VECTOR, vector(random));
        fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
        fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
        start += text.length() + 1;
      }
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return new WorkerSearchService(runtime);
  }

  private static void parent(RunningRuntime runtime, String id, String content, float[] vector) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, "/" + id,
        SchemaFields.CONTENT, content, SchemaFields.VECTOR, vector)));
  }

  private static float[] vector(Random random) {
    float x = random.nextFloat();
    return new float[] {x, 1f - x, random.nextFloat() * 0.2f, 0f};
  }

  private static String roundOne(int apples) {
    return "apple ".repeat(apples) + "filler ".repeat(100 - apples);
  }

  private static String apples(int apples) {
    return "apple ".repeat(apples) + "filler ".repeat(40 - apples);
  }
}
