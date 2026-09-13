package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Head reranks the full returned pool (up to {@code searchLimit}) and then trims, so a hit
 * ranked 11th+ by the Worker's pre-rerank order can still surface into the visible top-K. Before
 * the fix, {@code SearchResponseBuilder.toGrpcResponseBuilder} only enriched (parent title /
 * excerpt regions) the first 10 hits in that pre-rerank order, so a reranked-up hit arrived blank.
 */
final class SearchResponseBuilderEnrichmentCapTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final FieldCatalogDef CATALOG = FieldCatalogDef.forChunkTesting(0);
  private static final int HIT_COUNT = 13;
  private static final int BEYOND_CAP_INDEX = 12; // >= 10, out of reach of the old gate

  private RunningRuntime lifecycle;
  private SearchResponseBuilder builder;

  @BeforeEach
  void setUp() throws Exception {
    lifecycle = IndexSchema.fromCatalog(CATALOG).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "parent-1",
                    SchemaFields.DOC_UID, "parent-1#0",
                    SchemaFields.TITLE, "Parent Title",
                    SchemaFields.CONTENT, "unrelated filler content")));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    builder =
        new SearchResponseBuilder(
            lifecycle.indexCountOps(),
            lifecycle.documentFieldOps(),
            lifecycle.textQueryOps(),
            lifecycle.facetingEngine(),
            lifecycle::indexAnalyzerOrNull,
            lifecycle::resolvedConfig);
  }

  @AfterEach
  void tearDown() {
    if (lifecycle != null) lifecycle.close();
  }

  @Test
  void enrichesChunkHitsBeyondThePreRerankTop10() {
    List<LuceneRuntimeTypes.SearchHit> hits = new ArrayList<>();
    for (int i = 0; i < HIT_COUNT; i++) {
      hits.add(
          new LuceneRuntimeTypes.SearchHit(
              "chunk:parent-1#" + i,
              1.0f - (i * 0.01f),
              Map.of(
                  SchemaFields.PARENT_DOC_ID, "parent-1",
                  SchemaFields.IS_CHUNK, "true",
                  SchemaFields.CHUNK_CONTENT, "some filler text containing needle for excerpting")));
    }
    LuceneRuntimeTypes.SearchResult result =
        new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 5L);
    PipelineConfig pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).build();

    SearchResponse response =
        builder
            .toGrpcResponseBuilder(
                result, 5L, "needle", pipeline, null, /* includeExcerpts= */ true,
                /* includeDetail= */ false)
            .build();

    assertEquals(HIT_COUNT, response.getResultsCount());
    var beyondCapHit = response.getResults(BEYOND_CAP_INDEX);
    assertEquals(
        "Parent Title",
        beyondCapHit.getFieldsMap().get(SchemaFields.TITLE),
        "Parent title must be resolved even beyond the old pre-rerank top-10 cutoff");
    assertFalse(
        beyondCapHit.getExcerptRegionsList().isEmpty(),
        "Excerpt regions must be computed even beyond the old pre-rerank top-10 cutoff");
  }
}
