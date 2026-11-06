/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class EnrichmentMapperDispositionTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test
  void acceptedMutationWhoseVectorIsDroppedIsNotACompletedEmbedding() throws Exception {
    var catalog = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    catalog.add(new FieldDef(SchemaFields.EMBEDDING_RETRY_COUNT, "long", true, true,
        List.of("filter"), null, null, false));
    try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("mapper-disposition-test", catalog))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "parent", SchemaFields.DOC_UID, "uid-parent",
          SchemaFields.CONTENT, "body", SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.EMBEDDING_RETRY_COUNT, "0")));
      runtime.commitOps().maybeRefreshBlocking();
      var embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {Float.NaN, 1}));
      var sparse = mock(SpladeEncoder.class);
      var coordinator = spy(runtime.indexingCoordinator());
      var receipt = new AtomicReference<EnrichmentPublicationReceipt>();
      doAnswer(call -> {
        var applied = (EnrichmentPublicationReceipt) call.callRealMethod();
        receipt.set(applied);
        return applied;
      }).when(coordinator).publishEnrichmentBatch(anyList(), anyMap(), any());
      var outcome = assertDoesNotThrow(() -> LaneBackfillTestSupport.runLanes(
          new LaneBackfillTestSupport.BackfillContext(runtime.documentFieldOps(),
        coordinator,
        IndexingPacing.unthrottled(),
        () -> embedding,
        () -> sparse,
        () -> null,
        () -> true,
        () -> true,
        1,
        LoggerFactory.getLogger(getClass()),
        false,
        false,
        false,
        0,
        () -> false,
        () -> false,
        new WindowedEmbedProgress())));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_STATUS));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(1, outcome.embedProcessed(),
          "the existing completion count keeps its input; the correction is reported below");
      assertNotNull(receipt.get());
      assertEquals(Map.of("parent", Set.of(SchemaFields.EMBEDDING_STATUS)),
          receipt.get().correctedFailedStatuses());
      assertEquals(List.of("parent"), receipt.get().acceptedIds());
      assertEquals(0, outcome.accounting().count(
          EnrichmentAccounting.Stage.EMBED, EnrichmentAccounting.Disposition.PUBLISHED));
      assertEquals(1, outcome.accounting().count(
          EnrichmentAccounting.Stage.EMBED, EnrichmentAccounting.Disposition.REJECTED_OUTPUT));
      assertEquals(1L, outcome.accounting().counters().get("embed.computed"));
      assertEquals(1L, outcome.accounting().counters().get("embed.failed"));
      assertFalse(outcome.accounting().counters().containsKey("embed.accepted"));
      assertFalse(outcome.accounting().counters().containsKey("embed.completed"));
      assertEquals(0, outcome.accounting().count(
          EnrichmentAccounting.Stage.EMBED, EnrichmentAccounting.Disposition.FAILED_CHARGED));
    }
  }

  @Test
  void individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry() throws Exception {
    var catalog = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    catalog.add(new FieldDef(SchemaFields.EMBEDDING_RETRY_COUNT, "long", true, true,
        List.of("filter"), null, null, false));
    try (var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("mapper-fallback-test", catalog))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "parent", SchemaFields.DOC_UID, "uid-parent",
          SchemaFields.CONTENT, "body", SchemaFields.EMBEDDING_STATUS, "PENDING",
          SchemaFields.EMBEDDING_RETRY_COUNT, "0")));
      runtime.commitOps().maybeRefreshBlocking();
      var embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      when(embedding.embedDocumentBatch(anyList())).thenReturn(null);
      when(embedding.embedDocument(anyString(), any())).thenReturn(new float[] {Float.NaN, 1});
      var commits = spy(runtime.commitOps());
      var outcome = assertDoesNotThrow(() -> LaneBackfillTestSupport.runDenseParent(
          new LaneBackfillTestSupport.EmbeddingContext(runtime.documentFieldOps(),
        runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> embedding,
        () -> true,
        () -> true,
        1,
        LoggerFactory.getLogger(getClass()))));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_STATUS));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_RETRY_COUNT));
      assertTrue(outcome.success(), "preserve the existing routing signal");
      verifyNoInteractions(commits);
      assertEquals(1, outcome.docsProcessed(),
          "the existing completion count keeps its input; the correction is reported below");
      assertEquals(1, outcome.accounting().count(
          EnrichmentAccounting.Stage.EMBED, EnrichmentAccounting.Disposition.REJECTED_OUTPUT));
      assertEquals(1L, outcome.accounting().counters().get("embed.computed"));
      assertFalse(outcome.accounting().counters().containsKey("embed.accepted"));
      assertFalse(outcome.accounting().counters().containsKey("embed.completed"));
      assertEquals(0, outcome.accounting().count(
          EnrichmentAccounting.Stage.EMBED, EnrichmentAccounting.Disposition.FAILED_CHARGED));
    }
  }
}
