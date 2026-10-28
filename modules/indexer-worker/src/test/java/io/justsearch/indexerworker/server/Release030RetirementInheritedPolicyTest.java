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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual null-policy admission, late watcher registration and retained data after reboot. */
@Timeout(180)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementInheritedPolicyTest {
  @Test void nullRootPreservesExistingCollectionAndAttributionAcrossLateRegistration(@TempDir Path root) throws Exception {
    Release030Fixture.unpack("switching",root);var original=state(root);
    Path alpha=root.resolve("roots/alpha"),old=alpha.resolve("file-01.txt"),fresh=alpha.resolve("inherited-new.txt");
    var previous=ConfigStore.globalOrNull();var installed=configure(root);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
    try(var _=new ReleasedModelIdentities(root)) {
      try(var ex=new TestEngineExecutors();var server=Release030RetirementRootImageTest.boot(root,ex,false,t->{
        if(t.point().equals("release030-before-replay")&&once.compareAndSet(false,true)) {
          entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));
        }
      })) {
        activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));var service=server.appServices().ingestService();
        assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(old.toString()).setTargetCollection("C")
            .setRootAdded(true).setForceReindex(true).build(),Release030RetirementSyncPolicyTest.defaultContext()).getAcceptedCount());
        await(()->"DONE".equals(queue(server).watcherReconciliationRow(key(old)).get("state"))
            &&"C".equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(old),"collection")),"existing public C admission completes",30);
        var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
        try {
          var oldRow=queue(server).watcherReconciliationRow(key(old));Files.writeString(fresh,"inheritednewviolet");
          var response=service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(false).build(),CallContext.none());
          assertTrue(response.getError().isBlank(),response.getError());
          assertEquals(oldRow,queue(server).watcherReconciliationRow(key(old)),"force=false leaves the already indexed C file intact");
          var admitted=queue(server).watcherReconciliationRow(key(fresh));assertNotNull(admitted);
          assertTrue(admitted.get("collection")==null || admitted.get("collection").equals("default"));
          assertTrue(service.syncDirectory(SyncDirectoryRequest.newBuilder().setRootPath(alpha.toString()).setForce(true).build(),CallContext.none()).getError().isBlank());
          var forced=queue(server).watcherReconciliationRow(key(old));
          assertNotEquals(oldRow.get("unit_revision"),forced.get("unit_revision"));assertEquals("C",forced.get("collection"));
          assertEquals("user",forced.get("originator"));assertEquals("CLI",forced.get("transport"));
          var frozen=queue(server).watcherReconciliationRow(key(fresh));
          assertTrue(service.watchRoot(WatchRootRequest.newBuilder().setRootPath(alpha.toString()).setCollection("late-C").build(),CallContext.none()).getWatching());
          assertEquals(frozen,queue(server).watcherReconciliationRow(key(fresh)),"registration alone does not reinterpret admitted metadata");
        } finally {producer.resumeProducerAfterCutover();release.countDown();}
        await(()->state(root).previous_generation()==null,"inherited admission retires with its actual metadata",100);
        assertTrue(search(server,"inheritednewviolet").contains(key(fresh)));
        assertEquals("C",server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(old),"collection"));
        assertEquals("default",java.util.Objects.toString(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(fresh),"collection"),"default"));
      } finally {release.countDown();}
      try(var ex=new TestEngineExecutors();var server=boot(root,ex)) {
        activate(server);assertTrue(search(server,"inheritednewviolet").contains(key(fresh)));
        assertEquals("C",queue(server).watcherReconciliationRow(key(old)).get("collection"));
        Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
      }
    } finally {release.countDown();ConfigStore.restoreGlobal(installed,previous);}
  }
}
