/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SourceFileWork.COMMITTED;
import static io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SourceFileWork.NONE;
import static io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SourceFileWork.OUTSTANDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A refused candidate's accepted file admission is handed to the surviving generation only by
 * that generation's committed effect: the accepted or a later admission whose writer committed
 * exactly the bytes that generation holds. A row's status alone is not: a retired or refused row
 * wrote nothing, and a pending, claimed or failed row has not written yet.
 */
final class QueueAdmissionOwnershipTest {
  private static final String LATER = "c".repeat(64);
  private static final String STALE = "d".repeat(64);

  @TempDir Path tempDir;

  @Test
  void onlyACommittedWriteHandsTheFileToTheSource() throws Exception {
    Path db = tempDir.resolve("ownership.db");
    Path file = Files.writeString(tempDir.resolve("owned.txt"), "first").toAbsolutePath();
    String key = PathNormalizer.normalizeKey(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(NONE, queue.sourceFileWork(key, LATER), "no row: A needs a job");
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.stat(file), null, null));
      String accepted = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload()).unitRevision();

      assertEquals(OUTSTANDING, queue.sourceFileWork(key, null),
          "a pending row will run, but has written nothing yet");

      set(db, key, "DONE", accepted, LATER, LATER);
      assertEquals(COMMITTED, queue.sourceFileWork(key, LATER),
          "the accepted admission's own write committed the bytes the source holds");
      set(db, key, "DONE", accepted, LATER, null);
      assertEquals(NONE, queue.sourceFileWork(key, LATER),
          "the accepted admission retired without writing is no effect, even when its plan matches");
      set(db, key, "PROCESSING", "later-revision", LATER, null);
      assertEquals(OUTSTANDING, queue.sourceFileWork(key, LATER), "a claim is not an effect");
      set(db, key, "FAILED", "later-revision", LATER, null);
      assertEquals(OUTSTANDING, queue.sourceFileWork(key, LATER), "a failure is not an effect");

      set(db, key, "DONE", "later-revision", LATER, LATER);
      assertEquals(COMMITTED, queue.sourceFileWork(key, LATER),
          "the source committed the later admission's bytes");
      assertEquals(NONE, queue.sourceFileWork(key, STALE),
          "a later finished row with stale source bytes hands nothing over");
      assertEquals(NONE, queue.sourceFileWork(key, null), "a source without the file holds nothing");

      set(db, key, "DONE", "later-revision", STALE, null);
      assertEquals(NONE, queue.sourceFileWork(key, STALE),
          "a later row retired without writing is no effect, even when its plan matches the source");
      set(db, key, "DONE", "later-revision", STALE, LATER);
      assertEquals(COMMITTED, queue.sourceFileWork(key, LATER),
          "the source committed a later captured revision whose bytes changed after its plan");
    }
  }

  private static void set(Path db, String key, String state, String revision, String planned,
      String committed) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement("UPDATE jobs SET state = ?, unit_revision = ?, "
            + "planned_source_sha256 = ?, content_hash = ? WHERE path = ?")) {
      statement.setString(1, state);
      statement.setString(2, revision);
      statement.setString(3, planned);
      statement.setString(4, committed);
      statement.setString(5, key);
      assertEquals(1, statement.executeUpdate());
    }
  }
}
