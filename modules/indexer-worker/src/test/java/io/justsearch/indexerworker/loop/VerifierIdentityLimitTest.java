/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** U17/O8: a real shared identity-store resource limit; UUID/hash widths are engine constants. */
final class VerifierIdentityLimitTest {
 @TempDir Path temp;
 @Test @Timeout(55)
 void nativeIdentityRowLimitMustNotChargeHealthyFilesEvenWhenExtractionCanaryPasses() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var connectionField=SqliteDocumentIdentityStore.class.getDeclaredField("connection"); connectionField.setAccessible(true);
  var connection=(org.sqlite.SQLiteConnection)connectionField.get(h.store);
  var db=connection.getDatabase(); int originalLimit=db.limit(0,-1); // SQLITE_LIMIT_LENGTH
  var probes=new AtomicInteger(); var successfulProbes=new AtomicInteger();
  try(var owners=new TestEngineExecutors(); var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
   var sources=h.enqueue("identity-limit-a.txt","identity-limit-b.txt");
   assertTrue(provider.extractArtifact(sources.getFirst()).result().content().contains("healthy"));
   var good=h.store.resolve("0".repeat(64),System.currentTimeMillis()); assertEquals(36,good.docUid().length());
   db.limit(0,80);
   var actual=assertThrows(IllegalStateException.class,()->h.store.resolve("1".repeat(64),System.currentTimeMillis()));
   assertInstanceOf(SQLException.class,actual.getCause());
   assertEquals(18,((SQLException)actual.getCause()).getErrorCode()&255,"native fixed-width identity row must fail SQLITE_TOOBIG");
   assertFalse(actual.getClass().getSimpleName().equals("IngestionServiceUnavailableException"),"this is an untyped shared infrastructure condition");
   System.out.println("U17 IDENTITY QUOTA actual="+actual+" cause="+actual.getCause()+" old limit="+originalLimit+" current limit="+db.limit(0,-1)+" source hash width=64 generated UUID width=36 sources remain readable=true");
   ExtractionSandbox measured=new ExtractionSandbox() {
    @Override public ExtractionArtifact extract(Path source) throws java.io.IOException,ContentExtractor.ExtractionException {
     boolean probe=source.getFileName().toString().startsWith("justsearch-extraction-canary-");
     if(probe) probes.incrementAndGet();
     var artifact=provider.extractArtifact(source);
     if(probe && artifact.result().content().contains("healthy extraction canary")) successfulProbes.incrementAndGet();
     return artifact;
    }
   };
   try(var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"identity-limit-timebox",1),measured,Duration.ofSeconds(10),null)) {
    h.loop=PipelineTestFixture.withExtractor(h,timebox); h.loop.start(); h.awaitPolicy(8);
    long end=System.nanoTime()+18_000_000_000L;
    while(System.nanoTime()<end && h.rows().stream().allMatch(r->r.attempts()==0)) Thread.sleep(25);
    // Observe a completed batch rather than an intermediate callback.
    end=System.nanoTime()+5_000_000_000L;
    while(System.nanoTime()<end && h.rows().stream().anyMatch(r->r.state().equals("PROCESSING"))) Thread.sleep(25);
    var during=h.rows(); long observed=System.currentTimeMillis();
    System.out.println("U17 IDENTITY QUOTA outage rows="+during+" canary calls="+probes+" real successful RTF canaries="+successfulProbes);
    db.limit(0,originalLimit);
    assertEquals(36,h.store.resolve("1".repeat(64),System.currentTimeMillis()).docUid().length(),"restoring only the engine-owned connection limit restores the identical fixed-width operation");
    if(during.stream().allMatch(r->r.attempts()==0)) h.awaitAllState("DONE",12);
    else Thread.sleep(1000);
    h.stopLoopForOtherAssertions();
    System.out.println("U17 IDENTITY QUOTA restored identical service healthy, rows="+h.rows()+" claimable="+h.queue.pollPending(16).size());
    for(var source:sources) assertTrue(Files.isReadable(source));
    assertAll(
     ()->h.assertFree(during),
     ()->assertTrue(during.stream().allMatch(r->r.retry()<=observed+8_100),"engine-only identity failure must never impose minute-scale file deadlines"));
   }
  } finally { db.limit(0,originalLimit); h.close(); }
 }
}
