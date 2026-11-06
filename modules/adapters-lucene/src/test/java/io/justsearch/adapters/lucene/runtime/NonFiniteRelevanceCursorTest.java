/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.indexing.SchemaFields;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Relevance cursors use the invalid-score outcome for every non-finite float. */
class NonFiniteRelevanceCursorTest {
  @ParameterizedTest
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e100", "-1e100"})
  void nonFiniteScoresUseTheSameRejectionAsNonNumericScores(String score) throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      var doc = new Document();
      doc.add(new StringField(SchemaFields.DOC_ID, "doc", Field.Store.YES));
      writer.addDocument(doc);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var ops = new ReadPathOps(null, SchemaFields.DOC_ID);
        var invalid = assertThrows(IllegalArgumentException.class, () -> ops.decodeSearchAfterCursor(
            searcher, "safter-v1:relevance:ZG9j:garbage:_:_", RuntimeSearchSort.RELEVANCE));
        var nonFinite = assertThrows(IllegalArgumentException.class, () -> ops.decodeSearchAfterCursor(
            searcher, "safter-v1:relevance:ZG9j:" + score + ":_:_", RuntimeSearchSort.RELEVANCE));
        assertEquals(invalid.getMessage(), nonFinite.getMessage());
        assertEquals("cursor_missing_score", nonFinite.getMessage());
        assertEquals(0f, ops.decodeSearchAfterCursor(searcher, "safter-v1:relevance:ZG9j:0:_:_",
            RuntimeSearchSort.RELEVANCE).score);
        assertNotNull(ops.decodeSearchAfterCursor(searcher, "safter-v1:path_asc:ZG9j:_:_:_",
            RuntimeSearchSort.PATH_ASC));
        assertNotNull(ops.decodeSearchAfterCursor(searcher, "safter-v1:size_asc:ZG9j:_:_:0",
            RuntimeSearchSort.SIZE_ASC));
        assertNull(ops.decodeSearchAfterCursor(searcher, null, RuntimeSearchSort.RELEVANCE));
      }
    }
  }
}
