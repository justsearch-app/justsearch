/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Recorded traversal preserves exact admission, frozen ownership and cancellation. */
final class WorkerScanOpsRecordedTest {

  private static final String KEY = "01994180-0000-7000-8000-000000000901";
  private static final long EPOCH = 7L;
  private static final String COLLECTION = "recorded-docs";
  private static final JobQueue.EnqueueProvenance PROVENANCE =
      new JobQueue.EnqueueProvenance("agent", "RECORDED_SCAN_TEST");

  @TempDir Path tempDir;

  @Test
  void recordedAdmissionPreservesKeyEpochCollectionAndProvenance() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("recorded"));
    Path file = Files.writeString(root.resolve("entry.txt"), "recorded");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    List<JobQueue.EnqueueEntry> admitted = new ArrayList<>();
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION)))
        .thenAnswer(
            invocation -> {
              List<JobQueue.EnqueueEntry> entries = invocation.getArgument(2);
              admitted.addAll(entries);
              return entries.size();
            });

    ScanRootProgress terminal =
        guardedScan(queue)
            .scan(recordedRequest(root, List.of()), ignored -> {});

    assertEquals(1L, terminal.getFilesAdmitted());
    assertEquals(1, admitted.size());
    assertEquals(file, admitted.get(0).path());
    assertEquals(Files.size(file), admitted.get(0).sizeBytes());
    assertEquals(PROVENANCE, admitted.get(0).provenance());
    verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION));
    verify(queue, never()).enqueueEntries(anyList(), eq(COLLECTION));
  }

  @Test
  void recordedSingleFileUsesRecordedAdmissionWithItsFrozenContext() throws Exception {
    Path file = Files.writeString(tempDir.resolve("single.txt"), "one");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    AtomicReference<List<JobQueue.EnqueueEntry>> admitted = new AtomicReference<>();
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION)))
        .thenAnswer(
            invocation -> {
              List<JobQueue.EnqueueEntry> entries = invocation.getArgument(2);
              admitted.set(List.copyOf(entries));
              return entries.size();
            });

    ScanRootProgress terminal =
        guardedScan(queue)
            .scan(
                new WorkerScanOps.ScanRequest(
                    file,
                    COLLECTION,
                    WorkerScanOps.ScanMode.INITIAL,
                    List.of(),
                    KEY,
                    PROVENANCE,
                    EPOCH,
                    List.of(),
                    true),
                ignored -> {});

    assertEquals(1L, terminal.getFilesAdmitted());
    assertEquals(List.of(file), admitted.get().stream().map(JobQueue.EnqueueEntry::path).toList());
    assertEquals(PROVENANCE, admitted.get().get(0).provenance());
    verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION));
    verify(queue, never()).enqueueEntries(anyList(), eq(COLLECTION));
  }

  @Test
  void frozenExcludedDirectoryAndFileArePrunedBeforeCloudLedger() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("ownership"));
    Path excludedDir = Files.createDirectory(root.resolve("private"));
    Path excludedNested = Files.writeString(excludedDir.resolve("nested.txt"), "private");
    Path excludedFile = Files.writeString(root.resolve("excluded-cloud.txt"), "cloud");
    Path included = Files.writeString(root.resolve("included.txt"), "keep");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION)))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(2)).size());

    WorkerScanOps ops =
        new WorkerScanOps(
            queue,
            new CloudPlaceholderRecorder(queue),
            path -> path.equals(excludedFile) || path.equals(excludedNested),
            () -> 0L,
            () -> false,
            millis -> true,
            paths -> {}, () -> {});
    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(root, COLLECTION, WorkerScanOps.ScanMode.FORCE_REINDEX,
                List.of(), KEY, PROVENANCE, EPOCH, List.of(excludedDir, excludedFile)),
            ignored -> {});

    assertEquals(1L, terminal.getFilesAdmitted());
    verify(queue, never())
        .recordIngestionEvent(
            any(Path.class),
            any(),
            any());
    verify(queue, never()).enqueueEntries(anyList(), eq(COLLECTION));
    // The only recorded entry is the owned path; neither frozen exclusion can enter the queue.
    org.mockito.ArgumentCaptor<List<JobQueue.EnqueueEntry>> batches =
        org.mockito.ArgumentCaptor.captor();
    verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), batches.capture(), eq(COLLECTION));
    assertEquals(
        List.of(included), batches.getValue().stream().map(JobQueue.EnqueueEntry::path).toList());
  }

  @Test
  void frozenGlobListStillExcludesAfterTheCallerChangesItsList() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("frozen"));
    Files.writeString(root.resolve("excluded.txt"), "excluded");
    Path included = Files.writeString(root.resolve("included.md"), "included");
    var globs = new ArrayList<>(List.of("*.txt"));
    var request = recordedRequest(root, globs);
    globs.clear();
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    AtomicReference<List<JobQueue.EnqueueEntry>> admitted = new AtomicReference<>();
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION)))
        .thenAnswer(invocation -> {
          List<JobQueue.EnqueueEntry> entries = invocation.getArgument(2);
          admitted.set(List.copyOf(entries));
          return entries.size();
        });
    var terminal = guardedScan(queue).scan(request, ignored -> {});
    assertEquals(1, terminal.getFilesAdmitted());
    assertEquals(List.of(included), admitted.get().stream().map(JobQueue.EnqueueEntry::path).toList());
  }

  @Test
  void failedMemberAfterAdmittedBatchCannotReportCompleteEnumeration() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("unreadable"));
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION)))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(2)).size());
    var attributes = org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
    when(attributes.isRegularFile()).thenReturn(true);
    when(attributes.size()).thenReturn(5L);
    List<ScanRootProgress> frames = new ArrayList<>();
    try (var files = org.mockito.Mockito.mockStatic(Files.class)) {
      files.when(() -> Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(true);
      files.when(() -> Files.isReadable(any())).thenReturn(true);
      files.when(() -> Files.walkFileTree(eq(root), any(), anyInt(), any()))
          .thenAnswer(invocation -> {
            java.nio.file.FileVisitor<Path> visitor = invocation.getArgument(3);
            for (int i = 0; i < 2000; i++) visitor.visitFile(root.resolve("entry-" + i + ".txt"), attributes);
            verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION));
            visitor.visitFileFailed(root.resolve("denied"), new java.io.IOException("injected access failure"));
            return root;
          });
      var failure = assertThrows(java.io.IOException.class,
          () -> guardedScan(queue).scan(recordedRequest(root, List.of()), frames::add));
      assertEquals("injected access failure", failure.getMessage());
    }
    assertFalse(frames.stream().anyMatch(ScanRootProgress::getComplete));
    org.mockito.ArgumentCaptor<List<JobQueue.EnqueueEntry>> admitted = org.mockito.ArgumentCaptor.captor();
    verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), admitted.capture(), eq(COLLECTION));
    assertEquals(2000, admitted.getValue().size());
  }

  @Test
  void unreadableRegularMemberRefusesInsteadOfCompletingAsSkipped() throws Exception {
    Path root = tempDir.resolve("unreadable-root");
    Path member = root.resolve("entry.txt");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    var attributes = org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
    when(attributes.isRegularFile()).thenReturn(true);
    List<ScanRootProgress> frames = new ArrayList<>();
    try (var files = org.mockito.Mockito.mockStatic(Files.class)) {
      files.when(() -> Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(true);
      files.when(() -> Files.isReadable(member)).thenReturn(false);
      files.when(() -> Files.walkFileTree(eq(root), any(), anyInt(), any()))
          .thenAnswer(invocation -> {
            java.nio.file.FileVisitor<Path> visitor = invocation.getArgument(3);
            visitor.visitFile(member, attributes);
            return root;
          });
      var failure = assertThrows(java.io.IOException.class,
          () -> guardedScan(queue).scan(recordedRequest(root, List.of()), frames::add));
      assertEquals("Recorded member is unreadable", failure.getMessage());
    }
    assertTrue(frames.isEmpty());
    org.mockito.Mockito.verifyNoInteractions(queue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleFileChangedToDirectoryOrLinkCannotBroadenOrComplete(boolean link) throws Exception {
    Path root = tempDir.resolve("changed.txt");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    var attributes = org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
    when(attributes.isDirectory()).thenReturn(!link);
    when(attributes.isSymbolicLink()).thenReturn(link);
    List<ScanRootProgress> frames = new ArrayList<>();
    try (var files = org.mockito.Mockito.mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)).thenReturn(true);
      files.when(() -> Files.walkFileTree(eq(root), any(), anyInt(), any()))
          .thenAnswer(invocation -> {
            assertEquals(0, (int) invocation.getArgument(2), "file traversal must be bounded to the start node");
            java.nio.file.FileVisitor<Path> visitor = invocation.getArgument(3);
            visitor.visitFile(root, attributes);
            return root;
          });
      assertThrows(java.io.IOException.class, () -> guardedScan(queue).scan(
          new WorkerScanOps.ScanRequest(root, COLLECTION, WorkerScanOps.ScanMode.INITIAL,
              List.of(), KEY, PROVENANCE, EPOCH, List.of(), true), frames::add));
    }
    assertTrue(frames.isEmpty());
    org.mockito.Mockito.verifyNoInteractions(queue);
  }

  @Test
  void recordedAdmissionWithoutGenerationGuardIsDenied() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("unguarded"));
    Files.writeString(root.resolve("entry.txt"), "entry");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    var failure = assertThrows(WorkerServiceException.class,
        () -> new WorkerScanOps(queue).scan(recordedRequest(root, List.of()), ignored -> {}));
    assertEquals("RECORDED_GENERATION_GUARD_REQUIRED", failure.getMessage());
    verify(queue, never()).enqueueRecordedEntries(anyString(), anyLong(), anyList(), any());
  }

  @Test
  void malformedRecordedGlobRefusesBeforeAnyQueueOrLedgerEffect() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("malformed"));
    Files.writeString(root.resolve("entry.txt"), "entry");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> guardedScan(queue).scan(recordedRequest(root, List.of("[")), ignored -> {}));

    org.mockito.Mockito.verifyNoInteractions(queue);
  }

  @Test
  void partialRecordedAdmissionFailsInsteadOfReportingCompletion() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("partial"));
    Files.writeString(root.resolve("entry.txt"), "entry");
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    when(queue.enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION))).thenReturn(0);
    List<ScanRootProgress> progress = new ArrayList<>();

    WorkerServiceException failure =
        assertThrows(
            WorkerServiceException.class,
            () -> guardedScan(queue).scan(recordedRequest(root, List.of()), progress::add));

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
    assertFalse(progress.stream().anyMatch(ScanRootProgress::getComplete));
    verify(queue).enqueueRecordedEntries(eq(KEY), eq(EPOCH), anyList(), eq(COLLECTION));
  }

  @ParameterizedTest(name = "cancelled recorded scan with {0} files does not admit full or final batch")
  @ValueSource(ints = {2000, 1})
  void cancellationAfterHighWaterBackpressureSkipsFullOrFinalAdmission(int fileCount)
      throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("cancel-" + fileCount));
    for (int i = 0; i < fileCount; i++) {
      Files.writeString(root.resolve("entry-" + i + ".txt"), "entry");
    }
    JobQueue queue = org.mockito.Mockito.mock(JobQueue.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    WorkerScanOps ops =
        new WorkerScanOps(
            queue,
            new CloudPlaceholderRecorder(queue),
            path -> false,
            () -> WorkerScanOps.QUEUE_HIGH_WATERMARK,
            cancelled::get,
            millis -> {
              cancelled.set(true);
              return true;
            },
            paths -> {}, () -> {});

    ScanRootProgress terminal = ops.scan(recordedRequest(root, List.of()), ignored -> {});

    assertTrue(cancelled.get(), "backpressure waiter must observe cancellation");
    assertEquals("CLIENT_CANCELLED", terminal.getTerminalReasonCode());
    assertEquals(0L, terminal.getFilesAdmitted());
    verify(queue, never()).enqueueRecordedEntries(anyString(), anyLong(), anyList(), any());
    verify(queue, never()).enqueueEntries(anyList(), any());
  }

  private static WorkerScanOps guardedScan(JobQueue queue) {
    return new WorkerScanOps(queue, new CloudPlaceholderRecorder(queue), path -> false,
        () -> 0L, () -> false, millis -> true, paths -> {}, () -> {});
  }

  private static WorkerScanOps.ScanRequest recordedRequest(Path root, List<String> globs) {
    return new WorkerScanOps.ScanRequest(
        root, COLLECTION, WorkerScanOps.ScanMode.INITIAL, globs, KEY, PROVENANCE, EPOCH, List.of());
  }
}
