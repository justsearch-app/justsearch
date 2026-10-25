/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Independent checks: live native write-lock loss is infrastructure, not document content. */
final class LiveWriterLockHealthTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 static org.apache.lucene.index.IndexWriter writer(RunningRuntime runtime) throws Exception {
  var sf=RunningRuntime.class.getDeclaredField("session");sf.setAccessible(true);var session=sf.get(runtime);
  var ss=session.getClass().getDeclaredField("snapshot");ss.setAccessible(true);var snap=ss.get(session);
  var wm=snap.getClass().getDeclaredMethod("writer");wm.setAccessible(true);return (org.apache.lucene.index.IndexWriter)wm.invoke(snap);
 }
 @Test @Timeout(40) void lostLiveNativeWriteLockMustNotPassHealthProof() throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  var actualFailures=new CopyOnWriteArrayList<Throwable>();var fatal=new CopyOnWriteArrayList<Throwable>();
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var box=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"lock-proof",1),provider,Duration.ofSeconds(5),null)) {
   var live=writer(runtime);runtime.onTerminalWriterFailure(fatal::add);
   h.writer=mock(IndexingCoordinator.class,call->{try{return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException ex){if(call.getMethod().getName().equals("indexSingle"))actualFailures.add(ex.getCause());throw ex.getCause();}});
   h.commitOps=runtime.commitOps();h.enqueue("warmup.txt");h.loop=PipelineTestFixture.withExtractor(h,box);h.loop.start();h.awaitAllState("DONE",12);
   Path lock=temp.resolve("index/write.lock");assertTrue(Files.isRegularFile(lock),"real native writer lock must exist");Files.delete(lock);
   boolean probePassed=false,exists=true;
   try{IndexingCoordinator.class.getMethod("probeIngestion").invoke(runtime.indexingCoordinator());probePassed=true;}
   catch(NoSuchMethodException absent){exists=false;}
   catch(java.lang.reflect.InvocationTargetException rejected){System.out.println("lost-lock canary rejected: "+rejected.getCause());}
   live.getConfig().setMaxBufferedDocs(2);h.enqueue("healthy-a.txt","healthy-b.txt");
   h.awaitPolicy(10);
   Thread.sleep(1200);h.stopLoopForOtherAssertions();var rows=h.rows().stream().filter(r->!r.path().endsWith("warmup.txt")).toList();
   System.out.println("LOST NATIVE LOCK: supported="+exists+" probePassed="+probePassed+" actualFailures="+actualFailures+" tragedy="+live.getTragicException()+" fatal="+fatal+" rows="+rows);
   actualFailures.forEach(e->e.printStackTrace(System.out));
   assertFalse(actualFailures.isEmpty(),"drive damaged infrastructure through running loop");
   boolean supported=exists,passed=probePassed;
   assertAll(()->assertTrue(rows.stream().allMatch(r->r.attempts()==0),"live-lock infrastructure never charges healthy source"),
     ()->{if(supported)assertFalse(passed,"canary must reject missing LIVE native writer lock even while raw Directory writes work");});
  }finally{h.close();}
 }
 @Test @Timeout(30) void repeatedProbeLeavesLiveDocumentsAndSharedAnalyzerUsable() throws Exception {
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open()) {
   var live=writer(runtime);long before=live.getDocStats().numDocs;boolean pendingBefore=live.hasUncommittedChanges();
   var analyzer=live.getConfig().getAnalyzer();
   java.lang.reflect.Method probe;
   try{probe=IndexingCoordinator.class.getMethod("probeIngestion");}
   catch(NoSuchMethodException absent){System.out.println("baseline has no canary; isolation is new behavior");return;}
   for(int i=0;i<4;i++)probe.invoke(runtime.indexingCoordinator());
   assertSame(analyzer,live.getConfig().getAnalyzer());assertEquals(before,live.getDocStats().numDocs);assertEquals(pendingBefore,live.hasUncommittedChanges(),"no canary data or real index mutations");
   try(var ts=analyzer.tokenStream("content","ordinary healthy source")){ts.reset();int tokens=0;while(ts.incrementToken())tokens++;ts.end();assertTrue(tokens>0,"shared analyzer stays open");}
   System.out.println("four full probes: real numDocs="+live.getDocStats().numDocs+" hasUncommittedChanges="+live.hasUncommittedChanges()+" shared analyzer usable");
  }
 }

 @Test @Timeout(30) void replacementLockCannotImpersonateTheHeldNativeLock() throws Exception {
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open()) {
   var live=writer(runtime);Path lock=temp.resolve("index/write.lock"),held=temp.resolve("held-write.lock");
   Object original=Files.getAttribute(lock,"creationTime");Thread.sleep(20);Files.move(lock,held);
   try {
    Files.write(lock,new byte[0],StandardOpenOption.CREATE_NEW);
    assertNotEquals(original,Files.getAttribute(lock,"creationTime"),"replacement must have a distinct creation time");
    String probe;
    try(var output=live.getDirectory().createTempOutput("raw-directory","probe",org.apache.lucene.store.IOContext.DEFAULT)){probe=output.getName();output.writeByte((byte)1);}
    live.getDirectory().deleteFile(probe);
    assertThrows(IndexWriterUnavailableException.class,runtime.indexingCoordinator()::probeIngestion,
        "an existing replacement lock must not pass as the lock held by this writer");
    assertTrue(live.isOpen());assertNull(live.getTragicException(),"the probe must not mutate the live writer");
    System.out.println("replacement exists and raw directory is writable; probe rejected wrong lock identity");
   } finally { Files.deleteIfExists(lock);Files.move(held,lock); }
   runtime.indexingCoordinator().probeIngestion();
   assertEquals(0,live.getDocStats().numDocs);assertFalse(live.hasUncommittedChanges());
  }
 }
}
