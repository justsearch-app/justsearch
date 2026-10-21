/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.bgem3.*;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.*;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/** Real publication, real concurrent replacement, and committed-reader observations. */
final class EnrichmentRevisionRaceTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  enum Stage { EMBEDDING, CHUNK, SPLADE, NER, COMBINED, COMBINED_LATE, BGE_M3 }
  private static final String OLD = "old body ".repeat(20);
  private static final String NEW = "new body ".repeat(20);

  @ParameterizedTest(name="staleSuccessCannotCompleteReplacement({0})") @EnumSource(Stage.class)
  void staleSuccessCannotCompleteReplacement(Stage stage) throws Exception { race(stage, false, false); }

  @ParameterizedTest(name="staleFailureCannotSpendReplacementRetries({0})") @EnumSource(Stage.class)
  void staleFailureCannotSpendReplacementRetries(Stage stage) throws Exception { race(stage, true, true); }

  @ParameterizedTest(name="fallbackCannotPublishOntoReplacement({0})") @EnumSource(value=Stage.class, names={"EMBEDDING","CHUNK","SPLADE","BGE_M3"})
  void fallbackCannotPublishOntoReplacement(Stage stage) throws Exception { race(stage, false, true); }

  private void race(Stage stage, boolean fail, boolean fallback) throws Exception {
    try (var runtime = open()) {
      seed(runtime, "parent", OLD);
      if (stage == Stage.CHUNK) seedChunk(runtime, OLD);
      else {
        seed(runtime, "stable", OLD);
        if(stage==Stage.COMBINED_LATE) seedChunk(runtime,OLD);
      }
      commit(runtime);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var executor = Executors.newSingleThreadExecutor();
      try {
        var work = executor.submit(() -> {
          try { run(stage, runtime, () -> {
            entered.countDown();
            try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
          }, fail, fallback); }
          catch (Exception failure) { throw new AssertionError(failure); }
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
          assertDoesNotThrow(() -> work.get(1, TimeUnit.SECONDS));
          fail("provider must have captured C0 for " + stage);
        }
        seed(runtime, "parent", NEW);
        if(stage==Stage.CHUNK) seedChunk(runtime,NEW);
        commit(runtime);
        release.countDown();
        assertDoesNotThrow(() -> work.get(20, TimeUnit.SECONDS));
        String target = stage == Stage.CHUNK ? "child" : "parent";
        String status = status(stage);
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(target, status),
            "C0 success/failure cannot complete or fail C1");
        String retries = status.replace("_status", "_retry_count");
        assertEquals("0", runtime.documentFieldOps().getDocumentField(target, retries),
            "a stale failure cannot spend C1's budget");
        commit(runtime);
        assertPersistedPending(target, status);
        if (!fail && stage != Stage.CHUNK) assertEquals("COMPLETED",
            runtime.documentFieldOps().getDocumentField("stable", status), "mixed batch keeps its winning item");
        if (stage == Stage.CHUNK) seedChunk(runtime, NEW);
        commit(runtime);
        run(stage, runtime, () -> {}, false, false);
        commit(runtime);
        assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(target, status),
            "replacement work must remain runnable");
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
      }
    }
  }

  @ParameterizedTest(name="blankInputFailureCannotSpendReplacementRetries({0})") @EnumSource(value=Stage.class,names={"EMBEDDING","SPLADE","NER","BGE_M3"})
  void blankInputFailureCannotSpendReplacementRetries(Stage stage) throws Exception { blankRace(stage); }

  @Test void blankCombinedInputStillDoesNotSpendReplacementRetries() throws Exception { blankRace(Stage.COMBINED); }

  private void blankRace(Stage stage) throws Exception {
    try (var runtime=open()) {
      seed(runtime,"parent","");commit(runtime);
      var once=new java.util.concurrent.atomic.AtomicBoolean();
      var fields=mock(DocumentFieldOps.class,withSettings().spiedInstance(runtime.documentFieldOps())
          .defaultAnswer(call -> {
            Object value=call.callRealMethod();
            if (Set.of("getEnrichmentInputs","getDocumentContentBatch","getDocumentContent").contains(call.getMethod().getName())
                && once.compareAndSet(false,true)) { seed(runtime,"parent",NEW);commit(runtime); }
            return value;
          }));
      run(stage,runtime,fields,()->{},false,false);
      assertTrue(once.get(),"fixture must replace the source after the blank input was captured");
      assertEquals("0",runtime.documentFieldOps().getDocumentField("parent",status(stage).replace("_status","_retry_count")),
          "blank C0 cannot spend C1's retries");
      assertEquals("PENDING",runtime.documentFieldOps().getDocumentField("parent",status(stage)));
    }
  }

  @ParameterizedTest(name="completedCurrentWorkSurvivesAnOlderInFlightResult({0})") @EnumSource(value=Stage.class,names={"EMBEDDING","NER"})
  void completedCurrentWorkSurvivesAnOlderInFlightResult(Stage stage) throws Exception {
    try (var runtime=open()) {
      seed(runtime,"parent",OLD);commit(runtime);
      var once=new java.util.concurrent.atomic.AtomicBoolean();
      run(stage,runtime,()->{
        if (once.compareAndSet(false,true)) {
          Map<String,Object> updates=new HashMap<>();
          updates.put(SchemaFields.EMBEDDING_STATUS,"COMPLETED");updates.put(SchemaFields.VECTOR,new float[]{0,1,0,0});
          updates.put(SchemaFields.NER_STATUS,"COMPLETED");updates.put(SchemaFields.ENTITY_PERSONS_RAW,List.of("current-person"));
          runtime.indexingCoordinator().updateDocument("parent",updates);commit(runtime);
        }
      },false,false);
      assertEquals("COMPLETED",runtime.documentFieldOps().getDocumentField("parent",status(stage)));
      if (stage==Stage.NER) assertEquals(List.of("current-person"),runtime.documentFieldOps().getDocumentFieldValues("parent",SchemaFields.ENTITY_PERSONS_RAW));
      else {
        try(var fs=FSDirectory.open(directory);var reader=DirectoryReader.open(fs)) {
          boolean found=false;
          for (var leaf:reader.leaves()) {
            var vectors=leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
            if(vectors==null) continue;
            var it=vectors.iterator();
            for(int doc=it.nextDoc();doc!=Integer.MAX_VALUE;doc=it.nextDoc()) {
              if(leaf.reader().getLiveDocs()==null || leaf.reader().getLiveDocs().get(doc)) {
                assertArrayEquals(new float[]{0,1,0,0},vectors.vectorValue(it.index()));found=true;
              }
            }
          }
          assertTrue(found);
        }
      }
    }
  }

  @Test void equalLengthJavaHashCollisionDoesNotResumeOldWindows() {
    assertEquals("Aa".hashCode(), "BB".hashCode());
    var progress = new WindowedEmbedProgress();
    progress.record("parent", "Aa", 2, 0, List.of(new float[] {1,0}));
    assertEquals(0, progress.nextWindow("parent", "BB"), "different source text restarts at window zero");
    progress.record("parent", "BB", 1, 0, List.of(new float[] {0,1}));
    assertArrayEquals(new float[] {0,1}, progress.complete("parent"));
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("source_sha256", "embedding_retry_count", "splade_status", "splade_retry_count",
        "ner_status", "ner_retry_count", "entity_persons_raw")) {
      boolean number = name.endsWith("_count");
      fields.add(new FieldDef(name, number ? "long" : "keyword", true, true, List.of("filter"), null, null,
          name.equals("entity_persons_raw")));
    }
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false, "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("revision-races", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, String id, String content) {
    Map<String,Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID,id); fields.put(SchemaFields.DOC_UID,id+"-uid");
    fields.put(SchemaFields.CONTENT,content); fields.put(SchemaFields.CONTENT_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.SOURCE_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.IS_CHUNK,"false");
    for (String stage : List.of("embedding","splade","ner")) {
      fields.put(stage+"_status","PENDING"); fields.put(stage+"_retry_count","0");
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void seedChunk(RunningRuntime runtime, String content) {
    Map<String,Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID,"child"); fields.put(SchemaFields.DOC_UID,"child-uid");
    fields.put(SchemaFields.IS_CHUNK,"true"); fields.put(SchemaFields.PARENT_DOC_ID,"parent");
    fields.put(SchemaFields.CHUNK_START_CHAR,0); fields.put(SchemaFields.CHUNK_END_CHAR,content.length());
    fields.put(SchemaFields.CHUNK_INDEX,0); fields.put(SchemaFields.CHUNK_TOTAL,1);
    fields.put(SchemaFields.CHUNK_CONTENT,content);
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS,"PENDING"); fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,"0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(CommitReason.TIMER); runtime.commitOps().maybeRefreshBlocking();
  }

  private void assertPersistedPending(String id, String status) throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = DirectoryReader.open(fs)) {
      var searcher = new IndexSearcher(reader);
      var hits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID,id)),1);
      assertEquals(1,hits.scoreDocs.length);
      assertEquals("PENDING",reader.storedFields().document(hits.scoreDocs[0].doc).get(status));
      assertEquals(0,searcher.count(new BooleanQuery.Builder()
          .add(new TermQuery(new Term(SchemaFields.ENTITY_PERSONS_RAW,"old-entity")),BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.DOC_ID,id)),BooleanClause.Occur.FILTER).build()),
          "stale entities cannot be served after reopen");
    }
  }

  private static String status(Stage stage) {
    return switch(stage) {
      case CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
      case SPLADE, BGE_M3 -> SchemaFields.SPLADE_STATUS;
      case NER -> SchemaFields.NER_STATUS;
      default -> SchemaFields.EMBEDDING_STATUS;
    };
  }

  private void run(Stage stage, RunningRuntime runtime, Runnable entered, boolean fail, boolean fallback) throws Exception {
    run(stage,runtime,runtime.documentFieldOps(),entered,fail,fallback);
  }

  private void run(Stage stage, RunningRuntime runtime, DocumentFieldOps inputFields,
      Runnable entered, boolean fail, boolean fallback) throws Exception {
    var embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      entered.run(); List<String> texts = call.getArgument(0);
      return fallback ? List.of() : texts.stream().map(text -> fail ? null : new float[]{1,0,0,0}).toList();
    });
    when(embedding.embedDocument(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      entered.run(); return fail ? null : new float[]{1,0,0,0};
    });
    when(embedding.embedWithSpans(anyString(),any(int[][].class))).thenAnswer(call -> {
      entered.run();if(fail) throw new IllegalStateException("fixture late-chunk failure");
      return new io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding(new float[]{1,0,0,0},List.of(),1);
    });
    var sparse = mock(SpladeEncoder.class);
    when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
      entered.run(); if (fail || fallback) throw new IllegalStateException("fixture encoder failure");
      List<String> texts = call.getArgument(0); return texts.stream().map(text -> Map.of("token",1f)).toList();
    });
    when(sparse.encode(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      entered.run(); if (fail) throw new IllegalStateException("fixture encoder failure"); return Map.of("token",1f);
    });
    var ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call -> {
      entered.run(); if (fail) throw new IllegalStateException("fixture NER failure");
      List<String> texts = call.getArgument(0);
      return texts.stream().map(text -> new NerResult(List.of(text.contains("old") ? "old-entity" : "new-entity"),List.of(),List.of())).toList();
    });
    var bge = mock(BgeM3Encoder.class);
    when(bge.encodeBatch(anyList())).thenAnswer(call -> {
      entered.run(); if (fail || fallback) throw new IllegalStateException("fixture BGE failure");
      List<String> texts = call.getArgument(0); return texts.stream().map(text -> new BgeM3Output(new float[]{1,0,0,0},Map.of("token",1f))).toList();
    });
    when(bge.encode(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      entered.run(); if (fail) throw new IllegalStateException("fixture BGE failure");
      return new BgeM3Output(new float[]{1,0,0,0},Map.of("token",1f));
    });
    var fields=inputFields; var coordinator=runtime.indexingCoordinator(); var commits=runtime.commitOps();
    var log=LoggerFactory.getLogger(getClass()); var pace=IndexingPacing.unthrottled();
    var embedContext=new EmbeddingBackfillOps.BackfillContext(fields,coordinator,commits,
        mock(WorkerSignalBus.class),pace,()->embedding,()->true,()->true,100,log);
    switch(stage) {
      case EMBEDDING -> EmbeddingBackfillOps.processEmbeddingBackfill(embedContext);
      case CHUNK -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(embedContext);
      case SPLADE -> SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->sparse,()->true,100,true,false,log));
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->ner,()->true,100,log));
      case BGE_M3 -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(
          fields,coordinator,commits,mock(WorkerSignalBus.class),()->bge,()->true,100,true,false,log));
      case COMBINED, COMBINED_LATE -> CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->embedding,()->sparse,()->ner,()->true,()->true,100,log,
          false,false,stage==Stage.COMBINED_LATE,50,new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,new WindowedEmbedProgress()));
    }
  }
}
