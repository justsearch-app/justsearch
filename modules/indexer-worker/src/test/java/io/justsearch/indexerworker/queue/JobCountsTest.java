/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;

final class JobCountsTest {
  @TempDir Path temp;

  @Test void randomOperationsPreserveExactCountsBytesAndSavedRows() throws Exception { randomOperations(2_000); }

  @Test @Tag("evidence") @Timeout(value=10,unit=TimeUnit.MINUTES)
  void q9HundredThousandRandomOperations() throws Exception { randomOperations(100_000); }

  private void randomOperations(int operations) throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("random.db"))) {
      queue.open();
      var conn = connection(queue);
      var rng = new Random(739);
      var claims = new ArrayList<JobQueue.IndexJob>();
      var walk = queue.beginRecordedWalk("random-walk","a".repeat(64),true);
      // Always exercise both ordinary former REPLACE paths, including a changed state and size.
      var replacement = temp.resolve("replacement");
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(replacement,23)),"collection","scan");
      queue.markDone(replacement);
      queue.reenqueue(new JobQueue.EnqueueEntry(replacement,-1));
      assertExact(queue,conn);
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(replacement,47)),null,null);
      assertExact(queue,conn);
      for (int i=0;i<operations;i++) {
        Path path=temp.resolve("jobs/"+rng.nextInt(64));
        long size=rng.nextBoolean() ? -1 : rng.nextInt(100_000);
        switch (rng.nextInt(14)) {
          case 0 -> queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(path,size)),"collection","scan");
          case 1 -> queue.reenqueue(new JobQueue.EnqueueEntry(path,size));
          case 2 -> claims.addAll(queue.pollPending(2));
          case 3 -> queue.markDone(path);
          case 4 -> queue.defer(path,outcome(IngestionRetryPolicy.RETRY_WITH_BACKOFF));
          case 5 -> queue.markFailed(path,"failure detail");
          case 6 -> queue.markFailed(path,outcome(IngestionRetryPolicy.NONE));
          case 7 -> { queue.returnUnfinishedClaims(claims); claims.clear(); queue.recoverStuckJobs(); }
          case 8 -> queue.clearFailedJobs();
          case 9 -> queue.deleteByExactPath(path.toString());
          case 10 -> {
            var before=rows(conn);
            conn.setAutoCommit(false);
            try (var update=conn.prepareStatement("UPDATE jobs SET state='FAILED',size_bytes=NULL WHERE path=?")) {
              update.setString(1,path.toString()); update.executeUpdate();
              assertExact(queue,conn);
            } finally { conn.rollback(); conn.setAutoCommit(true); }
            assertEquals(before,rows(conn),"rollback preserves every saved field");
          }
          case 11 -> {
            try (var update=conn.prepareStatement("UPDATE jobs SET state='RETRY_EXHAUSTED',size_bytes=NULL WHERE path=?")) {
              update.setString(1,path.toString()); update.executeUpdate();
            }
          }
          case 12 -> {
            var recorded=temp.resolve("recorded-"+rng.nextInt(8));
            queue.deleteByExactPath(recorded.toString());
            queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(recorded,19)),"recorded",null);
            queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
                List.of(new JobQueue.EnqueueEntry(recorded,size)),"recorded");
          }
          case 13 -> queue.deleteByPathPrefix(temp.resolve("jobs/"+rng.nextInt(8)).toString());
          default -> throw new AssertionError();
        }
        if (i%25==0) assertExact(queue,conn);
      }
      assertExact(queue,conn);
      var before=rows(conn);
      queue.close(); queue.open();
      assertEquals(before,rows(connection(queue)),"reopen preserves every saved row field");
      assertExact(queue,connection(queue));
      System.out.println("Q9 random operations="+operations+" exact counts/bytes/rollback/reopen passed");
    }
  }

  @Test void reopenRepairsCountDriftWithoutChangingJobs() throws Exception {
    try (var queue=new SqliteJobQueue(temp.resolve("drift.db"))) {
      queue.open(); queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(temp.resolve("known"),123),
          new JobQueue.EnqueueEntry(temp.resolve("unknown"),-1)));
      var saved=rows(connection(queue));
      try(var stmt=connection(queue).createStatement()) { stmt.executeUpdate("UPDATE job_counts SET n=n+19,known_bytes=9999"); }
      queue.close(); queue.open();
      assertEquals(saved,rows(connection(queue)));
      assertExact(queue,connection(queue));
    }
  }

  @Test void reAdmissionResetsOnlyTheFieldsFormerReplaceReset() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("resets.db"))) {
      queue.open(); Path path=temp.resolve("reset");
      var provenance=new JobQueue.EnqueueProvenance("user","http");
      queue.enqueueEntries(List.of(new JobQueue.EnqueueEntry(path,37,provenance,null,temp)),"library","scan");
      try(var stmt=connection(queue).createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET state='RETRY_EXHAUSTED',attempts=7,retry_after=9999999999999,"
            +"error_message='detail',first_failed_at=123,content_hash='saved',last_outcome_class='PARSER_FAILED',"
            +"last_reason_code='reason',last_retry_policy='NONE',last_diagnostic_summary='diagnostic',last_outcome_at=456");
      }
      queue.reenqueue(new JobQueue.EnqueueEntry(path,-1));
      try(var stmt=connection(queue).createStatement();var row=stmt.executeQuery("SELECT * FROM jobs")) {
        assertTrue(row.next()); assertEquals("PENDING",row.getString("state")); assertEquals(0,row.getInt("attempts"));
        assertEquals("library",row.getString("collection")); assertEquals("scan",row.getString("scan_id"));
        assertEquals("user",row.getString("originator")); assertEquals("http",row.getString("transport"));
        assertEquals(temp.toString(),row.getString("ingestion_root"));
        for(var column:List.of("size_bytes","retry_after","first_failed_at","error_message","content_hash",
            "last_outcome_class","last_reason_code","last_retry_policy","last_diagnostic_summary","last_outcome_at"))
          assertNull(row.getObject(column),column);
      }
      assertExact(queue,connection(queue));
    }
  }

  static Connection connection(SqliteJobQueue queue) throws Exception {
    var field=SqliteJobQueue.class.getDeclaredField("connection");field.setAccessible(true);return (Connection)field.get(queue);
  }

  static void assertExact(SqliteJobQueue queue,Connection conn) throws Exception {
    var actual=new TreeMap<String,List<Long>>();var kept=new TreeMap<String,List<Long>>();
    try(var stmt=conn.createStatement();var row=stmt.executeQuery("SELECT state,COUNT(*),COALESCE(SUM(size_bytes),0),SUM(size_bytes IS NULL) FROM jobs GROUP BY state")) {
      while(row.next())actual.put(row.getString(1),List.of(row.getLong(2),row.getLong(3),row.getLong(4)));
    }
    try(var stmt=conn.createStatement();var row=stmt.executeQuery("SELECT state,n,known_bytes,unknown_size FROM job_counts WHERE n<>0 OR known_bytes<>0 OR unknown_size<>0")) {
      while(row.next())kept.put(row.getString(1),List.of(row.getLong(2),row.getLong(3),row.getLong(4)));
    }
    assertEquals(actual,kept,"trigger counts and bytes equal direct aggregates");
    var counts=queue.jobStateCountsStrict();
    assertEquals(n(actual,"PENDING"),counts.pendingCount());assertEquals(n(actual,"PROCESSING"),counts.processingCount());
    assertEquals(n(actual,"DONE"),counts.doneCount());assertEquals(n(actual,"FAILED")+n(actual,"RETRY_EXHAUSTED"),counts.failedCount());
    assertEquals(counts.pendingCount()+counts.processingCount(),queue.queueDepth());
    long bytes=0,unknown=0;
    for(var state:List.of("PENDING","PROCESSING")) {
      var row=actual.get(state);if(row!=null) {bytes+=row.get(1);unknown+=row.get(2);}
    }
    assertEquals(new JobQueue.PendingBytes(bytes,unknown),queue.pendingBytes());
  }

  private static long n(TreeMap<String,List<Long>> counts,String state) {return counts.getOrDefault(state,List.of(0L)).getFirst();}

  private static List<List<Object>> rows(Connection conn)throws Exception {
    var result=new ArrayList<List<Object>>();
    try(var stmt=conn.createStatement();var rows=stmt.executeQuery("SELECT * FROM jobs ORDER BY path")) {
      while(rows.next()) {
        var row=new ArrayList<Object>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getObject(i));result.add(row);
      }
    }
    return result;
  }

  private static IngestionOutcome outcome(IngestionRetryPolicy policy) {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED,"TEST",policy,"failure detail");
  }
}
