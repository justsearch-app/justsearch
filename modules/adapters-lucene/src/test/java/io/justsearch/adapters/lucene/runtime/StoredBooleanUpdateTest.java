/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Stored booleans must survive every entry point sharing the read-modify-write mapper. */
class StoredBooleanUpdateTest extends LuceneExecutorTestBase {
  private static final String DOC = "/docs/report.pdf";
  private static final String MOVED = "/docs/moved.pdf";
  private static final String CONTENT = "Readable extraction text. ".repeat(400);
  private static final List<String> BOOLEANS = List.of(SchemaFields.CONTENT_TRUNCATED,
      SchemaFields.VDU_PROCESSED, "ocr_present");

  @TempDir Path directory;

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
      "unconditional,true", "conditional,true", "stale,true", "batch,true",
      "enrichment-batch,true", "enrichment-stale,true", "status,true", "declaration,true", "move,true", "migration,true",
      "unconditional,false", "conditional,false", "stale,false", "batch,false",
      "enrichment-batch,false", "enrichment-stale,false", "status,false", "declaration,false", "move,false", "migration,false",
      "unconditional,absent", "conditional,absent", "stale,absent", "batch,absent",
      "enrichment-batch,absent", "enrichment-stale,absent", "status,absent", "declaration,absent", "move,absent", "migration,absent"
  })
  void bc1StoredBooleansAndOtherScalarFieldsSurviveEveryUpdateAndReopen(String path, String state) throws Exception {
    String finalId = path.equals("move") ? MOVED : DOC;
    try (var runtime = open()) {
      seed(runtime, state);
      // Fresh ingestion's declaration must work without a caller refresh between the writes.
      if (!path.equals("declaration")) refresh(runtime);
      switch (path) {
        case "unconditional" -> assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.TITLE, "updated")));
        case "conditional" -> assertTrue(runtime.indexingCoordinator().updateDocumentIf(DOC,
            Map.of(SchemaFields.VDU_RETRY_COUNT, "2"), Map.of(SchemaFields.TITLE, "updated")));
        case "stale" -> assertFalse(runtime.indexingCoordinator().updateDocumentIf(DOC,
            Map.of(SchemaFields.VDU_RETRY_COUNT, "1"), Map.of(SchemaFields.TITLE, "refused")));
        case "batch" -> assertEquals(1, runtime.indexingCoordinator().updateDocumentsBatch(
            List.of(Map.entry(DOC, Map.of(SchemaFields.TITLE, "updated")))).updatedCount());
        case "enrichment-batch", "enrichment-stale" -> {
          var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of(DOC));
          if (path.equals("enrichment-stale")) {
            assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.CONTENT, CONTENT + "changed")));
          }
          assertEquals(path.equals("enrichment-stale") ? 0 : 1,
              runtime.indexingCoordinator().updateDocumentsBatchIf(
                  List.of(Map.entry(DOC, Map.of(SchemaFields.NER_STATUS, "FAILED"))), inputs).updatedCount());
        }
        case "status" -> assertTrue(runtime.indexingCoordinator().updateDocumentIf(DOC,
            Map.of(SchemaFields.VDU_RETRY_COUNT, "2"), Map.of(SchemaFields.VDU_STATUS, "PENDING")));
        case "declaration" -> {
          int total = ChunkSplitter.splitWithMetadata(CONTENT, ChunkSplitter.DEFAULT_CHUNK_TOKENS,
              ChunkSplitter.DEFAULT_OVERLAP_TOKENS).size();
          assertTrue(runtime.indexingCoordinator().updateDocument(DOC,
              Map.of(SchemaFields.CHUNK_TOTAL, total, SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(CONTENT))));
          seedLegacyChunks(runtime);
        }
        case "move" -> assertEquals(1, runtime.indexingCoordinator().updateDocumentPaths(DOC, MOVED));
        case "migration" -> {
          seedLegacyChunks(runtime);
          refresh(runtime);
          assertTrue(runtime.documentFieldOps().isUnmodified(DOC, 123L));
          refresh(runtime);
          assertNotNull(runtime.documentFieldOps().getDocumentField(DOC, SchemaFields.CHUNK_TOTAL));
        }
        default -> throw new AssertionError(path);
      }
      refresh(runtime);
      assertState(runtime, finalId, state);
      if (path.equals("stale")) assertNull(runtime.documentFieldOps().getDocumentField(DOC, SchemaFields.TITLE));
    }
    try (var runtime = open()) {
      assertState(runtime, finalId, state);
    }
  }

  @Test
  void bcn1IncomingBooleanRepresentationsAndNullRemovalKeepTheirMeaning() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "absent");
      for (Object value : List.of(true, false, "true", "false", 1, 0, 7L, -1L)) {
        boolean expected = value instanceof Number number ? number.longValue() != 0 : Boolean.parseBoolean(value.toString());
        assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(
            SchemaFields.CONTENT_TRUNCATED, value, SchemaFields.VDU_PROCESSED, value, "ocr_present", value)));
        refresh(runtime);
        assertState(runtime, DOC, Boolean.toString(expected));
        assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.TITLE, "unrelated")));
        refresh(runtime);
        assertState(runtime, DOC, Boolean.toString(expected));
      }
      var removal = new HashMap<String, Object>();
      BOOLEANS.forEach(field -> removal.put(field, null));
      assertTrue(runtime.indexingCoordinator().updateDocument(DOC, removal));
      refresh(runtime);
      assertState(runtime, DOC, "absent");
    }
    try (var runtime = open()) {
      assertState(runtime, DOC, "absent");
    }
  }

  @Test
  void bcn3HistoricalFalseIsNotRepairedUntilReextractionWritesTrue() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "false");
      refresh(runtime);
    }
    try (var runtime = open()) {
      assertState(runtime, DOC, "false");
      assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.TITLE, "unrelated")));
      refresh(runtime);
      assertState(runtime, DOC, "false");
      seed(runtime, "true");
      refresh(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument(DOC, Map.of(SchemaFields.TITLE, "after reextraction")));
      refresh(runtime);
      assertState(runtime, DOC, "true");
    }
    try (var runtime = open()) {
      assertState(runtime, DOC, "true");
    }
  }

  @Test
  void bcn2KeywordChunkFlagRemainsTrueThroughRewriteAndReopen() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "true");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk", SchemaFields.DOC_UID, "chunk-uid",
          SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, DOC,
          SchemaFields.CHUNK_START_CHAR, 0L, SchemaFields.CHUNK_END_CHAR, 8L,
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(CONTENT))));
      refresh(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument("chunk", Map.of(SchemaFields.TITLE, "updated")));
      refresh(runtime);
      assertEquals("true", runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.IS_CHUNK));
      assertEquals("updated", runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.TITLE));
    }
    try (var runtime = open()) {
      assertEquals("true", runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.IS_CHUNK));
      assertEquals("updated", runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.TITLE));
    }
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String state) {
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, DOC);
    fields.put(SchemaFields.DOC_UID, "stable-uid");
    fields.put(SchemaFields.PATH, DOC);
    fields.put(SchemaFields.CONTENT, CONTENT);
    fields.put(SchemaFields.MODIFIED_AT, 123L);
    fields.put(SchemaFields.VDU_RETRY_COUNT, 2L);
    fields.put(SchemaFields.EXTRACTION_QUALITY_SCORE, 0.875);
    fields.put(SchemaFields.MIME, "application/pdf");
    fields.put(SchemaFields.IS_CHUNK, "false");
    if (!state.equals("absent")) BOOLEANS.forEach(field -> fields.put(field, Boolean.valueOf(state)));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void seedLegacyChunks(RunningRuntime runtime) {
    int total = ChunkSplitter.splitWithMetadata(CONTENT, ChunkSplitter.DEFAULT_CHUNK_TOKENS,
        ChunkSplitter.DEFAULT_OVERLAP_TOKENS).size();
    assertTrue(total > 0);
    for (int i = 0; i < total; i++) runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk-" + i, SchemaFields.DOC_UID, "chunk-uid-" + i,
        SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, DOC,
        SchemaFields.CHUNK_INDEX, i, SchemaFields.CHUNK_TOTAL, total,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(CONTENT))));
  }

  private static void assertState(RunningRuntime runtime, String id, String state) throws Exception {
    String expected = state.equals("absent") ? null : state.equals("true") ? "1" : "0";
    for (String field : BOOLEANS) {
      assertEquals(expected, runtime.documentFieldOps().getDocumentField(id, field), field);
      runtime.readPathOps().withSearcher(searcher -> {
        int doc = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc;
        var values = MultiDocValues.getNumericValues(searcher.getIndexReader(), field);
        if (expected == null) assertTrue(values == null || !values.advanceExact(doc), field);
        else {
          assertNotNull(values, field);
          assertTrue(values.advanceExact(doc), field);
          assertEquals(Long.parseLong(expected), values.longValue(), field);
        }
        return null;
      });
    }
    assertEquals("123", runtime.documentFieldOps().getDocumentField(id, SchemaFields.MODIFIED_AT));
    assertEquals("2", runtime.documentFieldOps().getDocumentField(id, SchemaFields.VDU_RETRY_COUNT));
    assertEquals("0.875", runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_QUALITY_SCORE));
    assertEquals("application/pdf", runtime.documentFieldOps().getDocumentField(id, SchemaFields.MIME));
    assertEquals("false", runtime.documentFieldOps().getDocumentField(id, SchemaFields.IS_CHUNK));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
