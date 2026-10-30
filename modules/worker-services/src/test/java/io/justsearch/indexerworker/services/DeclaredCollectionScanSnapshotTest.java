/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.configuration.persistence.WatchedRootsFormat;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class DeclaredCollectionScanSnapshotTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void readsDeclarationsOncePerScanAndRefreshesNextScan(boolean candidate, @TempDir Path data) throws Exception {
    Path root = Files.createDirectory(data.resolve("source"));
    var hits = new ArrayList<LuceneRuntimeTypes.SearchHit>();
    for (int i = 0; i < 32; i++) {
      Path file = Files.writeString(root.resolve("file" + i + ".txt"), "unchanged");
      hits.add(new LuceneRuntimeTypes.SearchHit(file.toString(), 1.0f, Map.of(
          SchemaFields.PATH, file.toString(), SchemaFields.SIZE_BYTES, String.valueOf(Files.size(file)),
          SchemaFields.MODIFIED_AT, String.valueOf(Files.getLastModifiedTime(file).toMillis()),
          SchemaFields.COLLECTION, "first")));
    }
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 0));
    var queue = mock(JobQueue.class);
    when(queue.enqueueEntries(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    var registryReads = new AtomicInteger();
    ops.useDeclaredCollectionRoots(() -> {
      registryReads.incrementAndGet();
      try {
        return WatchedRootsFormat.declaredCollections(data).entrySet().stream()
            .map(entry -> new RootBinding(entry.getKey(), entry.getValue())).toList();
      } catch (java.io.IOException failure) {
        throw new UncheckedIOException(failure);
      }
    });
    for (String label : List.of("first", "second")) {
      new ObjectMapper().writeValue(data.resolve("watched_roots.json").toFile(), Map.of(
          "schemaVersion", 1, "roots", List.of(Map.of("path", root.toString(), "collection", label))));
      int expected = label.equals("first") ? 0 : hits.size();
      if (candidate) {
        try (var result = ops.discoverCandidateDifference(root.toString(), false, () -> false)) {
          assertEquals(expected, result.additions().size(), "the next scan sees changed declarations");
        }
      } else {
        var result = ops.execute(root.toString(), false, null, () -> false);
        assertEquals("", result.getError());
        assertEquals(expected, result.getFilesAdded(), "the next scan sees changed declarations");
      }
      assertEquals(label.equals("first") ? 1 : 2, registryReads.get(),
          "a scan reads the registry once, regardless of its file count");
    }
  }
}
