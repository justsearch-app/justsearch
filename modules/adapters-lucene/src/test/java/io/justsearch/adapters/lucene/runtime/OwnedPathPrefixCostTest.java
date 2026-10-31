/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

final class OwnedPathPrefixCostTest {
  @Test
  void smallRemovalReadsTheSameStoredRowsRegardlessOfUnrelatedCorpus() throws Exception {
    int small = removeFolder(0);
    int large = removeFolder(4096);
    assertTrue(small > 0, "the counter observes actual stored-field reads");
    assertEquals(small, large, "unrelated parents and chunks must add no stored-field reads");
  }

  private int removeFolder(int unrelated) throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      row(writer, "/b", Map.of());
      row(writer, "exact", Map.of(SchemaFields.PATH, "/b"));
      row(writer, "opaque-owner", Map.of(SchemaFields.PATH, "/b/deep/file"));
      row(writer, "opaque-child", Map.of(SchemaFields.PARENT_DOC_ID, "opaque-owner", SchemaFields.IS_CHUNK, "true"));
      row(writer, "/b/legacy", Map.of());
      row(writer, "orphan", Map.of(SchemaFields.PARENT_DOC_ID, "/b/absent-owner", SchemaFields.IS_CHUNK, "true"));
      row(writer, "mixed", Map.of(SchemaFields.PATH, "\\b/deep\\mixed"));
      row(writer, "blank-child", Map.of(SchemaFields.PARENT_DOC_ID, "opaque-owner", SchemaFields.IS_CHUNK, "true", SchemaFields.PATH, " "));
      row(writer, "/b/divergent", Map.of(SchemaFields.PATH, "/bc/keep"));
      row(writer, "divergent-child", Map.of(SchemaFields.PARENT_DOC_ID, "/b/divergent", SchemaFields.IS_CHUNK, "true"));
      row(writer, "/b/keep/excluded", Map.of());
      for (int i = 0; i < unrelated; i++) {
        row(writer, "unrelated-" + i, Map.of(SchemaFields.PATH, "/other/file-" + i));
        row(writer, "unrelated-child-" + i, Map.of(SchemaFields.PATH, "/other/file-" + i,
            SchemaFields.PARENT_DOC_ID, "unrelated-" + i, SchemaFields.IS_CHUNK, "true"));
      }
      var reads = new AtomicInteger();
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader) {
          @Override public StoredFields storedFields() throws IOException {
            StoredFields delegate = super.storedFields();
            return new StoredFields() {
              @Override public void document(int doc, StoredFieldVisitor visitor) throws IOException {
                reads.incrementAndGet();
                delegate.document(doc, visitor);
              }
            };
          }
        };
        var scope = PruneOps.ownedPathPrefixQuery(searcher, SchemaFields.DOC_ID, "/b", List.of("/b/keep"));
        assertEquals(8, searcher.count(scope), "legacy ownership, separators and exclusions survive narrowing");
        writer.deleteDocuments(scope);
        writer.commit();
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        assertEquals(3 + unrelated * 2, reader.numDocs(), "outside backing paths and exclusions survive");
        assertEquals(0, searcher.count(PruneOps.ownedPathPrefixQuery(searcher,
            SchemaFields.DOC_ID, "/b", List.of("/b/keep"))), "completion proof agrees with deletion");
      }
      return reads.get();
    }
  }

  private static void row(IndexWriter writer, String id, Map<String, String> fields) throws IOException {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.DOC_ID, id, Field.Store.YES));
    fields.forEach((name, value) -> doc.add(new StringField(name, value, Field.Store.YES)));
    writer.addDocument(doc);
  }
}
