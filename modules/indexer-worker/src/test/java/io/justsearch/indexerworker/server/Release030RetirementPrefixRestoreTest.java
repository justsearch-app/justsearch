/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** A genuine earlier SAMEACK SQLite snapshot must reuse the frozen legacy conversion. */
@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementPrefixRestoreTest {
 @Test void restoredRawPrefixReusesTheExactFrozenSuccessor(@TempDir Path root)throws Exception {
  Release030Fixture.unpack("switching",root);var original=state(root);Path alpha=root.resolve("roots/alpha"),held=Files.move(alpha,root.resolve("held-alpha"));
  Path scope=Files.createDirectories(root.resolve("roots/beta/legacy-empty-scope")),database=root.resolve("data/jobs.db"),snapshot=root.resolve("raw-prefix-same-ack.db");
  String key="prefix:"+PathNormalizer.normalizePathPrefix(scope.toString());
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean();
  var raw=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();var converted=new AtomicReference<SwitchBufferCapableQueue.SwitchBufferOp>();long[] count={0};
  ConfigStore previous=ConfigStore.globalOrNull();var installed=configure(root);
  try(var models=new ReleasedModelIdentities(root)) {
   try(var ex=new TestEngineExecutors();var server=Release030RetirementTransitiveOrderTest.bootRoots(root,ex,false,t->{
    if(t.point().equals("release030-before-replay")&&first.compareAndSet(false,true)){entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));}
   })) {
    activate(server);assertTrue(entered.await(30,TimeUnit.SECONDS));
    // The real compatibility admission owner accepts the captured 0.3.0 wire shape.
    assertTrue(queue(server).putSwitchBufferForGeneration(original.building_generation(),key,"DELETE_PREFIX",scope.toString()));
    raw.set(queue(server).listSwitchBufferOpsStrict().stream().filter(op->op.key().equals(key)).findFirst().orElseThrow());count[0]=queue(server).switchBufferAdmissions();
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);var sql=c.createStatement()){sql.execute("VACUUM INTO '"+snapshot+"'");}
    assertEquals(count[0],admissions(snapshot));release.countDown();
    await(()->{var op=queue(server).listSwitchBufferOpsStrict().stream().filter(o->o.key().equals(key)).findFirst().orElseThrow();
      if(io.justsearch.adapters.lucene.runtime.PathPrefixScope.isLegacy(op.payload()))return false;converted.set(op);return true;
    },"real first conversion is published",30);
    assertEquals(raw.get().acceptedOrder(),converted.get().acceptedOrder());assertNotEquals(raw.get().revision(),converted.get().revision());assertEquals(count[0],queue(server).switchBufferAdmissions());
    Release030RetirementSafetyTest.assertRetained(root,original.building_generation());
    save(root,"converted",snapshot,raw.get(),converted.get());
   }finally{release.countDown();}
   Files.copy(snapshot,database,StandardCopyOption.REPLACE_EXISTING);
   assertEquals(Release030Fixture.sha256(Files.readAllBytes(snapshot)),Release030Fixture.sha256(Files.readAllBytes(database)));
   assertEquals(count[0],admissions(database));
   for(int restart=0;restart<2;restart++)try(var ex=new TestEngineExecutors();var server=Release030RetirementTransitiveOrderTest.bootRoots(root,ex,false,t->{})) {
    activate(server);
    await(()->queue(server).listSwitchBufferOpsStrict().stream().filter(o->o.key().equals(key)).anyMatch(o->!io.justsearch.adapters.lucene.runtime.PathPrefixScope.isLegacy(o.payload())),"restored raw receipt converts normally",30);
    var actual=queue(server).listSwitchBufferOpsStrict().stream().filter(o->o.key().equals(key)).findFirst().orElseThrow();
    assertEquals(converted.get(),actual,"restored raw predecessor must reuse the exact planned payload/UUID/order, not another admission");
    assertEquals(count[0],queue(server).switchBufferAdmissions());assertTrue(search(server,"nebula").contains(key(alpha.resolve("file-01.txt"))));
    assertEquals(original.active_generation(),state(root).active_generation());Release030RetirementSafetyTest.assertRetained(root,original.building_generation());save(root,"restart-"+restart,database,raw.get(),actual);
   }
  }finally{release.countDown();try{if(Files.exists(held))Files.move(held,alpha);}finally{ConfigStore.restoreGlobal(installed,previous);}}
 }
 static long admissions(Path database)throws Exception {
  try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:file:"+database+"?mode=ro");var q=c.createStatement();var r=q.executeQuery("SELECT count FROM switch_buffer_admissions WHERE id=1")){assertTrue(r.next());return r.getLong(1);}
 }
 static void save(Path root,String label,Path database,Object raw,Object typed)throws Exception {
  System.out.println("PREFIX_RESTORE "+label+" raw="+raw+" typed="+typed+" count="+admissions(database));
  String evidence=System.getenv("VERIFY_EVIDENCE");if(evidence==null)return;
  Path out=Path.of(evidence).resolve("prefix-"+label);for(int n=2;Files.exists(out);n++)out=Path.of(evidence).resolve("prefix-"+label+"-"+n);Files.createDirectories(out);
  if(database.getFileName().toString().equals("jobs.db")) {
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);var sql=c.createStatement()){sql.execute("VACUUM INTO '"+out.resolve("jobs.db")+"'");}
  }else Files.copy(database,out.resolve("jobs.db"));
  Files.copy(root.resolve("data/upgrade-030-recovery.properties"),out.resolve("recovery.properties"));
 }
}
