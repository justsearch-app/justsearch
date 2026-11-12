/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual Worker traversal and file-backed volume ledger; only queue/runtime and platform lookup are fixtures. */
final class RootVolumeResumePrerequisiteTest {
  @TempDir Path directory;

  @Test void acceptedAddStartsAfterRestartBeforeFirstWalkAndRecordsItsVolume() throws Exception {
    Path root = Files.createDirectory(directory.resolve("accepted-import"));
    Files.writeString(root.resolve("document.txt"), "accepted before worker traversal");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      var before = RootVolumeLedger.under(directory.resolve("index"));
      assertTrue(before.recordedFor(root).isEmpty());
      var scan = scan(root, 1, 1);
      assertEquals("", scan.getLast().getTerminalReasonCode());
      assertEquals(1, scan.getLast().getFilesWalked());
      assertEquals(Optional.of(RootIdentity.capture(root).volume()),
          RootVolumeLedger.under(directory.resolve("index")).recordedFor(root));
    }
  }

  @Test void crashAfterOpeningEmptyEpochStillFinishesTheAcceptedAdd() throws Exception {
    Path root = Files.createDirectory(directory.resolve("accepted-import"));
    Files.writeString(root.resolve("document.txt"), "accepted");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      assertEquals("", scan(root, 2, 2).getLast().getTerminalReasonCode());
      assertTrue(RootVolumeLedger.under(directory.resolve("index")).recordedFor(root).isPresent());
    }
  }

  @Test void previouslyRecordedReplacementIsNeverReadded() throws Exception {
    Path root = Files.createDirectory(directory.resolve("accepted-import"));
    Files.writeString(root.resolve("document.txt"), "replacement");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      var ledger = RootVolumeLedger.under(directory.resolve("index"));
      ledger.record(root, "windows-volume:deadbeef");
      assertEquals("ROOT_NOT_DIRECTORY", scan(root, 1, 1).getLast().getTerminalReasonCode());
      assertEquals(Optional.of("windows-volume:deadbeef"),
          RootVolumeLedger.under(directory.resolve("index")).recordedFor(root));
    }
  }

  @Test void admittedWalkWithLostVolumeEvidenceCannotReadd() throws Exception {
    Path root = Files.createDirectory(directory.resolve("accepted-import"));
    Files.writeString(root.resolve("document.txt"), "accepted");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      assertEquals("ROOT_NOT_DIRECTORY", scan(root, 2, 3).getLast().getTerminalReasonCode());
      assertTrue(RootVolumeLedger.under(directory.resolve("index")).recordedFor(root).isEmpty());
    }
  }

  @Test void unavailableAcceptedFolderRecordsNothing() throws Exception {
    Path root = directory.resolve("unavailable");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(true)) {
      assertThrows(JobQueue.RecordedWalkGapException.class, () -> scan(root, 1, 1));
      assertTrue(RootVolumeLedger.under(directory.resolve("index")).recordedFor(root).isEmpty());
    }
  }

  private List<ScanRootProgress> scan(Path root, long epoch, long revision) throws Exception {
    var layout = new IndexGenerationManager(directory.resolve("index")).initializeOrLoad();
    String generation = layout.activeGenerationPath().getFileName().toString();
    var queue = mock(JobQueue.class);
    when(queue.recordedWalk("accepted-child")).thenReturn(Optional.of(new JobQueue.WalkProgress(
        "accepted-child", "a".repeat(64), epoch, null, null, 0, 0, revision,
        null, null, 0, false, null, null)));
    when(queue.allowsIngestionBoundary(any(), anyString(), any())).thenReturn(true);
    when(queue.enqueueRecordedEntries(anyString(), anyLong(), anyList(), any()))
        .thenAnswer(call -> ((List<?>) call.getArgument(2)).size());
    var runtime = mock(RunningRuntime.class);
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
        layout.basePath(), layout.activeGenerationPath(), runtime, runtime, null, 0L);
    var request = ScanRootRequest.newBuilder().setRootPath(root.toString()).build();
    var recorded = new WorkerIngestService.RecordedRootScan(request, "accepted-child", epoch,
        generation, false, List.of(), WorkerIngestService.RecordedScanMode.RECOVERED);
    var events = new ArrayList<ScanRootProgress>();
    service.scanRecordedRoot(recorded, events::add, CallContext.none());
    return events;
  }
}
