/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.configuration.InternalCollections;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

final class LegacyHistorySourceScopeTest {
  @Test void lexicalVectorAndChunkFiltersExcludeLegacySourcesIndependentlyOfTags() throws Exception {
    String history = InternalCollections.dataDirectory().resolve("agent-history/run.md").toString();
    try (var directory = new ByteBuffersDirectory(); var analyzer = new StandardAnalyzer();
        var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      for (String ownership : List.of(SchemaFields.PATH, SchemaFields.PARENT_DOC_ID, SchemaFields.DOC_ID)) {
        for (String collection : List.of("", "public")) {
          var doc = new Document();
          doc.add(new StringField(ownership, history, Field.Store.YES));
          if (!collection.isBlank()) doc.add(new StringField(SchemaFields.COLLECTION, collection, Field.Store.YES));
          writer.addDocument(doc);
        }
      }
      var tagged = new Document();
      tagged.add(new StringField(SchemaFields.PATH, history, Field.Store.YES));
      tagged.add(new StringField(SchemaFields.COLLECTION, "agent-history", Field.Store.YES));
      writer.addDocument(tagged);
      var ordinary = new Document();
      ordinary.add(new StringField(SchemaFields.PATH, "/documents/notes.md", Field.Store.YES));
      ordinary.add(new StringField(SchemaFields.COLLECTION, "public", Field.Store.YES));
      writer.addDocument(ordinary);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        for (var scope : List.of(List.<String>of(), List.of("public"))) {
          var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().collection(scope).includeChunks(true).build();
          for (Query query : List.of(QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
              QueryFilterBuilder.buildFilterQueryOnly(filters), QueryFilterBuilder.buildChunkFilterQuery(filters))) {
            assertEquals(1, searcher.count(query), query.toString());
          }
        }
        var own = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().collection(List.of("agent-history")).build();
        assertEquals(1, searcher.count(QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), own)));
      }
    }
  }
}
