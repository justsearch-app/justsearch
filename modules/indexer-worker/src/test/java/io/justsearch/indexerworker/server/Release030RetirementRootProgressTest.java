/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;
import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementRootProgressTest {
 @Test void uncertainEarlierAlphaDoesNotStarveUnqueuedBetaOrAcknowledgedMutation(@TempDir Path temporary)throws Exception {
  Path root=Release030Fixture.unpack("migrating",temporary);var original=state(root);
  Path alpha=root.resolve("roots/alpha");Path beta=root.resolve("roots/beta");
  Path changed=Files.writeString(alpha.resolve("file-01.txt"),"heldalphavioleto");
  try(var q=new SqliteJobQueue(root.resolve("data/jobs.db"))) {q.open();assertTrue(q.enqueueAndBufferFileForGeneration(original.building_generation(),JobQueue.EnqueueEntry.stat(changed).withinRoot(alpha),null));}
  Path held=Files.move(alpha,root.resolve("held-alpha"));
  Path betaOnly=Files.writeString(beta.resolve("unqueued-root-only.txt"),"unqueuedbetaviolet");
  Path acknowledged=Files.writeString(beta.resolve("acknowledged.txt"),"acknowledgedbetaviolet");
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);var attempts=new AtomicInteger();
  try(var _=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=Release030RetirementOrderingTest.bootWithBarrier(root,ex,t->{if(t.point().equals("release030-before-replay"))attempts.incrementAndGet();})) {
    var q=Release030RetirementWalkOrderTest.queue(server);
    assertEquals(0,count(root,"path='"+key(betaOnly)+"'"),"new beta source starts genuinely unqueued");
    activate(server);
    assertEquals(1,server.appServices().ingestService().submitBatch(BatchRequest.newBuilder().addFilePaths(acknowledged.toString()).setRootAdded(true).build(),CallContext.none()).getAcceptedCount());
    try {
     await(()->attempts.get()>=2&&q.jobStateCountsStrict().pendingReadyCount()==0&&q.jobStateCountsStrict().processingCount()==0,"ordinary queued work drains and two recovery attempts run",90);
     assertTrue(search(server,"acknowledgedbetaviolet").contains(key(acknowledged)),"unrelated acknowledged mutation proceeds");
     assertEquals(original.building_generation(),state(root).previous_generation());
     assertTrue(search(server,"nebula").contains(key(changed)),"uncertain alpha retains old searchable content");
     assertTrue(search(server,"unqueuedbetaviolet").contains(key(betaOnly)),"non-overlapping beta recovery progresses despite earlier uncertain alpha receipt");
    } finally {
     var snapshot=new LinkedHashMap<String,Object>();snapshot.put("state",state(root).toString());snapshot.put("attempts",attempts.get());snapshot.put("counts",q.jobStateCountsStrict().toString());
     snapshot.put("betaJobRows",count(root,"path='"+key(betaOnly)+"'"));snapshot.put("betaSearch",search(server,"unqueuedbetaviolet"));snapshot.put("ackSearch",search(server,"acknowledgedbetaviolet"));snapshot.put("alphaSearch",search(server,"nebula"));
     snapshot.put("journal",q.listSwitchBufferOpsStrict().stream().map(Object::toString).toList());snapshot.put("sidecar",Files.readString(root.resolve("data/upgrade-030-recovery.properties")));
     System.out.println("ROOT_PROGRESS "+new tools.jackson.databind.ObjectMapper().writeValueAsString(snapshot));
    }
   }
  }finally{Files.move(held,alpha);ConfigStore.restoreGlobal(installed,previous);}
 }
}
