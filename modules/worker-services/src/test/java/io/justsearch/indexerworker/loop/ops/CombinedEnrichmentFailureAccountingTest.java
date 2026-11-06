/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ort.SessionTemporarilyUnavailableException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

class CombinedEnrichmentFailureAccountingTest {
  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
  private final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
  private final SpladeEncoder splade = mock(SpladeEncoder.class);
  private final NerService ner = mock(NerService.class);
  private final Map<String, Map<String, Object>> index = new LinkedHashMap<>();
  private final AtomicBoolean embeddingReady = new AtomicBoolean(true);
  private final AtomicBoolean spladeReady = new AtomicBoolean(true);
  private final AtomicBoolean stop = new AtomicBoolean();
  private boolean chunkVectors;
  private boolean chunkSplade;
  private boolean useEmbedding = true;
  private boolean useSplade = true;
  private final Logger log = (Logger) LoggerFactory.getLogger(CombinedEnrichmentFailureAccountingTest.class);

  @BeforeEach
  void wire() throws Exception {
    BackfillSnapshotTestSupport.installReceipts(coordinator);
    BackfillSnapshotTestSupport.installSelection(fields);
    when(embedding.isAvailable()).thenAnswer(call -> embeddingReady.get());
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call ->
        Collections.nCopies(((List<?>) call.getArgument(0)).size(), new float[] {1, 0}));
    when(splade.encode(anyString(), any())).thenThrow(new IllegalArgumentException("bad sparse input"));
    when(splade.encodeBatch(anyList())).thenAnswer(call ->
        Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("token", 1f)));
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        matching(call.getArgument(0), call.getArgument(1), false));
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        matching(call.getArgument(0), call.getArgument(1), true));
    lenient().when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call ->
        fields.getEnrichmentInputs(call.getArgument(0)));
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      Map<String, DocumentFieldOps.EnrichmentInput> inputs = new HashMap<>();
      for (String id : (List<String>) call.getArgument(0)) {
        Map<String, String> values = new HashMap<>();
        index.get(id).forEach((key, value) -> values.put(key, value.toString()));
        inputs.put(id, new DocumentFieldOps.EnrichmentInput(id, values, null, Map.of()));
      }
      return inputs;
    });
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> index.get(entry.getKey()).putAll(entry.getValue()));
      return new BatchUpdateResult(updates.size(), 0);
    });
  }

  private List<String> matching(String field, String value, boolean excludeChunks) {
    return index.entrySet().stream().filter(entry -> value.equals(entry.getValue().get(field)))
        .filter(entry -> !excludeChunks || !"true".equals(entry.getValue().get(SchemaFields.IS_CHUNK)))
        .map(Map.Entry::getKey).toList();
  }

  private void seed(String id, String field) {
    index.put(id, new HashMap<>(Map.of(field, "PENDING")));
  }

  private LaneBackfillTestSupport.BackfillContext context() {
    return new LaneBackfillTestSupport.BackfillContext(fields,
        coordinator,
        IndexingPacing.unthrottled(),
        () -> useEmbedding ? embedding : null,
        () -> useSplade && spladeReady.get() ? splade : null,
        () -> ner,
        () -> true,
        () -> true,
        100,
        log,
        chunkVectors,
        chunkSplade,
        false,
        50,
        stop::get,
        () -> false,
        new WindowedEmbedProgress());
  }

  private LaneBackfillTestSupport.BackfillContext sparseContext() { return sparseContext(2); }

  private LaneBackfillTestSupport.BackfillContext sparseContext(int cap) {
    return new LaneBackfillTestSupport.BackfillContext(fields,
        coordinator,
        IndexingPacing.unthrottled(),
        () -> null,
        () -> splade,
        () -> null,
        () -> true,
        () -> true,
        cap,
        log,
        false,
        chunkSplade,
        false,
        0,
        stop::get,
        () -> false,
        new WindowedEmbedProgress());
  }

  @Test
  void completedSparseIsNotEncodedOrCountedAsNewWork() throws Exception {
    for (int i = 0; i < 10; i++) {
      seed("doc-" + i, SchemaFields.EMBEDDING_STATUS);
      index.get("doc-" + i).put(SchemaFields.SPLADE_STATUS, "COMPLETED");
    }
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      stop.set(true);
      return Collections.nCopies(((List<?>) call.getArgument(0)).size(), new float[] {1, 0});
    });
    var outcome = LaneBackfillTestSupport.runLanes(context());

    assertEquals(8, outcome.embedProcessed());
    assertEquals(0, outcome.spladeProcessed());
    verify(splade, never()).encodeBatch(anyList());
    for (int i = 0; i < 10; i++) {
      assertFalse(index.get("doc-" + i).containsKey(SchemaFields.SPLADE));
      assertEquals(i < 8 ? "COMPLETED" : "PENDING", index.get("doc-" + i).get(SchemaFields.EMBEDDING_STATUS));
    }
  }

  @Test
  void failedSpladeSliceDoesNotChargeLaterUnattemptedDocuments() throws Exception {
    for (String id : List.of("a", "b", "c", "d", "e", "f")) seed(id, SchemaFields.SPLADE_STATUS);
    when(splade.encodeBatch(List.of("c", "d"))).thenThrow(new IllegalArgumentException("bad input"));
    LaneBackfillTestSupport.runLanes(sparseContext());
    LaneBackfillTestSupport.runLanes(sparseContext());
    assertEquals("COMPLETED", index.get("a").get(SchemaFields.SPLADE_STATUS));
    assertEquals("COMPLETED", index.get("b").get(SchemaFields.SPLADE_STATUS));
    for (String id : List.of("c", "d")) assertEquals("1", index.get(id).get(SchemaFields.SPLADE_RETRY_COUNT));
    for (String id : List.of("e", "f")) assertFalse(index.get(id).containsKey(SchemaFields.SPLADE_RETRY_COUNT));
    verify(splade, never()).encodeBatch(List.of("e", "f"));
  }

  @Test
  void spladeSliceFailurePreservesItsSuccessfulPrefixAndDoesNotChargeTheTail() throws Exception {
    for (String id : List.of("a", "b", "later")) seed(id, SchemaFields.SPLADE_STATUS);
    when(splade.encodeBatch(anyList())).thenReturn(new AbstractList<Map<String, Float>>() {
      @Override public int size() { return 2; }
      @Override public Map<String, Float> get(int position) {
        if (position == 0) return Map.of("good", 1f);
        throw new IllegalArgumentException("failed materializing second result");
      }
    });
    LaneBackfillTestSupport.runLanes(sparseContext());
    assertEquals("COMPLETED", index.get("a").get(SchemaFields.SPLADE_STATUS));
    assertEquals("0", index.get("a").get(SchemaFields.SPLADE_RETRY_COUNT));
    assertEquals("1", index.get("b").get(SchemaFields.SPLADE_RETRY_COUNT));
    assertFalse(index.get("later").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void spladeMaterializationFailureAtEitherEndPreservesOnlyItsPrefix(int failedPosition) throws Exception {
    for (String id : List.of("a", "b", "c", "later")) seed(id, SchemaFields.SPLADE_STATUS);
    when(splade.encodeBatch(anyList())).thenReturn(new AbstractList<Map<String, Float>>() {
      @Override public int size() { return 3; }
      @Override public Map<String, Float> get(int position) {
        if (position >= failedPosition) throw new IllegalArgumentException("failed materializing result");
        return Map.of("good", 1f);
      }
    });
    var outcome = LaneBackfillTestSupport.runLanes(sparseContext(3));
    assertEquals(failedPosition, outcome.spladeProcessed());
    for (int i = 0; i < 3; i++) {
      var row = index.get(List.of("a", "b", "c").get(i));
      assertEquals(i < failedPosition ? "COMPLETED" : "PENDING", row.get(SchemaFields.SPLADE_STATUS));
      assertEquals(i < failedPosition ? "0" : "1", row.get(SchemaFields.SPLADE_RETRY_COUNT));
      if (i < failedPosition) assertEquals(Map.of("good", 1f), row.get(SchemaFields.SPLADE));
      else assertFalse(row.containsKey(SchemaFields.SPLADE));
    }
    assertEquals("PENDING", index.get("later").get(SchemaFields.SPLADE_STATUS));
    assertFalse(index.get("later").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
    verify(splade, never()).encode(eq("later"), any());
    verify(splade, times(1)).encodeBatch(anyList());
  }

  @ParameterizedTest
  @CsvSource({"false, 0", "false, 1", "false, 2", "true, 0", "true, 1", "true, 2"})
  void denseMaterializationFailurePreservesItsPrefixAndDefersUnreadRows(boolean chunks, int failedPosition) {
    useSplade = false;
    chunkVectors = chunks;
    String status = chunks ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS;
    String retry = chunks ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT;
    String vector = chunks ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR;
    for (String id : List.of("a", "b", "c")) {
      seed(id, status);
      if (chunks) index.get(id).put(SchemaFields.IS_CHUNK, "true");
    }
    when(embedding.embedDocumentBatch(anyList())).thenReturn(new AbstractList<float[]>() {
      @Override public int size() { return 3; }
      @Override public float[] get(int position) {
        if (position >= failedPosition) throw new IllegalArgumentException("failed materializing result");
        return new float[] {1, 0};
      }
    });
    var lane = context();
    var outcome = CombinedEnrichmentBackfillOps.processDenseUnit(
        LaneBackfillTestSupport.denseContext(lane), new EnrichmentSelection(),
        chunks ? EnrichmentAccounting.Stage.CHUNK_EMBED : EnrichmentAccounting.Stage.EMBED);
    assertEquals(failedPosition, outcome.docsProcessed());
    for (int i = 0; i < 3; i++) {
      var row = index.get(List.of("a", "b", "c").get(i));
      assertEquals(i < failedPosition ? "COMPLETED" : "PENDING", row.get(status));
      if (i < failedPosition) {
        assertEquals("0", row.get(retry));
        assertArrayEquals(new float[] {1, 0}, (float[]) row.get(vector));
      } else {
        assertFalse(row.containsKey(retry));
        assertFalse(row.containsKey(vector));
        String id = List.of("a", "b", "c").get(i);
        assertEquals(WindowedEmbedProgress.Route.FALLBACK, lane.windowedEmbedProgress().route(id, id));
      }
    }
    verify(embedding, never()).embedDocument(anyString(), any());
    verify(embedding, times(1)).embedDocumentBatch(anyList());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void nerMaterializationFailureKeepsEarlierDocumentsAndDoesNotChargeTheTail(int failedPosition) throws Exception {
    when(ner.isAvailable()).thenReturn(true);
    for (String id : List.of("a", "b", "c", "later")) seed(id, SchemaFields.NER_STATUS);
    String failedId = List.of("a", "b", "c").get(failedPosition);
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      assertEquals(1, texts.size());
      return new AbstractList<NerResult>() {
        @Override public int size() { return 1; }
        @Override public NerResult get(int position) {
          if (texts.getFirst().equals(failedId)) throw new IllegalArgumentException("failed materializing result");
          return new NerResult(List.of("Ada"), List.of(), List.of());
        }
      };
    });
    var nerContext = new NerBackfillOps.BackfillContext(fields, coordinator, IndexingPacing.unthrottled(),
        () -> ner, () -> true, 3, log, () -> true);
    LaneBackfillTestSupport.runNer(nerContext);
    for (String id : List.of("a", "b", "c")) {
      var row = index.get(id);
      assertEquals(id.equals(failedId) ? "PENDING" : "COMPLETED", row.get(SchemaFields.NER_STATUS));
      assertEquals(id.equals(failedId) ? "1" : "0", row.get(SchemaFields.NER_RETRY_COUNT));
      if (!id.equals(failedId)) assertEquals(List.of("Ada"), row.get(SchemaFields.ENTITY_PERSONS_RAW));
    }
    assertEquals("PENDING", index.get("later").get(SchemaFields.NER_STATUS));
    assertFalse(index.get("later").containsKey(SchemaFields.NER_RETRY_COUNT));
    verify(ner, never()).extractEntitiesBatch(List.of("later"));
  }

  @Test
  void pendingSpladeChecksForYieldBetweenSlices() throws Exception {
    for (String id : List.of("a", "b", "later")) seed(id, SchemaFields.SPLADE_STATUS);
    when(splade.encodeBatch(anyList())).thenAnswer(call -> {
      stop.set(true);
      return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("good", 1f));
    });
    var outcome = LaneBackfillTestSupport.runLanes(sparseContext());
    assertTrue(outcome.aborted());
    assertEquals(2, outcome.spladeProcessed());
    assertEquals("PENDING", index.get("later").get(SchemaFields.SPLADE_STATUS));
    assertFalse(index.get("later").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
    verify(splade, times(1)).encodeBatch(anyList());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 3})
  void malformedEmbeddingSliceFallsBackAndOnlyBadDocumentEscalates(int resultSize) {
    useSplade = false;
    seed("healthy", SchemaFields.EMBEDDING_STATUS);
    seed("bad", SchemaFields.EMBEDDING_STATUS);
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call ->
        resultSize < 0 ? null : Collections.nCopies(
            resultSize == ((List<?>) call.getArgument(0)).size() ? resultSize + 1 : resultSize,
            new float[] {9, 9}));
    when(embedding.embedDocument(eq("healthy"), any())).thenReturn(new float[] {1, 0});
    when(embedding.embedDocument(eq("bad"), any())).thenReturn(null);
    for (int cycle = 1; cycle <= SchemaFields.EMBEDDING_MAX_RETRIES; cycle++) {
      LaneBackfillTestSupport.runLanes(context());
      assertEquals("COMPLETED", index.get("healthy").get(SchemaFields.EMBEDDING_STATUS));
      assertEquals("0", index.get("healthy").get(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(String.valueOf(cycle), index.get("bad").get(SchemaFields.EMBEDDING_RETRY_COUNT));
    }
    assertEquals("FAILED", index.get("bad").get(SchemaFields.EMBEDDING_STATUS));
    verify(embedding).embedDocument(eq("healthy"), any());
    verify(embedding, times(SchemaFields.EMBEDDING_MAX_RETRIES)).embedDocument(eq("bad"), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, 3})
  void malformedSpladeSliceNeverUsesMisalignedResults(int resultSize) throws Exception {
    for (String id : List.of("a", "b", "later")) seed(id, SchemaFields.SPLADE_STATUS);
    when(splade.encodeBatch(anyList())).thenReturn(resultSize < 0 ? null :
        Collections.nCopies(resultSize, Map.of("wrong", 5f)));
    LaneBackfillTestSupport.runLanes(sparseContext());
    for (String id : List.of("a", "b")) {
      assertEquals("PENDING", index.get(id).get(SchemaFields.SPLADE_STATUS));
      assertEquals("1", index.get(id).get(SchemaFields.SPLADE_RETRY_COUNT));
      assertFalse(index.get(id).containsKey(SchemaFields.SPLADE));
    }
    assertFalse(index.get("later").containsKey(SchemaFields.SPLADE_RETRY_COUNT));
  }

  @Test
  void sparseChunkSelectionCannotEnrollDisabledDenseStage() {
    seed("chunk", SchemaFields.SPLADE_STATUS);
    index.get("chunk").put(SchemaFields.IS_CHUNK, "true");
    index.get("chunk").put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
    chunkSplade = true;
    LaneBackfillTestSupport.runLanes(context());
    verify(embedding, never()).embedDocumentBatch(anyList());
    assertFalse(index.get("chunk").containsKey(SchemaFields.CHUNK_VECTOR));
    assertEquals("PENDING", index.get("chunk").get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals("COMPLETED", index.get("chunk").get(SchemaFields.SPLADE_STATUS));
    assertFalse(index.get("chunk").containsKey(SchemaFields.NER_STATUS));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completionCreditRequiresSuccessfulPublication(boolean throwingWrite) {
    seed("doc", SchemaFields.EMBEDDING_STATUS);
    index.get("doc").put(SchemaFields.SPLADE_STATUS, "PENDING");
    if (throwingWrite) when(coordinator.updateDocumentsBatchIf(anyList(), anyMap()))
        .thenThrow(new IllegalStateException("write failed"));
    else when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenReturn(new BatchUpdateResult(0, 1));
    var outcome = LaneBackfillTestSupport.runLanes(context());
    assertEquals(0, outcome.embedProcessed());
    assertEquals(0, outcome.spladeProcessed());

  }

  @Test
  void interruptedRejectedBatchReportsPublishedCount() {
    useSplade = false;
    for (int i = 0; i < 10; i++) seed("doc-" + i, SchemaFields.EMBEDDING_STATUS);
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      stop.set(true);
      return Collections.nCopies(((List<?>) call.getArgument(0)).size(), new float[] {1, 0});
    });
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenReturn(new BatchUpdateResult(0, 8));
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    log.addAppender(appender);
    try {
      var outcome = LaneBackfillTestSupport.runLanes(context());
      assertTrue(outcome.aborted());
      assertEquals(0, outcome.embedProcessed());
      assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("Published 0 document updates")));
      assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("Everything already enriched was written")));
    } finally {
      log.detachAppender(appender);
      appender.stop();
    }
  }


  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void providerDisappearingDuringInferenceDoesNotConsumeDocumentRetries(boolean sparse) throws Exception {
    seed("doc", sparse ? SchemaFields.SPLADE_STATUS : SchemaFields.EMBEDDING_STATUS);
    if (sparse) when(splade.encodeBatch(anyList())).thenAnswer(call -> {
      spladeReady.set(false);
      throw new SessionTemporarilyUnavailableException("retired during selection");
    });
    else when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      embeddingReady.set(false);
      return Collections.singletonList(null);
    });
    LaneBackfillTestSupport.runLanes(context());
    assertFalse(index.get("doc").containsKey(sparse ? SchemaFields.SPLADE_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals("PENDING", index.get("doc").get(sparse ? SchemaFields.SPLADE_STATUS : SchemaFields.EMBEDDING_STATUS));
  }
}
