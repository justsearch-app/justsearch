/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual admission, unchanged/force decisions, text extraction, producer and index. */
class IndexingDocumentOpsScalarIntegrationTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;
  private static final String QUALITY = SchemaFields.EXTRACTION_QUALITY_SCORE;

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @Test
  void changedFileReplacesScoreAndForcedUnchangedLegacyFileAcquiresOne() throws Exception {
    Path file = Files.writeString(tempDir.resolve("content.txt"), "a".repeat(31) + "!".repeat(69));
    String id = PathNormalizer.normalizeKey(file);
    Set<String> forced = new HashSet<>();
    try (var runtime = open(); var parser = new ContentExtractor();
        var timebox = new TimeboxedContentExtractor(TestWorkerExecutorRegistrations.timebox(), parser)) {
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "stable-uid", 1, 1));
      var queue = mock(JobQueue.class);
      when(queue.ownsClaimForPublication(any())).thenReturn(true);
      var journal = mock(IngestionOutcomeJournal.class);
      doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
          .when(journal).recordOutcomeSafely(any(), anyString(), any());
      var authority = new WorkerIngestionAuthority();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var fence = new FileMutationFence();
      var extractor = new JobBatchExtractor(authority, journal, queue, timebox,
          runtime.documentFieldOps(), runtime.indexCountOps(), stats, stale,
          mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), forced,
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false, delta -> {}, fence);
      var writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), null,
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, timebox,
          mock(OperationalMetrics.class), stats, stale, delta -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> null, fence);
      var claim = new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);

      // Proven unchanged source with a legacy missing quality score; ordinary ingestion keeps it missing.
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "stable-uid", SchemaFields.PATH, id,
          SchemaFields.MODIFIED_AT, Files.getLastModifiedTime(file).toMillis(),
          SchemaFields.SIZE_BYTES, Files.size(file),
          SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file),
          SchemaFields.CONTENT, "legacy content")));
      refresh(runtime);
      assertTrue(extractor.extractAll(List.of(claim)).isEmpty());
      verify(journal).recordOutcomeSafely(eq(file), eq("UNCHANGED"), any());
      assertNull(runtime.documentFieldOps().getDocumentField(id, QUALITY));

      // Explicit Rebuild Index uses the same forced-path set; unchanged input must be extracted.
      forced.add(id);
      var extracted = extractor.extractAll(List.of(claim));
      assertEquals(1, extracted.size());
      writer.write(extracted.getFirst(), null);
      refresh(runtime);
      String firstText = runtime.documentFieldOps().getDocumentContent(id);
      double firstScore = TextQualityAnalyzer.computeQualityScore(firstText);
      assertTrue(firstScore > 0.0 && firstScore < 1.0, "real extraction has a fractional score");
      assertNotEquals((double) (float) firstScore, firstScore, "real extraction separates double from float");
      assertEquals(Double.toString(firstScore), runtime.documentFieldOps().getDocumentField(id, QUALITY));

      long oldMtime = Files.getLastModifiedTime(file).toMillis();
      Files.writeString(file, "changed readable content with many letters ".repeat(10));
      Files.setLastModifiedTime(file, FileTime.fromMillis(oldMtime + 2000));
      extracted = extractor.extractAll(List.of(claim));
      assertEquals(1, extracted.size());
      writer.write(extracted.getFirst(), null);
      refresh(runtime);
      double changedScore = TextQualityAnalyzer.computeQualityScore(runtime.documentFieldOps().getDocumentContent(id));
      assertNotEquals(firstScore, changedScore);
      assertEquals(Double.toString(changedScore), runtime.documentFieldOps().getDocumentField(id, QUALITY));
    }
    try (var runtime = open()) {
      String text = runtime.documentFieldOps().getDocumentContent(id);
      assertEquals(Double.toString(TextQualityAnalyzer.computeQualityScore(text)),
          runtime.documentFieldOps().getDocumentField(id, QUALITY));
    }
  }
}
