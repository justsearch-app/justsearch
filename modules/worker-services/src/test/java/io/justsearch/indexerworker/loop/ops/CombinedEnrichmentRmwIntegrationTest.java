/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Real Lucene RMW proof for parent SPLADE preservation (the unit fake cannot observe postings). */
@DisplayName("CombinedEnrichmentBackfillOps — parent SPLADE RMW")
class CombinedEnrichmentRmwIntegrationTest {

  @TempDir java.nio.file.Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(testCatalog()).atPath(tempDir).open();
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("pending embedding keeps completed parent's sparse postings in one real RMW")
  void pendingEmbedding_rederivesCompletedParentSplade_andKeepsPosting() throws Exception {
    EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1f, 2f}));
    when(splade.encodeBatch(anyList())).thenReturn(List.of(Map.of("fresh", 2.0f)));

    String docId = "parent-embed";
    index(
        Map.of(
            SchemaFields.DOC_ID,
            docId,
            SchemaFields.DOC_UID,
            docId,
            SchemaFields.PATH,
            docId + ".txt",
            SchemaFields.CONTENT,
            "content for embedding",
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED,
            SchemaFields.SPLADE,
            Map.of("old", 2.0f)));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(embedding, splade, null));
    refresh();

    assertEquals(
        SchemaFields.SPLADE_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS));
    assertTrue(hasSparseHit("fresh", docId), "the newly derived FeatureField posting must survive RMW");
  }

  @Test
  @DisplayName("pending NER keeps completed parent's sparse postings in one real RMW")
  void pendingNer_rederivesCompletedParentSplade_andKeepsPosting() throws Exception {
    SpladeEncoder splade = mock(SpladeEncoder.class);
    NerService ner = mock(NerService.class);
    when(splade.encodeBatch(anyList())).thenReturn(List.of(Map.of("fresh", 2.0f)));
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));

    String docId = "parent-ner";
    index(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, docId,
        SchemaFields.PATH, docId + ".txt",
        SchemaFields.CONTENT, "content for ner",
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
        SchemaFields.SPLADE, Map.of("old", 2.0f),
        SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(
        context(null, splade, ner));
    refresh();

    assertEquals(
        SchemaFields.SPLADE_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertEquals(
        SchemaFields.NER_STATUS_COMPLETED_EMPTY,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.NER_STATUS));
    assertTrue(hasSparseHit("fresh", docId), "the newly derived FeatureField posting must survive RMW");
  }

  @Test
  void failedRederivationResetsStatusAndRemovesOldPostingsWithRetry() throws Exception {
    EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1f, 2f}));
    when(splade.encodeBatch(anyList())).thenThrow(new IllegalStateException("encoder failed"));
    String docId = "parent-failure";
    index(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, docId,
        SchemaFields.PATH, docId + ".txt",
        SchemaFields.CONTENT, "content for embedding",
        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
        SchemaFields.SPLADE, Map.of("old", 2.0f)));
    assertTrue(hasSparseHit("old", docId), "the seed must contain real sparse postings");

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(embedding, splade, null));
    refresh();

    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS));
    assertEquals(
        SchemaFields.SPLADE_STATUS_PENDING,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertEquals(
        "1",
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_RETRY_COUNT));
    assertFalse(
        hasSparseHit("old", docId),
        "failed re-derivation must not claim stale postings survived");
  }

  @Test
  @DisplayName("blank content does not encode and the real RMW truthfully resets sparse state")
  void blankContent_skipsEncoderAndRealRmwResetsCompletedSplade() throws Exception {
    EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(embedding.isAvailable()).thenReturn(true);
    String docId = "parent-blank";
    index(
        Map.of(
            SchemaFields.DOC_ID,
            docId,
            SchemaFields.DOC_UID,
            docId,
            SchemaFields.PATH,
            docId + ".txt",
            SchemaFields.CONTENT,
            "",
            SchemaFields.EMBEDDING_STATUS,
            SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_COMPLETED,
            SchemaFields.SPLADE,
            Map.of("old", 2.0f)));
    assertTrue(hasSparseHit("old", docId), "the seed must contain real sparse postings");

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(embedding, splade, null));
    refresh();

    verify(embedding, never()).embedDocumentBatch(anyList());
    verify(splade, never()).encodeBatch(anyList());
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS));
    assertEquals(
        "1",
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.SPLADE_STATUS_PENDING,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertFalse(hasSparseHit("old", docId), "the reset-status RMW must remove stale postings");
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      EmbeddingProvider embedding, SpladeEncoder splade, NerService ner) {
    return new CombinedEnrichmentBackfillOps.BackfillContext(
        runtime.documentFieldOps(),
        runtime.indexingCoordinator(),
        runtime.commitOps(),
        IndexingPacing.unthrottled(),
        embedding == null ? () -> null : () -> embedding,
        () -> splade,
        ner == null ? () -> null : () -> ner,
        () -> true,
        () -> true,
        10,
        LoggerFactory.getLogger(CombinedEnrichmentRmwIntegrationTest.class),
        false,
        false,
        false,
        0,
        new ArrayDeque<>(),
        new ArrayDeque<>(),
        new int[] {0},
        () -> false,
        () -> false,
        () -> false,
        new WindowedEmbedProgress());
  }

  private void index(Map<String, Object> fields) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void refresh() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private boolean hasSparseHit(String token, String docId) {
    var query = runtime.textQueryOps().buildSpladeQuery(Map.of(token, 1.0f), null);
    LuceneRuntimeTypes.SearchResult result =
        runtime.readPathOps().search(
            query, 10, Set.of(SchemaFields.DOC_ID),
            LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    return result.hits().stream().anyMatch(hit -> docId.equals(hit.fields().get(SchemaFields.DOC_ID)));
  }

  // Mirror canonical stored status/retry fields; only vector dimensions are reduced for the test.
  private static FieldCatalogDef testCatalog() {
    return new FieldCatalogDef(
        "combined-rmw-test",
        List.of(
            new FieldCatalogDef.FieldDef("doc_id", "keyword", true, true, List.of("id"), null, null, false),
            new FieldCatalogDef.FieldDef("doc_uid", "keyword", true, true, List.of("tiebreak"), null, null, false),
            new FieldCatalogDef.FieldDef("path", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldCatalogDef.FieldDef("content", "text", true, false, List.of(), null, "icu", false),
            new FieldCatalogDef.FieldDef("is_chunk", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldCatalogDef.FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldCatalogDef.FieldDef("embedding_retry_count", "long", true, true, List.of(), null, null, false),
            new FieldCatalogDef.FieldDef("vector", "vector", false, false, List.of("vector"), new FieldCatalogDef.VectorSpec(2), null, false, "preserve-reread-or-reset:embedding_status"),
            new FieldCatalogDef.FieldDef("splade_status", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldCatalogDef.FieldDef("splade_retry_count", "long", true, true, List.of(), null, null, false),
            new FieldCatalogDef.FieldDef("splade", "splade", false, false, List.of(), null, null, false, "reset-status:splade_status"),
            new FieldCatalogDef.FieldDef("ner_status", "keyword", true, true, List.of("filter"), null, null, false),
            new FieldCatalogDef.FieldDef("ner_retry_count", "long", true, true, List.of(), null, null, false)));
  }
}
