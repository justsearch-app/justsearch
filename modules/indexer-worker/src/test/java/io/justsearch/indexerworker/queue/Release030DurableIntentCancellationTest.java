/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/** Real queue recovery of a durable, uninstalled intent beneath a preserving ROOT. */
final class Release030DurableIntentCancellationTest {
  @TempDir Path temporary;

  @org.junit.jupiter.api.AfterEach void snapshot(org.junit.jupiter.api.TestInfo info) throws Exception {
    String evidence=System.getenv("VERIFY_EVIDENCE");
    if(evidence==null)return;
    Path target=Path.of(evidence,"snapshots",info.getTestMethod().orElseThrow().getName(),temporary.getFileName().toString());
    try(var paths=Files.walk(temporary)) {
      for(Path source:paths.filter(Files::isRegularFile).toList()) {
        Path copy=target.resolve(temporary.relativize(source));
        Files.createDirectories(copy.getParent());Files.copy(source,copy);
      }
    }
  }

  private void evidence(SqliteJobQueue q, Path file, String stage) throws Exception {
    Files.writeString(temporary.resolve(stage+".txt"),
        "row="+q.watcherReconciliationRow(file.toString())+"\ncorrections="+q.release030RecoveryState().corrections()
        +"\nreceipts="+q.retirementReplayOps()+"\nadmissions="+q.switchBufferAdmissions()+"\n");
    Files.copy(Release030RecoveryState.path(temporary.resolve("jobs.db")),temporary.resolve(stage+".properties"));
  }

  private SqliteJobQueue queue() throws Exception {
    Path db=temporary.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    var q=new SqliteJobQueue(db);q.open();q.inventoryRetirementRoots();
    q.retainRelease030Retirement("building",List.of(),Map.of());return q;
  }

  private SwitchBufferCapableQueue.SwitchBufferOp admit(SqliteJobQueue q,Path root,Path file) {
    String key="sync_root:"+root+"/";
    assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),true,null),"C"));
    var owner=q.retirementRootReceipt("building",key);
    assertEquals(1,q.enqueueRetirementRootEntries(owner,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"C",false,false,()->false));
    return owner;
  }

  private static void complete(SqliteJobQueue q,Path file) throws Exception {
    var claim=q.pollPending(1).getFirst();assertEquals(file,claim.path());
    String hash=io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    q.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim,null,hash)),
        IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL,"SUCCESS",IngestionRetryPolicy.NONE));
  }

  private static SwitchBufferCapableQueue.RetirementSourceObservation observe(SqliteJobQueue q,
      SwitchBufferCapableQueue.SwitchBufferOp owner,SwitchBufferUpsert scope,Path file) throws Exception {
    String hash=io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    return new SwitchBufferCapableQueue.RetirementSourceObservation(owner,scope,q.watcherReconciliationRow(file.toString()),
        "test-file/"+hash,hash,Files.size(file),false,true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"ROOT","FILE","DELETE"})
  void durableIntentResumesBeneathPreservingRoot(String laterWinner) throws Exception {
    resume(false,laterWinner);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"ROOT","FILE","DELETE"})
  void durableIntentResumesWithSameMutableRetry(String laterWinner) throws Exception {
    resume(true,laterWinner);
  }

  private void resume(boolean waiting, String laterWinner) throws Exception {
    Path root=Files.createDirectories(temporary.resolve("root"));
    Path file=Files.writeString(root.resolve("file.txt"),"old");
    try(var q=queue()) {
      var owner=admit(q,root,file);complete(q,file);
      var predecessor=q.watcherReconciliationRow(file.toString());
      assertEquals("DONE",predecessor.get("state"));
      var scope=q.retirementRootFileScope(owner,file.toString());
      String key="sync_root:"+root+"/";
      assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),false,null),"C"));
      var preserving=q.retirementRootReceipt("building",key);
      assertNotEquals(owner.retirementUuid(),preserving.retirementUuid());
      assertEquals(1,q.enqueueRetirementRootEntries(preserving,
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),"C",false,true,()->false,
          Map.of(file.toString(),predecessor)));
      assertEquals(predecessor,q.watcherReconciliationRow(file.toString()));
      Files.writeString(file,"new");
      String currentHash=io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
      long admissions=q.switchBufferAdmissions();
      var observation=observe(q,owner,scope,file);
      var calls=new AtomicInteger();
      assertThrows(IllegalStateException.class,()->q.correctRetirementSources(
          List.of(observation),()->calls.incrementAndGet()==2));
      assertEquals(2,calls.get());
      var intent=q.release030RecoveryState().corrections().getFirst();
      assertEquals(predecessor,intent.predecessor());
      assertEquals(predecessor,q.watcherReconciliationRow(file.toString()),"cancellation leaves exact predecessor");
      assertEquals(owner,intent.owner());assertEquals(scope,intent.scope());
      assertEquals(currentHash,intent.replacement().sourceSha256());
      evidence(q,file,"cancelled");
      if(waiting) {
        q.markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
            "SOURCE_UNREADABLE",IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE));
      }
      var before=q.watcherReconciliationRow(file.toString());
      if(waiting) {
        assertEquals("PENDING",before.get("state"));
        assertTrue(Long.parseLong(before.get("retry_after"))>System.currentTimeMillis());
      }
      var retried=q.correctRetirementSources(List.of(observe(q,owner,scope,file)),()->false);
      evidence(q,file,"retried");
      assertEquals(List.of(1),retried,"retained intent must resume beneath preserving force-false ROOT");
      var resumed=q.watcherReconciliationRow(file.toString());
      assertEquals(intent.replacement().unitRevision(),resumed.get("unit_revision"));
      assertEquals(currentHash,resumed.get("planned_source_sha256"));
      assertEquals(List.of(intent),q.release030RecoveryState().corrections());
      assertEquals(admissions,q.switchBufferAdmissions());
      if(waiting) {
        for(String column:before.keySet()) {
          if(!column.equals("unit_revision")&&!column.equals("planned_source_sha256"))
            assertEquals(before.get(column),resumed.get(column),column);
        }
        assertTrue(q.pollPending(10).isEmpty(),"same ACK retry remains held by its deadline");
      }
      if(laterWinner.equals("ROOT")) {
        assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),true,null),"C"));
      } else if(laterWinner.equals("FILE")) {
        assertTrue(q.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(file).withinRoot(root),"C",null));
      } else {
        assertTrue(q.putSwitchBufferForGeneration("building","delete-root","DELETE_PREFIX",
            new io.justsearch.adapters.lucene.runtime.PathPrefixScope(root.toString(),List.of()).encode()));
      }
      var winnerRow=q.watcherReconciliationRow(file.toString());
      Files.writeString(file,"newer");
      assertEquals(List.of(0),q.correctRetirementSources(List.of(observe(q,owner,scope,file)),()->false),
          "current history must veto obsolete correction after "+laterWinner);
      assertEquals(winnerRow,q.watcherReconciliationRow(file.toString()));
      assertTrue(q.pollPending(10).stream().noneMatch(job->
          intent.replacement().unitRevision().equals(job.unitRevision())),"obsolete replacement cannot claim");
      evidence(q,file,"later-winner");
    }
  }
}

