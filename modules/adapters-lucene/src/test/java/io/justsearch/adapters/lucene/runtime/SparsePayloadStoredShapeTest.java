/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SparsePayloadStoredShapeTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  private static Stream<Arguments> shapes() {
    return Stream.of(false, true).flatMap(chunk ->
        Stream.of("string", "numeric", "duplicate-binary", "mixed")
            .map(shape -> Arguments.of(chunk, shape)));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @MethodSource("shapes")
  void ordinaryRmwRejectsMalformedStoredShapeWithoutChangingWork(boolean chunk, String shape)
      throws Exception {
    Map<String, Object> before;
    try (var runtime = open()) {
      seed(runtime, chunk, shape);
      before = snapshot(runtime, "bad");
      assertTrue(runtime.indexingCoordinator().updateDocument("first", patch()));
      var failure = assertThrows(IndexRuntimeIOException.class,
          () -> runtime.indexingCoordinator().updateDocument("bad", patch()));
      assertEquals(IndexRuntimeIOException.Reason.DISK_IO, failure.reason());
      assertInstanceOf(IOException.class, failure.getCause());
      assertTrue(runtime.indexingCoordinator().updateDocument("last", patch()));
      commit(runtime);
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
    try (var runtime = open()) {
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @MethodSource("shapes")
  void batchRmwWithholdsMalformedStoredShapeAndStillWritesLaterRows(boolean chunk, String shape)
      throws Exception {
    Map<String, Object> before;
    try (var runtime = open()) {
      seed(runtime, chunk, shape);
      before = snapshot(runtime, "bad");
      var failure = assertThrows(IndexRuntimeIOException.class,
          () -> runtime.indexingCoordinator().updateDocumentsBatch(updates()));
      assertEquals(IndexRuntimeIOException.Reason.DISK_IO, failure.reason());
      assertInstanceOf(IOException.class, failure.getCause());
      commit(runtime);
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
    try (var runtime = open()) {
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @MethodSource("shapes")
  void conditionalPublicationReportsOnlyMalformedRowAsStorageFailure(boolean chunk, String shape)
      throws Exception {
    Map<String, Object> before;
    try (var runtime = open()) {
      seed(runtime, chunk, shape);
      before = snapshot(runtime, "bad");
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("first", "bad", "last"));
      var receipt = assertDoesNotThrow(
          () -> runtime.indexingCoordinator().publishEnrichmentBatch(updates(), inputs));
      assertEquals(Map.of("bad", "sparse_artifact_read"), receipt.storageFailures());
      assertNull(receipt.failure(), "malformed shapes must not abort the publication");
      assertEquals(List.of("first", "last"), receipt.acceptedIds());
      assertTrue(receipt.rejectedIds().isEmpty());
      commit(runtime);
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
    try (var runtime = open()) {
      assertPublishedPeersAndUnchangedBadRow(runtime, before);
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @MethodSource("shapes")
  void presentMalformedShapesNeverUseTheLegacyScan(boolean chunk, String shape) throws Exception {
    try (var runtime = open()) {
      seed(runtime, chunk, shape);
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("first", "bad", "last"));
      AtomicInteger sparseReads = new AtomicInteger();
      try (var reader = countingSparseReads(
          DirectoryReader.open(runtime.session().snapshot.writer()), sparseReads)) {
        var searcher = new IndexSearcher(reader);
        assertThrows(IOException.class,
            () -> runtime.session().writePathOps.readModifyWriteBatch(searcher, updates()));
        var receipt = assertDoesNotThrow(
            () -> runtime.session().writePathOps.publishEnrichmentBatch(searcher, updates(), inputs));
        assertEquals(Map.of("bad", "sparse_artifact_read"), receipt.storageFailures());
        assertNull(receipt.failure());
        assertEquals(List.of("first", "last"), receipt.acceptedIds());
        assertEquals(0, sparseReads.get(), "only an absent payload may trigger a legacy scan");
      }
    }
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    fields.add(new FieldDef("splade_payload", "binary-stored", true, false, List.of(), null, null, false));
    fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    for (String name : List.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT,
        SchemaFields.NER_STATUS)) {
      fields.add(new FieldDef(name, name.endsWith("count") ? "long" : "keyword", false, true,
          List.of("filter"), null, null, false));
    }
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("sparse-shape", fields)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, boolean chunk, String shape) throws IOException {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(row("owner", false)));
    for (String id : List.of("first", "bad", "last")) {
      var document = runtime.session().fieldMapper.toDocument(row(id, chunk), null);
      if (id.equals("bad")) {
        var valid = BytesRef.deepCopyOf(document.getBinaryValue(SparsePayload.FIELD));
        document.removeFields(SparsePayload.FIELD);
        switch (shape) {
          case "string" -> document.add(new StoredField(SparsePayload.FIELD, "not binary"));
          case "numeric" -> document.add(new StoredField(SparsePayload.FIELD, 17L));
          case "duplicate-binary" -> {
            document.add(new StoredField(SparsePayload.FIELD, valid));
            document.add(new StoredField(SparsePayload.FIELD, valid));
          }
          case "mixed" -> {
            document.add(new StoredField(SparsePayload.FIELD, valid));
            document.add(new StoredField(SparsePayload.FIELD, "extra"));
          }
          default -> throw new IllegalArgumentException(shape);
        }
      }
      runtime.session().snapshot.writer().updateDocument(new Term(SchemaFields.DOC_ID, id), document);
    }
    commit(runtime);
  }

  private static Map<String, Object> row(String id, boolean chunk) {
    Map<String, Object> fields = new HashMap<>(Map.of(SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, "uid-" + id, SchemaFields.SPLADE, Map.of(id, 1.234567f),
        SchemaFields.SPLADE_STATUS, "COMPLETED", SchemaFields.SPLADE_RETRY_COUNT, 2));
    if (chunk) {
      fields.putAll(Map.of(SchemaFields.IS_CHUNK, true, SchemaFields.PARENT_DOC_ID, "owner",
          SchemaFields.CHUNK_INDEX, 0, SchemaFields.CHUNK_START_CHAR, 0,
          SchemaFields.CHUNK_END_CHAR, 4, SchemaFields.CHUNK_CONTENT, "body",
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex("body owner")));
    } else {
      fields.put(SchemaFields.CONTENT, "body " + id);
    }
    return fields;
  }

  private static Map<String, Object> patch() {
    return Map.of(SchemaFields.NER_STATUS, "COMPLETED_EMPTY");
  }

  private static List<Map.Entry<String, Map<String, Object>>> updates() {
    return List.of(Map.entry("first", patch()), Map.entry("bad", patch()), Map.entry("last", patch()));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertPublishedPeersAndUnchangedBadRow(
      RunningRuntime runtime, Map<String, Object> before) throws IOException {
    assertEquals(before, snapshot(runtime, "bad"), "withheld payload/postings/status/retry must be unchanged");
    assertNull(runtime.documentFieldOps().getDocumentField("bad", SchemaFields.NER_STATUS));
    for (String id : List.of("first", "last")) {
      assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField(id, SchemaFields.NER_STATUS));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS));
      assertEquals("2", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_RETRY_COUNT));
      assertEquals(Map.of(id, Float.floatToIntBits(1.234567f) >>> 15), snapshot(runtime, id).get("postings"));
    }
  }

  private static Map<String, Object> snapshot(RunningRuntime runtime, String id) throws IOException {
    try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
      var searcher = new IndexSearcher(reader);
      int doc = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc;
      var payloads = new ArrayList<Object>();
      for (var field : searcher.storedFields().document(doc).getFields(SparsePayload.FIELD)) {
        payloads.add(field.binaryValue() != null ? BytesRef.deepCopyOf(field.binaryValue())
            : field.numericValue() != null ? field.numericValue() : field.stringValue());
      }
      Map<String, Integer> features = new HashMap<>();
      for (var leaf : reader.leaves()) {
        if (doc < leaf.docBase || doc >= leaf.docBase + leaf.reader().maxDoc()) continue;
        var terms = leaf.reader().terms(SchemaFields.SPLADE);
        if (terms == null) continue;
        var iterator = terms.iterator();
        for (var term = iterator.next(); term != null; term = iterator.next()) {
          var postings = iterator.postings(null, PostingsEnum.FREQS);
          if (postings.advance(doc - leaf.docBase) == doc - leaf.docBase)
            features.put(term.utf8ToString(), postings.freq());
        }
      }
      return Map.of("payloads", payloads, "postings", features,
          "status", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS),
          "retry", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_RETRY_COUNT));
    }
  }

  private static DirectoryReader countingSparseReads(DirectoryReader reader, AtomicInteger reads)
      throws IOException {
    return new FilterDirectoryReader(reader, new FilterDirectoryReader.SubReaderWrapper() {
      @Override public LeafReader wrap(LeafReader leaf) {
        return new FilterLeafReader(leaf) {
          @Override public Terms terms(String field) throws IOException {
            if (SchemaFields.SPLADE.equals(field)) reads.incrementAndGet();
            return super.terms(field);
          }
          @Override public IndexReader.CacheHelper getCoreCacheHelper() { return null; }
          @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
        };
      }
    }) {
      @Override protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) {
        throw new UnsupportedOperationException();
      }
      @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
    };
  }
}
