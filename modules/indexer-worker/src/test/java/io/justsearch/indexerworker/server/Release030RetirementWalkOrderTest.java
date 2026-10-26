/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.ingest.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementWalkOrderTest {
 @org.junit.jupiter.params.ParameterizedTest(name = "{0} delete then later {1} owner")
 @org.junit.jupiter.params.provider.CsvSource({"migrating,root", "migrating,exact", "switching,root", "switching,exact"})
 void acknowledgedPrefixThenForcedSyncSurvivesPendingWriterRetry(String fixture, String owner, @TempDir Path temporary) throws Exception {
  Path root=Release030Fixture.unpack(fixture,temporary);
  var original=state(root);
  Path originalAlpha=root.resolve("roots/alpha");
  assertTrue(Files.isReadable(originalAlpha.resolve("pending.txt")));
  System.out.println("R15_ALPHA_AVAILABLE volume="+Files.getFileStore(originalAlpha)+" source="+Files.size(originalAlpha.resolve("pending.txt")));
  Path held=Files.move(originalAlpha,root.resolve("held-alpha"));
  Path beta=root.resolve("roots/beta");
  Path alpha=beta;
  Path later=alpha.resolve("independent-later-sync.txt");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  var arm=new AtomicBoolean();
  var attempts=new AtomicInteger();var heldAttempt=new AtomicInteger();
  var admittedRow=new AtomicReference<Map<String,String>>();
  var faultObserved=new CountDownLatch(1);
  var target=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var injected=new AtomicBoolean();
  var walked=new AtomicInteger();var ref=new AtomicReference<KnowledgeServer>();
  try (var _=new ReleasedModelIdentities(root)) {
   try (var executors=new TestEngineExecutors();var server=bootBarrier(root,executors,t->{
    if(t.point().equals("release030-before-replay")) {
     int attempt=attempts.incrementAndGet();
     if(arm.get()&&once.compareAndSet(false,true)) {
      heldAttempt.set(attempt);arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
     }
    }
    if(t.point().equals("release030-root-walked")) {
     var q=queue(ref.get());
     // This checkpoint is in the gated attempt, before its finally can resume the producer.
     if(target.get()!=null && q.release030RecoveryState().rootWalked(target.get())
         && injected.compareAndSet(false,true)) {
      assertEquals(heldAttempt.get(),attempts.get(),"no intervening attempt released the continuous producer hold");
      assertPendingOwner(admittedRow.get(),q.watcherReconciliationRow(key(later)));
      if (owner.equals("root")) {
       var origin=SwitchBufferCapableQueue.RootOrigin.fromUnit(admittedRow.get().get("unit_revision"));
       assertNotNull(origin);assertEquals(target.get().retirementUuid(),origin.uuid());
       assertEquals(target.get().acceptedOrder(),origin.acceptedOrder());
      }
      boolean pendingAtCheckpoint = "PENDING".equals(q.watcherReconciliationRow(key(later)).get("state"));
      System.out.println("R18_PRIMARY_CHECKPOINT pending="+pendingAtCheckpoint+" row="+q.watcherReconciliationRow(key(later)));
      assertTrue(pendingAtCheckpoint,"target later walk durably admitted pending writer work");
      q.markFailed(later,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"independent transient writer wait"));
      walked.incrementAndGet();
      var row=q.watcherReconciliationRow(key(later));
      System.out.println("R18_POSTWALK_INJECTED token="+target.get()+" row="+row);
      assertEquals("PENDING",row.get("state"));
      for(String field:List.of("unit_revision","planned_source_sha256","collection","originator","transport","ingestion_root"))
       assertEquals(admittedRow.get().get(field),row.get(field),"injected outcome preserves target "+field);
      assertEquals("IO_FAILED",row.get("last_outcome_class"));
      assertEquals("SOURCE_UNREADABLE",row.get("last_reason_code"));
      assertEquals("RETRY_UNTIL_AVAILABLE",row.get("last_retry_policy"));
      assertEquals(1,Integer.parseInt(row.get("attempts")));
      assertTrue(Long.parseLong(row.get("retry_after"))>System.currentTimeMillis());
      capturePostwalkEvidence(root,ref.get(),fixture,owner,later);
      faultObserved.countDown();
      assertTrue(q.jobStateCountsStrict().pendingBackoffCount()>0);
      System.out.println("WALK_CHECKPOINT_WITH_PENDING_WRITER "+q.jobStateCountsStrict());
     }
    }
   })) {
    ref.set(server);activate(server);
    await(()->{var cp=Release030RecoveryState.load(root.resolve("data/jobs.db"));
      return queue(server).listSwitchBufferOpsStrict().stream().anyMatch(op->op.op().equals("SYNC_ROOT")
        &&op.key().startsWith("release030-root:")&&Path.of(SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(beta)
        &&cp.rootWalked(op));},"old automatic Beta token actually completes",90);
    var service=server.appServices().ingestService();
    var deleted=service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(alpha.toString()).build(),CallContext.none());
    assertTrue(deleted.getError().isBlank(),deleted.getError());
    var deletion=queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.op().equals("DELETE_PREFIX")
        &&io.justsearch.adapters.lucene.runtime.PathPrefixScope.decode(op.payload()).contains(key(later))).findFirst().orElseThrow();
    await(()->Release030RecoveryState.load(root.resolve("data/jobs.db")).deletionCommitted(deletion)
        &&Release030RecoveryState.load(root.resolve("data/jobs.db")).deletionQueueComplete(deletion),"earlier delete actually commits before final public sync",90);
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
    arm.set(true);assertTrue(arrived.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    assertFalse(Files.exists(later),"target cannot have an old automatic DONE admission");
    Files.writeString(later,"independentlatersyncviolet");
    if (owner.equals("exact")) {
     assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(later.toString())
         .setRootAdded(true).setForceReindex(true).build(),Release030RetirementSyncPolicyTest.defaultContext()).getAcceptedCount());
    }
    var synced=service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),Release030RetirementSyncPolicyTest.defaultContext());
    assertTrue(synced.getError().isBlank(),synced.getError());
    target.set(queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.op().equals("SYNC_ROOT")
        &&original.building_generation().equals(op.generation())
        &&op.key().equals("sync_root:"+io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(key(alpha)))
        &&Path.of(SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha))
        .max(Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder)).orElseThrow());
    admittedRow.set(queue(server).watcherReconciliationRow(key(later)));
    assertEquals("PENDING",admittedRow.get().get("state"));
    assertNotNull(admittedRow.get().get("unit_revision"));
    var exactReceipt=queue(server).retirementReplayOps().stream().filter(op -> op.op().equals("UPSERT")
        && SwitchBufferUpsert.decode(op.payload()).path().equals(key(later)))
        .max(Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder));
    if (owner.equals("root")) {
     assertTrue(exactReceipt.isEmpty(),"ordinary public ROOT has no per-file receipt");
     var origin=SwitchBufferCapableQueue.RootOrigin.fromUnit(admittedRow.get().get("unit_revision"));
     assertNotNull(origin);assertEquals(target.get().retirementUuid(),origin.uuid());
     assertEquals(target.get().acceptedOrder(),origin.acceptedOrder());
     assertNull(admittedRow.get().get("planned_source_sha256"));
    } else {
     var receipt=exactReceipt.orElseThrow();var file=SwitchBufferUpsert.decode(receipt.payload());
     assertTrue(receipt.acceptedOrder()>target.get().acceptedOrder(),"forced sync refreshes the publicly admitted exact owner");
     assertEquals(file.unitRevision(),admittedRow.get().get("unit_revision"));
     assertEquals(file.sourceSha256(),admittedRow.get().get("planned_source_sha256"));
     assertEquals(file.collection(),admittedRow.get().get("collection"));
     assertEquals(key(file.ingestionRoot()),admittedRow.get().get("ingestion_root"));
     assertEquals(file.provenance()==null?null:file.provenance().originator(),admittedRow.get().get("originator"));
     assertEquals(file.provenance()==null?null:file.provenance().transport(),admittedRow.get().get("transport"));
     System.out.println("R18_PUBLIC_EXACT_OWNER "+receipt);
    }
    assertFalse(queue(server).release030RecoveryState().rootWalked(target.get()));
    System.out.println("CONTINUOUS_PENDING_HOLD attempt="+heldAttempt.get()+" target="+target.get()+" row="+admittedRow.get());
    release.countDown();
    if (!faultObserved.await(30,TimeUnit.SECONDS)) {
     var task=(Future<?>)Release030ExtractionRecoveryTest.field(server,"release030RecoveryTask");
     if(task.isDone())task.get();
     fail("actual target fresh walk receives exactly one queue-outcome injection");
    }
    Release030RetirementRootImageTest.capture(root,server,"r18-postwalk-"+fixture+"-"+owner,List.of(later));
    Files.move(held,originalAlpha);
    await(()->state(root).previous_generation()==null,"retirement finishes after pending writer retry",100);
    assertTrue(walked.get()>0,"a complete root walk checkpoints real pending writer work");
    assertEquals(1,walked.get(),"exactly one transient failure at the target later walk");
    assertTrue(search(server,"independentlatersyncviolet").contains(key(later)),"older prefix delete cannot erase the later acknowledged forced-sync admission on retry");
    assertEquals(original.active_generation(),state(root).active_generation());
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   } finally { release.countDown(); }
   try(var executors=new TestEngineExecutors();var server=boot(root,executors)) {
    activate(server);assertTrue(search(server,"independentlatersyncviolet").contains(key(later)),"later forced-sync content survives actual restart");
   }
  } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }

 private static void capturePostwalkEvidence(Path root,KnowledgeServer server,String fixture,String owner,Path later)throws java.io.IOException {
  try { Release030RetirementRootImageTest.capture(root,server,"r18-hook-"+fixture+"-"+owner,List.of(later)); }
  catch(java.io.IOException failure){throw failure;}
  catch(Exception failure){throw new java.io.IOException("Postwalk evidence unavailable",failure);}
  String evidence=System.getenv("VERIFY_EVIDENCE");if(evidence==null)return;
  Path saved=Path.of(evidence).resolve("postwalk-stores-"+fixture+"-"+owner);
  for(int n=2;Files.exists(saved);n++)saved=Path.of(evidence).resolve("postwalk-stores-"+fixture+"-"+owner+"-"+n);
  Files.createDirectories(saved);
  try(var paths=Files.walk(root.resolve("data"))) {
   for(Path path:paths.filter(Files::isRegularFile).toList()) {
    Path relative=root.resolve("data").relativize(path);
    if(relative.startsWith("telemetry"))continue;
    Path copy=saved.resolve(relative);Files.createDirectories(copy.getParent());Files.copy(path,copy);
   }
  }
 }

 private static void assertPendingOwner(Map<String,String> expected,Map<String,String> actual) {
  for(String field:List.of("state","unit_revision","planned_source_sha256","collection","originator","transport","ingestion_root","attempts","retry_after"))
   assertEquals(expected.get(field),actual.get(field),"fresh replay preserves target "+field);
 }

 @Test void ordinaryOrderedPrefixThenSyncSurvivesRetryAndRestart(@TempDir Path temporary)throws Exception{
  Path root=Release030Fixture.unpack("small",temporary);Path alpha=root.resolve("roots/alpha");
  Path file=Files.writeString(alpha.resolve("independent-ordinary-sync.txt"),"independentordinarysyncviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  try(var _=new ReleasedModelIdentities(root)){
   try(var ex=new TestEngineExecutors();var s=boot(root,ex)){
    activate(s);await(()->queue(s).jobStateCountsStrict().pendingReadyCount()==0&&queue(s).jobStateCountsStrict().processingCount()==0,"initial recovery completes");
    var producer=(DefaultWorkerAppServices)s.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try{
     var d=s.appServices().ingestService().deleteByPath(DeleteByPathRequest.newBuilder().setPath(alpha.toString()).build(),CallContext.none());assertTrue(d.getError().isBlank(),d.getError());
     var sync=s.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none());assertTrue(sync.getError().isBlank(),sync.getError());
     queue(s).markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"independent ordinary writer wait"));
     assertTrue(queue(s).jobStateCountsStrict().pendingBackoffCount()>0);
    }finally{producer.resumeProducerAfterCutover();}
    await(()->search(s,"independentordinarysyncviolet").contains(key(file)),"ordinary later sync wins after writer retry",70);
   }
   try(var ex=new TestEngineExecutors();var s=boot(root,ex)){
    activate(s);assertTrue(search(s,"independentordinarysyncviolet").contains(key(file)),"ordinary later sync survives restart");
   }
  }finally{ConfigStore.restoreGlobal(installed,previous);}
 }
 @org.junit.jupiter.params.ParameterizedTest(name = "{0} then {1}")
 @org.junit.jupiter.params.provider.CsvSource({"sync,exact", "sync,prefix", "sync,collection", "prefix,sync", "collection,sync", "prefix,explicit"})
 void overlappingScopesKeepTheirAcceptedOrderAcrossRestart(String first, String last,
     @TempDir Path temporary) throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha");
  Path nested=Files.createDirectories(alpha.resolve("overlap"));
  Path file=Files.writeString(nested.resolve("ordered.txt"),"overlappedwalkviolet");
  Path sibling=Files.writeString(alpha.resolve("sibling.txt"),"ordersiblingviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  var exactOwners=new ArrayList<SwitchBufferUpsert>();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=namedBoot(root,ex,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {
     arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
    }
   })) {
    activate(server);assertTrue(arrived.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();
    assertTrue(producer.pauseProducerForCutover(10000));
    try {
     // Public admissions/deletes overlap a nested prefix with its root collection.
     var service=server.appServices().ingestService();
     if(first.equals("sync") && last.equals("collection")) {
      var watch=service.watchRoot(WatchRootRequest.newBuilder().setRootPath(alpha.toString())
          .setCollection("walk-order").setRootAdded(true).build(),CallContext.none());
      assertTrue(watch.getWatching(),watch.getErrorMessage());
     }
     if(first.equals("sync")) sync(service,alpha);
     else {
      admit(service,file);admit(service,sibling);
      if(first.equals("prefix")) prefix(service,nested);else collection(service);
     }
     switch(last) {
      case "exact" -> assertTrue(service.deleteById(DeleteByIdRequest.newBuilder()
          .setDocId(key(file)).build(),CallContext.none()).getError().isBlank());
      case "prefix" -> prefix(service,nested);
      case "collection" -> {
       assertNamedSyncAdmission(root,file,queue(server));
       var rawPath=key(alpha.resolve("pending.txt"));
       var refreshed=queue(server).retirementReplayOps().stream().filter(op->op.op().equals("UPSERT")
           &&op.generation().equals(original.building_generation())&&op.key().equals("path:"+rawPath))
           .max(Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder)).orElseThrow(
               ()->new AssertionError("public LIVE ROOT must refresh the already retained raw path"));
       var refreshedFile=SwitchBufferUpsert.decode(refreshed.payload());
       assertNotNull(refreshedFile.unitRevision(),"raw refresh has its actual new exact unit");
       assertNotNull(refreshedFile.sourceSha256(),"raw refresh has its admitted source witness");
       assertEquals("walk-order",refreshedFile.collection());
       System.out.println("LIVE_RAW_REFRESH "+refreshed);

       producer.resumeProducerAfterCutover();
       await(()->"DONE".equals(queue(server).watcherReconciliationRow(key(file)).get("state"))
           &&"walk-order".equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(
               key(file),io.justsearch.indexing.SchemaFields.COLLECTION))
           &&"walk-order".equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(
               key(alpha.resolve("pending.txt")),io.justsearch.indexing.SchemaFields.COLLECTION)),
           "public named sync commits Q and A membership before collection deletion",30);
       Release030RetirementRootImageTest.capture(root,server,"named-sync-before-delete",List.of(file,alpha.resolve("pending.txt")));
       assertTrue(producer.pauseProducerForCutover(10000));
       collection(service);
      }
      case "sync" -> sync(service,alpha);
      case "explicit" -> { Files.writeString(file,"overlappedwalkviolet");admit(service,file); }
      default -> fail(last);
     }
     var receipts=queue(server).listSwitchBufferOpsStrict();
     if(last.equals("explicit")) for(var receipt:receipts) {
      if(receipt.op().equals("UPSERT")) {
       var owner=SwitchBufferUpsert.decode(receipt.payload());
       if(owner.unitRevision()!=null) exactOwners.add(owner);
      }
     }
     System.out.println("ORDERED_PUBLIC_RECEIPTS "+receipts);
    } finally { release.countDown(); }
    await(()->state(root).previous_generation()==null,"ordered overlapping scopes retire",100);
    assertOrder(server,file,last);
    assertExactOwners(root,exactOwners);
   } finally {release.countDown();}
   try(var ex=new TestEngineExecutors();var server=namedBoot(root,ex,t->{})) {
    activate(server);assertOrder(server,file,last);
    assertExactOwners(root,exactOwners);
    assertEquals(original.active_generation(),state(root).active_generation());
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }
 private static void assertExactOwners(Path root,List<SwitchBufferUpsert> owners)throws Exception {
  try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("data/jobs.db"));
      var query=connection.prepareStatement("SELECT state,unit_revision,planned_source_sha256,collection,ingestion_root FROM jobs WHERE path=?")) {
   for(var owner:owners) {
    query.setString(1,owner.path());try(var row=query.executeQuery()) {
     assertTrue(row.next());assertEquals("DONE",row.getString("state"));
     assertEquals(owner.unitRevision(),row.getString("unit_revision"),"older recovery preserves later exact unit");
     assertEquals(owner.sourceSha256(),row.getString("planned_source_sha256"));
     assertEquals(owner.collection(),row.getString("collection"));
     assertEquals(key(owner.ingestionRoot()),row.getString("ingestion_root"));
     System.out.println("PRESERVED_EXACT_OWNER "+owner);
    }
   }
  }
 }
 private static void assertNamedSyncAdmission(Path root,Path file,SqliteJobQueue queue)throws Exception {
  var sync=queue.listSwitchBufferOpsStrict().stream().filter(op->op.op().equals("SYNC_ROOT")
      && !op.key().startsWith("release030-root:")).reduce((a,b)->b).orElseThrow();
  assertEquals(root.resolve("roots/alpha"),Path.of(SwitchBufferSyncRoot.decode(sync.payload()).rootPath()));
  try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("data/jobs.db"));
      var query=connection.prepareStatement("SELECT collection,state,unit_revision FROM jobs WHERE path=?")) {
   query.setString(1,key(file));try(var row=query.executeQuery()) {
    assertTrue(row.next());assertEquals("walk-order",row.getString("collection"));
    System.out.println("NAMED_SYNC_MEMBER receipt="+sync+" path="+file+" state="+row.getString("state")
        +" unit="+row.getString("unit_revision"));
   }
  }
 }
 private static void assertOrder(KnowledgeServer server,Path file,String last)throws Exception {
  boolean present=last.equals("sync")||last.equals("explicit");
  assertEquals(present,search(server,"overlappedwalkviolet").contains(key(file)),
      "last acknowledged overlapping scope wins even while the source stays present");
  if(!present) assertEquals(0,server.lifecycleManagerForTests().indexCountOps().countByIdAndChunksStrict(key(file)));
 }
 private static void sync(io.justsearch.indexerworker.services.WorkerIngestService service,Path root) {
  var response=service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(true).build(),Release030RetirementSyncPolicyTest.defaultContext());
  assertTrue(response.getError().isBlank(),response.getError());
 }
 private static void prefix(io.justsearch.indexerworker.services.WorkerIngestService service,Path root) {
  var response=service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(root.toString()).build(),CallContext.none());
  assertTrue(response.getError().isBlank(),response.getError());
 }
 private static void collection(io.justsearch.indexerworker.services.WorkerIngestService service) {
  var response=service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("walk-order").build(),CallContext.none());
  assertTrue(response.getError().isBlank(),response.getError());
 }
 private static void admit(io.justsearch.indexerworker.services.WorkerIngestService service,Path file) {
  assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString())
      .setTargetCollection("walk-order").setRootAdded(true).setForceReindex(true).build(),CallContext.none()).getAcceptedCount());
 }
 static KnowledgeServer namedBoot(Path root,TestEngineExecutors ex,MigrationTransitionBarrier.Hook hook)throws Exception {
  return Release030RetirementPublicSyncTest.namedBoot(root,ex,hook);
 }
 @org.junit.jupiter.params.ParameterizedTest(name = "repeated target faults / restore earlier DB={0}")
 @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
 void repeatedFaultsRetainDebtAndDeletionProofAcrossRestart(boolean restore,@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha");Path file=Files.writeString(alpha.resolve("repeated-sync.txt"),"repeatedwalkviolet");
  Path backup=root.resolve("same-acknowledgements.db");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  var injected=new AtomicBoolean();var observed=new CountDownLatch(1);
  var target=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var ref=new AtomicReference<KnowledgeServer>();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=bootBarrier(root,ex,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {
     arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
    }
    if(t.point().equals("release030-root-walked")&&target.get()!=null
        && Release030RecoveryState.load(root.resolve("data/jobs.db")).rootWalked(target.get())
        && injected.compareAndSet(false,true)) {
     var q=queue(ref.get());
     var failure=IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,
         IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"genuine repeated target-writer wait");
     q.markFailed(file,failure);q.markFailed(file,failure);
     assertRepeatedPending(root,file,target.get());observed.countDown();
    }
   })) {
    ref.set(server);activate(server);assertTrue(arrived.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try {
     prefix(server.appServices().ingestService(),alpha);sync(server.appServices().ingestService(),alpha);
     target.set(queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.op().equals("SYNC_ROOT")
         && !op.key().startsWith("release030-root:")
         && Path.of(SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha)).findFirst().orElseThrow());
     if(restore) {
      try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("data/jobs.db"));
          var statement=connection.createStatement()) {statement.execute("VACUUM INTO '"+backup+"'");}
      System.out.println("EARLIER_SAME_ACK_DB "+backup+" receipt="+target.get());
     }
    } finally {release.countDown();}
    assertTrue(observed.await(30,TimeUnit.SECONDS),"repeated fault is at the target completed walk");
    assertRepeatedPending(root,file,target.get());
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
    observeDeletionProof(root);
   } finally {release.countDown();}
   if(restore) Files.copy(backup,root.resolve("data/jobs.db"),StandardCopyOption.REPLACE_EXISTING);
   for(int restart=0;restart<2;restart++) {
    var attempts=new AtomicInteger();
    try(var ex=new TestEngineExecutors();var server=bootBarrier(root,ex,t->{
     if(t.point().equals("release030-before-replay")) attempts.incrementAndGet();
    })) {
     activate(server);
     if(restore) {
      await(()->state(root).previous_generation()==null,"supported DB replacement replays the same acknowledged walk",100);
      assertTrue(search(server,"repeatedwalkviolet").contains(key(file)));
      Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
     } else {
      await(()->attempts.get()>=2||state(root).previous_generation()==null,"real retry resumes without waiting for backoff eligibility",30);
      assertEquals(original.building_generation(),state(root).previous_generation());
      assertRepeatedPending(root,file,target.get());observeDeletionProof(root);
      Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
     }
     assertTrue(Files.isRegularFile(file));
     assertTrue(search(server,"nebula").contains(key(root.resolve("roots/beta/file-02.txt"))));
     assertEquals(original.active_generation(),state(root).active_generation());
    }
   }
  } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }
 private static void observeDeletionProof(Path root)throws Exception {
  var properties=new Properties();try(var in=Files.newInputStream(root.resolve("data/upgrade-030-recovery.properties"))) {properties.load(in);}
  System.out.println("DELETION_CHECKPOINTS "+properties.stringPropertyNames().stream()
      .filter(k->k.startsWith("retire|delete|")).sorted().toList());
 }
 private static void assertRepeatedPending(Path root,Path file,SwitchBufferCapableQueue.SwitchBufferOp target)throws java.io.IOException {
  try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro");
      var query=connection.prepareStatement("SELECT state,attempts,retry_after FROM jobs WHERE path=?")) {
   query.setString(1,key(file));try(var row=query.executeQuery()) {
    assertTrue(row.next());long now=System.currentTimeMillis();
    System.out.println("REPEATED_TARGET_RETRY count=2 receipt="+target+" state="+row.getString(1)+" attempts="+row.getInt(2)+" retry_after="+row.getLong(3)+" now="+now);
    assertEquals("PENDING",row.getString(1));assertEquals(2,row.getInt(2));assertTrue(row.getLong(3)>now);
   }
  }catch(java.sql.SQLException failure){throw new java.io.IOException(failure);}
 }
 @Test
 void newerDeleteUsingTheSameKeyCannotBorrowTheOldReceiptProof(@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha");Path file=Files.writeString(alpha.resolve("same-key.txt"),"samekeyviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  var injected=new AtomicBoolean();var observed=new CountDownLatch(1);
  var target=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var ref=new AtomicReference<KnowledgeServer>();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=bootBarrier(root,ex,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {
     arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
    }
    if(t.point().equals("release030-root-walked")&&target.get()!=null
        && Release030RecoveryState.load(root.resolve("data/jobs.db")).rootWalked(target.get())
        && injected.compareAndSet(false,true)) {
     var q=queue(ref.get());q.markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
         IngestionReasonCodes.SOURCE_UNREADABLE,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"same-key genuine writer retry"));
     observeRetry(root,file,target.get(),1);observed.countDown();
    }
   })) {
    ref.set(server);activate(server);assertTrue(arrived.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    SwitchBufferCapableQueue.SwitchBufferOp old;
    try {
     prefix(server.appServices().ingestService(),alpha);sync(server.appServices().ingestService(),alpha);
     var journal=queue(server).listSwitchBufferOpsStrict();
     old=journal.stream().filter(op->op.op().equals("DELETE_PREFIX")).findFirst().orElseThrow();
     target.set(journal.stream().filter(op->op.op().equals("SYNC_ROOT")&&!op.key().startsWith("release030-root:")
         && Path.of(SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha)).findFirst().orElseThrow());
    } finally {release.countDown();}
    assertTrue(observed.await(30,TimeUnit.SECONDS));observeDeletionProof(root);
    prefix(server.appServices().ingestService(),alpha);
    var newer=queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.key().equals(old.key())&&op.generation().equals(old.generation())).findFirst().orElseThrow();
    assertEquals(old.key(),newer.key());assertNotEquals(old.revision(),newer.revision(),"a real replacement receives its own receipt version");
    System.out.println("REPLACED_DELETE_RECEIPT old="+old+" new="+newer);
    await(()->state(root).previous_generation()==null,"newer same-key delete settles before retirement",100);
    assertTrue(Files.isRegularFile(file));assertTrue(search(server,"samekeyviolet").isEmpty());
    assertEquals(0,server.lifecycleManagerForTests().indexCountOps().countByIdAndChunksStrict(key(file)));
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   } finally {release.countDown();}
   try(var ex=new TestEngineExecutors();var server=boot(root,ex)) {
    activate(server);assertTrue(search(server,"samekeyviolet").isEmpty());assertTrue(Files.isRegularFile(file));
    assertEquals(original.active_generation(),state(root).active_generation());
    assertTrue(search(server,"nebula").contains(key(root.resolve("roots/beta/file-02.txt"))));
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  }finally{release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }
 static void observeRetry(Path root,Path file,SwitchBufferCapableQueue.SwitchBufferOp target,int count)throws java.io.IOException {
  try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro");
      var query=connection.prepareStatement("SELECT state,attempts,retry_after FROM jobs WHERE path=?")) {
   query.setString(1,key(file));try(var row=query.executeQuery()) {
    assertTrue(row.next(),"target walk has a durable writer job");long now=System.currentTimeMillis();
    var observation=Map.of("targetReceipt",target.toString(),"injectionCount",count,"state",row.getString(1),
        "attempts",row.getInt(2),"retryAfter",row.getLong(3),"now",now);
    System.out.println("SINGLE_TARGET_RETRY "+observation);
    assertEquals("PENDING",row.getString(1));assertEquals(1,row.getInt(2));assertTrue(row.getLong(3)>now);
   }
  }catch(java.sql.SQLException failure){throw new java.io.IOException(failure);}
 }
 static SqliteJobQueue queue(KnowledgeServer s) { try { return Release030RetirementSafetyTest.queue(s); } catch (Exception failure) { throw new IllegalStateException(failure); } }
 static KnowledgeServer bootBarrier(Path root,TestEngineExecutors ex,MigrationTransitionBarrier.Hook hook)throws Exception {
  return Release030RetirementOrderingTest.bootWithBarrier(root,ex,hook);
 }
}
