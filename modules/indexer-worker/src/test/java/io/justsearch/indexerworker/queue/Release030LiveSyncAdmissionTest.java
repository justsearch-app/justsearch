/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Release030LiveSyncAdmissionTest {
  @TempDir Path root;

  @Test void unreadableExactSourceRollsBackTheWholeAdmissionBatch() throws Exception {
    Path exact=Files.writeString(root.resolve("exact.txt"),"owned");
    Path other=Files.writeString(root.resolve("ordinary.txt"),"ordinary");
    Path db=root.resolve("jobs.db");
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();queue.retainRelease030Retirement("building",List.of(),Map.of());
      assertTrue(queue.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(exact),"named",null));
      var before=queue.listSwitchBufferOpsStrict();Files.delete(exact);
      assertThrows(IllegalStateException.class,()->queue.enqueueRelease030LiveSyncEntries(
          List.of(JobQueue.EnqueueEntry.stat(other),new JobQueue.EnqueueEntry(exact,5)),null,()->false));
      assertEquals(before,queue.listSwitchBufferOpsStrict());
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var q=c.prepareStatement("SELECT count(*) FROM jobs WHERE path=?")) {
        q.setString(1,other.toString());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
      }
    }
  }

  @Test void cancellationDuringExactHashLeavesItsPriorAdmissionIntact() throws Exception {
    Path exact=Files.write(root.resolve("exact.bin"),new byte[131072]);
    try(var queue=new SqliteJobQueue(root.resolve("jobs.db"))) {
      queue.open();queue.retainRelease030Retirement("building",List.of(),Map.of());
      assertTrue(queue.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(exact),"named",null));
      var before=queue.listSwitchBufferOpsStrict();var checks=new AtomicInteger();
      assertThrows(IllegalStateException.class,()->queue.enqueueRelease030LiveSyncEntries(
          List.of(JobQueue.EnqueueEntry.stat(exact)),null,()->checks.incrementAndGet()>=2));
      assertTrue(checks.get()>=2,"cancellation reached the source hash boundary");
      assertEquals(before,queue.listSwitchBufferOpsStrict());
    }
  }

  @Test void automaticReplayKeepsTheExactRetryAndMintsNoAcknowledgement() throws Exception {
    Path exact=Files.writeString(root.resolve("exact.txt"),"owned");Path db=root.resolve("jobs.db");
    try(var queue=new SqliteJobQueue(db)) {
      queue.open();queue.retainRelease030Retirement("building",List.of(),Map.of());
      assertTrue(queue.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(exact),"named",null));
      queue.markFailed(exact,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
          IngestionReasonCodes.SOURCE_UNREADABLE,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"one transient source failure"));
      var before=queue.listSwitchBufferOpsStrict();String retry=job(db,exact);
      assertEquals(1,queue.enqueueUpgradeRecoveryEntries(List.of(JobQueue.EnqueueEntry.stat(exact).withinRoot(root)),null));
      assertEquals(before,queue.listSwitchBufferOpsStrict());assertEquals(retry,job(db,exact));
      assertTrue(queue.jobStateCountsStrict().pendingBackoffCount()>0);
    }
  }

  private static String job(Path db,Path file)throws Exception {
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var q=c.prepareStatement(
        "SELECT state,attempts,retry_after,unit_revision,planned_source_sha256,collection,ingestion_root FROM jobs WHERE path=?")) {
      q.setString(1,file.toString());try(var r=q.executeQuery()){assertTrue(r.next());var values=new java.util.ArrayList<String>();for(int i=1;i<=7;i++)values.add(r.getString(i));return values.toString();}
    }
  }
}
