/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
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
@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementTransitiveOrderTest {
 static final String C="order-c";
 @Test void transitiveDeferredCollectionCannotEraseLaterBetaSync(@TempDir Path root)throws Exception {
  Release030Fixture.unpack("switching",root);var original=state(root);Path alpha=root.resolve("roots/alpha"),beta=root.resolve("roots/beta");
  Path target=Files.writeString(beta.resolve("transitive-target.txt"),"transitivebetaviolet");
  Path held=root.resolve("held-alpha");ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean();var attempts=new AtomicInteger();
  var targetOp=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var deletion=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();
  var firstVisibility=new AtomicBoolean();var tokenBeforeDelete=new AtomicBoolean();var serverRef=new AtomicReference<KnowledgeServer>();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=bootRoots(root,ex,true,t->{
    try {
    if(t.point().equals("release030-before-replay")) {attempts.incrementAndGet();if(first.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}}
    if(t.point().equals("release030-root-walked")&&targetOp.get()!=null) {
     var cp=Release030RecoveryState.load(root.resolve("data/jobs.db"));
     if(cp.rootWalked(targetOp.get())&&!cp.deletionCommitted(deletion.get())) {
      tokenBeforeDelete.set(true);capture(root,serverRef.get(),"transitive-walk-before-delete",List.of(target));
     }
    }
    } catch(java.io.IOException|InterruptedException failure) {throw failure;}
      catch(Exception diagnosticFailure) {throw new java.io.IOException("probe observation failed",diagnosticFailure);}
   })) {
    serverRef.set(server);activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try {
     var service=server.appServices().ingestService();watch(service,alpha,C);watch(service,beta,C);
     sync(service,alpha);assertCollection(root,alpha.resolve("file-01.txt"),C);
     // The released A image predates this live collection registration. Establish its
     // real named writer effect before testing a named delete while Alpha is absent.
     producer.resumeProducerAfterCutover();
     await(()->"DONE".equals(queue(server).watcherReconciliationRow(key(alpha.resolve("pending.txt"))).get("state"))
         &&C.equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(
             key(alpha.resolve("pending.txt")),io.justsearch.indexing.SchemaFields.COLLECTION)),
         "public Alpha named registration commits in Q and A before named deletion",30);
     capture(root,server,"transitive-named-before-delete",List.of(alpha.resolve("pending.txt"),alpha.resolve("file-01.txt")));
     assertTrue(producer.pauseProducerForCutover(10000));
     Files.move(alpha,held);assertFalse(Files.exists(alpha));assertTrue(Files.isDirectory(held));
     var del=service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection(C).build(),CallContext.none());assertTrue(del.getError().isBlank(),del.getError());
     sync(service,beta);assertCollection(root,target,C);
     var ops=queue(server).listSwitchBufferOpsStrict();targetOp.set(rootOp(ops,beta,false));
     deletion.set(ops.stream().filter(o->o.op().equals("DELETE_COLLECTION")&&o.payload().equals(C)).findFirst().orElseThrow());
     var alphaOp=rootOp(ops,alpha,false);assertTrue(ops.indexOf(alphaOp)<ops.indexOf(deletion.get())&&ops.indexOf(deletion.get())<ops.indexOf(targetOp.get()),"actual AlphaSync/deleteC/BetaSync accepted order");
     capture(root,server,"transitive-acknowledged",List.of(target));
    } finally {release.countDown();}
    await(()->attempts.get()>=2&&queue(server).jobStateCountsStrict().pendingReadyCount()==0&&queue(server).jobStateCountsStrict().processingCount()==0,"initial unavailable-alpha replay attempts and ordinary writer settle",90);
    assertEquals(original.building_generation(),state(root).previous_generation());
    capture(root,server,"transitive-before-return",List.of(target));
    Files.move(held,alpha);
    await(()->state(root).previous_generation()==null,"transitive ordered scopes retire after Alpha returns through normal cooldown",100);
    capture(root,server,"transitive-retired",List.of(target));
    assertTrue(Files.isRegularFile(target));firstVisibility.set(search(server,"transitivebetaviolet").contains(key(target)));
    assertEquals(original.active_generation(),state(root).active_generation());
   } finally {release.countDown();}
   try(var ex=new TestEngineExecutors();var server=bootRoots(root,ex,true,t->{})) {
    activate(server);capture(root,server,"transitive-restart",List.of(target));
    boolean restartedVisible=search(server,"transitivebetaviolet").contains(key(target));
    System.out.println("TRANSITIVE_VISIBILITY first="+firstVisibility.get()+" restart="+restartedVisible+" tokenBeforeDelete="+tokenBeforeDelete.get());
    assertTrue(firstVisibility.get(),"later acknowledged Beta sync survives intervening deferred collection delete on first boot");
    assertTrue(restartedVisible,"later acknowledged Beta sync remains searchable after restart");assertEquals(original.active_generation(),state(root).active_generation());Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  } finally {release.countDown();try {if(Files.exists(held)&&!Files.exists(alpha))Files.move(held,alpha);capture(root,null,"transitive-final",List.of(target));System.out.println("BETA_TOKEN_BEFORE_DELETE="+tokenBeforeDelete.get());}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
 static KnowledgeServer bootRoots(Path root,TestEngineExecutors ex,boolean named,MigrationTransitionBarrier.Hook hook)throws Exception {
  var server=org.mockito.Mockito.spy(new KnowledgeServer(ex,WorkerBootFixture.workerConfig(root.resolve("data")),null));org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
  server.bindBootRootBindings(List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"),named?C:null),new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"),named?C:null)));
  server.bindExclusionRoots(()->List.of(root.resolve("roots/alpha"),root.resolve("roots/beta")));server.installMigrationBarrierForTests(hook);try{server.start();}catch(Exception|Error f){server.close();throw f;}return server;
 }
 static SwitchBufferCapableQueue.SwitchBufferOp rootOp(List<SwitchBufferCapableQueue.SwitchBufferOp> ops,Path path,boolean automatic){return ops.stream().filter(o->o.op().equals("SYNC_ROOT")&&o.key().startsWith("release030-root:")==automatic&&Path.of(SwitchBufferSyncRoot.decode(o.payload()).rootPath()).equals(path)).reduce((a,b)->b).orElseThrow();}
 static void watch(io.justsearch.indexerworker.services.WorkerIngestService s,Path p,String c){var r=s.watchRoot(WatchRootRequest.newBuilder().setRootPath(p.toString()).setCollection(c).setRootAdded(true).build(),CallContext.none());assertTrue(r.getWatching(),r.getErrorMessage());}
 static void sync(io.justsearch.indexerworker.services.WorkerIngestService s,Path p){var r=s.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(p.toString()).setForce(true).build(),CallContext.none());assertTrue(r.getError().isBlank(),r.getError());assertFalse(r.getSkipped());assertFalse(r.getDeleteDetectionUnverified());}
 static void assertCollection(Path root,Path file,String collection)throws Exception {Release030RetirementPublicSyncTest.assertCollection(root,file,collection);}
 static void write(Path root,String label,Object data)throws Exception {Release030RetirementPublicSyncTest.write(root,label,data);}
 static void capture(Path root,KnowledgeServer server,String label,List<Path> paths)throws Exception {Release030RetirementPublicSyncTest.capture(root,server,label,paths);}
}
