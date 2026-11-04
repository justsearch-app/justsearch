/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;

final class EnrichmentInternalStopTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  private static FieldCatalogDef catalog() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("embedding_retry_count", "splade_status", "splade_retry_count", "ner_status", "ner_retry_count", "entity_persons_raw")) {
      fields.add(new FieldCatalogDef.FieldDef(name, name.endsWith("count") ? "long" : "keyword", true, true,
          List.of("filter"), null, null, false));
    }
    fields.add(new FieldCatalogDef.FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    return new FieldCatalogDef("internal-stop", fields);
  }

  @org.junit.jupiter.api.Test
  void cancellationPreservesCompletedSparseSlicesWithoutSettingBackoff() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      int prefix = io.justsearch.indexerworker.loop.pacing.BulkPacePolicy.DEFAULTS.spladePhaseDocs();
      for (int i = 0; i <= prefix; i++) {
        String id = String.format(Locale.ROOT, "doc%03d", i);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.CONTENT, "document",
            SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"),
            SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.SPLADE_RETRY_COUNT, "2")));
      }
      runtime.commitOps().maybeRefreshBlocking();
      var sparse = mock(SpladeEncoder.class);
      var calls = new AtomicInteger();
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
        if (calls.incrementAndGet() == 2) throw new java.util.concurrent.CancellationException("native hard stop");
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
      });
      var outcome = SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
          () -> sparse, () -> true, prefix + 1, true, false, LoggerFactory.getLogger(getClass())));
      assertTrue(outcome.success(), "cancellation is never a systemic backoff signal");
      assertEquals(prefix, outcome.docsProcessed(), "completed earlier units keep publication credit");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(prefix, runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "COMPLETED"));
      assertEquals(1, runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "PENDING"));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("doc064", SchemaFields.SPLADE_RETRY_COUNT));
      assertDoesNotThrow(() -> verify(sparse, never()).encode(anyString(), any()));
    }
  }

  @org.junit.jupiter.api.Test
  void cancellationInErrorFallbackKeepsAnEarlierCompletedSparseSlice() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      int prefix = io.justsearch.indexerworker.loop.pacing.BulkPacePolicy.DEFAULTS.spladePhaseDocs();
      for (int i = 0; i <= prefix; i++) {
        String id = String.format(Locale.ROOT, "doc%03d", i);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.CONTENT, "document " + id,
            SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document " + id),
            SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.SPLADE_RETRY_COUNT, "2")));
      }
      runtime.commitOps().maybeRefreshBlocking();
      var held = new AtomicBoolean();
      var batches = new AtomicInteger();
      var fallbackCalls = new AtomicInteger();
      var sparse = mock(SpladeEncoder.class);
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
        if (batches.incrementAndGet() == 2) throw new IllegalStateException("batch requires per-document fallback");
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f));
      });
      when(sparse.encode(anyString(), any())).thenAnswer(call -> {
        io.justsearch.core.execution.InferenceRequest request = call.getArgument(1);
        for (int i = 0; i < 2; i++) {
          request.remainingNanos();
          fallbackCalls.incrementAndGet();
          held.set(true);
        }
        return Map.of("term", 1f);
      });
      var outcome = SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
          () -> sparse, () -> !held.get(), prefix + 1, true, false, LoggerFactory.getLogger(getClass())));
      runtime.commitOps().maybeRefreshBlocking();
      String cancelledId = String.format(Locale.ROOT, "doc%03d", prefix);
      assertAll(
          () -> assertEquals(1, fallbackCalls.get(), "fallback stops before its next native call"),
          () -> assertEquals(prefix, outcome.docsProcessed(), "cancellation must preserve the earlier completed sparse slice"),
          () -> assertTrue(outcome.success(), "cancellation must not cause systemic backoff"),
          () -> assertEquals(prefix, runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "COMPLETED"), "persisted completed prefix"),
          () -> assertEquals(1, runtime.indexCountOps().countByField(SchemaFields.SPLADE_STATUS, "PENDING"), "persisted pending suffix"),
          () -> assertEquals("2", runtime.documentFieldOps().getDocumentField(cancelledId, SchemaFields.SPLADE_RETRY_COUNT), "suffix retries unchanged"),
          () -> assertEquals(2, batches.get(), "completed slices are not re-encoded"),
          () -> assertDoesNotThrow(() -> verify(sparse).encode(eq("document " + cancelledId), any())));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"ner", "sparse"})
  void cycleDeadlineLetsTheCurrentOperationFinishAndBoundsTheNextUnit(String stage) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      int prefix = stage.equals("ner") ? 1 : io.justsearch.indexerworker.loop.pacing.BulkPacePolicy.DEFAULTS.spladePhaseDocs();
      String status = stage.equals("ner") ? SchemaFields.NER_STATUS : SchemaFields.SPLADE_STATUS;
      for (int i = 0; i <= prefix; i++) {
        String id = String.format(Locale.ROOT, "doc%03d", i);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.CONTENT, "document",
            SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"), status, "PENDING")));
      }
      runtime.commitOps().maybeRefreshBlocking();
      var expired = new AtomicBoolean(); var calls = new AtomicInteger();
      Runnable operation = () -> {
        LocalSessionAcquisition.background().remainingNanos(); calls.incrementAndGet(); expired.set(true);
        LocalSessionAcquisition.background().remainingNanos(); calls.incrementAndGet();
      };
      var ner = mock(NerService.class); when(ner.isAvailable()).thenReturn(true);
      when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> { operation.run(); return List.of(NerResult.EMPTY); });
      var sparse = mock(SpladeEncoder.class);
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> { operation.run();
        return Collections.nCopies(((List<?>) call.getArgument(0)).size(), Map.of("term", 1f)); });
      var result = CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
          () -> mock(EmbeddingProvider.class), () -> stage.equals("sparse") ? sparse : null,
          () -> stage.equals("ner") ? ner : null, () -> true, () -> true, prefix + 1, LoggerFactory.getLogger(getClass()),
          false, false, false, 0, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, expired::get,
          () -> false, () -> false, new WindowedEmbedProgress()));
      assertEquals(2, calls.get(), "deadline alone cannot interrupt a document's internal native calls");
      assertTrue(result.wroteAnything());
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(prefix, runtime.indexCountOps().countByField(status, stage.equals("ner") ? "COMPLETED_EMPTY" : "COMPLETED"));
      assertEquals(1, runtime.indexCountOps().countByField(status, "PENDING"));
    }
  }

  @org.junit.jupiter.api.Test
  void anInternalDenseStopKeepsEarlierValidSlicesAndTheirActivePacingCredit() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "doc", SchemaFields.DOC_UID, "uid", SchemaFields.CONTENT, "document",
          SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"),
          SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2")));
      runtime.commitOps().maybeRefreshBlocking();
      var held = new AtomicBoolean(); var calls = new AtomicInteger(); var starts = new ArrayList<Integer>();
      var dense = mock(EmbeddingService.class); when(dense.isAvailable()).thenReturn(true); when(dense.dimension()).thenReturn(4);
      when(dense.documentWindowCount(anyString())).thenReturn(64);
      when(dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        int from = call.getArgument(1); starts.add(from);
        for (int nativeCall = 0; nativeCall < 2; nativeCall++) {
          LocalSessionAcquisition.background().remainingNanos();
          if (calls.incrementAndGet() == 3) held.set(true);
        }
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(32, new float[] {1, 0, 0, 0}), from, 64);
      });
      var progress = new WindowedEmbedProgress();
      var context = new CombinedEnrichmentBackfillOps.BackfillContext(runtime.documentFieldOps(), runtime.indexingCoordinator(),
          runtime.commitOps(), IndexingPacing.unthrottled(), () -> dense, () -> null, () -> null,
          () -> !held.get(), () -> true, 10, LoggerFactory.getLogger(getClass()), false, false, false, 0,
          new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, held::get, held::get, () -> false, progress);
      var first = CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
      assertEquals(3, calls.get());
      assertEquals(32, progress.nextWindow("doc", "document"), "only the incomplete slice is discarded");
      assertTrue(first.wroteAnything(), "retained-window advancement keeps active pacing");
      assertFalse(first.progressed(), "retained advancement cannot extend the tight loop");
      held.set(false);
      var resumed = CombinedEnrichmentBackfillOps.processCombinedBackfill(context);
      assertEquals(List.of(0, 32, 32), starts);
      assertTrue(resumed.progressed());
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("doc", SchemaFields.EMBEDDING_STATUS));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("doc", SchemaFields.EMBEDDING_RETRY_COUNT));
    }
  }

  @org.junit.jupiter.api.Test
  void heldGpuRetainedOwnersCannotOccupyTheCpuSelection() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      for (int i = 0; i < 100; i++) {
        String id = String.format(Locale.ROOT, "owner-%03d", i);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.CONTENT, "document",
            SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"),
            SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "2",
            SchemaFields.NER_STATUS, "COMPLETED_EMPTY")));
      }
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "z-ner", SchemaFields.DOC_UID, "z-ner", SchemaFields.CONTENT, "document",
          SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"),
          SchemaFields.NER_STATUS, "PENDING", SchemaFields.NER_RETRY_COUNT, "2")));
      runtime.commitOps().maybeRefreshBlocking();
      var dense = mock(EmbeddingService.class); when(dense.isAvailable()).thenReturn(true);
      when(dense.isUsingGpu()).thenReturn(true); when(dense.dimension()).thenReturn(4);
      var ner = mock(NerService.class); when(ner.isAvailable()).thenReturn(true);
      when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
      var progress = new WindowedEmbedProgress();
      for (int i = 0; i < 100; i++) {
        String id = String.format(Locale.ROOT, "owner-%03d", i);
        var input = runtime.documentFieldOps().getEnrichmentInputs(List.of(id)).get(id);
        progress.identify(id, "document", new WindowedEmbedProgress.Identity(
            runtime.indexingCoordinator().enrichmentAuthority(), runtime.indexingCoordinator().bulkDeleteEpoch(), dense, 4,
            input.fields().get(SchemaFields.DOC_UID), input.fields().get(SchemaFields.CONTENT_SHA256),
            input.fields().get(SchemaFields.SOURCE_SHA256)));
        progress.route(id, "document", WindowedEmbedProgress.Route.WINDOW);
        progress.record(id, "document", 64, 0, Collections.nCopies(32, new float[] {1, 0, 0, 0}));
      }
      var result = CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
          () -> dense, () -> null, () -> ner, () -> true, () -> true, 100, LoggerFactory.getLogger(getClass()),
          false, false, false, 0, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0},
          () -> false, () -> false, () -> false, progress, new HashSet<>(), () -> true, success -> {}, gpu -> !gpu));
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField("z-ner", SchemaFields.NER_STATUS),
          "held GPU owners must not fill the turn before the eligible CPU document");
      assertTrue(result.wroteAnything());
      assertEquals(100, progress.owners().size(), "holds retain valid dense owners without selecting their text");
      for (int i = 0; i < 100; i++) {
        String id = String.format(Locale.ROOT, "owner-%03d", i);
        assertEquals(32, progress.nextWindow(id, "document"));
        assertEquals("2", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EMBEDDING_RETRY_COUNT));
      }
      assertDoesNotThrow(() -> verify(dense, never()).embedDocumentWindows(anyString(), anyInt(), anyInt()));
      assertDoesNotThrow(() -> verify(dense, never()).embedDocumentBatch(anyList()));
      assertDoesNotThrow(() -> verify(ner).extractEntitiesBatch(anyList()));
    }
  }

  static Stream<Arguments> stops() {
    return Stream.of("dense", "window", "ner", "sparse", "bge", "combined-ner", "combined-sparse",
        "dense-fallback", "sparse-fallback", "bge-fallback", "combined-dense-fallback")
        .flatMap(path -> Stream.of("hold", "shutdown", "source", "provider", "authority")
            .map(stop -> Arguments.of(path, stop)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @MethodSource("stops")
  void everyHardStopReachesInternalCallsWithoutPublishingOrChargingRetries(String path, String stop) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(catalog()).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      String status = path.contains("ner") ? SchemaFields.NER_STATUS
          : path.contains("sparse") || path.startsWith("bge") ? SchemaFields.SPLADE_STATUS : SchemaFields.EMBEDDING_STATUS;
      String retry = status.replace("_status", "_retry_count");
      var row = new HashMap<String, Object>();
      row.put(SchemaFields.DOC_ID, "doc"); row.put(SchemaFields.DOC_UID, "uid");
      row.put(SchemaFields.CONTENT, "document");
      row.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("document"));
      row.put(status, "PENDING"); row.put(retry, "2");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(row));
      runtime.commitOps().maybeRefreshBlocking();
      var running = new AtomicBoolean(true);
      var held = new AtomicBoolean();
      var retired = new AtomicBoolean();
      var triggered = new AtomicBoolean();
      var calls = new AtomicInteger();
      var dense = mock(EmbeddingService.class);
      var ner = mock(NerService.class);
      var sparse = mock(SpladeEncoder.class);
      var bge = mock(BgeM3Encoder.class);
      when(dense.isAvailable()).thenReturn(true); when(dense.dimension()).thenReturn(4);
      when(ner.isAvailable()).thenReturn(true);
      BooleanSupplier canRun = () -> running.get() && !held.get();
      java.util.function.Consumer<Runnable> nativeCallsWithCheckpoint = checkpoint -> {
        for (int i = 0; i < 2; i++) {
          checkpoint.run();
          calls.incrementAndGet();
          if (triggered.compareAndSet(false, true)) switch (stop) {
            case "hold" -> held.set(true);
            case "shutdown" -> running.set(false);
            case "source" -> runtime.indexingCoordinator().deleteByPathPrefix("/unrelated/");
            case "provider" -> retired.set(true);
            case "authority" -> runtime.indexingCoordinator().transitionEnrichmentAuthority(() -> {});
            default -> throw new AssertionError(stop);
          }
        }
      };
      Runnable nativeCalls = () -> nativeCallsWithCheckpoint.accept(() -> LocalSessionAcquisition.background().remainingNanos());
      when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> { nativeCalls.run(); return List.of(new float[] {1, 0, 0, 0}); });
      when(dense.documentWindowCount(anyString())).thenReturn(path.equals("combined-dense-fallback") ? 1 : 64);
      when(dense.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        nativeCalls.run();
        int from = call.getArgument(1), count = call.getArgument(2);
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(count, new float[] {1, 0, 0, 0}), from, 64);
      });
      when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> { nativeCalls.run(); return List.of(new NerResult(List.of("Ada"), List.of(), List.of())); });
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> { nativeCalls.run(); return List.of(Map.of("term", 1f)); });
      when(bge.encodeBatch(anyList())).thenAnswer(call -> { nativeCalls.run(); return List.of(new BgeM3Output(new float[] {1, 0, 0, 0}, Map.of("term", 1f))); });
      if (path.endsWith("fallback")) {
        doReturn(null).when(dense).embedDocumentBatch(anyList());
        doThrow(new IllegalStateException("batch requires per-document fallback")).when(sparse).encodeBatch(anyList());
        doThrow(new IllegalStateException("batch requires per-document fallback")).when(bge).encodeBatch(anyList());
        when(dense.embedDocument(anyString(), any())).thenAnswer(call -> {
          io.justsearch.core.execution.InferenceRequest request = call.getArgument(1);
          nativeCallsWithCheckpoint.accept(() -> request.remainingNanos());
          return new float[] {1, 0, 0, 0};
        });
        when(sparse.encode(anyString(), any())).thenAnswer(call -> {
          io.justsearch.core.execution.InferenceRequest request = call.getArgument(1);
          nativeCallsWithCheckpoint.accept(() -> request.remainingNanos());
          return Map.of("term", 1f);
        });
        when(bge.encode(anyString(), any())).thenAnswer(call -> {
          io.justsearch.core.execution.InferenceRequest request = call.getArgument(1);
          nativeCallsWithCheckpoint.accept(() -> request.remainingNanos());
          return new BgeM3Output(new float[] {1, 0, 0, 0}, Map.of("term", 1f));
        });
      }
      var log = LoggerFactory.getLogger(getClass());
      Runnable process = () -> {
        switch (path) {
          case "dense", "dense-fallback" -> EmbeddingBackfillOps.processEmbeddingBackfill(new EmbeddingBackfillOps.BackfillContext(
              runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), mock(WorkerSignalBus.class),
              IndexingPacing.unthrottled(), () -> retired.get() ? null : dense, canRun, () -> true, 10, log));
          case "ner" -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(runtime.documentFieldOps(),
              runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(), () -> retired.get() ? null : ner, canRun, 10, log));
          case "sparse", "sparse-fallback" -> SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(runtime.documentFieldOps(),
              runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(), () -> retired.get() ? null : sparse, canRun, 10, true, false, log));
          case "bge", "bge-fallback" -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(runtime.documentFieldOps(),
              runtime.indexingCoordinator(), runtime.commitOps(), mock(WorkerSignalBus.class), () -> retired.get() ? null : bge, canRun, 10, true, false, log));
          default -> CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
              runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
              () -> retired.get() ? null : dense, () -> path.equals("combined-sparse") && !retired.get() ? sparse : null,
              () -> path.equals("combined-ner") && !retired.get() ? ner : null, canRun, () -> true, 10, log,
              false, false, false, 0, new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> !canRun.getAsBoolean(),
              () -> !canRun.getAsBoolean(), () -> false, new WindowedEmbedProgress()));
        }
      };
      assertDoesNotThrow(process::run);
      assertEquals(1, calls.get(), "hard stop must reach the operation's second native boundary");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("doc", status));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("doc", retry));
      assertNull(runtime.documentFieldOps().getDocumentField("doc", SchemaFields.ENTITY_PERSONS_RAW));
      running.set(true); held.set(false); retired.set(false); calls.set(0);
      assertDoesNotThrow(process::run);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("doc", status));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("doc", retry));
    }
  }
}
