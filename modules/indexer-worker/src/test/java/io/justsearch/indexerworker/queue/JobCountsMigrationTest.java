/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobCountsMigrationTest {
  @TempDir Path temp;

  @Test void failedV23MigrationRollsBackTableTriggersIndexesCountsAndVersion() throws Exception {
    Path db=temp.resolve("migration.db");
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(temp.resolve("known"),123),
          new JobQueue.EnqueueEntry(temp.resolve("unknown"),-1)));
    }
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db);var stmt=conn.createStatement()) {
      for(String trigger:List.of("jobs_counts_insert","jobs_counts_update","jobs_counts_delete"))stmt.execute("DROP TRIGGER "+trigger);
      stmt.execute("DROP TABLE job_counts");stmt.execute("DROP INDEX idx_jobs_state_retry");stmt.execute("DROP INDEX idx_jobs_pending_ready");stmt.execute("DROP INDEX idx_jobs_pending_retry");
      stmt.execute("PRAGMA user_version=22");
    }
    try(var queue=new SqliteJobQueue(db,3,null,version -> {
      if(version==23)throw new SQLException("injected v23 failure");
    })) {assertThrows(SQLException.class,queue::open);}
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db);var stmt=conn.createStatement()) {
      try(var row=stmt.executeQuery("PRAGMA user_version")) {assertTrue(row.next());assertEquals(22,row.getInt(1));}
      try(var row=stmt.executeQuery("SELECT name FROM sqlite_master WHERE name='job_counts' OR name LIKE 'jobs_counts_%' OR name IN ('idx_jobs_state_retry','idx_jobs_pending_ready','idx_jobs_pending_retry')")) {
        assertFalse(row.next(),"failed migration leaves no v23 schema objects");
      }
    }
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();JobCountsTest.assertExact(queue,JobCountsTest.connection(queue));
      assertEquals(new JobQueue.PendingBytes(123,1),queue.pendingBytes());
      assertEquals(2,queue.jobStateCountsStrict().pendingReadyCount());
    }
  }
}
