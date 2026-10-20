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
 * A refused candidate's accepted file admission is still owned by the queue (and so by the source
 * generation's own writer) only while its row is pending, or once a later admission replaced it.
 */
final class QueueAdmissionOwnershipTest {
  @TempDir Path tempDir;

  @Test
  void queueOwnsAPendingOrSupersededAdmissionButNotASettledOne() throws Exception {
    Path db = tempDir.resolve("ownership.db");
    Path file = Files.writeString(tempDir.resolve("owned.txt"), "first").toAbsolutePath();
    String key = PathNormalizer.normalizeKey(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertFalse(queue.queueStillOwnsFileAdmission(key, "any"), "no row owns nothing");
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.stat(file), null, null));
      String accepted = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload()).unitRevision();

      assertTrue(queue.queueStillOwnsFileAdmission(key, accepted), "a pending row is owned");

      setState(db, key, "DONE", accepted);
      assertFalse(queue.queueStillOwnsFileAdmission(key, accepted),
          "a settled row of the accepted revision must be proven on the source instead");
      setState(db, key, "PROCESSING", accepted);
      assertFalse(queue.queueStillOwnsFileAdmission(key, accepted),
          "a claimed row of the accepted revision is not settled ownership");

      setState(db, key, "DONE", "later-revision");
      assertTrue(queue.queueStillOwnsFileAdmission(key, accepted),
          "a later admission replaced the accepted one");
    }
  }

  private static void setState(Path db, String key, String state, String revision)
      throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement(
            "UPDATE jobs SET state = ?, unit_revision = ? WHERE path = ?")) {
      statement.setString(1, state);
      statement.setString(2, revision);
      statement.setString(3, key);
      assertEquals(1, statement.executeUpdate());
    }
  }
}
