package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import io.justsearch.indexing.SchemaFields;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;

class IndexingIntegrationTest extends LuceneExecutorTestBase {

  @Test
  void reindexReplacesExistingDocument() throws Exception {
    Path dir = Files.createTempDirectory("lucene-index-reindex");
    String config = baseConfig(dir)
        + "  soft_deletes:\n"
        + "    retention:\n"
        + "      enabled: true\n"
        + "      days: 7\n";
    withConfig(config, () -> {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(document("doc-1", "doc-1#0", "first")));
      runtime.commitOps().commitAndTrack();

      runtime.indexingCoordinator().indexSingle(new IndexDocument(document("doc-1", "doc-1#1", "second")));
      runtime.commitOps().commitAndTrack();
      runtime.close();

      try (var directory = new MMapDirectory(dir); var reader = DirectoryReader.open(directory)) {
        assertEquals(1, reader.numDocs(), "Expected single live document after update");
        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs hits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, "doc-1")), 1);
        assertEquals(1, hits.scoreDocs.length);
        Document doc = reader.storedFields().document(hits.scoreDocs[0].doc);
        assertEquals("second", doc.get("title"));
      }
    });
  }

  @Test
  void softDeletedDocumentsHiddenFromSearchers() throws Exception {
    Path dir = Files.createTempDirectory("lucene-soft-delete-hidden");
    String config = baseConfig(dir)
        + "  soft_deletes:\n"
        + "    retention:\n"
        + "      enabled: true\n"
        + "      days: 7\n";
    withConfig(config, () -> {
      RunningRuntime runtime =
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new SsotCommitMetadataSource(), new JsonSchemaCommitMetadataValidator()).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(document("doc-1", "doc-1#0", "visible")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      assertHitCount(runtime, "doc-1", 1);

      Map<String, Object> tombstone = softDeleteDocument("doc-1", "doc-1#1", 1L);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(tombstone));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      assertHitCount(runtime, "doc-1", 0);
      assertTrue(runtime.commitOps().softDeletesCurrent() > 0);
      runtime.close();
    });
  }

  private static void assertHitCount(RunningRuntime runtime, String docId, int expected)
      throws IOException {
    var accessor = new LifecycleTestAccessor(runtime);
    IndexSearcher searcher = accessor.acquireSearcher();
    try {
      TopDocs docs = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), 10);
      int actual = docs.scoreDocs.length;
      if (expected == 0 && actual > 0) {
        int internalDocId = docs.scoreDocs[0].doc;
        Document doc = searcher.getIndexReader().storedFields().document(internalDocId);
        long softValue =
            docValue(searcher.getIndexReader(), accessor.softDeletesField(), internalDocId);
        throw new AssertionError(
            "Soft delete not applied for doc_id="
                + doc.get(SchemaFields.DOC_ID)
                + " softValue="
                + softValue);
      }
      assertEquals(expected, actual, () -> "unexpected hit count for " + docId + ": " + actual);
    } finally {
      accessor.releaseSearcher(searcher);
    }
  }

  private static long docValue(IndexReader reader, String field, int docId) throws IOException {
    NumericDocValues values = MultiDocValues.getNumericValues(reader, field);
    if (values == null) return -1;
    if (values.advance(docId) == docId) {
      return values.longValue();
    }
    return -1;
  }

  private static Map<String, Object> document(String docId, String docUid, String title) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, docId);
    doc.put(SchemaFields.DOC_UID, docUid);
    doc.put("title", title);
    return doc;
  }

  private static Map<String, Object> softDeleteDocument(String docId, String docUid, long ordinal) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, docId);
    doc.put(SchemaFields.DOC_UID, docUid);
    doc.put(SchemaFields.SOFT_DELETE, 1);
    doc.put(SchemaFields.SOFT_DELETE_ORDINAL, ordinal);
    return doc;
  }

  private static String baseConfig(Path dataDir) {
    return "app:\n"
        + "  data_dir: " + dataDir.toString().replace("\\", "\\\\") + "\n"
        + "index:\n"
        + "  collections:\n"
        + "    - name: runtime\n"
        + "      roots: ['ignored']\n"
        + "  vector:\n"
        + "    dimension: 768\n";
  }

  private static void withConfig(String yaml, ThrowingRunnable runnable) throws Exception {
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    String prev = System.getProperty("justsearch.config");
    System.setProperty("justsearch.config", cfg.toString());
    try {
      runnable.run();
    } finally {
      if (prev == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prev);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
