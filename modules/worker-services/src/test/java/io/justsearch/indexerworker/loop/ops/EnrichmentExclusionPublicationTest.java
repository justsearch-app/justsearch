/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.services.WorkerIngestService;
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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/** Policy saves during real backfill inference must fence all publication lanes. */
final class EnrichmentExclusionPublicationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  enum Stage { EMBEDDING, CHUNK, SPLADE, NER, COMBINED, COMBINED_LATE, BGE_M3, SPLADE_CHUNK, BGE_CHUNK, COMBINED_CHUNK }
  private static final String OLD = "old body ".repeat(20);

  @ParameterizedTest(name="exclusionSavedDuringInferencePublishesNothing({0})") @EnumSource(Stage.class)
  void exclusionSavedDuringInferencePublishesNothing(Stage stage) throws Exception {
    race(stage, false, false, "[\"private.png\"]");
  }

  @ParameterizedTest(name="excludedInferenceFailureCannotSpendRetries({0})") @EnumSource(Stage.class)
  void excludedInferenceFailureCannotSpendRetries(Stage stage) throws Exception {
    race(stage, true, true, "[\"*.png\"]");
  }

  @ParameterizedTest(name="excludedFallbackCannotPublish({0})") @EnumSource(value=Stage.class, names={"EMBEDDING","CHUNK","SPLADE","BGE_M3"})
  void excludedFallbackCannotPublish(Stage stage) throws Exception {
    race(stage, false, true, "[\"private/\"]");
  }

  @ParameterizedTest(name="sourcePathDifferentFromDocumentIdIsExcluded({0})")
  @EnumSource(value=Stage.class, names={"EMBEDDING","COMBINED_CHUNK"})
  void sourcePathDifferentFromDocumentIdIsExcluded(Stage stage) throws Exception {
    race(stage, false, false, "[\"private.png\"]", true);
  }

  private void race(Stage stage, boolean fail, boolean fallback, String patterns) throws Exception {
    race(stage, fail, fallback, patterns, false);
  }

  private void race(Stage stage, boolean fail, boolean fallback, String patterns, boolean opaqueId) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(config("[]"));
    ConfigStore.setGlobal(installed);
    try (var runtime = open()) {
      var folder = java.nio.file.Files.createDirectories(directory.resolve("private"));
      String source = java.nio.file.Files.writeString(folder.resolve("private.png"), "source").toString();
      String parent = opaqueId ? "opaque-parent-id" : source;
      String stable = java.nio.file.Files.writeString(directory.resolve("public.txt"), "source").toString();
      seed(runtime, parent, OLD, opaqueId ? source : null);
      if (chunkStage(stage) || stage == Stage.COMBINED_LATE) seedChunk(runtime, parent, OLD);
      if (stage != Stage.CHUNK) seed(runtime, stable, OLD);
      new WorkerIngestService(null,null,null,IndexingPacing.unthrottled(),null,null,runtime,runtime,null,0L)
          .useExclusionRoots(() -> List.of(directory));
      commit(runtime);
      if (opaqueId) assertEquals(source, runtime.documentFieldOps().getDocumentField(parent, SchemaFields.PATH));
      var inferred = new java.util.concurrent.atomic.AtomicBoolean();
      run(stage, runtime, () -> {
        inferred.set(true);
        installed.update(config(patterns));
      }, fail, fallback);
      assertTrue(inferred.get(), "fixture must reach inference before saving the policy");
      commit(runtime);
      String target = chunkStage(stage) ? "child" : parent;
      try (var fs = FSDirectory.open(directory.resolve("index")); var reader = DirectoryReader.open(fs)) {
        var searcher = new IndexSearcher(reader);
        var hit = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, target)), 1);
        assertEquals(1, hit.scoreDocs.length);
        var stored = reader.storedFields().document(hit.scoreDocs[0].doc);
        assertEquals("PENDING", stored.get(status(stage)), "excluded inference must never complete or fail");
        assertEquals("0", stored.get(status(stage).replace("_status", "_retry_count")), "excluded work spends no retry");
        for (String unchangedStage : chunkStage(stage) ? List.of("chunk_embedding","splade") : List.of("embedding","splade","ner")) {
          assertEquals("PENDING",stored.get(unchangedStage+"_status"),"no excluded stage may publish");
          assertEquals("0",stored.get(unchangedStage+"_retry_count"),"no excluded stage may spend retries");
        }
        assertNull(stored.get(SchemaFields.ENTITY_PERSONS_RAW));
        assertEquals(OLD, runtime.documentFieldOps().getDocumentContent(target));
        for (String field : List.of(SchemaFields.VECTOR, SchemaFields.CHUNK_VECTOR, SchemaFields.SPLADE)) {
          assertEquals(0, searcher.count(new BooleanQuery.Builder()
              .add(new TermQuery(new Term(SchemaFields.DOC_ID,target)), BooleanClause.Occur.FILTER)
              .add((field.equals(SchemaFields.SPLADE) ? new TermQuery(new Term(field,"token")) : new FieldExistsQuery(field)), BooleanClause.Occur.FILTER).build()), "no excluded " + field);
        }
      }
      assertFalse(runtime.documentFieldOps().queryDocIdsByField(status(stage), "PENDING", 100).contains(target),
          "normal exclusion selection must leave this work pending");
      if (!fail && stage != Stage.CHUNK) assertEquals("COMPLETED",
          runtime.documentFieldOps().getDocumentField(stable, stage == Stage.COMBINED_CHUNK ? SchemaFields.EMBEDDING_STATUS : status(stage)), "nonexcluded work still publishes");
      installed.update(config("[]"));
      run(stage, runtime, () -> {}, false, false);
      commit(runtime);
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(target, status(stage)),
          "removing the exclusion makes pending work runnable normally");
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  private static ResolvedConfig config(String patterns) {
    return ResolvedConfig.builder().putDefault("justsearch.ui.exclude_patterns",patterns).build();
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
        .atPath(directory.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, String id, String content) {
    seed(runtime, id, content, null);
  }

  private void seed(RunningRuntime runtime, String id, String content, String source) {
    Map<String,Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID,id); fields.put(SchemaFields.DOC_UID,id+"-uid");
    if (source != null) fields.put(SchemaFields.PATH,source);
    fields.put(SchemaFields.CONTENT,content); fields.put(SchemaFields.CONTENT_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.SOURCE_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.IS_CHUNK,"false");
    for (String stage : List.of("embedding","splade","ner")) {
      fields.put(stage+"_status","PENDING"); fields.put(stage+"_retry_count","0");
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void seedChunk(RunningRuntime runtime, String parent, String content) {
    Map<String,Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID,"child"); fields.put(SchemaFields.DOC_UID,"child-uid");
    fields.put(SchemaFields.IS_CHUNK,"true"); fields.put(SchemaFields.PARENT_DOC_ID,parent);
    fields.put(SchemaFields.CHUNK_START_CHAR,0); fields.put(SchemaFields.CHUNK_END_CHAR,content.length());
    fields.put(SchemaFields.CHUNK_INDEX,0); fields.put(SchemaFields.CHUNK_TOTAL,1);
    fields.put(SchemaFields.CHUNK_CONTENT,content);
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256,ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.SPLADE_STATUS,"PENDING"); fields.put(SchemaFields.SPLADE_RETRY_COUNT,"0");
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS,"PENDING"); fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT,"0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(CommitReason.TIMER); runtime.commitOps().maybeRefreshBlocking();
  }

  private static boolean chunkStage(Stage stage) {
    return stage == Stage.CHUNK || stage == Stage.SPLADE_CHUNK || stage == Stage.BGE_CHUNK || stage == Stage.COMBINED_CHUNK;
  }

  private static String status(Stage stage) {
    return switch(stage) {
      case CHUNK, COMBINED_CHUNK -> SchemaFields.CHUNK_EMBEDDING_STATUS;
      case SPLADE, BGE_M3, SPLADE_CHUNK, BGE_CHUNK -> SchemaFields.SPLADE_STATUS;
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
      case SPLADE, SPLADE_CHUNK -> SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->sparse,()->true,100,true,stage==Stage.SPLADE_CHUNK,log));
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->ner,()->true,100,log));
      case BGE_M3, BGE_CHUNK -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(
          fields,coordinator,commits,mock(WorkerSignalBus.class),()->bge,()->true,100,true,stage==Stage.BGE_CHUNK,log));
      case COMBINED, COMBINED_LATE, COMBINED_CHUNK -> CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->embedding,()->sparse,()->ner,()->true,()->true,100,log,
          stage==Stage.COMBINED_CHUNK,stage==Stage.COMBINED_CHUNK,stage==Stage.COMBINED_LATE,50,new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,new WindowedEmbedProgress()));
    }
  }
}
