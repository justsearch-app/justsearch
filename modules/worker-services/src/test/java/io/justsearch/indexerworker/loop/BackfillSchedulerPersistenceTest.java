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
import java.util.concurrent.atomic.AtomicBoolean;
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
  void deadlinePublishesCompletedUnitsAndReopenResumesOnlyPendingParents() throws Exception {
    List<String> encoded = new ArrayList<>();
    EmbeddingProvider embedding = embedding();
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
      List<String> texts = inv.getArgument(0);
      encoded.addAll(texts);
      clock.addAndGet(5_000_000_000L);
      return Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
    });
    Set<String> completed;
    try (var runtime = open()) {
      for (int i = 0; i < 16; i++) seed(runtime, "parent-" + i, "parent-" + i);
      commit(runtime);
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertEquals(8, encoded.size(), "the spent probe must not start another encode slice");
      completed = Set.copyOf(encoded);
      // No test commit here: scheduler/combined final publication owns durability.
      for (int i = 0; i < 16; i++) {
        String id = "parent-" + i;
        assertDurable(id, completed.contains(id) ? "COMPLETED" : "PENDING", completed.contains(id));
      }
    }
    try (var runtime = open()) {
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertEquals(16, encoded.size());
      assertEquals(16, new HashSet<>(encoded).size(), "reopen must not re-encode completed parents");
      for (int i = 0; i < 16; i++) assertDurable("parent-" + i, "COMPLETED", true);
    }
  }

  @Test
  void unfinishedWindowsResumeInProcessButRestartRecomputesOnlyUnpublishedWork() throws Exception {
    EmbeddingProvider embedding = embedding();
    when(embedding.documentWindowCount(anyString())).thenReturn(64);
    List<Integer> starts = new ArrayList<>();
    when(embedding.embedDocumentWindows(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
      int from = inv.getArgument(1);
      int count = Math.min((int) inv.getArgument(2), 64 - from);
      starts.add(from);
      clock.addAndGet(5_000_000_000L);
      return new EmbeddingProvider.WindowSlice(
          Collections.nCopies(count, new float[] {1, 0, 0, 0}), from, 64);
    });
    try (var runtime = open()) {
      seed(runtime, "long", "long content");
      commit(runtime);
      BackfillScheduler scheduler = scheduler(runtime, embedding);
      scheduler.runIdleCycle();
      assertEquals(List.of(0), starts);
      assertDurable("long", "PENDING", false);
      scheduler.runIdleCycle();
      assertEquals(List.of(0, 32), starts);
      assertDurable("long", "COMPLETED", true);
      seed(runtime, "unfinished", "unfinished content");
      commit(runtime);
      scheduler.runIdleCycle();
      assertEquals(List.of(0, 32, 0), starts);
      assertDurable("unfinished", "PENDING", false);
    }
    try (var runtime = open()) {
      BackfillScheduler restarted = scheduler(runtime, embedding);
      restarted.runIdleCycle();
      assertEquals(List.of(0, 32, 0, 0), starts, "restart owns no process-local window accumulator");
      assertDurable("unfinished", "PENDING", false);
      restarted.runIdleCycle();
      assertEquals(List.of(0, 32, 0, 0, 32), starts);
      assertDurable("unfinished", "COMPLETED", true);
      assertDurable("long", "COMPLETED", true);
    }
  }

  @Test
  void replacementDuringEncodeRejectsStaleBundleAndReopenKeepsReplacementRunnable() throws Exception {
    EmbeddingProvider embedding = embedding();
    AtomicBoolean replaced = new AtomicBoolean();
    try (var runtime = open()) {
      seed(runtime, "parent", "old content");
      seed(runtime, "stable", "stable content");
      commit(runtime);
      doAnswer(inv -> {
        List<String> texts = inv.getArgument(0);
        if (replaced.compareAndSet(false, true)) {
          seed(runtime, "parent", "new content");
          commit(runtime);
        }
        clock.addAndGet(5_000_000_000L);
        return Collections.nCopies(texts.size(), new float[] {1, 0, 0, 0});
      }).when(embedding).embedDocumentBatch(anyList());
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertTrue(replaced.get(), "replacement must land while the old encode is in flight");
      assertDurable("parent", "PENDING", false);
      assertDurable("stable", "COMPLETED", true);
    }
    try (var runtime = open()) {
      doAnswer(inv -> {
        List<String> texts = inv.getArgument(0);
        assertEquals(List.of("new content"), texts);
        clock.addAndGet(5_000_000_000L);
        return List.of(new float[] {0, 1, 0, 0});
      }).when(embedding).embedDocumentBatch(anyList());
      assertTrue(scheduler(runtime, embedding).runIdleCycle());
      assertDurable("parent", "COMPLETED", true);
    }
  }

  @Test
  void deadlineAfterSparseRederivationPublishesTheCompleteConditionalBundle() throws Exception {
    EmbeddingProvider embedding = embedding();
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0, 0, 0}));
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.encodeBatch(anyList())).thenAnswer(inv -> {
      clock.addAndGet(5_000_000_000L);
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
    when(config.ai().backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    return new BackfillScheduler(
        runtime.documentFieldOps(), runtime.indexingCoordinator(), runtime.indexCountOps(),
        runtime.commitOps(), signals, IndexingPacing.unthrottled(), lifecycle,
        new AtomicBoolean(true), () -> config, () -> splade, () -> null, () -> ner, () -> null,
        clock::get);
  }

  // Same real catalog and revision fields as EnrichmentRevisionRaceTest.
  private RunningRuntime open() {
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
    runtime.commitOps().stopCommitTimer();
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

  private void assertDurable(String id, String embeddingStatus, boolean vectorExpected) throws Exception {
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
      boolean hasVector = vectors != null && vectors.iterator().advance(doc - leaf.docBase) == doc - leaf.docBase;
      assertEquals(vectorExpected, hasVector, "only complete vectors may become durable");
      assertEquals("COMPLETED_EMPTY", stored.get(SchemaFields.SPLADE_STATUS));
    }
  }
}
