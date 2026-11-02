/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayList;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * b71: the bulk write path declares each parent's chunk set in the parent document it writes. It
 * must not rewrite the parent per document (a blocking searcher refresh, a read-back and a
 * superseded parent version each), and the declaration must still certify complete sets.
 */
final class BulkWriteChunkDeclarationTest extends LuceneExecutorTestBase {
  private static final int SHORT = 40;
  private static final int CHUNKED = 3;
  private static final long MODIFIED = 1_700_000_000_000L;

  @TempDir Path tempDir;

  @Test
  void rejectedInlineVectorsNeverEarnFirstCompatibilityEvidence() throws Exception {
    io.justsearch.indexerworker.embed.EmbeddingFingerprint.setForTesting("inline-model");
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(tempDir.resolve("inline"))
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      var ecc = new io.justsearch.indexerworker.embed.EmbeddingCompatibilityController(java.util.Map::of, () -> 0L);
      ecc.refresh();
      var lifecycle = mock(EmbeddingProviderLifecycle.class);
      when(lifecycle.embeddingCompatController()).thenReturn(ecc);
      var stats = mock(BatchStats.class);
      var writer = writer(runtime, stats, lifecycle);
      for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
        Path file = tempDir.resolve("bad-" + Float.floatToIntBits(bad) + ".txt");
        writer.write(job(file, "retained lexical text"), new float[] {bad, 1, 0, 0});
        runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
        assertEquals("FAILED", runtime.documentFieldOps().getDocumentField(
            PathNormalizer.normalizeKey(file), SchemaFields.EMBEDDING_STATUS));
        assertTrue(ecc.fingerprintToStamp().isEmpty(), "the field mapper rejected the only vector");
      }
      writer.write(job(tempDir.resolve("good.txt"), "valid inline vector"), new float[] {1, 0, 0, 0});
      assertTrue(ecc.fingerprintToStamp().isPresent());
      writer.write(job(tempDir.resolve("late-bad.txt"), "rejected after success"), new float[] {Float.NaN, 1, 0, 0});
      assertTrue(ecc.fingerprintToStamp().isPresent(), "later rejection must not erase real success");
      verify(stats, never()).recordFailed();
    } finally {
      io.justsearch.indexerworker.embed.EmbeddingFingerprint.invalidate();
    }
  }

  @Test
  void bulkWritesNeedNoPerDocumentRefreshOrParentRewrite() throws Exception {
    Path index = tempDir.resolve("index");
    var ids = new ArrayList<String>();
    var contents = new ArrayList<String>();
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(index)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      // Only refreshes the write path itself performs are counted.
      runtime.commitOps().suspendNrtRefresh();
      var batchStats = mock(BatchStats.class);
      var writer = writer(runtime, batchStats);
      long reopensBefore = runtime.runtimeGaugesSnapshot().reopenCount();
      for (int i = 0; i < SHORT + CHUNKED; i++) {
        boolean chunked = i < CHUNKED;
        String content = chunked
            ? ("chunked parent " + i + " alpha beta gamma delta\n\n").repeat(500)
            : "short parent " + i + " body text";
        Path file = tempDir.resolve("docs").resolve("doc-" + i + ".txt");
        writer.write(job(file, content), null);
        ids.add(PathNormalizer.normalizeKey(file));
        contents.add(content);
      }
      long reopens = runtime.runtimeGaugesSnapshot().reopenCount() - reopensBefore;
      verify(batchStats, never()).recordFailed();
      assertTrue(reopens <= 1, "writing " + (SHORT + CHUNKED)
          + " parents must not refresh the searcher per document; observed " + reopens);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      for (int i = 0; i < ids.size(); i++) {
        String content = contents.get(i);
        int expected = runtime.documentFieldOps()
            .queryDocIdsByField(SchemaFields.PARENT_DOC_ID, ids.get(i), 10_000).size();
        if (i < CHUNKED) assertTrue(expected > 1, "chunked fixture " + i + " must have chunks");
        else assertEquals(0, expected, "short fixture " + i + " has no chunks");
        assertEquals(String.valueOf(expected),
            runtime.documentFieldOps().getDocumentField(ids.get(i), SchemaFields.CHUNK_TOTAL));
        assertEquals(ChunkParentRevision.sha256Hex(content),
            runtime.documentFieldOps().getDocumentField(ids.get(i), SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
        assertTrue(runtime.documentFieldOps().isUnmodified(ids.get(i), MODIFIED),
            "a written complete set retains unchanged admission: " + ids.get(i));
      }
    }
    try (var directory = FSDirectory.open(index);
        var reader = new SoftDeletesDirectoryReaderWrapper(
            DirectoryReader.open(directory), SchemaFields.SOFT_DELETE)) {
      assertEquals(0, reader.maxDoc() - reader.numDocs(),
          "new documents must not leave a superseded parent version each");
    }
  }

  private static JobBatchWriter writer(RunningRuntime runtime, BatchStats batchStats) {
    return writer(runtime, batchStats, mock(EmbeddingProviderLifecycle.class));
  }

  private static JobBatchWriter writer(RunningRuntime runtime, BatchStats batchStats,
      EmbeddingProviderLifecycle lifecycle) {
    var jobQueue = mock(JobQueue.class);
    when(jobQueue.ownsClaimForPublication(any())).thenReturn(true);
    var extractor = mock(TimeboxedContentExtractor.class);
    when(extractor.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    return new JobBatchWriter(new WorkerIngestionAuthority(),
        runtime.indexingCoordinator(),
        mock(WorkerSignalBus.class),
        lifecycle,
        () -> null,
        mock(IngestionOutcomeJournal.class),
        jobQueue,
        extractor,
        mock(OperationalMetrics.class),
        batchStats,
        mock(StaleSnapshotResolver.class),
        delta -> {},
        mock(IndexingDocumentOps.StageRecorder.class),
        () -> false,
        () -> false,
        () -> null,
        new FileMutationFence());
  }

  private static ExtractedJob job(Path file, String content) {
    var artifact = TestDocumentBuilder.validatedArtifact(
        new ExtractionResult(content, file.getFileName().toString(), "text/plain"));
    var envelope = new FileEnvelope(file, PathNormalizer.normalizeKey(file), "hash-" + file.getFileName(),
        null, content.length(), MODIFIED, true, MODIFIED);
    return new ExtractedJob(file, null, artifact, System.currentTimeMillis(), envelope,
        null, "uid-" + file.getFileName());
  }
}
