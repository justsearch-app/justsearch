/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class Release030RetirementClaimOwnershipTest {
  @TempDir Path temporary;

  @ParameterizedTest(name="{0} owner / changed {1}")
  @CsvSource({"exact,planned_source_sha256", "exact,collection", "exact,originator", "exact,transport", "exact,ingestion_root",
      "root,planned_source_sha256", "root,collection", "root,originator", "root,transport", "root,ingestion_root", "unknown,unit_revision"})
  void changedMetadataStaysHeldAfterBindingWasPreparedWhileDisjointRootClaims(String owner, String column) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),beta=Files.createDirectories(temporary.resolve("beta"));
    Path target=Files.writeString(alpha.resolve("target.txt"),"target"), healthy=Files.writeString(beta.resolve("healthy.txt"),"healthy");
    Path db=temporary.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      assertTrue(q.putSwitchBufferForGeneration("building","delete-alpha","DELETE_PREFIX",new PathPrefixScope(alpha.toString(),List.of()).encode()));
      var deletion=q.retirementReplayOps().getFirst();q.release030RecoveryState().markDeletionCommitted(deletion);
      var root=admitRoot(q,alpha,target);
      if(owner.equals("exact")) assertTrue(q.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(target).withinRoot(alpha),null,null));
      admitRoot(q,beta,healthy);
      q.prepareRetirementClaims();
      var before=q.watcherReconciliationRow(target.toString());
      String binding=q.release030RecoveryState().admissionBinding();
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var write=c.prepareStatement("UPDATE jobs SET "+column+"=? WHERE path=?")) {
        write.setString(1,owner.equals("legacy")?"release030/job/123/"+"a".repeat(32)
            :column.equals("planned_source_sha256")?"b".repeat(64):column.equals("ingestion_root")?temporary.toString():"unknown");
        write.setString(2,target.toString());assertEquals(1,write.executeUpdate());
      }
      var changed=q.watcherReconciliationRow(target.toString());
      if(!column.equals("unit_revision"))assertEquals(before.get("unit_revision"),changed.get("unit_revision"));
      assertEquals(binding,q.release030RecoveryState().admissionBinding(),"receipt-set binding itself did not change");
      var claims=q.pollPending(10);
      assertEquals(List.of(healthy),claims.stream().map(JobQueue.IndexJob::path).toList(),"unknown target stays held; disjoint ROOT progresses");
      assertEquals(changed,q.watcherReconciliationRow(target.toString()),"claim guard preserves the complete held row");
      assertTrue(q.release030RecoveryState().deletionQueueComplete(deletion));
      assertFalse(q.release030RecoveryState().rootWalked(root));
    }
  }

  @ParameterizedTest(name="obsolete {0} owner / changed {1}")
  @CsvSource({"exact,planned_source_sha256", "exact,collection", "exact,originator", "exact,transport", "exact,ingestion_root",
      "root,planned_source_sha256", "root,collection", "root,originator", "root,transport", "root,ingestion_root", "legacy,unit_revision"})
  void obsoleteMismatchedMetadataIsHeldRatherThanAdministrativelyErased(String owner,String column) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),beta=Files.createDirectories(temporary.resolve("beta"));
    Path target=Files.writeString(alpha.resolve("target.txt"),"target"),healthy=Files.writeString(beta.resolve("healthy.txt"),"healthy");
    Path db=temporary.resolve("jobs.db");try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      admitRoot(q,alpha,target);
      if(owner.equals("exact"))assertTrue(q.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(target).withinRoot(alpha),null,null));
      assertTrue(q.putSwitchBufferForGeneration("building","delete-alpha","DELETE_PREFIX",new PathPrefixScope(alpha.toString(),List.of()).encode()));
      var deletion=q.retirementReplayOps().getLast();q.release030RecoveryState().markDeletionCommitted(deletion);
      admitRoot(q,beta,healthy);
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var write=c.prepareStatement("UPDATE jobs SET "+column+"=? WHERE path=?")) {
        write.setString(1,owner.equals("legacy")?"release030/job/123/"+"a".repeat(32)
            :column.equals("planned_source_sha256")?"b".repeat(64):column.equals("ingestion_root")?temporary.toString():"unknown");
        write.setString(2,target.toString());assertEquals(1,write.executeUpdate());
      }
      var changed=q.watcherReconciliationRow(target.toString());q.prepareRetirementClaims();
      assertEquals(changed,q.watcherReconciliationRow(target.toString()),"unproven obsolete metadata cannot authorize administrative removal");
      assertEquals(List.of(healthy),q.pollPending(10).stream().map(JobQueue.IndexJob::path).toList());
      assertTrue(q.release030RecoveryState().deletionQueueComplete(deletion));
    }
  }

  @ParameterizedTest(name="historical {0} / later {1} owner")
  @CsvSource({"exact,root", "exact,exact", "prefix,root", "prefix,exact", "collection,root", "collection,exact"})
  void historicalScopesPreserveLaterPendingOwnersAcrossReopen(String scope,String owner) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),target=Files.writeString(alpha.resolve("target.txt"),"target");
    Path db=temporary.resolve("jobs.db");try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    Map<String,String> admitted;SwitchBufferCapableQueue.SwitchBufferOp deletion;
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      String kind=scope.equals("exact")?"DELETE":scope.equals("prefix")?"DELETE_PREFIX":"DELETE_COLLECTION";
      String payload=scope.equals("exact")?new SwitchBufferDelete(target.toString(),SwitchBufferDelete.Origin.EXPLICIT,null).encode()
          :scope.equals("prefix")?new PathPrefixScope(alpha.toString(),List.of()).encode():"default";
      assertTrue(q.putSwitchBufferForGeneration("building","delete-alpha",kind,payload));
      deletion=q.retirementReplayOps().getFirst();q.release030RecoveryState().markDeletionCommitted(deletion);
      admitRoot(q,alpha,target);
      if(owner.equals("exact"))assertTrue(q.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(target).withinRoot(alpha),null,null));
      admitted=q.watcherReconciliationRow(target.toString());q.prepareRetirementClaims();
      assertEquals(admitted,q.watcherReconciliationRow(target.toString()));
      assertTrue(q.release030RecoveryState().deletionQueueComplete(deletion));
    }
    try(var q=new SqliteJobQueue(db)) {
      q.open();assertFalse(q.release030RecoveryState().deletionQueueComplete(deletion),"reopen needs fresh queue-owned proof");
      q.prepareRetirementClaims();assertTrue(q.release030RecoveryState().deletionQueueComplete(deletion));
      assertEquals(admitted,q.watcherReconciliationRow(target.toString()));
      var claimed=q.pollPending(1);assertEquals(1,claimed.size());assertEquals(admitted.get("unit_revision"),claimed.getFirst().unitRevision());
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"outside","excluded","policy"})
  void rootIdentityCannotAuthorizeOutsideItsScopeOrExclusions(String kind) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),beta=Files.createDirectories(temporary.resolve("beta"));
    Path nested=Files.createDirectories(alpha.resolve("nested")),hidden=Files.createDirectories(alpha.resolve(".git"));
    Path target=Files.writeString((kind.equals("outside")?beta:kind.equals("excluded")?nested:hidden).resolve("target.txt"),"target");
    Path healthy=Files.writeString(beta.resolve("healthy.txt"),"healthy"),db=temporary.resolve("jobs.db");
    try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.journalRelease030Roots("building",List.of(alpha,beta,nested));q.inventoryRetirementRoots();
      q.retainRelease030Retirement("building",List.of(),Map.of());
      if(kind.equals("outside")) {
        var intent=admitRoot(q,alpha,Files.writeString(alpha.resolve("normal.txt"),"normal"));
        q.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(target).withinRoot(beta)));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var write=c.prepareStatement("UPDATE jobs SET unit_revision=?,ingestion_root=? WHERE path=?")) {
          write.setString(1,intent.retirementRootUnit());write.setString(2,alpha.toString());write.setString(3,target.toString());assertEquals(1,write.executeUpdate());
        }
      } else admitRoot(q,alpha,target);
      admitRoot(q,beta,healthy);var before=q.watcherReconciliationRow(target.toString());q.prepareRetirementClaims();
      assertFalse(q.pollPending(10).stream().anyMatch(claim->claim.path().equals(target)));
      assertEquals(before,q.watcherReconciliationRow(target.toString()));
      assertEquals("PROCESSING",q.watcherReconciliationRow(healthy.toString()).get("state"));
    }
  }

  @Test void nonhistoricalDeleteCannotAcquireHistoricalQueueProof() throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),target=Files.writeString(alpha.resolve("target.txt"),"target");
    Path db=temporary.resolve("jobs.db");try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      assertTrue(q.putSwitchBufferForGeneration("building","delete-alpha","DELETE_PREFIX",new PathPrefixScope(alpha.toString(),List.of()).encode()));
      var deletion=q.retirementReplayOps().getFirst();admitRoot(q,alpha,target);q.prepareRetirementClaims();
      assertFalse(q.release030RecoveryState().deletionCommitted(deletion));
      assertFalse(q.release030RecoveryState().deletionQueueComplete(deletion),"metadata cannot invent an A commitment");
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void historicalReconciliationRetainsRecordedCoverageAndUnacknowledgedSeal(boolean sealed) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha")),target=Files.writeString(alpha.resolve("recorded.txt"),"recorded");
    Path db=temporary.resolve("jobs.db");try(var q=new SqliteJobQueue(db)){q.open();}
    Release030RecoveryState.capture(db);Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    String key="01994180-0000-7000-8000-000000000033";
    try(var q=new SqliteJobQueue(db,ignored->JobQueue.RecordedClaimDecision.ALLOW)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      var walk=q.beginRecordedWalk(key,"a".repeat(64),true);
      q.enqueueRecordedEntries(key,walk.enumerationEpoch(),List.of(JobQueue.EnqueueEntry.stat(target).withinRoot(alpha)),null);
      assertTrue(q.enqueueAndBufferFileForGeneration("building",JobQueue.EnqueueEntry.stat(target).withinRoot(alpha),null,null));
      if(sealed) {
        var claim=q.pollPending(1).getFirst();
        q.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim,null,claim.plannedSourceSha256())),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL,
                "SUCCESS",io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        q.closeRecordedWalkEnumeration(key,walk.enumerationEpoch(),JobQueue.WalkEnumerationOutcome.COMPLETE);
        assertNotNull(q.trySealRecordedWalk(key).sealedAt());
      }
      var before=q.watcherReconciliationRow(target.toString());var progress=q.recordedWalk(key).orElseThrow();
      assertTrue(q.putSwitchBufferForGeneration("building","delete-alpha","DELETE_PREFIX",new PathPrefixScope(alpha.toString(),List.of()).encode()));
      var deletion=q.retirementReplayOps().getLast();q.release030RecoveryState().markDeletionCommitted(deletion);
      // A later admission invalidates the original queue checkpoint without replacing this member.
      assertTrue(q.putSyncRootForGeneration("building","sync_root:"+alpha+"/",new SwitchBufferSyncRoot(alpha+"/",true,null)));
      q.prepareRetirementClaims();
      var after=q.watcherReconciliationRow(target.toString());assertNotNull(after);
      assertEquals(before.get("unit_revision"),after.get("unit_revision"));
      assertTrue(q.release030RecoveryState().deletionQueueComplete(deletion));
      assertTrue(q.pollPending(10).isEmpty());
      if(sealed) {
        assertEquals(before,after);assertEquals(progress,q.recordedWalk(key).orElseThrow());
        assertTrue(progress.acknowledgedRevision()<progress.revision());
      } else {
        assertEquals("DONE",after.get("state"));assertEquals("STALE_SOURCE",after.get("last_outcome_class"));
        assertEquals("SOURCE_REMOVED",after.get("last_reason_code"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var query=c.prepareStatement(
            "SELECT COUNT(*) FROM ingestion_ledger WHERE operation_key=? AND unit_revision=? AND terminal_coverage='SKIPPED'")) {
          query.setString(1,key);query.setString(2,before.get("unit_revision"));
          try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
        }
      }
    }
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp admitRoot(SqliteJobQueue q,Path root,Path file) {
    String key="sync_root:"+io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(root.toString());
    assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(root.toString()),true,null)));
    var receipt=q.retirementRootReceipt("building",key);
    assertEquals(1,q.enqueueRetirementRootEntries(receipt,List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)),null,false,false,()->false));
    return receipt;
  }
}
