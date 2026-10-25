/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.indexerworker.queue.*;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.ipc.StatusRequest;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** The real queue's released-admission query fails; error classification must not call it again and abandon outcomes. */
final class AdmissionLookupLivenessTest {
 @TempDir Path temp;
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"admission-query","presence-lookup","io-presence-lookup"})
 @Timeout(30) void unavailableAdmissionReadPersistsRetryAndDoesNotStarveHealthyPeer(String mode) throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  Path missing=Files.writeString(temp.resolve("a-missing.txt"),"original explicit source");
  Path healthy=Files.writeString(temp.resolve("z-healthy.txt"),"ordinary healthy peer");
  h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(missing).withinRoot(missing),JobQueue.EnqueueEntry.stat(healthy).withinRoot(temp)));
  h.sql("UPDATE jobs SET unit_revision='release030/verifier-original' WHERE path LIKE '%a-missing.txt'");
  h.queue.prepareRelease030ExplicitFiles();assertTrue(h.queue.isRelease030ExactFile(missing));if(!mode.equals("io-presence-lookup"))Files.delete(missing);
  var cf=SqliteJobQueue.class.getDeclaredField("connection");cf.setAccessible(true);var original=(Connection)cf.get(h.queue);
  var broken=new AtomicBoolean(true);var reads=new AtomicInteger();
  var proxy=java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(object,method,args)->{
   if(mode.equals("admission-query") && broken.get() && method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith("SELECT 1 FROM jobs WHERE path=? AND ingestion_root=path")){
    reads.incrementAndGet();throw new SQLException("Admission identity read is unavailable");
   }
   try{return method.invoke(original,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });cf.set(h.queue,proxy);
  var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
  try{
   h.loop=h.newLoop();
   if(mode.equals("io-presence-lookup")) {
    var field=IndexingLoop.class.getDeclaredField("contentExtractor");field.setAccessible(true);
    var content=(io.justsearch.indexerworker.extract.TimeboxedContentExtractor)field.get(h.loop);
    doAnswer(call->{
     Path input=call.getArgument(0);
     if(input.equals(missing) && broken.get()) {
      Files.deleteIfExists(missing);throw new java.io.IOException("source vanished during parsing");
     }
     return io.justsearch.indexerworker.extract.ExtractionArtifact.full(
      new io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult(h.extractedText,null,"text/plain"),
      io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(),"verifier",false);
    }).when(content).extractArtifact(any(Path.class));
   }
   h.loop.useRootCheck(new SourcePresence.RootCheck(){
    public boolean isRelease030ExactFile(Path p){
     if((mode.equals("presence-lookup") || mode.equals("io-presence-lookup")) && broken.get() && p.equals(missing) && !Files.exists(missing)) {
      reads.incrementAndGet();throw new IllegalStateException("presence lookup unavailable");
     }
     return h.queue.isRelease030ExactFile(p);
    }
    public boolean vouches(Path p){return Files.isDirectory(p);}
    public boolean admits(Path p){return Files.isDirectory(p);}
   });
   var service=new WorkerIngestService(h.queue,h.loop,mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),temp,temp.resolve("index"),null,null,null,0L);
   h.loop.start();long end=System.nanoTime()+3_500_000_000L;
   while(System.nanoTime()<end){
    var r=h.rows();if(r.stream().anyMatch(x->x.path().endsWith("a-missing.txt") && x.retry()>0) && r.stream().anyMatch(x->x.path().endsWith("z-healthy.txt") && x.state().equals("DONE")))break;
    Thread.sleep(25);
   }
   var during=h.rows();long observedAt=System.currentTimeMillis();int failedReads=reads.get();var status=service.indexStatus(StatusRequest.getDefaultInstance(),CallContext.none());
   long errors=new ArrayList<>(logs.list).stream().filter(x->x.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)).count();
   System.out.println("OWN admission fault mode="+mode+" rows="+during+" queryFailures="+failedReads+" errorEvents="+errors+" state="+status.getCore().getState()+" ready="+status.getMigration().getPendingReadyJobsCount()+" processing="+status.getMigration().getProcessingJobsCount()+" nextRetry="+status.getFailure().getNextRetryAtMs());
   var bad=during.stream().filter(x->x.path().endsWith("a-missing.txt")).findFirst().orElseThrow();
   var peer=during.stream().filter(x->x.path().endsWith("z-healthy.txt")).findFirst().orElseThrow();
   broken.set(false);boolean debtRetained=h.queue.isRelease030ExactFile(missing);
   Files.writeString(missing,"original explicit source restored");
   var restored=h.rows().stream().filter(x->x.path().equals(bad.path())).findFirst().orElseThrow();
   assertEquals(bad.retry(),restored.retry(),"repair alone must preserve the saved retry deadline");
   // Queue retry tests own the minute-scale wait; expire only this test's original row.
   h.sql("UPDATE jobs SET retry_after=0 WHERE path='"+bad.path().replace("'","''")+"'");
   long repairedAt=System.nanoTime();h.awaitPathState("z-healthy.txt","DONE",8);
   h.awaitPathState("a-missing.txt","DONE",8);h.stopLoopForOtherAssertions();
   System.out.println("OWN admission repaired healthy recoveryMs="+(System.nanoTime()-repairedAt)/1_000_000+" rows="+h.rows());
   assertTrue(failedReads>0);
   assertAll(
    ()->assertEquals("PENDING",bad.state()),
    ()->assertEquals(1,bad.attempts(),"lookup uncertainty retains baseline per-file handling"),
    ()->assertEquals(mode.equals("io-presence-lookup")?"RETRY_UNTIL_AVAILABLE":"RETRY_WITH_BACKOFF",bad.policy()),
    ()->assertEquals(0,peer.attempts()),
    ()->assertEquals("WAITING",status.getCore().getState()),
    ()->assertEquals(0,status.getMigration().getPendingReadyJobsCount()),
    ()->assertEquals(0,status.getMigration().getProcessingJobsCount()),
    ()->assertTrue(status.getFailure().getNextRetryAtMs()>observedAt),
    ()->assertTrue(failedReads<=2,"no repeated failing admission loop: "+failedReads),
    ()->assertTrue(errors<=1,"no per-second error flood: "+errors),
    ()->assertTrue(debtRetained,"uncertain legacy debt must be retained"),
    ()->verify(h.writer,never()).deleteByIdAndChunks(anyString()),
    ()->assertTrue(bad.retry()>0,"an admission read failure must persist a retry outcome"),()->assertEquals("DONE",peer.state(),"one failed admission cannot repeatedly abort a batch and starve a healthy peer"));
  }finally{broken.set(false);cf.set(h.queue,original);logger.detachAppender(logs);logs.stop();h.close();}
 }
}
