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
