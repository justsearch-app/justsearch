/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.loop.IndexingLoop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobReadinessAndClaimTest {
  @TempDir Path temp;

  @Test void elapsedRetryDeadlineChangesCountsProbeAndClaimsWithoutAWrite() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("deadline.db"))) {
      queue.open();queue.enqueue(List.of(temp.resolve("a"),temp.resolve("b"),temp.resolve("c")));
      var loop=org.mockito.Mockito.mock(IndexingLoop.class,org.mockito.Mockito.CALLS_REAL_METHODS);
      var queueField=IndexingLoop.class.getDeclaredField("jobQueue");queueField.setAccessible(true);queueField.set(loop,queue);
      var probe=IndexingLoop.class.getDeclaredMethod("hasPendingIngestWork");probe.setAccessible(true);
      long deadline=System.currentTimeMillis()+200;
      try(var update=JobCountsTest.connection(queue).prepareStatement("UPDATE jobs SET last_updated=?,retry_after=? WHERE path=?")) {
        int i=0;
        for(var name:List.of("a","b","c")) {
          update.setLong(1,i++);update.setLong(2,deadline);update.setString(3,temp.resolve(name).toString());update.executeUpdate();
        }
      }
      assertFalse(queue.hasPendingIngestWork());assertEquals(0,queue.jobStateCountsStrict().pendingReadyCount());
      assertEquals(false,probe.invoke(loop),"backfill does not yield for backoff-only work");
      // Ready rows let a cursor move beyond the backoff row before its deadline passes.
      try(var stmt=JobCountsTest.connection(queue).createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET retry_after=NULL WHERE last_updated>0");
      }
      assertEquals(temp.resolve("b"),queue.pollPending(1).getFirst().path());
      while(System.currentTimeMillis()<=deadline) Thread.sleep(5);
      assertTrue(queue.hasPendingIngestWork());assertEquals(2,queue.jobStateCountsStrict().pendingReadyCount());
      var cache=IndexingLoop.class.getDeclaredField("pendingIngestProbedAtMs");cache.setAccessible(true);cache.setLong(loop,0);
      assertEquals(true,probe.invoke(loop),"backfill yields when a retry becomes runnable");
      assertEquals(temp.resolve("a"),queue.pollPending(1).getFirst().path(),"elapsed deadline restarts the cursor");
    }
  }

  @Test void skipBudgetAdvancesWithoutClaimingDeniedRowsAndStopsAtWrap() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("budget.db"))) {
      queue.open();var walk=queue.beginRecordedWalk("denied","a".repeat(64),true);
      queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
          IntStream.range(0,600).mapToObj(i->new JobQueue.EnqueueEntry(temp.resolve(String.format("a%04d",i)),1)).toList(),null);
      queue.enqueue(List.of(temp.resolve("z")));
      assertTrue(queue.pollPending(16).isEmpty());assertTrue(queue.pollPendingBudgetExhausted());
      assertTrue(queue.pollPending(16).isEmpty());assertTrue(queue.pollPendingBudgetExhausted());
      var jobs=queue.pollPending(16);
      assertEquals(List.of(temp.resolve("z")),jobs.stream().map(JobQueue.IndexJob::path).toList());
      assertFalse(queue.pollPendingBudgetExhausted(),"end of scan permits an ordinary idle cycle");
      assertEquals(600,queue.jobStateCountsStrict().pendingCount());
    }
  }

  @Test void walkProgressChangeRestartsCursorAndAuthorityStillDecides() throws Exception {
    var permitted=new AtomicBoolean();
    try(var queue=new SqliteJobQueue(temp.resolve("authority.db"),key -> permitted.get()
        ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();var walk=queue.beginRecordedWalk("walk","a".repeat(64),true);
      queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
          IntStream.range(0,400).mapToObj(i->new JobQueue.EnqueueEntry(temp.resolve(String.format("a%04d",i)),1)).toList(),null);
      assertTrue(queue.pollPending(1).isEmpty());assertTrue(queue.pollPendingBudgetExhausted());
      permitted.set(true);
      try(var stmt=JobCountsTest.connection(queue).createStatement()) {stmt.executeUpdate("UPDATE ingestion_walk_progress SET revision=revision+1");}
      assertEquals(temp.resolve("a0000"),queue.pollPending(1).getFirst().path());
    }
  }

  @Test void walkPreconditionReuseNeverCachesAuthorityDecisions() throws Exception {
    var calls=new AtomicInteger();
    try(var queue=new SqliteJobQueue(temp.resolve("per-row-authority.db"),key -> calls.incrementAndGet()==1
        ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();var walk=queue.beginRecordedWalk("walk","a".repeat(64),true);
      queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
          List.of(new JobQueue.EnqueueEntry(temp.resolve("a"),1),new JobQueue.EnqueueEntry(temp.resolve("b"),1)),null);
      assertEquals(List.of(temp.resolve("a")),queue.pollPending(2).stream().map(JobQueue.IndexJob::path).toList());
      assertEquals(2,calls.get(),"authority is consulted for each candidate, including the denied one");
      assertEquals(1,queue.jobStateCountsStrict().pendingCount());
    }
  }

  @Test void reentrantProgressChangeInvalidatesWalkPreconditionWithinThePoll() throws Exception {
    var calls=new AtomicInteger();
    var connection=new java.sql.Connection[1];
    try(var queue=new SqliteJobQueue(temp.resolve("reentrant-progress.db"),key -> {
      calls.incrementAndGet();
      try(var stmt=connection[0].createStatement()) {
        stmt.executeUpdate("UPDATE ingestion_walk_progress SET enumeration_outcome='FAILED',enumeration_closed_at=1 WHERE operation_key='walk'");
      } catch(java.sql.SQLException failure) {throw new IllegalStateException(failure);}
      return JobQueue.RecordedClaimDecision.ALLOW;
    })) {
      queue.open();connection[0]=JobCountsTest.connection(queue);
      var walk=queue.beginRecordedWalk("walk","a".repeat(64),true);
      queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
          List.of(new JobQueue.EnqueueEntry(temp.resolve("a"),1),new JobQueue.EnqueueEntry(temp.resolve("b"),1)),null);
      assertEquals(List.of(temp.resolve("a")),queue.pollPending(2).stream().map(JobQueue.IndexJob::path).toList());
      assertEquals(1,calls.get(),"changed progress fences the next row before its authority callback");
      assertEquals(1,queue.jobStateCountsStrict().pendingCount());
    }
  }

  @Test void refillingDeniedSourcePrecedesTheOtherSourcesLookahead() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("refill.db"))) {
      queue.open();var walk=queue.beginRecordedWalk("denied","a".repeat(64),true);
      queue.enqueueRecordedEntries(walk.operationKey(),walk.enumerationEpoch(),
          IntStream.range(0,40).mapToObj(i->new JobQueue.EnqueueEntry(temp.resolve(String.format("a%04d",i)),1)).toList(),null);
      Path ready=temp.resolve("ready"),due=temp.resolve("due");
      queue.enqueue(List.of(ready,due));
      try(var stmt=JobCountsTest.connection(queue).createStatement()) {
        stmt.executeUpdate("UPDATE jobs SET last_updated=1 WHERE scan_id='denied'");
        stmt.executeUpdate("UPDATE jobs SET last_updated=2 WHERE path LIKE '%/ready'");
        stmt.executeUpdate("UPDATE jobs SET last_updated=3,retry_after=0 WHERE path LIKE '%/due'");
      }
      assertEquals(ready,queue.pollPending(1).getFirst().path(),"refill before consuming a later retry lookahead");
      assertEquals(due,queue.pollPending(1).getFirst().path());
      assertEquals(40,queue.jobStateCountsStrict().pendingCount(),"denied rows stay unclaimed");
    }
  }

  @Test void readyAndDueSourcesMergeByTimeThenBinaryPath() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("order.db"))) {
      queue.open();var paths=List.of(temp.resolve("b"),temp.resolve("a"),temp.resolve("\uE000"),temp.resolve("\uD83D\uDE00"));
      queue.enqueue(paths);
      try(var update=JobCountsTest.connection(queue).prepareStatement("UPDATE jobs SET last_updated=1,retry_after=? WHERE path=?")) {
        for(int i=0;i<paths.size();i++) {if(i%2==0) update.setNull(1,java.sql.Types.BIGINT);else update.setLong(1,0);update.setString(2,paths.get(i).toString());update.executeUpdate();}
      }
      var actual=new ArrayList<Path>();for(int i=0;i<4;i++)actual.add(queue.pollPending(1).getFirst().path());
      assertEquals(List.of(paths.get(1),paths.get(0),paths.get(2),paths.get(3)),actual);
    }
  }
}
