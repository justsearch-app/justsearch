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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.codecs.*;
import org.apache.lucene.index.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Shared codec has a dead doc-values service, which every ordinary source write uses. */
final class DocValuesServiceOutageTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(30) void textOnlyCanaryMustExerciseSharedDocValuesWriteService() throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  var faults=new AtomicInteger();var writeFailures=new CopyOnWriteArrayList<Throwable>();
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();
      var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"doc-values",1),provider,Duration.ofSeconds(5),null)) {
   var sf=RunningRuntime.class.getDeclaredField("session");sf.setAccessible(true);var session=sf.get(runtime);
   var ss=session.getClass().getDeclaredField("snapshot");ss.setAccessible(true);var snapshot=ss.get(session);
   var wm=snapshot.getClass().getDeclaredMethod("writer");wm.setAccessible(true);var w=(IndexWriter)wm.invoke(snapshot);
   var original=w.getConfig().getCodec();var originalDv=original.docValuesFormat();
   var unavailable=new DocValuesFormat("UnavailableSharedDV") {
    public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws java.io.IOException {
     faults.incrementAndGet();throw new java.io.IOException("shared doc-values format unavailable");
    }
    public DocValuesProducer fieldsProducer(SegmentReadState state) throws java.io.IOException { return originalDv.fieldsProducer(state); }
   };
   var broken=new FilterCodec(original.getName(),original) { public DocValuesFormat docValuesFormat(){return unavailable;} };
   var cf=LiveIndexWriterConfig.class.getDeclaredField("codec");cf.setAccessible(true);cf.set(w.getConfig(),broken);
   w.getConfig().setMaxBufferedDocs(2);
   boolean probeReturned=false,probeExists=true;
   try { IndexingCoordinator.class.getMethod("probeIngestion").invoke(runtime.indexingCoordinator());probeReturned=true; }
   catch(NoSuchMethodException absent) { probeExists=false; }
   catch(java.lang.reflect.InvocationTargetException expected) {System.out.println("Canary rejected actual codec fault: "+expected.getCause());}
   int faultCountAfterProbe=faults.get();
   h.writer=mock(IndexingCoordinator.class,call->{
    try {return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException e) {if(call.getMethod().getName().equals("indexSingle"))writeFailures.add(e.getCause());throw e.getCause();}
   });
   h.commitOps=runtime.commitOps();h.enqueue("healthy-a.txt","healthy-b.txt");
   h.loop=PipelineTestFixture.withExtractor(h,timebox);h.loop.start();
   long end=System.nanoTime()+10_000_000_000L;
   while(faults.get()==faultCountAfterProbe && System.nanoTime()<end)Thread.sleep(20);
   Thread.sleep(300);h.stopLoopForOtherAssertions();
   System.out.println("O10 shared codec input-independent outage: probeExists="+probeExists+" probeReturned="+probeReturned+" faultCountAfterProbe="+faultCountAfterProbe+" totalFaults="+faults+" actualWriteFailures="+writeFailures+" tragedy="+w.getTragicException()+" rows="+h.rows());
   assertTrue(faults.get()>faultCountAfterProbe,"actual live Lucene indexing must use the damaged shared service");
   boolean falseHealth=probeReturned;boolean supported=probeExists;
   assertAll(()->assertTrue(h.rows().stream().allMatch(r->r.attempts()==0),"shared write-path failure must not charge healthy sources"),
    ()->{if(supported)assertFalse(falseHealth,"canary passed while the live codec's shared doc-values writer was unavailable");});
  }finally{h.close();}
 }
}
