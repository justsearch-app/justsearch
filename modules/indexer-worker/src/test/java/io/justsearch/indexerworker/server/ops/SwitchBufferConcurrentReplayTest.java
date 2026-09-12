/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** A successful replay may remove its snapshot, never a later accepted version. */
final class SwitchBufferConcurrentReplayTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(strings = {"new-key", "replacement", "identical-replacement"})
  void admissionAfterSnapshotSurvivesReplayRemovalAndReopen(String scenario) throws Exception {
    Path db = tempDir.resolve("jobs.db");
    String originalKey = "path:original";
    String originalPayload = "original";
    String nextKey = scenario.equals("new-key") ? "path:later" : originalKey;
    String nextPayload = scenario.equals("identical-replacement") ? originalPayload : "later";
    var runtime = mock(RunningRuntime.class);
    var indexing = mock(IndexingCoordinator.class);
    var commits = mock(CommitOps.class);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSwitchBuffer(originalKey, "DELETE", originalPayload));
      long originalTimestamp = queue.listSwitchBufferOps().getFirst().lastUpdatedMs();
      doAnswer(invocation -> {
        // Commit is after snapshot and effect, but before replay removes the snapshot.
        assertTrue(queue.putSwitchBuffer(nextKey, "DELETE", nextPayload));
        // Equal wall-clock timestamps and identical payloads must still be distinct admissions.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
            var update = connection.prepareStatement(
                "UPDATE switch_buffer SET last_updated = ? WHERE key = ?")) {
          update.setLong(1, originalTimestamp);
          update.setString(2, nextKey);
          assertEquals(1, update.executeUpdate());
        }
        return null;
      }).when(commits).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);

      KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, runtime, null, IndexingPacing.unthrottled(), tempDir, tempDir,
              new ObjectMapper(), () -> false,
              LoggerFactory.getLogger(SwitchBufferConcurrentReplayTest.class)));
      verify(indexing).deleteByIdAndChunks(originalPayload);
      verifyNoMoreInteractions(indexing);
      assertEquals(1, queue.switchBufferDepth());
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      var retained = reopened.listSwitchBufferOps();
      assertEquals(1, retained.size());
      assertEquals(nextKey, retained.getFirst().key());
      assertEquals(nextPayload, retained.getFirst().payload());
    }
  }
}
