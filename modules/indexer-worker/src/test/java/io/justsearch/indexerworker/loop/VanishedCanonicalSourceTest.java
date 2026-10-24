/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** New counterexample: canonical source vanishes inside the parser while writer is unavailable. */
final class VanishedCanonicalSourceTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(30) void vanishedCanonicalSourceKeepsFilePolicyDuringWriterOutage() throws Exception {
  var h=new PipelineTestFixture(); h.temp=temp; h.init(); h.openStore(false);
  Path real=Files.createDirectory(temp.resolve("real"));
  Path canonical=Files.writeString(real.resolve("source.txt"),"healthy readable source content");
  Path alias=Files.createSymbolicLink(temp.resolve("alias"),real).resolve("source.txt");
  var failures=new CopyOnWriteArrayList<Exception>();
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(temp.resolve("index"))
      .withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();
      var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool)) {
   assertTrue(parser.extractArtifact(alias).result().content().contains("healthy"));
   ContentExtractorProvider provider=new ContentExtractorProvider() {
    public ContentExtractor.ExtractionResult extract(Path p) throws java.io.IOException,ContentExtractor.ExtractionException {
     if(p.equals(alias)) {
      Path resolved=p.toRealPath(); Files.delete(resolved);
      try { Files.readString(resolved); fail("source must actually have vanished"); }
      catch(java.io.IOException e) { failures.add(e); throw e; }
     }
     return parser.extractArtifact(p).result();
    }
    public String detectMimeType(Path p) { return parser.detectMimeType(p); }
   };
   h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(alias).withinRoot(temp)));
   h.enqueue("healthy-peer.txt");
   h.writer=runtime.indexingCoordinator(); runtime.close();
   try(var timebox=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"vanished-alias",1),provider,Duration.ofSeconds(5),null)) {
    h.loop=PipelineTestFixture.withExtractor(h,timebox); h.loop.start(); h.awaitPolicy(10);
    h.stopLoopForOtherAssertions(); var rows=h.rows();
    var source=rows.stream().filter(r->r.path().endsWith("alias/source.txt")).findFirst().orElseThrow();
    var peer=rows.stream().filter(r->r.path().endsWith("healthy-peer.txt")).findFirst().orElseThrow();
    System.out.println("K2 vanished canonical source="+canonical+" admitted alias="+alias+" actual source failures="+failures+" durable rows="+rows);
    assertFalse(failures.isEmpty()); assertFalse(Files.exists(alias));
    assertAll(()->assertTrue(source.attempts()>0 || source.state().equals("DONE") || source.state().equals("FAILED"),"the source itself vanished, so retain a per-file failure or confirmed-absence outcome despite simultaneous writer outage"),
      ()->assertNotEquals("DEFER_WITHOUT_ATTEMPT",source.policy()),
      ()->assertEquals(0,peer.attempts()),()->assertEquals("DEFER_WITHOUT_ATTEMPT",peer.policy()));
   }
  } finally { h.close(); }
 }
}
