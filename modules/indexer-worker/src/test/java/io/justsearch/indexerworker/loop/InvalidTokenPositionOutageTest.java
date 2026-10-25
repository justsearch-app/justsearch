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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Shared analyzer emits an invalid FIRST token position for every input, including the canary.
 * The attribute API permits zero; Lucene's real indexing chain rejects it. No synthetic exception.
 */
final class InvalidTokenPositionOutageTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(45)
 void brokenSharedTokenPositionsMustFailPipelineCanary() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var broken=new AtomicBoolean(false); var badStreams=new AtomicInteger(); var probes=new AtomicInteger();
  var failures=new CopyOnWriteArrayList<Throwable>();
  Analyzer analyzer=new Analyzer() {
   @Override protected TokenStreamComponents createComponents(String field) {
    var tokenizer=new StandardTokenizer();
    var stream=new TokenFilter(tokenizer) {
     final PositionIncrementAttribute pos=addAttribute(PositionIncrementAttribute.class);
     boolean first;
     @Override public void reset() throws java.io.IOException { super.reset(); first=true; }
     @Override public boolean incrementToken() throws java.io.IOException {
      if(!input.incrementToken()) return false;
      if(first && broken.get()) { pos.setPositionIncrement(0); badStreams.incrementAndGet(); }
      first=false; return true;
     }
    };
    return new TokenStreamComponents(tokenizer,stream);
   }
  };
  var schema=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
  var cf=schema.analyzerRegistry().getClass().getDeclaredField("analyzersById"); cf.setAccessible(true);
  @SuppressWarnings("unchecked") var cache=(Map<String,Analyzer>)cf.get(schema.analyzerRegistry());
  for(String id:schema.analyzerRegistry().analyzerIds()) cache.put(id,analyzer);
  try(var runtime=schema.atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();
      var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"token-position",1),provider,Duration.ofSeconds(8),null)) {
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
   System.out.println("I10 invalid shared token positions, writerOpen="+writerOpen(runtime)+" badStreams="+badStreams+" successful real writer probes="+probes+" real Lucene failures="+failures+" durable rows="+during);
   failures.forEach(e->e.printStackTrace(System.out));
   assertTrue(writerOpen(runtime));
   assertTrue(failures.size()>=2 && failures.stream().allMatch(e->e instanceof IllegalArgumentException && e.getMessage().contains("first position increment")),"actual indexing rejects invalid positions for all healthy documents");
   // Repair only the shared analyzer; do not re-enqueue, edit sources or shorten durable deadlines.
   broken.set(false); long recovery=System.nanoTime();
   boolean recovered=false;
   try { h.awaitAllState("DONE",10); recovered=true; } catch(AssertionError e) { System.out.println("recovery oracle: "+e); }
   h.stopLoopForOtherAssertions();
   System.out.println("I10 shared analyzer restored; promptRecovery="+recovered+" recoveryMs="+(System.nanoTime()-recovery)/1000000+" actualRows="+h.rows());
   boolean prompt=recovered;
   assertAll(()->h.assertFree(during),
     ()->assertTrue(during.stream().allMatch(r->r.retry()<=observed+8100),"shared outage deadlines <=8s"),
     ()->assertTrue(prompt,"unchanged healthy files recover within seconds"));
  } finally { h.close(); analyzer.close(); }
 }
  private static boolean writerOpen(RunningRuntime runtime) throws Exception {
    var sessionField = RunningRuntime.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    Object session = sessionField.get(runtime);
    var snapshotField = session.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    var writerMethod = snapshot.getClass().getDeclaredMethod("writer");
    writerMethod.setAccessible(true);
    return ((org.apache.lucene.index.IndexWriter) writerMethod.invoke(snapshot)).isOpen();
  }
}
