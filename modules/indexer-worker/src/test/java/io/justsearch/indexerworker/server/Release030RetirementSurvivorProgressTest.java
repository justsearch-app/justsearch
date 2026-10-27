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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementSurvivorProgressTest {
 @Test void unavailableExactUnderDeleteDoesNotStarveUnrelatedBeta(@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("switching",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha"),beta=root.resolve("roots/beta"),held=root.resolve("held-alpha");
  Path file=Files.writeString(alpha.resolve("later-exact.txt"),"pendingexactviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();var attempts=new AtomicInteger();
  Path betaOnly=beta.resolve("late-unqueued.txt"),ack=beta.resolve("independent-ack.txt");
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=Release030RetirementWalkOrderTest.namedBoot(root,ex,t->{
    if(t.point().equals("release030-before-replay")){attempts.incrementAndGet();if(once.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}}
   })) {
    activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
    try {
     var s=server.appServices().ingestService();
     assertTrue(s.deleteByPath(DeleteByPathRequest.newBuilder().setPath(alpha.toString()).build(),CallContext.none()).getError().isBlank());
     assertEquals(1,s.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection("walk-order").setRootAdded(true).setForceReindex(true).build(),CallContext.none()).getAcceptedCount());
     Files.move(alpha,held);Files.writeString(betaOnly,"progressbetaviolet");Files.writeString(ack,"independentackviolet");
     assertEquals(0,count(root,"path='"+key(betaOnly)+"'"));
     assertEquals(1,s.submitBatch(BatchRequest.newBuilder().addFilePaths(ack.toString()).setRootAdded(true).build(),CallContext.none()).getAcceptedCount());
     System.out.println("PENDING_EXACT_ACCEPTED_ORDER "+queue(server).listSwitchBufferOpsStrict());
    } finally {release.countDown();}
    try {
     await(()->attempts.get()>=2&&queue(server).jobStateCountsStrict().pendingReadyCount()==0&&queue(server).jobStateCountsStrict().processingCount()==0&&search(server,"progressbetaviolet").contains(key(betaOnly)),"two replay attempts, normal queued writer settlement and independent Beta search visibility",90);
     assertTrue(search(server,"independentackviolet").contains(key(ack)));assertTrue(search(server,"nebula").contains(key(beta.resolve("file-00.txt"))));
     assertEquals(original.building_generation(),state(root).previous_generation());
     assertTrue(search(server,"progressbetaviolet").contains(key(betaOnly)),"unrelated root-only Beta work must progress despite unavailable exact survivor under Alpha broad delete");
    } finally {
     Release030RetirementTransitiveOrderTest.capture(root,server,"pending-exact-progress",List.of(betaOnly,file,ack));
     System.out.println("PENDING_EXACT_RESULT attempts="+attempts.get()+" betaJobRows="+count(root,"path='"+key(betaOnly)+"'")+" betaSearch="+search(server,"progressbetaviolet")+" ackSearch="+search(server,"independentackviolet"));
    }
   }
  } finally {release.countDown();try {if(Files.exists(held))Files.move(held,alpha);}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
}
