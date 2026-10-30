/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class DeclaredCollectionReconciliationTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"missing", "obsolete", "default"})
  void ordinaryScanAdmitsUnchangedFilesWithWrongCollection(String wrong) throws Exception {
    check(wrong, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "obsolete", "default"})
  void candidateScanAdmitsUnchangedFilesWithWrongCollection(String wrong) throws Exception {
    check(wrong, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"current", "agent-history", "justsearch-help"})
  void currentAndReservedLabelsStaySkipped(String label) throws Exception {
    check(label, false);
    check(label, true);
  }

  private void check(String stored, boolean candidate) throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source"));
    Path file = Files.writeString(root.resolve("file.txt"), "same bytes");
    var rootEntry = new HashMap<String, String>();
    rootEntry.put("path", root.toString());
    if (!stored.equals("default")) rootEntry.put("collection", "current");
    Files.writeString(temporary.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
        Map.of("schemaVersion", 1, "roots", List.of(rootEntry))));
    var fields = new HashMap<String, String>();
    fields.put(SchemaFields.PATH, file.toString());
    fields.put(SchemaFields.SIZE_BYTES, String.valueOf(Files.size(file)));
    fields.put(SchemaFields.MODIFIED_AT, String.valueOf(Files.getLastModifiedTime(file).toMillis()));
    if (!stored.equals("missing")) fields.put(SchemaFields.COLLECTION, stored.equals("default") ? "obsolete" : stored);
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class))).thenReturn(
        new LuceneRuntimeTypes.SearchResult(List.of(new LuceneRuntimeTypes.SearchHit(file.toString(), 1.0f, fields)), 1, 0));
    var runtime = mock(RunningRuntime.class);
    when(runtime.readPathOps()).thenReturn(reads);
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any()))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, runtime, runtime, null, 0L);
    service.setDeclaredRootsDirectory(temporary);
    var field = WorkerIngestService.class.getDeclaredField(candidate ? "candidateDiscoveryOps" : "syncOps");
    field.setAccessible(true);
    var ops = (SyncDirectoryOps) field.get(service);
    boolean wrong = List.of("missing", "obsolete", "default").contains(stored);
    if (candidate) {
      try (var difference = ops.discoverCandidateDifference(root.toString(), false, () -> false)) {
        assertEquals(wrong ? List.of(file) : List.of(), difference.additions(), "collection is part of ordinary reconciliation");
      }
    } else {
      for (var mode : List.of(SyncDirectoryOps.AdmissionMode.DEFAULT, SyncDirectoryOps.AdmissionMode.MAINTENANCE)) {
        var result = ops.execute(root.toString(), false, null, () -> false, null, mode);
        assertEquals("", result.getError());
        assertEquals(wrong ? 1 : 0, result.getFilesAdded(), "unchanged bytes do not justify an obsolete collection");
      }
    }
  }
}
