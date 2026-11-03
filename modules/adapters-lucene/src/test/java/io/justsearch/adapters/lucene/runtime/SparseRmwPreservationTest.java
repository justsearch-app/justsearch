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

class SparseRmwPreservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void partialUpdatesRetainPostingsStatusAndRetryAfterReopen() throws Exception {
    Map<String, Map<String, Integer>> before = new HashMap<>();
    var updates = List.<Map<String, Object>>of(
        Map.of(SchemaFields.NER_STATUS, "COMPLETED_EMPTY"),
        Map.of(SchemaFields.PATH, "renamed.txt"),
        Map.of(SchemaFields.VECTOR, new float[]{1, 0}, SchemaFields.EMBEDDING_STATUS, "COMPLETED",
            SchemaFields.NER_STATUS, "COMPLETED_EMPTY"),
        Map.of(SchemaFields.EMBEDDING_STATUS, "PENDING", SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
    try (var runtime = open()) {
      for (int i = 0; i < updates.size(); i++) {
        seed(runtime, "full-" + i, "COMPLETED", Map.of("alpha", 1.234567f, "beta", 2.345678f));
        seed(runtime, "empty-" + i, "COMPLETED_EMPTY", Map.of());
      }
      commit(runtime);
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        for (int i = 0; i < updates.size(); i++) {
          before.put("full-" + i, postings(new IndexSearcher(reader), "full-" + i));
        }
      }
      for (int i = 0; i < updates.size(); i++) {
        assertTrue(runtime.indexingCoordinator().updateDocument("full-" + i, updates.get(i)));
        assertTrue(runtime.indexingCoordinator().updateDocument("empty-" + i, updates.get(i)));
      }
      commit(runtime);
      checkPreserved(runtime, before);
    }
    try (var runtime = open()) { checkPreserved(runtime, before); }
  }

