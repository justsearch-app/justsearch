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
 @org.junit.jupiter.params.ParameterizedTest(name = "{0} delete then later sync")
 @org.junit.jupiter.params.provider.ValueSource(strings = {"migrating", "switching"})
 void acknowledgedPrefixThenForcedSyncSurvivesPendingWriterRetry(String fixture, @TempDir Path temporary) throws Exception {
  Path root=Release030Fixture.unpack(fixture,temporary);
  var original=state(root);
  Path alpha=root.resolve("roots/alpha");
  Path later=Files.writeString(alpha.resolve("independent-later-sync.txt"),"independentlatersyncviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  var target=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var injected=new AtomicBoolean();
  var walked=new AtomicInteger();var ref=new AtomicReference<KnowledgeServer>();
  try (var models=new ReleasedModelIdentities(root)) {
   try (var executors=new TestEngineExecutors();var server=bootBarrier(root,executors,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {
     arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
    }
    if(t.point().equals("release030-root-walked")) {
     var q=queue(ref.get());
     // Keep actual walk admissions waiting for their real writer, as a transient I/O failure does.
     if(target.get()!=null && Release030RecoveryState.load(root.resolve("data/jobs.db")).rootWalked(target.get())
         && injected.compareAndSet(false,true)) {
      assertTrue(q.hasNonterminalJobsByPathPrefixStrict(key(alpha)),"target later walk durably admitted pending writer work");
      q.markFailed(later,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"independent transient writer wait"));
      walked.incrementAndGet();
      observeRetry(root,later,target.get(),walked.get());
      assertTrue(q.jobStateCountsStrict().pendingBackoffCount()>0);
      System.out.println("WALK_CHECKPOINT_WITH_PENDING_WRITER "+q.jobStateCountsStrict());
     }
    }
   })) {
    ref.set(server);activate(server);assertTrue(arrived.await(30,TimeUnit.SECONDS),"retirement attempt reaches real replay");
    var producer=(DefaultWorkerAppServices)server.appServices();
    assertTrue(producer.pauseProducerForCutover(10000));
    try {
     var deleted=server.appServices().ingestService().deleteByPath(DeleteByPathRequest.newBuilder().setPath(alpha.toString()).build(),CallContext.none());
     assertTrue(deleted.getError().isBlank(),deleted.getError());
     var synced=server.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none());
     assertTrue(synced.getError().isBlank(),synced.getError());
     assertTrue(queue(server).hasNonterminalJobsByPathPrefixStrict(key(alpha)),"forced sync actually admits current files");
     var journal=queue(server).listSwitchBufferOpsStrict();
     assertTrue(journal.stream().anyMatch(op->op.op().equals("DELETE_PREFIX")));
     assertTrue(journal.stream().anyMatch(op->op.op().equals("SYNC_ROOT")&&!op.key().startsWith("release030-root:")));
     target.set(journal.stream().filter(op->op.op().equals("SYNC_ROOT")&&!op.key().startsWith("release030-root:")
         && Path.of(SwitchBufferSyncRoot.decode(op.payload()).rootPath()).equals(alpha)).findFirst().orElseThrow());
     System.out.println("ACKNOWLEDGED_DELETE_THEN_SYNC "+journal);
    } finally { release.countDown(); }
    await(()->state(root).previous_generation()==null,"retirement finishes after pending writer retry",100);
    assertTrue(walked.get()>0,"a complete root walk checkpoints real pending writer work");
    assertEquals(1,walked.get(),"exactly one transient failure at the target later walk");
    assertTrue(search(server,"independentlatersyncviolet").contains(key(later)),"older prefix delete cannot erase the later acknowledged forced-sync admission on retry");
    assertEquals(original.active_generation(),state(root).active_generation());
   } finally { release.countDown(); }
   try(var executors=new TestEngineExecutors();var server=boot(root,executors)) {
    activate(server);assertTrue(search(server,"independentlatersyncviolet").contains(key(later)),"later forced-sync content survives actual restart");
   }
  } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }
 @Test void ordinaryOrderedPrefixThenSyncSurvivesRetryAndRestart(@TempDir Path temporary)throws Exception{
  Path root=Release030Fixture.unpack("small",temporary);Path alpha=root.resolve("roots/alpha");
  Path file=Files.writeString(alpha.resolve("independent-ordinary-sync.txt"),"independentordinarysyncviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  try(var models=new ReleasedModelIdentities(root)){
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
  try(var models=new ReleasedModelIdentities(root)) {
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
  var response=service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(true).build(),CallContext.none());
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
  var server=org.mockito.Mockito.spy(new KnowledgeServer(ex,WorkerBootFixture.workerConfig(root.resolve("data")),null));
  org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
  server.bindBootRootBindings(List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"),"walk-order"),
      new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"),null)));
  server.bindExclusionRoots(()->List.of(root.resolve("roots/alpha"),root.resolve("roots/beta")));
  server.installMigrationBarrierForTests(hook);
  try {server.start();} catch(Exception|Error failure) {server.close();throw failure;}return server;
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
  try(var models=new ReleasedModelIdentities(root)) {
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
  try(var models=new ReleasedModelIdentities(root)) {
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
