/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
class Release030RetirementPublicSyncTest {
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,false", "true,true", "false,true"})
 void laterPublicSyncMaySupersedeAnEarlierExactAdmission(boolean watched, boolean changed, @TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha"),nested=Files.createDirectories(alpha.resolve("overlap"));
  Path file=Files.writeString(nested.resolve("ordered.txt"),"overlappedwalkviolet");
  Files.writeString(alpha.resolve("sibling.txt"),"ordersiblingviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
  try(var models=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=Release030RetirementWalkOrderTest.namedBoot(root,ex,t->{
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
     var priorUnit=io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(priorExact.payload()).unitRevision();
     if(changed)Files.writeString(file,"overlappedchangedviolet");
     assertTrue(service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none()).getError().isBlank());
     var after=queue(server).listSwitchBufferOpsStrict();
     var exact=after.stream().filter(o->o.key().equals("path:"+key(file))).findFirst().orElseThrow();
     var accepted=io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(exact.payload());
     assertNotEquals(priorUnit,accepted.unitRevision());assertNotEquals(priorExact.revision(),exact.revision());
     assertEquals("walk-order",accepted.collection());assertEquals(key(alpha),key(accepted.ingestionRoot()));
     assertEquals(io.justsearch.indexerworker.loop.SourceContentHash.sha256(file),accepted.sourceSha256());
     assertFalse(after.stream().anyMatch(o->o.key().equals("path:"+key(alpha.resolve("sibling.txt")))),"ordinary members acquire no new exact receipt");
     Release030RetirementTransitiveOrderTest.assertCollection(root,file,"walk-order");
     System.out.println("PUBLIC_PREFIX_EXACT_THEN_SYNC watched="+watched+" changed="+changed+" "+after);
     Release030RetirementTransitiveOrderTest.capture(root,server,"public-sync-admitted",java.util.List.of(file));
    } finally {release.countDown();}
    await(()->state(root).previous_generation()==null,"later public sync supersedes earlier exact admission without losing current content",100);
    assertTrue(Files.isRegularFile(file));assertTrue(search(server,"nebula").contains(key(alpha.resolve("file-01.txt"))));
    assertTrue(search(server,changed?"overlappedchangedviolet":"overlappedwalkviolet").contains(key(file)));assertEquals(original.active_generation(),state(root).active_generation());
   } finally {release.countDown();}
   try(var ex=new TestEngineExecutors();var server=Release030RetirementWalkOrderTest.namedBoot(root,ex,t->{})) {
    activate(server);assertTrue(search(server,changed?"overlappedchangedviolet":"overlappedwalkviolet").contains(key(file)));Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
   }
  } finally {release.countDown();try {Release030RetirementTransitiveOrderTest.capture(root,null,"public-sync-final",java.util.List.of(file));}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
}
