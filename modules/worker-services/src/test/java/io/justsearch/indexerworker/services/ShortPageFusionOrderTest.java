/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Searches without a cross-encoder (Best match first off, or no reranker installed): the Head asks
 * the Worker for exactly the visible limit. Live on 8613d76e02 a hybrid search at limit 5 returned
 * unrelated documents while limit 10 and above did not. A short page must be the prefix of a long
 * one: the same fused order, only fewer results.
 */
final class ShortPageFusionOrderTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final List<Integer> LIMITS = List.of(1, 5, 9, 10, 50);
  @TempDir Path temp;
  private String previous;
  private RunningRuntime runtime;
  private WorkerSearchService service;

  @BeforeEach
  void open() throws Exception {
    previous = System.getProperty("justsearch.config");
    Path config = temp.resolve("config.yaml");
    Files.writeString(config, "app:\n  data_dir: '" + temp.toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: shortpage\n      roots: ['ignored']\n"
        + "  vector:\n    dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(runtime);
    seed();
  }

  @AfterEach
  void close() {
    try { if (runtime != null) runtime.close(); }
    finally {
      if (previous == null) System.clearProperty("justsearch.config");
      else System.setProperty("justsearch.config", previous);
    }
  }

  @Test
  void everyLimitWithoutCrossEncoderIsAPrefixOfTheLongPage() {
    SearchResponse longPage = search(50);
    assertEquals("HYBRID", longPage.getSearchTrace().getEffectiveMode(), "both legs fused");
    List<String> order = ids(longPage);
    assertEquals(30, order.size(), "every lexical and dense candidate is ranked");
    for (int limit : LIMITS) {
      List<String> page = ids(search(limit));
      assertEquals(order.subList(0, Math.min(limit, order.size())), page,
          "limit " + limit + " keeps the fused order of the long page");
    }
    assertTrue(order.contains("both"), "the two-signal document is ranked");
  }

  private SearchResponse search(int limit) {
    // The Head's request for this search: hybrid, relevance order, no cross-encoder window.
    var request = SearchRequest.newBuilder().setQuery("apple").setLimit(limit)
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true)
            .setFusionAlgorithm("rrf").setCrossEncoderEnabled(false))
        .addAllVector(List.of(1f, 0f, 0f, 0f)).build();
    return service.search(request, CallContext.none());
  }

  private static List<String> ids(SearchResponse response) {
    return response.getResultsList().stream().map(hit -> hit.getId()).toList();
  }

  /**
   * Ten lexical-only documents, nineteen dense-only documents and one document both legs rank
   * eleventh: shallow candidate pools leave it out, full pools rank it on two signals.
   */
  private void seed() {
    for (int i = 0; i < 10; i++) {
      index("bm25-" + (i + 1), content(20 - i), new float[] {0f, 1f, 0f, 0f});
    }
    for (int i = 0; i < 19; i++) {
      index("vec-" + (i + 1), "filler filler filler",
          i < 10 ? new float[] {1f, 0f, 0f, 0f} : new float[] {0.7f, 0.3f, 0f, 0f});
    }
    index("both", content(10), new float[] {0.9f, 0.1f, 0f, 0f});
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void index(String id, String content, float[] vector) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, "/" + id,
        SchemaFields.CONTENT, content, SchemaFields.VECTOR, vector)));
  }

  private static String content(int apples) {
    return "apple ".repeat(apples) + "filler ".repeat(100 - apples);
  }
}
