/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.indexerworker.server.Release030Fixture;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Release030ConditionalCorrectionTest {
  @TempDir Path temporary;
  private Path file,db;
  private String active,building;

  private SqliteJobQueue nativeQueue() throws Exception {
    Release030Fixture.unpack("switching",temporary);
    db=temporary.resolve("data/jobs.db");file=temporary.resolve("roots/beta/file-01.txt");
    // Preserve the authentic v13 input before the real queue performs its migration.
    Files.copy(db,temporary.resolve("native-before.db"));
    if(Files.exists(Path.of(db+"-wal")))Files.copy(Path.of(db+"-wal"),temporary.resolve("native-before.db-wal"));
    var state=tools.jackson.databind.json.JsonMapper.builder().build().readTree(
        Files.readString(temporary.resolve("data/index/default/state.json")));
    active=state.path("active_generation").asText();building=state.path("building_generation").asText();
    var q=new SqliteJobQueue(db);
    try {
      q.open();var roots=List.of(temporary.resolve("roots/alpha"),temporary.resolve("roots/beta"));
      q.release030RecoveryState().prepared(roots,q.prepareRelease030Recovery(roots,p->false));
      q.prepareRelease030ExplicitFiles();
      q.release030RecoveryState().beginRetirement(active,building,Map.of());q.inventoryRetirementRoots();
      q.retainRelease030Retirement(building,List.of(),Map.of());
      q.prepareRetirementClaims();
      return q;
    } catch(Exception failure) {q.close();throw failure;}
  }

  private SwitchBufferCapableQueue.SwitchBufferOp owner(SqliteJobQueue q) {
    return q.retirementReplayOps().stream().filter(r->r.op().equals("DELETE")&&r.payload().equals(file.toString())).findFirst().orElseThrow();
  }

  private boolean parentMatches(SwitchBufferUpsert scope) throws Exception {
    try(var fs=FSDirectory.open(temporary.resolve("data/index/default/indices").resolve(active));var reader=DirectoryReader.open(fs)) {
      for(var leaf:reader.leaves()) {var r=leaf.reader();for(int i=0;i<r.maxDoc();i++) {
        if(r.getLiveDocs()!=null&&!r.getLiveDocs().get(i))continue;
        var values=new HashMap<String,String>();var stored=r.storedFields().document(i);
        for(String key:List.of("doc_id","path","collection")) {
          String value=stored.get(key);var info=r.getFieldInfos().fieldInfo(key);
          if(value==null&&info!=null&&info.getDocValuesType()==DocValuesType.SORTED) {
            var dv=DocValues.getSorted(r,key);if(dv.advanceExact(i))value=dv.lookupOrd(dv.ordValue()).utf8ToString();
          }
          values.put(key,value);
        }
        if(scope.path().equals(values.get("doc_id"))&&scope.path().equals(values.get("path"))
            &&scope.collection().equals(values.get("collection")))return true;
      }}
    }
    return false;
  }

  private SwitchBufferCapableQueue.RetirementSourceObservation observe(SqliteJobQueue q,
      SwitchBufferCapableQueue.SwitchBufferOp receipt) throws Exception {
    var scope=new SwitchBufferUpsert(file.toString(),"default",null,null,null,file);
    String hash=io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    return new SwitchBufferCapableQueue.RetirementSourceObservation(receipt,scope,
        q.watcherReconciliationRow(file.toString()),"source/"+hash,hash,Files.size(file),false,parentMatches(scope));
  }

  @Test void changedNativeConditionalClaimsItsExactPathWithoutAnotherAck() throws Exception {
    try(var q=nativeQueue()) {
      var receipt=owner(q);var before=q.watcherReconciliationRow(file.toString());
      assertEquals("DONE",before.get("state"));assertNull(before.get("ingestion_root"));
      Files.writeString(file,"changed native conditional bytes");long admissions=q.switchBufferAdmissions();
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,receipt)),()->false));
      var packet=q.release030RecoveryState().corrections().getFirst();
      assertEquals(receipt,packet.owner());assertEquals(before,packet.predecessor());
      assertEquals(file,packet.replacement().ingestionRoot());assertEquals(admissions,q.switchBufferAdmissions());
      boolean claimed=false;
      for(int page=0;page<16&&!claimed;page++)claimed=q.pollPending(1000).stream().anyMatch(job->job.path().equals(file));
      assertTrue(claimed,"actual claim gate accepts the native operation's packet after bounded cursor pages");
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"ROOT","FILE","DELETE"})
  void laterAuthorityHoldsTheInstalledConditionalCorrection(String later) throws Exception {
    try(var q=nativeQueue()) {
      var receipt=owner(q);Files.writeString(file,"changed bytes");
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,receipt)),()->false));
      if(later.equals("ROOT"))assertTrue(q.putSyncRootForGeneration(building,"sync_root:"+file.getParent()+"/",
          new SwitchBufferSyncRoot(file.getParent().toString(),true,null),"default"));
      else if(later.equals("FILE"))assertTrue(q.enqueueAndBufferFileForGeneration(building,JobQueue.EnqueueEntry.stat(file).withinRoot(file.getParent()),null));
      else assertTrue(q.putSwitchBufferForGeneration(building,"path:"+file,"DELETE",
          new SwitchBufferDelete(file.toString(),SwitchBufferDelete.Origin.EXPLICIT,null).encode()));
      String correction=q.release030RecoveryState().corrections().getFirst().replacement().unitRevision();
      if(later.equals("FILE"))assertNotEquals(correction,q.watcherReconciliationRow(file.toString()).get("unit_revision"));
      for(int page=0;page<16;page++)assertTrue(q.pollPending(1000).stream()
          .noneMatch(job->job.path().equals(file)&&job.unitRevision().equals(correction)),"the superseded correction never claims");
    }
  }

  @Test void intentSurvivesSqlCancellationAndReopen() throws Exception {
    String unit;long admissions;
    try(var q=nativeQueue()) {
      var receipt=owner(q);Files.writeString(file,"changed bytes");admissions=q.switchBufferAdmissions();
      var calls=new AtomicInteger();
      assertThrows(IllegalStateException.class,()->q.correctRetirementSources(List.of(observe(q,receipt)),()->calls.incrementAndGet()==2));
      var packet=q.release030RecoveryState().corrections().getFirst();unit=packet.replacement().unitRevision();
      assertEquals(packet.predecessor(),q.watcherReconciliationRow(file.toString()));
    }
    try(var q=new SqliteJobQueue(db)) {
      q.open();q.retainRelease030Retirement(building,List.of(),Map.of());
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner(q))),()->false));
      assertEquals(unit,q.watcherReconciliationRow(file.toString()).get("unit_revision"));
      assertEquals(1,q.release030RecoveryState().corrections().size());assertEquals(admissions,q.switchBufferAdmissions());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"collection","unit_revision","ingestion_root","retry_after"})
  void changedNativePredecessorIsHeld(String column) throws Exception {
    try(var q=nativeQueue()) {
      var receipt=owner(q);Files.writeString(file,"changed bytes");var observation=observe(q,receipt);
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.prepareStatement("UPDATE jobs SET "+column+"=? WHERE path=?")) {
        s.setString(1,column.equals("collection")?null:"unknown");s.setString(2,file.toString());assertEquals(1,s.executeUpdate());
      }
      var changed=q.watcherReconciliationRow(file.toString());
      assertEquals(List.of(0),q.correctRetirementSources(List.of(observation),()->false));
      assertEquals(changed,q.watcherReconciliationRow(file.toString()));assertTrue(q.release030RecoveryState().corrections().isEmpty());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"unknown-namespace","modern-unowned","forged-cohort"})
  void freshlyObservedUnprovenRowIsHeld(String kind) throws Exception {
    try(var q=nativeQueue()) {
      var receipt=owner(q);Files.writeString(file,"changed bytes");
      String column=kind.equals("unknown-namespace")?"collection":"unit_revision";
      String value=kind.equals("unknown-namespace")?null:kind.equals("modern-unowned")
          ? "modern-unowned" : "release030/job/123/"+"a".repeat(32);
      try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.prepareStatement("UPDATE jobs SET "+column+"=? WHERE path=?")) {
        s.setString(1,value);s.setString(2,file.toString());assertEquals(1,s.executeUpdate());
      }
      var changed=q.watcherReconciliationRow(file.toString());
      assertEquals(List.of(0),q.correctRetirementSources(List.of(observe(q,receipt)),()->false));
      assertEquals(changed,q.watcherReconciliationRow(file.toString()));assertTrue(q.release030RecoveryState().corrections().isEmpty());
    }
  }

  private Properties checkpoint() throws Exception {
    var properties=new Properties();
    try(var input=Files.newInputStream(Release030RecoveryState.path(db))) {properties.load(input);}
    return properties;
  }

  private void prepareAgain(SqliteJobQueue q) throws Exception {
    var roots=List.of(temporary.resolve("roots/alpha"),temporary.resolve("roots/beta"));
    var recovery=q.release030RecoveryState();
    recovery.prepared(roots,q.prepareRelease030Recovery(roots,p->false));
    q.prepareRelease030ExplicitFiles();
    q.retainRelease030Retirement(building,List.of(),Map.of());
    q.prepareRetirementClaims();
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"missing","wrong"})
  void checkpointWithoutOriginalCohortHoldsChangedSource(String kind) throws Exception {
    try(var q=nativeQueue()) {assertNotNull(owner(q));}
    var properties=checkpoint();
    if(kind.equals("missing")) properties.remove("native-cohort");
    else properties.setProperty("native-cohort","2147483647");
    try(var output=Files.newOutputStream(Release030RecoveryState.path(db))) {properties.store(output,"test");}
    try(var q=new SqliteJobQueue(db)) {
      q.open();prepareAgain(q);Files.writeString(file,"changed bytes");
      var before=q.watcherReconciliationRow(file.toString());
      assertEquals(List.of(0),q.correctRetirementSources(List.of(observe(q,owner(q))),()->false));
      assertEquals(before,q.watcherReconciliationRow(file.toString()));
      assertTrue(q.release030RecoveryState().corrections().isEmpty());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"missing","wrong"})
  void installedCorrectionNeedsOriginalCohortAtActualClaim(String kind) throws Exception {
    String unit;
    try(var q=nativeQueue()) {
      Files.writeString(file,"changed before cohort loss");
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner(q))),()->false));
      unit=q.watcherReconciliationRow(file.toString()).get("unit_revision");
    }
    var properties=checkpoint();
    if(kind.equals("missing")) properties.remove("native-cohort");
    else properties.setProperty("native-cohort","2147483647");
    try(var output=Files.newOutputStream(Release030RecoveryState.path(db))) {properties.store(output,"test");}
    try(var q=new SqliteJobQueue(db)) {
      q.open();prepareAgain(q);
      assertEquals(unit,q.watcherReconciliationRow(file.toString()).get("unit_revision"));
      for(int page=0;page<16;page++)assertTrue(q.pollPending(1000).stream()
          .noneMatch(job->job.path().equals(file)),"lost native cohort holds an installed packet at actual claim");
      assertEquals(1,q.release030RecoveryState().corrections().size());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings={"restart","copy"})
  void originalCohortSurvivesCurrentDatabasePreparation(String kind) throws Exception {
    String cohort;
    try(var q=nativeQueue()) {assertNotNull(owner(q));cohort=checkpoint().getProperty("native-cohort");assertNotNull(cohort);}
    if(kind.equals("copy")) {
      Path copied=Files.createDirectory(temporary.resolve("copied"));
      Files.copy(db,copied.resolve("jobs.db"));
      Files.copy(Release030RecoveryState.path(db),copied.resolve("upgrade-030-recovery.properties"));
      db=copied.resolve("jobs.db");
    }
    try(var q=new SqliteJobQueue(db)) {
      q.open();
      if(kind.equals("copy")) assertFalse(q.release030RecoveryState().preparedFor(q.release030RecoveryIdentity()));
      prepareAgain(q);assertEquals(cohort,checkpoint().getProperty("native-cohort"));
      Files.writeString(file,"changed bytes after "+kind);
      assertEquals(List.of(1),q.correctRetirementSources(List.of(observe(q,owner(q))),()->false));
      boolean claimed=false;
      for(int page=0;page<16&&!claimed;page++)claimed=q.pollPending(1000).stream().anyMatch(job->job.path().equals(file));
      assertTrue(claimed,"prepared "+kind+" retains exact native correction authority");
    }
  }

  @Test void restoredConflictingV13CohortHoldsWithoutOverwritingCheckpoint() throws Exception {
    try(var q=nativeQueue()) {assertNotNull(owner(q));}
    byte[] retained=Files.readAllBytes(Release030RecoveryState.path(db));
    Files.copy(temporary.resolve("native-before.db"),db,StandardCopyOption.REPLACE_EXISTING);
    if(Files.exists(temporary.resolve("native-before.db-wal")))
      Files.copy(temporary.resolve("native-before.db-wal"),Path.of(db+"-wal"),StandardCopyOption.REPLACE_EXISTING);
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
      s.execute("PRAGMA application_id=2147483647");
    }
    try(var q=new SqliteJobQueue(db)) {assertThrows(java.io.IOException.class,q::open);}
    assertArrayEquals(retained,Files.readAllBytes(Release030RecoveryState.path(db)));
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var row=s.executeQuery("PRAGMA user_version")) {
      assertTrue(row.next());assertEquals(13,row.getInt(1));
    }
  }

  @Test void failedNativeMigrationRetainsCapturedCohortForRetry() throws Exception {
    try(var q=nativeQueue()) {assertNotNull(owner(q));}
    Path failed=Files.createDirectory(temporary.resolve("failed"));
    Files.copy(temporary.resolve("native-before.db"),failed.resolve("jobs.db"));
    if(Files.exists(temporary.resolve("native-before.db-wal")))
      Files.copy(temporary.resolve("native-before.db-wal"),failed.resolve("jobs.db-wal"));
    db=failed.resolve("jobs.db");
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
      s.execute("CREATE TRIGGER r33_fail BEFORE UPDATE ON jobs BEGIN SELECT RAISE(ABORT,'r33 migration failure'); END");
    }
    try(var q=new SqliteJobQueue(db)) {assertThrows(java.sql.SQLException.class,q::open);}
    String cohort=checkpoint().getProperty("native-cohort");assertNotNull(cohort);
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
      try(var row=s.executeQuery("PRAGMA user_version")) {assertTrue(row.next());assertEquals(13,row.getInt(1));}
      try(var row=s.executeQuery("PRAGMA application_id")) {assertTrue(row.next());assertEquals(cohort,row.getString(1));}
      s.execute("DROP TRIGGER r33_fail");
    }
    try(var q=new SqliteJobQueue(db)) {
      q.open();prepareAgain(q);assertEquals(cohort,checkpoint().getProperty("native-cohort"));
      assertTrue(q.watcherReconciliationRow(file.toString()).get("unit_revision").startsWith("release030/job/"+cohort+"/"));
    }
  }

}
