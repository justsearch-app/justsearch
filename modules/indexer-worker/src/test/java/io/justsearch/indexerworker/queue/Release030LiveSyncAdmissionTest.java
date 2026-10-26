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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"", "building"})
  void liveRootRefreshRetainsOriginalRawReceiptAcrossCoalescingAndRestart(String originalGeneration) throws Exception {
    Path file=Files.writeString(root.resolve("raw.txt"),"original raw bytes");
    Path ordinary=Files.writeString(root.resolve("ordinary.txt"),"ordinary bytes");
    Path db=root.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)),"original");
      assertTrue(originalGeneration.isEmpty()
          ? q.putSwitchBuffer("path:"+file,"UPSERT",file.toString())
          : q.putSwitchBufferForGeneration(originalGeneration,"path:"+file,"UPSERT",file.toString()));
    }
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    SwitchBufferCapableQueue.SwitchBufferOp raw;
    SwitchBufferUpsert frozen;
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      raw=q.retirementReplayOps().getFirst();frozen=q.release030RecoveryState().legacyExpectation(raw);
      assertNotNull(frozen);assertEquals("original",frozen.collection());
      String key="sync_root:"+io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(root.toString());
      assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),true,null),"new"));
      var token=q.retirementRootReceipt("building",key);
      long admissions=q.switchBufferAdmissions();
      assertEquals(2,q.enqueueRetirementRootEntries(token,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root),
          JobQueue.EnqueueEntry.stat(ordinary).withinRoot(root)),"new",false,false,()->false));
      var union=q.retirementReplayOps();assertTrue(union.contains(raw),"replacement cannot erase original raw receipt");
      assertEquals(frozen,q.release030RecoveryState().legacyExpectation(raw),"original namespace remains immutable");
      var fresh=union.getLast();var exact=SwitchBufferUpsert.decode(fresh.payload());
      assertEquals("UPSERT",fresh.op());assertEquals("building",fresh.generation());
      assertTrue(fresh.acceptedOrder()>token.acceptedOrder());
      assertEquals("new",exact.collection());assertEquals(root,exact.ingestionRoot());
      assertEquals(io.justsearch.indexerworker.loop.SourceContentHash.sha256(file),exact.sourceSha256());
      assertEquals(exact.unitRevision(),q.watcherReconciliationRow(file.toString()).get("unit_revision"));
      assertNull(q.watcherReconciliationRow(ordinary.toString()).get("planned_source_sha256"));
      assertEquals(admissions+1,q.switchBufferAdmissions(),"only the retained raw path receives an exact receipt");
      assertEquals(originalGeneration.isEmpty()?2:1,
          q.listSwitchBufferOpsStrict().stream().filter(op->op.op().equals("UPSERT")).count());
    }
    try(var q=new SqliteJobQueue(db)) {
      q.open();assertTrue(q.retirementReplayOps().contains(raw));
      assertEquals(frozen,q.release030RecoveryState().legacyExpectation(raw));
      assertTrue(q.release030RecoveryState().removalPackets().isEmpty(),"passive history never authorizes removal");
    }
  }

  @Test void rawCheckpointSaveFailureRollsBackSqlAndAllowsRetry() throws Exception {
    Path file=Files.writeString(root.resolve("raw.txt"),"raw bytes"),db=root.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)),"original");
      assertTrue(q.putSwitchBufferForGeneration("building","path:"+file,"UPSERT",file.toString()));
    }
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      var before=q.retirementReplayOps();String row=job(db,file);long admissions=q.switchBufferAdmissions();
      Path checkpoint=Release030RecoveryState.path(db),backup=root.resolve("saved-checkpoint");
      Files.move(checkpoint,backup);Files.createDirectory(checkpoint);
      try {
        assertThrows(IllegalStateException.class,()->q.enqueueRelease030LiveSyncEntries(
            List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"new",false,()->false));
        assertEquals(before,q.retirementReplayOps());assertEquals(row,job(db,file));
        assertEquals(admissions,q.switchBufferAdmissions());
        assertTrue(q.release030RecoveryState().removalPackets().isEmpty());
      } finally {Files.delete(checkpoint);Files.move(backup,checkpoint);}
      assertEquals(1,q.enqueueRelease030LiveSyncEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"new",false,()->false));
      assertTrue(q.retirementReplayOps().contains(before.getFirst()));
      assertTrue(q.release030RecoveryState().removalPackets().isEmpty());
    }
    try(var q=new SqliteJobQueue(db)) {q.open();assertEquals(2,q.retirementReplayOps().size());}
  }

  @Test void rawHashCancellationPreservesTheOriginalReceiptAndRetry() throws Exception {
    Path file=Files.write(root.resolve("raw.bin"),new byte[131072]),db=root.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)),"original");
      assertTrue(q.putSwitchBufferForGeneration("building","path:"+file,"UPSERT",file.toString()));
    }
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      q.markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,
          IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"original waiting unit"));
      var before=q.retirementReplayOps();String row=job(db,file);long admissions=q.switchBufferAdmissions();
      var checks=new AtomicInteger();
      assertThrows(IllegalStateException.class,()->q.enqueueRelease030LiveSyncEntries(
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"new",false,()->checks.incrementAndGet()>=2));
      assertTrue(checks.get()>=2);assertEquals(before,q.retirementReplayOps());assertEquals(row,job(db,file));
      assertEquals(admissions,q.switchBufferAdmissions());
      assertTrue(q.release030RecoveryState().removalPackets().isEmpty());
      assertEquals(1,q.enqueueRelease030LiveSyncEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"new",false,()->false));
    }
  }

  private static String job(Path db,Path file)throws Exception {
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var q=c.prepareStatement(
        "SELECT state,attempts,retry_after,unit_revision,planned_source_sha256,collection,ingestion_root FROM jobs WHERE path=?")) {
      q.setString(1,file.toString());try(var r=q.executeQuery()){assertTrue(r.next());var values=new java.util.ArrayList<String>();for(int i=1;i<=7;i++)values.add(r.getString(i));return values.toString();}
    }
  }
}
