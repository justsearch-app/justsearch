package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for the batch-embedding trusted-length bug: a null-only guard on the
 * embedding provider's batch result let a short/empty (but non-null) result reach the
 * index-aligned update loop and throw ArrayIndexOutOfBoundsException before any doc/chunk was
 * marked failed — crash-looping the whole backfill cycle forever since the scheduler just
 * refetched the same still-PENDING batch (live-reproduced on a legal-text corpus).
 */
@DisplayName("EmbeddingBackfillOps")
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock EmbeddingProvider embeddingProvider;

  @org.junit.jupiter.api.BeforeEach
  void availableProvider() {
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
  }

  private EmbeddingBackfillOps.BackfillContext context() {
    return new EmbeddingBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        signalBus,
        IndexingPacing.unthrottled(),
        () -> embeddingProvider,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(EmbeddingBackfillOpsTest.class));
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> fallbackOutages() {
    return java.util.stream.Stream.of(false, true).flatMap(chunk ->
        java.util.stream.Stream.of("retired", "replaced", "writesBlocked", "sessionUnavailable")
            .map(outage -> org.junit.jupiter.params.provider.Arguments.of(chunk, outage)));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.MethodSource("fallbackOutages")
  void fallbackOutageDoesNotChargeDocumentsAndRecovers(boolean chunk, String outage) throws Exception {
    BackfillSnapshotTestSupport.install(documentFieldOps, indexingCoordinator, !chunk, java.util.Set.of());
    String status = chunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS;
    when(documentFieldOps.queryDocIdsByField(eq(status), eq("PENDING"), anyInt()))
        .thenReturn(List.of("first", "later"));
    if (chunk) {
      when(documentFieldOps.getDocumentContentBatch(anyList()))
          .thenReturn(Map.of("first", "first content", "later", "later content"));
    } else {
      when(documentFieldOps.getDocumentContent("first")).thenReturn("first content");
      when(documentFieldOps.getDocumentContent("later")).thenReturn("later content");
    }
    var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
    var writesAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var selected = new java.util.concurrent.atomic.AtomicReference<>(embeddingProvider);
    var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(embeddingProvider.isAvailable()).thenAnswer(call -> ready.get());
    when(embeddingProvider.embedDocumentBatch(anyList())).thenReturn(List.of());
    when(embeddingProvider.embedDocument(anyString(), any(InferenceRequest.class))).thenAnswer(call -> {
      if (!failing.get()) return new float[] {1f, 2f};
      switch (outage) {
        case "retired" -> ready.set(false);
        case "replaced" -> selected.set(mock(EmbeddingProvider.class));
        case "writesBlocked" -> writesAllowed.set(false);
        case "sessionUnavailable" -> throw new io.justsearch.ort.SessionTemporarilyUnavailableException("retired session");
        default -> throw new AssertionError(outage);
      }
      return null;
    });
    var ctx = new EmbeddingBackfillOps.BackfillContext(documentFieldOps, indexingCoordinator,
        commitOps, signalBus, IndexingPacing.unthrottled(), selected::get, () -> true,
        writesAllowed::get, 100, LoggerFactory.getLogger(EmbeddingBackfillOpsTest.class));
    if (chunk) EmbeddingBackfillOps.processChunkEmbeddingBackfill(ctx);
    else EmbeddingBackfillOps.processEmbeddingBackfill(ctx);
    assertDoesNotThrow(() -> verify(indexingCoordinator, never()).updateDocumentIf(
        anyString(), any(DocumentFieldOps.EnrichmentInput.class), anyMap()));
    verify(embeddingProvider, never()).embedDocument(eq("later content"), any(InferenceRequest.class));

    failing.set(false);
    ready.set(true);
    writesAllowed.set(true);
    selected.set(embeddingProvider);
    StageOutcome recovered = chunk ? EmbeddingBackfillOps.processChunkEmbeddingBackfill(ctx)
        : EmbeddingBackfillOps.processEmbeddingBackfill(ctx);
    assertEquals(2, recovered.docsProcessed());
    verify(indexingCoordinator).updateDocumentIf(eq("first"), any(DocumentFieldOps.EnrichmentInput.class), argThat(update ->
        "COMPLETED".equals(update.get(status))));
    verify(indexingCoordinator).updateDocumentIf(eq("later"), any(DocumentFieldOps.EnrichmentInput.class), argThat(update ->
        "COMPLETED".equals(update.get(status))));
  }

  @Nested
  @DisplayName("processChunkEmbeddingBackfill()")
  class ProcessChunkEmbeddingBackfill {
    @org.junit.jupiter.api.BeforeEach void snapshots() throws Exception {
      BackfillSnapshotTestSupport.install(documentFieldOps, indexingCoordinator, false, java.util.Set.of());
    }


    private void stubTwoPendingChunks() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.CHUNK_EMBEDDING_STATUS),
              eq(SchemaFields.EMBEDDING_STATUS_PENDING),
              anyInt()))
          .thenReturn(List.of("chunk1", "chunk2"));
      when(signalBus.isMainGpuActive()).thenReturn(false);
      when(documentFieldOps.getDocumentContentBatch(List.of("chunk1", "chunk2")))
          .thenReturn(Map.of("chunk1", "content one", "chunk2", "content two"));
    }

    @Test
    @DisplayName("falls back per-chunk without throwing when batch embedding returns an empty list")
    void fallsBackPerChunk_whenBatchReturnsEmptyList() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      StageOutcome result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result.success(), "should report work was done via the per-chunk fallback");
      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk1"),
              argThat(
                  (Map<String, Object> m) ->
                      SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          m.get(SchemaFields.CHUNK_EMBEDDING_STATUS))));
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk2"),
              argThat(
                  (Map<String, Object> m) ->
                      SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          m.get(SchemaFields.CHUNK_EMBEDDING_STATUS))));
    }

    @Test
    @DisplayName(
        "falls back per-chunk without throwing when batch embedding returns a short (n-1) list")
    void fallsBackPerChunk_whenBatchReturnsShortList() {
      stubTwoPendingChunks();
      // Two chunks requested, only one vector returned — the AIOOBE trigger for the old
      // null-only guard.
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of(new float[] {9f}));
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      StageOutcome result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result.success());
      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
    }

    @Test
    @DisplayName("still falls back cleanly when batch embedding returns null (pre-existing case)")
    void fallsBackPerChunk_whenBatchReturnsNull() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(null);
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      StageOutcome result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result.success());
      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
    }

    @Test
    @DisplayName(
        "marks a chunk for retry (not permanently FAILED) when the per-chunk fallback also fails")
    void marksRetry_whenFallbackAlsoReturnsEmptyVector() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[0]);
      lenient().when(documentFieldOps.getDocumentField("chunk2", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
          .thenReturn(null);

      StageOutcome result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result.success());
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk2"),
              argThat(
                  (Map<String, Object> m) ->
                      "1".equals(m.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
                          && !m.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)));
    }
  }

  @Nested
  @DisplayName("processEmbeddingBackfill() (doc-level sibling)")
  class ProcessEmbeddingBackfill {
    @org.junit.jupiter.api.BeforeEach void snapshots() throws Exception {
      BackfillSnapshotTestSupport.install(documentFieldOps, indexingCoordinator, true, java.util.Set.of());
    }


    private void stubTwoPendingDocs() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1", "doc2"));
      when(signalBus.isMainGpuActive()).thenReturn(false);
      when(documentFieldOps.getDocumentContent("doc1")).thenReturn("content one");
      when(documentFieldOps.getDocumentContent("doc2")).thenReturn("content two");
    }

    @Test
    void oversizedParentIsKeptWholeAndLaterParentsRemainPending() {
      String large = "x".repeat((int) EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS + 1);
      when(documentFieldOps.queryDocIdsByField(
          eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("large", "later"));
      when(documentFieldOps.getDocumentContent("large")).thenReturn(large);
      when(embeddingProvider.embedDocumentBatch(List.of(large))).thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq(large), any(InferenceRequest.class))).thenReturn(new float[] {1f});

      EmbeddingBackfillOps.processEmbeddingBackfill(context());

      verify(embeddingProvider).embedDocumentBatch(List.of(large));
      verify(documentFieldOps, never()).getDocumentContent("later");
      verify(documentFieldOps, never()).getDocumentContentBatch(anyList());
    }

    @Test
    void oversizedNextParentWaitsForItsOwnBatch() {
      String first = "x".repeat((int) EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS - 1);
      String large = "y".repeat((int) EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS * 2);
      when(documentFieldOps.queryDocIdsByField(
          eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("first", "large", "later"));
      when(documentFieldOps.getDocumentContent("first")).thenReturn(first);
      when(documentFieldOps.getDocumentContent("large")).thenReturn(large);
      when(embeddingProvider.embedDocumentBatch(List.of(first))).thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq(first), any(InferenceRequest.class))).thenReturn(new float[] {1f});

      EmbeddingBackfillOps.processEmbeddingBackfill(context());

      verify(embeddingProvider).embedDocumentBatch(List.of(first));
      verify(embeddingProvider, never()).embedDocument(eq(large), any(InferenceRequest.class));
      verify(indexingCoordinator, never()).updateDocument(eq("large"), anyMap());
      verify(documentFieldOps, never()).getDocumentContent("later");
    }

    @Test
    void aggregateTextBudgetStopsCollectionBeforeRemainingDocumentsAreRead() {
      String half = "x".repeat((int) EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS / 2);
      when(documentFieldOps.queryDocIdsByField(
          eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("first", "second", "later"));
      when(documentFieldOps.getDocumentContent("first")).thenReturn(half);
      when(documentFieldOps.getDocumentContent("second")).thenReturn(half);
      when(embeddingProvider.embedDocumentBatch(List.of(half, half))).thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq(half), any(InferenceRequest.class))).thenReturn(new float[] {1f});

      EmbeddingBackfillOps.processEmbeddingBackfill(context());

      verify(embeddingProvider).embedDocumentBatch(List.of(half, half));
      verify(documentFieldOps, never()).getDocumentContent("later");
    }

    @Test
    @DisplayName("falls back per-doc without throwing when batch embedding returns an empty list")
    void fallsBackPerDoc_whenBatchReturnsEmptyList() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList())).thenReturn(List.of());
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
    }

    @Test
    @DisplayName("falls back per-doc without throwing when batch embedding returns a short list")
    void fallsBackPerDoc_whenBatchReturnsShortList() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList()))
          .thenReturn(List.of(new float[] {9f}));
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
    }

    @Test
    @DisplayName("still falls back cleanly when batch embedding returns null (pre-existing case)")
    void fallsBackPerDoc_whenBatchReturnsNull() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList())).thenReturn(null);
      when(embeddingProvider.embedDocument(eq("content one"), any(InferenceRequest.class))).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument(eq("content two"), any(InferenceRequest.class))).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument(eq("content one"), any(InferenceRequest.class));
      verify(embeddingProvider).embedDocument(eq("content two"), any(InferenceRequest.class));
    }
  }
}
