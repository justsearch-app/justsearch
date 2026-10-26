/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Release030RootIntentTest {
  @TempDir Path temporary;

  @org.junit.jupiter.api.AfterEach void snapshot(org.junit.jupiter.api.TestInfo info) throws Exception {
    String evidence=System.getenv("VERIFY_EVIDENCE");
    if(evidence==null)return;
    Path target=Path.of(evidence,"snapshots",info.getTestMethod().orElseThrow().getName());
    try(var paths=Files.walk(temporary)) {
      for(Path source:paths.filter(Files::isRegularFile).toList()) {
        Path copy=target.resolve(temporary.relativize(source));Files.createDirectories(copy.getParent());Files.copy(source,copy);
      }
    }
  }

  private SqliteJobQueue queue() throws Exception {
    Path db=temporary.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    var q=new SqliteJobQueue(db);q.open();q.inventoryRetirementRoots();
    q.retainRelease030Retirement("building",List.of(),Map.of());return q;
  }

  private SwitchBufferCapableQueue.SwitchBufferOp root(SqliteJobQueue q,Path root) {
    assertTrue(q.putSyncRootForGeneration("building","root",new SwitchBufferSyncRoot(root.toString(),true,null)));
    return q.retirementRootReceipt("building","root");
  }

  @Test void freshDefaultCannotBeReinterpreted() throws Exception {
    try(var q=queue()) {
      var receipt=root(q,temporary);
      assertEquals("default",q.retirementRootCollection(receipt));
      assertThrows(IllegalStateException.class,()->q.retainRetirementRootCollection(receipt,"C"));
      assertEquals("default",q.retirementRootCollection(receipt));
    }
  }

  @Test void historicalMissingNamespaceCannotBeBackfilled() throws Exception {
    try(var q=queue()) {
      assertTrue(q.putSwitchBufferForGeneration("building","historical","SYNC_ROOT",new SwitchBufferSyncRoot(temporary.toString(),true,null).encode()));
      // Historical inventory is deliberately distinct from a fresh creator admission.
      var receipt=q.listSwitchBufferOpsStrict().getFirst();
      q.release030RecoveryState().retainRootHead(receipt);
      assertNull(q.retirementRootCollection(receipt));
      assertThrows(IllegalStateException.class,()->q.retainRetirementRootCollection(receipt,"C"));
      assertNull(q.retirementRootCollection(receipt));
      Path held=Files.writeString(temporary.resolve("held.txt"),"held");
      assertThrows(IllegalStateException.class,()->q.enqueueRetirementRootEntries(receipt,
          List.of(JobQueue.EnqueueEntry.stat(held).withinRoot(temporary)),"C",false,false,()->false));
      Path healthy=Files.createDirectories(temporary.resolve("healthy"));
      Path file=Files.writeString(healthy.resolve("file.txt"),"healthy");
      var known=root(q,healthy);
      assertEquals(1,q.enqueueRetirementRootEntries(known,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(healthy)),null,false,false,()->false));
      q.prepareRetirementClaims();assertEquals(List.of(file),q.pollPending(1).stream().map(JobQueue.IndexJob::path).toList());
    }
  }

  @Test void ordinaryRootClearsEarlierAttributionAndPlan() throws Exception {
    Path file=Files.writeString(temporary.resolve("file.txt"),"content");
    try(var q=queue()) {
      q.enqueueEntries(List.of(new JobQueue.EnqueueEntry(file,7,new JobQueue.EnqueueProvenance("user","rpc"),"a".repeat(64),temporary)),"C");
      var receipt=root(q,temporary);
      assertEquals(1,q.enqueueRetirementRootEntries(receipt,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)),null,false,false,()->false));
      var row=q.watcherReconciliationRow(file.toString());
      assertNull(row.get("originator"));assertNull(row.get("transport"));
      assertNull(row.get("planned_source_sha256"));assertEquals("default",row.get("collection"));
      q.prepareRetirementClaims();assertEquals(List.of(file),q.pollPending(1).stream().map(JobQueue.IndexJob::path).toList());
    }
  }

  @Test void freshCreatorCollectionIsFrozenAndClaimable() throws Exception {
    Path file=Files.writeString(temporary.resolve("file.txt"),"content");
    try(var q=queue()) {
      assertTrue(q.putSyncRootForGeneration("building","root",new SwitchBufferSyncRoot(temporary.toString(),true,null),"C"));
      var receipt=q.retirementRootReceipt("building","root");
      assertEquals("C",q.retirementRootCollection(receipt));
      assertEquals(1,q.enqueueRetirementRootEntries(receipt,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)),"C",false,false,()->false));
      q.prepareRetirementClaims();assertEquals(List.of(file),q.pollPending(1).stream().map(JobQueue.IndexJob::path).toList());
      assertThrows(IllegalStateException.class,()->q.retainRetirementRootCollection(receipt,null));
    }
  }

  @Test void freshAutomaticCreatorCollectionIsFrozenWithoutBackfillingExistingRoot() throws Exception {
    Path db=temporary.resolve("automatic.db"),root=Files.createDirectories(temporary.resolve("root"));
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.journalRelease030Roots("building",List.of(root),ignored->"C");q.inventoryRetirementRoots();
      var receipt=q.retirementReplayOps().getFirst();assertEquals("C",q.retirementRootCollection(receipt));
      q.journalRelease030Roots("building",List.of(root),ignored->"later");
      assertEquals("C",q.retirementRootCollection(receipt));
    }
  }

  @Test void orphanNamespaceSlotDoesNotAuthorizeAnUnretainedReceipt() throws Exception {
    try(var q=queue()) {
      assertTrue(q.putSwitchBufferForGeneration("building","orphan","SYNC_ROOT",new SwitchBufferSyncRoot(temporary.toString(),true,null).encode()));
      var receipt=q.listSwitchBufferOpsStrict().getFirst();
      q.release030RecoveryState().captureFreshRootCollection(receipt,"C");
      assertNull(q.retirementRootCollection(receipt));
      assertThrows(IllegalStateException.class,()->q.enqueueRetirementRootEntries(receipt,List.of(),"C",false,false,()->false));
    }
  }
}
