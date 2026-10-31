/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SyncDirectoryOpsChangedStampTest {
  @TempDir Path temporary;


  @org.junit.jupiter.api.Test
  void failedLaterCensusPageWithholdsVerificationAndStillWalksDisk() throws Exception {
    Path added = Files.writeString(temporary.resolve("added.txt"), "new");
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0, "next"))
        .thenThrow(new IllegalStateException("later page unavailable"));
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    var result = ops.execute(temporary.toString(), false, null, () -> false, null);
    assertEquals("", result.getError());
    assertTrue(result.getDeleteDetectionUnverified());
    assertEquals(1, result.getFilesAdded());
    verify(queue).enqueueEntries(argThat(entries -> entries.size() == 1 && entries.getFirst().path().equals(added)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"paths", "characters"})
  void censusCapsStillAllowPeriodicRecoveryOfAdditionsAndEdits(String cap) throws Exception {
    Path changed = Files.writeString(temporary.resolve("changed.txt"), "changed bytes");
    Path added = Files.writeString(temporary.resolve("added.txt"), "new");
    var hits = new ArrayList<LuceneRuntimeTypes.SearchHit>();
    hits.add(new LuceneRuntimeTypes.SearchHit(changed.toString(), 1f,
        java.util.Map.of(SchemaFields.SIZE_BYTES, "1", SchemaFields.MODIFIED_AT, "0")));
    hits.add(new LuceneRuntimeTypes.SearchHit(temporary.resolve("old.txt").toString(), 1f, java.util.Map.of()));
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(hits, 2, 0));
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled(), null,
        cap.equals("paths") ? 1 : 200_000, cap.equals("characters") ? changed.toString().length() : 16 * 1024 * 1024);
    for (int attempt = 0; attempt < 2; attempt++) {
      var result = ops.execute(temporary.toString(), false, null, () -> false, null);
      assertEquals("", result.getError());
      assertTrue(result.getDeleteDetectionUnverified());
      assertEquals(2, result.getFilesAdded());
    }
    verify(queue, times(2)).enqueueEntries(argThat(entries ->
        entries.stream().map(JobQueue.EnqueueEntry::path).collect(java.util.stream.Collectors.toSet())
            .equals(java.util.Set.of(added, changed))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "mtime", "missing-size"})
  void ordinaryReconciliationAdmitsChangedIndexedFiles(String change) throws Exception {
    check(change, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"size", "mtime", "missing-size"})
  void candidateReconciliationAdmitsChangedIndexedFiles(String change) throws Exception {
    check(change, true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unchangedIndexedFilesStaySkipped(boolean candidate) throws Exception {
    check("unchanged", candidate);
  }

  private void check(String change, boolean candidate) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "original");
    var stamp = Files.getLastModifiedTime(file);
    String id = PathNormalizer.normalizeKey(file);
    var fields = new HashMap<String, String>();
    fields.put(SchemaFields.PATH, id);
    fields.put(SchemaFields.MODIFIED_AT, String.valueOf(stamp.toMillis()));
    if (!change.equals("missing-size")) fields.put(SchemaFields.SIZE_BYTES, String.valueOf(Files.size(file)));
    if (change.equals("size")) {
      Files.writeString(file, "replacement with a different size");
      Files.setLastModifiedTime(file, stamp);
    } else if (change.equals("mtime")) {
      Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(stamp.toMillis() + 20_000));
    }
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(
            List.of(new LuceneRuntimeTypes.SearchHit(id, 1.0f, fields)), 1, 0));
    var queue = mock(JobQueue.class);
    var admitted = new ArrayList<Path>();
    org.mockito.stubbing.Answer<Integer> admission = invocation -> {
      List<JobQueue.EnqueueEntry> entries = invocation.getArgument(0);
      for (var entry : entries) {
        assertEquals(Files.size(entry.path()), entry.sizeBytes());
        admitted.add(entry.path());
      }
      return entries.size();
    };
    when(queue.enqueueEntries(anyList())).thenAnswer(admission);
    when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any())).thenAnswer(admission);
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    List<Path> expected = change.equals("unchanged") ? List.of() : List.of(file.toAbsolutePath());
    if (candidate) {
      try (var result = ops.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
        assertEquals(expected, result.additions(), "candidate reconciliation must compare the stored source stamp");
        assertTrue(result.deletions().isEmpty());
        result.requireCurrentRootIdentity();
      }
      verifyNoInteractions(queue);
    } else {
      for (var mode : List.of(SyncDirectoryOps.AdmissionMode.DEFAULT, SyncDirectoryOps.AdmissionMode.MAINTENANCE)) {
        admitted.clear();
        var result = ops.execute(temporary.toString(), false, null, () -> false, null, mode);
        assertEquals("", result.getError());
        assertEquals(expected, admitted, mode + " reconciliation must compare the stored source stamp");
        assertEquals(expected.size(), result.getFilesAdded());
      }
    }
  }
}
