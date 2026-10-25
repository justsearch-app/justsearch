/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** I1: a poison-only batch alternates with healthy batches; healthy progress must not grant infinite grace. */
final class VerifierInterleavedPoisonTest {
 @TempDir Path temp;
 @Test @Timeout(70)
 void singletonPoisonCannotRenewItsFreeGraceByInterleavingHealthyBatches() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var poisonCalls=new AtomicInteger(); var healthyWrites=new AtomicInteger(); var probes=new AtomicInteger();
  var failures=new CopyOnWriteArrayList<Throwable>();
  try(var owners=new TestEngineExecutors()) {
   var cmd=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
      "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),
      "io.justsearch.indexerworker.extract.VerifierParserChild");
   try(var pool=new PersistentExtractionSandbox(PipelineTestFixture.registration(owners,"interleaved-readers",2),cmd,
      TikaExtractionPolicy.defaults(),OcrRoutingConfig.disabled(),Duration.ofSeconds(3),1,500,null)) {
    Path bad=h.enqueue("z-poison.txt").getFirst(); Files.writeString(bad,"CRASH");
    h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(bad).withinRoot(temp)));
    ExtractionSandbox arrivingFiles=new ExtractionSandbox() {
     @Override public ExtractionArtifact extract(Path source) throws IOException,ContentExtractor.ExtractionException {
      if(source.getFileName().toString().startsWith("justsearch-extraction-canary-")) probes.incrementAndGet();
      try { return pool.extract(source); }
      catch(IOException|ContentExtractor.ExtractionException error) {
       if(source.equals(bad)) {
        int round=poisonCalls.incrementAndGet(); failures.add(error);
        try { h.enqueue("a-steady-"+round+".txt"); }
        catch(Exception setup) { throw new IOException("failed to admit the next real healthy arrival",setup); }
       }
       throw error;
      }
     }
    };
    try(var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"interleaved-timebox",1),arrivingFiles,Duration.ofSeconds(8),null)) {
     doAnswer(call->{healthyWrites.incrementAndGet();return null;}).when(h.writer).indexSingle(any());
     var builder=new ResolvedConfigBuilder(); builder.putDefault("justsearch.backfill.poll_batch_size","1");
     var config=builder.build(); assertEquals(1,config.ai().backfillPacing().pollBatchSize());
     h.loop=new IndexingLoop(null,null,h.queue,h.writer,h.commitOps,mock(DocumentFieldOps.class),mock(IndexCountOps.class),
       ()->config,mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),null,null,null,null,timebox,null,
       new IndexingLoopOptions(false,null,h.store,null,null,null));
     h.loop.start();
     for(int round=1;round<=6;round++) {
      long end=System.nanoTime()+10_000_000_000L;
      while(System.nanoTime()<end && (poisonCalls.get()<round || healthyWrites.get()<round)) Thread.sleep(25);
      assertTrue(poisonCalls.get()>=round && healthyWrites.get()>=round,"real healthy and poison batches must both progress; round="+round+" calls="+poisonCalls+" healthy="+healthyWrites+" rows="+h.rows());
      System.out.println("I1 interleaved round="+round+" poison calls="+poisonCalls+" healthy writes="+healthyWrites+" probes="+probes+" rows="+h.rows());
      if(round<6) h.sql("UPDATE jobs SET retry_after=0 WHERE path LIKE '%z-poison.txt' AND state='PENDING'");
     }
     var observed=h.rows().stream().filter(r->r.path().endsWith("z-poison.txt")).findFirst().orElseThrow();
     if(observed.attempts()>0) {
      h.sql("UPDATE jobs SET first_failed_at="+(System.currentTimeMillis()-8L*86400000)+",retry_after=0 WHERE path LIKE '%z-poison.txt' AND state='PENDING'");
      h.awaitPathState("z-poison.txt","RETRY_EXHAUSTED",12);
      long end=System.nanoTime()+5_000_000_000L;
      while(System.nanoTime()<end && healthyWrites.get()<poisonCalls.get()) Thread.sleep(25);
     }
     h.stopLoopForOtherAssertions();
     System.out.println("I1 REAL INTERLEAVED POISON observed="+observed+" poison failures="+failures.size()+" healthy writes="+healthyWrites+" canaries="+probes+" final="+h.rows()+" child events="+Files.readAllLines(temp.resolve("parser-events.tsv")));
     assertTrue(failures.stream().allMatch(t->t.getClass().getSimpleName().equals("SandboxExtractionException")),"must be real document crashes, without an unavailable-type signal");
     assertTrue(observed.attempts()>0,"six poison-only failures interleaved with healthy batches must not remain uncharged forever");
     assertEquals("RETRY_EXHAUSTED",h.rows().stream().filter(r->r.path().endsWith("z-poison.txt")).findFirst().orElseThrow().state());
    }
   }
  } finally { h.close(); }
 }
}
