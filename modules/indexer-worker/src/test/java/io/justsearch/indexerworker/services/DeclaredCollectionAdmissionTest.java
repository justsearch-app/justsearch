/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.ipc.BatchRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class DeclaredCollectionAdmissionTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void hintlessMixedBatchUsesPersistedDeepestOwners(boolean migrating) throws Exception {
    Path parent = Files.createDirectories(temporary.resolve("P"));
    Path child = Files.createDirectories(parent.resolve("C"));
    Path defaults = Files.createDirectories(parent.resolve("default"));
    Path p = Files.writeString(parent.resolve("p.txt"), "p");
    Path c = Files.writeString(child.resolve("c.txt"), "c");
    Path d = Files.writeString(defaults.resolve("default.txt"), "default");
    Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside");
    Path data = Files.createDirectory(temporary.resolve("data"));
    declarations(data, List.of(Map.of("path", parent.toString(), "collection", "P"),
        Map.of("path", child.toString(), "collection", "C"), Map.of("path", defaults.toString())));
    Path base = temporary.resolve("index");
    var manager = new IndexGenerationManager(base);
    var layout = manager.initializeOrLoad();
    if (migrating) manager.startMigration("manual");
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
          base, layout.activeGenerationPath(), null, null, null, 0L);
      service.setDeclaredRootsDirectory(data);
      var response = service.submitBatch(BatchRequest.newBuilder()
          .addAllFilePaths(List.of(p.toString(), c.toString(), d.toString(), outside.toString())).build(), CallContext.none());
      assertEquals(4, response.getAcceptedCount(), response.getErrorMessage());
      var labels = new HashMap<Path, String>();
      for (var claim : queue.pollPending(10)) labels.put(claim.path(), claim.collection());
      assertEquals("P", labels.get(p), "parent declaration applies without a watch");
      assertEquals("C", labels.get(c), "the deepest declared folder owns each file");
      assertNull(labels.get(d));
      assertNull(labels.get(outside), "the ad-hoc mcp-ingest fallback is not a worker default");
      if (migrating) {
        var receipts = new HashMap<Path, String>();
        for (var op : queue.listSwitchBufferOpsStrict()) {
          var upsert = SwitchBufferUpsert.decode(op.payload());
          receipts.put(Path.of(upsert.path()), upsert.collection());
        }
        assertEquals(labels, receipts, "the job and its replay receipt carry the same ownership");
      }
    }
  }

  @Test
  void declaredDefaultClearsObsoleteCollectionOnReadmission() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("file.txt"), "content");
    declarations(temporary, List.of(Map.of("path", root.toString())));
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)), "obsolete-parent");
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      service.setDeclaredRootsDirectory(temporary);
      service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).build(), CallContext.none());
      assertNull(queue.pollPending(1).getFirst().collection(), "a declared default is an exact assignment");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"explicit", "agent-history", "justsearch-help"})
  void explicitLabelsKeepTheirAuthority(String label) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("file.txt"), "content");
    declarations(temporary, List.of(Map.of("path", root.toString(), "collection", "owner")));
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
      queue.open();
      var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
      service.setDeclaredRootsDirectory(temporary);
      service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection(label).build(), CallContext.none());
      assertEquals(label, queue.pollPending(1).getFirst().collection());
    }
  }

  private static void declarations(Path data, List<Map<String, String>> roots) throws Exception {
    Files.writeString(data.resolve("watched_roots.json"), new ObjectMapper().writeValueAsString(
        Map.of("schemaVersion", 1, "roots", roots)));
  }
}
