/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.ipc.StatusRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Own shared component failure: the mapping dependency is unavailable for ALL inputs,
 * including the full canary. A non-VM linkage failure must not escape batch finalization. */
final class SharedMappingLinkageTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(100) void failedSharedMappingAndItsCanaryMustNotStrandClaimedRows() throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  var broken=new AtomicBoolean();var mapFailures=new AtomicInteger();
  var writeFailures=new CopyOnWriteArrayList<Throwable>();
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open();var executors=new TestEngineExecutors();
      var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var box=new TimeboxedContentExtractor(PipelineTestFixture.registration(executors,"mapping-linkage",1),parser,Duration.ofSeconds(5),null)) {
   var sessionField=RunningRuntime.class.getDeclaredField("session");sessionField.setAccessible(true);Object session=sessionField.get(runtime);
   var mapperField=session.getClass().getDeclaredField("fieldMapper");mapperField.setAccessible(true);Object mapper=mapperField.get(session);
   var mappingField=mapper.getClass().getDeclaredField("byId");mappingField.setAccessible(true);
   @SuppressWarnings("unchecked") Map<Object,Object> original=(Map<Object,Object>)mappingField.get(mapper);
   Map<Object,Object> unavailable=new AbstractMap<>() {
    private void available(){if(broken.get()){mapFailures.incrementAndGet();throw new NoClassDefFoundError("shared mapping dependency is temporarily unavailable");}}
    @Override public Object get(Object key){available();return original.get(key);}
    @Override public Set<Entry<Object,Object>> entrySet(){available();return original.entrySet();}
   };
   mappingField.set(mapper,unavailable);
   h.writer=mock(IndexingCoordinator.class,call->{try{return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException e){if(call.getMethod().getName().equals("indexSingle"))writeFailures.add(e.getCause());throw e.getCause();}});
   h.commitOps=runtime.commitOps();h.loop=PipelineTestFixture.withExtractor(h,box);
   h.enqueue("warmup.txt");h.loop.start();h.awaitAllState("DONE",12);
   broken.set(true);h.enqueue("mapping-a.txt","mapping-b.txt");
   long until=System.nanoTime()+6_000_000_000L;
   while(writeFailures.size()<2&&System.nanoTime()<until)Thread.sleep(20);
   assertTrue(writeFailures.size()>=2,"both ordinary documents must reach the same real mapper");
   Thread.sleep(1500);
   assertTrue(mapFailures.get()>=3,"two real writes and their full canary must hit the shared dependency");
   var during=h.rows().stream().filter(r->!r.path().endsWith("warmup.txt")).toList();
   var service=new WorkerIngestService(h.queue,h.loop,mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),temp,temp.resolve("index"),runtime,runtime,null,0L);
   var state=service.indexStatus(StatusRequest.getDefaultInstance(),CallContext.none());
   System.out.println("OWN mapping linkage during="+during+" state="+state.getCore().getState()+" nextRetry="+state.getFailure().getNextRetryAtMs()+" mapFailures="+mapFailures+" writeFailures="+writeFailures+" writerOpen="+LiveWriterLockHealthTest.writer(runtime).isOpen()+" tragedy="+LiveWriterLockHealthTest.writer(runtime).getTragicException());
   // Repair just the shared mapping dependency; leave the same loop, source bytes and claims.
   broken.set(false);runtime.indexingCoordinator().probeIngestion();long recoveredAt=System.nanoTime();boolean recovered=false;
   try{h.awaitAllState("DONE",75);recovered=true;}catch(AssertionError failure){System.out.println("OWN mapping linkage recovery failure: "+failure);}
   var after=h.rows().stream().filter(r->!r.path().endsWith("warmup.txt")).toList();
   System.out.println("OWN mapping linkage repaired recoveryMs="+(System.nanoTime()-recoveredAt)/1_000_000+" before stopping rows="+after);
   h.stopLoopForOtherAssertions();mappingField.set(mapper,original);boolean done=recovered;
   assertAll(
    ()->assertTrue(during.stream().noneMatch(r->r.state().equals("PROCESSING")),"a failed health proof must finalize and return claims; shared errors must not strand PROCESSING rows"),
    ()->assertTrue(during.stream().allMatch(r -> r.state().equals("PENDING") && r.attempts()==1 && "RETRY_WITH_BACKOFF".equals(r.policy())), "an escaping health-check Error retains legacy per-file handling: "+during),()->assertEquals("WAITING",state.getCore().getState()),
    ()->assertTrue(done,"non-tragic shared dependency repair must recover the same queued work in process"),
    ()->assertTrue(after.stream().allMatch(r->r.attempts()==1)));
  }finally{broken.set(false);h.close();}
 }
 @Test void vmProbeFailureStillReturnsClaimsAndPropagates() throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  var fatal=new StackOverflowError("simulated fatal probe");
  try {
   doThrow(new IllegalStateException("document write fails")).when(h.writer).indexSingle(any());
   doThrow(fatal).when(h.writer).probeIngestion();
   h.loop=h.newLoop();h.enqueue("fatal-probe.txt");h.manual();
   var thrown=assertThrows(java.lang.reflect.InvocationTargetException.class,h::process);
   var during=h.rows();
   System.out.println("VM probe finalization rows="+during+" cause="+thrown.getCause());
   assertAll(()->assertSame(fatal,thrown.getCause(),"VM failures must propagate"),
    ()->assertTrue(during.stream().noneMatch(row->row.state().equals("PROCESSING")),"classification must sit inside the claim finalization guard"),
    ()->h.assertFree(during));
  } finally {h.close();}
 }
}
