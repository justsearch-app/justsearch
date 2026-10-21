/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobCountsReplaceTest {
  @TempDir Path temp;

  @Test void reopenRepairsUnmanagedWriterDriftOnceWithoutChangingRows() throws Exception {
    Path db = temp.resolve("unmanaged.db");
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SqliteQueueMigrationOps.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.list = new java.util.concurrent.CopyOnWriteArrayList<>();
    String thread = Thread.currentThread().getName();
    events.start(); logger.addAppender(events);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      Path path = temp.resolve("job");
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(path, 123)));
      // An unmanaged connection deliberately disables REPLACE's recursive DELETE triggers.
      try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
           var replace = conn.prepareStatement("INSERT OR REPLACE INTO jobs(path,state,last_updated,size_bytes) VALUES (?,'DONE',42,NULL)")) {
        try (var stmt = conn.createStatement()) { stmt.execute("PRAGMA recursive_triggers=OFF"); }
        replace.setString(1, path.toString()); replace.executeUpdate();
      }
      queue.close(); queue.open();
      JobCountsTest.assertExact(queue, JobCountsTest.connection(queue));
      try (var stmt = JobCountsTest.connection(queue).createStatement(); var row = stmt.executeQuery("SELECT state,last_updated,size_bytes FROM jobs")) {
        assertTrue(row.next()); assertEquals("DONE", row.getString(1)); assertEquals(42, row.getLong(2)); assertNull(row.getObject(3));
      }
      queue.close(); queue.open();
      JobCountsTest.assertExact(queue, JobCountsTest.connection(queue));
      assertEquals(1, events.list.stream().filter(e -> e.getThreadName().equals(thread)
          && e.getFormattedMessage().startsWith("Repairing job count drift at open:")).count());
    } finally { logger.detachAppender(events); events.stop(); }
  }

  @Test void plainSqlReplaceKeepsCountsAndBytesExactWithoutReopen() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("replace.db"))) {
      queue.open();
      Path path = temp.resolve("job");
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(path, 123)));
      var conn = JobCountsTest.connection(queue);
      try (var replace = conn.prepareStatement("INSERT OR REPLACE INTO jobs(path,state,last_updated,size_bytes) VALUES (?,?,1,?)")) {
        for (String state : List.of("DONE", "PENDING", "FAILED", "RETRY_EXHAUSTED", "PROCESSING")) {
          replace.setString(1, path.toString()); replace.setString(2, state); replace.setNull(3, java.sql.Types.BIGINT);
          replace.executeUpdate(); JobCountsTest.assertExact(queue, conn);
          replace.setLong(3, 321); replace.executeUpdate(); JobCountsTest.assertExact(queue, conn);
        }
        conn.setAutoCommit(false);
        replace.setString(2, "PENDING"); replace.setLong(3, 777); replace.executeUpdate();
        JobCountsTest.assertExact(queue, conn); conn.rollback(); conn.setAutoCommit(true);
        JobCountsTest.assertExact(queue, conn);
      }
    }
  }
}
