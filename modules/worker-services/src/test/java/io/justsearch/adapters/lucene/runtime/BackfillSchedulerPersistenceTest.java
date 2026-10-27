/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.BackfillScheduler;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real conditional writes and fresh committed readers; no timer, sleeps, or clock seam. */
final class BackfillSchedulerPersistenceTest extends LuceneExecutorTestBase {
  private static final float[] NEW_VECTOR = {1, 0, 0, 0};
  private static final float[] OLD_VECTOR = {0, 1, 0, 0};
  @TempDir Path directory;

  @Test
  void probeOnlyCycleCommitsEveryWrittenDocument() throws Exception {
    try (var runtime = open(directory)) {
      seed(runtime, "one");
      seed(runtime, "two");
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 2);
      assertTrue(cycle.scheduler().runIdleCycle());
      assertEquals(List.of(2), cycle.writes);
      assertDurable(directory, "one", "COMPLETED", NEW_VECTOR, "0");
      assertDurable(directory, "two", "COMPLETED", NEW_VECTOR, "0");
      assertEquals(List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED_FINAL, 1)), cycle.commits);
    }
  }

  @Test
  void windowOnlyProbeMakesProgressWithoutWritingOrCommitting() throws Exception {
    try (var runtime = open(directory)) {
      seed(runtime, "long");
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 1);
      cycle.windowed("long");
      assertTrue(cycle.scheduler().runIdleCycle(), "a window is activity for mode selection");
      assertEquals(List.of(0), cycle.windowStarts);
      assertTrue(cycle.writes.isEmpty(), "half a document has no publishable update");
      assertDurable(directory, "long", "PENDING", null, "0");
      assertEquals(List.of(), cycle.commits);
    }
  }

  @Test
  void writingBatchCadenceIncludesProbeAtAllThreeBoundaries() {
    // Run every boundary even when an earlier one fails on the before version.
    assertAll(() -> assertCadence(4), () -> assertCadence(5), () -> assertCadence(6));
  }

  private void assertCadence(int batches) throws Exception {
    long started = System.nanoTime();
    Path path = directory.resolve("batches-" + batches);
    try (var runtime = open(path)) {
      for (int i = 0; i < batches; i++) seed(runtime, "doc-" + i);
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 1);
      assertTrue(cycle.scheduler().runIdleCycle());
      assertEquals(Collections.nCopies(batches, 1), cycle.writes, "one real writing batch per doc");
      for (int i = 0; i < batches; i++) {
        assertDurable(path, "doc-" + i, "COMPLETED", NEW_VECTOR, "0");
      }
      List<CommitEvent> expected = switch (batches) {
        case 4 -> List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED_FINAL, 4));
        case 5 -> List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED, 5));
        case 6 -> List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED, 5),
            new CommitEvent(CommitReason.BACKFILL_COMBINED_FINAL, 6));
        default -> throw new AssertionError("unexpected boundary " + batches);
      };
      System.out.println("cadence " + batches + " observed " + cycle.commits);
      assertEquals(expected, cycle.commits, "commit after each five writing batches, probe included");
    } finally {
      System.out.printf("cadence %d elapsed %.3fs%n", batches, (System.nanoTime() - started) / 1e9);
    }
  }

  @Test
  void zeroWriteBatchAfterCadenceDoesNotAddAFinalCommit() throws Exception {
    try (var runtime = open(directory)) {
      for (int i = 0; i < 5; i++) seed(runtime, "doc-" + i);
      seed(runtime, "long");
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 1);
      cycle.windowed("long");
      assertTrue(cycle.scheduler().runIdleCycle());
      assertEquals(Collections.nCopies(5, 1), cycle.writes);
      assertEquals(List.of(0), cycle.windowStarts, "a zero-write batch ran after the writing batches");
      for (int i = 0; i < 5; i++) {
        assertDurable(directory, "doc-" + i, "COMPLETED", NEW_VECTOR, "0");
      }
      assertDurable(directory, "long", "PENDING", null, "0");
      assertEquals(List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED, 5)), cycle.commits);
    }
  }

  @Test
  void partiallyAcceptedProbeCommitsOnlyTheCurrentDocumentBundle() throws Exception {
    try (var runtime = open(directory)) {
      seed(runtime, "replaced");
      seed(runtime, "stable");
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 2);
      when(cycle.embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
        seed(runtime, "replaced", "new source", Map.of());
        cycle.pendingIngest.set(true);
        List<String> texts = inv.getArgument(0);
        assertEquals(2, texts.size(), "both old revisions were encoded before conditional acceptance");
        return Collections.nCopies(texts.size(), NEW_VECTOR);
      });
      assertTrue(cycle.scheduler().runIdleCycle());
      assertEquals(List.of(1), cycle.writes, "one replacement rejected, one current bundle accepted");
      assertDurable(directory, "replaced", "PENDING", null, "0");
      assertDurable(directory, "stable", "COMPLETED", NEW_VECTOR, "0");
      try (var fs = FSDirectory.open(directory);
          var reader = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
        assertEquals("new source", stored(reader, "replaced").get(SchemaFields.CONTENT));
      }
      assertEquals(List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED_FINAL, 1)), cycle.commits);
    }
  }

  @Test
  void settledCycleDoesNotWriteOrCommit() throws Exception {
    try (var runtime = open(directory)) {
      seed(runtime, "settled", "settled", Map.of(
          SchemaFields.EMBEDDING_STATUS, "COMPLETED", SchemaFields.VECTOR, OLD_VECTOR));
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 1);
      assertFalse(cycle.scheduler().runIdleCycle());
      assertTrue(cycle.writes.isEmpty());
      assertDurable(directory, "settled", "COMPLETED", OLD_VECTOR, "0");
      assertEquals(List.of(), cycle.commits);
    }
  }

  @Test
  void pendingIngestDuringProbeCommitsFinishedUnitsAtTheOrderlyBreak() throws Exception {
    try (var runtime = open(directory)) {
      for (int i = 0; i < 9; i++) seed(runtime, "doc-" + i);
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 9);
      when(cycle.embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
        List<String> texts = inv.getArgument(0);
        assertEquals(8, texts.size(), "stop after one encoder unit, leaving one pending document");
        cycle.pendingIngest.set(true);
        return Collections.nCopies(texts.size(), NEW_VECTOR);
      });
      assertTrue(cycle.scheduler().runIdleCycle());
      assertEquals(List.of(8), cycle.writes);
      for (int i = 0; i < 8; i++) {
        assertDurable(directory, "doc-" + i, "COMPLETED", NEW_VECTOR, "0");
      }
      assertDurable(directory, "doc-8", "PENDING", null, "0");
      verify(cycle.embedding, times(1)).embedDocumentBatch(anyList());
      assertEquals(List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED_FINAL, 1)), cycle.commits);
    }
  }

  @Test
  void abruptExitBeforeFinalCommitRestoresLastCommittedArtifactsAndRetries() throws Exception {
    var events = new ArrayList<CommitEvent>();
    try (var runtime = open(directory)) {
      for (int i = 0; i < 5; i++) seed(runtime, "doc-" + i);
      seed(runtime, "last", "last", Map.of(
          SchemaFields.VECTOR, OLD_VECTOR, SchemaFields.EMBEDDING_RETRY_COUNT, "2",
          SchemaFields.SPLADE_STATUS, "COMPLETED", SchemaFields.SPLADE, Map.of("old", 2f),
          SchemaFields.SPLADE_RETRY_COUNT, "1"));
      commitSeed(runtime);
      var cycle = new Cycle(runtime, 1);
      var commits = spy(runtime.commitOps());
      doThrow(new BeforeFinalCommit()).when(commits).commitAndTrack(CommitReason.BACKFILL_COMBINED_FINAL);
      when(cycle.signals.hasPendingIngest()).thenAnswer(inv -> cycle.writes.size() == 6);
      try {
        // The sentinel ends the cycle before publication, then rollback discards pending mutations.
        // Read the NRT state first to prove the sixth bundle really reached the writer.
        try {
          cycle.scheduler(commits).runIdleCycle();
        } catch (BeforeFinalCommit expected) {
          // Simulated abrupt exit: no finally-commit is promised by the scheduler.
        }
        assertEquals(Collections.nCopies(6, 1), cycle.writes);
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("last", SchemaFields.EMBEDDING_STATUS));
        events.addAll(cycle.commits);
      } finally {
        runtime.commitOps().suspendNrtRefresh();
        runtime.session().snapshot.writer().rollback(); // Never publish on normal runtime close.
      }
    }
    try (var reopened = open(directory)) {
      for (int i = 0; i < 5; i++) {
        assertDurable(directory, "doc-" + i, "COMPLETED", NEW_VECTOR, "0");
      }
      assertDurable(directory, "last", "PENDING", OLD_VECTOR, "2");
      try (var fs = FSDirectory.open(directory);
          var reader = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
        var last = stored(reader, "last");
        assertEquals("COMPLETED", last.get(SchemaFields.SPLADE_STATUS));
        assertEquals("1", last.get(SchemaFields.SPLADE_RETRY_COUNT));
        var sparse = reopened.textQueryOps().buildSpladeQuery(Map.of("old", 1f), null);
        assertEquals(1, new IndexSearcher(reader).count(sparse), "last committed sparse artifact survives");
      }
      assertEquals(List.of(new CommitEvent(CommitReason.BACKFILL_COMBINED, 5)), events);
    }
  }

  private static final class BeforeFinalCommit extends Error {}

  private record CommitEvent(CommitReason reason, long writingBatches) {}

  private final class Cycle {
    private final RunningRuntime runtime;
    private final int batchSize;
    private final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
    private final WorkerSignalBus signals = mock(WorkerSignalBus.class);
    private final AtomicBoolean pendingIngest = new AtomicBoolean();
    private final List<Integer> writes = new ArrayList<>();
    private final List<Integer> windowStarts = new ArrayList<>();
    private final List<CommitEvent> commits = new ArrayList<>();
    private final IndexingCoordinator coordinator;

    private Cycle(RunningRuntime runtime, int batchSize) throws Exception {
      this.runtime = runtime;
      this.batchSize = batchSize;
      when(embedding.isAvailable()).thenReturn(true);
      when(embedding.documentWindowCount(anyString())).thenReturn(1);
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(inv -> {
        List<String> texts = inv.getArgument(0);
        return Collections.nCopies(texts.size(), NEW_VECTOR);
      });
      when(signals.hasPendingIngest()).thenAnswer(inv -> pendingIngest.get());
      coordinator = spy(runtime.indexingCoordinator());
      doAnswer(inv -> {
        LuceneRuntimeTypes.BatchUpdateResult result = (LuceneRuntimeTypes.BatchUpdateResult) inv.callRealMethod();
        writes.add(result.updatedCount());
        // Make next-batch selection deterministic without committing. Every comparison/write is real.
        runtime.commitOps().maybeRefreshBlocking();
        return result;
      }).when(coordinator).updateDocumentsBatchIf(anyList(), anyMap());
      runtime.commitOps().setCommitCompletedListener(reason -> {
        commits.add(new CommitEvent(reason, writes.stream().filter(count -> count > 0).count()));
        System.out.println("backfill commit " + commits.getLast());
      });
    }

    private void windowed(String content) throws Exception {
      when(embedding.documentWindowCount(content)).thenReturn(64);
      when(embedding.embedDocumentWindows(eq(content), anyInt(), anyInt())).thenAnswer(inv -> {
        int from = inv.getArgument(1);
        int count = inv.getArgument(2);
        windowStarts.add(from);
        pendingIngest.set(true);
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(count, NEW_VECTOR), from, 64);
      });
    }

    private BackfillScheduler scheduler() {
      return scheduler(runtime.commitOps());
    }

    private BackfillScheduler scheduler(CommitOps commitOps) {
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class), runtime.indexCountOps(), commitOps);
      lifecycle.setEmbeddingProvider(embedding);
      NerService ner = mock(NerService.class);
      when(ner.isAvailable()).thenReturn(true);
      ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
      var pacing = mock(ResolvedConfig.Ai.BackfillPacing.class);
      when(pacing.embeddingBackfillBatchSize()).thenReturn(batchSize);
      when(config.ai().backfillPacing()).thenReturn(pacing);
      return new BackfillScheduler(runtime.documentFieldOps(), coordinator, runtime.indexCountOps(), commitOps,
          signals, IndexingPacing.unthrottled(), lifecycle, new AtomicBoolean(true), () -> config,
          () -> null, () -> null, () -> ner, () -> null);
    }
  }

  private RunningRuntime open(Path path) {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("source_sha256", "embedding_retry_count", "splade_status",
        "splade_retry_count", "ner_status", "ner_retry_count")) {
      fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword",
          true, true, List.of("filter"), null, null, false));
    }
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("scheduler-persistence", fields))
        .atPath(path).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    runtime.commitOps().suspendNrtRefresh();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String id) {
    seed(runtime, id, id, Map.of());
  }

  private static void seed(RunningRuntime runtime, String id, String content, Map<String, Object> overrides) {
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
    fields.putAll(overrides);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void commitSeed(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static org.apache.lucene.document.Document stored(DirectoryReader reader, String id) throws Exception {
    var hits = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 2);
    assertEquals(1, hits.scoreDocs.length, id);
    return reader.storedFields().document(hits.scoreDocs[0].doc);
  }

  private static void assertDurable(Path path, String id, String status, float[] vector, String retries)
      throws Exception {
    try (var fs = FSDirectory.open(path);
        var reader = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
      var fields = stored(reader, id);
      assertEquals(status, fields.get(SchemaFields.EMBEDDING_STATUS), id);
      assertEquals(retries, fields.get(SchemaFields.EMBEDDING_RETRY_COUNT), id + " retries");
      assertEquals("COMPLETED_EMPTY", fields.get(SchemaFields.NER_STATUS));
      var hits = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1);
      int doc = hits.scoreDocs[0].doc;
      var leaf = reader.leaves().get(ReaderUtil.subIndex(doc, reader.leaves()));
      var vectors = leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
      var iterator = vectors == null ? null : vectors.iterator();
      boolean present = iterator != null && iterator.advance(doc - leaf.docBase) == doc - leaf.docBase;
      assertEquals(vector != null, present, id + " vector presence");
      if (vector != null) assertArrayEquals(vector, vectors.vectorValue(iterator.index()), id);
    }
  }
}
