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
/** Independent preservation counterexample: canonical source IO at the write boundary. */
final class WriterUnreadableAliasTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 @Timeout(30) void canonicalSourceParentBecomingUnreadableDuringWriteStillCostsAFileAttemptDuringOutage(boolean retarget) throws Exception {
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  Path real=Files.createDirectory(temp.resolve("real-source"));
  var originalPermissions=Files.getPosixFilePermissions(real);
  Path canonical=Files.writeString(real.resolve("poison.txt"),"aliasdisappearsverifier");
  Path alias=Files.createSymbolicLink(temp.resolve("a-alias"),real).resolve("poison.txt");
  Path healthy=Files.writeString(temp.resolve("z-healthy.txt"),"ordinary healthy document");
  assertTrue(Files.isSameFile(alias,canonical));assertNotEquals(alias.toString(),canonical.toString());
  var broken=new AtomicBoolean(false);var sourceFailures=new CopyOnWriteArrayList<Throwable>();var actualFailures=new CopyOnWriteArrayList<Throwable>();
  Analyzer analyzer=new Analyzer() {
   protected TokenStreamComponents createComponents(String field) {
    var tokenizer=new StandardTokenizer();
    var tokens=new TokenFilter(tokenizer) {
     final CharTermAttribute text=addAttribute(CharTermAttribute.class);
     public boolean incrementToken() throws java.io.IOException {
      if(!input.incrementToken())return false;
      if(text.toString().contains("aliasdisappearsverifier")&&!broken.get()) {
       if(retarget) {
        Path replacement=Files.createDirectory(temp.resolve("readable-replacement"));
        Files.writeString(replacement.resolve("poison.txt"),"replacement source stays readable");
        Files.delete(alias.getParent()); Files.createSymbolicLink(alias.getParent(),replacement);
       }
       Files.setPosixFilePermissions(real,Set.of());broken.set(true);
       try{Files.readString(canonical);fail("canonical source must really refuse read");}
       catch(java.io.IOException actual){sourceFailures.add(actual);throw actual;}
      }
      if(broken.get())throw new java.io.IOException("shared analysis service unavailable for every input");
      return true;
     }
    };
    return new TokenStreamComponents(tokenizer,tokens);
   }
  };
  var schema=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
  var cacheField=schema.analyzerRegistry().getClass().getDeclaredField("analyzersById");cacheField.setAccessible(true);
  @SuppressWarnings("unchecked") var cache=(Map<String,Analyzer>)cacheField.get(schema.analyzerRegistry());
  for(String id:schema.analyzerRegistry().analyzerIds())cache.put(id,analyzer);
  try(var runtime=schema.atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();var parser=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var box=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"writer-source-alias",1),parser,Duration.ofSeconds(5),null)) {
   assertTrue(parser.extractArtifact(alias).result().content().contains("aliasdisappearsverifier"));
   h.writer=mock(IndexingCoordinator.class,call->{try{return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException wrapped){if(call.getMethod().getName().equals("indexSingle"))actualFailures.add(wrapped.getCause());throw wrapped.getCause();}});
   h.commitOps=runtime.commitOps();
   h.queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(alias).withinRoot(temp),JobQueue.EnqueueEntry.stat(healthy).withinRoot(temp)));
   h.loop=PipelineTestFixture.withExtractor(h,box);h.loop.start();h.awaitPolicy(12);h.stopLoopForOtherAssertions();
   var rows=h.rows();var peer=rows.stream().filter(r->r.path().endsWith("z-healthy.txt")).findFirst().orElseThrow();var bad=rows.stream().filter(r->r.path().contains("a-alias")).findFirst().orElseThrow();
   System.out.println("OWN writer canonical parent permission loss: retarget="+retarget+" sourceExceptions="+sourceFailures+" realWriteFailures="+actualFailures+" sourceAbsent="+Files.notExists(alias)+" sharedBroken="+broken+" writerOpen="+LiveWriterLockHealthTest.writer(runtime).isOpen()+" rows="+rows);
   actualFailures.forEach(f->f.printStackTrace(System.out));
   assertEquals(1,sourceFailures.size());assertInstanceOf(AccessDeniedException.class,sourceFailures.getFirst());
   assertEquals(retarget,Files.isReadable(alias),"retarget control keeps the claimed alias readable while its captured canonical source is denied");assertThrows(java.io.IOException.class,()->Files.isSameFile(alias,canonical),"canonical identity must be inaccessible while source parent is unsearchable");assertTrue(actualFailures.size()>=2,"real healthy peer must reach the broken shared analyzer too");
   assertAll(()->assertEquals(1,bad.attempts(),"K2/U19: an unreadable canonical source remains per-file even during a proven outage"),
    ()->assertNotEquals("DEFER_WITHOUT_ATTEMPT",bad.policy(),"writer source failure must not become an infrastructure deferral"),
    ()->assertEquals("RETRY_WITH_BACKOFF",bad.policy()),
    ()->assertEquals(0,peer.attempts()),()->assertEquals("DEFER_WITHOUT_ATTEMPT",peer.policy()));
  }finally{Files.setPosixFilePermissions(real,originalPermissions);h.close();analyzer.close();}
 }
}
