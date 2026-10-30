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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** After-only integration of real SQLite rollback, durable intent and ordinary retry ownership. */
final class Release030CorrectionHistoryTest {
  @TempDir Path temporary;

  private SqliteJobQueue queue() throws Exception {
    Path db=temporary.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryStateFixture.capture(db);
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
        "test-file/"+hash,hash,Files.size(file),false);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void checkpointWriteBytesScaleLinearlyWithinAndAcrossCorrectionCalls(boolean batched) throws Exception {
    Path root=Files.createDirectories(temporary.resolve("linear"));
    var files=new java.util.ArrayList<Path>();
    for(int i=0;i<52;i++) files.add(Files.writeString(root.resolve("file-"+i+".txt"),"old"));
    try(var q=queue()) {
      String key="sync_root:"+root+"/";
      assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),true,null),"C"));
      var owner=q.retirementRootReceipt("building",key);
      assertEquals(files.size(),q.enqueueRetirementRootEntries(owner,files.stream().map(file->JobQueue.EnqueueEntry.stat(file).withinRoot(root)).toList(),"C",false,false,()->false));
      var claims=q.pollPending(files.size());
      assertEquals(files.size(),claims.size());
      var transitions=new java.util.ArrayList<JobQueue.IngestionLedgerTransition>();
      for(var claim:claims) transitions.add(new JobQueue.IngestionLedgerTransition(claim,null,
          io.justsearch.indexerworker.loop.SourceContentHash.sha256(claim.path())));
      q.markDoneTransitions(transitions,IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL,"SUCCESS",IngestionRetryPolicy.NONE));
      var observations=new java.util.ArrayList<SwitchBufferCapableQueue.RetirementSourceObservation>();
      for(Path file:files) {
        var scope=q.retirementRootFileScope(owner,file.toString());Files.writeString(file,"new");
        observations.add(observe(q,owner,scope,file));
      }
      // A pre-existing history prevents an empty-checkpoint special case hiding repeated rewrites.
      correct(q,observations.subList(0,16),batched);
      long small=checkpointBytes(()->correct(q,observations.subList(16,20),batched));
      long large=checkpointBytes(()->correct(q,observations.subList(20,52),batched));
      assertTrue(small>0,"JFR must observe checkpoint writes");
      assertTrue(large<=small*10,"eight times the corrections must cost approximately eight times the bytes: "+small+" -> "+large);
      assertEquals(52,q.release030RecoveryState().corrections().size());
      var retained=q.release030RecoveryState().corrections();
      assertEquals(new java.util.HashSet<>(retained),new java.util.HashSet<>(Release030RecoveryState.load(temporary.resolve("jobs.db")).corrections()));
      q.release030RecoveryState().finishRetirement();
      var finished=Release030RecoveryState.load(temporary.resolve("jobs.db"));
      assertTrue(finished.corrections().isEmpty());
      finished.beginRetirement("later-active","later-building",Map.of());
      assertTrue(Release030RecoveryState.load(temporary.resolve("jobs.db")).corrections().isEmpty());
    }
  }

  private static void correct(SqliteJobQueue queue,List<SwitchBufferCapableQueue.RetirementSourceObservation> observations,boolean batch) {
    if(batch) assertTrue(queue.correctRetirementSources(observations,()->false).stream().allMatch(count->count==1));
    else for(var observation:observations)assertEquals(List.of(1),queue.correctRetirementSources(List.of(observation),()->false));
  }

  @Test void legacyPropertiesHistoryLoadsAndRetainsNewVersionsUntilRetirementEnds() throws Exception {
    try (var q = queue()) {
      assertTrue(q.release030RecoveryState().corrections().isEmpty());
      Path db = temporary.resolve("jobs.db"), path = temporary.resolve("source.txt");
      var scope = new SwitchBufferUpsert(path.toString(), "C", null, null, null, temporary);
      var first = new Release030RecoveryState.Correction(null, "active", "building", scope,
          Map.of("state", "DONE"), "DONE",
          new SwitchBufferUpsert(path.toString(), "C", null, "first", "a".repeat(64), temporary));
      Path checkpoint = Release030RecoveryState.path(db);
      var properties = new java.util.Properties();
      try (var input = Files.newInputStream(checkpoint)) { properties.load(input); }
      properties.setProperty("retire|correction|first",
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(first));
      try (var output = Files.newOutputStream(checkpoint)) { properties.store(output, "legacy history"); }
      var loaded = Release030RecoveryState.load(db);
      assertEquals(List.of(first), loaded.corrections());
      var second = new Release030RecoveryState.Correction(null, "active", "building", scope,
          Map.of("state", "PENDING", "retry_after", "12345"), "PENDING",
          new SwitchBufferUpsert(path.toString(), "C", null, "second", "b".repeat(64), temporary));
      loaded.retainCorrection(second);
      assertEquals(java.util.Set.of(first, second), java.util.Set.copyOf(Release030RecoveryState.load(db).corrections()));
      loaded.finishRetirement();
      assertTrue(Release030RecoveryState.load(db).corrections().isEmpty());
      loaded.beginRetirement("later-active", "later-building", Map.of());
      assertTrue(Release030RecoveryState.load(db).corrections().isEmpty());
    }
  }

  @FunctionalInterface private interface CheckedAction {void run() throws Exception;}

  private long checkpointBytes(CheckedAction action) throws Exception {
    Path recording=temporary.resolve(java.util.UUID.randomUUID()+".jfr");
    try(var capture=new jdk.jfr.Recording()) {
      capture.enable("jdk.FileWrite").withThreshold(java.time.Duration.ZERO).withoutStackTrace();
      capture.start();action.run();capture.stop();capture.dump(recording);
    }
    long total=0;
    for(var event:jdk.jfr.consumer.RecordingFile.readAllEvents(recording)) {
      if(event.getEventType().getName().equals("jdk.FileWrite")
          && event.getString("path").contains("upgrade-030-recovery.properties"))total+=event.getLong("bytesWritten");
    }
    return total;
  }

  @Test void sameIntentResumesAfterRollbackWithActualMutableRetryPreserved() throws Exception {
    Path root=Files.createDirectories(temporary.resolve("root")),file=Files.writeString(root.resolve("file.txt"),"old");
    try(var q=queue()) {
      var owner=admit(q,root,file);complete(q,file);
      var scope=q.retirementRootFileScope(owner,file.toString());
      Files.writeString(file,"new");long admissions=q.switchBufferAdmissions();
      var calls=new AtomicInteger();
      var observation=observe(q,owner,scope,file);
      assertThrows(IllegalStateException.class,()->q.correctRetirementSources(List.of(observation),()->calls.incrementAndGet()==2));
      var intent=q.release030RecoveryState().corrections().getFirst();
      assertEquals(intent.predecessor(),q.watcherReconciliationRow(file.toString()),"SQL rolled back after durable intent");
      q.close(); q.open();
      assertEquals(List.of(intent), q.release030RecoveryState().corrections());
      q.markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,"SOURCE_UNREADABLE",IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE));
      var waiting=q.watcherReconciliationRow(file.toString());
      assertEquals("PENDING",waiting.get("state"));assertTrue(Long.parseLong(waiting.get("retry_after"))>System.currentTimeMillis());
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner,scope,file)),()->false));
      var resumed=q.watcherReconciliationRow(file.toString());
      assertEquals(intent.replacement().unitRevision(),resumed.get("unit_revision"));
      for(String key:waiting.keySet())if(!key.equals("unit_revision")&&!key.equals("planned_source_sha256"))assertEquals(waiting.get(key),resumed.get(key),key);
      assertEquals(1,q.release030RecoveryState().corrections().size());assertEquals(admissions,q.switchBufferAdmissions());
      assertTrue(q.pollPending(10).isEmpty(),"the real retry deadline still owns eligibility");
    }
  }

  @Test void laterUnwalkedRootHoldsAnInstalledCorrectionWhileDisjointRootClaims() throws Exception {
    Path root=Files.createDirectories(temporary.resolve("root")),file=Files.writeString(root.resolve("file.txt"),"old");
    Path other=Files.createDirectories(temporary.resolve("other")),healthy=Files.writeString(other.resolve("healthy.txt"),"healthy");
    try(var q=queue()) {
      var owner=admit(q,root,file);complete(q,file);var scope=q.retirementRootFileScope(owner,file.toString());
      Files.writeString(file,"new");assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner,scope,file)),()->false));
      var before=q.watcherReconciliationRow(file.toString());
      assertTrue(q.putSyncRootForGeneration("building","sync_root:"+root+"/",new SwitchBufferSyncRoot(root.toString(),true,null),"D"));
      admit(q,other,healthy);
      assertEquals(List.of(healthy),q.pollPending(10).stream().map(JobQueue.IndexJob::path).toList());
      assertEquals(before,q.watcherReconciliationRow(file.toString()));
    }
  }

  @Test void explicitCollectionRootRetainsInheritedAttributionDuringSourceCorrection() throws Exception {
    Path root=Files.createDirectories(temporary.resolve("root")),file=Files.writeString(root.resolve("file.txt"),"old");
    try(var q=queue()) {
      var provenance=new JobQueue.EnqueueProvenance("user","rpc");
      q.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file,3,provenance,null,root)),"C",null);
      var owner=admit(q,root,file);complete(q,file);
      SwitchBufferUpsert scope;
      // Exercise the real production helper on both sides of its instrumentation-only signature change.
      try {
        var method=io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.class.getDeclaredMethod(
            "releaseRootScope",SwitchBufferCapableQueue.class,SwitchBufferCapableQueue.SwitchBufferOp.class,String.class);
        method.setAccessible(true);
        scope=(SwitchBufferUpsert)method.invoke(null,q,owner,file.toString());
      } catch(NoSuchMethodException _) {
        var method=io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps.class.getDeclaredMethod(
            "releaseRootScope",SwitchBufferCapableQueue.class,SwitchBufferCapableQueue.SwitchBufferOp.class,String.class,
            java.util.function.Function.class);
        method.setAccessible(true);
        var rootFiles=q.getClass().getMethod("retirementRootFiles").invoke(q);
        scope=(SwitchBufferUpsert)method.invoke(null,q,owner,file.toString(),rootFiles);
      }
      assertEquals("C",scope.collection());assertEquals(provenance,scope.provenance());
      Files.writeString(file,"new");
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner,scope,file)),()->false));
      var row=q.watcherReconciliationRow(file.toString());
      assertEquals("C",row.get("collection"));assertEquals("user",row.get("originator"));assertEquals("rpc",row.get("transport"));
    }
  }
}
