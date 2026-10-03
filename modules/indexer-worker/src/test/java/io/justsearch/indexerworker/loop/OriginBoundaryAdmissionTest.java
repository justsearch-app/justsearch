/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.UnwatchRootRequest;
import io.justsearch.ipc.WatchRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real scan admission, durable queue, and batch consumption share the admitting boundary. */
final class OriginBoundaryAdmissionTest {
  @TempDir Path tempDir;

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void unwatchedScanKeepsBoundaryAcrossRestartAndWatchRemoval() throws Exception {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    Path root = Files.createDirectories(tempDir.resolve("private").resolve("watched"));
    Path file = Files.writeString(root.resolve("notes.txt"), "admitted content");
    Files.writeString(Files.createDirectory(root.resolve("private")).resolve("hidden.txt"), "excluded");
    try (var queue = new SqliteJobQueue(tempDir.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, mock(IndexingLoop.class), null,
          IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      service.scanRoot(ScanRootRequest.newBuilder().setRootPath(root.toString())
          .setMode(ScanMode.SCAN_MODE_INITIAL).build(), ignored -> {}, CallContext.none());

      var claim = queue.pollPending(10).getFirst();
      assertEquals(1, queue.queueDepth(), "The excluded descendant was pruned by the scan");
      assertEquals(root, claim.ingestionRoot());
      assertEquals(file, claim.path());
      assertConsumedWithoutDirectoryExclusion(claim);
      queue.returnUnfinishedClaims(List.of(claim));

      assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
          CallContext.none()).getWatching());
      assertTrue(service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
          CallContext.none()).getUnwatched());
      queue.close();
      queue.open();

      var recovered = queue.pollPending(10).getFirst();
      assertEquals(root, recovered.ingestionRoot());
      assertConsumedWithoutDirectoryExclusion(recovered);
      queue.returnUnfinishedClaims(List.of(recovered));
    }
  }

  private void assertConsumedWithoutDirectoryExclusion(JobQueue.IndexJob claim) throws Exception {
    var fields = mock(DocumentFieldOps.class);
    when(fields.isUnmodified(anyString(), anyLong())).thenReturn(true);
    var counts = mock(IndexCountOps.class);
    when(counts.docCount()).thenReturn(7L);
    var content = mock(TimeboxedContentExtractor.class);
    when(content.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
    var identity = mock(DocumentIdentityStore.class);
    when(identity.resolve(anyString(), anyLong()))
        .thenReturn(new DocumentIdentityStore.Identity("hash", "uid", 1L, 1L));
    var extractor = new JobBatchExtractor(new WorkerIngestionAuthority(),
        mock(IngestionOutcomeJournal.class), mock(JobQueue.class), content, fields, counts,
        mock(BatchStats.class), mock(StaleSnapshotResolver.class), mock(StaleSourceHandler.class),
        IndexingPacing.unthrottled(), new AtomicBoolean(true), new java.util.HashSet<>(),
        () -> mock(PathResolutionStore.class), () -> identity,
        mock(IndexingDocumentOps.StageRecorder.class), () -> false, ignored -> {});

    extractor.extractAll(List.of(claim));

    // Reaching the index freshness check proves production consumer admission accepted this file.
    verify(fields).isUnmodified(eq(PathNormalizer.normalizeKey(claim.path())), anyLong());
  }
}
