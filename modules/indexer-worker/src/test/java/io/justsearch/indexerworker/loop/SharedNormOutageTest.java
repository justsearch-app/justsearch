/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.StatusRequest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.apache.lucene.analysis.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Own I10/O10 counterexample: a shared Similarity emits invalid norms for every input. No thrown fault. */
final class SharedNormOutageTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(45)
 void sharedInvalidNormMustFailPipelineCanary() throws Exception {
  Path log=temp.resolve("norm-child.log");
  // Gradle enables library assertions; the verifier exercises production's assertion mode.
  // Scope that mode to this owned child: no existing suite assertion setting is changed.
  var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
      "-Xmx256m","-da:org.apache.lucene...","-Dnet.bytebuddy.experimental=true",
      "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",
      System.getProperty("verifier.check.classpath",System.getProperty("java.class.path")),
      SharedNormOutageTest.class.getName(),temp.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  try {
   boolean finished=child.waitFor(40,java.util.concurrent.TimeUnit.SECONDS);
   String output=Files.readString(log);System.out.print(output);
   assertTrue(finished,"owned norms-check JVM completed: "+output);
   assertEquals(0,child.exitValue(),"real norms/recovery assertions: "+output);
  } finally {
   if(child.isAlive()) {child.destroyForcibly();assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}
  }
 }
 public static void main(String[] args) throws Exception {
  var check=new SharedNormOutageTest();check.temp=Path.of(args[0]);
  try {check.runScenario();} finally {check.closeTestExecutors();}
 }
 private void runScenario() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var broken=new AtomicBoolean(false); var badStreams=new AtomicInteger(); var probes=new AtomicInteger();
  var failures=new CopyOnWriteArrayList<Throwable>();
  var healthy=new org.apache.lucene.search.similarities.BM25Similarity();
  org.apache.lucene.search.similarities.Similarity shared=new org.apache.lucene.search.similarities.Similarity() {
   @Override public long computeNorm(org.apache.lucene.index.FieldInvertState state) {
    if(broken.get()) { badStreams.incrementAndGet(); return 0; }
    return healthy.computeNorm(state);
   }
   @Override public SimScorer scorer(float boost,org.apache.lucene.search.CollectionStatistics collection,org.apache.lucene.search.TermStatistics... terms) {
    return healthy.scorer(boost,collection,terms);
   }
  };
  var schema=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
  try(var runtime=schema.atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();
      var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"token-position",1),provider,Duration.ofSeconds(8),null)) {
   // Inject the actual shared service before its first use. It produces invalid values;
   // Lucene, rather than a fake service, must reject the healthy documents.
   var sf=RunningRuntime.class.getDeclaredField("session"); sf.setAccessible(true);
   Object session=sf.get(runtime);
   var snapf=session.getClass().getDeclaredField("snapshot"); snapf.setAccessible(true);
   Object snap=snapf.get(session);
   var wm=snap.getClass().getDeclaredMethod("writer"); wm.setAccessible(true);
   var writer=(org.apache.lucene.index.IndexWriter)wm.invoke(snap);
   var simf=org.apache.lucene.index.LiveIndexWriterConfig.class.getDeclaredField("similarity"); simf.setAccessible(true);
   simf.set(writer.getConfig(),shared);
   assertSame(shared,writer.getConfig().getSimilarity());
   var fatalExits=new CopyOnWriteArrayList<Throwable>();
   runtime.onTerminalWriterFailure(fatalExits::add);
   h.writer=mock(IndexingCoordinator.class,call->{
    try {
     Object result=call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());
     if(call.getMethod().getName().equals("probeIngestion")) probes.incrementAndGet();
     return result;
    } catch(java.lang.reflect.InvocationTargetException wrapped) {
     if(call.getMethod().getName().equals("indexSingle")) failures.add(wrapped.getCause());
     throw wrapped.getCause();
    }
   });
   h.commitOps=runtime.commitOps();
   h.enqueue("warmup.txt"); h.loop=PipelineTestFixture.withExtractor(h,timebox); h.loop.start();
   h.awaitPathState("warmup.txt","DONE",12);
   broken.set(true); h.enqueue("healthy-a.txt","healthy-b.txt");
   h.awaitPolicy(12);
   var during=h.rows().stream().filter(r->!r.path().endsWith("warmup.txt")).toList();
   long observed=System.currentTimeMillis();
   System.out.println("I10 invalid shared norms, writerOpen="+writer.isOpen()+" badStreams="+badStreams+" successful real writer probes="+probes+" real Lucene failures="+failures+" durable rows="+during);
   failures.forEach(e->e.printStackTrace(System.out));

   assertTrue(failures.size()>=2 && failures.stream().allMatch(e->e.getMessage()!=null && e.getMessage().contains("return 0 for non-empty field")),"actual indexing rejects invalid norms for all healthy documents");
   // Observe the outage before repair, including any native terminal flush failure.
   // I6 recovery belongs to the Engine owner; a tragic writer cannot be retried in place.
   boolean unchargedThroughout=during.stream().allMatch(row->row.attempts()==0);
   long observeUntil=System.nanoTime()+3_000_000_000L;
   while(writer.getTragicException()==null && writer.isOpen() && System.nanoTime()<observeUntil) {
    unchargedThroughout &= h.rows().stream().allMatch(row->row.attempts()==0);
    Thread.sleep(20);
   }
   boolean tragic=writer.getTragicException()!=null;
   boolean recovered=false;
   String fatalStatus=null;
   long recovery;
   if(tragic) {
    // A real public health observation publishes the existing one-shot owner callback.
    assertThrows(IndexWriterUnavailableException.class, runtime.indexingCoordinator()::probeIngestion);
    assertEquals(1,fatalExits.size(),"I6 fatal-exit callback fires once for the actual tragedy");
    var service=new WorkerIngestService(h.queue,h.loop,mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(),temp,temp.resolve("index"),runtime,runtime,null,0L);
    fatalStatus=service.indexStatus(StatusRequest.getDefaultInstance(),CallContext.none()).getCore().getState();
    System.out.println("I6 native norms tragedy: status="+fatalStatus+" fatalExitCallbacks="+fatalExits.size()
        +" cause="+writer.getTragicException()+" rows="+h.rows());
    unchargedThroughout &= h.rows().stream().allMatch(row->row.attempts()==0);
    h.stopLoopForOtherAssertions();
    h.store.close();h.store=null;h.queue.close();h.queue=null;
    runtime.close();
    broken.set(false);
    // Restart opens the same index/jobs.db. No source, enqueue or retry deadline is edited.
    try(var restarted=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
        var restartProvider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
        var restartedTimebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"norm-restart",1),
            restartProvider,Duration.ofSeconds(8),null)) {
     var restartedWriter=luceneWriter(restarted);
     simf.set(restartedWriter.getConfig(),shared);
     assertSame(shared,restartedWriter.getConfig().getSimilarity());
     h.openQueue();h.openStore(false);h.writer=restarted.indexingCoordinator();h.commitOps=restarted.commitOps();
     recovery=System.nanoTime();h.loop=PipelineTestFixture.withExtractor(h,restartedTimebox);h.loop.start();
     try {h.awaitAllState("DONE",10);recovered=true;} catch(AssertionError e) {System.out.println("restart recovery oracle: "+e);}
     h.stopLoopForOtherAssertions();
     assertEquals(1,fatalExits.size(),"retired runtime does not emit another fatal-exit callback");
     System.out.println("I6 writer reopened after repaired Similarity; promptRecovery="+recovered
         +" recoveryMs="+(System.nanoTime()-recovery)/1000000+" actualRows="+h.rows());
    }
   } else {
    broken.set(false);recovery=System.nanoTime();
    try {h.awaitAllState("DONE",10);recovered=true;} catch(AssertionError e) {System.out.println("in-process recovery oracle: "+e);}
    h.stopLoopForOtherAssertions();
    assertTrue(fatalExits.isEmpty(),"nontragic variant must recover without a fatal exit");
    System.out.println("I6 nontragic Similarity restored in process; promptRecovery="+recovered
        +" recoveryMs="+(System.nanoTime()-recovery)/1000000+" actualRows="+h.rows());
   }
   boolean prompt=recovered;
   boolean outageUncharged=unchargedThroughout;
   String observedFatalStatus=fatalStatus;
   var after=h.rows();
   assertAll(()->h.assertFree(during),
     ()->assertTrue(outageUncharged,"no attempts charged throughout the shared outage"),
     ()->assertTrue(during.stream().allMatch(row->row.retry()<=observed+8100),"shared outage deadlines <=8s"),
     ()->assertTrue(after.stream().allMatch(row->row.attempts()==0 && row.retry()<=System.currentTimeMillis()+8100),
         "restart retains zero attempts and second-scale persisted deadlines: "+after),
     ()->assertTrue(prompt,"both unchanged healthy files index promptly when the writer service returns"),
     ()->{if(tragic) assertEquals("ERROR",observedFatalStatus,"tragic writer follows existing I6 status path");});
  } finally { h.close();  }
 }
 private static org.apache.lucene.index.IndexWriter luceneWriter(RunningRuntime runtime) throws Exception {
  var sf=RunningRuntime.class.getDeclaredField("session");sf.setAccessible(true);
  Object session=sf.get(runtime);
  var snapf=session.getClass().getDeclaredField("snapshot");snapf.setAccessible(true);
  Object snap=snapf.get(session);
  var wm=snap.getClass().getDeclaredMethod("writer");wm.setAccessible(true);
  return (org.apache.lucene.index.IndexWriter)wm.invoke(snap);
 }

}
