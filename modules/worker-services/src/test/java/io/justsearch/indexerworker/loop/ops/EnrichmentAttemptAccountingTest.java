/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.justsearch.indexerworker.loop.ops.EnrichmentAccounting.Disposition.*;
import static io.justsearch.indexerworker.loop.ops.EnrichmentAccounting.Stage.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EnrichmentAttemptAccountingTest {
  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
  private final CommitOps commits = mock(CommitOps.class);
  private final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
  private final SpladeEncoder splade = mock(SpladeEncoder.class);
  private final NerService ner = mock(NerService.class);
  private final Map<String, Map<String, String>> documents = new LinkedHashMap<>();
  private final AtomicBoolean stop = new AtomicBoolean();

  @BeforeEach
  void setup() throws Exception {
    BackfillSnapshotTestSupport.installSelection(fields);
    when(embedding.isAvailable()).thenReturn(true);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        documents.entrySet().stream().filter(entry -> call.getArgument(1).equals(entry.getValue().get(call.getArgument(0))))
            .map(Map.Entry::getKey).toList());
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        fields.queryDocIdsByField(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    lenient().when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call ->
        fields.getEnrichmentInputs(call.getArgument(0)));
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      Map<String, DocumentFieldOps.EnrichmentInput> result = new LinkedHashMap<>();
      for (String id : (List<String>) call.getArgument(0)) {
        result.put(id, new DocumentFieldOps.EnrichmentInput("body " + id, documents.get(id), null, Map.of()));
      }
      return result;
    });
    // Keep the aggregate seam usable on the baseline compatibility checkout too.
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call ->
        coordinator.publishEnrichmentBatch(call.getArgument(0), call.getArgument(1)).result());
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      return new EnrichmentPublicationReceipt(updates.stream().map(Map.Entry::getKey).toList(), List.of(), null);
    });
  }

  @Test
  void cancelledFallbackPartitionsEverySelectedStageOnce() throws Exception {
    for (int i = 0; i < 12; i++) documents.put("doc-" + i, Map.of(
        SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "PENDING",
        SchemaFields.NER_STATUS, "PENDING"));
    when(ner.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenReturn(null);
    AtomicInteger individuals = new AtomicInteger();
    when(embedding.embedDocument(anyString(), any())).thenAnswer(call -> {
      if (individuals.incrementAndGet() == 2) {
        stop.set(true);
        throw new CancellationException("cancelled individual fallback");
      }
      return new float[] {1, 0};
    });
    var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context()));
    var summary = outcome.accounting();
    assertEquals(36, summary.tickets().size());
    assertEquals(1, summary.count(EMBED, PUBLISHED));
    assertEquals(11, summary.count(EMBED, CANCELLED));
    assertEquals(12, summary.count(SPLADE, CANCELLED));
    assertEquals(12, summary.count(NER, CANCELLED));
    assertEquals(0, summary.count(EMBED, FAILED_CHARGED));
    assertEquals(1, outcome.embedProcessed());
    assertTrue(outcome.aborted());
    assertEquals(2, individuals.get());
    verifyNoInteractions(commits);
  }

  @Test
  void untouchedSparseWorkIsNeitherComputedNorPublishedAgain() throws Exception {
    documents.put("parent", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.SPLADE_STATUS, "COMPLETED"));
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0}));
    when(splade.encodeBatch(anyList())).thenReturn(List.of(Map.of("term", 1f)));
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(context());
    assertEquals(1, outcome.embedProcessed(), "published parent vectors include successful parents");
    assertEquals(0, outcome.spladeProcessed(), "there is no new sparse output in this cycle");
    assertEquals(1, outcome.accounting().tickets().size());
    assertEquals(1, outcome.accounting().count(EMBED, PUBLISHED));
    assertEquals(0, outcome.accounting().count(SPLADE, PUBLISHED));
    assertFalse(outcome.accounting().counters().containsKey("splade.computed"));
    assertFalse(outcome.accounting().counters().containsKey("splade.accepted"));
    verify(splade, never()).encodeBatch(anyList());
    verifyNoInteractions(commits);
  }

  @Test
  void partialWindowsAdvanceWithoutCompletingAnEmbedding() throws Exception {
    documents.put("long", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    when(embedding.documentWindowCount(anyString())).thenReturn(64);
    when(embedding.embedDocumentWindows(anyString(), eq(0), anyInt())).thenAnswer(call -> {
      stop.set(true);
      return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, new float[] {1, 0}), 0, 64);
    });
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(context());
    assertEquals(1L, outcome.accounting().counters().get("embed.window_advances"));
    assertEquals(1, outcome.accounting().count(EMBED, CANCELLED));
    assertEquals(0, outcome.embedProcessed());
    assertEquals(0, outcome.accounting().count(EMBED, PUBLISHED));
    assertFalse(outcome.accounting().counters().containsKey("embed.computed"));
    verify(coordinator, never()).publishEnrichmentBatch(anyList(), anyMap());
    verifyNoInteractions(commits);
  }

  @Test
  void receiptSeparatesComputedAcceptedEmptyFailedAndStale() throws Exception {
    documents.put("empty", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
    documents.put("stale", Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
    documents.put("blank", Map.of(SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.SPLADE_RETRY_COUNT, "2"));
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> Map.of(
        "empty", new DocumentFieldOps.EnrichmentInput("empty body", documents.get("empty"), null, Map.of()),
        "stale", new DocumentFieldOps.EnrichmentInput("stale body", documents.get("stale"), null, Map.of()),
        "blank", new DocumentFieldOps.EnrichmentInput("", documents.get("blank"), null, Map.of())));
    when(splade.encodeBatch(anyList())).thenReturn(List.of(Map.of(), Map.of("term", 1f)));
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenReturn(
        new EnrichmentPublicationReceipt(List.of("empty", "blank"), List.of("stale"), null));
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(context());
    assertEquals(3, outcome.accounting().tickets().size());
    assertEquals(1, outcome.accounting().count(SPLADE, COMPLETED_EMPTY));
    assertEquals(1, outcome.accounting().count(SPLADE, FAILED_CHARGED));
    assertEquals(1, outcome.accounting().count(SPLADE, REJECTED_STALE));
    assertEquals(2L, outcome.accounting().counters().get("splade.computed"));
    assertEquals(2L, outcome.accounting().counters().get("splade.accepted"));
    assertEquals(1L, outcome.accounting().counters().get("splade.failed"));
    assertEquals(0, outcome.spladeProcessed(),
        "the existing count keeps its complete-batch rule; the receipt feeds only the accounting");
  }

  @Test
  void publicationFailureStillReportsTheAcceptedPrefix() throws Exception {
    for (String id : List.of("accepted", "broken", "tail")) {
      documents.put(id, Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    }
    when(embedding.embedDocumentBatch(anyList())).thenReturn(Collections.nCopies(3, new float[] {1, 0}));
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenReturn(
        new EnrichmentPublicationReceipt(List.of("accepted"), List.of(), new IllegalStateException("mutation failed")));
    var context = context();
    var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context));
    assertEquals(1, outcome.accounting().count(EMBED, PUBLISHED));
    assertEquals(2, outcome.accounting().count(EMBED, DEFERRED));
    assertEquals(3L, outcome.accounting().counters().get("embed.computed"));
    assertEquals(java.util.Set.of(), context.publishedDocIds(),
        "the cycle's exclusion set keeps its members; the receipt feeds only the accounting");
    assertFalse(outcome.progressed(), "publication failure retains the existing loop stop signal");
    verifyNoInteractions(commits);
  }

  @Test
  void individualNerReportsAcceptedEmptyAndRejectedComputedOutput() throws Exception {
    when(ner.isAvailable()).thenReturn(true);
    documents.put("accepted", Map.of(SchemaFields.NER_STATUS, "PENDING"));
    documents.put("stale", Map.of(SchemaFields.NER_STATUS, "PENDING"));
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(io.justsearch.indexerworker.ner.NerResult.EMPTY));
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenReturn(
        new EnrichmentPublicationReceipt(List.of("accepted"), List.of("stale"), null));
    var outcome = assertDoesNotThrow(() -> NerBackfillOps.processNerBackfill(
        new NerBackfillOps.BackfillContext(fields, coordinator, commits, IndexingPacing.unthrottled(),
            () -> ner, () -> true, 100, LoggerFactory.getLogger(getClass()))));
    assertEquals(2, outcome.accounting().tickets().size());
    assertEquals(1, outcome.accounting().count(NER, COMPLETED_EMPTY));
    assertEquals(1, outcome.accounting().count(NER, REJECTED_STALE));
    assertEquals(2L, outcome.accounting().counters().get("ner.computed"));
    assertEquals(1L, outcome.accounting().counters().get("ner.accepted"));
    assertEquals(1, outcome.docsProcessed());
    verify(commits).commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_NER);
  }

  @Test
  void selectionCancellationIsTimedWithoutChangingTheRoutingSignal() throws Exception {
    documents.put("selected", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    var context = new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, commits,
        IndexingPacing.unthrottled(), () -> embedding, () -> splade, () -> ner, () -> false,
        () -> true, 100, LoggerFactory.getLogger(getClass()), false, false, false, 50,
        new ArrayDeque<>(), new ArrayDeque<>(), new int[1], stop::get, stop::get,
        () -> false, new WindowedEmbedProgress());
    var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context));
    assertFalse(outcome.recordTiming(), "the scheduler retains its pre-existing routing signal");
    assertTrue(outcome.attempted(), "selection and content collection were attempted");
    assertEquals(1, outcome.accounting().count(EMBED, CANCELLED));
    verify(embedding, never()).embedDocumentBatch(anyList());
    verify(coordinator, never()).publishEnrichmentBatch(anyList(), anyMap());
    verifyNoInteractions(commits);
  }

  @Test
  void unusedFailingSparseEncoderDoesNotCreateAnAttemptOrWithholdEmbedding() throws Exception {
    documents.put("parent", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING",
        SchemaFields.SPLADE_STATUS, "COMPLETED"));
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0}));
    when(splade.encodeBatch(anyList())).thenThrow(new CancellationException("preservation cancelled"));
    var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context()));
    assertFalse(outcome.accounting().counters().containsKey("splade.attempts"));
    assertEquals(0, outcome.spladeProcessed());
    assertFalse(outcome.accounting().counters().containsKey("splade.computed"));
    assertEquals(1, outcome.accounting().count(EMBED, PUBLISHED));
    verify(coordinator).publishEnrichmentBatch(anyList(), anyMap());
    verify(splade, never()).encodeBatch(anyList());
    verifyNoInteractions(commits);
  }

  @Test
  void computedOutputsSurviveProviderRetirementInBatchAndIndividualFallback() throws Exception {
    documents.put("first", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    documents.put("tail", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    var available = new AtomicBoolean(true);
    when(embedding.isAvailable()).thenAnswer(call -> available.get());
    for (boolean fallback : List.of(false, true)) {
      available.set(true);
      doAnswer(call -> {
        if (fallback) return null;
        available.set(false);
        return List.of(new float[] {1, 0}, new float[] {0, 1});
      }).when(embedding).embedDocumentBatch(anyList());
      doAnswer(call -> {
        available.set(false);
        return new float[] {1, 0};
      }).when(embedding).embedDocument(anyString(), any());
      var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context()));
      assertEquals(fallback ? 1L : 2L, outcome.accounting().counters().get("embed.computed"));
      assertEquals(2, outcome.accounting().count(EMBED, DEFERRED));
      assertEquals(0, outcome.accounting().count(EMBED, FAILED_CHARGED));
      assertEquals(0, outcome.embedProcessed());
      assertFalse(outcome.accounting().counters().containsKey("embed.accepted"));
    }
    verify(embedding, times(2)).embedDocumentBatch(List.of("body first", "body tail"));
    verify(embedding).embedDocument(eq("body first"), any());
    verify(coordinator, never()).publishEnrichmentBatch(anyList(), anyMap());
    verifyNoInteractions(commits);
  }

  @Test
  void individualSpladeRetainsComputedPrefixWhenALaterSliceIsCancelled() throws Exception {
    for (int i = 0; i < 65; i++) documents.put("doc-" + i, Map.of(SchemaFields.SPLADE_STATUS, "PENDING"));
    when(splade.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      if (texts.size() == 1) throw new CancellationException("later slice cancelled");
      return Collections.nCopies(texts.size(), Map.of("term", 1f));
    });
    var outcome = assertDoesNotThrow(() -> SpladeBackfillOps.processSpladeBackfill(
        new SpladeBackfillOps.BackfillContext(fields, coordinator, commits, IndexingPacing.unthrottled(),
            () -> splade, () -> true, 100, true, false, LoggerFactory.getLogger(getClass()))));
    assertEquals(64L, outcome.accounting().counters().get("splade.computed"));
    assertEquals(64, outcome.accounting().count(SPLADE, PUBLISHED));
    assertEquals(1, outcome.accounting().count(SPLADE, CANCELLED));
    assertEquals(0, outcome.accounting().count(SPLADE, FAILED_CHARGED));
    assertEquals(64, outcome.docsProcessed());
    assertEquals(64L, outcome.accounting().counters().get("splade.accepted"));
    assertTrue(outcome.success(), "cancellation must not set systemic backoff");
    assertDoesNotThrow(() -> verify(coordinator).publishEnrichmentBatch(anyList(), anyMap()));
    assertDoesNotThrow(() -> verify(commits).commitAndTrack(
        io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_SPLADE));
  }

  @Test
  void parentPublicationCreditIncludesLateAndSinglePassParentsAlongsideChunks() throws Exception {
    documents.put("late", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    documents.put("single", Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING"));
    documents.put("chunk", Map.of(SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, "late",
        SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"));
    when(embedding.embedWithSpans(eq("body late"), any(int[][].class))).thenReturn(
        new io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding(new float[] {1, 0}, List.of(), 1));
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      return texts.stream().map(text -> "body single".equals(text)
          ? new float[] {0, 1} : new float[] {1, 1}).toList();
    });
    Map<String, Map<String, Object>> applied = new LinkedHashMap<>();
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> applied.put(entry.getKey(), entry.getValue()));
      return new EnrichmentPublicationReceipt(updates.stream().map(Map.Entry::getKey).toList(), List.of(), null);
    });
    var context = new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, commits,
        IndexingPacing.unthrottled(), () -> embedding, () -> null, () -> null, () -> true,
        () -> true, 100, LoggerFactory.getLogger(getClass()), true, false, true, 50,
        new ArrayDeque<>(), new ArrayDeque<>(), new int[1], () -> false, () -> false,
        () -> false, new WindowedEmbedProgress());
    var outcome = assertDoesNotThrow(() -> CombinedEnrichmentBackfillOps.processCombinedBackfill(context));
    assertEquals(3, outcome.embedProcessed(), "both parent routes and the chunk retain publication credit");
    assertEquals(2, applied.values().stream().filter(update -> update.containsKey(SchemaFields.VECTOR)).count());
    assertEquals(1, applied.values().stream().filter(update -> update.containsKey(SchemaFields.CHUNK_VECTOR)).count());
    assertArrayEquals(new float[] {1, 0}, (float[]) applied.get("late").get(SchemaFields.VECTOR));
    assertArrayEquals(new float[] {0, 1}, (float[]) applied.get("single").get(SchemaFields.VECTOR));
    assertArrayEquals(new float[] {1, 1}, (float[]) applied.get("chunk").get(SchemaFields.CHUNK_VECTOR));
    var order = inOrder(embedding);
    order.verify(embedding).embedDocumentBatch(List.of("body chunk"));
    order.verify(embedding).embedWithSpans(eq("body late"), argThat(spans -> spans.length == 0));
    order.verify(embedding).embedDocumentBatch(List.of("body single"));
    verifyNoInteractions(commits);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context() {
    return new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, commits,
        IndexingPacing.unthrottled(), () -> embedding, () -> splade, () -> ner, () -> true,
        () -> true, 100, LoggerFactory.getLogger(EnrichmentAttemptAccountingTest.class),
        false, false, false, 50, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0},
        stop::get, stop::get, () -> false, new WindowedEmbedProgress());
  }
}
