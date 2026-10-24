/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** O8: JVM creation succeeds but shared child bootstrap storage is absent; no injected exception. */
final class VerifierMissingChildSupportTest {
 @TempDir Path temp;
 @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
 @Test @Timeout(85)
 void realMissingChildJvmSupportJarIsCanaryDiagnosedAndRecoveredWithoutAttempts() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  var manifest=new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version","1.0");
  manifest.getMainAttributes().putValue("Premain-Class","io.justsearch.indexerworker.extract.VerifierNoOpAgent");
  byte[] jarBytes;
  try(var bytes=new ByteArrayOutputStream(); var jar=new JarOutputStream(bytes,manifest)) {
   String name="io/justsearch/indexerworker/extract/VerifierNoOpAgent.class";
   jar.putNextEntry(new JarEntry(name));
   try(var resource=getClass().getClassLoader().getResourceAsStream(name)) { assertNotNull(resource); resource.transferTo(jar); }
   jar.closeEntry(); jar.finish(); jarBytes=bytes.toByteArray();
  }
  Path support=temp.resolve("child-support.jar"); Files.write(support,jarBytes);
  var probes=new AtomicInteger(); var failures=new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
  var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start(); logger.addAppender(appender);
  try(var owners=new TestEngineExecutors()) {
   var cmd=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-javaagent:"+support,
     "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),ExtractionSandboxChild.class.getName());
   try(var pool=new PersistentExtractionSandbox(PipelineTestFixture.registration(owners,"bootstrap-readers",2),cmd,
      TikaExtractionPolicy.defaults(),OcrRoutingConfig.disabled(),Duration.ofSeconds(10),1,1,null)) {
    ExtractionSandbox measured=new ExtractionSandbox() {
     @Override public ExtractionArtifact extract(Path path) throws IOException,ContentExtractor.ExtractionException {
      if(path.getFileName().toString().startsWith("justsearch-extraction-canary-")) probes.incrementAndGet();
      try { return pool.extract(path); }
      catch(IOException|ContentExtractor.ExtractionException|RuntimeException e) { failures.add(e); throw e; }
     }
    };
    try(var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"bootstrap-timebox",1),measured,Duration.ofSeconds(15),null)) {
     var files=h.enqueue("healthy-bootstrap-a.txt","healthy-bootstrap-b.txt");
     assertTrue(timebox.extractArtifact(files.getFirst()).result().content().contains("healthy"));
     Files.delete(support); appender.list.clear();
     h.loop=PipelineTestFixture.withExtractor(h,timebox); h.loop.start(); h.awaitPolicy(12);
     long end=System.nanoTime()+30_000_000_000L;
     while(System.nanoTime()<end && probes.get()==0 && h.rows().stream().allMatch(r->r.attempts()==0)) Thread.sleep(25);
     // Allow the same canary call to publish the durable deferral before observing it.
     end=System.nanoTime()+5_000_000_000L;
     while(System.nanoTime()<end && h.rows().stream().anyMatch(r->r.state().equals("PROCESSING"))) Thread.sleep(25);
     var outage=h.rows(); var logs=new ArrayList<>(appender.list);
     System.out.println("O8 MISSING REAL CHILD JVM AGENT rows="+outage+" canaries="+probes+" real exceptions="+failures);
     for(Throwable t:failures) t.printStackTrace(System.out);
     long recycled=logs.stream().filter(e->e.getFormattedMessage().contains("Extraction sandbox child recycled")).count();
     System.out.println("O8 real child support outage lifecycle recycle lines="+recycled);
     Files.write(support,jarBytes); long start=System.nanoTime();
     if(outage.stream().allMatch(r->r.attempts()==0)) h.awaitAllState("DONE",25);
     h.stopLoopForOtherAssertions();
     System.out.println("O8 actual support restored recovery ms="+(System.nanoTime()-start)/1_000_000+" final="+h.rows());
     assertAll(
      ()->h.assertFree(outage),
      ()->assertTrue(probes.get()>0,"the real shared child failure must run the built-in canary"),
      ()->assertTrue(failures.stream().allMatch(t->t instanceof SandboxExtractionException && !t.getClass().getSimpleName().equals("SandboxServiceUnavailableException")),"real untaged child failure must reach the canary rule"),
      ()->assertTrue(outage.stream().allMatch(r->r.retry()<=System.currentTimeMillis()+8_000),"bounded durable deadline"),
      ()->assertTrue(recycled<=1,"I2: no repeated lifecycle WARN per failed document/canary; actual="+recycled),
      ()->assertTrue(h.rows().stream().allMatch(r->r.state().equals("DONE") && r.attempts()==0),"prompt zero-attempt recovery through production parser child"));
    }
   }
  } finally { logger.detachAppender(appender); appender.stop(); h.close(); }
 }
}
