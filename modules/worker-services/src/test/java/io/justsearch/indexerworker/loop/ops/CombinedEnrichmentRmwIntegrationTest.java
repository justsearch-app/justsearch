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
class CombinedEnrichmentRmwIntegrationTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir java.nio.file.Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(testCatalog()).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
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
  void absentSpladeEncoderPublishesEmbeddingAndRetainsCompletedSplade() throws Exception {
    EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1f, 2f}));
    String docId = "parent-without-encoder";
    index(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, docId,
        SchemaFields.PATH, docId + ".txt",
        SchemaFields.CONTENT, "content for embedding",
        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
        SchemaFields.SPLADE_RETRY_COUNT, "0",
        SchemaFields.SPLADE, Map.of("old", 2.0f)));
    assertTrue(hasSparseHit("old", docId), "the seed must contain real sparse postings");

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(embedding, null, null));
    refresh();

    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertEquals("0",
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_RETRY_COUNT));
    assertTrue(hasSparseHit("old", docId), "embedding-only work retains independent sparse postings");
  }

  @Test
  void failedPreservationPublishesEmbeddingAndResetsSplade() throws Exception {
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

    assertFalse(hasSparseHit("old", docId), "failed preservation uses the baseline reset lane");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS));
    assertEquals(
        SchemaFields.SPLADE_STATUS_PENDING,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertEquals(
        "1",
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_RETRY_COUNT));
  }

  @Test
  @DisplayName("blank content does not encode and embedding bookkeeping retains sparse state")
  void blankContent_skipsEncoderAndRetainsIndependentCompletedSplade() throws Exception {
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
        SchemaFields.SPLADE_STATUS_COMPLETED,
        runtime.documentFieldOps().getDocumentField(docId, SchemaFields.SPLADE_STATUS));
    assertTrue(hasSparseHit("old", docId), "embedding retry bookkeeping must retain sparse postings");
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "retry", "terminal", "blank", "fallback"})
  void embeddingBatchReadsSparseDictionaryOnce(String outcome) throws Exception {
    int count = 8;
    var features = new java.util.HashMap<String, Float>();
    for (int term = 0; term < 64; term++) features.put("term" + term, 2f);
    for (int doc = 0; doc < count; doc++) {
      var fields = new java.util.HashMap<String, Object>();
      String id = "sparse-parent-" + doc;
      fields.put(SchemaFields.DOC_ID, id);
      fields.put(SchemaFields.DOC_UID, id);
      fields.put(SchemaFields.CONTENT, outcome.equals("blank") ? "" : "parent text " + doc);
      fields.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
      fields.put(SchemaFields.EMBEDDING_RETRY_COUNT,
          outcome.equals("terminal") ? SchemaFields.EMBEDDING_MAX_RETRIES - 1 : 0);
      fields.put(SchemaFields.SPLADE_STATUS, "COMPLETED");
      fields.put(SchemaFields.SPLADE_RETRY_COUNT, 0);
      fields.put(SchemaFields.SPLADE, features);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    refresh();
    var embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    if (!outcome.equals("blank")) {
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> outcome.equals("fallback")
          ? List.of() : java.util.Collections.nCopies(count,
              outcome.equals("success") ? new float[] {1f, 2f} : null));
    }
    var context = new EmbeddingBackfillOps.BackfillContext(runtime.documentFieldOps(),
        runtime.indexingCoordinator(), runtime.commitOps(), mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
        IndexingPacing.unthrottled(), () -> embedding, () -> true, () -> true, count,
        LoggerFactory.getLogger(getClass()));
    var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
        io.justsearch.adapters.lucene.runtime.WritePathOps.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    var oldLevel = logger.getLevel();
    events.start(); logger.addAppender(events); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    try {
      var result = EmbeddingBackfillOps.processEmbeddingBackfill(context);
      assertTrue(result.success());
      long scans = events.list.stream().filter(event -> event.getFormattedMessage()
          .startsWith("Prepared legacy sparse preservation:")).count();
      System.out.println("Sparse preservation scans: " + outcome + "=" + scans + ", docs=" + count + ", terms=64");
      assertEquals(1, scans, "one populated sparse dictionary scan per embedding outcome batch");
    } finally {
      logger.detachAppender(events); events.stop(); logger.setLevel(oldLevel);
    }
    refresh();
    for (int doc = 0; doc < count; doc++) {
      String id = "sparse-parent-" + doc;
      assertEquals(outcome.equals("success") ? "COMPLETED" : outcome.equals("terminal") ? "FAILED" : "PENDING",
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.EMBEDDING_STATUS));
      assertEquals(outcome.equals("success") ? "0" : outcome.equals("terminal")
          ? Integer.toString(SchemaFields.EMBEDDING_MAX_RETRIES) : "1",
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS));
      assertTrue(hasSparseHit("term63", id));
    }
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
