/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementForceFalseTest {
 @Test void indexedOrdinaryMemberKeepsOlderNamespaceAcrossForceFalseAndDelete(@TempDir Path root)throws Exception {
  exercise(root,false);
 }
 @Test void readableChangedOlderMemberCompletesInOriginalNamespaceAndRestarts(@TempDir Path root)throws Exception {
  exercise(root,true);
 }
 private static void exercise(Path root,boolean changed)throws Exception {
  Release030Fixture.unpack("switching",root);var original=state(root);
  Path alpha=root.resolve("roots/alpha"),held=Files.move(alpha,root.resolve("held-alpha")),beta=root.resolve("roots/beta");
  Path file=Files.writeString(beta.resolve("older-d.txt"),"oldernamespaceviolet"),missing=beta.resolve("new-c.txt");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  Map<String,String> before;
  try(var models=new ReleasedModelIdentities(root)) {
   var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,"older-d",t->{if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}})) {
    activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));
    var response=server.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(beta.toString()).setForce(true).build(),Release030RetirementSyncPolicyTest.defaultContext());
    assertTrue(response.getError().isBlank(),response.getError());
    await(()->search(server,"oldernamespaceviolet").contains(key(file))&&"DONE".equals(queue(server).watcherReconciliationRow(key(file)).get("state")),"ordinary D ROOT member actually commits",30);
    before=queue(server).watcherReconciliationRow(key(file));assertEquals("older-d",before.get("collection"));assertNotNull(SwitchBufferCapableQueue.RootOrigin.fromUnit(before.get("unit_revision")));
    assertFalse(queue(server).retirementReplayOps().stream().anyMatch(op->op.key().equals("path:"+key(file))));
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());save(root,server,"force-false-original-d",List.of(file));release.countDown();
   }finally{release.countDown();}
   var entered2=new CountDownLatch(1);var release2=new CountDownLatch(1);var once2=new AtomicBoolean();var target=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,"new-c",t->{
    if(t.point().equals("release030-before-replay")&&once2.compareAndSet(false,true)){entered2.countDown();assertTrue(release2.await(30,TimeUnit.SECONDS));}
   })) {
    activate(server);assertTrue(entered2.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try {
     Files.writeString(missing,"newnamespaceviolet");
     var response=server.appServices().ingestService().syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(beta.toString()).setForce(false).build(),Release030RetirementSyncPolicyTest.defaultContext());
     assertTrue(response.getError().isBlank(),response.getError());
     assertEquals(before,queue(server).watcherReconciliationRow(key(file)),"native force-false immediately leaves indexed D unchanged");
     target.set(queue(server).retirementReplayOps().stream().filter(op->op.op().equals("SYNC_ROOT")&&!op.key().startsWith("release030-root:")&&!SwitchBufferSyncRoot.decode(op.payload()).force()).max(Comparator.comparingLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder)).orElseThrow());
    }finally{producer.resumeProducerAfterCutover();}
    await(()->search(server,"newnamespaceviolet").contains(key(missing))&&"DONE".equals(queue(server).watcherReconciliationRow(key(missing)).get("state")),"force-false actually admits missing member to C",30);
    assertEquals("new-c",queue(server).watcherReconciliationRow(key(missing)).get("collection"));
    release2.countDown();
    await(()->Release030RecoveryState.load(root.resolve("data/jobs.db")).rootEnumerated(target.get()),"actual replay enumerates force-false ROOT",90);
    save(root,server,"force-false-walk",List.of(file,missing));
    assertEquals(before,queue(server).watcherReconciliationRow(key(file)),"replay preserves complete older D row, identity, boundary, attempts and outcome");
    assertTrue(server.appServices().ingestService().deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection("new-c").build(),CallContext.none()).getError().isBlank());
    assertTrue(search(server,"oldernamespaceviolet").contains(key(file)));assertTrue(search(server,"nebula").contains(key(alpha.resolve("file-01.txt"))));
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
    if(changed) {
     Files.writeString(file,"changedolderviolet");
     try {
      await(()->search(server,"changedolderviolet").contains(key(file))
          &&"DONE".equals(queue(server).watcherReconciliationRow(key(file)).get("state")),
          "readable changed D source becomes claimable and completes beneath force-false C",90);
      var corrected=queue(server).watcherReconciliationRow(key(file));
      assertEquals("older-d",corrected.get("collection"));
      assertEquals(before.get("ingestion_root"),corrected.get("ingestion_root"));
      assertEquals(before.get("originator"),corrected.get("originator"));assertEquals(before.get("transport"),corrected.get("transport"));
      assertNotEquals(before.get("unit_revision"),corrected.get("unit_revision"));
      assertEquals(SwitchBufferCapableQueue.RootOrigin.fromUnit(before.get("unit_revision")).uuid(),
          SwitchBufferCapableQueue.RootOrigin.fromUnit(corrected.get("unit_revision")).uuid());
      assertFalse(queue(server).retirementReplayOps().stream().anyMatch(op->op.key().equals("path:"+key(file))));
      Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
     } finally {save(root,server,"force-false-changed-d",List.of(file,missing));}
    }
    Files.move(held,alpha);
    await(()->state(root).previous_generation()==null,"preserved older owner settles before retirement",100);
    assertTrue(search(server,changed?"changedolderviolet":"oldernamespaceviolet").contains(key(file)));assertEquals(original.active_generation(),state(root).active_generation());
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }finally{release2.countDown();}
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,"new-c",t->{})) {
    activate(server);assertTrue(search(server,changed?"changedolderviolet":"oldernamespaceviolet").contains(key(file)));Release030RetirementTransitiveOrderTest.assertCollection(root,file,"older-d");
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  }finally{try{if(Files.exists(held))Files.move(held,alpha);save(root,null,"force-false-final",List.of(file,missing));}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
 private static void save(Path root,KnowledgeServer server,String label,List<Path> files)throws Exception {
  Release030RetirementRootImageTest.capture(root,server,label,files);
  String evidence=System.getenv("VERIFY_EVIDENCE");if(evidence==null)return;
  Path saved=Path.of(evidence).resolve(label+"-stores-nonatomic");
  for(int n=2;Files.exists(saved);n++)saved=Path.of(evidence).resolve(label+"-stores-nonatomic-"+n);
  Files.createDirectories(saved);
  for(String directory:List.of("data","roots","held-alpha")) {
   Path base=root.resolve(directory);if(!Files.isDirectory(base))continue;
   try(var walk=Files.walk(base)) {
    for(Path source:walk.filter(Files::isRegularFile).toList()) {
     Path target=saved.resolve(root.relativize(source));Files.createDirectories(target.getParent());Files.copy(source,target);
    }
   }
  }
  Files.writeString(saved.resolve("capture.txt"),"Non-atomic live copy. Source: "+root+" UTC: "+java.time.Instant.now());
 }
 private static KnowledgeServer boot(Path root,TestEngineExecutors ex,String collection,MigrationTransitionBarrier.Hook hook)throws Exception {
  var server=org.mockito.Mockito.spy(new KnowledgeServer(ex,WorkerBootFixture.workerConfig(root.resolve("data")),null));org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
  server.bindBootRootBindings(List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"),null),new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"),collection)));
  server.bindExclusionRoots(()->List.of(root.resolve("roots/alpha"),root.resolve("roots/beta")));server.installMigrationBarrierForTests(hook);try{server.start();}catch(Exception|Error failed){server.close();throw failed;}return server;
 }
}
