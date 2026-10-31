/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.ingest.CloudPlaceholderDetector;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CandidateSourceAdmissionSafetyTest {
  @TempDir Path temporary;

  @Test
  void candidateAdmissionKeepsConsecutiveDotsInsideNames() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("version..notes"));
    Path file = Files.writeString(root.resolve("release..notes.txt"), "readable source");
    try (var queue = new SqliteJobQueue(temporary.resolve("dots.db"))) {
      queue.open();
      assertEquals(1, queue.enqueueAndBufferFilesForGeneration("g-candidate",
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs", null));
      var admitted = queue.pollPending(1).getFirst();
      assertEquals(file, admitted.path());
      assertEquals("docs", admitted.collection());
    }
  }

  @Test
  void candidateAdmissionRejectsReplacedAncestorBeforeHashing() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path parent = Files.createDirectory(root.resolve("parent"));
    Path file = Files.writeString(parent.resolve("notes.txt"), "inside");
    var entry = new JobQueue.EnqueueEntry(file, 6).withinRoot(root);
    Path outside = Files.createDirectory(temporary.resolve("outside"));
    Files.writeString(outside.resolve("notes.txt"), "outside");
    Files.delete(file);
    Files.delete(parent);
    Files.createSymbolicLink(parent, outside);
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"));
        var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      queue.open();
      assertEquals(0, queue.enqueueAndBufferFilesForGeneration("g-candidate", List.of(entry), "docs", null));
      hashes.verify(() -> SourceContentHash.sha256(any(Path.class)), never());
      assertTrue(queue.pollPending(10).isEmpty());
    }
  }

  @Test
  void candidatePlaceholderAdmissionDefersWithoutHashing() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("cloud"));
    Path file = Files.writeString(root.resolve("notes.txt"), "cloud metadata fixture");
    try (var queue = new SqliteJobQueue(temporary.resolve("cloud.db"));
        var detector = mockStatic(CloudPlaceholderDetector.class);
        var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
      queue.open();
      detector.when(() -> CloudPlaceholderDetector.isCloudPlaceholder(file)).thenReturn(true);
      assertEquals(0, queue.enqueueAndBufferFilesForGeneration("g-candidate",
          List.of(new JobQueue.EnqueueEntry(file, 22).withinRoot(root)), "docs", null));
      hashes.verifyNoInteractions();
      assertTrue(queue.hasRecentLedgerEvent(
          io.justsearch.indexerworker.identity.PathHash.sha256(file.toString()),
          IngestionReasonCodes.CLOUD_PLACEHOLDER, 0));
      assertTrue(queue.pollPending(10).isEmpty());
    }
  }

  @Test
  void liveWatcherPlaceholderIsObservableBeforeQueueAdmission() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("watch"));
    Path file = Files.writeString(root.resolve("notes.txt"), "cloud metadata fixture");
    JobQueue queue = mock(JobQueue.class);
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        null, null, null, null, null, 0L);
    var witness = new RootWatcherRegistry.Subscription(root, "docs", new Object(), RootIdentity.capture(root));
    try (var detector = mockStatic(SyncDirectoryOps.class, CALLS_REAL_METHODS)) {
      detector.when(() -> SyncDirectoryOps.isCloudPlaceholder(file)).thenReturn(true);
      service.acceptWatcherUpsert(witness, "docs", file);
      verify(queue).recordIngestionEvent(eq(file),
          argThat(outcome -> IngestionReasonCodes.CLOUD_PLACEHOLDER.equals(outcome.reasonCode())),
          argThat(ledger -> "docs".equals(ledger.collection())));
      verify(queue, never()).enqueueEntries(anyList(), any());
    }
  }
}
