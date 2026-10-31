/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RemovalAndRenameDurabilityTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String TEXT = "alpha beta";
  private static final String REV = ChunkParentRevision.sha256Hex(TEXT);

  private RunningRuntime open(Path path) {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    fields.removeIf(field -> SchemaFields.META_AUTHOR.equals(field.id()));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.META_AUTHOR, "keyword", true, true, List.of("filter"), null, null, true));
    fields.add(new FieldCatalogDef.FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    fields.add(new FieldCatalogDef.FieldDef("splade_status", "keyword", false, true, List.of("filter"), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef("splade_retry_count", "long", false, true, List.of(), null, null, false));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("removal-replay-test", fields))
        .atPath(path).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void parent(RunningRuntime runtime, String id, String uid) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, uid, SchemaFields.PATH, id,
        SchemaFields.CONTENT, TEXT, SchemaFields.COLLECTION, "docs",
        SchemaFields.META_AUTHOR, List.of("Alice", "Bob"))));
  }

  private void child(RunningRuntime runtime, String id, String parent, String revision) {
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, id); fields.put(SchemaFields.DOC_UID, "uid-" + id);
    fields.put(SchemaFields.PATH, parent); fields.put(SchemaFields.PARENT_DOC_ID, parent);
    fields.put(SchemaFields.IS_CHUNK, "true"); fields.put(SchemaFields.CHUNK_CONTENT, "alpha");
    fields.put(SchemaFields.CHUNK_START_CHAR, "0"); fields.put(SchemaFields.CHUNK_END_CHAR, "5");
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
    fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
    fields.put(SchemaFields.COLLECTION, "docs");
    fields.put(SchemaFields.META_AUTHOR, List.of("Alice", "Bob"));
    fields.put(SchemaFields.CHUNK_VECTOR, new float[] {0.25f, -0.5f, 0.75f, 1f});
    if (revision != null) fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }

  @Test void D34DestinationFamilyIsReplacedAndReplayPreservesSourceFamilyAfterReopen() {
    Path index = directory.resolve("index");
    try (var runtime = open(index)) {
      parent(runtime, "/a", "source"); child(runtime, "source-child", "/a", REV);
      parent(runtime, "/b", "destination"); child(runtime, "destination-child", "/b", REV);
      refresh(runtime);
      assertEquals(2, runtime.indexingCoordinator().updateDocumentPaths("/a", "/b"));
      refresh(runtime);
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("/a", "/b"));
      refresh(runtime);
      assertEquals(2, runtime.indexCountOps().docCount());
      assertEquals("source", runtime.documentFieldOps().getDocumentField("/b", SchemaFields.DOC_UID));
      assertNull(runtime.documentFieldOps().getDocumentField("destination-child", SchemaFields.DOC_UID));
    }
    try (var runtime = open(index)) {
      assertEquals("alpha", runtime.documentFieldOps().getEnrichmentInputs(List.of("source-child"))
          .get("source-child").content());
      assertEquals(List.of("Alice", "Bob"), runtime.documentFieldOps().getDocumentFieldValues("/b", SchemaFields.META_AUTHOR));
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict("/a"));
      for (var entry : Map.of(SchemaFields.DOC_UID, "uid-source-child", SchemaFields.PATH, "/b",
          SchemaFields.PARENT_DOC_ID, "/b", SchemaFields.CHUNK_START_CHAR, "0", SchemaFields.CHUNK_END_CHAR, "5",
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, REV, SchemaFields.COLLECTION, "docs").entrySet()) {
        assertEquals(entry.getValue(), runtime.documentFieldOps().getDocumentField("source-child", entry.getKey()));
      }
      assertEquals(List.of("Alice", "Bob"), runtime.documentFieldOps().getDocumentFieldValues("source-child", SchemaFields.META_AUTHOR));
      assertEquals(1, runtime.indexCountOps().countQueryOrThrow(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, "alpha"))));
      float[] vector = runtime.readPathOps().withSearcher(searcher -> {
        int global = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, "source-child")), 1).scoreDocs[0].doc;
        var leaves = searcher.getIndexReader().leaves();
        var leaf = leaves.get(org.apache.lucene.index.ReaderUtil.subIndex(global, leaves));
        var values = leaf.reader().getFloatVectorValues(SchemaFields.CHUNK_VECTOR);
        var iterator = values.iterator();
        assertEquals(global - leaf.docBase, iterator.advance(global - leaf.docBase));
        return values.vectorValue(iterator.index()).clone();
      });
      assertArrayEquals(new float[] {0.25f, -0.5f, 0.75f, 1f}, vector);
    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }

  @Test void D35ReplayAfterParentAndEarlierChildRewriteConvergesAfterReopen() throws Exception {
    Path index = directory.resolve("index");
    try (var runtime = open(index)) {
      parent(runtime, "/old", "source");
      child(runtime, "first", "/old", REV); child(runtime, "later", "/old", null);
      refresh(runtime);
      assertThrows(IndexRuntimeIOException.class, () -> runtime.indexingCoordinator().updateDocumentPaths("/old", "/new"));
      refresh(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("/old", SchemaFields.DOC_UID));
      assertEquals("/new", runtime.documentFieldOps().getDocumentField("first", SchemaFields.PARENT_DOC_ID));
      assertTrue(runtime.indexingCoordinator().updateDocument("later", Map.of(
          SchemaFields.CHUNK_CONTENT, "alpha", SchemaFields.CHUNK_PARENT_CONTENT_SHA256, REV)));
      refresh(runtime);
    }
    try (var runtime = open(index)) {
      assertEquals(1, runtime.indexingCoordinator().updateDocumentPaths("/old", "/new"));
      refresh(runtime);
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict("/old"));
      assertEquals(3, runtime.indexCountOps().countByIdAndChunksStrict("/new"));
      assertEquals("alpha", runtime.documentFieldOps().getEnrichmentInputs(List.of("later")).get("later").content());
    }
  }

  @Test void D36RenameTraversesMoreThanTenThousandChildren() throws Exception {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/old", "source");
      for (int i = 0; i < 10001; i++) child(runtime, "child-" + i, "/old", REV);
      refresh(runtime);
      assertEquals(10002, runtime.indexingCoordinator().updateDocumentPaths("/old", "/new"));
      refresh(runtime);
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict("/old"));
      assertEquals(10002, runtime.indexCountOps().countByIdAndChunksStrict("/new"));
    }
  }

  @Test void D38PrefixRemovalAndProofShareLegacyBackingPathsAndBoundaries() throws Exception {
    try (var runtime = open(directory.resolve("index"))) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "/b/legacy", SchemaFields.DOC_UID, "legacy", SchemaFields.CONTENT, TEXT)));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "legacy-child", SchemaFields.DOC_UID, "legacy-child",
          SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, "/b/legacy")));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "/b/divergent", SchemaFields.DOC_UID, "divergent", SchemaFields.PATH, "/bc/live")));
      parent(runtime, "/bc/sibling", "sibling"); parent(runtime, "/b/keep/file", "keep");
      parent(runtime, "/b/survivor", "survivor"); parent(runtime, "\\b\\mixed", "mixed");
      refresh(runtime);
      assertEquals(3, runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
          "/b", List.of("/b/survivor"), List.of(), List.of("/b/keep")));
      runtime.indexingCoordinator().deleteByPathPrefixExcludingAcceptedSurvivors(
          "/b", List.of("/b/survivor"), List.of("/b/keep"));
      refresh(runtime);
      assertEquals(0, runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
          "/b", List.of("/b/survivor"), List.of(), List.of("/b/keep")));
      assertEquals(4, runtime.indexCountOps().docCount());
    }
  }

  @Test void D48UnrelatedChunkRmwPreservesAbsentSpladeWhileApplicableStatesRemainHonest() {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/parent", "source"); child(runtime, "child", "/parent", REV); refresh(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument("child", Map.of(
          SchemaFields.CHUNK_VECTOR, new float[] {1, 0, 0, 0}, SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED")));
      refresh(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      assertTrue(runtime.indexingCoordinator().updateDocument("child", Map.of(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "1")));
      runtime.indexingCoordinator().updateDocumentPaths("/parent", "/renamed"); refresh(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      assertTrue(runtime.indexingCoordinator().updateDocument("child", Map.of(SchemaFields.SPLADE_STATUS, "FAILED", SchemaFields.SPLADE_RETRY_COUNT, "2")));
      refresh(runtime);
      runtime.indexingCoordinator().updateDocument("child", Map.of(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "2")); refresh(runtime);
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_RETRY_COUNT));
    }
  }

  @Test void D63MalformedProofQuarantinesDurablyAndStaleFailureCannotOverwriteRepair() throws Exception {
    Path index = directory.resolve("index");
    try (var runtime = open(index)) {
      parent(runtime, "/parent", "source"); child(runtime, "broken", "/parent", null);
      child(runtime, "healthy", "/parent", REV); refresh(runtime);
      var input = runtime.documentFieldOps().getEnrichmentInputs(List.of("broken", "healthy"));
      assertNull(input.get("broken").content()); assertEquals("alpha", input.get("healthy").content());
      assertTrue(runtime.indexingCoordinator().updateDocumentIf("broken", input.get("broken"),
          Map.of(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "1")));
      refresh(runtime);
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
      runtime.indexingCoordinator().updateDocument("broken", Map.of(SchemaFields.CHUNK_CONTENT, "alpha",
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, REV, SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING"));
      refresh(runtime);
      assertFalse(runtime.indexingCoordinator().updateDocumentIf("broken", input.get("broken"),
          Map.of(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "2")));
      refresh(runtime);
    }
    try (var runtime = open(index)) {
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
      assertEquals("alpha", runtime.documentFieldOps().getEnrichmentInputs(List.of("broken")).get("broken").content());
    }
  }

  @Test void D129UnrefreshedPublicationIsCountedByCollectionDelete() throws Exception {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/parent", "source"); child(runtime, "child", "/parent", REV);
      assertEquals(2, runtime.indexingCoordinator().deleteByCollection("docs"));
      refresh(runtime);
      assertEquals(0, runtime.indexCountOps().countQueryOrThrow(new TermQuery(new Term(SchemaFields.COLLECTION, "docs"))));
      assertEquals(0, runtime.indexingCoordinator().deleteByCollection("docs"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"replace", "rename", "delete"})
  void C35CapturedMalformedFailureCannotOverwriteAChangedFamily(String mutation) throws Exception {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/parent", "source"); child(runtime, "broken", "/parent", null); refresh(runtime);
      var captured = runtime.documentFieldOps().getEnrichmentInputs(List.of("broken")).get("broken");
      switch (mutation) {
        case "replace" -> {
          child(runtime, "broken", "/parent", REV);
        }
        case "rename" -> runtime.indexingCoordinator().updateDocument("broken", Map.of(
            SchemaFields.CHUNK_CONTENT, "alpha", SchemaFields.PATH, "/renamed", SchemaFields.PARENT_DOC_ID, "/renamed"));
        case "delete" -> runtime.indexingCoordinator().deleteById("broken");
        default -> throw new AssertionError(mutation);
      }
      refresh(runtime);
      assertFalse(runtime.indexingCoordinator().updateDocumentIf("broken", captured,
          Map.of(SchemaFields.CHUNK_EMBEDDING_STATUS, "FAILED", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "1")));
      refresh(runtime);
      if (mutation.equals("delete")) assertNull(runtime.documentFieldOps().getDocumentField("broken", SchemaFields.DOC_UID));
      else assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.CHUNK_EMBEDDING_STATUS));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PENDING", "FAILED", "COMPLETED"})
  void C32ApplicableSparseStatesKeepRetriesAndCompletedWithoutDataRequeues(String state) {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/parent", "source"); child(runtime, "child", "/parent", REV); refresh(runtime);
      for (String id : List.of("/parent", "child")) {
        var sparse = new HashMap<String, Object>();
        sparse.put(SchemaFields.SPLADE_STATUS, state); sparse.put(SchemaFields.SPLADE_RETRY_COUNT, "2");
        if (state.equals("COMPLETED")) sparse.put(SchemaFields.SPLADE, Map.of("alpha", 1f));
        assertTrue(runtime.indexingCoordinator().updateDocument(id, sparse));
      }
      refresh(runtime);
      for (String id : List.of("/parent", "child")) {
        assertTrue(runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.META_AUTHOR, "Carol")));
      }
      refresh(runtime);
      for (String id : List.of("/parent", "child")) {
        assertEquals(state.equals("COMPLETED") ? "PENDING" : state,
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS));
        assertEquals(state.equals("COMPLETED") ? "0" : "2",
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_RETRY_COUNT));
      }
    }
  }

  @Test void C20NoOpAndAbsentSourceNeverEraseAnExistingDestination() {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/destination", "destination"); child(runtime, "child", "/destination", REV); refresh(runtime);
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("/destination", "/destination"));
      assertEquals(0, runtime.indexingCoordinator().updateDocumentPaths("/missing", "/destination"));
      refresh(runtime);
      assertEquals("destination", runtime.documentFieldOps().getDocumentField("/destination", SchemaFields.DOC_UID));
      assertEquals("/destination", runtime.documentFieldOps().getDocumentField("child", SchemaFields.PARENT_DOC_ID));
      assertEquals(2, runtime.indexCountOps().docCount());
    }
  }


  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"embedding", "failure", "rename"})
  void C31DisabledSparseAbsenceSurvivesUnrelatedRmwAndRestart(String operation) {
    Path index = directory.resolve("index");
    try (var runtime = open(index)) {
      parent(runtime, "/parent", "source"); child(runtime, "child", "/parent", REV); refresh(runtime);
      switch (operation) {
        case "embedding" -> runtime.indexingCoordinator().updateDocument("child", Map.of(
            SchemaFields.CHUNK_VECTOR, new float[] {1f, 0f, 0f, 0f}, SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED"));
        case "failure" -> runtime.indexingCoordinator().updateDocument("child", Map.of(
            SchemaFields.CHUNK_EMBEDDING_STATUS, "FAILED", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "1"));
        case "rename" -> runtime.indexingCoordinator().updateDocumentPaths("/parent", "/renamed");
        default -> throw new AssertionError(operation);
      }
      refresh(runtime);
    }
    try (var runtime = open(index)) {
      assertNull(runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      assertNull(runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_RETRY_COUNT));
    }
  }

  @Test void C37CollectionUnavailableWriterCannotReportSuccessfulZero() throws Exception {
    Path index = directory.resolve("index");
    try (var runtime = open(index)) {
      parent(runtime, "/parent", "source"); refresh(runtime);
      runtime.session().snapshot.writer().rollback();
      assertThrows(IndexWriterUnavailableException.class, () -> runtime.indexingCoordinator().deleteByCollection("docs"));
    }
    try (var runtime = open(index)) { assertEquals(1, runtime.indexCountOps().docCount()); }
  }


  @Test void C32SparseAbsenceUsesTheCurrentChunkConfiguration() {
    try (var runtime = open(directory.resolve("index"))) {
      parent(runtime, "/parent", "source"); child(runtime, "child", "/parent", REV); refresh(runtime);
      runtime.indexingCoordinator().updateDocument("child", Map.of(SchemaFields.META_AUTHOR, "Carol")); refresh(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      runtime.session().resolvedConfig = io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .putDefault("rag.chunk_splade.enabled", "true").build();
      runtime.indexingCoordinator().updateDocument("child", Map.of(SchemaFields.META_AUTHOR, "Dana")); refresh(runtime);
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_STATUS));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("child", SchemaFields.SPLADE_RETRY_COUNT));
    }
  }


  @Test void C23HiddenLegacyVersionCannotAuthorizeDeletingItsLiveOutsideBackingPath() throws Exception {
    try (var runtime = open(directory.resolve("index"))) {
      runtime.session().snapshot.writer().getConfig().setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE);
      String id = "/b/legacy-id";
      var current = runtime.session().fieldMapper.toDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "current", SchemaFields.PATH, "/bc/live", SchemaFields.CONTENT, TEXT), null);
      var hidden = runtime.session().fieldMapper.toDocument(Map.of(
          SchemaFields.DOC_ID, id, SchemaFields.PATH, "/b/hidden"), null);
      hidden.add(new org.apache.lucene.document.NumericDocValuesField(runtime.session().softDeleteField, 1));
      runtime.session().snapshot.writer().addDocuments(List.of(current, hidden));
      refresh(runtime);
      try (var reader = org.apache.lucene.index.DirectoryReader.open(runtime.session().snapshot.writer().getDirectory())) {
        assertEquals(1, new org.apache.lucene.search.IndexSearcher(reader).count(
            new TermQuery(new Term(SchemaFields.PATH, "/b/hidden"))));
      }
      assertEquals(0, runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict("/b", List.of(), List.of()));
      runtime.indexingCoordinator().deleteByPathPrefix("/b"); refresh(runtime);
      try (var reader = org.apache.lucene.index.DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(0, new org.apache.lucene.search.IndexSearcher(reader).count(
            new TermQuery(new Term(SchemaFields.PATH, "/b/hidden"))));
      }
      assertEquals("current", runtime.documentFieldOps().getDocumentField(id, SchemaFields.DOC_UID));
      assertEquals("/bc/live", runtime.documentFieldOps().getDocumentField(id, SchemaFields.PATH));
    }
  }

}
