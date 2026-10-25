/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.ipc.StatusRequest;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class VerifierGeneralCasesTest {
 @TempDir Path temp;
 @Test @Timeout(65)
 void unknownSharedProtocolFailureUsesCanaryAndSummarizesLogs() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var rootLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
  appender.start(); rootLogger.addAppender(appender);
  try(var owners=new TestEngineExecutors()) {
   var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
       "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),
       "io.justsearch.indexerworker.extract.VerifierProtocolChild",temp.toString());
   try(var sandbox=new PersistentExtractionSandbox(PipelineTestFixture.registration(owners,"protocol-readers",2),command,
       TikaExtractionPolicy.defaults(),OcrRoutingConfig.disabled(),Duration.ofSeconds(4),1,500,null);
       var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"protocol-timebox",1),sandbox,Duration.ofSeconds(8),null)) {
    var sources=h.enqueue("healthy-protocol-a.txt","healthy-protocol-b.txt");
    // Prove exact pool and source work before damaging only shared service state.
    assertTrue(timebox.extractArtifact(sources.getFirst()).result().content().contains("healthy"));
    Files.writeString(temp.resolve("broken-protocol"),"bad shared configuration");
    h.loop=PipelineTestFixture.withExtractor(h,timebox);
    var service=new WorkerIngestService(h.queue,h.loop,org.mockito.Mockito.mock(io.justsearch.indexerworker.coordination.WorkerSignalBus.class),IndexingPacing.unthrottled(),temp,temp.resolve("index"),null,null,null,0L);
    appender.list.clear();
    h.loop.start();
    long end=System.nanoTime()+35_000_000_000L;
    boolean sawCanary=false; int waitingSamples=0; long previous=0;
    var deadlines=new ArrayList<Long>();
    while(System.nanoTime()<end) {
     var rows=h.rows(); long now=System.currentTimeMillis();
     if(rows.stream().allMatch(r->r.policy()!=null && r.state().equals("PENDING"))) {
      long next=rows.getFirst().retry();
      assertTrue(next>=previous,"deadlines must only advance");
      if(next!=previous) { deadlines.add(next); previous=next; }
      assertTrue(next<=now+8_100,"at most eight seconds ahead");
      if(next>now+150) {
       var status=service.indexStatus(StatusRequest.getDefaultInstance(),CallContext.none());
       if(status.getMigration().getProcessingJobsCount()==0) {
        assertEquals("WAITING",status.getCore().getState());
        assertTrue(status.getFailure().getNextRetryAtMs()>now);
        waitingSamples++;
       }
      }
     }
     if(Files.exists(temp.resolve("protocol-events.tsv"))) {
      sawCanary=Files.readAllLines(temp.resolve("protocol-events.tsv")).stream().anyMatch(e->e.contains("justsearch-extraction-canary-"));
     }
     if(sawCanary && rows.stream().allMatch(r->r.state().equals("PENDING")) && deadlines.size()>=4) break;
     if(rows.stream().anyMatch(r->r.attempts()>0)) break;
     Thread.sleep(25);
    }
    var during=h.rows();
    var events=Files.readAllLines(temp.resolve("protocol-events.tsv"));
    var outageLogs=new ArrayList<>(appender.list);
    System.out.println("O8 real protocol outage rows="+during+" deadlines="+deadlines+" waitingSamples="+waitingSamples+" canary="+sawCanary+" events="+events);
    for(var event:outageLogs) if(event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) System.out.println("O8 log "+event.getLevel()+" "+event.getFormattedMessage());
    // Recover before asserting to collect recovery evidence even when the oracle rejects rows/logs.
    Files.delete(temp.resolve("broken-protocol"));
    long recoveryStart=System.nanoTime();
    if(during.stream().noneMatch(r->r.attempts()>0)) h.awaitAllState("DONE",15);
    h.stopLoopForOtherAssertions();
    System.out.println("O8 restored actual protocol recovery ms="+(System.nanoTime()-recoveryStart)/1_000_000+" rows="+h.rows());
    long recycleLogs=outageLogs.stream().filter(e->e.getFormattedMessage().contains("Extraction sandbox child recycled")).count();
    long spawnLogs=outageLogs.stream().filter(e->e.getFormattedMessage().contains("Extraction sandbox child spawned")).count();
    boolean observedCanary=sawCanary; int samples=waitingSamples;
    assertAll(
      ()->h.assertFree(during),
      ()->assertTrue(observedCanary,"unknown shared fault must be diagnosed by actual canary after grace"),
      ()->assertTrue(samples>0,"observe real public WAITING status"),
      ()->assertTrue(recycleLogs<=1 && spawnLogs<=1,"I2/O8 summarized per-cause logs, recycled="+recycleLogs+", spawned="+spawnLogs),
      ()->assertTrue(h.rows().stream().allMatch(r->r.state().equals("DONE") && r.attempts()==0),"prompt zero-attempt recovery"));
   }
  } finally { rootLogger.detachAppender(appender); appender.stop(); h.close(); }
 }

 @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
 @ParameterizedTest(name="documentPathProvenance({0})")
 @ValueSource(strings={"symlink-canonical-source","source-companion-under-temp"})
 @Timeout(40)
 void actualPerDocumentFilesystemFailureMustNotBecomeOwnedStorageOutage(String mode) throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  Path realDirectory=Files.createDirectory(temp.resolve("real-source-dir"));
  Files.writeString(realDirectory.resolve("a-bad.txt"),"healthy readable source content");
  // The final entry is a regular file; its parent is a symlink, so source admission and
  // extraction both run while the parser still resolves a different canonical source path.
  Path bad=mode.equals("symlink-canonical-source")?Files.createSymbolicLink(temp.resolve("source-alias"),realDirectory).resolve("a-bad.txt"):Files.writeString(temp.resolve("a-bad.txt"),"healthy readable source content");
  Path peer=Files.writeString(temp.resolve("b-healthy.txt"),"healthy readable source content");
  var failures=new java.util.concurrent.CopyOnWriteArrayList<IOException>();
  var canaryCount=new AtomicInteger();
  try(var owners=new TestEngineExecutors(); var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
   ContentExtractorProvider provider=new ContentExtractorProvider() {
    @Override public ContentExtractor.ExtractionResult extract(Path file) throws IOException,ContentExtractor.ExtractionException {
     if(file.getFileName().toString().startsWith("justsearch-extraction-canary-")) canaryCount.incrementAndGet();
     if(file.equals(bad)) {
      if(mode.equals("symlink-canonical-source")) {
       // A parser resolves its symlinked input before opening it. Only this source becomes
       // unreadable during that call; actual AccessDeniedException names its canonical path.
       Path canonical=file.toRealPath(); var perms=Files.getPosixFilePermissions(canonical);
       try {
        Files.setPosixFilePermissions(canonical,Set.of());
        try { Files.readString(canonical); fail("source must really refuse read"); }
        catch(IOException actual) { failures.add(actual); throw actual; }
       } finally { Files.setPosixFilePermissions(canonical,perms); }
      } else {
       // A source-specific referenced component/sidecar is absent, in the source's own parent.
       // The parser and scratch storage are healthy, confirmed by the real peer and canary.
       try { Files.readString(file.resolveSibling("missing-for-this-document.txt")); fail("missing companion must refuse read"); }
       catch(IOException actual) { failures.add(actual); throw actual; }
      }
     }
     return parser.extractArtifact(file).result();
    }
    @Override public String detectMimeType(Path file) { return parser.detectMimeType(file); }
   };
   // The exact source is healthy with the production provider before the per-file access fault.
   assertTrue(parser.extractArtifact(bad).result().content().contains("healthy"));
   try(var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"source-provenance",1),provider,Duration.ofSeconds(8),null)) {
    h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(bad).withinRoot(temp),JobQueue.EnqueueEntry.stat(peer).withinRoot(temp)));
    h.loop=PipelineTestFixture.withExtractor(h,timebox); h.loop.start(); h.awaitPolicy(10); h.awaitPathState("b-healthy.txt","DONE",10);
    // Observe beyond the three ambiguous batches without shortening persisted deadlines.
    long end=System.nanoTime()+10_000_000_000L;
    while(System.nanoTime()<end && h.rows().stream().filter(r->r.path().endsWith("a-bad.txt")).allMatch(r->r.attempts()==0)) Thread.sleep(25);
    h.stopLoopForOtherAssertions();
    var row=h.rows().stream().filter(r->r.path().endsWith("a-bad.txt")).findFirst().orElseThrow();
    System.out.println("K2/K3 real document failure mode="+mode+" failures="+failures+" canaries="+canaryCount+" rows="+h.rows());
    assertFalse(failures.isEmpty(),"must exercise actual filesystem IO");
    assertTrue(Files.isReadable(bad) && Files.isReadable(peer));
    assertEquals(1,row.attempts(),"a real per-source IO failure must be charged even under the engine temp root");
    assertNotEquals("DEFER_WITHOUT_ATTEMPT",row.policy());
    assertTrue(row.retry()>System.currentTimeMillis()+45_000,"file retry ladder is minute-scale");
   }
  } finally { h.close(); }
 }
}
