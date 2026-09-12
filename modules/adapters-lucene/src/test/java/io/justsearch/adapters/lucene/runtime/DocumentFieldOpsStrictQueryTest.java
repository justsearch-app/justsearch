/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFieldOpsStrictQueryTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void strictSelectionPreservesReaderFailureInsteadOfReportingAnEmptyIndex() throws Exception {
    var failure = new IOException("reader failed");
    try (var directory = new ByteBuffersDirectory();
        var analyzer = new KeywordAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      var document = new Document();
      document.add(new StringField(SchemaFields.DOC_ID, "pending", Field.Store.YES));
      document.add(new StringField(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING, Field.Store.YES));
      writer.addDocument(document);
      try (var manager = new SearcherManager(writer, new SearcherFactory() {
        @Override
        public IndexSearcher newSearcher(IndexReader reader, IndexReader previous) {
          return new IndexSearcher(reader) {
            @Override
            public Weight createWeight(Query query, ScoreMode scoreMode, float boost) throws IOException {
              throw failure;
            }
          };
        }
      })) {
        var session = new RuntimeSession(IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)),
            testLuceneExecutors());
        session.snapshot = new LifecycleSnapshot(directory, writer, manager, tempDir, false, null);
        var fields = new DocumentFieldOps(session, new SearcherBridge(session), SchemaFields.DOC_ID, null);

        assertSame(failure, assertThrows(IOException.class, () -> fields.queryDocIdsByFieldOrThrow(
            SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING, 100)));
        // Existing observational callers retain their explicitly best-effort behavior.
        assertTrue(fields.queryDocIdsByField(
            SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING, 100).isEmpty());
      }
    }
  }
}
