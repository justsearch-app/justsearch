/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class EnrichmentPreservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void explicitExpectationsMatchMismatchAndPreserveUnchangedContent() throws Exception {
    try(var runtime=open()) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID,"parent",
          SchemaFields.DOC_UID,"uid",SchemaFields.CONTENT,"same text",SchemaFields.VECTOR,new float[]{0,1,0,0},
          SchemaFields.EMBEDDING_STATUS,"COMPLETED")));
      commit(runtime);
      assertFalse(runtime.indexingCoordinator().updateDocumentIf("parent",Map.of(SchemaFields.CONTENT,"older text"),
          Map.of(SchemaFields.CONTENT,"must not be published")));
      assertTrue(runtime.indexingCoordinator().updateDocumentIf("parent",Map.of(SchemaFields.CONTENT,"same text"),
          Map.of(SchemaFields.CONTENT,"same text",SchemaFields.TITLE,"updated title")));
      commit(runtime);
      assertEquals("same text",runtime.documentFieldOps().getDocumentContent("parent"));
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("parent",SchemaFields.EMBEDDING_STATUS));
      try(var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
        boolean found=false;
        for(var leaf:reader.leaves()) {
          var vectors=leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);if(vectors==null) continue;
          var it=vectors.iterator();
          for(int doc=it.nextDoc();doc!=Integer.MAX_VALUE;doc=it.nextDoc()) {
            if(leaf.reader().getLiveDocs()==null || leaf.reader().getLiveDocs().get(doc)) {
              assertArrayEquals(new float[]{0,1,0,0},vectors.vectorValue(it.index()));found=true;
            }
          }
        }
        assertTrue(found,"unchanged text retains its vector after reopen");
      }
    }
  }

  @Test void oneOversizedParentDoesNotPullFollowingParentsIntoTheInferenceBatch() throws Exception {
    try(var runtime=open()) {
      String large="x".repeat((int)EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS+1);
      for(var entry:List.of(Map.entry("a-large",large),Map.entry("b-later","later text"))) {
        runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID,entry.getKey(),
            SchemaFields.DOC_UID,entry.getKey()+"-uid",SchemaFields.CONTENT,entry.getValue(),SchemaFields.EMBEDDING_STATUS,"PENDING")));
      }
      commit(runtime);
      var provider=mock(EmbeddingProvider.class);
      when(provider.embedDocumentBatch(List.of(large))).thenReturn(List.of(new float[]{1,0,0,0}));
      var result=EmbeddingBackfillOps.processEmbeddingBackfill(new EmbeddingBackfillOps.BackfillContext(
          runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),mock(WorkerSignalBus.class),
          IndexingPacing.unthrottled(),()->provider,()->true,()->true,100,LoggerFactory.getLogger(getClass())));
      assertEquals(1,result.docsProcessed());
      verify(provider).embedDocumentBatch(List.of(large));
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField("b-later",SchemaFields.EMBEDDING_STATUS));
    }
  }
  @Test void batchSnapshotHashesEachLegacyParentOnce() throws Exception {
    try(var runtime=open()) {
      String content="legacy parent text ".repeat(500);
      String revision=io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"parent",SchemaFields.CONTENT,content,SchemaFields.DOC_UID,"legacy-uid")));
      for(int i=0;i<3;i++) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"child-"+i,SchemaFields.DOC_UID,"child-uid-"+i,SchemaFields.IS_CHUNK,"true",SchemaFields.PARENT_DOC_ID,"parent",
          SchemaFields.CHUNK_START_CHAR,i*10,SchemaFields.CHUNK_END_CHAR,(i+1)*10,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256,revision)));
      commit(runtime);
      var hashes=new java.util.concurrent.atomic.AtomicInteger();
      try(var digest=mockStatic(io.justsearch.indexing.chunking.ChunkParentRevision.class,call -> {
        if(call.getMethod().getName().equals("sha256Hex")) hashes.incrementAndGet();
        return call.callRealMethod();
      })) {
        List<String> ids=List.of("child-0","child-1","child-2");
        Map<?,?> captured;
        try { captured=(Map<?,?>)runtime.documentFieldOps().getClass().getMethod("getEnrichmentInputs",List.class)
            .invoke(runtime.documentFieldOps(),ids); }
        catch(NoSuchMethodException beforeSnapshotApi) { captured=runtime.documentFieldOps().getDocumentContentBatch(ids); }
        assertEquals(3,captured.size());
        for(int i=0;i<3;i++) {
          Object value=captured.get("child-"+i);
          String text=value instanceof String before ? before : (String)value.getClass().getMethod("content").invoke(value);
          assertEquals(content.substring(i*10,(i+1)*10),text);
        }
        assertEquals(1,hashes.get(),"one captured legacy parent is hashed once for all sibling chunks");
      }
    }
  }

  @Test void sameParentBatchKeepsDifferentRevisionExpectationsSeparate() throws Exception {
    try(var runtime=open()) {
      String content="same parent text across both incarnations";
      String revision=io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"parent",SchemaFields.DOC_UID,"old-parent-uid",SchemaFields.CONTENT,content)));
      for(String id:List.of("old-child","current-child")) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,id,SchemaFields.DOC_UID,id+"-uid",SchemaFields.IS_CHUNK,"true",
          SchemaFields.PARENT_DOC_ID,"parent",SchemaFields.CHUNK_START_CHAR,0,SchemaFields.CHUNK_END_CHAR,10,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256,revision,SchemaFields.CHUNK_EMBEDDING_STATUS,"PENDING")));
      commit(runtime);
      var old=capture(runtime,List.of("old-child"));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"parent",SchemaFields.DOC_UID,"new-parent-uid",SchemaFields.CONTENT,content)));
      commit(runtime);
      var inputs=new HashMap<String,Object>(capture(runtime,List.of("current-child")));inputs.putAll(old);
      Map<String,Object> update=Map.of(SchemaFields.CHUNK_VECTOR,new float[]{1,0,0,0},SchemaFields.CHUNK_EMBEDDING_STATUS,"COMPLETED");
      var updates=List.of(Map.entry("current-child",update),Map.entry("old-child",update));
      io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult applied;
      try { applied=(io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult)runtime.indexingCoordinator().getClass()
          .getMethod("updateDocumentsBatchIf",List.class,Map.class).invoke(runtime.indexingCoordinator(),updates,inputs); }
      catch(NoSuchMethodException beforeGuardedPublication) { applied=runtime.indexingCoordinator().updateDocumentsBatch(updates); }
      assertEquals(1,applied.updatedCount(),"a current sibling cannot authorize the old incarnation's expectation");
      commit(runtime);
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField("old-child",SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("current-child",SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> capture(RunningRuntime runtime,List<String> ids) throws Exception {
    try { return (Map<String,Object>)runtime.documentFieldOps().getClass().getMethod("getEnrichmentInputs",List.class).invoke(runtime.documentFieldOps(),ids); }
    catch(NoSuchMethodException beforeSnapshotApi) { return new HashMap<>(runtime.documentFieldOps().getDocumentContentBatch(ids)); }
  }

  private RunningRuntime open() {
    var runtime=IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();runtime.commitOps().stopCommitTimer();return runtime;
  }
  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(CommitReason.TIMER);runtime.commitOps().maybeRefreshBlocking();
  }
}
