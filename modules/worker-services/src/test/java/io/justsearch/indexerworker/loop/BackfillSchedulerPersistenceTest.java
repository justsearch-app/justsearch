/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scheduler composition over the real RMW/revision fixtures, including committed-reader proof. */
class BackfillSchedulerPersistenceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private final AtomicLong clock = new AtomicLong(-100_000_000_000L);

  @Test
  void tightLoopCadenceAndFinalCommitPublishBeforeRuntimeClose() throws Exception {
    EmbeddingProvider embedding = embedding();
    AtomicInteger encodes = new AtomicInteger();
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      spend(encodes.incrementAndGet() == 8 ? 5_100_000_000L : 1);
      return Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
    });
    try (var runtime = open()) {
      for (int i = 0; i < 64; i++) seed(runtime, "parent-" + i, "parent-" + i);
      commit(runtime);
      List<CommitReason> reasons = new ArrayList<>();
      List<Integer> committedAfterEncodes = new ArrayList<>();
      runtime.commitOps().setCommitCompletedListener(reason -> {
        reasons.add(reason);
        committedAfterEncodes.add(encodes.get());
      });
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertEquals(8, encodes.get());
      assertEquals(List.of(CommitReason.BACKFILL_COMBINED, CommitReason.BACKFILL_COMBINED_FINAL), reasons);
      assertEquals(List.of(6, 8), committedAfterEncodes,
          "the probe has its existing separate counter; five tight batches commit, then the tail");
      for (int i = 0; i < 64; i++) assertDurable("parent-" + i, "COMPLETED", true);
    }
  }

  @Test
  void productionTimerPublishesSpentProbeBeforeRuntimeClose() throws Exception {
    EmbeddingProvider embedding = embedding();
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      spend(5_100_000_000L);
      return Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
    });
    // Keep the production timer at its unconfigured 10 s interval. Wait for its actual commit,
    // never poll a reader until the desired value happens to appear.
    try (var runtime = open(true)) {
      seed(runtime, "timer", "timer content");
      commit(runtime);
      CountDownLatch timerCommit = new CountDownLatch(1);
      List<CommitReason> reasons = Collections.synchronizedList(new ArrayList<>());
      runtime.commitOps().setCommitCompletedListener(reason -> {
        reasons.add(reason);
        if (reason == CommitReason.TIMER) timerCommit.countDown();
      });
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      runtime.commitOps().maybeRefreshBlocking(); // NRT observation only; the timer owns durability.
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("timer", SchemaFields.EMBEDDING_STATUS));
      assertTrue(timerCommit.await(25, TimeUnit.SECONDS), "the production safety-net timer must commit");
      assertEquals(List.of(CommitReason.TIMER), List.copyOf(reasons));
      assertDurable("timer", "COMPLETED", true);
    }
    try (var runtime = open()) {
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("timer", SchemaFields.EMBEDDING_STATUS));
    }
  }

  @Test
  void replacementDuringEncodeRejectsStaleBundleAndPublishesNewRevision() throws Exception {
    EmbeddingProvider embedding = embedding();
    List<String> encoded = new ArrayList<>();
    AtomicBoolean replaced = new AtomicBoolean();
    try (var runtime = open()) {
      seed(runtime, "parent", "old content");
      for (int i = 0; i < 7; i++) seed(runtime, "stable-" + i, "stable-" + i);
      commit(runtime);
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
        List<String> texts = inv.getArgument(0);
        encoded.addAll(texts);
        if (texts.contains("old content") && replaced.compareAndSet(false, true)) {
          seed(runtime, "parent", "new content");
          commit(runtime);
        }
        if (texts.contains("new content")) {
          assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.EMBEDDING_STATUS),
              "the stale old encode must not complete the replacement");
        }
        spend(texts.contains("new content") ? 5_100_000_000L : 1);
        return texts.stream().map(text -> text.equals("new content")
            ? new float[] {0, 1, 0, 0} : new float[] {1, 0, 0, 0}).toList();
      });
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertTrue(replaced.get());
      assertEquals(1, Collections.frequency(encoded, "old content"));
      assertEquals(1, Collections.frequency(encoded, "new content"));
      assertDurable("parent", "COMPLETED", true, 0, 1, 0, 0);
      for (int i = 0; i < 7; i++) assertDurable("stable-" + i, "COMPLETED", true);
    }
    try (var runtime = open()) {
      assertEquals("new content", runtime.documentFieldOps().getDocumentField("parent", SchemaFields.CONTENT));
      assertDurable("parent", "COMPLETED", true, 0, 1, 0, 0);
    }
  }

  @Test
  void reopenRestartsOnlyUnfinishedWindowsAndTightLoopPublishesCompletion() throws Exception {
    EmbeddingProvider embedding = embedding();
    when(embedding.documentWindowCount(anyString())).thenAnswer(inv ->
        "long content".equals(inv.getArgument(0)) ? 64 : 1);
    List<Integer> starts = new ArrayList<>();
    when(embedding.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
      int from = inv.getArgument(1);
      int count = Math.min((int) inv.getArgument(2), 64 - from);
      starts.add(from);
      spend(from == 0 ? 5_100_000_000L : 1);
      return new EmbeddingProvider.WindowSlice(
          Collections.nCopies(count, new float[] {1, 0, 0, 0}), from, 64);
    });
    List<String> shortEncoded = new ArrayList<>();
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      shortEncoded.addAll(texts);
      spend(shortEncoded.size() == 15 ? 5_100_000_000L : 1);
      return Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
    });
    try (var runtime = open()) {
      seed(runtime, "long", "long content");
      for (int i = 0; i < 15; i++) seed(runtime, "short-" + i, "short-" + i);
      commit(runtime);
      scheduler(runtime, embedding).runIdleCycle();
      assertEquals(List.of(0), starts);
      assertDurable("long", "PENDING", false);
      assertTrue(shortEncoded.isEmpty());
    }
    try (var runtime = open()) {
      BackfillScheduler restarted = scheduler(runtime, embedding);
      restarted.runIdleCycle();
      assertEquals(List.of(0, 0), starts, "restart must recompute the unpublished window accumulator");
      assertDurable("long", "PENDING", false);
      List<CommitReason> reasons = new ArrayList<>();
      runtime.commitOps().setCommitCompletedListener(reasons::add);
      assertTrue(restarted.runIdleCycle());
      assertEquals(List.of(0, 0, 32), starts, "the same scheduler resumes at its next unfinished window");
      assertEquals(15, new HashSet<>(shortEncoded).size());
      assertEquals(15, shortEncoded.size());
      assertEquals(List.of(CommitReason.BACKFILL_COMBINED_FINAL), reasons);
      assertDurable("long", "COMPLETED", true);
      for (int i = 0; i < 15; i++) assertDurable("short-" + i, "COMPLETED", true);
    }
    try (var runtime = open()) {
      assertFalse(scheduler(runtime, embedding).runIdleCycle());
      assertEquals(List.of(0, 0, 32), starts, "durable completion must survive another reopen");
    }
  }

  private void spend(long nanos) {
    clock.addAndGet(nanos);
  }

  @Test
  void deadlineAfterSparseRederivationPublishesTheCompleteConditionalBundle() throws Exception {
    EmbeddingProvider embedding = embedding();
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0, 0, 0}));
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.encodeBatch(anyList())).thenAnswer(inv -> {
      spend(5_100_000_000L);
      return List.of(Map.of("fresh", 2f));
    });
    try (var runtime = open()) {
      seed(runtime, "sparse", "sparse content");
      runtime.indexingCoordinator().updateDocument("sparse", Map.of(
          SchemaFields.SPLADE_STATUS, "COMPLETED", SchemaFields.SPLADE, Map.of("old", 2f)));
      commit(runtime);
      assertTrue(scheduler(runtime, embedding, splade).runIdleCycle());
    }
    try (var runtime = open()) {
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("sparse", SchemaFields.EMBEDDING_STATUS));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("sparse", SchemaFields.SPLADE_STATUS));
      var query = runtime.textQueryOps().buildSpladeQuery(Map.of("fresh", 1f), null);
      var hits = runtime.readPathOps().search(query, 10, Set.of(SchemaFields.DOC_ID),
          LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
      assertTrue(hits.hits().stream().anyMatch(hit -> "sparse".equals(hit.fields().get(SchemaFields.DOC_ID)))) ;
    }
  }

  private EmbeddingProvider embedding() {
    EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.documentWindowCount(anyString())).thenReturn(1);
    return embedding;
  }

  private BackfillScheduler scheduler(RunningRuntime runtime, EmbeddingProvider embedding) throws Exception {
    return scheduler(runtime, embedding, null);
  }

  private BackfillScheduler scheduler(
      RunningRuntime runtime, EmbeddingProvider embedding, SpladeEncoder splade) throws Exception {
    WorkerSignalBus signals = mock(WorkerSignalBus.class);
    EmbeddingProviderLifecycle lifecycle = new EmbeddingProviderLifecycle(
        signals, mock(JobQueue.class), runtime.indexCountOps(), runtime.commitOps());
    lifecycle.setEmbeddingProvider(embedding);
    NerService ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
    var defaults = ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
    when(config.ai().backfillPacing()).thenReturn(new ResolvedConfig.Ai.BackfillPacing(
        defaults.pollBatchSize(), 8, defaults.nerBackfillBatchSize(),
        defaults.disambiguationBackfillBatchSize(), defaults.spladeBackfillBatchSize(),
        defaults.spladeInterleaveBatchSize(), defaults.spladeInterleaveIntervalMs(),
        defaults.commitIntervalMs(), defaults.maxDocsBeforeCommit(), defaults.chunkSlotsPerBatch(),
        defaults.bgeM3BackfillBatchSize(), defaults.bgeM3InterleaveBatchSize(),
        defaults.foregroundDutyPct(), defaults.foregroundCooldownMs()));
    // Freeze a positively refreshed NRT snapshot at tight-loop entry. The real suspension,
    // conditional writes and commits still run; encode speed cannot race the reopen thread.
    CommitOps commits = mock(CommitOps.class, org.mockito.AdditionalAnswers.delegatesTo(runtime.commitOps()));
    doAnswer(inv -> {
      runtime.commitOps().maybeRefreshBlocking();
      runtime.commitOps().withNrtSuspended(inv.getArgument(0));
      return null;
    }).when(commits).withNrtSuspended(any(Runnable.class));
    return new BackfillScheduler(
        runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.indexCountOps(),
        commits, signals, IndexingPacing.unthrottled(), lifecycle,
        new AtomicBoolean(true), () -> config, () -> splade, () -> null, () -> ner, () -> null,
        clock::get);
  }

  // Same real catalog and revision fields as EnrichmentRevisionRaceTest.
  private RunningRuntime open() {
    return open(false);
  }

  private RunningRuntime open(boolean timerEnabled) {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("source_sha256", "embedding_retry_count", "splade_status",
        "splade_retry_count", "ner_status", "ner_retry_count")) {
      fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword",
          true, true, List.of("filter"), null, null, false));
    }
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("scheduler-deadline", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    if (!timerEnabled) runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, String id, String content) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "-uid");
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.SOURCE_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.IS_CHUNK, "false");
    fields.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
    fields.put(SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY");
    fields.put(SchemaFields.NER_STATUS, "COMPLETED_EMPTY");
    for (String stage : List.of("embedding", "splade", "ner")) fields.put(stage + "_retry_count", "0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void assertDurable(String id, String embeddingStatus, boolean vectorExpected, float... expectedVector) throws Exception {
    try (var fs = FSDirectory.open(directory);
        var reader = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
      var searcher = new IndexSearcher(reader);
      var hits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 2);
      assertEquals(1, hits.scoreDocs.length);
      int doc = hits.scoreDocs[0].doc;
      var stored = reader.storedFields().document(doc);
      assertEquals(embeddingStatus, stored.get(SchemaFields.EMBEDDING_STATUS), id);
      assertEquals("COMPLETED_EMPTY", stored.get(SchemaFields.NER_STATUS));
      for (String stage : List.of("embedding", "splade", "ner")) {
        assertEquals("0", stored.get(stage + "_retry_count"), "interruption cannot spend retries");
      }
      var leaf = reader.leaves().get(ReaderUtil.subIndex(doc, reader.leaves()));
      var vectors = leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
      var iterator = vectors == null ? null : vectors.iterator();
      boolean hasVector = iterator != null && iterator.advance(doc - leaf.docBase) == doc - leaf.docBase;
      assertEquals(vectorExpected, hasVector, "only complete vectors may become durable");
      if (expectedVector.length > 0) assertArrayEquals(expectedVector, vectors.vectorValue(iterator.index()), 0f);
      assertEquals("COMPLETED_EMPTY", stored.get(SchemaFields.SPLADE_STATUS));
    }
  }
}
