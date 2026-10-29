/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D1/D4/D8: parent metadata, never chunk metadata or post-budget rejection. */
class ParentRestrictionQueryTest {
  @TempDir Path temp;

  @Test
  void crossedCorpusAndSaturatedChunksUseParentMetadataBeforeBudget() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/a/target", 200L, 50L);
      chunk(writer, "/a/target", "needle");
      parent(writer, "/ab/folder-decoy", 200L, 50L);
      chunk(writer, "/ab/folder-decoy", "needle needle needle");
      parent(writer, "/a/date-decoy", 300L, 50L);
      chunk(writer, "/a/date-decoy", "needle needle needle");
      for (int i = 0; i < 250; i++) {
        String id = "/a/size-decoy-" + i;
        parent(writer, id, 200L, 100L);
        chunk(writer, id, "needle needle needle needle needle");
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var filters = filters("/a", 200L, 200L, 50L, 50L);
        assertEquals(Set.of("/a/target"), paths(searcher, whole(filters), 5));
        assertEquals(Set.of("/a/target"), paths(searcher, chunks(filters), 1));
      }
    }
  }

  @Test
  void parentBoundsIntersectSearchFacetsBeforeChunkCandidates() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/a/target", 200L, 50L, "alice");
      chunk(writer, "/a/target", "needle");
      parent(writer, "/a/wrong-author", 200L, 50L, "bob");
      chunk(writer, "/a/wrong-author", "needle needle needle needle");
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        var filters = filters("/a", 200L, 200L, 50L, 50L).withMetaAuthor(List.of("alice"));
        assertEquals(Set.of("/a/target"), paths(searcher, whole(filters), 10));
        assertEquals(Set.of("/a/target"), paths(searcher, chunks(filters), 1));
      }
    }
  }

  @Test
  void inclusiveOneSidedEqualAndLargeBoundsDistinguishUnknownAndKnownZero() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/a/low", 99L, 0L);
      parent(writer, "/a/at", 100L, 4_294_967_297L);
      parent(writer, "/a/high", 101L, 4_294_967_298L);
      parent(writer, "/a/unknown", null, null);
      for (String name : List.of("low", "at", "high", "unknown")) chunk(writer, "/a/" + name, "needle");
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        checkBoth(searcher, filters(null, 0L, 0L, null, null), Set.of("/a/low", "/a/at", "/a/high", "/a/unknown"));
        checkBoth(searcher, filters(null, 100L, null, null, null), Set.of("/a/at", "/a/high"));
        checkBoth(searcher, filters(null, null, 100L, null, null), Set.of("/a/low", "/a/at"));
        checkBoth(searcher, filters(null, 100L, 100L, null, null), Set.of("/a/at"));
        checkBoth(searcher, filters(null, null, null, 0L, 0L), Set.of("/a/low"));
        checkBoth(searcher, filters(null, null, null, 4_294_967_297L, null), Set.of("/a/at", "/a/high"));
        checkBoth(searcher, filters(null, null, null, null, 4_294_967_297L), Set.of("/a/low", "/a/at"));
        checkBoth(searcher, filters(null, 99L, 101L, 0L, 4_294_967_297L), Set.of("/a/low", "/a/at"));
        checkBoth(searcher, filters("/nonexistent", null, null, null, null), Set.of());
      }
    }
  }

  @Test
  void knownEpochZeroMatchesAnUpperDateBoundWhileUnknownDoesNot() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      parent(writer, "/a/epoch", 0L, 0L);
      chunk(writer, "/a/epoch", "needle");
      parent(writer, "/a/unknown", null, null);
      chunk(writer, "/a/unknown", "needle");
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        checkBoth(searcher, filters(null, null, 1L, null, null), Set.of("/a/epoch"));
        checkBoth(searcher, filters(null, null, null, 0L, 0L), Set.of("/a/epoch"));
      }
    }
  }

  @Test
  void sourceBytesAndFilesystemMtimeStayDistinctFromTextAndPublicationTime() throws Exception {
    Path sparse = temp.resolve("sparse.bin");
    try (var channel = java.nio.channels.FileChannel.open(sparse,
        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
        java.nio.file.StandardOpenOption.SPARSE)) {
      channel.position(4_294_967_296L);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    java.nio.file.Files.setLastModifiedTime(sparse, java.nio.file.attribute.FileTime.fromMillis(1234));
    Path compressed = temp.resolve("compressed.zip");
    try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(compressed))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("body.txt"));
      zip.write("needle ".repeat(20000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    java.nio.file.Files.setLastModifiedTime(compressed, java.nio.file.attribute.FileTime.fromMillis(5678));
    assertTrue(java.nio.file.Files.size(compressed) < 140000);
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (Path file : List.of(sparse, compressed)) {
        parent(writer, file.toString(), java.nio.file.Files.getLastModifiedTime(file).toMillis(), java.nio.file.Files.size(file));
        chunk(writer, file.toString(), "needle");
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        checkBoth(searcher, filters(null, 1234L, 1234L, 4_294_967_297L, 4_294_967_297L), Set.of(sparse.toString()));
        long compressedBytes = java.nio.file.Files.size(compressed);
        checkBoth(searcher, filters(null, 5678L, 5678L, compressedBytes, compressedBytes), Set.of(compressed.toString()));
        checkBoth(searcher, filters(null, 999999L, 999999L, null, null), Set.of());
      }
    }
  }

  @Test
  void folderPrefixesAreLiteralDirectories() throws Exception {
    try (var dir = new ByteBuffersDirectory();
        var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (String id : List.of("/a/f", "/ab/f", "/space ü/%_/child/f", "/space ü/xyz/f")) {
        parent(writer, id, 100L, 0L);
        chunk(writer, id, "needle");
      }
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        checkBoth(searcher, filters("/a/", null, null, null, null), Set.of("/a/f"));
        checkBoth(searcher, filters("/a", null, null, null, null), Set.of("/a/f"));
        checkBoth(searcher, filters("/space ü/%_", null, null, null, null), Set.of("/space ü/%_/child/f"));
        assertEquals(4, paths(searcher, chunks(filters("/", null, null, null, null)), 10).size());
      }
    }
  }

  @Test
  void reopensLegacyFieldIndexThenParentUpdateChangesChunkEligibility() throws Exception {
    // Run with -Dwp23.legacyIndex=/... to consume an index emitted by this test on the baseline.
    Path index = System.getProperty("wp23.legacyIndex") == null ? temp
        : Path.of(System.getProperty("wp23.legacyIndex"));
    try (var dir = FSDirectory.open(index)) {
      if (!DirectoryReader.indexExists(dir)) {
        try (var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
          parent(writer, "/a/legacy", 100L, 50L);
          chunk(writer, "/a/legacy", "needle");
        }
      }
      try (var reader = DirectoryReader.open(dir)) {
        checkBoth(new IndexSearcher(reader), filters("/a", 100L, 100L, 50L, 50L), Set.of("/a/legacy"));
      }
      try (var writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
        writer.deleteDocuments(new Term(SchemaFields.DOC_ID, "/a/legacy"));
        parent(writer, "/a/legacy", 200L, 100L);
      }
      try (var reader = DirectoryReader.open(dir)) {
        checkBoth(new IndexSearcher(reader), filters("/a", 100L, 100L, 50L, 50L), Set.of());
        checkBoth(new IndexSearcher(reader), filters("/a", 200L, 200L, 100L, 100L), Set.of("/a/legacy"));
      }
    }
  }

  private static LuceneRuntimeTypes.RuntimeSearchFilters filters(
      String path, Long from, Long to, Long sizeFrom, Long sizeTo) throws Exception {
    var builder = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().pathPrefix(path)
        .modifiedFromMs(from).modifiedToMs(to);
    // Keeps the same check runnable on the before-build: absence of size support produces an
    // assertion failure from leaked results rather than a compilation failure.
    try {
      builder.getClass().getMethod("sizeFromBytes", Long.class).invoke(builder, sizeFrom);
      builder.getClass().getMethod("sizeToBytes", Long.class).invoke(builder, sizeTo);
    } catch (NoSuchMethodException absentOnBaseline) {
      // Baseline has no size fields; exercise its actual unfiltered query.
    }
    return builder.build();
  }

  private static Query whole(LuceneRuntimeTypes.RuntimeSearchFilters filters) {
    return QueryFilterBuilder.applyRuntimeFilters(new TermQuery(new Term(SchemaFields.CONTENT, "needle")), filters);
  }

  private static Query chunks(LuceneRuntimeTypes.RuntimeSearchFilters filters) {
    return new BooleanQuery.Builder()
        .add(new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, "needle")), BooleanClause.Occur.MUST)
        .add(QueryFilterBuilder.buildChunkFilterQuery(filters), BooleanClause.Occur.FILTER).build();
  }

  private static void checkBoth(IndexSearcher searcher, LuceneRuntimeTypes.RuntimeSearchFilters filters,
      Set<String> expected) throws Exception {
    assertEquals(expected, paths(searcher, whole(filters), 100));
    assertEquals(expected, paths(searcher, chunks(filters), 100));
  }

  private static Set<String> paths(IndexSearcher searcher, Query query, int limit) throws Exception {
    Set<String> paths = new HashSet<>();
    for (var hit : searcher.search(query, limit).scoreDocs) {
      paths.add(searcher.storedFields().document(hit.doc).get(SchemaFields.PATH));
    }
    return paths;
  }

  private static void parent(IndexWriter writer, String id, Long date, Long size) throws Exception {
    parent(writer, id, date, size, "alice");
  }

  private static void parent(IndexWriter writer, String id, Long date, Long size, String author) throws Exception {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.META_AUTHOR, author, Field.Store.NO));
    doc.add(new StringField(SchemaFields.DOC_ID, id, Field.Store.YES));
    doc.add(new StringField(SchemaFields.PATH, id, Field.Store.YES));
    doc.add(new TextField(SchemaFields.CONTENT, "needle", Field.Store.NO));
    // Deliberately divergent publication time/text length; source metadata alone decides.
    number(doc, SchemaFields.META_PUBLISHED_AT, 999999L);
    if (date != null) number(doc, SchemaFields.MODIFIED_AT, date);
    if (size != null) number(doc, SchemaFields.SIZE_BYTES, size);
    writer.addDocument(doc);
  }

  private static void chunk(IndexWriter writer, String parent, String text) throws Exception {
    var doc = new Document();
    doc.add(new StringField(SchemaFields.DOC_ID, "chunk:" + parent, Field.Store.YES));
    doc.add(new StringField(SchemaFields.PATH, parent, Field.Store.YES));
    doc.add(new StringField(SchemaFields.IS_CHUNK, "true", Field.Store.NO));
    doc.add(new TextField(SchemaFields.CHUNK_CONTENT, text, Field.Store.NO));
    writer.addDocument(doc);
  }

  private static void number(Document doc, String field, long value) {
    doc.add(new LongPoint(field, value));
    doc.add(new NumericDocValuesField(field, value));
  }
}
