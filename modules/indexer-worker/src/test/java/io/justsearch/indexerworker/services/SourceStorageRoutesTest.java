/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source storage evidence applies to content reads and admissions, including underlying replacements. */
@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class SourceStorageRoutesTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"scan", "forced-scan", "captured-scan", "sync", "forced-sync", "watcher", "candidate", "explicit-candidate", "recorded-candidate", "boot", "candidate-reconcile"})
  void unavailableDescendantStorageNeverAdmitsUnderlyingFile(String route) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path child = Files.createDirectory(root.resolve("child"));
    Path file = Files.writeString(child.resolve("notes.txt"), "underlying unrelated bytes");
    Path base = temporary.resolve("base");
    var layout = new IndexGenerationManager(base).initializeOrLoad();
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"));
        var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(layout.activeGenerationPath()).withExecutorRegistrations(testLuceneExecutors()).open();
        var candidate = route.equals("candidate-reconcile") ? IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(new IndexGenerationManager(base).resolveGenerationPathStrict(
                new IndexGenerationManager(base).startMigration("storage-check").building_generation()))
            .withExecutorRegistrations(testLuceneExecutors()).open() : null) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), base,
          layout.activeGenerationPath(), candidate == null ? runtime : candidate, runtime, null, 0L);
      service.useExclusionRoots(() -> List.of(root));
      service.setForcedPathSink(paths -> {});
      var ledger = RootVolumeLedger.under(base);
      ledger.record(root, RootIdentity.capture(root).volume());
      ledger.recordIfAbsent(child, "linux-volume-v1:740:ext4:Lw");
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        if (route.endsWith("scan")) {
          var frames = new ArrayList<ScanRootProgress>();
          var request = ScanRootRequest.newBuilder().setRootPath(root.toString()).setRootAdded(true)
              .setMode(route.equals("scan") ? ScanMode.SCAN_MODE_INITIAL : ScanMode.SCAN_MODE_FORCE_REINDEX).build();
          if (route.equals("captured-scan")) {
            String key = "storage-captured-walk";
            long epoch = queue.beginCapturedWalk(key, "a".repeat(64), true).enumerationEpoch();
            service.scanRecordedRoot(new WorkerIngestService.RecordedRootScan(request, key, epoch,
                layout.state().active_generation(), false, List.of(), WorkerIngestService.RecordedScanMode.CAPTURED),
                frames::add, CallContext.none());
          } else service.scanRoot(request, frames::add, CallContext.none());
          assertFalse(frames.getLast().getTerminalReasonCode().isBlank(), "unavailable storage prevents clean coverage");
        } else if (route.endsWith("sync")) {
          var response = service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(root.toString())
              .setForce(route.equals("forced-sync")).build(), CallContext.none());
          assertTrue(response.getDeleteDetectionUnverified() || !response.getError().isBlank(),
              "an unavailable descendant cannot report verified discovery");
        } else if (route.equals("watcher")) {
          var witness = new RootWatcherRegistry.Subscription(root, "docs", new Object(), RootIdentity.capture(root));
          assertThrows(WorkerServiceException.class, () -> service.acceptWatcherUpsert(witness, "docs", file));
        } else if (route.equals("boot")) {
          assertFalse(service.reconcileCommittedBootRoot(
              new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, null), () -> false));
        } else if (route.equals("candidate-reconcile")) {
          assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(root.toString()).build(),
              CallContext.none()).getWatching());
          assertThrows(WorkerServiceException.class, () -> service.syncDirectory(
              SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(true).build(), CallContext.none()));
        } else if (route.equals("candidate") || route.equals("explicit-candidate")) {
          assertEquals(0, queue.enqueueAndBufferFilesForGeneration("g-candidate",
              List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(route.equals("explicit-candidate") ? file : root)), "docs", null));
        } else {
          long epoch = queue.beginRecordedWalk("storage-recorded-walk", "a".repeat(64), true).enumerationEpoch();
          assertThrows(IllegalStateException.class, () -> queue.enqueueRecordedEntriesAndBufferForGeneration(
              "g-candidate", "storage-recorded-walk", epoch,
              List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)), "docs"));
        }
        assertEquals(0, queue.queueDepth(), "unavailable child storage never overwrites original indexed bytes");
        hashes.verifyNoInteractions();
      }
    }
  }
}
