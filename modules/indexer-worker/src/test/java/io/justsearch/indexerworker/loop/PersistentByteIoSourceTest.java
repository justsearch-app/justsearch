/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
/** New independent cases: java.io at parent and disappeared/retargeted source boundaries,
 * plus byte-readable sources under identical shared writer failure (negative control). */
final class PersistentByteIoSourceTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @ParameterizedTest(name="own-javaio-{0}")
 @ValueSource(strings={"persistent-byte-eio","captured-byte-eio","readable","readable-empty","submitted-fifo","captured-fifo","submitted-regular","captured-regular"})
 @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
 @Timeout(35) void sourcePolicyDependsOnActualAccessAndPreservesHealthyOutage(String mode) throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  Path originalParent=Files.createDirectory(temp.resolve("original"));
  var parentPermissions=Files.getPosixFilePermissions(originalParent);
  Path source=Files.writeString(originalParent.resolve("mem"),"ownjavaioverifier");
  Path link=Files.createSymbolicLink(temp.resolve("a-link"),originalParent);
  Path submitted=link.resolve("mem");
  Path healthy=Files.writeString(temp.resolve("z-healthy.txt"),"ordinary peer");
  boolean fifo=mode.endsWith("fifo");
  boolean captured=mode.startsWith("captured-");
  boolean readable=mode.startsWith("readable") || mode.endsWith("regular");
  var fifoReady=new java.util.concurrent.CountDownLatch(1);
  var finished=new java.util.concurrent.CountDownLatch(1);
  var rescued=new AtomicBoolean();
  var rescueErrors=new CopyOnWriteArrayList<Throwable>();
  Path fifoPath=captured?source:temp.resolve("fifo-target/mem");
  Thread rescue=new Thread(()->{
   try {
    while(!fifoReady.await(50,java.util.concurrent.TimeUnit.MILLISECONDS))if(finished.getCount()==0)return;
    if(!finished.await(2,java.util.concurrent.TimeUnit.SECONDS)) {
     rescued.set(true);
     // Linux O_RDWR opens our FIFO without a peer; one byte releases native open/read.
     try(var channel=java.nio.channels.FileChannel.open(fifoPath,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
      channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
      finished.await(15,java.util.concurrent.TimeUnit.SECONDS);
     }
    }
   }catch(Exception failure){rescueErrors.add(failure);}
  },"owned-source-fifo-rescue");
  if(fifo)rescue.start();
  var sharedFailure=new AtomicBoolean(true);
  var fault=new AtomicBoolean();var errors=new CopyOnWriteArrayList<Throwable>();var actualWrites=new CopyOnWriteArrayList<Throwable>();
  Analyzer analyzer=new Analyzer() {
   protected TokenStreamComponents createComponents(String field) {
    var tokenizer=new StandardTokenizer();
    return new TokenStreamComponents(tokenizer,new TokenFilter(tokenizer) {
     final CharTermAttribute term=addAttribute(CharTermAttribute.class);
     public boolean incrementToken() throws java.io.IOException {
      if(!input.incrementToken())return false;
      if(term.toString().contains("ownjavaioverifier")) {
       if(fault.compareAndSet(false,true)) {
       if(mode.equals("persistent-byte-eio")){Files.delete(link);Files.createSymbolicLink(link,Path.of("/proc/self"));assertTrue(Files.isReadable(submitted));assertTrue(Files.isRegularFile(submitted));}
       if(mode.equals("captured-byte-eio")) {
        Path replacement=Files.createDirectory(temp.resolve("replacement"));
        Files.writeString(replacement.resolve("mem"),"ownjavaioverifier readable replacement");
        Files.delete(link);Files.createSymbolicLink(link,replacement);
        Files.delete(source);Files.createSymbolicLink(source,Path.of("/proc/self/mem"));
        assertTrue(Files.isReadable(submitted));assertTrue(Files.isReadable(source));
       }
       if(mode.equals("readable-empty"))Files.writeString(source,"");
       if(mode.endsWith("regular") || fifo) {
        Path replacement=Files.createDirectory(temp.resolve("fifo-target"));
        if(captured) {
         Files.writeString(replacement.resolve("mem"),"readable submitted replacement");
         Files.delete(link);Files.createSymbolicLink(link,replacement);
         Files.delete(source);
         if(!fifo)Files.writeString(source,"readable captured replacement");
        }else {
         Files.delete(link);Files.createSymbolicLink(link,replacement);
         if(!fifo)Files.writeString(replacement.resolve("mem"),"readable submitted replacement");
        }
        if(fifo) {
         var mkfifo=new ProcessBuilder("mkfifo",fifoPath.toString()).start();
         try {assertTrue(mkfifo.waitFor(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,mkfifo.exitValue());}
         catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("FIFO setup interrupted",interrupted);}
         finally {if(mkfifo.isAlive())mkfifo.destroyForcibly();}
         assertFalse(Files.isRegularFile(fifoPath));
         fifoReady.countDown();
         throw new java.io.IOException("writer failed after regular source became a FIFO");
        }
       }
       }
       Path opened=mode.equals("captured-byte-eio")?source:submitted;
       try(var input=new java.io.FileInputStream(opened.toFile())) {
        int first=input.read();assertTrue(first>=0 || mode.equals("readable-empty"));assertTrue(readable,"an actually unreadable source must refuse java.io read");
       }catch(java.io.IOException failure) {
        errors.add(failure);throw failure;
       }
      }
      if(fault.get() && sharedFailure.get())throw new java.io.IOException("untyped shared writer failure for all document and canary content");
      return true;
     }
    });
   }
  };
  var schema=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
  var field=schema.analyzerRegistry().getClass().getDeclaredField("analyzersById");field.setAccessible(true);
  @SuppressWarnings("unchecked") var cached=(Map<String,Analyzer>)field.get(schema.analyzerRegistry());
  for(String id:schema.analyzerRegistry().analyzerIds())cached.put(id,analyzer);
  try(var runtime=schema.atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
      var executors=new TestEngineExecutors();
      var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var extractor=new TimeboxedContentExtractor(PipelineTestFixture.registration(executors,"own-javaio",1),parser,Duration.ofSeconds(5),null)) {
   assertTrue(parser.extractArtifact(submitted).result().content().contains("ownjavaioverifier"));
   h.writer=mock(IndexingCoordinator.class,call->{
    try{return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException e){if(call.getMethod().getName().equals("indexSingle"))actualWrites.add(e.getCause());throw e.getCause();}
   });h.commitOps=runtime.commitOps();
   h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(submitted).withinRoot(temp),JobQueue.EnqueueEntry.stat(healthy).withinRoot(temp)));
   h.loop=PipelineTestFixture.withExtractor(h,extractor);h.loop.start();h.awaitPolicy(12);if(readable || fifo)h.stopLoopForOtherAssertions();finished.countDown();if(fifo)rescue.join(3_000);
   var rows=h.rows();var own=rows.stream().filter(r->r.path().contains("a-link")).findFirst().orElseThrow();
   var peer=rows.stream().filter(r->r.path().endsWith("z-healthy.txt")).findFirst().orElseThrow();
   System.out.println("OWN mode="+mode+" sourceReadable="+Files.isReadable(source)+" submittedReadable="+Files.isReadable(submitted)+" javaIoFailures="+errors+" realWriteFailures="+actualWrites+" rows="+rows);
   assertTrue(actualWrites.size()>=2,"the ordinary peer must actually reach the shared broken analyzer");
   if(readable) {
    assertTrue(errors.isEmpty());assertTrue(Files.isReadable(source));assertTrue(Files.isReadable(submitted));
    if(mode.equals("readable-empty"))assertEquals(0,Files.size(source));
    assertEquals(0,own.attempts(),"readable document under a shared outage must stay free");
    assertEquals("DEFER_WITHOUT_ATTEMPT",own.policy());
   }else if(fifo) {
    assertAll(()->assertFalse(rescued.get(),"failure classification blocked until the owned FIFO rescue supplied a writer"),
     ()->assertEquals(1,own.attempts(),"a replaced nonregular source uses a per-file retry"),
     ()->assertEquals("RETRY_WITH_BACKOFF",own.policy()));
   }else {
    assertEquals(1,errors.size());assertInstanceOf(java.io.IOException.class,errors.getFirst());
    assertTrue(Files.isReadable(source));assertTrue(Files.isReadable(submitted));
    var still=assertThrows(java.io.IOException.class,()->{try(var stream=new java.io.FileInputStream((mode.equals("captured-byte-eio")?source:submitted).toFile())){stream.read();}});
    System.out.println("OWN persistent byte read still fails after classification: "+still);
    assertEquals(0,own.attempts(),"byte-only EIO with readable metadata defers during a shared outage");
    assertEquals("DEFER_WITHOUT_ATTEMPT",own.policy());
   }
   assertEquals(0,peer.attempts(),"a genuinely unreadable source does not charge its healthy peer");
   assertEquals("DEFER_WITHOUT_ATTEMPT",peer.policy());
   if(!readable && !fifo) {
    sharedFailure.set(false);
    var restored=h.rows().stream().filter(r->r.path().equals(own.path())).findFirst().orElseThrow();
    assertTrue(restored.retry()>=own.retry(),"repair observation permits only forward movement of the observed saved deadline");
    assertThrows(java.io.IOException.class,()->{try(var stream=new java.io.FileInputStream((mode.equals("captured-byte-eio")?source:submitted).toFile())){stream.read();}});
    // Expire only this still-uncounted outage observation; zero matches leave live recovery untouched.
    h.sql("UPDATE jobs SET retry_after=0 WHERE path='"+restored.path().replace("'","''")
     +"' AND retry_after="+restored.retry()+" AND attempts=0 AND last_retry_policy='DEFER_WITHOUT_ATTEMPT' AND state='PENDING'");
    h.awaitPathState("z-healthy.txt","DONE",8);
    long end=System.nanoTime()+8_000_000_000L;
    while(System.nanoTime()<end && h.rows().stream().noneMatch(r->r.path().equals(own.path()) && r.attempts()>0))Thread.sleep(20);
    h.stopLoopForOtherAssertions();
    var recovered=h.rows().stream().filter(r->r.path().equals(own.path())).findFirst().orElseThrow();
    System.out.println("OWN shared repair with persistent byte EIO mode="+mode+" rows="+h.rows());
    assertEquals("PENDING",recovered.state());assertEquals(1,recovered.attempts(),"after shared repair the remaining source fault uses the existing per-file policy");
    assertEquals("RETRY_WITH_BACKOFF",recovered.policy());assertTrue(recovered.retry()>0);
    var completed=h.rows().stream().filter(r->r.path().equals(peer.path())).findFirst().orElseThrow();
    assertEquals("DONE",completed.state());assertEquals(0,completed.attempts());
    assertThrows(java.io.IOException.class,()->{try(var stream=new java.io.FileInputStream((mode.equals("captured-byte-eio")?source:submitted).toFile())){stream.read();}});
   }
  }finally{
   finished.countDown();
   if(fifo){rescue.join(3_000);assertFalse(rescue.isAlive(),"owned rescue must terminate");assertTrue(rescueErrors.isEmpty(),rescueErrors.toString());}
   if(Files.exists(originalParent))Files.setPosixFilePermissions(originalParent,parentPermissions);
   h.close();analyzer.close();
  }
 }
}