  @Test void embeddingOnlyUpdateKeepsEncodedSparsePostings() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "dense-only", "COMPLETED", Map.of("alpha", 1.234567f, "beta", 2.345678f));
      commit(runtime);
      Map<String, Integer> before;
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        before = postings(new IndexSearcher(reader), "dense-only");
      }
      assertTrue(runtime.indexingCoordinator().updateDocument("dense-only", Map.of(
          SchemaFields.VECTOR, new float[]{1, 0}, SchemaFields.EMBEDDING_STATUS, "COMPLETED")));
      commit(runtime);
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(before, postings(new IndexSearcher(reader), "dense-only"));
      }
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("dense-only", SchemaFields.SPLADE_STATUS));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("dense-only", SchemaFields.SPLADE_RETRY_COUNT));
    }
  }

  @Test void batchTraversesSparseDictionaryOncePerLeaf() throws Exception {
    try (var runtime = open()) {
      for (String id : List.of("a", "b", "c")) seed(runtime, id, "COMPLETED", Map.of("one", 1f, "two", 2f));
      commit(runtime);
      AtomicLong visited = new AtomicLong();
      AtomicLong features = new AtomicLong();
      try (var reader = wrapped(DirectoryReader.open(runtime.session().snapshot.writer()), visited, features, false)) {
        var searcher = new IndexSearcher(reader);
        long expected = 0;
        for (var leaf : reader.leaves()) expected += leaf.reader().terms(SchemaFields.SPLADE).size();
        assertEquals(3, runtime.session().writePathOps.readModifyWriteBatch(searcher,
            List.of(update("a"), update("b"), update("c"))).updatedCount());
        assertEquals(expected, visited.get(), "one sparse term pass per leaf for the entire NER batch");
        assertEquals(6, features.get(), "all six features are reread once");
      }
      commit(runtime);
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        for (String id : List.of("a", "b", "c")) assertEquals(2, postings(new IndexSearcher(reader), id).size());
      }
    }
  }

  @Test void unreadableSparseDocumentIsWithheldWhileOtherDocumentsPublish() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "broken", "COMPLETED", Map.of("bad", 1f));
      seed(runtime, "good", "COMPLETED", Map.of("good", 2f));
      commit(runtime);
      var inputs = runtime.documentFieldOps().getEnrichmentInputs(List.of("broken", "good"));
      AtomicLong visited = new AtomicLong();
      AtomicLong features = new AtomicLong();
      try (var reader = wrapped(DirectoryReader.open(runtime.session().snapshot.writer()), visited, features, true)) {
        // Exercise the same conditional publication path with a storage fault in its leased reader.
        var receipt = assertDoesNotThrow(() -> runtime.session().writePathOps.publishEnrichmentBatch(
            new IndexSearcher(reader), List.of(update("broken"), update("good")), inputs));
        assertEquals(List.of("good"), receipt.acceptedIds());
        assertTrue(receipt.rejectedIds().isEmpty());
        assertTrue(receipt.storageFailures().containsKey("broken"));
        assertNull(receipt.failure());
      }
      commit(runtime);
      assertNull(runtime.documentFieldOps().getDocumentField("broken", SchemaFields.NER_STATUS));
      assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField("good", SchemaFields.NER_STATUS));
      assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.SPLADE_STATUS));
      assertEquals("2", runtime.documentFieldOps().getDocumentField("broken", SchemaFields.SPLADE_RETRY_COUNT));
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(1, postings(new IndexSearcher(reader), "broken").size());
      }
    }
  }

  @Test void suppliedSparseAndContentReplacementKeepTheirOwnSemantics() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "supplied", "COMPLETED", Map.of("old", 1f));
      seed(runtime, "changed", "COMPLETED", Map.of("old", 1f));
      for (String state : List.of("PENDING", "FAILED", "absent")) seed(runtime, state, state, Map.of());
      commit(runtime);
      runtime.indexingCoordinator().updateDocument("supplied", Map.of(SchemaFields.SPLADE, Map.of("new", 3f),
          SchemaFields.SPLADE_STATUS, "COMPLETED", SchemaFields.SPLADE_RETRY_COUNT, "0"));
      runtime.indexingCoordinator().updateDocument("changed", Map.of(SchemaFields.CONTENT, "replacement"));
      for (String state : List.of("PENDING", "FAILED", "absent")) runtime.indexingCoordinator().updateDocument(state, update(state).getValue());
      commit(runtime);
      assertEquals("PENDING", runtime.documentFieldOps().getDocumentField("changed", SchemaFields.SPLADE_STATUS));
      assertEquals("0", runtime.documentFieldOps().getDocumentField("changed", SchemaFields.SPLADE_RETRY_COUNT));
      for (String state : List.of("PENDING", "FAILED", "absent")) {
        assertEquals(state.equals("absent") ? "PENDING" : state, runtime.documentFieldOps().getDocumentField(state, SchemaFields.SPLADE_STATUS));
        assertEquals(state.equals("absent") ? "0" : "2", runtime.documentFieldOps().getDocumentField(state, SchemaFields.SPLADE_RETRY_COUNT));
      }
      try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
        assertEquals(Map.of("new", Float.floatToIntBits(3f) >>> 15), postings(new IndexSearcher(reader), "supplied"));
        assertTrue(postings(new IndexSearcher(reader), "changed").isEmpty());
      }
    }
  }

  private void checkPreserved(RunningRuntime runtime, Map<String, Map<String, Integer>> before) throws Exception {
    try (var reader = DirectoryReader.open(runtime.session().snapshot.writer())) {
      var searcher = new IndexSearcher(reader);
      for (var entry : before.entrySet()) {
        assertEquals(entry.getValue(), postings(searcher, entry.getKey()), "encoded posting frequencies must be identical");
        assertEquals("COMPLETED", runtime.documentFieldOps().getDocumentField(entry.getKey(), SchemaFields.SPLADE_STATUS));
        String empty = entry.getKey().replace("full-", "empty-");
        assertEquals("COMPLETED_EMPTY", runtime.documentFieldOps().getDocumentField(empty, SchemaFields.SPLADE_STATUS));
        assertTrue(postings(searcher, empty).isEmpty());
        for (String id : List.of(entry.getKey(), empty)) assertEquals("2",
            runtime.documentFieldOps().getDocumentField(id, SchemaFields.SPLADE_RETRY_COUNT));
      }
    }
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
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

  private static DirectoryReader wrapped(DirectoryReader reader, AtomicLong visited, AtomicLong features, boolean fail) throws IOException {
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
                    boolean brokenTerm = fail && term().utf8ToString().equals("bad");
                    return new FilterPostingsEnum(postings) {
                      @Override public int freq() throws IOException { features.incrementAndGet(); return super.freq(); }
                      @Override public int advance(int target) throws IOException {
                        if (brokenTerm && "broken".equals(leaf.storedFields().document(target).get(SchemaFields.DOC_ID)))
                          throw new IOException("injected sparse storage read failure");
                        return super.advance(target);
                      }
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
