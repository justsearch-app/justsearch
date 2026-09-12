/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class SyncRootReplayProvenanceTest {
  @TempDir Path tempDir;

  @Test
  void versionedReplayRestoresPersistedAttribution() throws Exception {
    JobQueue.EnqueueProvenance expected =
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP");
    JobQueue.IndexJob job =
        replay("""
            {"version":1,"root_path":"%s","force":true,
             "originator":"agent","transport":"AGENT_LOOP"}
            """.formatted(syncRootJson()), true);

    assertEquals(expected, job.provenance());
  }

  @Test
  void maintenanceCoalescesAtomicallyAndSurvivesRestartReplay() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("coalesced"));
    Files.writeString(root.resolve("doc.txt"), "content");
    Path db = tempDir.resolve("coalesced.db");
    var agent = new JobQueue.EnqueueProvenance("agent", "MCP");
    var user = new JobQueue.EnqueueProvenance("user", "BUTTON");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSyncRoot("sync_root:test",
          new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root.toString(), false, agent)));
      assertTrue(queue.putSyncRoot("sync_root:test",
          new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root.toString(), true, null)));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var restored = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(
          queue.listSwitchBufferOps().getFirst().payload());
      assertEquals(agent, restored.provenance());
      assertTrue(restored.force());
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, mock(RunningRuntime.class),
              null, IndexingPacing.unthrottled(), tempDir.resolve("base"), tempDir.resolve("active"),
              new ObjectMapper(), () -> false, () -> true, LoggerFactory.getLogger(getClass())));
      assertEquals(0, queue.switchBufferDepth());
      assertEquals(agent, queue.pollPending(1).getFirst().provenance());
      assertTrue(queue.putSyncRoot("sync_root:test",
          new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root.toString(), true, null)));
      assertTrue(queue.putSyncRoot("sync_root:test",
          new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root.toString(), false, user)));
      assertEquals(user, io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(
          queue.listSwitchBufferOps().getFirst().payload()).provenance());
    }
  }

  @Test
  void maintenanceDoesNotEraseUnreadableOrDifferentBufferedWork() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("protected"));
    var incoming = new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root.toString(), true, null);
    try (var queue = new SqliteJobQueue(tempDir.resolve("protected.db"))) {
      queue.open();
      for (String operation : new String[] {"SYNC_ROOT", "UPSERT"}) {
        queue.putSwitchBuffer("sync_root:test", operation, "unreadable-payload");
        org.junit.jupiter.api.Assertions.assertFalse(queue.putSyncRoot("sync_root:test", incoming));
        assertEquals("unreadable-payload", queue.listSwitchBufferOps().getFirst().payload());
        assertEquals(operation, queue.listSwitchBufferOps().getFirst().op());
      }
    }
  }

  @Test
  void versionedMaintenanceAcceptsExplicitNullAttribution() throws Exception {
    JobQueue.IndexJob job = replay("""
        {"version":1,"root_path":"%s","force":true,"originator":null,"transport":null}
        """.formatted(syncRootJson()), true);
    assertNull(job.provenance());
  }

  @Test
  void legacyUnversionedReplayLeavesAttributionUnknown() throws Exception {
    JobQueue.IndexJob job =
        replay(
            """
            {"root_path":"%s","force":true}
            """.formatted(syncRootJson()),
            true);

    assertNull(job.provenance());
  }

  @Test
  void malformedFutureAndMissingRootPayloadsRemainBuffered() throws Exception {
    String root = syncRootJson();
    for (String payload :
        new String[] {
          "{\"version\":2,\"root_path\":\"%s\",\"force\":true}".formatted(root),
          "{\"version\":4294967297,\"root_path\":\"%s\",\"force\":true}".formatted(root),
          "{\"version\":1,\"root_path\":\"%s\",\"force\":true,\"originator\":\"agent\"}".formatted(root),
          "{\"version\":1,\"root_path\":\"%s\",\"force\":\"true\"}".formatted(root),
          "{\"version\":1,\"root_path\":\"%s\"}".formatted(root),
          "{\"version\":1,\"root_path\":\"%s\",\"force\":true}".formatted(root),
          "{\"root_path\":\"%s\",\"force\":true,\"originator\":\"agent\",\"transport\":\"MCP\"}".formatted(root),
          "{\"version\":1,\"force\":true,\"originator\":null,\"transport\":null}"
        }) {
      replay(payload, false);
    }
  }

  private JobQueue.IndexJob replay(String payload, boolean expectCleared) throws Exception {
    Path db = tempDir.resolve("jobs-" + System.nanoTime() + ".db");
    try (SqliteJobQueue queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("sync_root:test", "SYNC_ROOT", payload));
    }
    try (SqliteJobQueue queue = new SqliteJobQueue(db)) {
      queue.open();
      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue,
              mock(RunningRuntime.class),
              null,
              IndexingPacing.unthrottled(),
              tempDir.resolve("base"),
              tempDir.resolve("active"),
              new ObjectMapper(),
              () -> false, () -> true,
              LoggerFactory.getLogger(getClass())));
      assertEquals(expectCleared ? 0 : 1, queue.switchBufferDepth());
      return expectCleared ? queue.pollPending(1).getFirst() : null;
    }
  }

  private String syncRootJson() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("root-" + System.nanoTime()));
    Files.writeString(root.resolve("document.txt"), "content");
    return root.toAbsolutePath().toString().replace("\\", "\\\\");
  }
}
