/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native null inheritance and unchanged-unit integrity must hold together. */
final class Release030RootMemberIdentityTest {
  @TempDir Path temporary;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings={"collection", "provenance", "ingestion_root", "planned_source_sha256"})
  void inheritedMembersRemainDistinctAndMetadataEditsStayHeld(String changed) throws Exception {
    Path alpha=Files.createDirectories(temporary.resolve("alpha"));
    Path beta=Files.createDirectories(temporary.resolve("beta"));
    Path old=Files.writeString(alpha.resolve("old.txt"),"old");
    Path fresh=Files.writeString(alpha.resolve("new.txt"),"new");
    Path healthy=Files.writeString(beta.resolve("healthy.txt"),"healthy");
    Path db=temporary.resolve("jobs.db");
    var provenance=new JobQueue.EnqueueProvenance("user","rpc");
    String scan=java.util.UUID.randomUUID().toString();
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.enqueueEntries(List.of(new JobQueue.EnqueueEntry(old,3,provenance,null,alpha)),"C",scan);
    }
    Release030RecoveryState.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active","building",Map.of());
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.inventoryRetirementRoots();q.retainRelease030Retirement("building",List.of(),Map.of());
      admit(q,"alpha",alpha,List.of(old,fresh));admit(q,"beta",beta,List.of(healthy));
      var before=q.watcherReconciliationRow(old.toString());
      assertEquals("C",before.get("collection"));assertEquals("user",before.get("originator"));
      assertEquals("rpc",before.get("transport"));assertEquals(scan,before.get("scan_id"));
      var newRow=q.watcherReconciliationRow(fresh.toString());
      assertTrue(newRow.get("collection")==null || newRow.get("collection").equals("default"));
      assertNull(newRow.get("originator"));assertNull(newRow.get("planned_source_sha256"));
      q.prepareRetirementClaims();String binding=q.release030RecoveryState().admissionBinding();
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
        String columns=changed.equals("provenance")?"originator='agent',transport='rpc'":changed+"=?";
        try(var write=c.prepareStatement("UPDATE jobs SET "+columns+" WHERE path=?")) {
          int parameter=1;
          if(!changed.equals("provenance"))write.setString(parameter++,changed.equals("collection")?"D":changed.equals("ingestion_root")?beta.toString():"b".repeat(64));
          write.setString(parameter,old.toString());assertEquals(1,write.executeUpdate());
        }
      }
      var altered=q.watcherReconciliationRow(old.toString());
      assertEquals(before.get("unit_revision"),altered.get("unit_revision"));
      assertEquals(binding,q.release030RecoveryState().admissionBinding());
      assertEquals(Set.of(fresh,healthy),q.pollPending(10).stream().map(JobQueue.IndexJob::path).collect(Collectors.toSet()));
      assertEquals(altered,q.watcherReconciliationRow(old.toString()));
    }
  }

  private static void admit(SqliteJobQueue q,String key,Path root,List<Path> files) {
    assertTrue(q.putSyncRootForGeneration("building",key,new SwitchBufferSyncRoot(root.toString(),true,null)));
    var receipt=q.retirementRootReceipt("building",key);
    assertEquals(files.size(),q.enqueueRetirementRootEntries(receipt,
        files.stream().map(path->JobQueue.EnqueueEntry.stat(path).withinRoot(root)).toList(),null,false,false,()->false));
  }
}
