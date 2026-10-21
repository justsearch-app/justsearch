/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A refused candidate's accepted file admission is handed to the surviving generation only by
 * that generation's committed effect: a pending row that will produce the file there, or the
 * accepted or a later admission whose writer committed exactly the bytes that generation holds. A row's status alone
 * is not: a retired or refused row wrote nothing.
 */
final class QueueAdmissionOwnershipTest {
  private static final String LATER = "c".repeat(64);
  private static final String STALE = "d".repeat(64);

  @TempDir Path tempDir;

  @Test
  void onlyAPendingRowOrACommittedWriteHandsTheFileToTheSource() throws Exception {
    Path db = tempDir.resolve("ownership.db");
    Path file = Files.writeString(tempDir.resolve("owned.txt"), "first").toAbsolutePath();
    String key = PathNormalizer.normalizeKey(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertFalse(queue.sourceOwnsFileAdmission(key, "any", LATER), "no row owns nothing");
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.stat(file), null, null));
      String accepted = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload()).unitRevision();

      assertTrue(queue.sourceOwnsFileAdmission(key, accepted, null), "a pending row will run");

      set(db, key, "DONE", accepted, LATER, LATER);
      assertTrue(queue.sourceOwnsFileAdmission(key, accepted, LATER),
          "the accepted admission's own write committed the bytes the source holds");
      set(db, key, "DONE", accepted, LATER, null);
      assertFalse(queue.sourceOwnsFileAdmission(key, accepted, LATER),
          "the accepted admission retired without writing is no effect, even when its plan matches");
      set(db, key, "PROCESSING", "later-revision", LATER, null);
      assertFalse(queue.sourceOwnsFileAdmission(key, accepted, LATER), "a claim is not an effect");

      set(db, key, "DONE", "later-revision", LATER, LATER);
      assertTrue(queue.sourceOwnsFileAdmission(key, accepted, LATER),
          "the source committed the later admission's bytes");
      assertFalse(queue.sourceOwnsFileAdmission(key, accepted, STALE),
          "a later finished row with stale source bytes hands nothing over");
      assertFalse(queue.sourceOwnsFileAdmission(key, accepted, null),
          "a source without the file holds nothing");

      set(db, key, "DONE", "later-revision", STALE, null);
      assertFalse(queue.sourceOwnsFileAdmission(key, accepted, STALE),
          "a later row retired without writing is no effect, even when its plan matches the source");
      set(db, key, "DONE", "later-revision", STALE, LATER);
      assertTrue(queue.sourceOwnsFileAdmission(key, accepted, LATER),
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
