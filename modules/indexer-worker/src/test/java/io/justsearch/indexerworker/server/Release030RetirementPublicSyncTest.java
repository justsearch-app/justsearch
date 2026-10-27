/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
class Release030RetirementPublicSyncTest {
 @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
 @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,false", "true,true", "false,true"})
 void laterPublicSyncMaySupersedeAnEarlierExactAdmission(boolean watched, boolean changed, @TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha"),nested=Files.createDirectories(alpha.resolve("overlap"));
  Path file=Files.writeString(nested.resolve("ordered.txt"),"overlappedwalkviolet");
  Files.writeString(alpha.resolve("sibling.txt"),"ordersiblingviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=namedBoot(root,ex,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {arrived.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}
   })) {
    activate(server);assertTrue(arrived.await(30,TimeUnit.SECONDS));
    var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    var service=server.appServices().ingestService();
    try {
     if(watched) assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(alpha.toString()).setCollection("walk-order").setRootAdded(true).build(),CallContext.none()).getWatching());
     assertTrue(service.deleteByPath(DeleteByPathRequest.newBuilder().setPath(nested.toString()).build(),CallContext.none()).getError().isBlank());
     assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection("walk-order").setRootAdded(true).setForceReindex(true).build(),CallContext.none()).getAcceptedCount());
     var priorExact=queue(server).listSwitchBufferOpsStrict().stream().filter(o->o.key().equals("path:"+key(file))).findFirst().orElseThrow();
     var priorUnit=SwitchBufferUpsert.decode(priorExact.payload()).unitRevision();
     if(changed)Files.writeString(file,"overlappedchangedviolet");
     assertTrue(service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none()).getError().isBlank());
     var after=queue(server).listSwitchBufferOpsStrict();
     var exact=after.stream().filter(o->o.key().equals("path:"+key(file))).findFirst().orElseThrow();
     var accepted=SwitchBufferUpsert.decode(exact.payload());
     assertNotEquals(priorUnit,accepted.unitRevision());assertNotEquals(priorExact.revision(),exact.revision());
     assertEquals("walk-order",accepted.collection());assertEquals(key(alpha),key(accepted.ingestionRoot()));
     assertEquals(io.justsearch.indexerworker.loop.SourceContentHash.sha256(file),accepted.sourceSha256());
     assertFalse(after.stream().anyMatch(o->o.key().equals("path:"+key(alpha.resolve("sibling.txt")))),"ordinary members acquire no new exact receipt");
     assertCollection(root,file,"walk-order");
     System.out.println("PUBLIC_PREFIX_EXACT_THEN_SYNC watched="+watched+" changed="+changed+" "+after);
     capture(root,server,"public-sync-admitted",List.of(file));
    } finally {release.countDown();}
    await(()->state(root).previous_generation()==null,"later public sync supersedes earlier exact admission without losing current content",100);
    assertTrue(Files.isRegularFile(file));assertTrue(search(server,"nebula").contains(key(alpha.resolve("file-01.txt"))));
    assertTrue(search(server,changed?"overlappedchangedviolet":"overlappedwalkviolet").contains(key(file)));assertEquals(original.active_generation(),state(root).active_generation());
   } finally {release.countDown();}
   try(var ex=new TestEngineExecutors();var server=namedBoot(root,ex,t->{})) {
    activate(server);assertTrue(search(server,changed?"overlappedchangedviolet":"overlappedwalkviolet").contains(key(file)));Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  } finally {release.countDown();try {capture(root,null,"public-sync-final",List.of(file));}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
 static KnowledgeServer namedBoot(Path root,TestEngineExecutors ex,MigrationTransitionBarrier.Hook hook)throws Exception {
  var server=org.mockito.Mockito.spy(new KnowledgeServer(ex,WorkerBootFixture.workerConfig(root.resolve("data")),null));
  shortenRecoveryWaits(server);org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
  server.bindBootRootBindings(List.of(new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"),"walk-order"),
      new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"),null)));
  server.bindExclusionRoots(()->List.of(root.resolve("roots/alpha"),root.resolve("roots/beta")));
  server.installMigrationBarrierForTests(hook);
  try {server.start();} catch(Exception|Error failure) {server.close();throw failure;}return server;
 }
 static void assertCollection(Path root,Path file,String collection)throws Exception{try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("data/jobs.db"));var q=c.prepareStatement("SELECT collection FROM jobs WHERE path=?")){q.setString(1,key(file));try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(collection,r.getString(1));}}}
 static void capture(Path root,KnowledgeServer server,String label,List<Path> paths)throws Exception{
  var data=new LinkedHashMap<String,Object>();data.put("now",System.currentTimeMillis());data.put("state",state(root).toString());var side=root.resolve("data/upgrade-030-recovery.properties");data.put("sidecar",Files.exists(side)?Files.readString(side):null);data.put("databaseFileKey",String.valueOf(Files.readAttributes(root.resolve("data/jobs.db"),java.nio.file.attribute.BasicFileAttributes.class).fileKey()));var cp=Release030RecoveryState.load(root.resolve("data/jobs.db"));var tokens=new LinkedHashMap<String,Boolean>();try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro");var q=c.createStatement();var rs=q.executeQuery("SELECT generation,key,op,payload,last_updated,revision FROM switch_buffer ORDER BY accepted_order")){while(rs.next())if("SYNC_ROOT".equals(rs.getString("op"))){var op=new SwitchBufferCapableQueue.SwitchBufferOp(rs.getString("generation"),rs.getString("key"),rs.getString("op"),rs.getString("payload"),rs.getLong("last_updated"),rs.getString("revision"));tokens.put(op.key()+"@"+op.revision(),cp!=null&&cp.rootWalked(op));}}data.put("rootWalkTokens",tokens);
  try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro")){for(var query:Map.of("jobs","SELECT path,state,collection,attempts,retry_after,last_updated,unit_revision,planned_source_sha256,ingestion_root,content_hash,last_outcome_class,last_reason_code FROM jobs ORDER BY path","journal","SELECT * FROM switch_buffer ORDER BY accepted_order").entrySet()){var list=new ArrayList<Map<String,Object>>();try(var q=c.createStatement();var rs=q.executeQuery(query.getValue())){var md=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnName(i),rs.getObject(i));list.add(row);}}data.put(query.getKey(),list);}}
  var index=new LinkedHashMap<String,Object>();try(var dir=org.apache.lucene.store.FSDirectory.open(Release030CompatibilityTest.generation(root));var reader=org.apache.lucene.index.DirectoryReader.open(dir)){var searcher=new org.apache.lucene.search.IndexSearcher(reader);for(Path p:paths){var info=new LinkedHashMap<String,Object>();info.put("physicalExists",Files.isRegularFile(p));if(Files.isRegularFile(p))info.put("physicalSha256",Release030Fixture.sha256(Files.readAllBytes(p)));var hits=searcher.search(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("doc_id",key(p))),10);info.put("indexCount",hits.totalHits.value());if(hits.scoreDocs.length>0){int doc=hits.scoreDocs[0].doc;var leaf=reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc,reader.leaves()));int at=doc-leaf.docBase;for(String field:List.of("source_sha256","collection")){String v=leaf.reader().storedFields().document(at).get(field);var fi=leaf.reader().getFieldInfos().fieldInfo(field);if(v==null&&fi!=null&&fi.getDocValuesType()==org.apache.lucene.index.DocValuesType.SORTED){var dv=org.apache.lucene.index.DocValues.getSorted(leaf.reader(),field);if(dv.advanceExact(at))v=dv.lookupOrd(dv.ordValue()).utf8ToString();}info.put(field,v);}}index.put(key(p),info);}}data.put("index",index);
  if(server!=null){data.put("counts",queue(server).jobStateCountsStrict().toString());data.put("dbIdentity",queue(server).release030RecoveryIdentity());data.put("betaSearch",search(server,paths.get(0).getFileName().toString().startsWith("transitive")?"transitivebetaviolet":"snapshotunqueuedviolet"));data.put("oldASearch",search(server,"nebula"));}write(root,label,data);
 }
 static void write(Path root,String label,Object data)throws Exception {System.out.println("TRANSITIVE_DIAGNOSTIC "+label+" "+new tools.jackson.databind.ObjectMapper().writeValueAsString(data));}
}
