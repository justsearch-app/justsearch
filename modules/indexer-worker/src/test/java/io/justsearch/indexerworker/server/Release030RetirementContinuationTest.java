/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.*;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Public admissions after a real failed physical cleanup, including saved writer backoff. */
@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementContinuationTest {
  @Test void laterAdmissionsAfterEmptySqlAckKeepTheirOwnersAcrossRestart(@TempDir Path root) throws Exception {
    Release030Fixture.unpack("switching",root);
    var original=state(root);Path beta=root.resolve("roots/beta"),file=Files.writeString(root.resolve("collection-owner.txt"),"continuationownerviolet");
    Path indices=root.resolve("data/index/default/indices"),building=indices.resolve(original.building_generation());
    var indexPermissions=Files.getPosixFilePermissions(indices);var buildPermissions=Files.getPosixFilePermissions(building);
    ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
    var first=new AtomicBoolean();var firstEntered=new CountDownLatch(1);var firstRelease=new CountDownLatch(1);
    var ackEntered=new CountDownLatch(1);var ackRelease=new CountDownLatch(1);var ackOnce=new AtomicBoolean();
    var pauseLater=new AtomicBoolean();var laterEntered=new CountDownLatch(1);var laterRelease=new CountDownLatch(1);
    long[] highest={0},deadline={0};String[] winningUnit={null};
    var projection=new AcceptedProjection("continuation-source","doc",1,AcceptedProjection.Kind.UPSERT,
        "{\"title\":\"continuationprojectionviolet\",\"content\":\"continuationprojectionviolet\"}");
    Path target=beta.resolve("continuation-new.txt");
    try(var _=new ReleasedModelIdentities(root)) {
      try(var ex=new TestEngineExecutors();var server=Release030RetirementTransitiveOrderTest.bootRoots(root,ex,false,t->{
        if(t.point().equals("release030-before-replay")) {
          if(first.compareAndSet(false,true)){firstEntered.countDown();assertTrue(firstRelease.await(30,TimeUnit.SECONDS));}
          else if(pauseLater.compareAndSet(true,false)){laterEntered.countDown();assertTrue(laterRelease.await(30,TimeUnit.SECONDS));}
        }
        if(t.point().equals("release030-journal-acknowledged")&&ackOnce.compareAndSet(false,true)) {
          ackEntered.countDown();assertTrue(ackRelease.await(30,TimeUnit.SECONDS));
        }
      })) {
        activate(server);assertTrue(firstEntered.await(30,TimeUnit.SECONDS));
        var service=server.appServices().ingestService();
        assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection("C1").setForceReindex(true).setRootAdded(true).build(),CallContext.none()).getAcceptedCount());
        service.applyProjection(projection,ProjectionDurability.NRT,CallContext.none());
        Release030RetirementTransitiveOrderTest.watch(service,beta,"default");
        Release030RetirementTransitiveOrderTest.sync(service,beta);
        highest[0]=queue(server).listSwitchBufferOpsStrict().stream().mapToLong(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder).max().orElseThrow();
        firstRelease.countDown();assertTrue(ackEntered.await(100,TimeUnit.SECONDS));
        assertTrue(queue(server).listSwitchBufferOpsStrict().isEmpty(),"real conditional SQL ACK emptied the journal");
        assertTrue(search(server,"continuationownerviolet").contains(key(file)));
        assertTrue(search(server,"continuationprojectionviolet").contains(projection.indexId()));
        Files.setPosixFilePermissions(indices,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(building,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
        pauseLater.set(true);ackRelease.countDown();assertTrue(laterEntered.await(30,TimeUnit.SECONDS));
        assertEquals(original.building_generation(),state(root).previous_generation());
        assertTrue(Files.isDirectory(building),"failed physical cleanup keeps B");
        var producer=(DefaultWorkerAppServices)server.appServices();assertTrue(producer.pauseProducerForCutover(10000));
        assertEquals(1,service.submitBatch(BatchRequest.newBuilder().addFilePaths(file.toString()).setTargetCollection("C2").setForceReindex(true).setRootAdded(true).build(),CallContext.none()).getAcceptedCount());
        var newFile=queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.key().equals("path:"+key(file))).findFirst().orElseThrow();
        assertTrue(newFile.acceptedOrder()>highest[0],"empty SQL must not reuse an order older than retained verified admissions");
        winningUnit[0]=SwitchBufferUpsert.decode(newFile.payload()).unitRevision();
        queue(server).markFailed(file,IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,IngestionReasonCodes.SOURCE_UNREADABLE,
            IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE,"one real saved pending winning writer"));
        var waiting=queue(server).watcherReconciliationRow(key(file));deadline[0]=Long.parseLong(waiting.get("retry_after"));
        assertEquals("1",waiting.get("attempts"));assertTrue(deadline[0]>System.currentTimeMillis());
        service.applyProjection(new AcceptedProjection(projection.sourceId(),projection.documentId(),2,AcceptedProjection.Kind.DELETE,null),ProjectionDurability.NRT,CallContext.none());
        Files.writeString(target,"continuationbetaviolet");
        Release030RetirementTransitiveOrderTest.watch(service,beta,"C2");Release030RetirementTransitiveOrderTest.sync(service,beta);
        assertTrue(queue(server).listSwitchBufferOpsStrict().stream().allMatch(op->op.acceptedOrder()>highest[0]));
        producer.resumeProducerAfterCutover();laterRelease.countDown();
        await(()->queue(server).jobStateCountsStrict().processingCount()==0&&queue(server).jobStateCountsStrict().pendingReadyCount()==0,
            "unrelated work settles while winning exact unit waits for its genuine deadline",30);
        assertEquals(winningUnit[0],queue(server).watcherReconciliationRow(key(file)).get("unit_revision"));
        assertEquals("C2",queue(server).watcherReconciliationRow(key(file)).get("collection"));
        assertFalse(search(server,"continuationprojectionviolet").contains(projection.indexId()));
        Release030RetirementTransitiveOrderTest.capture(root,server,"continuation-before-restart",List.of(file,target));
      } finally {firstRelease.countDown();ackRelease.countDown();laterRelease.countDown();}
      try(var ex=new TestEngineExecutors();var server=Release030RetirementTransitiveOrderTest.bootRoots(root,ex,false,t->{})) {
        activate(server);var row=queue(server).watcherReconciliationRow(key(file));
        assertEquals(winningUnit[0],row.get("unit_revision"));assertEquals("1",row.get("attempts"));
        assertEquals(Long.toString(deadline[0]),row.get("retry_after"),"reopen preserves the actual existing waiting unit and retry");
        await(()->search(server,"continuationownerviolet").contains(key(file))
            &&"C2".equals(server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(file),io.justsearch.indexing.SchemaFields.COLLECTION)),
            "winning C2 writer becomes current after configured retry eligibility",100);
        assertFalse(search(server,"continuationprojectionviolet").contains(projection.indexId()));
        Files.setPosixFilePermissions(building,buildPermissions);Files.setPosixFilePermissions(indices,indexPermissions);
        await(()->state(root).previous_generation()==null,"new receipt set converges before physical B retirement",100);
        assertTrue(search(server,"continuationbetaviolet").contains(key(target)));
        assertEquals(original.active_generation(),state(root).active_generation());Release030RetirementSafetyTest.assertRetired(root,original.building_generation());
      }
      try(var ex=new TestEngineExecutors();var server=Release030RetirementTransitiveOrderTest.bootRoots(root,ex,false,t->{})) {
        activate(server);assertTrue(search(server,"continuationownerviolet").contains(key(file)));assertTrue(search(server,"continuationbetaviolet").contains(key(target)));
        assertEquals("C2",server.lifecycleManagerForTests().documentFieldOps().getDocumentFieldOrThrow(key(file),io.justsearch.indexing.SchemaFields.COLLECTION));
        assertFalse(search(server,"continuationprojectionviolet").contains(projection.indexId()));
      }
    } finally {
      firstRelease.countDown();ackRelease.countDown();laterRelease.countDown();
      try {if(Files.exists(building))Files.setPosixFilePermissions(building,buildPermissions);Files.setPosixFilePermissions(indices,indexPermissions);}
      finally {try {Release030RetirementTransitiveOrderTest.capture(root,null,"continuation-final",List.of(file,target));}finally{ConfigStore.restoreGlobal(installed,previous);}}
    }
  }
}
