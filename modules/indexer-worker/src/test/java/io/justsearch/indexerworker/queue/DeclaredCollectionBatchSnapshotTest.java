/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.configuration.persistence.WatchedRootsFormat;
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

final class DeclaredCollectionBatchSnapshotTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void hintlessBatchReadsDeclarationsOnceAndRefreshesNextBatch(boolean migrating, @TempDir Path data) throws Exception {
    Path root = Files.createDirectory(data.resolve("source"));
    var entries = new ArrayList<JobQueue.EnqueueEntry>();
    for (int i = 0; i < 32; i++) {
      entries.add(JobQueue.EnqueueEntry.stat(Files.writeString(root.resolve("file" + i + ".txt"), "content")));
    }
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      var registryReads = new AtomicInteger();
      queue.useDeclaredCollectionRoots(() -> {
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
        assertEquals(entries.size(), migrating
            ? queue.enqueueAndBufferFilesForGeneration("green", entries, null, null)
            : queue.enqueueEntries(entries));
        assertEquals(label.equals("first") ? 1 : 2, registryReads.get(),
            "a hintless batch reads the registry once, regardless of its file count");
        var claims = queue.pollPending(entries.size());
        assertEquals(entries.size(), claims.size());
        assertTrue(claims.stream().allMatch(job -> label.equals(job.collection())),
            "every file uses this batch's declarations, including changes since the previous batch");
        queue.returnUnfinishedClaims(claims);
        if (migrating) {
          var receipts = queue.listSwitchBufferOpsStrict();
          assertEquals(entries.size(), receipts.size());
          assertTrue(receipts.stream().allMatch(op -> label.equals(SwitchBufferUpsert.decode(op.payload()).collection())));
        }
      }
    }
  }
}
