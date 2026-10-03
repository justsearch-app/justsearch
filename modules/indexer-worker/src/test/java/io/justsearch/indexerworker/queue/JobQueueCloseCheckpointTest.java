/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stage B item B5 — WAL checkpointing (design 7.3 step 7).
 *
 * <p><b>B5's stated acceptance could not be met, and the measurements are why.</b> The item asks
 * for "a test asserting the WAL is checkpointed after an ordinary {@code close()} (assert on the
 * {@code -wal} file's size or the pragma result, not on the call)". Three drafts were written, and
 * each was falsified by deleting the new call from {@code close()} and watching the test pass
 * anyway:
 *
 * <ol>
 *   <li><b>{@code -wal} size after a lone close.</b> Passed without the call: SQLite checkpoints and
 *       deletes the {@code -wal} itself when the last connection closes cleanly.
 *   <li><b>Database growth across the close with a second connection held open</b>, on the theory
 *       that SQLite defers its automatic checkpoint while another connection exists. Passed without
 *       the call too — measured {@code dbBeforeClose=4096 dbAfterClose=139264 wal=absent}
 *       identically with and without it. The second connection was obtained but never used, and
 *       sqlite-jdbc opens lazily, so it deferred nothing.
 *   <li><b>{@code -wal} shrinking to zero after an explicit checkpoint.</b> Failed, for a different
 *       reason worth keeping: {@code wal_checkpoint(FULL)} copies frames into the database and
 *       REUSES the log file rather than truncating it — measured {@code wal 3753352 -> 3753352,
 *       db 4096 -> 139264}. "Checkpointed" does not mean "the file shrank".
 * </ol>
 *
 * <p><b>So the honest position is recorded rather than a fourth attempt dressed up as proof.</b> On
 * this driver an ordinary close already drains the log, with or without the explicit call, and no
 * test written here distinguishes them. What the call buys is that design 7.3 step 7's guarantee is
 * stated by this code rather than inherited from the driver's happy path — real, but modest, and
 * not what a passing test would have appeared to claim. The test below asserts the part that IS
 * distinguishable: the pragma this code calls genuinely drains frames.
 *
 * <p>The rename is the change with teeth. {@code checkpointForUpgrade} named its caller rather than
 * its effect, and it had exactly one — so the ordinary close never checkpointed by design, and
 * nobody reading the name would think to ask why.
 */
@DisplayName("SqliteJobQueue — WAL checkpointing (stage B item B5)")
final class JobQueueCloseCheckpointTest {

  private static List<Path> manyPaths(Path dir, int n) {
    List<Path> paths = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      paths.add(dir.resolve("doc-" + i + ".txt"));
    }
    return paths;
  }

  @Test
  @DisplayName("checkpointWal copies the log's frames into the database")
  void checkpointCopiesFramesIntoTheDatabase(@TempDir Path tempDir) throws Exception {
    Path db = tempDir.resolve("jobs.db");
    Path wal = tempDir.resolve("jobs.db-wal");

    try (SqliteJobQueue queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueue(manyPaths(tempDir, 200));

      assertTrue(Files.exists(wal), "precondition: journal_mode=WAL, so the log file exists");
      long walFrames = Files.size(wal);
      assertTrue(walFrames > 0, "precondition: frames must exist, or the assertion proves nothing");

      long dbBefore = Files.size(db);
      assertTrue(queue.checkpointWal(), "a checkpoint with no competing reader must fully drain");
      long dbAfter = Files.size(db);

      // The database growing is the observable; the -wal's size is NOT (it is reused — see the
      // class javadoc). This runs while the connection is open, so SQLite's close-time behaviour
      // cannot be the explanation.
      assertTrue(
          dbAfter > dbBefore,
          "the database should have grown as the "
              + walFrames
              + " bytes of log frames were copied into it, but it went "
              + dbBefore
              + " -> "
              + dbAfter);
    }
  }
}
