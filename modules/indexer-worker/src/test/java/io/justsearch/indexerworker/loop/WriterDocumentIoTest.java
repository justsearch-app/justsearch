/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.ipc.StatusRequest;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Verifier's own cases, independent of the candidate's tests. */
final class WriterDocumentIoTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"plain", "outside-path"})
  void actualAnalyzerIOExceptionIsChargedAndExhausts(String kind) throws Exception {
    var schema=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0));
    var enabled=new AtomicBoolean(true);
    var tokenizerFailures=new AtomicInteger();
    var poison=new Analyzer() {
      @Override protected TokenStreamComponents createComponents(String field) {
        var tokenizer=new StandardTokenizer();
        var stream=new TokenFilter(tokenizer) {
          final CharTermAttribute term=addAttribute(CharTermAttribute.class);
          @Override public boolean incrementToken() throws IOException {
            if(!input.incrementToken()) return false;
            if(enabled.get() && term.toString().contains("verifierpoison")) {
              tokenizerFailures.incrementAndGet();
              if(kind.equals("outside-path")) throw new AccessDeniedException(temp.resolve("source-token-data").toString());
              // Text deliberately resembles a disk-full diagnostic. Message text alone is not provenance.
              throw new IOException("no space left on device: verifier document token stream");
            }
            return true;
          }
        };
        return new TokenStreamComponents(tokenizer,stream);
      }
    };
    // Install a real Lucene Analyzer before opening its IndexWriter. Only the fault source is injected.
    var cacheField=schema.analyzerRegistry().getClass().getDeclaredField("analyzersById");
    cacheField.setAccessible(true);
    @SuppressWarnings("unchecked") var cache=(Map<String,Analyzer>)cacheField.get(schema.analyzerRegistry());
    for(String id:schema.analyzerRegistry().analyzerIds()) cache.put(id,poison);
    var s=new PipelineTestFixture(); s.temp=temp; s.init(); s.openStore(false);
    s.extractedText="verifierpoison body";
    var failures=new CopyOnWriteArrayList<RuntimeException>();
    try(var runtime=schema.atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      s.writer=mock(IndexingCoordinator.class, org.mockito.AdditionalAnswers.delegatesTo(runtime.indexingCoordinator()));
      doAnswer(c -> {
        try { runtime.indexingCoordinator().indexSingle(c.getArgument(0)); }
        catch(RuntimeException failure) { failures.add(failure); throw failure; }
        return null;
      }).when(s.writer).indexSingle(any());
      s.commitOps=runtime.commitOps(); s.enqueue("poison.txt"); s.loop=s.newLoop(); s.loop.start();
      long deadline=System.nanoTime()+20_000_000_000L;
      while(System.nanoTime()<deadline && s.rows().getFirst().attempts()<3) {
        s.sql("UPDATE jobs SET retry_after=0 WHERE state='PENDING' AND attempts<3");
        Thread.sleep(20);
      }
      System.out.println("real analyzer "+kind+" tokenizerFailures="+tokenizerFailures+" rows="+s.rows()+" exceptions="+failures);
      assertTrue(tokenizerFailures.get()>=3,"must reach actual TokenStream through actual IndexWriter");
      assertTrue(failures.stream().anyMatch(e -> e instanceof IndexRuntimeIOException && e.getCause() instanceof IOException));
      assertTrue(failures.size() >= 3, "document failures leave the writer accepting later operations");
      var charged=s.rows().getFirst();
      assertTrue(charged.attempts()>=3,"document-specific writer IO must leave free grace and enter ladder");
      assertTrue(charged.retry()>System.currentTimeMillis()+59000,"counted retry uses durable minute-scale ladder");
      s.sql("UPDATE jobs SET first_failed_at="+(System.currentTimeMillis()-8L*86400000)+",retry_after=0 WHERE state='PENDING'");
      s.awaitAllState("RETRY_EXHAUSTED",5);
      enabled.set(false); s.enqueue("healthy-after-poison.txt"); s.awaitPathState("healthy-after-poison.txt","DONE",5);
      assertEquals("RETRY_EXHAUSTED",s.rows().stream().filter(r -> Path.of(r.path()).getFileName().toString().equals("poison.txt")).findFirst().orElseThrow().state());
      System.out.println("real analyzer final "+kind+" "+s.rows());
      s.stopLoopForOtherAssertions();
    } finally { s.close(); poison.close(); }
  }

}
