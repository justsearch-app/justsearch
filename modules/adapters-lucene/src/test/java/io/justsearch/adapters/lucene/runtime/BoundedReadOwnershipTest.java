/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

final class BoundedReadOwnershipTest {
  @Test void defaultQueriesContainOnlyIndexNativeClauses() {
    var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    for (Query query : List.of(QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
        QueryFilterBuilder.buildFilterQueryOnly(filters), QueryFilterBuilder.buildChunkFilterQuery(filters))) {
      assertIndexNative(query);
    }
  }

  @Test void twentyThousandCandidatesReadOwnershipOnlyForReturnedHits() throws Exception {
    try (var directory = new ByteBuffersDirectory(); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      for (int i = 0; i < 20_000; i++) {
        var row = new Document();
        row.add(new StringField(SchemaFields.DOC_ID, "document-" + i, Field.Store.YES));
        writer.addDocument(row);
      }
      try (var reader = DirectoryReader.open(writer)) {
        var reads = new AtomicInteger();
        var searcher = new IndexSearcher(reader) {
          @Override public StoredFields storedFields() throws IOException {
            var delegate = super.storedFields();
            return new StoredFields() {
              @Override public void document(int doc, StoredFieldVisitor visitor) throws IOException {
                reads.incrementAndGet();
                delegate.document(doc, visitor);
              }
            };
          }
        };
        var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
        for (Query query : List.of(QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
            QueryFilterBuilder.buildFilterQueryOnly(filters), QueryFilterBuilder.buildChunkFilterQuery(filters))) {
          reads.set(0);
          var hits = searcher.search(query, 7);
          assertEquals(20_000, searcher.count(query));
          assertEquals(0, reads.get(), "Candidate matching must never read stored ownership fields");
          var ownership = new IndexReadOwnership(searcher);
          for (var hit : hits.scoreDocs) {
            assertEquals(io.justsearch.configuration.ReadOwnership.Owner.PUBLIC, ownership.owner(hit.doc));
          }
          assertEquals(hits.scoreDocs.length, reads.get(), "Ownership work is bounded by the returned page");
        }
      }
    }
  }

  private static void assertIndexNative(Query query) {
    assertTrue(query.getClass().getName().startsWith("org.apache.lucene."), query.toString());
    if (query instanceof BooleanQuery conjunction) {
      for (var clause : conjunction) assertIndexNative(clause.query());
    }
  }
}
