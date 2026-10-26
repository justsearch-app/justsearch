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

  private static final String LEGACY = "anchor anchor a" + "\uD83D\uDE00".repeat(1000)
      + "anchor anchor " + "\uD83D\uDE00".repeat(2000);

  /** Offsets captured by the unchanged f8e16e326bf3 splitter, not by the repaired implementation. */
  private void seedLegacy(RunningRuntime runtime) throws Exception {
    seed(runtime, "legacy-parent", LEGACY);
    runtime.indexingCoordinator().updateDocument("legacy-parent", Map.of(
        SchemaFields.EMBEDDING_STATUS, "FAILED", SchemaFields.SPLADE_STATUS, "FAILED", SchemaFields.NER_STATUS, "FAILED"));
    var rows = new ArrayList<int[]>();
    try (var stream = getClass().getResourceAsStream("/unicode/v1-chunks.tsv")) {
      assertNotNull(stream);
      for (String line : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
        rows.add(Arrays.stream(line.split("\t")).mapToInt(Integer::parseInt).toArray());
      }
    }
    for (int ordinal : new int[] {1, 2, 0, 3}) {
      int[] row = rows.get(ordinal);
      String id = ordinal == 1 ? "bad-0" : ordinal == 2 ? "bad-1" : "safe-" + ordinal;
      var fields = new HashMap<String, Object>();
      fields.put(SchemaFields.DOC_ID, id); fields.put(SchemaFields.DOC_UID, id + "-uid");
      fields.put(SchemaFields.PATH, "legacy-parent"); fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, "legacy-parent");
      fields.put(SchemaFields.CHUNK_START_CHAR, row[1]); fields.put(SchemaFields.CHUNK_END_CHAR, row[2]);
      fields.put(SchemaFields.CHUNK_INDEX, row[0]); fields.put(SchemaFields.CHUNK_TOTAL, rows.size());
      fields.put(SchemaFields.CHUNK_CONTENT, LEGACY.substring(row[1], row[2]));
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(LEGACY));
      fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"); fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
      fields.put(SchemaFields.SPLADE_STATUS, "PENDING"); fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
      fields.put(SchemaFields.CHUNK_VECTOR, new float[] {0, 1, 0, 0});
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    commit(runtime);
  }

  @Test
  void legacyReadOmissionPreservesSafeSiblingsAndSearchableArtifacts() throws Exception {
    try (var runtime = open()) {
      seedLegacy(runtime);
      assertNull(runtime.documentFieldOps().getDocumentContent("bad-0"));
      assertNull(runtime.documentFieldOps().getDocumentContent("bad-1"));
      assertEquals(LEGACY, runtime.documentFieldOps().getDocumentContent("legacy-parent"));
      assertEquals(LEGACY.substring(0, 1923), runtime.documentFieldOps().getDocumentContent("safe-0"));
      var batch = runtime.documentFieldOps().getDocumentContentBatch(List.of("bad-0", "bad-1", "safe-0", "legacy-parent"));
      assertEquals(Set.of("safe-0", "legacy-parent"), batch.keySet());
      var input = runtime.documentFieldOps().getEnrichmentInputs(List.of("bad-0", "safe-0"));
      assertNull(input.get("bad-0").content());
      assertNotNull(input.get("bad-0").fields().get(SchemaFields.CHUNK_END_CHAR), "failure publication retains geometry proof");
      assertEquals(batch.get("safe-0"), input.get("safe-0").content());
      var projected = runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.DOC_ID, "bad-0")),
          1, Set.of(SchemaFields.CHUNK_CONTENT), null, null);
      assertEquals(1, projected.hits().size(), "retained ranking document is still indexed");
      assertNull(projected.hits().getFirst().fields().get(SchemaFields.CHUNK_CONTENT));
      var rag = runtime.chunkSearchOps().searchChunksForDocs("anchor", Set.of("legacy-parent"), 10);
      assertFalse(rag.hits().isEmpty());
      for (var hit : rag.hits()) {
        if (hit.docId().equals("bad-0") || hit.docId().equals("bad-1")) {
          assertEquals("", hit.fields().get(SchemaFields.CHUNK_CONTENT),
              "RAG's existing missing-slice representation is an empty string");
        } else assertEquals(runtime.documentFieldOps().getDocumentContent(hit.docId()),
            hit.fields().get(SchemaFields.CHUNK_CONTENT));
      }
      assertFalse(postings("bad-0").isEmpty(), "the old malformed slice has real searchable postings");
    }
  }

  @ParameterizedTest(name="legacyFailuresDrainBadHeadsWithoutEncodingTheirText({0})")
  @EnumSource(value=Stage.class, names={"CHUNK", "SPLADE", "COMBINED", "BGE_M3"})
  void legacyFailuresDrainBadHeadsWithoutEncodingTheirText(Stage stage) throws Exception {
    try (var runtime = open()) {
      seedLegacy(runtime);
      String status = stage == Stage.CHUNK ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.SPLADE_STATUS;
      String retry = status.replace("_status", "_retry_count");
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      assertEquals("bad-0", runtime.documentFieldOps().queryDocIdsByField(status, "PENDING", 1).getFirst());
      for (int pass = 0; pass < 16; pass++) {
        run(stage, runtime, runtime.documentFieldOps(), calls::incrementAndGet, false, false, 1, true);
        commit(runtime);
        for (String id : List.of("bad-0", "bad-1")) {
          assertNotEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(id, status));
        }
        if (runtime.documentFieldOps().queryDocIdsByField(status, "PENDING", 10).isEmpty()) break;
      }
      for (String id : List.of("bad-0", "bad-1")) {
        assertEquals("FAILED", runtime.documentFieldOps().getDocumentField(id, status));
        assertEquals("3", runtime.documentFieldOps().getDocumentField(id, retry));
      }
      for (String id : List.of("safe-0", "safe-3")) {
        assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(id, status), "safe tail beyond batchSize must run");
      }
      assertTrue(calls.get() > 0, "safe work really reached an encoder fake");
    }
    try (var runtime = open()) {
      String status = stage == Stage.CHUNK ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.SPLADE_STATUS;
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("bad-0", status));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("safe-0", status));
    }
  }

  @ParameterizedTest(name="legacyProofRaceCannotPublishFailure({0},{1})")
  @org.junit.jupiter.params.provider.CsvSource({"CHUNK,geometry", "CHUNK,revision", "SPLADE,geometry", "SPLADE,revision",
      "COMBINED,geometry", "COMBINED,revision", "BGE_M3,geometry", "BGE_M3,revision"})
  void legacyProofRaceCannotPublishFailure(Stage stage, String race) throws Exception {
    try (var runtime = open()) {
      seedLegacy(runtime);
      var once = new java.util.concurrent.atomic.AtomicBoolean();
      var fields = mock(DocumentFieldOps.class, withSettings().spiedInstance(runtime.documentFieldOps()).defaultAnswer(call -> {
        Object captured = call.callRealMethod();
        if (call.getMethod().getName().equals("getEnrichmentInputs") && once.compareAndSet(false, true)) {
          if (race.equals("geometry")) {
            assertTrue(runtime.indexingCoordinator().updateDocument("bad-0", Map.of(
                SchemaFields.CHUNK_END_CHAR, 3656, SchemaFields.CHUNK_CONTENT, LEGACY.substring(1731, 3656))));
          } else {
            String changed = LEGACY.replace('a', 'b');
            assertTrue(runtime.indexingCoordinator().updateDocument("legacy-parent", Map.of(
                SchemaFields.CONTENT, changed, SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(changed))));
          }
          commit(runtime);
        }
        return captured;
      }));
      assertFalse(run(stage, runtime, fields, () -> fail("unsafe text must not reach an encoder"), false, false, 1, true),
          "a rejected comparison proof cannot claim durable advancement");
      assertTrue(once.get());
      commit(runtime);
      for (String status : List.of(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.SPLADE_STATUS)) {
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("bad-0", status));
        assertEquals("0", runtime.documentFieldOps().getDocumentField("bad-0", status.replace("_status", "_retry_count")));
      }
      assertEquals(race.equals("geometry") ? "3656" : "3654",
          runtime.documentFieldOps().getDocumentField("bad-0", SchemaFields.CHUNK_END_CHAR));
    }
  }

  @Test
  void legacyMatchingProofRmwAndRenamePreservePostingsAndDenseVector() throws Exception {
    Map<String, List<Integer>> original;
    long norm;
    try (var runtime = open()) {
      seedLegacy(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument("bad-0", Map.of(
          SchemaFields.SPLADE, Map.of("oldweight", 2f), SchemaFields.SPLADE_STATUS, "COMPLETED")));
      commit(runtime);
      original = postings("bad-0"); norm = norm("bad-0");
      assertFalse(original.isEmpty());
      assertEquals(1, runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.SPLADE, "oldweight")),
          10, Set.of(), null, null).hits().size());
      for (int attempt = 1; attempt <= 3; attempt++) {
        var proof = runtime.documentFieldOps().getEnrichmentInputs(List.of("bad-0")).get("bad-0");
        assertTrue(runtime.indexingCoordinator().updateDocumentIf("bad-0", proof,
            EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(attempt - 1)));
        commit(runtime);
        assertEquals(original, postings("bad-0"), "same-analyzer terms, frequencies and positions survive exact legacy reconstruction");
        assertEquals(norm, norm("bad-0"));
        assertDenseVector();
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("bad-0", SchemaFields.SPLADE_STATUS),
            "existing reset-status policy drops omitted sparse weights and resets completed status");
        assertEquals(0, runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.SPLADE, "oldweight")),
            10, Set.of(), null, null).hits().size());
      }
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("bad-0", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("3", runtime.documentFieldOps().getDocumentField("bad-0", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(5, runtime.indexingCoordinator().updateDocumentPaths("legacy-parent", "renamed-parent"));
      commit(runtime);
      assertEquals(original, postings("bad-0")); assertEquals(norm, norm("bad-0")); assertDenseVector();
      assertEquals("renamed-parent", runtime.documentFieldOps().getDocumentField("bad-0", SchemaFields.PARENT_DOC_ID));
    }
    try (var runtime = open()) {
      assertEquals(LEGACY, runtime.documentFieldOps().getDocumentContent("renamed-parent"));
      assertEquals(original, postings("bad-0")); assertEquals(norm, norm("bad-0")); assertDenseVector();
    }
  }

  private Map<String, List<Integer>> postings(String id) throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = new org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
        DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
      int doc = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc;
      var result = new TreeMap<String, List<Integer>>();
      var terms = org.apache.lucene.index.MultiTerms.getTerms(reader, SchemaFields.CHUNK_CONTENT);
      if (terms == null) return result;
      var iterator = terms.iterator();
      for (var term = iterator.next(); term != null; term = iterator.next()) {
        var postings = iterator.postings(null, org.apache.lucene.index.PostingsEnum.ALL);
        if (postings.advance(doc) != doc) continue;
        var positions = new ArrayList<Integer>();
        positions.add(postings.freq());
        if (terms.hasPositions()) for (int i = 0; i < postings.freq(); i++) positions.add(postings.nextPosition());
        result.put(term.utf8ToString(), positions);
      }
      return result;
    }
  }

  private long norm(String id) throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = new org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
        DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
      int doc = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc;
      var leaf = reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc, reader.leaves()));
      var norms = leaf.reader().getNormValues(SchemaFields.CHUNK_CONTENT);
      if (norms == null) return -1;
      assertTrue(norms.advanceExact(doc - leaf.docBase));
      return norms.longValue();
    }
  }

  private void assertDenseVector() throws Exception {
    try (var fs = FSDirectory.open(directory); var reader = new org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper(
        DirectoryReader.open(fs), SchemaFields.SOFT_DELETE)) {
      int doc = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, "bad-0")), 1).scoreDocs[0].doc;
      var leaf = reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(doc, reader.leaves()));
      var vector = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
      assertNotNull(vector);
      var iterator = vector.iterator(); assertEquals(doc - leaf.docBase, iterator.advance(doc - leaf.docBase));
      assertArrayEquals(new float[] {0, 1, 0, 0}, vector.vectorValue(iterator.index()));
    }
  }

  private static void safeEncoderInputs(List<String> texts) {
    for (String text : texts) {
      assertFalse(Character.isLowSurrogate(text.charAt(0)), "unsafe legacy start reached encoder");
      assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)), "unsafe legacy end reached encoder");
    }
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
    run(stage, runtime, inputFields, entered, fail, fallback, 100, false);
  }

  private boolean run(Stage stage, RunningRuntime runtime, DocumentFieldOps inputFields,
      Runnable entered, boolean fail, boolean fallback, int batchSize, boolean legacy) throws Exception {
    var embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0); if (legacy) safeEncoderInputs(texts); entered.run();
      return fallback ? List.of() : texts.stream().map(text -> fail ? null : new float[]{1,0,0,0}).toList();
    });
    when(embedding.embedDocument(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      if (legacy) safeEncoderInputs(List.of(call.getArgument(0))); entered.run(); return fail ? null : new float[]{1,0,0,0};
    });
    when(embedding.embedWithSpans(anyString(),any(int[][].class))).thenAnswer(call -> {
      entered.run();if(fail) throw new IllegalStateException("fixture late-chunk failure");
      return new io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding(new float[]{1,0,0,0},List.of(),1);
    });
    var sparse = mock(SpladeEncoder.class);
    when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
      if (legacy) safeEncoderInputs(call.getArgument(0)); entered.run(); if (fail || fallback) throw new IllegalStateException("fixture encoder failure");
      List<String> texts = call.getArgument(0); return texts.stream().map(text -> Map.of("token",1f)).toList();
    });
    when(sparse.encode(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      if (legacy) safeEncoderInputs(List.of(call.getArgument(0))); entered.run(); if (fail) throw new IllegalStateException("fixture encoder failure"); return Map.of("token",1f);
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
      if (legacy) safeEncoderInputs(call.getArgument(0)); entered.run(); if (fail || fallback) throw new IllegalStateException("fixture BGE failure");
      List<String> texts = call.getArgument(0); return texts.stream().map(text -> new BgeM3Output(new float[]{1,0,0,0},Map.of("token",1f))).toList();
    });
    when(bge.encode(anyString(),any(InferenceRequest.class))).thenAnswer(call -> {
      if (legacy) safeEncoderInputs(List.of(call.getArgument(0))); entered.run(); if (fail) throw new IllegalStateException("fixture BGE failure");
      return new BgeM3Output(new float[]{1,0,0,0},Map.of("token",1f));
    });
    var fields=inputFields; var coordinator=runtime.indexingCoordinator(); var commits=runtime.commitOps();
    var log=LoggerFactory.getLogger(getClass()); var pace=IndexingPacing.unthrottled();
    var embedContext=new EmbeddingBackfillOps.BackfillContext(fields,coordinator,commits,
        mock(WorkerSignalBus.class),pace,()->embedding,()->true,()->true,batchSize,log);
    return switch(stage) {
      case EMBEDDING -> EmbeddingBackfillOps.processEmbeddingBackfill(embedContext).docsProcessed() > 0;
      case CHUNK -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(embedContext).docsProcessed() > 0;
      case SPLADE -> SpladeBackfillOps.processSpladeBackfill(new SpladeBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->sparse,()->true,batchSize,true,legacy,log)).docsProcessed() > 0;
      case NER -> NerBackfillOps.processNerBackfill(new NerBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->ner,()->true,batchSize,log)).docsProcessed() > 0;
      case BGE_M3 -> BgeM3BackfillOps.processBgeM3Backfill(new BgeM3BackfillOps.BackfillContext(
          fields,coordinator,commits,mock(WorkerSignalBus.class),()->bge,()->true,batchSize,true,legacy,log)).docsProcessed() > 0;
      case COMBINED, COMBINED_LATE -> CombinedEnrichmentBackfillOps.processCombinedBackfill(new CombinedEnrichmentBackfillOps.BackfillContext(
          fields,coordinator,commits,pace,()->embedding,()->sparse,()->ner,()->true,()->true,batchSize,log,
          legacy,legacy,stage==Stage.COMBINED_LATE,legacy ? 1 : 50,new ArrayDeque<>(),new ArrayDeque<>(),new int[]{0},()->false,()->false,()->false,new WindowedEmbedProgress())).progressed();
    };
  }
}
