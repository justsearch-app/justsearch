/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.IndexFingerprint.*;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real catalog, directory, projections and mutation entry points; no model inference. */
class DoubleFieldIntegrationTest extends LuceneExecutorTestBase {
  private static final String QUALITY = SchemaFields.EXTRACTION_QUALITY_SCORE;
  @TempDir Path tempDir;

  private FieldCatalogDef catalog() {
    return new JustSearchConfigurationLoader().loadFieldCatalog();
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(catalog()).atPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private String path(String id) {
    return tempDir.resolve(id + ".txt").toString();
  }

  private void index(RunningRuntime runtime, String id, Object quality) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id);
    fields.put(SchemaFields.PATH, path(id));
    fields.put(SchemaFields.CONTENT, "searchable original content");
    fields.put(SchemaFields.CONTENT_PREVIEW, "searchable original");
    fields.put(SchemaFields.SIZE_BYTES, 42L);
    fields.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
    if (quality != null) fields.put(QUALITY, quality);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    refresh(runtime);
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void assertReads(RunningRuntime runtime, String id, String expected) {
    var fields = runtime.documentFieldOps();
    assertEquals(expected, fields.getDocumentField(id, QUALITY), "single " + id);
    assertEquals(expected, fields.getDocumentFieldsBatch(List.of(id), Set.of(QUALITY))
        .get(id).get(QUALITY), "batch " + id);
    Query byId = new TermQuery(new Term(SchemaFields.DOC_ID, id));
    var projected = runtime.readPathOps().search(byId, 10, Set.of(QUALITY), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, projected.hits().size());
    assertEquals(expected, projected.hits().getFirst().fields().get(QUALITY), "search " + id);
    var all = runtime.readPathOps().search(byId, 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    if (runtime.schema().fieldMapper().fieldDef(QUALITY).stored) {
      assertEquals(expected, all.hits().getFirst().fields().get(QUALITY), "all stored " + id);
    }
    var listing = runtime.folderBrowseEngine().listFolderFiles(
        tempDir + File.separator, 0, Set.of(SchemaFields.DOC_ID, QUALITY));
    var file = listing.files().stream().filter(hit -> id.equals(hit.docId())).findFirst().orElseThrow();
    assertEquals(expected, file.fields().get(QUALITY), "folder " + id);
  }

  @Test
  void preciseDoubleAndMissingSurviveEveryReadAndReopenAcrossSegments() throws Exception {
    try (var runtime = open()) {
      index(runtime, "legacy", null);
      index(runtime, "zero", 0.0);
      index(runtime, "fraction", 0.1);
      index(runtime, "one", 1.0);
      runtime.readPathOps().withSearcher(searcher -> {
        assertTrue(searcher.getIndexReader().leaves().size() > 1, "exercise mixed segments");
        return null;
      });
      assertMixedReads(runtime);
    }
    try (var runtime = open()) {
      assertMixedReads(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument("legacy", Map.of(SchemaFields.NER_STATUS, "PENDING")));
      refresh(runtime);
      assertReads(runtime, "legacy", null);
    }
  }

  private void assertMixedReads(RunningRuntime runtime) {
    assertReads(runtime, "legacy", null);
    assertReads(runtime, "zero", "0.0");
    assertReads(runtime, "fraction", "0.1");
    assertReads(runtime, "one", "1.0");
  }

  @Test
  void exactNumericLookupAndLuceneDoubleSortRetainAdjacentFractions() throws Exception {
    try (var runtime = open()) {
      index(runtime, "missing", null);
      index(runtime, "one", 1.0);
      index(runtime, "upper", Math.nextUp(0.1));
      index(runtime, "lower", 0.1);
      index(runtime, "zero", 0.0);
      var fields = runtime.documentFieldOps();
      assertEquals(List.of("lower"), fields.queryDocIdsByField(QUALITY, "0.1", 10));
      assertEquals(List.of("lower"), fields.queryNonChunkDocIdsByField(QUALITY, "1e-1", 10));
      assertEquals(List.of("zero"), fields.queryDocIdsByField(QUALITY, "0.0", 10));
      assertEquals(List.of("upper"), fields.queryDocIdsByFieldOrThrow(QUALITY, Double.toString(Math.nextUp(0.1)), 10));
      assertEquals(List.of("lower"), fields.queryDocIdsByField(SchemaFields.PATH, path("lower"), 10));
      for (String invalid : List.of("NaN", "Infinity", "not numeric")) {
        assertTrue(fields.queryDocIdsByField(QUALITY, invalid, 10).isEmpty());
      }
      runtime.readPathOps().withSearcher(searcher -> {
        SortField quality = new SortField(QUALITY, SortField.Type.DOUBLE);
        quality.setMissingValue(Double.POSITIVE_INFINITY);
        var sorted = searcher.search(new MatchAllDocsQuery(), 10, new Sort(quality));
        List<String> ids = new ArrayList<>();
        for (var hit : sorted.scoreDocs) ids.add(searcher.storedFields().document(hit.doc).get(SchemaFields.DOC_ID));
        assertEquals(List.of("zero", "lower", "upper", "one", "missing"), ids);
        return null;
      });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.1", "0.0", "1.0"})
  void unrelatedUpdatesConditionalBatchAndMovePreserveDouble(String value) throws Exception {
    String id = "kept";
    try (var runtime = open()) {
      index(runtime, id, value);
      for (String status : List.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.NER_STATUS,
          SchemaFields.SPLADE_STATUS, SchemaFields.CHUNK_EMBEDDING_STATUS)) {
        assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of(status, "FAILED")));
        refresh(runtime);
        assertReads(runtime, id, value);
      }
      float[] vector = new float[catalog().vectorDimension()];
      vector[0] = 1.0f;
      assertTrue(runtime.indexingCoordinator().updateDocument(id,
          Map.of(SchemaFields.VECTOR, vector, SchemaFields.EMBEDDING_STATUS, "COMPLETED")));
      refresh(runtime);
      assertReads(runtime, id, value);
      assertTrue(runtime.indexingCoordinator().updateDocument(id,
          Map.of(SchemaFields.ENTITY_PERSONS_RAW, List.of("Alice"), SchemaFields.NER_STATUS, "COMPLETED")));
      refresh(runtime);
      assertReads(runtime, id, value);
      assertTrue(runtime.indexingCoordinator().updateDocument(id,
          Map.of(SchemaFields.SPLADE, Map.of("searchable", 1.0f), SchemaFields.SPLADE_STATUS, "COMPLETED")));
      refresh(runtime);
      assertReads(runtime, id, value);
      assertFalse(runtime.indexingCoordinator().updateDocumentIf(id,
          Map.of(SchemaFields.CONTENT, "stale"), Map.of(QUALITY, 0.9)));
      assertTrue(runtime.indexingCoordinator().updateDocumentIf(id,
          Map.of(SchemaFields.CONTENT, "searchable original content"), Map.of(SchemaFields.TITLE, "conditional")));
      refresh(runtime);
      var input = runtime.documentFieldOps().getEnrichmentInputs(List.of(id)).get(id);
      assertTrue(runtime.indexingCoordinator().updateDocumentIf(id, input, Map.of(SchemaFields.NER_STATUS, "FAILED")));
      var result = runtime.indexingCoordinator().updateDocumentsBatch(List.of(Map.entry(id, Map.of(SchemaFields.TITLE, "batch"))));
      assertEquals(1, result.updatedCount());
      refresh(runtime);
      assertReads(runtime, id, value);
      assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.DOC_ID, path(id))));
      refresh(runtime);
      assertEquals(1, runtime.indexingCoordinator().updateDocumentPaths(path(id), path("moved")));
      refresh(runtime);
    }
    try (var runtime = open()) {
      var ids = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PATH, path("moved"), 10);
      assertEquals(1, ids.size());
      assertReads(runtime, ids.getFirst(), value);
    }
  }

  @Test
  void explicitProjectionsAndPreviewDoNotAcquireQuality() throws Exception {
    try (var runtime = open()) {
      index(runtime, "projected", 0.1);
      var hit = runtime.readPathOps().search(new MatchAllDocsQuery(), 10,
          Set.of(SchemaFields.PATH, SchemaFields.CONTENT_PREVIEW), RuntimeSearchSort.RELEVANCE, null).hits().getFirst();
      assertFalse(hit.fields().containsKey(QUALITY));
      assertEquals("searchable original", hit.fields().get(SchemaFields.CONTENT_PREVIEW));
      assertFalse(runtime.folderBrowseEngine().listFolderFiles(tempDir + File.separator, 0, Set.of())
          .files().getFirst().fields().containsKey(QUALITY));
    }
  }

  @Test
  void docValuesOnlyDoubleIsDecodedDuringProjectionAndReconstruction() throws Exception {
    var fields = new ArrayList<>(catalog().fields());
    fields.replaceAll(def -> QUALITY.equals(def.id())
        ? new FieldCatalogDef.FieldDef(def.id(), def.type(), false, true, def.roles(), null, null, false)
        : def);
    var schema = IndexSchema.fromCatalog(new FieldCatalogDef(catalog().version(), fields));
    for (int phase = 0; phase < 2; phase++) {
      try (var runtime = schema.atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open()) {
        if (phase == 0) {
          index(runtime, "dv", 0.1);
          assertTrue(runtime.indexingCoordinator().updateDocument("dv", Map.of(SchemaFields.TITLE, "retained")));
          refresh(runtime);
        }
        assertReads(runtime, "dv", "0.1");
      }
    }
  }

  @Test
  void invalidDoubleWritesRetainTheDocumentAndFiniteNumericUpdates() throws Exception {
    try (var runtime = open()) {
      List<Object> invalid = Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY, "NaN", "Infinity", "bad", true);
      for (int i = 0; i < invalid.size(); i++) {
        String id = "invalid-" + i;
        index(runtime, id, invalid.get(i));
        assertReads(runtime, id, null);
        assertEquals("searchable original content", runtime.documentFieldOps().getDocumentContent(id));
        assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of(QUALITY, "0.1")));
        refresh(runtime);
        assertReads(runtime, id, "0.1");
        assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.TITLE, "unrelated")));
        refresh(runtime);
        assertReads(runtime, id, "0.1");
      }
    }
    try (var runtime = open()) {
      for (int i = 0; i < 8; i++) assertReads(runtime, "invalid-" + i, "0.1");
    }
  }

  @Test
  void fingerprintForUnchangedRealCatalogMatchesBaseline() {
    var catalog = catalog();
    var shapes = catalog.fields().stream().map(def -> new FieldShape(def.id(), def.type(), def.stored(),
        def.docValues(), def.multiValued(), def.analyzer(), def.roles(), def.vectorDimension(), null)).toList();
    var input = new Inputs(catalog.version(), shapes, "fixed-analyzers", "float32", new Hnsw(16, 200),
        new Chunking(500, 50, 100, 2000, "v1"), 4096, new Analysis("fixed-lucene", "fixed-icu"),
        ModelFingerprint.notConfigured(), ModelFingerprint.notConfigured(), ModelFingerprint.notConfigured());
    assertEquals("7a30d6336b017b65cc24a10abf7f6f0fd41fed3a9aa2836310733e124fd35651", IndexFingerprint.compute(input).orElseThrow());
  }
}
