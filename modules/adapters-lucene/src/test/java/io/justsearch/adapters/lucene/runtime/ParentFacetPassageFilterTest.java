/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0020: parent-only predicates restrict passages before retrieval budgets. */
class ParentFacetPassageFilterTest {
  private static final List<String> PARENT_FIELDS = List.of(SchemaFields.META_AUTHOR,
      SchemaFields.META_SOURCE, SchemaFields.META_CATEGORY, SchemaFields.ENTITY_PERSONS_RAW,
      SchemaFields.ENTITY_ORGANIZATIONS_RAW, SchemaFields.ENTITY_LOCATIONS_RAW);

  @TempDir Path temp;

  private static RuntimeSearchFilters empty() {
    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
  }

  private static List<RuntimeSearchFilters> predicates() {
    return List.of(empty().withMetaAuthor(List.of("wanted")),
        empty().withMetaSource(List.of("wanted")), empty().withMetaCategory(List.of("wanted")),
        empty().withEntityPersons(List.of("wanted")),
        empty().withEntityOrganizations(List.of("wanted")),
        empty().withEntityLocations(List.of("wanted")),
        empty().withMetaPublishedFromMs(100L).withMetaPublishedToMs(100L));
  }

  @Test
  void everyParentFacetScopesTextDenseAndSparseBeforeSaturatedBudgets() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/scope/target", "wanted", 100L);
      chunk(writer, "/scope/target", false);
      for (int i = 0; i < 1100; i++) {
        String path = "/scope/excluded-" + i;
        parent(writer, path, "excluded", 200L);
        chunk(writer, path, true);
      }
      parent(writer, "/scope/unknown", null, null);
      chunk(writer, "/scope/unknown", true);
      chunk(writer, "/scope/orphan", true);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        for (var predicate : predicates()) {
          for (var filters : List.of(predicate, predicate.withModifiedFromMs(1L),
              predicate.withSizeFromBytes(0L))) {
            Query filter = QueryFilterBuilder.buildChunkFilterQuery(filters);
            Query text = filtered(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, "needle")), filter);
            Query sparse = filtered(FeatureField.newLinearQuery(SchemaFields.SPLADE, "needle", 1f), filter);
            assertEquals(1, searcher.count(text), filters.toString());
            assertEquals(Set.of("/scope/target"), paths(searcher, text, 1));
            assertEquals(Set.of("/scope/target"), paths(searcher, sparse, 1));
            assertEquals(Set.of("/scope/target"), paths(searcher,
                new KnnFloatVectorQuery(SchemaFields.CHUNK_VECTOR, new float[] {1f, 0f}, 1, filter), 1));
          }
        }
      }
    }
  }

  @Test
  void parentFacetsIntersectFolderDateAndSelectionAndEmptyMeansEmpty() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (String path : List.of("/scope/target", "/other/target")) {
        parent(writer, path, "wanted", 100L);
        chunk(writer, path, false);
      }
      parent(writer, "/scope/excluded", "excluded", 100L);
      chunk(writer, "/scope/excluded", true);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var author = empty().withMetaAuthor(List.of("wanted"));
        assertEquals(Set.of("/scope/target"), chunkPaths(searcher, author.withPathPrefix("/scope")));
        assertEquals(Set.of("/scope/target", "/other/target"),
            chunkPaths(searcher, author.withModifiedFromMs(50L).withModifiedToMs(50L)));
        assertEquals(Set.of(), chunkPaths(searcher, author.withModifiedFromMs(51L)));
        assertEquals(Set.of("/scope/target"), chunkPaths(searcher,
            empty().withMetaCategory(List.of("wanted")).withDocIds(List.of("/scope/target", "/scope/excluded"))));
        assertEquals(Set.of(), chunkPaths(searcher, empty().withMetaAuthor(List.of("absent"))));
      }
    }
  }

  @Test
  void ineffectivePredicatesDoNotResolveParentsOrSuppressOrphans() throws Exception {
    assertTrue(hasParentQuery(QueryFilterBuilder.buildChunkFilterQuery(predicates().getFirst())));
    var blanks = Arrays.asList(null, "", " \t");
    var inactive = empty().withMetaAuthor(blanks).withMetaSource(blanks).withMetaCategory(blanks)
        .withEntityPersons(blanks).withEntityOrganizations(blanks).withEntityLocations(blanks)
        .withMetaPublishedFromMs(0L).withMetaPublishedToMs(-1L);
    for (var filters : List.of(empty(), inactive)) {
      assertFalse(hasParentQuery(QueryFilterBuilder.buildChunkFilterQuery(filters)));
    }
    assertFalse(hasParentQuery(QueryFilterBuilder.buildChunkFilterQuery(null)));
    for (var filters : List.of(empty().withModifiedFromMs(1L), empty().withSizeToBytes(0L),
        empty().withModifiedToMs(1L).withSizeFromBytes(0L))) {
      assertEquals(1, ((BooleanQuery) QueryFilterBuilder.buildChunkFilterQuery(filters)).clauses()
          .stream().filter(c -> c.query() instanceof ParentMetadataQuery).count());
    }
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      chunk(writer, "/orphan", false);
      try (var reader = DirectoryReader.open(writer)) {
        assertEquals(Set.of("/orphan"), chunkPaths(new IndexSearcher(reader), inactive));
      }
    }
  }

  @Test
  void largeListsKeepOrIntersectionDuplicatesBlanksAndExactValues() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/scope/target", "wanted", 100L);
      parent(writer, "/scope/excluded", "excluded", 100L);
      chunk(writer, "/scope/target", false);
      chunk(writer, "/scope/excluded", true);
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        for (int size : List.of(1024, 10000)) {
          var values = new ArrayList<String>();
          for (int i = 0; i < size; i++) values.add("/missing/" + i);
          values.addAll(Arrays.asList(null, "", " ", "/scope/target", "/scope/target"));
          var filters = empty().withDocIds(values);
          for (Query query : assertDoesNotThrow(() -> List.of(
              QueryFilterBuilder.applyRuntimeFilters(new MatchAllDocsQuery(), filters),
              QueryFilterBuilder.buildFilterQueryOnly(filters)))) {
            assertEquals(Set.of("/scope/target"), paths(searcher, query, 10));
          }
          assertEquals(Set.of("/scope/target"), assertDoesNotThrow(() -> chunkPaths(searcher, filters)));
          values.add("wanted");
          List<UnaryOperator<RuntimeSearchFilters>> facets = List.of(
              f -> f.withMetaAuthor(values), f -> f.withMetaCategory(values), f -> f.withMetaSource(values),
              f -> f.withEntityPersons(values), f -> f.withEntityOrganizations(values), f -> f.withEntityLocations(values),
              f -> f.withMime(values), f -> f.withMimeBase(values), f -> f.withFileKind(values), f -> f.withLanguage(values),
              f -> f.withCollection(values));
          for (var facet : facets) {
            var scoped = facet.apply(empty()).withDocIds(List.of("/scope/target", "/scope/excluded"));
            assertEquals(Set.of("/scope/target"), assertDoesNotThrow(() -> paths(searcher,
                QueryFilterBuilder.buildFilterQueryOnly(scoped), 10)));
            assertEquals(Set.of("/scope/target"), assertDoesNotThrow(() -> chunkPaths(searcher, scoped)));
          }
          assertEquals(Set.of(), chunkPaths(searcher, empty().withDocIds(List.of(" /scope/target"))));
          assertEquals(Set.of("/scope/target", "/scope/excluded"),
              chunkPaths(searcher, empty().withDocIds(Arrays.asList(null, "", " "))));
        }
      }
    }
  }

  @Test
  void repeatedQueryFollowsRefreshAndReopenWithoutChangingOldSearcher() throws Exception {
    Query query = QueryFilterBuilder.buildChunkFilterQuery(empty().withMetaAuthor(List.of("wanted")));
    try (var dir = FSDirectory.open(temp);
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/scope/target", "wanted", 100L);
      parent(writer, "/scope/excluded", "excluded", 100L);
      chunk(writer, "/scope/target", false);
      chunk(writer, "/scope/excluded", true);
      try (var old = DirectoryReader.open(writer)) {
        assertEquals(Set.of("/scope/target"), paths(new IndexSearcher(old), query, 10));
        writer.deleteDocuments(new Term(SchemaFields.DOC_ID, "/scope/target"));
        parent(writer, "/scope/target", "excluded", 100L);
        writer.deleteDocuments(new Term(SchemaFields.DOC_ID, "/scope/excluded"));
        parent(writer, "/scope/excluded", "wanted", 100L);
        try (var refreshed = DirectoryReader.openIfChanged(old, writer)) {
          assertNotNull(refreshed);
          assertEquals(Set.of("/scope/excluded"), paths(new IndexSearcher(refreshed), query, 10));
          assertEquals(Set.of("/scope/target"), paths(new IndexSearcher(old), query, 10));
        }
        writer.deleteDocuments(new Term(SchemaFields.DOC_ID, "/scope/excluded"));
        writer.commit();
      }
    }
    try (var dir = FSDirectory.open(temp); var reopened = DirectoryReader.open(dir)) {
      assertEquals(Set.of(), paths(new IndexSearcher(reopened), query, 10));
    }
  }

  @Test
  void broadMetadataAndDateCostOnTenThousandParents() throws Exception {
    assertTrue(hasParentQuery(QueryFilterBuilder.buildChunkFilterQuery(predicates().getFirst())));
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (int i = 0; i < 10000; i++) {
        String path = "/scope/" + i;
        parent(writer, path, i == 0 ? "excluded" : "wanted", 100L);
        chunk(writer, path, false);
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var metadata = filtered(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
            QueryFilterBuilder.buildChunkFilterQuery(predicates().getFirst()));
        var date = filtered(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
            QueryFilterBuilder.buildChunkFilterQuery(empty().withModifiedFromMs(1L)));
        for (int i = 0; i < 8; i++) {
          long start = System.nanoTime();
          assertEquals(9999, searcher.count(metadata));
          long middle = System.nanoTime();
          assertEquals(10000, searcher.count(date));
          System.out.printf("broad-parent-filter run=%d parents=10000 metadataMs=%.3f dateMs=%.3f%n",
              i, (middle - start) / 1e6, (System.nanoTime() - middle) / 1e6);
        }
      }
    }
  }

  private static boolean hasParentQuery(Query query) {
    return query instanceof BooleanQuery bq && bq.clauses().stream()
        .anyMatch(c -> c.query() instanceof ParentMetadataQuery);
  }

  private static Query filtered(Query query, Query filter) {
    return new BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST)
        .add(filter, BooleanClause.Occur.FILTER).build();
  }

  private static Set<String> chunkPaths(IndexSearcher searcher, RuntimeSearchFilters filters) throws Exception {
    return paths(searcher, filtered(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, "needle")),
        QueryFilterBuilder.buildChunkFilterQuery(filters)), 100);
  }

  private static Set<String> paths(IndexSearcher searcher, Query query, int limit) throws Exception {
    Set<String> result = new HashSet<>();
    for (var hit : searcher.search(query, limit).scoreDocs) {
      result.add(searcher.storedFields().document(hit.doc).get(SchemaFields.PATH));
    }
    return result;
  }

  private static void parent(IndexWriter writer, String path, String value, Long published) throws Exception {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.DOC_ID, path, Field.Store.YES));
    doc.add(new StringField(SchemaFields.PATH, path, Field.Store.YES));
    if (value != null) {
      for (String field : PARENT_FIELDS) doc.add(new StringField(field, value, Field.Store.NO));
      for (String field : List.of(SchemaFields.MIME, SchemaFields.MIME_BASE, SchemaFields.FILE_KIND,
          SchemaFields.LANGUAGE, SchemaFields.COLLECTION)) doc.add(new StringField(field, value, Field.Store.NO));
    }
    if (published != null) number(doc, SchemaFields.META_PUBLISHED_AT, published);
    number(doc, SchemaFields.MODIFIED_AT, 50L);
    number(doc, SchemaFields.SIZE_BYTES, 0L);
    writer.addDocument(doc);
  }

  private static void chunk(IndexWriter writer, String path, boolean stronger) throws Exception {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.DOC_ID, "chunk:" + path, Field.Store.YES));
    doc.add(new StringField(SchemaFields.PATH, path, Field.Store.YES));
    doc.add(new StringField(SchemaFields.IS_CHUNK, "true", Field.Store.NO));
    for (String field : List.of(SchemaFields.MIME, SchemaFields.MIME_BASE, SchemaFields.FILE_KIND,
        SchemaFields.LANGUAGE, SchemaFields.COLLECTION)) {
      doc.add(new StringField(field, path.endsWith("target") ? "wanted" : "excluded", Field.Store.NO));
    }
    doc.add(new TextField(SchemaFields.CHUNK_CONTENT, stronger ? "needle needle needle needle" : "needle", Field.Store.NO));
    doc.add(new FeatureField(SchemaFields.SPLADE, "needle", stronger ? 10f : 1f));
    doc.add(new KnnFloatVectorField(SchemaFields.CHUNK_VECTOR, stronger ? new float[] {1f, 0f} : new float[] {0.9f, 0.1f}));
    writer.addDocument(doc);
  }

  private static void number(Document doc, String field, long value) {
    doc.add(new LongPoint(field, value));
    doc.add(new NumericDocValuesField(field, value));
  }
}
