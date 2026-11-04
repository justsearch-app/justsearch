/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

final class EnrichmentUnitBoundaryTest {
  enum Route { DENSE_BATCH, DENSE_CHUNK, WINDOW, SINGLE_PASS, NER, SPARSE_PARENT, SPARSE_CHUNK }

  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
  private final CommitOps commits = mock(CommitOps.class);
  private final EmbeddingProvider dense = mock(EmbeddingService.class);
  private final SpladeEncoder sparse = mock(SpladeEncoder.class);
  private final NerService ner = mock(NerService.class);
  private final AtomicReference<EmbeddingProvider> provider = new AtomicReference<>(dense);
  private final AtomicInteger calls = new AtomicInteger();
  private final Map<String, String> metadata = new HashMap<>();
  private final Map<String, Map<String, Object>> writes = new HashMap<>();
  private final WindowedEmbedProgress progress = new WindowedEmbedProgress();
  private List<String> documents = List.of("doc");

  private void seed(Route route) throws Exception {
    BackfillSnapshotTestSupport.installSelection(fields);
    String status = switch (route) {
      case NER -> SchemaFields.NER_STATUS;
      case DENSE_CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
      case SPARSE_PARENT, SPARSE_CHUNK -> SchemaFields.SPLADE_STATUS;
      default -> SchemaFields.EMBEDDING_STATUS;
    };
    metadata.put(status, "PENDING");
    if (route == Route.SPARSE_CHUNK || route == Route.DENSE_CHUNK) metadata.put(SchemaFields.IS_CHUNK, "true");
    metadata.put(SchemaFields.DOC_UID, "uid-1");
    metadata.put(SchemaFields.CONTENT_SHA256, "revision-1");
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        call.getArgument(0).equals(SchemaFields.PARENT_DOC_ID)
            ? route == Route.SINGLE_PASS ? List.of("child") : List.of()
            : call.getArgument(1).equals(metadata.get(call.getArgument(0))) ? documents : List.of());
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        call.getArgument(1).equals(metadata.get(call.getArgument(0))) ? documents : List.of());
    lenient().when(fields.getDocumentFieldsBatchOrThrow(anyList(), anySet())).thenAnswer(call -> {
      Map<String, Map<String, String>> rows = new LinkedHashMap<>();
      for (String id : (List<String>)call.getArgument(0)) rows.put(id, Map.copyOf(metadata));
      return rows;
    });
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      List<String> ids = call.getArgument(0);
      Map<String, DocumentFieldOps.EnrichmentInput> result = new LinkedHashMap<>();
      for (String id : ids) result.put(id,
          new DocumentFieldOps.EnrichmentInput("content", Map.copyOf(metadata), null, Map.of()));
      return result;
    });
    when(fields.getEnrichmentInputs(anyList(), anyLong())).thenAnswer(call ->
        fields.getEnrichmentInputs(call.getArgument(0)));
    when(coordinator.publishEnrichmentBatch(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> writes.put(entry.getKey(), entry.getValue()));
      return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(
          updates.stream().map(Map.Entry::getKey).toList(), List.of(), null);
    });
    when(dense.isAvailable()).thenReturn(true);
    when(dense.dimension()).thenReturn(2);
    when(dense.documentWindowCount(anyString())).thenReturn(route == Route.WINDOW ? 96 : 1);
    when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
      calls.incrementAndGet();
      List<String> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), new float[] {1, 0});
    });
    when(dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
      calls.incrementAndGet();
      int from = call.getArgument(1);
      return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, new float[] {1, 0}), from, 96);
    });
    when(dense.embedWithSpans(anyString(), any())).thenAnswer(call -> {
      calls.incrementAndGet();
      return new EmbeddingService.ChunkedEmbedding(new float[] {1, 0}, List.of(), 1);
    });
    when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
      calls.incrementAndGet();
      List<String> texts = call.getArgument(0);
      return Collections.nCopies(texts.size(), Map.of("term", 1f));
    });
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
      calls.incrementAndGet();
      return List.of(NerResult.EMPTY);
    });
  }

  private CombinedEnrichmentBackfillOps.CombinedOutcome run(Route route,
      BooleanSupplier deadline, BooleanSupplier hardStop) {
    return CombinedEnrichmentBackfillOps.processCombinedBackfill(
        new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, commits,
            IndexingPacing.unthrottled(), provider::get,
            () -> route == Route.SPARSE_PARENT || route == Route.SPARSE_CHUNK ? sparse : null,
            () -> route == Route.NER ? ner : null, () -> true, () -> true, 100,
            LoggerFactory.getLogger(getClass()), route == Route.DENSE_CHUNK, route == Route.SPARSE_CHUNK,
            route == Route.SINGLE_PASS, 100, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0},
            deadline, hardStop, () -> false, progress));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void expiredDeadlinePreventsTheFirstUnit(Route route) throws Exception {
    seed(route);
    run(route, () -> true, () -> false);
    assertEquals(0, calls.get(), "a deadline bounds the first unit too");
    assertTrue(writes.isEmpty());
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void hardStopPreventsTheFirstUnitAndCancelsTheSelection(Route route) throws Exception {
    seed(route);
    var outcome = run(route, () -> false, () -> true);
    assertEquals(0, calls.get(), "the scheduler hard-stop supplier also bounds the first unit");
    assertTrue(writes.isEmpty());
    assertEquals(1, outcome.accounting().tickets().size());
    assertTrue(outcome.accounting().tickets().values().stream().allMatch(
        disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
  }

  private void invalidateSourceDuringFetch() throws Exception {
    var epoch = new java.util.concurrent.atomic.AtomicLong();
    when(coordinator.bulkDeleteEpoch()).thenAnswer(call -> epoch.get());
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      List<String> ids = call.getArgument(0);
      Map<String, DocumentFieldOps.EnrichmentInput> inputs = new LinkedHashMap<>();
      for (String id : ids) inputs.put(id,
          new DocumentFieldOps.EnrichmentInput("content", Map.copyOf(metadata), null, Map.of()));
      epoch.incrementAndGet();
      return inputs;
    });
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void sourceInvalidationDuringFetchPreventsTheFirstUnit(Route route) throws Exception {
    seed(route);
    invalidateSourceDuringFetch();
    var outcome = run(route, () -> false, () -> false);
    assertEquals(0, calls.get());
    assertTrue(writes.isEmpty());
    assertEquals(1, outcome.accounting().tickets().size());
    assertTrue(outcome.accounting().tickets().values().stream().allMatch(
        disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void sourceInvalidationWhileTheSchedulerPacesPreventsTheFirstUnit(Route route) throws Exception {
    seed(route);
    var epoch = new java.util.concurrent.atomic.AtomicLong();
    when(coordinator.bulkDeleteEpoch()).thenAnswer(call -> epoch.get());
    var outcome = run(route, () -> { epoch.set(1); return false; }, () -> false);
    assertEquals(0, calls.get(), "source validity must be checked after scheduler pacing");
    assertTrue(writes.isEmpty());
    assertEquals(1, outcome.accounting().tickets().size());
    assertTrue(outcome.accounting().tickets().values().stream().allMatch(
        disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"DENSE_BATCH", "DENSE_CHUNK", "NER", "SPARSE_PARENT", "SPARSE_CHUNK"})
  void individualSourceInvalidationDuringFetchPreventsTheFirstUnit(Route route) throws Exception {
    seed(route);
    invalidateSourceDuringFetch();
    var outcome = individual(route);
    assertEquals(0, calls.get());
    assertTrue(writes.isEmpty());
    assertEquals(1, outcome.accounting().tickets().size());
    assertTrue(outcome.accounting().tickets().values().stream().allMatch(
        disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void interruptionPreventsTheFirstUnitAndCancelsTheSelection(Route route) throws Exception {
    seed(route);
    try {
      Thread.currentThread().interrupt();
      var outcome = run(route, () -> false, () -> false);
      assertEquals(0, calls.get(), "interruption is checked even before the first unit");
      assertTrue(writes.isEmpty());
      assertEquals(1, outcome.accounting().tickets().size());
      assertTrue(outcome.accounting().tickets().values().stream().allMatch(
          disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(value = Route.class, names = {"DENSE_BATCH", "DENSE_CHUNK", "NER", "SPARSE_PARENT", "SPARSE_CHUNK"})
  void individualStageInterruptionPreventsTheFirstUnit(Route route) throws Exception {
    seed(route);
    try {
      Thread.currentThread().interrupt();
      var outcome = individual(route);
      assertEquals(0, calls.get());
      assertTrue(writes.isEmpty());
      assertEquals(1, outcome.accounting().tickets().size());
      assertTrue(outcome.accounting().tickets().values().stream().allMatch(
          disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
    } finally {
      Thread.interrupted();
    }
  }

  private StageOutcome individual(Route route) {
    var log = LoggerFactory.getLogger(getClass());
    return switch (route) {
      case DENSE_BATCH, DENSE_CHUNK -> {
        var context = new EmbeddingBackfillOps.BackfillContext(fields, coordinator, commits,
            mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),
            IndexingPacing.unthrottled(), provider::get, () -> true, () -> true, 100, log);
        yield route == Route.DENSE_CHUNK ? EmbeddingBackfillOps.processChunkEmbeddingBackfill(context)
            : EmbeddingBackfillOps.processEmbeddingBackfill(context);
      }
      case SPARSE_PARENT, SPARSE_CHUNK -> SpladeBackfillOps.processSpladeBackfill(
          new SpladeBackfillOps.BackfillContext(fields, coordinator, commits, IndexingPacing.unthrottled(),
              () -> sparse, () -> true, 100, false, route == Route.SPARSE_CHUNK, log));
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(fields, coordinator,
          commits, IndexingPacing.unthrottled(), () -> ner, () -> true, 100, log));
      default -> throw new AssertionError(route);
    };
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @EnumSource(Route.class)
  void hardStopAfterOneUnitPreservesItsResultAndLeavesTheRestCancelled(Route route) throws Exception {
    seed(route);
    documents = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "doc-" + i).toList();
    var outcome = run(route, () -> false, () -> calls.get() >= 1);
    assertEquals(1, calls.get());
    if (route != Route.WINDOW) assertFalse(writes.isEmpty(), "the completed unit keeps its result");
    assertTrue(outcome.accounting().tickets().values().stream().anyMatch(
        disposition -> disposition == EnrichmentAccounting.Disposition.CANCELLED));
    assertTrue(writes.values().stream().noneMatch(update -> update.entrySet().stream()
        .anyMatch(entry -> entry.getKey().endsWith("_retry_count") && !entry.getValue().equals("0"))),
        "untouched documents do not spend a retry");
  }

  @org.junit.jupiter.api.Test
  void exhaustedEmbeddingShareDoesNotForceAFirstDenseUnit() throws Exception {
    seed(Route.DENSE_BATCH);
    metadata.put(SchemaFields.NER_STATUS, "PENDING");
    var outcome = CombinedEnrichmentBackfillOps.processCombinedBackfill(
        new CombinedEnrichmentBackfillOps.BackfillContext(fields, coordinator, commits,
            IndexingPacing.unthrottled(), provider::get, () -> null, () -> ner, () -> true,
            () -> true, 100, LoggerFactory.getLogger(getClass()), false, false, false, 0,
            new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> false, () -> false,
            () -> true, progress));
    assertDoesNotThrow(() -> verify(dense, never()).embedDocumentBatch(anyList()));
    assertDoesNotThrow(() -> verify(ner).extractEntitiesBatch(List.of("content")));
    assertEquals(1, outcome.accounting().count(EnrichmentAccounting.Stage.EMBED,
        EnrichmentAccounting.Disposition.DEFERRED));
    assertFalse(writes.get("doc").containsKey(SchemaFields.EMBEDDING_STATUS));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void deadlineSpentByNullOrArenaOomClassificationRetainsItsRoute(boolean arenaOom) throws Exception {
    seed(Route.SINGLE_PASS);
    doAnswer(call -> {
      calls.incrementAndGet();
      if (arenaOom) throw new RuntimeException(new ai.onnxruntime.OrtException(
          "BFCArena::AllocateRawInternal: Failed to allocate memory"));
      return null;
    }).when(dense).embedWithSpans(anyString(), any());
    run(Route.SINGLE_PASS, () -> calls.get() >= 1, () -> false);
    assertDoesNotThrow(() -> verify(dense, never()).embedDocumentWindows(anyString(), anyInt(), anyInt()));
    calls.set(0);
    run(Route.SINGLE_PASS, () -> calls.get() >= 1, () -> false);
    assertDoesNotThrow(() -> verify(dense, times(1)).embedWithSpans(anyString(), any()));
    assertDoesNotThrow(() -> verify(dense, times(1)).embedDocumentWindows("content", 0, 32));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void hardStopAfterClassificationRetainsItsRouteWithoutStartingWindows(boolean arenaOom) throws Exception {
    seed(Route.SINGLE_PASS);
    doAnswer(call -> {
      calls.incrementAndGet();
      if (arenaOom) throw new RuntimeException(new ai.onnxruntime.OrtException(
          "BFCArena::AllocateRawInternal: Failed to allocate memory"));
      return null;
    }).when(dense).embedWithSpans(anyString(), any());
    var outcome = run(Route.SINGLE_PASS, () -> false, () -> calls.get() >= 1);
    assertDoesNotThrow(() -> verify(dense, never()).embedDocumentWindows(anyString(), anyInt(), anyInt()));
    assertEquals(0, progress.trackedDocuments(), "classification is not computed window progress");
    assertEquals(1, outcome.accounting().count(EnrichmentAccounting.Stage.EMBED,
        EnrichmentAccounting.Disposition.CANCELLED));
    calls.set(0);
    run(Route.SINGLE_PASS, () -> calls.get() >= 1, () -> false);
    assertDoesNotThrow(() -> verify(dense, times(1)).embedWithSpans(anyString(), any()));
    assertDoesNotThrow(() -> verify(dense, times(1)).embedDocumentWindows("content", 0, 32));
  }

  @org.junit.jupiter.api.Test
  void batchOnlyLateParentCompletesWithoutWindowsOrRetry() throws Exception {
    seed(Route.SINGLE_PASS);
    provider.set(new EmbeddingProvider() {
      public float[] embedDocument(String text) { throw new AssertionError("batch expected"); }
      public float[] embedQuery(String text) { throw new AssertionError("query unexpected"); }
      public List<float[]> embedDocumentBatch(List<String> texts) {
        calls.incrementAndGet();
        return Collections.nCopies(texts.size(), new float[] {1, 0});
      }
      public EmbeddingService.ChunkedEmbedding embedWithSpans(String text, int[][] spans) { return null; }
      public int dimension() { return 2; }
      public Availability availability() { return Availability.READY; }
      public boolean isUsingGpu() { return false; }
    });
    run(Route.SINGLE_PASS, () -> false, () -> false);
    assertEquals(1, calls.get());
    assertEquals("COMPLETED", writes.getOrDefault("doc", Map.of()).get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", writes.get("doc").get(SchemaFields.EMBEDDING_RETRY_COUNT));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"generation", "uid", "revision", "provider", "dimension"})
  void identicalTextWithChangedDenseIdentityRestartsWindows(String change) throws Exception {
    seed(Route.WINDOW);
    run(Route.WINDOW, () -> calls.get() >= 1, () -> false);
    assertEquals(32, progress.nextWindow("doc", "content"));
    EmbeddingProvider next = dense;
    switch (change) {
      case "generation" -> when(coordinator.bulkDeleteEpoch()).thenReturn(1L);
      case "uid" -> metadata.put(SchemaFields.DOC_UID, "uid-2");
      case "revision" -> metadata.put(SchemaFields.CONTENT_SHA256, "revision-2");
      case "dimension" -> when(dense.dimension()).thenReturn(3);
      case "provider" -> {
        next = mock(EmbeddingProvider.class, delegatesToDense());
        provider.set(next);
      }
      default -> throw new AssertionError(change);
    }
    calls.set(0);
    run(Route.WINDOW, () -> calls.get() >= 1, () -> false);
    var target = next;
    assertDoesNotThrow(() -> verify(target, atLeastOnce()).embedDocumentWindows("content", 0, 32),
        "replacement identity must restart at zero");
    assertDoesNotThrow(() -> verify(target, never()).embedDocumentWindows("content", 32, 32));
  }

  private org.mockito.stubbing.Answer<Object> delegatesToDense() {
    return org.mockito.AdditionalAnswers.delegatesTo(dense);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"replay", "skip"})
  void rejectedSlicesUseTheExistingRetryLimit(String fault) throws Exception {
    checkSliceRetryLimit(fault);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"empty", "null"})
  void nullAndEmptySlicesKeepExistingRetryAccounting(String fault) throws Exception {
    checkSliceRetryLimit(fault);
  }

  private void checkSliceRetryLimit(String fault) throws Exception {
    seed(Route.WINDOW);
    progress.record("doc", "content", 96, 0, List.of(new float[] {1, 0}));
    doAnswer(call -> {
      calls.incrementAndGet();
      if (fault.equals("null")) return null;
      int from = fault.equals("replay") ? 0 : fault.equals("skip") ? 2 : call.getArgument(1);
      return new EmbeddingProvider.WindowSlice(
          fault.equals("empty") ? List.of() : List.of(new float[] {0, 1}), from, 96);
    }).when(dense).embedDocumentWindows(anyString(), anyInt(), anyInt());
    for (int attempt = 1; attempt <= SchemaFields.EMBEDDING_MAX_RETRIES; attempt++) {
      run(Route.WINDOW, () -> false, () -> false);
      assertEquals(Integer.toString(attempt), writes.getOrDefault("doc", Map.of())
          .get(SchemaFields.EMBEDDING_RETRY_COUNT), "a rejected slice cannot loop without charge");
      metadata.put(SchemaFields.EMBEDDING_RETRY_COUNT, Integer.toString(attempt));
      assertEquals(fault.equals("replay") || fault.equals("skip") ? 1 : 0,
          progress.nextWindow("doc", "content"));
    }
    assertEquals("FAILED", writes.get("doc").get(SchemaFields.EMBEDDING_STATUS));
    assertEquals(SchemaFields.EMBEDDING_MAX_RETRIES, calls.get());
  }
}
