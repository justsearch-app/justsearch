/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobCountsMigrationTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "everySupportedVersionMigratesToV24WithoutChangingSavedRows({0})")
  @ValueSource(ints = {13,14,15,16,17,18,19,20,21,22,23})
  void everySupportedVersionMigratesToV24WithoutChangingSavedRows(int sourceVersion) throws Exception {
    Path db=temp.resolve("v"+sourceVersion+".db");
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db)) {
      QueueScaleFixtures.schema(conn,sourceVersion);
      try(var stmt=conn.createStatement()) {
        stmt.execute("INSERT INTO jobs(path,state,attempts,last_updated,size_bytes,scan_id) VALUES('/saved','PENDING',2,123,456,'scan')");
        if(sourceVersion==23)stmt.execute("UPDATE switch_buffer_admissions SET count=41");
      }
    }
    try(var queue=new SqliteJobQueue(db)) {
      assertDoesNotThrow(queue::open);
      assertEquals(24,version(queue),"V23 belongs to main's admission counter; counts require V24");
      var conn=JobCountsTest.connection(queue);
      JobCountsTest.assertExact(queue,conn);
      try(var stmt=conn.createStatement();var row=stmt.executeQuery("SELECT path,state,attempts,last_updated,size_bytes,scan_id FROM jobs")) {
        assertTrue(row.next());assertEquals("/saved",row.getString(1));assertEquals("PENDING",row.getString(2));
        assertEquals(2,row.getInt(3));assertEquals(123,row.getLong(4));assertEquals(456,row.getLong(5));assertEquals("scan",row.getString(6));assertFalse(row.next());
      }
      assertEquals(sourceVersion==23?41:0,queue.switchBufferAdmissions());
    }
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();assertEquals(24,version(queue));JobCountsTest.assertExact(queue,JobCountsTest.connection(queue));
      assertEquals(sourceVersion==23?41:0,queue.switchBufferAdmissions());
    }
  }

  private static int version(SqliteJobQueue queue) throws Exception {
    try(var stmt=JobCountsTest.connection(queue).createStatement();var row=stmt.executeQuery("PRAGMA user_version")) {
      assertTrue(row.next());return row.getInt(1);
    }
  }

  @Test void unreleasedQueueV23GainsMainAdmissionFenceWithoutLosingCounts() throws Exception {
    Path db=temp.resolve("unreleased-v23.db");
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();queue.enqueue(List.of(temp.resolve("saved")));
    }
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db);var stmt=conn.createStatement()) {
      stmt.execute("DROP TRIGGER count_switch_buffer_admission");
      stmt.execute("DROP TABLE switch_buffer_admissions");stmt.execute("PRAGMA user_version=23");
    }
    try(var queue=new SqliteJobQueue(db)) {
      assertDoesNotThrow(queue::open);assertEquals(24,version(queue));
      assertEquals(0,assertDoesNotThrow(queue::switchBufferAdmissions));
      JobCountsTest.assertExact(queue,JobCountsTest.connection(queue));assertEquals(1,queue.queueDepth());
    }
  }

  @Test void failedV24MigrationRollsBackTableTriggersIndexesCountsAndVersion() throws Exception {
    Path db=temp.resolve("migration.db");
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(temp.resolve("known"),123),
          new JobQueue.EnqueueEntry(temp.resolve("unknown"),-1)));
    }
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db);var stmt=conn.createStatement()) {
      for(String trigger:List.of("jobs_counts_insert","jobs_counts_update","jobs_counts_delete"))stmt.execute("DROP TRIGGER "+trigger);
      stmt.execute("DROP TABLE job_counts");stmt.execute("DROP INDEX idx_jobs_state_retry");stmt.execute("DROP INDEX idx_jobs_pending_ready");stmt.execute("DROP INDEX idx_jobs_pending_retry");
      stmt.execute("PRAGMA user_version=23");
    }
    try(var queue=new SqliteJobQueue(db,3,null,version -> {
      if(version==24)throw new SQLException("injected v24 failure");
    })) {assertThrows(SQLException.class,queue::open);}
    try(var conn=DriverManager.getConnection("jdbc:sqlite:"+db);var stmt=conn.createStatement()) {
      try(var row=stmt.executeQuery("PRAGMA user_version")) {assertTrue(row.next());assertEquals(23,row.getInt(1));}
      try(var row=stmt.executeQuery("SELECT name FROM sqlite_master WHERE name='job_counts' OR name LIKE 'jobs_counts_%' OR name IN ('idx_jobs_state_retry','idx_jobs_pending_ready','idx_jobs_pending_retry')")) {
        assertFalse(row.next(),"failed migration leaves no v24 schema objects");
      }
    }
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();JobCountsTest.assertExact(queue,JobCountsTest.connection(queue));
      assertEquals(new JobQueue.PendingBytes(123,1),queue.pendingBytes());
      assertEquals(2,queue.jobStateCountsStrict().pendingReadyCount());
    }
  }
}
