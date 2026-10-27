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
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ordinary ROOT ownership across source uncertainty, real retention, convergence and restart. */
@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementRootImageTest {
 @ParameterizedTest(name="{displayName} ordinaryRootImage / live named collection={0}")
 @ValueSource(booleans={false,true})
 void acknowledgedImageSurvivesUncertaintyThenConverges(boolean named,@TempDir Path root)throws Exception {
  Release030Fixture.unpack("switching",root);var original=state(root);
  Path alpha=root.resolve("roots/alpha"),beta=root.resolve("roots/beta"),held=root.resolve("held-alpha"),file=beta.resolve("ordinary-image.txt");
  Path unrelated=Files.writeString(root.resolve("unrelated-ack.txt"),"unrelatedackviolet");
  String collection=named?"live-order-c":"default";
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);Set<PosixFilePermission> permissions=null;
  var initial=new AtomicBoolean();var initialEntered=new CountDownLatch(1);var initialRelease=new CountDownLatch(1);
  var pause=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new AtomicInteger();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,named,t->{
    if(t.point().equals("release030-before-replay")) {
     attempts.incrementAndGet();
     if(initial.compareAndSet(false,true)){initialEntered.countDown();assertTrue(initialRelease.await(30,TimeUnit.SECONDS));}
     else if(pause.compareAndSet(true,false)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}
    }
   })) {
    activate(server);assertTrue(initialEntered.await(30,TimeUnit.SECONDS));var service=server.appServices().ingestService();
    assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(unrelated.toString()).setTargetCollection("unrelated").setRootAdded(true).build(),CallContext.none()).getAcceptedCount());
    await(()->search(server,"unrelatedackviolet").contains(key(unrelated)),"actual unrelated acknowledgement is searchable before collection deletion",30);
    // Actual public registration and completed admission while available records Alpha's witness.
    Release030RetirementTransitiveOrderTest.watch(service,alpha,collection);Release030RetirementTransitiveOrderTest.sync(service,alpha);
    await(()->"DONE".equals(queue(server).watcherReconciliationRow(key(alpha.resolve("file-01.txt"))).get("state"))
        &&collection.equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(alpha.resolve("file-01.txt")),io.justsearch.indexing.SchemaFields.COLLECTION)),
        "real available Alpha collection admission commits before removal",30);
    Files.move(alpha,held);initialRelease.countDown();
    await(()->{var auto=Release030RetirementTransitiveOrderTest.rootOp(queue(server).listSwitchBufferOpsStrict(),beta,true);
      return Release030RecoveryState.load(root.resolve("data/jobs.db")).rootWalked(auto)&&queue(server).jobStateCountsStrict().pendingReadyCount()==0&&queue(server).jobStateCountsStrict().processingCount()==0;
    },"old automatic Beta token genuinely completes while recorded Alpha is unavailable",90);
    pause.set(true);assertTrue(entered.await(30,TimeUnit.SECONDS));var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    Files.writeString(file,"ordinaryackimageviolet");
    if(named) {
      Release030RetirementTransitiveOrderTest.watch(service,beta,collection);
      assertTrue(service.deleteByCollection(DeleteByCollectionRequest.newBuilder().setCollection(collection).build(),CallContext.none()).getError().isBlank());
    } else assertTrue(service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(beta.toString()).build(),CallContext.none()).getError().isBlank());
    Release030RetirementTransitiveOrderTest.sync(service,beta);
    var ops=queue(server).listSwitchBufferOpsStrict();var later=Release030RetirementTransitiveOrderTest.rootOp(ops,beta,false);
    var deletion=ops.stream().filter(op->named?op.op().equals("DELETE_COLLECTION")&&op.payload().equals(collection):op.op().equals("DELETE_PREFIX")).reduce((a,b)->b).orElseThrow();
    assertTrue(later.acceptedOrder()>deletion.acceptedOrder());assertFalse(ops.stream().anyMatch(op->op.key().equals("path:"+key(file))),"ordinary ROOT member acquires no exact receipt");
    producer.resumeProducerAfterCutover();
    await(()->search(server,"ordinaryackimageviolet").contains(key(file))&&"DONE".equals(queue(server).watcherReconciliationRow(key(file)).get("state")),"later public ROOT writer completed",30);
    assertEquals(collection,Objects.toString(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(file),io.justsearch.indexing.SchemaFields.COLLECTION),"default"));
    String unit=queue(server).watcherReconciliationRow(key(file)).get("unit_revision");
    // Obsolete: newly admitted inherited metadata cannot be represented by root1's opaque nonce.
    // New: root2 retains the exact original receipt position and binds resolved member metadata.
    assertTrue(unit.startsWith("root2:"));assertEquals(new SwitchBufferCapableQueue.RootOrigin(later.retirementUuid(),later.acceptedOrder()),SwitchBufferCapableQueue.RootOrigin.fromUnit(unit));assertEquals(io.justsearch.indexerworker.loop.SourceContentHash.sha256(file),server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(file),io.justsearch.indexing.SchemaFields.SOURCE_SHA256));
    permissions=Files.getPosixFilePermissions(file);Files.setPosixFilePermissions(file,Set.of());assertFalse(Files.isReadable(file));
    int before=attempts.get();release.countDown();await(()->attempts.get()>before+1,"ordered replay observes later source uncertainty",30);
    assertTrue(Files.isRegularFile(file));assertTrue(search(server,"ordinaryackimageviolet").contains(key(file)),"later acknowledged image remains visible while source is unreadable");assertTrue(search(server,"unrelatedackviolet").contains(key(unrelated)),"unrelated acknowledged content remains visible");
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());queue(server).cleanupOldJobs(0);
    assertEquals(unit,queue(server).watcherReconciliationRow(key(file)).get("unit_revision"),"retention cannot erase a necessary later ROOT witness");
    capture(root,server,"ordinary-image-uncertain-"+named,List.of(file,alpha.resolve("pending.txt"),unrelated));
    Files.setPosixFilePermissions(file,permissions);Files.move(held,alpha);
    await(()->state(root).previous_generation()==null,"confirmed sources converge before B retirement",100);
    assertTrue(search(server,"ordinaryackimageviolet").contains(key(file)));assertEquals(original.active_generation(),state(root).active_generation());
    Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
    assertEquals(unit,queue(server).watcherReconciliationRow(key(file)).get("unit_revision"),"confirmed repeat discovery preserves the later acknowledged unit");
    queue(server).cleanupOldJobs(0);assertNull(queue(server).watcherReconciliationRow(key(file)),"normal retention resumes after the obligation ends");
   } finally {initialRelease.countDown();release.countDown();}
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,named,t->{})) {
    activate(server);assertTrue(search(server,"ordinaryackimageviolet").contains(key(file)));assertEquals(collection,Objects.toString(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(file),io.justsearch.indexing.SchemaFields.COLLECTION),"default"));
    assertEquals(original.active_generation(),state(root).active_generation());Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  } finally {
   initialRelease.countDown();release.countDown();
   try{if(permissions!=null)Files.setPosixFilePermissions(file,permissions);if(Files.exists(held))Files.move(held,alpha);}
   finally{try{capture(root,null,"ordinary-image-final-"+named,List.of(file,alpha.resolve("pending.txt"),unrelated));}finally{ConfigStore.restoreGlobal(installed,previous);}}
  }
 }
 @org.junit.jupiter.api.Test
 void sameStampedPublicSyncTargetsOnlyChangedMember(@TempDir Path root)throws Exception {
  Release030Fixture.unpack("switching",root);var original=state(root);Path alpha=root.resolve("roots/alpha"),held=Files.move(alpha,root.resolve("held-alpha")),beta=root.resolve("roots/beta");
  Path file=Files.writeString(beta.resolve("same-stamp.txt"),"firstcontentviolet"),sibling=Files.writeString(beta.resolve("stable-sibling.txt"),"stablesiblingviolet");
  var stamp=Files.getLastModifiedTime(file);ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=boot(root,ex,false,t->{if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}})) {
    activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));var service=server.appServices().ingestService();
    Release030RetirementTransitiveOrderTest.sync(service,beta);
    await(()->search(server,"firstcontentviolet").contains(key(file))&&"DONE".equals(queue(server).watcherReconciliationRow(key(file)).get("state")),"first ordinary ROOT image commits",30);
    long size=Files.size(file);Files.writeString(file,"othercontentviolet");Files.setLastModifiedTime(file,stamp);assertEquals(size,Files.size(file));
    Release030RetirementTransitiveOrderTest.sync(service,beta);release.countDown();
    await(()->search(server,"othercontentviolet").contains(key(file)),"changed same-stamp ROOT member is revalidated through actual targeted writer work",100);
    assertFalse(search(server,"firstcontentviolet").contains(key(file)));
    assertNull(queue(server).watcherReconciliationRow(key(file)).get("planned_source_sha256"));
    assertEquals("UNCHANGED",queue(server).watcherReconciliationRow(key(sibling)).get("last_reason_code"),"unchanged ordinary members are not forced extraction");
    assertFalse(queue(server).listSwitchBufferOpsStrict().stream().anyMatch(op->op.key().equals("path:"+key(file))||op.key().equals("path:"+key(sibling))));
    assertEquals(original.active_generation(),state(root).active_generation());Release030RetirementSafetyTest.assertRetained(root,original.building_generation());capture(root,server,"same-stamp-current",List.of(file,sibling));
   }finally{release.countDown();}
  }finally{release.countDown();try{if(Files.exists(held))Files.move(held,alpha);}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
 static KnowledgeServer boot(Path root,TestEngineExecutors ex,boolean named,MigrationTransitionBarrier.Hook hook)throws Exception {
  var server=org.mockito.Mockito.spy(new KnowledgeServer(ex,WorkerBootFixture.workerConfig(root.resolve("data")),null));org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
  server.bindBootRootBindings(List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"),named?"live-order-c":null),new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"),null)));
  server.bindExclusionRoots(()->List.of(root.resolve("roots/alpha"),root.resolve("roots/beta")));server.installMigrationBarrierForTests(hook);try{server.start();}catch(Exception|Error failed){server.close();throw failed;}return server;
 }
 static void capture(Path root,KnowledgeServer server,String label,List<Path> paths)throws Exception{
  var data=new LinkedHashMap<String,Object>();data.put("now",System.currentTimeMillis());data.put("state",state(root).toString());var side=root.resolve("data/upgrade-030-recovery.properties");data.put("sidecar",Files.exists(side)?Files.readString(side):null);data.put("databaseFileKey",String.valueOf(Files.readAttributes(root.resolve("data/jobs.db"),java.nio.file.attribute.BasicFileAttributes.class).fileKey()));var cp=Release030RecoveryState.load(root.resolve("data/jobs.db"));var tokens=new LinkedHashMap<String,Boolean>();try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro");var q=c.createStatement();var rs=q.executeQuery("SELECT generation,key,op,payload,last_updated,revision FROM switch_buffer ORDER BY accepted_order")){while(rs.next())if("SYNC_ROOT".equals(rs.getString("op"))){var op=new SwitchBufferCapableQueue.SwitchBufferOp(rs.getString("generation"),rs.getString("key"),rs.getString("op"),rs.getString("payload"),rs.getLong("last_updated"),rs.getString("revision"));tokens.put(op.key()+"@"+op.revision(),cp!=null&&cp.rootWalked(op));}}data.put("rootWalkTokens",tokens);
  try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro")){for(var query:Map.of("jobs","SELECT path,state,collection,attempts,retry_after,last_updated,unit_revision,planned_source_sha256,ingestion_root,content_hash,last_outcome_class,last_reason_code FROM jobs ORDER BY path","journal","SELECT * FROM switch_buffer ORDER BY accepted_order").entrySet()){var list=new ArrayList<Map<String,Object>>();try(var q=c.createStatement();var rs=q.executeQuery(query.getValue())){var md=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnName(i),rs.getObject(i));list.add(row);}}data.put(query.getKey(),list);}}
  var index=new LinkedHashMap<String,Object>();try(var dir=org.apache.lucene.store.FSDirectory.open(Release030CompatibilityTest.generation(root));var reader=org.apache.lucene.index.DirectoryReader.open(dir)){var searcher=new org.apache.lucene.search.IndexSearcher(reader);for(Path p:paths){var info=new LinkedHashMap<String,Object>();info.put("physicalExists",Files.isRegularFile(p));info.put("physicalReadable",Files.isReadable(p));if(Files.isRegularFile(p)){try{info.put("physicalSha256",Release030Fixture.sha256(Files.readAllBytes(p)));}catch(java.io.IOException unreadable){info.put("physicalReadError",unreadable.toString());}}var hits=searcher.search(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("doc_id",key(p))),10);info.put("indexCount",hits.totalHits.value());if(hits.scoreDocs.length>0){int doc=hits.scoreDocs[0].doc;var leaf=reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc,reader.leaves()));int at=doc-leaf.docBase;for(String field:List.of("source_sha256","collection")){String v=leaf.reader().storedFields().document(at).get(field);var fi=leaf.reader().getFieldInfos().fieldInfo(field);if(v==null&&fi!=null&&fi.getDocValuesType()==org.apache.lucene.index.DocValuesType.SORTED){var dv=org.apache.lucene.index.DocValues.getSorted(leaf.reader(),field);if(dv.advanceExact(at))v=dv.lookupOrd(dv.ordValue()).utf8ToString();}info.put(field,v);}}index.put(key(p),info);}}data.put("index",index);
  if(server!=null){data.put("counts",queue(server).jobStateCountsStrict().toString());data.put("dbIdentity",queue(server).release030RecoveryIdentity());data.put("betaSearch",search(server,paths.get(0).getFileName().toString().startsWith("transitive")?"transitivebetaviolet":"snapshotunqueuedviolet"));data.put("oldASearch",search(server,"nebula"));}System.out.println("UNCERTAIN_IMAGE_DIAGNOSTIC "+label+" "+new tools.jackson.databind.ObjectMapper().writeValueAsString(data));if(System.getenv("VERIFY_EVIDENCE")!=null){Path saved=Path.of(System.getenv("VERIFY_EVIDENCE")).resolve(label+".json");for(int n=2;Files.exists(saved);n++)saved=Path.of(System.getenv("VERIFY_EVIDENCE")).resolve(label+"-"+n+".json");Files.writeString(saved,new tools.jackson.databind.ObjectMapper().writeValueAsString(data));}
 }
}
