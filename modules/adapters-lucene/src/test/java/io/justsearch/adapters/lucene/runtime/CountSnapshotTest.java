/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

class CountSnapshotTest {
  @Test
  void mergeCountersUseOneAcquiredReaderAndPropagateIoFailures() throws Exception {
    SearcherBridge bridge = mock(SearcherBridge.class);
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer())
            .setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
      Document deleted = new Document();
      deleted.add(new StringField("doc_id", "deleted", Field.Store.NO));
      writer.addDocument(deleted);
      writer.addDocument(new Document());
      writer.flush();
      writer.deleteDocuments(new org.apache.lucene.index.Term("doc_id", "deleted"));
      try (var reader = DirectoryReader.open(writer)) {
        var searcher = new IndexSearcher(reader);
        when(bridge.withSearcher(any())).thenAnswer(invocation ->
            ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(searcher));
        var counts = new IndexCountOps(bridge);
        var result = mergeCounts(counts);
        assertArrayEquals(new long[] {2, 1}, result);
        verify(bridge, times(1)).withSearcher(any());
        doThrow(new IOException("reader acquisition failed")).when(bridge).withSearcher(any());
        assertThrows(IOException.class, () -> mergeCounts(counts),
            "unreadable merge debt must not be a zero measurement");
        doAnswer(invocation -> ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(searcher))
            .when(bridge).withSearcher(any());
        assertArrayEquals(new long[] {2, 1}, mergeCounts(counts));
      }
    }
  }

  /** The before-state reads each counter separately and swallows IO failures. */
  private static long[] mergeCounts(IndexCountOps counts) throws Exception {
    try {
      var method = IndexCountOps.class.getMethod("mergeCountsOrThrow");
      var result = method.invoke(counts);
      return new long[] {
          (Long) result.getClass().getMethod("maxDoc").invoke(result),
          (Long) result.getClass().getMethod("numDocs").invoke(result)};
    } catch (NoSuchMethodException baseline) {
      return new long[] {counts.maxDoc(), counts.docCount()};
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"embedding", "splade", "chunkEmbedding", "chunkSplade", "joint"})
  void individualCountFailureIsUnknown(String stage) throws Exception {
    SearcherBridge bridge = mock(SearcherBridge.class);
    when(bridge.withSearcher(any())).thenThrow(new IOException("unreadable " + stage));
    var counts = new IndexCountOps(bridge);
    long result = switch (stage) {
      case "embedding" -> counts.queryEmbeddingCounts().pending();
      case "splade" -> counts.querySpladeFeatureCounts().pending();
      case "chunkEmbedding" -> counts.queryChunkEmbeddingCounts().pending();
      case "chunkSplade" -> counts.queryChunkSpladeCounts().pending();
      default -> counts.queryEmbeddedAndSparseCount();
    };
    assertEquals(-1, result);
  }

  @Test
  void zeroChunkExemptionRequiresCurrentParentDeclarationsAndSurvivesReopen() throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document parent = new Document();
      parent.add(new StringField("doc_id", "short", Field.Store.YES));
      parent.add(new StringField("chunk_total", "0", Field.Store.YES));
      parent.add(new StringField("chunk_parent_content_sha256", "revision", Field.Store.YES));
      parent.add(new StringField("content_sha256", "revision", Field.Store.YES));
      writer.addDocument(parent);
      writer.commit();
      for (int reopen = 0; reopen < 2; reopen++) {
        try (var reader = DirectoryReader.open(directory)) {
          SearcherBridge bridge = mock(SearcherBridge.class);
          when(bridge.withSearcher(any())).thenAnswer(invocation ->
              ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(new IndexSearcher(reader)));
          var counts = new IndexCountOps(bridge);
          var result = counts.queryChunkVectorPresenceCount();
          assertEquals(0, result.totalChunks());
          assertTrue(zeroChunkExempt(result), "A current zero-chunk declaration must qualify");
          assertFalse(result.isReady(95), "Retrieval eligibility still needs actual chunk vectors");
        }
      }
      for (String kind : new String[] {"unknown", "missing", "stale", "orphan"}) {
        Document inconsistent = new Document();
        inconsistent.add(new StringField("doc_id", kind, Field.Store.YES));
        if (kind.equals("orphan")) inconsistent.add(new StringField("is_chunk", "true", Field.Store.NO));
        if (!kind.equals("unknown")) {
          inconsistent.add(new StringField("chunk_total", kind.equals("missing") ? "2" : "0", Field.Store.YES));
          inconsistent.add(new StringField("chunk_parent_content_sha256", "old", Field.Store.YES));
          inconsistent.add(new StringField("content_sha256", kind.equals("stale") ? "new" : "old", Field.Store.YES));
        }
        writer.addDocument(inconsistent);
        try (var reader = DirectoryReader.open(writer)) {
          SearcherBridge bridge = mock(SearcherBridge.class);
          when(bridge.withSearcher(any())).thenAnswer(invocation ->
              ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(new IndexSearcher(reader)));
          assertFalse(zeroChunkExempt(new IndexCountOps(bridge).queryChunkVectorPresenceCount()), kind);
        }
        writer.deleteDocuments(new org.apache.lucene.index.Term("doc_id", kind));
      }
    }
  }

  private static boolean zeroChunkExempt(Object presence) throws Exception {
    for (var component : presence.getClass().getRecordComponents()) {
      if (component.getName().equals("chunkingNotRequired")) return (Boolean) component.getAccessor().invoke(presence);
    }
    return false;
  }

  @Test
  void failedCountsAreUnknownAndRetryTheSameReader() throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document document = new Document();
      document.add(new StringField("is_chunk", "true", Field.Store.NO));
      document.add(new KnnFloatVectorField("chunk_vector", new float[] {1, 0}));
      writer.addDocument(document);
      try (var reader = DirectoryReader.open(writer)) {
        SearcherBridge bridge = mock(SearcherBridge.class);
        IndexSearcher searcher = new IndexSearcher(reader);
        when(bridge.withSearcher(any())).thenThrow(new IOException("unreadable"));
        var counts = new IndexCountOps(bridge);
        assertEquals(-1, counts.queryEmbeddingCounts().total());
        assertEquals(-1, counts.querySpladeFeatureCounts().total());
        assertEquals(-1, counts.queryChunkSpladeCounts().total());
        assertEquals(-1, counts.queryChunkEmbeddingCounts().total());
        assertNull(counts.queryStageCompletenessCounts());
        assertEquals(-1, counts.queryChunkVectorPresenceCount().totalChunks());
        doAnswer(invocation -> ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(searcher))
            .when(bridge).withSearcher(any());
        var recovered = counts.queryChunkVectorPresenceCount();
        assertEquals(1, recovered.totalChunks());
        assertEquals(1, recovered.vectorsPresent());
        assertTrue(recovered.isReady(95));
        assertSame(recovered, counts.queryChunkVectorPresenceCount());
      }
    }
  }

  @Test
  void changedReaderNeverReusesOldDataEvenWithoutDirectoryVersions() throws Exception {
    try (var first = new org.apache.lucene.index.MultiReader();
        var second = new org.apache.lucene.index.MultiReader()) {
      IndexSearcher oldSearcher = mock(IndexSearcher.class);
      IndexSearcher newSearcher = mock(IndexSearcher.class);
      when(oldSearcher.getIndexReader()).thenReturn(first);
      when(newSearcher.getIndexReader()).thenReturn(second);
      when(oldSearcher.count(any())).thenReturn(1);
      when(newSearcher.count(any())).thenReturn(2);
      SearcherBridge bridge = mock(SearcherBridge.class);
      var serving = new java.util.concurrent.atomic.AtomicReference<>(oldSearcher);
      when(bridge.withSearcher(any())).thenAnswer(invocation ->
          ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(serving.get()));
      var counts = new IndexCountOps(bridge);
      assertEquals(1, counts.queryChunkVectorPresenceCount().totalChunks());
      serving.set(newSearcher);
      assertEquals(2, counts.queryChunkVectorPresenceCount().totalChunks());
      verify(bridge, times(2)).withSearcher(any());
    }
  }
  @Test
  void cachedCountsUseTheSearcherTheyMeasured() throws Exception {
    try (var first = new org.apache.lucene.index.MultiReader();
        var second = new org.apache.lucene.index.MultiReader()) {
      IndexSearcher oldSearcher = mock(IndexSearcher.class);
      IndexSearcher newSearcher = mock(IndexSearcher.class);
      when(oldSearcher.getIndexReader()).thenReturn(first);
      when(newSearcher.getIndexReader()).thenReturn(second);
      when(oldSearcher.count(any())).thenReturn(1);
      when(newSearcher.count(any())).thenReturn(2);
      for (String operation : new String[] {"vectors", "completeness", "root"}) {
        SearcherBridge bridge = mock(SearcherBridge.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(bridge.withSearcher(any())).thenAnswer(invocation ->
            ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(
                calls.getAndIncrement() == 0 ? oldSearcher : newSearcher));
        var counts = new IndexCountOps(bridge);
        int observed = switch (operation) {
          case "vectors" -> counts.queryChunkVectorPresenceCount().totalChunks();
          case "completeness" -> counts.queryStageCompletenessCounts().embedding().expected();
          default -> counts.queryRootCoverageCounts("/library").parentDocsTotalEmbedding();
        };
        assertEquals(1, observed, operation + " must use one snapshot");
        assertEquals(1, calls.get(), operation + " must acquire only once");
      }
    }
  }

  @Test
  void vectorReadFailureRecoversWithoutReopening() throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document document = new Document();
      document.add(new StringField("is_chunk", "true", Field.Store.NO));
      document.add(new KnnFloatVectorField("chunk_vector", new float[] {1, 0}));
      writer.addDocument(document);
      try (var reader = DirectoryReader.open(writer)) {
        IndexSearcher searcher = spy(new IndexSearcher(reader));
        doThrow(new IOException("transient count failure")).doCallRealMethod().when(searcher).count(any());
        SearcherBridge bridge = mock(SearcherBridge.class);
        when(bridge.withSearcher(any())).thenAnswer(invocation ->
            ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(searcher));
        var counts = new IndexCountOps(bridge);
        assertFalse(counts.queryChunkVectorPresenceCount().isReady(95));
        assertTrue(counts.queryChunkVectorPresenceCount().isReady(95));
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"vectors", "completeness", "root"})
  void lateOldComputationCannotMakeNewReaderCountsLookCurrent(String operation) throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      writer.addDocument(new Document());
      try (var first = DirectoryReader.open(writer)) {
      writer.addDocument(new Document());
      try (var second = DirectoryReader.open(writer)) {
      IndexSearcher oldSearcher = mock(IndexSearcher.class);
      IndexSearcher newSearcher = mock(IndexSearcher.class);
      when(oldSearcher.getIndexReader()).thenReturn(first);
      when(newSearcher.getIndexReader()).thenReturn(second);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var finishOld = new java.util.concurrent.CountDownLatch(1);
      when(oldSearcher.count(any())).thenAnswer(invocation -> {
        entered.countDown();
        if (!finishOld.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("old computation stalled");
        return 1;
      });
      when(newSearcher.count(any())).thenReturn(2);
      SearcherBridge bridge = mock(SearcherBridge.class);
      var serving = new java.util.concurrent.atomic.AtomicReference<>(oldSearcher);
      when(bridge.withSearcher(any())).thenAnswer(invocation ->
          ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(serving.get()));
      var counts = new IndexCountOps(bridge);
      var old = executor.submit(() -> cachedCount(counts, operation));
      try {
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        serving.set(newSearcher);
        assertEquals(2, cachedCount(counts, operation));
      } finally {
        finishOld.countDown();
      }
      assertEquals(1, old.get(10, java.util.concurrent.TimeUnit.SECONDS));
      // Force the legacy publication order: newer data, older data, newer version.
      String versionField = operation.equals("vectors") ? "cachedChunkVectorPresenceVersion"
          : operation.equals("completeness") ? "cachedStageCompletenessVersion" : "";
      try {
        var version = IndexCountOps.class.getDeclaredField(versionField);
        version.setAccessible(true);
        version.setLong(counts, second.getVersion());
      } catch (NoSuchFieldException atomicPublication) {
        // A reader and its data published as one value cannot represent this interleaving.
      }
      assertEquals(2, cachedCount(counts, operation));
      }
      }
    }
  }

  @Test
  void folderDefectsAreSeparateFromTerminalCountsAndRespectLiveDocuments() throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (String stage : new String[] {"embedding", "splade", "ner", "chunkVectors"}) {
        String field = switch (stage) {
          case "embedding" -> "embedding_status";
          case "chunkVectors" -> "chunk_embedding_status";
          default -> stage + "_status";
        };
        Document failed = coverageDocument(stage, field, "FAILED");
        writer.addDocument(failed);
      }
      Document completedMissing = coverageDocument("missing", "embedding_status", "COMPLETED");
      writer.addDocument(completedMissing);
      writer.addDocument(coverageDocument("chunkMissing", "chunk_embedding_status", "COMPLETED"));
      Document empty = coverageDocument("empty", "splade_status", "COMPLETED_EMPTY");
      writer.addDocument(empty);
      try (var reader = DirectoryReader.open(writer)) {
        var counts = countsFor(reader);
        var result = counts.queryRootCoverageCounts("/library");
        assertEquals(2, result.parentDocsSettledEmbedding());
        assertEquals(2, result.chunkDocsSettled());
        for (String stage : new String[] {"embedding", "splade", "ner", "chunkVectors"}) {
          assertTrue(defect(result, "failedStages", stage), stage);
        }
        assertTrue(defect(result, "missingArtifactStages", "embedding"));
        assertTrue(defect(result, "missingArtifactStages", "chunkVectors"));
      }
      writer.deleteDocuments(new org.apache.lucene.index.Term("state", "FAILED"),
          new org.apache.lucene.index.Term("doc_id", "missing"),
          new org.apache.lucene.index.Term("doc_id", "chunkMissing"));
      try (var reader = DirectoryReader.open(writer)) {
        var result = countsFor(reader).queryRootCoverageCounts("/library");
        assertFalse(defect(result, "failedStages", "splade"));
        assertFalse(defect(result, "missingArtifactStages", "embedding"));
        assertFalse(defect(result, "missingArtifactStages", "chunkVectors"));
        assertEquals(1, result.parentDocsSettledSplade(), "COMPLETED_EMPTY stays successful");
      }
    }
  }

  private static Document coverageDocument(String id, String field, String status) {
    Document doc = new Document();
    doc.add(new StringField("doc_id", id, Field.Store.NO));
    doc.add(new StringField("path", "/library/" + id, Field.Store.NO));
    doc.add(new StringField("state", status, Field.Store.NO));
    doc.add(new StringField(field, status, Field.Store.NO));
    doc.add(new org.apache.lucene.document.SortedDocValuesField(field, new org.apache.lucene.util.BytesRef(status)));
    if (field.equals("chunk_embedding_status")) doc.add(new StringField("is_chunk", "true", Field.Store.NO));
    return doc;
  }

  private static IndexCountOps countsFor(DirectoryReader reader) throws IOException {
    SearcherBridge bridge = mock(SearcherBridge.class);
    when(bridge.withSearcher(any())).thenAnswer(invocation ->
        ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(new IndexSearcher(reader)));
    return new IndexCountOps(bridge);
  }

  private static boolean defect(Object result, String component, String stage) throws Exception {
    for (var field : result.getClass().getRecordComponents()) {
      if (field.getName().equals(component)) {
        return Boolean.TRUE.equals(((java.util.Map<?, ?>) field.getAccessor().invoke(result)).get(stage));
      }
    }
    return false;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"addChunks", "deleteParent", "deleteChunks"})
  void parentCountsUseOneSnapshotDuringConcurrentIndexChanges(String change) throws Exception {
    try (var directory = new ByteBuffersDirectory();
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (String id : new String[] {"p1", "p2", "c1"}) {
        Document doc = new Document();
        doc.add(new StringField("doc_id", id, Field.Store.NO));
        if (id.startsWith("c")) doc.add(new StringField("is_chunk", "true", Field.Store.NO));
        writer.addDocument(doc);
      }
      try (var first = DirectoryReader.open(writer)) {
        if (change.equals("addChunks")) {
          for (int i = 0; i < 3; i++) {
            Document chunk = new Document();
            chunk.add(new StringField("is_chunk", "true", Field.Store.NO));
            writer.addDocument(chunk);
          }
        } else writer.deleteDocuments(new org.apache.lucene.index.Term("doc_id", change.equals("deleteParent") ? "p2" : "c1"));
        try (var second = DirectoryReader.open(writer)) {
          SearcherBridge bridge = mock(SearcherBridge.class);
          var acquisitions = new java.util.concurrent.atomic.AtomicInteger();
          when(bridge.withSearcher(any())).thenAnswer(invocation ->
              ((ReadPathOps.SearcherOperation<?>) invocation.getArgument(0)).execute(new IndexSearcher(
                  acquisitions.getAndIncrement() == 0 ? first : second)));
          var counts = new IndexCountOps(bridge);
          long parents;
          try {
            parents = (Long) IndexCountOps.class.getMethod("parentDocCountOrThrow").invoke(counts);
          } catch (NoSuchMethodException beforeSnapshotCounting) {
            parents = counts.docCountOrThrow() - counts.countByFieldOrThrow("is_chunk", "true");
          }
          assertEquals(2, parents, change);
          assertEquals(1, acquisitions.get(), "parent count must lease exactly one reader");
        }
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"9499,false", "9500,true", "9501,true", "9989,true", "9990,true", "9991,true"})
  void retrievalKeepsItsDistinctNinetyFivePercentThreshold(int present, boolean ready) {
    assertEquals(ready, new LuceneRuntimeTypes.ChunkVectorPresence(10000, present).isReady(95));
  }

  private static int cachedCount(IndexCountOps counts, String operation) {
    return switch (operation) {
      case "vectors" -> counts.queryChunkVectorPresenceCount().totalChunks();
      case "completeness" -> counts.queryStageCompletenessCounts().embedding().expected();
      default -> counts.queryRootCoverageCounts("/library").parentDocsTotalEmbedding();
    };
  }

}
