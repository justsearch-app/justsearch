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
      when(provider.isAvailable()).thenReturn(true);
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
      try(var ignored=mockStatic(io.justsearch.indexing.chunking.ChunkParentRevision.class,call -> {
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
      LuceneRuntimeTypes.BatchUpdateResult applied;
      try { applied=(LuceneRuntimeTypes.BatchUpdateResult)runtime.indexingCoordinator().getClass()
          .getMethod("updateDocumentsBatchIf",List.class,Map.class).invoke(runtime.indexingCoordinator(),updates,inputs); }
      catch(NoSuchMethodException beforeGuardedPublication) { applied=runtime.indexingCoordinator().updateDocumentsBatch(updates); }
      assertEquals(1,applied.updatedCount(),"a current sibling cannot authorize the old incarnation's expectation");
      commit(runtime);
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField("old-child",SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("current-child",SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
  }

  enum Lane { DENSE_PARENT, DENSE_CHUNK, SPARSE_PARENT, SPARSE_CHUNK, NER, BGE, COMBINED,
    COMBINED_CHUNK, COMBINED_SPARSE_PARENT, COMBINED_SPARSE_CHUNK, COMBINED_NER, COMBINED_ALL }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.EnumSource(Lane.class)
  void keysetTurnsPassPoisonSurviveRefreshDeletionAndIdleThenWrap(Lane lane) throws Exception {
    try (var runtime = openEnrichment()) {
      // Physical insertion order deliberately disagrees with the document-ID order.
      for (String id : List.of("c-later", "a-poison", "b-later"))
        seedPending(runtime, id, id.equals("a-poison") ?
            (lane.name().contains("CHUNK") ? "poison" : "") : id, lane);
      commit(runtime);
      var selection = new EnrichmentSelection();
      var progress = new WindowedEmbedProgress();
      runTurn(runtime, lane, 1, selection, progress);
      assertEquals("1", runtime.documentFieldOps().getDocumentField("a-poison", retry(lane)));
      var idleProvider = mock(EmbeddingProvider.class);
      CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),IndexingPacing.unthrottled(),()->idleProvider,
          ()->null,()->null,()->true,()->false,1,LoggerFactory.getLogger(getClass()),false,false,false,0,
          new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,progress),selection);
      runTurn(runtime, lane, 1, selection, progress);
      assertTrue(completed(runtime, "b-later", lane), "a retryable poison row must not pin the head");
      assertEquals("1", runtime.documentFieldOps().getDocumentField("a-poison", retry(lane)));
      runtime.indexingCoordinator().deleteById("b-later");
      seedPending(runtime, "aa-behind-cursor", "new text", lane);
      commit(runtime);
      runTurn(runtime, lane, 1, selection, progress);
      assertTrue(completed(runtime, "c-later", lane), "refresh/deletion must not restart the sweep");
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("aa-behind-cursor", status(lane)));
      runTurn(runtime, lane, 1, selection, progress);
      assertEquals("2", runtime.documentFieldOps().getDocumentField("a-poison", retry(lane)), "wrap revisits pending poison");
      runTurn(runtime, lane, 1, selection, progress);
      assertTrue(completed(runtime, "aa-behind-cursor", lane));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
  void examinedIneligibleHeadDoesNotRunOrPinTheFollowingPage(boolean excluded) throws Exception {
    try (var runtime = openEnrichment()) {
      seedPending(runtime,"a-ineligible","first text",Lane.DENSE_PARENT);
      seedPending(runtime,"b-pending","next text",Lane.DENSE_PARENT); commit(runtime);
      var changed = new java.util.concurrent.atomic.AtomicBoolean();
      var real = runtime.documentFieldOps();
      var fields = mock(DocumentFieldOps.class,call -> {
        var result = call.getMethod().invoke(real,call.getArguments());
        String method = call.getMethod().getName();
        if (!excluded && Set.of("queryPendingPage","queryDocIdsByField").contains(method)
            && changed.compareAndSet(false,true)) {
          // Another path completes the row between selection and the reader's content snapshot.
          runtime.indexingCoordinator().updateDocument("a-ineligible",Map.of(
              SchemaFields.VECTOR,new float[]{1,0,0,0},SchemaFields.EMBEDDING_STATUS,"COMPLETED"));
          commit(runtime);
        }
        return result;
      });
      if (excluded) doReturn(Set.of("a-ineligible")).when(fields).excludedProcessingIds(anyList());
      var provider = mock(EmbeddingProvider.class); when(provider.isAvailable()).thenReturn(true);
      when(provider.embedDocumentBatch(anyList())).thenReturn(List.of(new float[]{1,0,0,0}));
      var context = new EmbeddingBackfillOps.BackfillContext(fields,runtime.indexingCoordinator(),runtime.commitOps(),
          mock(WorkerSignalBus.class),IndexingPacing.unthrottled(),()->provider,()->true,()->true,1,LoggerFactory.getLogger(getClass()));
      var selection = new EnrichmentSelection();
      EmbeddingBackfillOps.processEmbeddingBackfill(context,selection);
      assertDoesNotThrow(()->verify(provider,never()).embedDocumentBatch(anyList()),"an ineligible selection does not infer or spend a retry");
      assertEquals("0",real.getDocumentField("a-ineligible",SchemaFields.EMBEDDING_RETRY_COUNT));
      EmbeddingBackfillOps.processEmbeddingBackfill(context,selection); commit(runtime);
      assertEquals("COMPLETED",real.getDocumentField("b-pending",SchemaFields.EMBEDDING_STATUS));
      if (excluded) assertEquals("PENDING",real.getDocumentField("a-ineligible",SchemaFields.EMBEDDING_STATUS));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.EnumSource(value = Lane.class, mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE, names = "DENSE_PARENT")
  void budgetDeferredSuffixIsNextTurnWithoutBlankRetriesOrCharges(Lane lane) throws Exception {
    try (var runtime = openEnrichment()) {
      seedPending(runtime, "a", "a".repeat(300_000), lane);
      seedPending(runtime, "b", "b".repeat(300_000), lane);
      seedPending(runtime, "c", "c".repeat(300_000), lane);
      commit(runtime);
      var selection = new EnrichmentSelection(); var progress = new WindowedEmbedProgress();
      runTurn(runtime, lane, 3, selection, progress);
      assertTrue(completed(runtime, "a", lane));
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("b", status(lane)),
          "the second proof/text must be deferred by the 512,000-character allowance");
      assertEquals("0", runtime.documentFieldOps().getDocumentField("b", retry(lane)));
      runTurn(runtime, lane, 3, selection, progress);
      assertTrue(completed(runtime, "b", lane), "deferred suffix is next, never skipped by the cursor");
      assertEquals("0", runtime.documentFieldOps().getDocumentField("c", retry(lane)));
      runTurn(runtime, lane, 3, selection, progress);
      assertTrue(completed(runtime, "c", lane));
    }
  }

  @Test void chunkProofRetentionCountsDistinctParentsOnceAndAllowsOneOversizedOwner() throws Exception {
    try (var runtime = openEnrichment()) {
      for (String id : List.of("a", "b")) seedPending(runtime, id, id.repeat(300_000), Lane.DENSE_CHUNK);
      // A sibling shares a's parent proof; its own slice is still retained separately.
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"a-sibling",SchemaFields.DOC_UID,"a-sibling-uid",SchemaFields.IS_CHUNK,"true",
          SchemaFields.PARENT_DOC_ID,"parent-a",SchemaFields.CHUNK_START_CHAR,10,SchemaFields.CHUNK_END_CHAR,20,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256,io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("a".repeat(300_000)))));
      commit(runtime);
      var ids = List.of("a", "a-sibling", "b");
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(ids, 512_000);
      assertEquals(Set.of("a", "a-sibling"), inputs.keySet(), "distinct proof text, rather than tiny slices, consumes retention");
      var parentFirst = runtime.documentFieldOps().getEnrichmentInputs(List.of("parent-a","a"),512_000);
      assertEquals(Set.of("parent-a","a"),parentFirst.keySet(),"a parent and its proof text consume one allowance");
      var chunkFirst = runtime.documentFieldOps().getEnrichmentInputs(List.of("a","parent-a"),512_000);
      assertEquals(Set.of("parent-a","a"),chunkFirst.keySet(),"proof de-duplication is independent of collection order");
      assertEquals(List.of("a", "a-sibling"), runtime.documentFieldOps().getEnrichmentInputPage(ids,512_000).examinedIds());
      seedPending(runtime, "huge", "z".repeat(512_001), Lane.DENSE_CHUNK); commit(runtime);
      assertEquals(Set.of("huge"), runtime.documentFieldOps().getEnrichmentInputs(List.of("huge","b"),512_000).keySet());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.CsvSource({"deleted,false","replaced,false","completed,false","deleted,true","replaced,true","completed,true"})
  void nextTurnReleasesDeletedReplacedAndExternallyCompletedOwners(String change,boolean routeOnly) throws Exception {
    try (var runtime = openEnrichment()) {
      seedPending(runtime,"stable","stable text",Lane.COMBINED);
      seedPending(runtime,"retired","retired text",Lane.COMBINED); commit(runtime);
      var provider = mock(EmbeddingProvider.class); when(provider.isAvailable()).thenReturn(true); when(provider.dimension()).thenReturn(4);
      var progress = new WindowedEmbedProgress();
      for (String id : List.of("stable","retired")) {
        var input = runtime.documentFieldOps().getEnrichmentInputs(List.of(id)).get(id);
        progress.identify(id,input.content(),new WindowedEmbedProgress.Identity(runtime.indexingCoordinator().enrichmentAuthority(),
            runtime.indexingCoordinator().bulkDeleteEpoch(),provider,4,input.fields().get(SchemaFields.DOC_UID),
            input.fields().get(SchemaFields.CONTENT_SHA256),input.fields().get(SchemaFields.SOURCE_SHA256)));
        if (routeOnly) progress.route(id,input.content(),WindowedEmbedProgress.Route.WINDOW);
        else progress.record(id,input.content(),3,0,List.of(new float[]{1,0,0,0}));
      }
      switch(change) {
        case "deleted" -> runtime.indexingCoordinator().deleteById("retired");
        case "replaced" -> runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID,"retired",SchemaFields.DOC_UID,"new-uid",SchemaFields.CONTENT,"retired text",SchemaFields.EMBEDDING_STATUS,"PENDING")));
        case "completed" -> runtime.indexingCoordinator().updateDocument("retired",Map.of(
            SchemaFields.VECTOR,new float[]{1,0,0,0},SchemaFields.EMBEDDING_STATUS,"COMPLETED"));
        default -> throw new AssertionError(change);
      }
      commit(runtime);
      // No encoder work is available in this turn; cleanup must not require re-selection.
      CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),IndexingPacing.unthrottled(),()->provider,
          ()->null,()->null,()->true,()->false,1,LoggerFactory.getLogger(getClass()),false,false,false,0,
          new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,progress),new EnrichmentSelection());
      assertEquals(0,progress.nextWindow("retired","retired text"),"retired partial releases its slot next turn");
      assertNull(progress.route("retired","retired text"),"route-only owners also release their slot");
      if (routeOnly) assertEquals(WindowedEmbedProgress.Route.WINDOW,progress.route("stable","stable text"));
      else assertEquals(1,progress.nextWindow("stable","stable text"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
  void retainedOwnerIsServedBeforeNewAdmissions(boolean fullWithWaitingChunk) throws Exception {
    try (var runtime = openEnrichment()) {
      seedPending(runtime,"a-new",fullWithWaitingChunk ? "x".repeat(512_001) : "new text",
          fullWithWaitingChunk ? Lane.DENSE_CHUNK : Lane.COMBINED);
      seedPending(runtime,"z-owner","owner text",Lane.COMBINED); commit(runtime);
      var provider = mock(EmbeddingProvider.class); when(provider.isAvailable()).thenReturn(true); when(provider.dimension()).thenReturn(4);
      when(provider.documentWindowCount(anyString())).thenReturn(3);
      when(provider.embedDocumentWindows(eq("owner text"),eq(1),anyInt())).thenReturn(
          new EmbeddingProvider.WindowSlice(List.of(new float[]{1,0,0,0},new float[]{1,0,0,0}),1,3));
      var progress = new WindowedEmbedProgress();
      var input = runtime.documentFieldOps().getEnrichmentInputs(List.of("z-owner")).get("z-owner");
      progress.identify("z-owner","owner text",new WindowedEmbedProgress.Identity(runtime.indexingCoordinator().enrichmentAuthority(),
          runtime.indexingCoordinator().bulkDeleteEpoch(),provider,4,input.fields().get(SchemaFields.DOC_UID),
          input.fields().get(SchemaFields.CONTENT_SHA256),input.fields().get(SchemaFields.SOURCE_SHA256)));
      progress.record("z-owner","owner text",3,0,List.of(new float[]{1,0,0,0}));
      if (fullWithWaitingChunk) {
        var owners = new ArrayList<String>();
        for (int i = 0; i < 255; i++) {
          String id = "retained-"+i;
          seedPending(runtime,id,"retained text",Lane.COMBINED);
          owners.add(id);
        }
        commit(runtime);
        var retainedInputs = runtime.documentFieldOps().getEnrichmentInputs(owners);
        for (String id : owners) {
          var fields = retainedInputs.get(id).fields();
          progress.identify(id,"retained text",new WindowedEmbedProgress.Identity(
              runtime.indexingCoordinator().enrichmentAuthority(),runtime.indexingCoordinator().bulkDeleteEpoch(),
              provider,4,fields.get(SchemaFields.DOC_UID),fields.get(SchemaFields.CONTENT_SHA256),
              fields.get(SchemaFields.SOURCE_SHA256)));
          progress.route(id,"retained text",WindowedEmbedProgress.Route.WINDOW);
        }
      }
      CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),IndexingPacing.unthrottled(),()->provider,
          ()->null,()->null,()->true,()->true,1,LoggerFactory.getLogger(getClass()),fullWithWaitingChunk,false,false,fullWithWaitingChunk ? 1 : 0,
          new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,progress),new EnrichmentSelection());
      commit(runtime);
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("z-owner",SchemaFields.EMBEDDING_STATUS));
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField("a-new",fullWithWaitingChunk
          ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS));
      assertEquals("0",runtime.documentFieldOps().getDocumentField("a-new",fullWithWaitingChunk
          ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT));
      assertDoesNotThrow(()->verify(provider).embedDocumentWindows("owner text",1,32));
    }
  }

  @Test void oversizedRetainedParentStillAllowsPendingChunkToAdvanceWithinThreeCycles() throws Exception {
    try (var runtime = openEnrichment()) {
      String text = "x".repeat((int)EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS+1);
      seedPending(runtime,"a-parent",text,Lane.COMBINED); commit(runtime);
      var units = new java.util.concurrent.atomic.AtomicInteger();
      var provider = mock(EmbeddingProvider.class);
      when(provider.isAvailable()).thenReturn(true); when(provider.dimension()).thenReturn(4);
      when(provider.documentWindowCount(anyString())).thenAnswer(call ->
          ((String)call.getArgument(0)).length()>EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS ? 128 : 1);
      when(provider.embedDocumentWindows(anyString(),anyInt(),anyInt())).thenAnswer(call -> {
        units.incrementAndGet(); int from = call.getArgument(1);
        return new EmbeddingProvider.WindowSlice(Collections.nCopies(Math.min(32,128-from),new float[]{1,0,0,0}),from,128);
      });
      when(provider.embedDocumentBatch(anyList())).thenAnswer(call -> {
        units.incrementAndGet(); List<String> texts = call.getArgument(0);
        return Collections.nCopies(texts.size(),new float[]{1,0,0,0});
      });
      var progress = new WindowedEmbedProgress();
      var input = runtime.documentFieldOps().getEnrichmentInputs(List.of("a-parent")).get("a-parent");
      progress.identify("a-parent",text,new WindowedEmbedProgress.Identity(runtime.indexingCoordinator().enrichmentAuthority(),
          runtime.indexingCoordinator().bulkDeleteEpoch(),provider,4,input.fields().get(SchemaFields.DOC_UID),
          input.fields().get(SchemaFields.CONTENT_SHA256),input.fields().get(SchemaFields.SOURCE_SHA256)));
      progress.record("a-parent",text,128,0,Collections.nCopies(32,new float[]{1,0,0,0}));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID,"b-chunk",SchemaFields.DOC_UID,"b-chunk-uid",SchemaFields.IS_CHUNK,"true",
          SchemaFields.PARENT_DOC_ID,"a-parent",SchemaFields.CHUNK_START_CHAR,0,SchemaFields.CHUNK_END_CHAR,10,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256,io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(text),
          SchemaFields.CHUNK_EMBEDDING_STATUS,"PENDING",SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,"0")));
      commit(runtime);
      var selection = new EnrichmentSelection();
      for (int cycle = 0; cycle < 3 && "PENDING".equals(runtime.documentFieldOps().getDocumentField(
          "b-chunk",SchemaFields.CHUNK_EMBEDDING_STATUS)); cycle++) {
        units.set(0);
        CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
            runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),IndexingPacing.unthrottled(),()->provider,
            ()->null,()->null,()->true,()->true,100,LoggerFactory.getLogger(getClass()),true,false,false,1,
            new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->units.get()>=1,()->false,()->false,progress),selection);
        commit(runtime);
      }
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("b-chunk",SchemaFields.CHUNK_EMBEDDING_STATUS),
          "a retained oversized parent must not consume every turn's text allowance and starve its ordinary chunk");
    }
  }

  private RunningRuntime openEnrichment() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.add(new FieldCatalogDef.FieldDef("splade","splade",false,false,List.of(),null,null,false,"reset-status:splade_status"));
    for (String name : List.of("splade_status","ner_status")) fields.add(new FieldCatalogDef.FieldDef(name,"keyword",true,true,List.of("filter"),null,null,false));
    for (String name : List.of("embedding_retry_count","splade_retry_count","ner_retry_count")) fields.add(new FieldCatalogDef.FieldDef(name,"long",true,true,List.of(),null,null,false));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("bounded-enrichment",fields)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open(); runtime.commitOps().stopCommitTimer(); return runtime;
  }

  private void seedPending(RunningRuntime runtime, String id, String text, Lane lane) {
    boolean chunk = lane.name().contains("CHUNK");
    var values = new HashMap<String,Object>();
    values.put(SchemaFields.DOC_ID,id); values.put(SchemaFields.DOC_UID,id+"-uid");
    values.put(status(lane),"PENDING"); values.put(retry(lane),"0");
    if (lane == Lane.COMBINED_ALL) {
      values.put(SchemaFields.SPLADE_STATUS,"PENDING"); values.put(SchemaFields.SPLADE_RETRY_COUNT,"0");
      values.put(SchemaFields.NER_STATUS,"PENDING"); values.put(SchemaFields.NER_RETRY_COUNT,"0");
    }
    if (chunk) {
      String parent = "parent-"+id;
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID,parent,
          SchemaFields.DOC_UID,parent+"-uid",SchemaFields.CONTENT,text)));
      values.put(SchemaFields.IS_CHUNK,"true"); values.put(SchemaFields.PARENT_DOC_ID,parent);
      values.put(SchemaFields.CHUNK_START_CHAR,0); values.put(SchemaFields.CHUNK_END_CHAR,Math.min(10,text.length()));
      values.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256,io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(text));
    } else values.put(SchemaFields.CONTENT,text);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(values));
  }

  private static String status(Lane lane) {
    return switch(lane) {
      case DENSE_PARENT, COMBINED, COMBINED_ALL -> SchemaFields.EMBEDDING_STATUS;
      case DENSE_CHUNK, COMBINED_CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
      case SPARSE_PARENT, SPARSE_CHUNK, BGE, COMBINED_SPARSE_PARENT, COMBINED_SPARSE_CHUNK -> SchemaFields.SPLADE_STATUS;
      case NER, COMBINED_NER -> SchemaFields.NER_STATUS;
    };
  }
  private static String retry(Lane lane) {
    return switch(lane) {
      case DENSE_PARENT, COMBINED, COMBINED_ALL -> SchemaFields.EMBEDDING_RETRY_COUNT;
      case DENSE_CHUNK, COMBINED_CHUNK -> SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT;
      case SPARSE_PARENT, SPARSE_CHUNK, BGE, COMBINED_SPARSE_PARENT, COMBINED_SPARSE_CHUNK -> SchemaFields.SPLADE_RETRY_COUNT;
      case NER, COMBINED_NER -> SchemaFields.NER_RETRY_COUNT;
    };
  }
  private static boolean completed(RunningRuntime runtime,String id,Lane lane) {
    return Set.of("COMPLETED","COMPLETED_EMPTY").contains(runtime.documentFieldOps().getDocumentField(id,status(lane)));
  }

  private void runTurn(RunningRuntime runtime, Lane lane, int size, EnrichmentSelection selection,
      WindowedEmbedProgress progress) throws Exception {
    var dense = mock(EmbeddingProvider.class); when(dense.isAvailable()).thenReturn(true); when(dense.dimension()).thenReturn(4);
    when(dense.embedDocumentBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      if (texts.contains("poison")) return null;
      return Collections.nCopies(texts.size(), new float[]{1,0,0,0});
    });
    when(dense.embedDocument(eq("poison"), any())).thenThrow(new IllegalArgumentException("poison row"));
    var sparse = mock(io.justsearch.indexerworker.splade.SpladeEncoder.class);
    when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      if (texts.contains("poison")) throw new IllegalArgumentException("poison row");
      return Collections.nCopies(texts.size(),Map.of("word",1f));
    });
    when(sparse.encode(eq("poison"), any())).thenThrow(new IllegalArgumentException("poison row"));
    var ner = mock(io.justsearch.indexerworker.ner.NerService.class); when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(io.justsearch.indexerworker.ner.NerResult.EMPTY));
    var log = LoggerFactory.getLogger(getClass()); var pacing = IndexingPacing.unthrottled();
    switch(lane) {
      case DENSE_PARENT, DENSE_CHUNK -> {
        var context = new EmbeddingBackfillOps.BackfillContext(runtime.documentFieldOps(), runtime.indexingCoordinator(),runtime.commitOps(),
            mock(WorkerSignalBus.class),pacing,()->dense,()->true,()->true,size,log);
        if(lane==Lane.DENSE_CHUNK) EmbeddingBackfillOps.processChunkEmbeddingBackfill(context,selection);
        else EmbeddingBackfillOps.processEmbeddingBackfill(context,selection);
      }
      case SPARSE_PARENT, SPARSE_CHUNK -> SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),pacing,()->sparse,()->true,size,true,lane==Lane.SPARSE_CHUNK,log),selection);
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(runtime.documentFieldOps(),runtime.indexingCoordinator(),
          runtime.commitOps(),pacing,()->ner,()->true,size,log),selection);
      case COMBINED, COMBINED_CHUNK, COMBINED_SPARSE_PARENT, COMBINED_SPARSE_CHUNK, COMBINED_NER, COMBINED_ALL -> {
        boolean denseEnabled = lane == Lane.COMBINED || lane == Lane.COMBINED_CHUNK || lane == Lane.COMBINED_ALL;
        boolean sparseEnabled = lane == Lane.COMBINED_SPARSE_PARENT || lane == Lane.COMBINED_SPARSE_CHUNK || lane == Lane.COMBINED_ALL;
        boolean nerEnabled = lane == Lane.COMBINED_NER || lane == Lane.COMBINED_ALL;
        CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
            runtime.documentFieldOps(),runtime.indexingCoordinator(),runtime.commitOps(),pacing,()->dense,
            ()->sparseEnabled ? sparse : null,()->nerEnabled ? ner : null,()->true,()->denseEnabled,
            size,log,lane==Lane.COMBINED_CHUNK,lane==Lane.COMBINED_SPARSE_CHUNK,false,lane==Lane.COMBINED_CHUNK ? size : 0,
            new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,progress),selection);
      }
      case BGE -> {
        var encoder = mock(io.justsearch.indexerworker.bgem3.BgeM3Encoder.class);
        when(encoder.encodeBatch(anyList())).thenAnswer(call -> Collections.nCopies(((List<?>)call.getArgument(0)).size(),
            new io.justsearch.indexerworker.bgem3.BgeM3Output(new float[]{1,0,0,0},Map.of("word",1f))));
        BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(runtime.documentFieldOps(),runtime.indexingCoordinator(),
            runtime.commitOps(),mock(WorkerSignalBus.class),()->encoder,()->true,size,true,false,log),selection);
      }
    }
    commit(runtime);
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
