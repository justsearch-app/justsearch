/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.*;
import org.apache.lucene.document.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
/** Existing on-disk schema from another mapping, not document-specific content or injected code. */
final class ExistingSchemaHealthTest extends LuceneExecutorTestBase {
 @TempDir Path temp;
 @Test @Timeout(30) void incompatibleStoredTextSchemaMustNotChargeEveryHealthySource() throws Exception {
  Path index=temp.resolve("index");
  // Seed the actual active index and commit its real runtime fingerprint before reopening.
  // This injects stored field metadata only; no analyzer, codec, or mapper code is replaced.
  try(var seedRuntime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(index)
      .withExecutorRegistrations(testLuceneExecutors()).open()) {
   var old=new Document();old.add(new StringField(SchemaFields.CONTENT,"ordinary text from prior mapping",Field.Store.YES));
   LiveWriterLockHealthTest.writer(seedRuntime).addDocument(old);
   seedRuntime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
  }
  var h=new PipelineTestFixture();h.temp=temp;h.init();h.openStore(false);
  var failures=new CopyOnWriteArrayList<Throwable>();
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(index)
      .withExecutorRegistrations(testLuceneExecutors()).open();
      var owners=new TestEngineExecutors();var provider=new PolicyDrivenTikaExtractor(java.util.concurrent.Executors::newFixedThreadPool);
      var box=new TimeboxedContentExtractor(PipelineTestFixture.registration(owners,"stored-schema",1),provider,Duration.ofSeconds(5),null)) {
   var live=LiveWriterLockHealthTest.writer(runtime);
   h.writer=mock(IndexingCoordinator.class,call->{try{return call.getMethod().invoke(runtime.indexingCoordinator(),call.getArguments());}
    catch(java.lang.reflect.InvocationTargetException ex){if(call.getMethod().getName().equals("indexSingle"))failures.add(ex.getCause());throw ex.getCause();}});
   h.commitOps=runtime.commitOps();var files=h.enqueue("healthy-a.txt","healthy-b.txt");
   assertTrue(provider.extractArtifact(files.getFirst()).result().content().contains("healthy readable content"));
   String proof="absent";
   try{IndexingCoordinator.class.getMethod("probeIngestion").invoke(runtime.indexingCoordinator());proof="passed";}
   catch(NoSuchMethodException absent){/* Older runtimes lack this probe; durable-row assertions still apply. */}catch(java.lang.reflect.InvocationTargetException rejected){proof="rejected: "+rejected.getCause();}
   h.loop=PipelineTestFixture.withExtractor(h,box);h.loop.start();h.awaitPolicy(10);h.stopLoopForOtherAssertions();
   var rows=h.rows();
   System.out.println("Existing stored schema is incompatible with every ordinary text document; full canary="+proof+" writerOpen="+live.isOpen()+" tragedy="+live.getTragicException()+" realFailures="+failures+" durable rows="+rows);
   failures.forEach(f->f.printStackTrace(System.out));
   assertEquals(2,failures.size());assertTrue(failures.stream().allMatch(f->f instanceof IllegalArgumentException&&f.getMessage().contains("content")),"real Lucene field consistency check must reject both normal documents");
   assertTrue(rows.stream().allMatch(r->r.attempts()==0),"shared stored schema mismatch cannot spend healthy per-file attempts");
   long now=System.currentTimeMillis();assertTrue(rows.stream().allMatch(r->r.retry()<=now+8100));
  }finally{h.close();}
 }
 @Test @Timeout(30) void ordinaryStoredSchemaStillPassesWithoutPublishingPendingWork() throws Exception {
  Path index=temp.resolve("ordinary-index");
  try(var seed=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(index)
      .withExecutorRegistrations(testLuceneExecutors()).open()) {
   seed.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(java.util.Map.of(
       SchemaFields.DOC_ID,"seed",SchemaFields.DOC_UID,"seed#0",SchemaFields.CONTENT,"ordinary healthy content")));
   seed.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
  }
  try(var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(index)
      .withExecutorRegistrations(testLuceneExecutors()).open()) {
   var live=LiveWriterLockHealthTest.writer(runtime);
   runtime.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(java.util.Map.of(
       SchemaFields.DOC_ID,"pending",SchemaFields.DOC_UID,"pending#0",SchemaFields.CONTENT,"pending healthy content")));
   long sequence=live.getMaxCompletedSequenceNumber();int documents=live.getDocStats().maxDoc;
   var analyzer=live.getConfig().getAnalyzer();boolean pending=live.hasUncommittedChanges();
   for(int i=0;i<3;i++)runtime.indexingCoordinator().probeIngestion();
   assertEquals(sequence,live.getMaxCompletedSequenceNumber());assertEquals(documents,live.getDocStats().maxDoc);
   assertEquals(pending,live.hasUncommittedChanges());assertSame(analyzer,live.getConfig().getAnalyzer());
   System.out.println("ordinary stored schema: three probes pass, buffered live document and analyzer preserved");
  }
 }
}
