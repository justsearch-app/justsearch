/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.*;
import io.justsearch.indexerworker.loop.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/** Uses the production lifecycle binding and the real conditional Lucene writer. */
final class EnrichmentAuthorityRaceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  enum Stage { EMBED, CHUNK, SPARSE, INTERLEAVED, SPARSE_CHUNK, INTERLEAVED_CHUNK,
    NER, COMBINED, SINGLE_PASS, WINDOW, BGE, BGE_CHUNK }
  private final BgeM3Encoder bge = mock(BgeM3Encoder.class);

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"source-stale", "corrected-output", "published"})
  void completedPublicationKeepsItsDispositionAcrossLaterReplacement(String outcome) throws Exception {
    try (var runtime = open()) {
      seed(runtime, Stage.COMBINED);
      var owner = new AtomicReference<EmbeddingProviderLifecycle>();
      var providerB = mock(EmbeddingProvider.class);
      var coordinator = mock(IndexingCoordinator.class,
          withSettings().spiedInstance(runtime.indexingCoordinator()).defaultAnswer(call -> {
            var receipt = call.callRealMethod();
            if (call.getMethod().getName().equals("publishEnrichmentBatch")) {
              owner.get().setEmbeddingProvider(providerB);
            }
            return receipt;
          }));
      var providerA = mock(EmbeddingProvider.class);
      when(providerA.isAvailable()).thenReturn(true);
      when(providerA.dimension()).thenReturn(4);
      when(providerA.embedDocumentBatch(anyList())).thenAnswer(call -> {
        if (outcome.equals("source-stale")) {
          runtime.indexingCoordinator().updateDocument("parent", Map.of(SchemaFields.DOC_UID, "replacement"));
          runtime.commitOps().maybeRefreshBlocking();
        }
        return List.of(outcome.equals("corrected-output")
            ? new float[] {Float.NaN, 0, 0, 0} : new float[] {1, 0, 0, 0});
      });
      var signals = mock(WorkerSignalBus.class);
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class),
          runtime.indexCountOps(), runtime.commitOps());
      lifecycle.setEmbeddingProvider(providerA);
      owner.set(lifecycle);
      new BackfillScheduler(runtime.documentFieldOps(), coordinator, runtime.indexCountOps(),
          runtime.commitOps(), signals, IndexingPacing.unthrottled(), lifecycle, new AtomicBoolean(true),
          () -> null, () -> null, () -> null, () -> null, () -> null);
      var accounting = run(Stage.COMBINED, runtime, coordinator, signals, lifecycle, null, null);
      var expected = switch (outcome) {
        case "source-stale" -> EnrichmentAccounting.Disposition.REJECTED_STALE;
        case "corrected-output" -> EnrichmentAccounting.Disposition.REJECTED_OUTPUT;
        default -> EnrichmentAccounting.Disposition.PUBLISHED;
      };
      assertEquals(1, accounting.count(EnrichmentAccounting.Stage.EMBED, expected),
          "later replacement cannot reinterpret a completed conditional publication");
    }
  }

  @org.junit.jupiter.api.Test
  void republishingTheSameProviderRestartsItsRetainedWindows() throws Exception {
    try (var runtime = open()) {
      seed(runtime, Stage.SINGLE_PASS);
      var provider = mock(EmbeddingService.class);
      when(provider.isAvailable()).thenReturn(true);
      when(provider.dimension()).thenReturn(4);
      var units = new AtomicInteger();
      var starts = new ArrayList<Integer>();
      when(provider.embedWithSpans(anyString(), any())).thenAnswer(call -> {
        units.incrementAndGet();
        return null;
      });
      when(provider.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        units.incrementAndGet();
        int from = call.getArgument(1);
        starts.add(from);
        return new EmbeddingProvider.WindowSlice(
            Collections.nCopies(32, new float[] {1, 0, 0, 0}), from, 64);
      });
      var signals = mock(WorkerSignalBus.class);
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class),
          runtime.indexCountOps(), runtime.commitOps());
      lifecycle.setEmbeddingProvider(provider);
      new BackfillScheduler(runtime.documentFieldOps(), runtime.indexingCoordinator(),
          runtime.indexCountOps(), runtime.commitOps(), signals, IndexingPacing.unthrottled(),
          lifecycle, new AtomicBoolean(true), () -> null, () -> null, () -> null, () -> null, () -> null);
      var progress = new WindowedEmbedProgress();
      for (int turn = 0; turn < 2; turn++) {
        if (turn == 1) lifecycle.setEmbeddingProvider(provider);
        units.set(0);
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            new CombinedEnrichmentBackfillOps.BackfillContext(runtime.documentFieldOps(),
                runtime.indexingCoordinator(), runtime.commitOps(), IndexingPacing.unthrottled(),
                lifecycle::embeddingProvider, () -> null, () -> null, () -> true, () -> true,
                10, LoggerFactory.getLogger(getClass()), false, false, true, 0,
                new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> units.get() >= 2,
                () -> false, () -> false, progress));
      }
      assertEquals(List.of(0, 0), starts, "a new publication cannot inherit a prior partial");
      assertDoesNotThrow(() -> verify(provider, times(2)).embedWithSpans(anyString(), any()));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"SPARSE,false", "SPARSE,true", "INTERLEAVED,false", "INTERLEAVED,true",
      "NER,false", "NER,true", "SINGLE_PASS,false", "WINDOW,false"})
  void providerReplacementDuringInferenceRevokesSuccessAndFailure(Stage stage, boolean failure)
      throws Exception {
    race(stage, failure, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"EMBED,false", "EMBED,true", "CHUNK,false", "CHUNK,true",
      "COMBINED,false", "COMBINED,true", "SINGLE_PASS,true", "WINDOW,true"})
  void providerReplacementDuringInferencePreservesExistingRejection(Stage stage, boolean failure)
      throws Exception {
    race(stage, failure, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"EMBED,false", "EMBED,true", "CHUNK,false", "CHUNK,true", "SPARSE,false",
      "SPARSE,true", "INTERLEAVED,false", "INTERLEAVED,true", "NER,false", "NER,true",
      "COMBINED,false", "COMBINED,true", "SINGLE_PASS,false", "SINGLE_PASS,true",
      "WINDOW,false", "WINDOW,true"})
  void providerReplacementAtCoordinatorBoundaryRevokesTheComputedOutcome(Stage stage, boolean failure)
      throws Exception {
    race(stage, failure, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"EMBED,false", "EMBED,true", "CHUNK,false", "CHUNK,true", "SPARSE,false",
      "SPARSE,true", "INTERLEAVED,false", "INTERLEAVED,true", "NER,false", "NER,true",
      "COMBINED,false", "COMBINED,true", "SINGLE_PASS,false", "SINGLE_PASS,true",
      "WINDOW,false", "WINDOW,true"})
  void providerReplacementWhilePublicationWaitsForMutationLockIsAtomic(Stage stage, boolean failure)
      throws Exception {
    race(stage, failure, true, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"SINGLE_PASS", "WINDOW"})
  void unavailableProviderCannotPublishItsSuccessfulFinalDenseResult(Stage stage) throws Exception {
    race(stage, false, false, false, true, false);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"SPARSE", "INTERLEAVED", "SPARSE_CHUNK", "INTERLEAVED_CHUNK"})
  void replacedProviderCancellationStaysDeferredThroughTheRealWriter(Stage stage) throws Exception {
    race(stage, false, false, false, false, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"EMBED,false", "EMBED,true", "CHUNK,false", "CHUNK,true",
      "COMBINED,false", "COMBINED,true", "SINGLE_PASS,false", "SINGLE_PASS,true",
      "WINDOW,false", "WINDOW,true", "NER,false", "NER,true",
      "SPARSE,false", "SPARSE,true", "INTERLEAVED,false", "INTERLEAVED,true"})
  void unavailableProviderDefersFailureAndCancellationThroughTheRealWriter(Stage stage, boolean cancellation) throws Exception {
    race(stage, !cancellation, false, false, true, cancellation);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"BGE,false,success", "BGE,false,failure", "BGE,false,cancellation",
      "BGE,true,success", "BGE,true,failure", "BGE,true,cancellation",
      "BGE_CHUNK,false,success", "BGE_CHUNK,false,failure", "BGE_CHUNK,false,cancellation",
      "BGE_CHUNK,true,success", "BGE_CHUNK,true,failure", "BGE_CHUNK,true,cancellation"})
  void retiredBgeCannotPublishAnyFinalOutcomeThroughTheRealWriter(Stage stage, boolean unavailable,
      String outcome) throws Exception {
    race(stage, outcome.equals("failure"), false, false, unavailable, outcome.equals("cancellation"));
  }

  private void race(Stage stage, boolean failure, boolean publicationBoundary) throws Exception {
    race(stage, failure, publicationBoundary, false);
  }

  private void race(Stage stage, boolean failure, boolean publicationBoundary, boolean mutationWindow)
      throws Exception {
    race(stage, failure, publicationBoundary, mutationWindow, false, false);
  }

  private void race(Stage stage, boolean failure, boolean publicationBoundary, boolean mutationWindow,
      boolean unavailable, boolean cancellation) throws Exception {
    try (var runtime = open()) {
      seed(runtime, stage);
      var ready = new AtomicBoolean(true);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var once = new AtomicBoolean();
      Runnable latch = () -> {
        if (!once.compareAndSet(false, true)) return;
        entered.countDown();
        try { assertTrue(release.await(20, TimeUnit.SECONDS), "replacement must release inference"); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
      };
      var coordinator = mock(IndexingCoordinator.class,
          withSettings().spiedInstance(runtime.indexingCoordinator()).defaultAnswer(call -> {
            if (publicationBoundary && call.getMethod().getName().equals("publishEnrichmentBatch")) {
              latch.run();
            }
            return call.callRealMethod();
          }));
      var providerA = mock(EmbeddingProvider.class);
      var providerB = mock(EmbeddingProvider.class);
      when(providerA.isAvailable()).thenAnswer(call -> ready.get());
      when(providerA.dimension()).thenReturn(4);
      when(providerA.documentWindowCount(anyString())).thenReturn(stage == Stage.WINDOW ? 2 : 1);
      when(providerB.isAvailable()).thenReturn(true);
      Runnable inference = () -> {
        if (!publicationBoundary) latch.run();
        if (cancellation) throw new CancellationException("retired inference cancelled");
      };
      when(providerA.embedDocumentBatch(anyList())).thenAnswer(call -> {
        inference.run();
        List<String> texts = call.getArgument(0);
        return failure ? List.of() : Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
      });
      when(providerA.embedWithSpans(anyString(), any())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return new EmbeddingService.ChunkedEmbedding(new float[] {1, 0, 0, 0}, List.of(), 1);
      });
      when(providerA.embedDocument(anyString(), any())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return new float[] {1, 0, 0, 0};
      });
      when(providerA.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return new EmbeddingProvider.WindowSlice(
            Collections.nCopies(2, new float[] {1, 0, 0, 0}), 0, 2);
      });
      var sparse = mock(SpladeEncoder.class);
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return List.of(Map.of("old-term", 1f));
      });
      when(sparse.encode(anyString(), any())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return Map.of("old-term", 1f);
      });
      var ner = mock(NerService.class);
      when(ner.isAvailable()).thenAnswer(call -> ready.get());
      when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        return List.of(NerResult.EMPTY);
      });
      when(bge.encodeBatch(anyList())).thenAnswer(call -> {
        inference.run();
        if (failure) throw new IllegalArgumentException("document inference failed");
        List<String> texts = call.getArgument(0);
        return Collections.nCopies(texts.size(), new BgeM3Output(new float[] {1, 0, 0, 0}, Map.of("old-term", 1f)));
      });
      var signals = mock(WorkerSignalBus.class);
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class),
          runtime.indexCountOps(), runtime.commitOps());
      lifecycle.setEmbeddingProvider(providerA);
      // The scheduler binds the lifecycle to the coordinator on both before and candidate trees.
      new BackfillScheduler(runtime.documentFieldOps(), coordinator, runtime.indexCountOps(),
          runtime.commitOps(), signals, IndexingPacing.unthrottled(), lifecycle,
          new AtomicBoolean(true), () -> null, () -> sparse, () -> null, () -> ner, () -> null);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var worker = new AtomicReference<Thread>();
        var work = executor.submit(() -> {
          worker.set(Thread.currentThread());
          return run(stage, runtime, coordinator, signals, lifecycle, sparse, ner, ready::get);
        });
        if (!entered.await(10, TimeUnit.SECONDS)) {
          assertDoesNotThrow(() -> work.get(1, TimeUnit.SECONDS));
          fail("the selected route must reach the requested race boundary");
        }
        if (mutationWindow) {
          var field = IndexingCoordinator.class.getDeclaredField("dispatchLock");
          field.setAccessible(true);
          var lock = (java.util.concurrent.locks.ReentrantLock) field.get(coordinator);
          lock.lock();
          try {
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!lock.hasQueuedThread(worker.get()) && System.nanoTime() < deadline) {
              java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(lock.hasQueuedThread(worker.get()), "publication must be waiting for mutation");
            lifecycle.setEmbeddingProvider(providerB);
          } finally {
            lock.unlock();
          }
        } else {
          if (unavailable) ready.set(false);
          else lifecycle.setEmbeddingProvider(providerB);
          release.countDown();
        }
        var accounting = assertDoesNotThrow(() -> work.get(20, TimeUnit.SECONDS));
        runtime.commitOps().commitAndTrack(CommitReason.TIMER);
        runtime.commitOps().maybeRefreshBlocking();
        String id = stage == Stage.CHUNK || stage.name().endsWith("_CHUNK") ? "child" : "parent";
        String status = switch (stage) {
          case CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
          case SPARSE, INTERLEAVED, SPARSE_CHUNK, INTERLEAVED_CHUNK, BGE, BGE_CHUNK -> SchemaFields.SPLADE_STATUS;
          case NER -> SchemaFields.NER_STATUS;
          default -> SchemaFields.EMBEDDING_STATUS;
        };
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(id, status),
            "retired execution cannot publish success or terminal failure");
        assertEquals("0", runtime.documentFieldOps().getDocumentField(id,
            status.replace("_status", "_retry_count")), "retired execution cannot spend retries");
        if (stage == Stage.BGE || stage == Stage.BGE_CHUNK) {
          String denseStatus = stage == Stage.BGE_CHUNK ? SchemaFields.CHUNK_EMBEDDING_STATUS
              : SchemaFields.EMBEDDING_STATUS;
          assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(id, denseStatus));
          assertEquals("0", runtime.documentFieldOps().getDocumentField(id,
              denseStatus.replace("_status", "_retry_count")), "retirement cannot charge the paired dense stage");
        }
        assertEquals(0, runtime.readPathOps().search(new org.apache.lucene.search.TermQuery(
            new org.apache.lucene.index.Term(SchemaFields.SPLADE, "old-term")), 10, Set.of(), null, null)
            .hits().size(), "retired sparse artifacts must not become searchable");
        assertEquals(stage == Stage.BGE || stage == Stage.BGE_CHUNK ? 2 : 1, accounting.tickets().size());
        assertTrue(accounting.tickets().values().stream().allMatch(
            disposition -> disposition == EnrichmentAccounting.Disposition.DEFERRED),
            "retired success and failure outcomes stay deferred without retry charge");
        assertTrue(accounting.counters().entrySet().stream().noneMatch(
            entry -> entry.getKey().endsWith(".accepted") && entry.getValue() > 0));
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
      }
    }
  }

  private EnrichmentAccounting.Summary run(Stage stage, RunningRuntime runtime, IndexingCoordinator coordinator,
      WorkerSignalBus signals, EmbeddingProviderLifecycle lifecycle, SpladeEncoder sparse,
      NerService ner) {
    return run(stage, runtime, coordinator, signals, lifecycle, sparse, ner, () -> true);
  }

  private EnrichmentAccounting.Summary run(Stage stage, RunningRuntime runtime, IndexingCoordinator coordinator,
      WorkerSignalBus signals, EmbeddingProviderLifecycle lifecycle, SpladeEncoder sparse,
      NerService ner, java.util.function.BooleanSupplier ready) {
    var pacing = IndexingPacing.unthrottled();
    var log = LoggerFactory.getLogger(getClass());
    return switch (stage) {
      case EMBED, CHUNK -> {
        var context = new EmbeddingBackfillOps.BackfillContext(runtime.documentFieldOps(), coordinator,
            runtime.commitOps(), signals, pacing, lifecycle::embeddingProvider, () -> true,
            () -> true, 10, log);
        yield (stage == Stage.CHUNK ? EmbeddingBackfillOps.processChunkEmbeddingBackfill(context)
            : EmbeddingBackfillOps.processEmbeddingBackfill(context)).accounting();
      }
      case SPARSE, INTERLEAVED, SPARSE_CHUNK, INTERLEAVED_CHUNK -> SpladeBackfillOps.processSpladeBackfill(
          new SpladeBackfillOps.BackfillContext(runtime.documentFieldOps(), coordinator,
              runtime.commitOps(), pacing, () -> ready.getAsBoolean() ? sparse : null, () -> true, 10,
              stage != Stage.INTERLEAVED && stage != Stage.INTERLEAVED_CHUNK,
              stage.name().endsWith("_CHUNK"), log)).accounting();
      case BGE, BGE_CHUNK -> BgeM3BackfillOps.processBgeM3Backfill(
          new BgeM3BackfillOps.BackfillContext(runtime.documentFieldOps(), coordinator, runtime.commitOps(),
              signals, () -> ready.getAsBoolean() ? bge : null, () -> true, 10, false,
              stage == Stage.BGE_CHUNK, log)).accounting();
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(
          runtime.documentFieldOps(), coordinator, runtime.commitOps(), pacing, () -> ner,
          () -> true, 10, log)).accounting();
      case COMBINED, SINGLE_PASS, WINDOW -> CombinedEnrichmentBackfillOps.processCombinedBackfill(
          new CombinedEnrichmentBackfillOps.BackfillContext(runtime.documentFieldOps(), coordinator,
              runtime.commitOps(), pacing, lifecycle::embeddingProvider, () -> null, () -> null,
              () -> true, () -> true, 10, log, false, false, stage == Stage.SINGLE_PASS, 0,
              new ArrayDeque<>(), new ArrayDeque<>(), new int[] {0}, () -> false, () -> false,
              () -> false, new WindowedEmbedProgress())).accounting();
    };
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("embedding_retry_count", "splade_status", "splade_retry_count",
        "ner_status", "ner_retry_count")) {
      fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword", true, true,
          List.of("filter"), null, null, false));
    }
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("authority-races", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, Stage stage) {
    var fields = new HashMap<String, Object>();
    String text = "stable document content";
    fields.put(SchemaFields.DOC_ID, "parent"); fields.put(SchemaFields.DOC_UID, "parent-uid");
    fields.put(SchemaFields.CONTENT, text);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
    for (String name : List.of("embedding", "splade", "ner")) {
      fields.put(name + "_status", "PENDING"); fields.put(name + "_retry_count", "0");
    }
    if (stage.name().endsWith("_CHUNK")) fields.remove(SchemaFields.SPLADE_STATUS);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    if (stage == Stage.CHUNK || stage == Stage.SINGLE_PASS || stage.name().endsWith("_CHUNK")) {
      fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "child"); fields.put(SchemaFields.DOC_UID, "child-uid");
      fields.put(SchemaFields.IS_CHUNK, "true"); fields.put(SchemaFields.PARENT_DOC_ID, "parent");
      fields.put(SchemaFields.CHUNK_START_CHAR, 0); fields.put(SchemaFields.CHUNK_END_CHAR, text.length());
      fields.put(SchemaFields.CHUNK_INDEX, 0); fields.put(SchemaFields.CHUNK_TOTAL, 1);
      fields.put(SchemaFields.CHUNK_CONTENT, text);
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
      fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
      fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
      if (stage.name().endsWith("_CHUNK")) {
        fields.put(SchemaFields.SPLADE_STATUS, "PENDING");
        fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
      }
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    runtime.commitOps().commitAndTrack(CommitReason.TIMER);
    runtime.commitOps().maybeRefreshBlocking();
  }
}
