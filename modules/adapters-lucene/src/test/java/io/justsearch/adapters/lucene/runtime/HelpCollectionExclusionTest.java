/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

class HelpCollectionExclusionTest {
  @Test
  void exclusionBindsLexicalVectorSpladeAndChunkFiltersBeforeBudgets() throws Exception {
    var builder = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder();
    // Reflection keeps the before check executable: the missing request-specific field is the failure.
    exclude(builder, List.of("justsearch-help"));
    var filters = builder.build();
    try (var directory = new ByteBuffersDirectory();
         var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (String collection : List.of("files", "justsearch-help")) {
        var doc = new Document();
        doc.add(new StringField(SchemaFields.COLLECTION, collection, Field.Store.NO));
        writer.addDocument(doc);
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        for (Query query : List.of(
            QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
            QueryFilterBuilder.buildFilterQueryOnly(filters),
            QueryFilterBuilder.buildChunkFilterQuery(filters))) {
          assertEquals(1, searcher.count(query), query.toString());
        }
        assertEquals(2, searcher.count(QueryFilterBuilder.buildFilterQueryOnly(null)),
            "callers without the optional exclusion retain Help");
        var helpOnly = filters.withCollection(List.of("justsearch-help"));
        assertEquals(0, searcher.count(QueryFilterBuilder.buildFilterQueryOnly(helpOnly)),
            "positive and negative scopes intersect");
      }
    }
  }
  @Test
  void helpCannotConsumeTextDenseSpladeOrPassageCandidateBudgets() throws Exception {
    var builder = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder();
    exclude(builder, List.of("justsearch-help"));
    var filters = builder.build().withSizeFromBytes(0L);
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (int i = 0; i < 61; i++) {
        String path = i == 0 ? "/files/own" : "/help/" + i;
        for (boolean chunk : List.of(false, true)) {
          var doc = new Document();
          doc.add(new StringField(SchemaFields.DOC_ID, chunk ? "chunk:" + path : path, Field.Store.YES));
          doc.add(new StringField(SchemaFields.PATH, path, Field.Store.YES));
          doc.add(new StringField(SchemaFields.COLLECTION, i == 0 ? "files" : "justsearch-help", Field.Store.NO));
          if (chunk) {
            doc.add(new StringField(SchemaFields.IS_CHUNK, "true", Field.Store.NO));
            doc.add(new StringField(SchemaFields.PARENT_DOC_ID, path, Field.Store.NO));
          } else {
            doc.add(new org.apache.lucene.document.LongPoint(SchemaFields.SIZE_BYTES, 10L));
            doc.add(new org.apache.lucene.document.NumericDocValuesField(SchemaFields.SIZE_BYTES, 10L));
          }
          doc.add(new org.apache.lucene.document.TextField(chunk ? SchemaFields.CHUNK_CONTENT : SchemaFields.CONTENT,
              i == 0 ? "needle" : "needle ".repeat(20), Field.Store.NO));
          doc.add(new org.apache.lucene.document.FeatureField(SchemaFields.SPLADE, "needle", i == 0 ? 1f : 100f));
          doc.add(new org.apache.lucene.document.KnnFloatVectorField(
              chunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, i == 0 ? new float[] {0.9f, 0.1f} : new float[] {1f, 0f}));
          writer.addDocument(doc);
        }
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        for (boolean chunk : List.of(false, true)) {
          Query filter = chunk ? QueryFilterBuilder.buildChunkFilterQuery(filters)
              : QueryFilterBuilder.buildFilterQueryOnly(filters);
          Query text = new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(
              chunk ? SchemaFields.CHUNK_CONTENT : SchemaFields.CONTENT, "needle"));
          Query sparse = org.apache.lucene.document.FeatureField.newLinearQuery(SchemaFields.SPLADE, "needle", 1f);
          for (Query query : List.of(filtered(text, filter), filtered(sparse, filter),
              new org.apache.lucene.search.KnnFloatVectorQuery(chunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR,
                  new float[] {1f, 0f}, 1, filter))) {
            var hits = searcher.search(query, 1).scoreDocs;
            assertEquals(1, hits.length, query.toString());
            assertEquals("/files/own", searcher.storedFields().document(hits[0].doc).get(SchemaFields.PATH), query.toString());
          }
        }
      }
    }
  }

  private static Query filtered(Query content, Query filter) {
    return new org.apache.lucene.search.BooleanQuery.Builder()
        .add(content, org.apache.lucene.search.BooleanClause.Occur.MUST)
        .add(filter, org.apache.lucene.search.BooleanClause.Occur.FILTER).build();
  }


  private static void exclude(Object builder, List<String> collections) throws Exception {
    java.lang.reflect.Method method;
    try {
      method = builder.getClass().getMethod("excludedCollections", List.class);
    } catch (NoSuchMethodException e) {
      throw new AssertionError("runtime filters must accept request-specific excluded collections", e);
    }
    method.invoke(builder, collections);
  }
}
