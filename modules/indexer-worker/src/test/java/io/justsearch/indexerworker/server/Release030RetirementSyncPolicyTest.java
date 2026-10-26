/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
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
final class Release030RetirementSyncPolicyTest {
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void liveSyncKeepsItsOrdinaryRetryPolicy(boolean maintenance,@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary),alpha=root.resolve("roots/alpha");
  List<Path> files=new ArrayList<>();for(String name:List.of("ordinary-io","ordinary-parser","exact-io","exact-parser"))files.add(Files.writeString(alpha.resolve(name+".txt"),name+" policyviolet"));
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  try(var models=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=Release030RetirementWalkOrderTest.namedBoot(root,ex,t->{
    if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}
   })) {
    activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try {
     var service=server.appServices().ingestService();
     assertTrue(service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none()).getError().isBlank());
     for(Path file:files.subList(2,4))assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection("walk-order").setRootAdded(true).setForceReindex(true).build(),CallContext.none()).getAcceptedCount());
     for(int i=0;i<files.size();i++)queue(server).markFailed(files.get(i),IngestionOutcome.of(i%2==0?IngestionOutcomeClass.IO_FAILED:IngestionOutcomeClass.PARSER_TIMEOUT,i%2==0?IngestionReasonCodes.SOURCE_UNREADABLE:IngestionReasonCodes.PARSER_TIMEOUT,IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"one actual transient failure"));
     var before=rows(root,files);var receiptsBefore=queue(server).listSwitchBufferOpsStrict();
     for(var row:before.values()){assertEquals("PENDING",row.get("state"));assertEquals(1,((Number)row.get("attempts")).intValue());assertTrue(((Number)row.get("retry_after")).longValue()>System.currentTimeMillis());}
     CallContext ctx=maintenance?CallContext.none():defaultContext();
     var response=service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),ctx);
     var after=rows(root,files);System.out.println("LIVE_SYNC_POLICY maintenance="+maintenance+" response="+response+" BEFORE="+before+" AFTER="+after+" RECEIPTS_BEFORE="+receiptsBefore+" RECEIPTS_AFTER="+queue(server).listSwitchBufferOpsStrict());
     assertTrue(response.getError().isBlank(),response.getError());assertFalse(response.getSkipped());assertFalse(response.getDeleteDetectionUnverified());
     if(maintenance){assertEquals(before,after,"maintenance preserves waiting ordinary AND exact jobs, unit and real retry deadline");
      assertEquals(receiptsBefore.stream().filter(o->files.subList(2,4).stream().anyMatch(f->o.key().equals("path:"+key(f)))).toList(),queue(server).listSwitchBufferOpsStrict().stream().filter(o->files.subList(2,4).stream().anyMatch(f->o.key().equals("path:"+key(f)))).toList(),"a policy skip cannot refresh exact receipts");}

     else for(Path file:files){var row=after.get(key(file));assertEquals("PENDING",row.get("state"));assertEquals(0,((Number)row.get("attempts")).intValue());assertNull(row.get("retry_after"));assertNotEquals(before.get(key(file)).get("unit_revision"),row.get("unit_revision"));}
    } finally {release.countDown();}
   } finally {release.countDown();}
  } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
 }
 @Test void legacyHashEntryStillReadsOnAnInterruptedThread(@TempDir Path root)throws Exception {
  Path file=Files.writeString(root.resolve("hash.txt"),"legacy interrupt preservation");String expected=io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
  try {Thread.currentThread().interrupt();assertEquals(expected,io.justsearch.indexerworker.loop.SourceContentHash.sha256(file));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }
 static CallContext defaultContext(){return new CallContext(null,null,null,new io.justsearch.core.context.EngineContext(io.justsearch.core.context.EngineContext.ClientKind.CLI,"policy-probe",Optional.empty(),Optional.empty(),"TRUSTED","CLI",io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,io.justsearch.core.context.EngineContext.Urgency.FOREGROUND),new io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance("user","CLI"),io.justsearch.core.execution.EngineTaskLifetime.NONE);}
 static Map<String,Map<String,Object>> rows(Path root,List<Path> paths)throws Exception {
  var result=new LinkedHashMap<String,Map<String,Object>>();try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+root.resolve("data/jobs.db")+"?mode=ro");var q=c.prepareStatement("SELECT state,attempts,retry_after,unit_revision,planned_source_sha256,collection,ingestion_root FROM jobs WHERE path=?")){
   for(Path path:paths){q.setString(1,key(path));try(var r=q.executeQuery()){assertTrue(r.next());var row=new LinkedHashMap<String,Object>();for(String col:List.of("state","attempts","retry_after","unit_revision","planned_source_sha256","collection","ingestion_root"))row.put(col,r.getObject(col));result.put(key(path),row);}}
  }return result;
 }
}
