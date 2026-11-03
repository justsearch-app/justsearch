/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SparsePayloadPreservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void mapperDerivesAllAcceptedFeaturesAndRewritesKeepFrequencies() throws Exception {
    try (var runtime = open()) {
      Map<String, Float> features = new HashMap<>();
      for (int i = 0; i < 129; i++) features.put("feature-" + i, 1.234567f + i);
      features.put("界".repeat(org.apache.lucene.index.IndexWriter.MAX_TERM_LENGTH / 3), 2.345678f);
      features.put("zero", 0f);
      seed(runtime, "parent", "COMPLETED", features);
      commit(runtime);
      byte[] payload = bytes(stored(runtime, "parent"));
      assertNotNull(payload, "every accepted sparse artifact must carry its derived payload");
      assertEquals(130, java.nio.ByteBuffer.wrap(payload).getInt(1), "no feature-count cap");
      Map<String, Integer> before;
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        before = postings(new IndexSearcher(reader), "parent");
      }
      assertEquals(130, before.size());
      assertEquals(Float.floatToIntBits(64f) >>> 15, before.get("feature-128"));
      for (int pass = 0; pass < 3; pass++) {
        assertTrue(runtime.indexingCoordinator().updateDocument("parent", update("parent").getValue()));
        commit(runtime);
        try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
          assertEquals(before, postings(new IndexSearcher(reader), "parent"));
        }
      }
      assertArrayEquals(payload, bytes(stored(runtime, "parent")));
    }
    try (var runtime = open()) {
      assertNotNull(bytes(stored(runtime, "parent")));
      assertTrue(runtime.indexingCoordinator().updateDocument("parent", Map.of(SchemaFields.PATH, "moved")));
      commit(runtime);
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(130, postings(new IndexSearcher(reader), "parent").size());
      }
    }
  }

  @Test void legacyScanRunsOnceAndEmptyOrFailedRowsNeverDecodeOldPayload() throws Exception {
    try (var runtime = open()) {
      replacePayload(runtime, "legacy", null);
      commit(runtime);
      AtomicLong visited = new AtomicLong(), features = new AtomicLong();
      try (var reader = wrapped(DirectoryReader.open(runtime.session().snapshot.writer()), visited, features)) {
        assertEquals(1, runtime.session().writePathOps.readModifyWriteBatch(new IndexSearcher(reader),
            List.of(update("legacy"))).updatedCount());
        assertTrue(visited.get() > 0);
        assertEquals(1, features.get());
      }
      commit(runtime);
      assertNotNull(bytes(stored(runtime, "legacy")), "legacy row gains payload on its first rewrite");
      visited.set(0); features.set(0);
      try (var reader = wrapped(DirectoryReader.open(runtime.session().snapshot.writer()), visited, features)) {
        assertEquals(1, runtime.session().writePathOps.readModifyWriteBatch(new IndexSearcher(reader),
            List.of(update("legacy"))).updatedCount());
        assertEquals(0, visited.get(), "second rewrite must not visit sparse terms");
      }
      for (String status : List.of("COMPLETED_EMPTY", "FAILED", "PENDING")) {
        replacePayload(runtime, status, new byte[]{99});
      }
      commit(runtime);
      var updates = new ArrayList<Map.Entry<String, Map<String, Object>>>();
      for (String status : List.of("COMPLETED_EMPTY", "FAILED", "PENDING"))
        updates.add(Map.entry(status, Map.of(SchemaFields.SPLADE_STATUS, status, SchemaFields.SPLADE_RETRY_COUNT, 3)));
      var receipt = assertDoesNotThrow(() -> runtime.session().writePathOps.publishEnrichmentBatch(updates,
          runtime.documentFieldOps().getEnrichmentInputs(List.of("COMPLETED_EMPTY", "FAILED", "PENDING"))));
      assertEquals(3, receipt.acceptedIds().size());
      assertTrue(receipt.storageFailures().isEmpty());
      commit(runtime);
      for (String status : List.of("COMPLETED_EMPTY", "FAILED", "PENDING")) {
        assertNull(bytes(stored(runtime, status)));
        try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
          assertTrue(postings(new IndexSearcher(reader), status).isEmpty());
        }
      }
    }
  }

  @Test void malformedPayloadsWithholdOnlyTheirRowsWithoutChangingStoredWork() throws Exception {
    byte[] valid = payload(1, new String[]{"x"}, new float[]{1f});
    byte[] invalidUtf8 = valid.clone(); invalidUtf8[9] = (byte) 0xff;
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
    var malformed = List.of(payload(2, new String[]{"x"}, new float[]{1f}),
        java.util.Arrays.copyOf(valid, valid.length - 1), trailing,
        payload(1, new String[]{"x", "x"}, new float[]{1f, 2f}), invalidUtf8,
        payload(1, new String[]{"x"}, new float[]{Float.MIN_VALUE}),
        payload(1, new String[]{"x"}, new float[]{0f}),
        payload(1, new String[]{"original"}, new float[]{Math.nextUp(64f)}),
        payload(1, new String[]{"original"}, new float[]{Float.MAX_VALUE}));
    try (var runtime = open()) {
      var ids = new ArrayList<String>();
      var updates = new ArrayList<Map.Entry<String, Map<String, Object>>>();
      for (int i = 0; i < malformed.size(); i++) {
        String id = "bad-" + i;
        replacePayload(runtime, id, malformed.get(i)); ids.add(id); updates.add(update(id));
        String good = "good-" + i;
        seed(runtime, good, "COMPLETED", Map.of("healthy", 64f)); ids.add(good); updates.add(update(good));
      }
      commit(runtime);
      var receipt = assertDoesNotThrow(() -> runtime.session().writePathOps.publishEnrichmentBatch(updates,
          runtime.documentFieldOps().getEnrichmentInputs(ids)));
      assertEquals(malformed.size(), receipt.storageFailures().size());
      assertEquals(malformed.size(), receipt.acceptedIds().size());
      assertTrue(receipt.rejectedIds().isEmpty()); assertNull(receipt.failure());
      commit(runtime);
    }
    try (var runtime = open()) {
      for (int i = 0; i < malformed.size(); i++) {
        String id = "bad-" + i;
        assertArrayEquals(malformed.get(i), bytes(stored(runtime, id)));
        assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.NER_STATUS));
        assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_STATUS));
        assertEquals("2", runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_RETRY_COUNT));
        assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField("good-" + i, SchemaFields.NER_STATUS));
        try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
          var searcher = new IndexSearcher(reader);
          assertEquals(Map.of("original", Float.floatToIntBits(1f) >>> 15), postings(searcher, id));
          assertEquals(Map.of("healthy", Float.floatToIntBits(64f) >>> 15), postings(searcher, "good-" + i));
        }
      }
    }
  }

  @Test void storedPayloadReadFailureIsIsolatedInConditionalPublication() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "broken", "COMPLETED", Map.of("retained", 1f));
      seed(runtime, "healthy", "COMPLETED", Map.of("healthy", 2f)); commit(runtime);
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("broken", "healthy"));
      try (var original = DirectoryReader.open(runtime.session().snapshot.writer())) {
        int broken = new IndexSearcher(original).search(new TermQuery(new Term(SchemaFields.DOC_ID, "broken")), 1).scoreDocs[0].doc;
        var locals = new java.util.IdentityHashMap<LeafReader, Integer>();
        for (var leaf : original.leaves()) {
          if (broken >= leaf.docBase && broken < leaf.docBase + leaf.reader().maxDoc())
            locals.put(leaf.reader(), broken - leaf.docBase);
        }
        original.incRef();
        try (var reader = new FilterDirectoryReader(original, new FilterDirectoryReader.SubReaderWrapper() {
          @Override public LeafReader wrap(LeafReader leaf) {
            int bad = locals.getOrDefault(leaf, -1);
            return new FilterLeafReader(leaf) {
              @Override public org.apache.lucene.index.StoredFields storedFields() throws IOException {
                var delegate = super.storedFields();
                return new org.apache.lucene.index.StoredFields() {
                  @Override public void document(int doc, org.apache.lucene.index.StoredFieldVisitor visitor) throws IOException {
                    if (doc == bad) throw new IOException("injected stored sparse payload read failure");
                    delegate.document(doc, visitor);
                  }
                };
              }
              @Override public IndexReader.CacheHelper getCoreCacheHelper() { return null; }
              @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
            };
          }
        }) {
          @Override protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) { throw new UnsupportedOperationException(); }
          @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
        }) {
          var receipt = assertDoesNotThrow(() -> runtime.session().writePathOps.publishEnrichmentBatch(new IndexSearcher(reader),
              List.of(update("broken"), update("healthy")), inputs));
          assertEquals(List.of("healthy"), receipt.acceptedIds());
          assertEquals(java.util.Set.of("broken"), receipt.storageFailures().keySet()); assertNull(receipt.failure());
        }
      }
      commit(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("broken", SchemaFields.NER_STATUS));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.SPLADE_RETRY_COUNT));
      assertNotNull(bytes(stored(runtime, "broken")));
    }
  }

  @Test void suppliedSparseWinsWithReplacementContentAndStatusOnlyFailureDropsBoth() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "supplied", "COMPLETED", Map.of("old", 1f));
      seed(runtime, "changed", "COMPLETED", Map.of("old", 1f));
      seed(runtime, "failed", "COMPLETED", Map.of("old", 1f));
      seed(runtime, "removed", "COMPLETED", Map.of("old", 1f));
      commit(runtime);
      assertTrue(runtime.indexingCoordinator().updateDocument("supplied", Map.of(SchemaFields.CONTENT, "new body",
          SchemaFields.SPLADE, Map.of("new", 3f), SchemaFields.SPLADE_STATUS, "COMPLETED")));
      assertTrue(runtime.indexingCoordinator().updateDocument("changed", Map.of(SchemaFields.CONTENT, "new body")));
      assertTrue(runtime.indexingCoordinator().updateDocument("failed", Map.of(SchemaFields.SPLADE_STATUS, "FAILED",
          SchemaFields.SPLADE_RETRY_COUNT, 3)));
      Map<String, Object> removal = new HashMap<>();
      removal.put(SchemaFields.SPLADE, null);
      assertTrue(runtime.indexingCoordinator().updateDocument("removed", removal));
      commit(runtime);
      assertNotNull(bytes(stored(runtime, "supplied")));
      for (String id : List.of("changed", "failed", "removed")) {
        assertNull(bytes(stored(runtime, id)));
        try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
          assertTrue(postings(new IndexSearcher(reader), id).isEmpty());
        }
      }
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("changed", SchemaFields.SPLADE_STATUS));
      assertEquals("FAILED", runtime.documentFieldOps().getDocumentField("failed", SchemaFields.SPLADE_STATUS));
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("removed", SchemaFields.SPLADE_STATUS));
    }
  }

  @Test void chunkPayloadSurvivesBatchPublicationReopenAndMerge() throws Exception {
    Map<String, Integer> before;
    try (var runtime = open()) {
      seed(runtime, "parent", "COMPLETED", Map.of("parent", 1.234567f));
      Map<String, Object> chunk = new HashMap<>(Map.of(SchemaFields.DOC_ID, "chunk", SchemaFields.DOC_UID, "chunk#1",
          SchemaFields.IS_CHUNK, true, SchemaFields.PARENT_DOC_ID, "parent", SchemaFields.CHUNK_INDEX, 0,
          SchemaFields.CHUNK_START_CHAR, 0, SchemaFields.CHUNK_END_CHAR, 4,
          SchemaFields.CHUNK_CONTENT, "body", SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
          io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("body parent")));
      chunk.put(SchemaFields.SPLADE, Map.of("chunk", 2.345678f));
      chunk.put(SchemaFields.SPLADE_STATUS, "COMPLETED"); chunk.put(SchemaFields.SPLADE_RETRY_COUNT, 2);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk)); commit(runtime);
      assertNotNull(bytes(stored(runtime, "chunk")));
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        before = postings(new IndexSearcher(reader), "chunk");
      }
      var updates = List.of(update("parent"), update("chunk"));
      var receipt = assertDoesNotThrow(() -> runtime.session().writePathOps.publishEnrichmentBatch(updates,
          runtime.documentFieldOps().getEnrichmentInputs(List.of("parent", "chunk"))));
      assertEquals(List.of("parent", "chunk"), receipt.acceptedIds()); assertNull(receipt.failure());
      runtime.session().snapshot.writer().forceMerge(1); commit(runtime);
    }
    try (var runtime = open()) {
      assertTrue(runtime.indexingCoordinator().updateDocument("chunk", Map.of(SchemaFields.CHUNK_VECTOR,
          new float[]{1, 0}, SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED")));
      commit(runtime);
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(before, postings(new IndexSearcher(reader), "chunk"));
        assertNotNull(bytes(stored(runtime, "chunk")));
      }
      assertEquals("2", runtime.documentFieldOps().getDocumentField("chunk", SchemaFields.SPLADE_RETRY_COUNT));
    }
  }

  @Test void payloadIsDerivedAndCannotCertifyAnInvalidStatus() throws Exception {
    try (var runtime = open()) {
      var mapper = runtime.session().fieldMapper;
      var doc = mapper.toDocument(Map.of(SchemaFields.SPLADE, Map.of("accepted", 100f),
          SchemaFields.SPLADE_STATUS, "COMPLETED", "splade_payload", new byte[]{99}), null);
      assertArrayEquals(payload(1, new String[]{"accepted"}, new float[]{64f}), bytes(doc));
      for (String status : List.of("COMPLETED_EMPTY", "PENDING", "FAILED")) {
        assertThrows(IllegalArgumentException.class, () -> mapper.toDocument(Map.of(
            SchemaFields.SPLADE, Map.of("x", 1f), SchemaFields.SPLADE_STATUS, status), null));
      }
      assertThrows(IllegalArgumentException.class, () -> mapper.toDocument(Map.of(
          SchemaFields.SPLADE, Map.of("x", 1f)), null));
      assertThrows(IllegalArgumentException.class, () -> mapper.toDocument(Map.of(
          SchemaFields.SPLADE_STATUS, "COMPLETED", "splade_payload", new byte[]{1}), null));
    }
  }

  @Test void payloadAdditionAcceptedByBothParityPaths() throws Exception {
    var json = new tools.jackson.databind.ObjectMapper();
    String legacy = json.writeValueAsString(Map.of("fields", List.of()));
    String candidate = json.writeValueAsString(Map.of("fields", List.of(Map.of(
        "id", "splade_payload", "type", "binary-stored", "stored", true,
        "doc_values", false, "multi_valued", false, "roles", List.of()))));
    String key = io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY;
    String inputs = io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_INPUTS_KEY;
    String beforeDigest = digest(legacy), afterDigest = digest(candidate);
    assertTrue(ParityDiagnostics.diff(Map.of(key, beforeDigest, inputs, legacy),
        Map.of(key, afterDigest, inputs, candidate), 1, List.of()).isEmpty(),
        "certified additive stored binary field is compatible when both fingerprints are available");
    assertTrue(ParityDiagnostics.diff(Map.of(inputs, legacy),
        Map.of(inputs, candidate), 1, List.of()).isEmpty(),
        "indeterminate comparison uses the same additive compatibility predicate");
    String changed = candidate.replace("binary-stored", "keyword");
    assertFalse(ParityDiagnostics.diff(Map.of(key, afterDigest, inputs, candidate),
        Map.of(key, digest(changed), inputs, changed), 1, List.of()).isEmpty(),
        "changing an existing field remains incompatible");
  }

  private static String digest(String value) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    fields.add(new FieldDef("splade_payload", "binary-stored", true, false, List.of(), null, null, false));
    fields.add(new FieldDef(SchemaFields.SPLADE, "splade", false, false, List.of(), null, null, false, "reset-status:splade_status"));
    for (String name : List.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_RETRY_COUNT, SchemaFields.NER_STATUS))
      fields.add(new FieldDef(name, name.endsWith("count") ? "long" : "keyword", false, true, List.of("filter"), null, null, false));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("sparse-rmw", fields)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String id, String status, Map<String, Float> sparse) {
    Map<String, Object> fields = new HashMap<>(Map.of(SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid-" + id,
        SchemaFields.CONTENT, "body " + id, SchemaFields.SPLADE, sparse, SchemaFields.SPLADE_RETRY_COUNT, "2"));
    if (!status.equals("absent")) fields.put(SchemaFields.SPLADE_STATUS, status);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static Document stored(RunningRuntime runtime, String id) throws IOException {
    try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
      var searcher = new IndexSearcher(reader);
      return searcher.storedFields().document(searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc);
    }
  }

  private static byte[] bytes(Document doc) {
    BytesRef bytes = doc.getBinaryValue("splade_payload");
    return bytes == null ? null : java.util.Arrays.copyOfRange(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
  }

  private static void replacePayload(RunningRuntime runtime, String id, byte[] payload) throws IOException {
    var fields = new HashMap<String, Object>(Map.of(SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, "uid-" + id,
        SchemaFields.CONTENT, "body " + id, SchemaFields.SPLADE, Map.of("original", 1f),
        SchemaFields.SPLADE_STATUS, "COMPLETED", SchemaFields.SPLADE_RETRY_COUNT, 2));
    var doc = runtime.session().fieldMapper.toDocument(fields, null);
    doc.removeFields("splade_payload");
    if (payload != null) doc.add(new org.apache.lucene.document.StoredField("splade_payload", payload));
    runtime.session().snapshot.writer().updateDocument(new Term(SchemaFields.DOC_ID, id), doc);
  }

  private static byte[] payload(int format, String[] terms, float[] weights) throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(bytes);
    out.writeByte(format); out.writeInt(terms.length);
    for (int i = 0; i < terms.length; i++) {
      byte[] term = terms[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
      out.writeInt(term.length); out.write(term); out.writeFloat(weights[i]);
    }
    return bytes.toByteArray();
  }

  private static Map.Entry<String, Map<String, Object>> update(String id) {
    return Map.entry(id, Map.of(SchemaFields.NER_STATUS, "COMPLETED_EMPTY"));
  }
  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
  }
  private static Map<String, Integer> postings(IndexSearcher searcher, String id) throws IOException {
    int doc = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, id)), 1).scoreDocs[0].doc;
    Map<String, Integer> result = new HashMap<>();
    for (var leaf : searcher.getIndexReader().leaves()) {
      if (doc < leaf.docBase || doc >= leaf.docBase + leaf.reader().maxDoc()) continue;
      var terms = leaf.reader().terms(SchemaFields.SPLADE);
      if (terms == null) continue;
      var iterator = terms.iterator();
      for (var term = iterator.next(); term != null; term = iterator.next()) {
        var postings = iterator.postings(null, org.apache.lucene.index.PostingsEnum.FREQS);
        if (postings.advance(doc - leaf.docBase) == doc - leaf.docBase) result.put(term.utf8ToString(), postings.freq());
      }
    }
    return result;
  }

  private static DirectoryReader wrapped(DirectoryReader reader, AtomicLong visited, AtomicLong features) throws IOException {
    return new FilterDirectoryReader(reader, new FilterDirectoryReader.SubReaderWrapper() {
      @Override public LeafReader wrap(LeafReader leaf) {
        return new FilterLeafReader(leaf) {
          @Override public Terms terms(String field) throws IOException {
            Terms terms = super.terms(field);
            if (terms == null || !SchemaFields.SPLADE.equals(field)) return terms;
            return new FilterTerms(terms) {
              @Override public TermsEnum iterator() throws IOException {
                return new FilterTermsEnum(super.iterator()) {
                  @Override public BytesRef next() throws IOException {
                    BytesRef term = super.next(); if (term != null) visited.incrementAndGet(); return term;
                  }
                  @Override public org.apache.lucene.index.PostingsEnum postings(org.apache.lucene.index.PostingsEnum reuse, int flags) throws IOException {
                    var postings = super.postings(reuse, flags);
                    return new FilterPostingsEnum(postings) {
                      @Override public int freq() throws IOException { features.incrementAndGet(); return super.freq(); }
                    };
                  }
                };
              }
            };
          }
          @Override public IndexReader.CacheHelper getCoreCacheHelper() { return null; }
          @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
        };
      }
    }) {
      @Override protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) { throw new UnsupportedOperationException(); }
      @Override public IndexReader.CacheHelper getReaderCacheHelper() { return null; }
    };
  }
}
