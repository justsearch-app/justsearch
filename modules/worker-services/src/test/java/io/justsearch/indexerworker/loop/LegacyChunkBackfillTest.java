/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Upgrade admission uses persisted content once and keeps the extraction pipeline idle. */
final class LegacyChunkBackfillTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void completeLegacyScanBackfillsWithoutExtractionOrEmbeddingAndSurvivesReopen() throws Exception {
    Path file = Files.writeString(tempDir.resolve("legacy.txt"), "alpha beta gamma delta ".repeat(400), StandardCharsets.UTF_8);
    Path index = tempDir.resolve("index");
    String content = Files.readString(file, StandardCharsets.UTF_8);
    String id = PathNormalizer.normalizeKey(file);
    long modified = Files.getLastModifiedTime(file).toMillis();
    int total;
    int[] children;
    Map<String, String> vectors;
    try (var runtime = open(index)) {
      // This check uses internal Lucene doc numbers to detect a child rewrite. Segment merges
      // may renumber untouched children, so keep the fixture's segments fixed before seeding.
      writer(runtime).getConfig().setMergePolicy(NoMergePolicy.INSTANCE);
      total = seed(runtime, id, content, modified, Files.size(file));
      children = childIds(index, id);
      vectors = vectors(index);
      assertEquals(total + 1, vectors.size(), "fixture has real parent and child vectors");
      var extraction = mock(TimeboxedContentExtractor.class);
      var journal = mock(IngestionOutcomeJournal.class);
      var batch = extractor(runtime, extraction, journal);
      assertEquals(List.of(), batch.extractAll(List.of(job(file))));
      var outcome = org.mockito.ArgumentCaptor.forClass(String.class);
      verify(journal).recordOutcomeSafely(eq(file), outcome.capture(), any());
      assertEquals("UNCHANGED", outcome.getValue(), "complete legacy scan retains unchanged admission");
      verifyNoInteractions(extraction);
      assertEquals(Integer.toString(total), runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_TOTAL));
      assertEquals(ChunkParentRevision.sha256Hex(content),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
      assertEquals(ChunkParentRevision.sha256Hex(content),
          runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT_SHA256));
      try (var splitter = mockStatic(ChunkSplitter.class)) {
        assertTrue(runtime.documentFieldOps().isUnmodified(id, modified));
        splitter.verifyNoInteractions();
      }
      runtime.commitOps().commitAndTrack();
      assertArrayEquals(children, childIds(index, id), "no child is rewritten (including its embedding)");
      assertEquals(vectors, vectors(index), "parent and child embeddings survive unchanged");
    }
    try (var runtime = open(index); var splitter = mockStatic(ChunkSplitter.class)) {
      assertTrue(runtime.documentFieldOps().isUnmodified(id, modified));
      splitter.verifyNoInteractions();
      assertEquals(Integer.toString(total), runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_TOTAL));
    }
  }

  @Test
  void incompleteLegacyScanReachesExtractionAndTheCanonicalWriterRepairsIt() throws Exception {
    Path file = Files.writeString(tempDir.resolve("partial.txt"), "alpha beta gamma delta ".repeat(400), StandardCharsets.UTF_8);
    String content = Files.readString(file, StandardCharsets.UTF_8);
    String id = PathNormalizer.normalizeKey(file);
    long modified = Files.getLastModifiedTime(file).toMillis();
    try (var runtime = open(tempDir.resolve("partial-index"))) {
      int total = seed(runtime, id, content, modified, Files.size(file));
      runtime.indexingCoordinator().deleteById(runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 10000).getFirst());
      refresh(runtime);
      var extraction = mock(TimeboxedContentExtractor.class);
      when(extraction.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(extraction.extractArtifact(any())).thenThrow(new ContentExtractor.ExtractionException("reached extraction"));
      var journal = mock(IngestionOutcomeJournal.class);
      extractor(runtime, extraction, journal).extractAll(List.of(job(file)));
      verify(extraction).extractArtifact(file);
      verify(journal, never()).recordOutcomeSafely(eq(file), eq("UNCHANGED"), any());
      assertNull(runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_TOTAL));
      // The normal extraction path rewrites the parent (and its current digest) before children.
      runtime.indexingCoordinator().updateDocument(id, Map.of(SchemaFields.CONTENT, content));
      assertEquals(total, ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(), runtime.indexingCoordinator(), id, content, false));
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(id, modified));
    }
  }

  @Test
  void legacyZeroChildrenAndMissingContentGainDurableZeroDeclarations() throws Exception {
    try (var runtime = open(tempDir.resolve("zero-index"))) {
      for (String id : List.of("small", "empty")) {
        var fields = new java.util.HashMap<String, Object>(Map.of(SchemaFields.DOC_ID, id,
            SchemaFields.DOC_UID, id, SchemaFields.PATH, id, SchemaFields.MODIFIED_AT, 123L));
        if (id.equals("small")) fields.put(SchemaFields.CONTENT, "small content");
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      refresh(runtime);
      for (String id : List.of("small", "empty")) {
        assertTrue(runtime.documentFieldOps().isUnmodified(id, 123));
        assertEquals("0", runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_TOTAL));
        try (var splitter = mockStatic(ChunkSplitter.class)) {
          assertTrue(runtime.documentFieldOps().isUnmodified(id, 123));
          splitter.verifyNoInteractions();
        }
      }
    }
  }

  private RunningRuntime open(Path index) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(2)).atPath(index)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static int seed(RunningRuntime runtime, String id, String content, long modified, long sizeBytes) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id, SchemaFields.PATH, id, SchemaFields.CONTENT, content,
        SchemaFields.MODIFIED_AT, modified, SchemaFields.SIZE_BYTES, sizeBytes)));
    refresh(runtime);
    int total = ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(), runtime.indexingCoordinator(), id, content, false);
    refresh(runtime);
    for (String child : runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, id, 10000)) {
      runtime.indexingCoordinator().updateDocument(child, Map.of(SchemaFields.CHUNK_VECTOR, new float[] {0.25f, 0.75f},
          SchemaFields.CHUNK_EMBEDDING_STATUS, "COMPLETED"));
    }
    // Simulate the pre-declaration writer. Children are the real canonical writer's documents.
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id, SchemaFields.PATH, id, SchemaFields.CONTENT, content,
        SchemaFields.MODIFIED_AT, modified, SchemaFields.SIZE_BYTES, sizeBytes,
        SchemaFields.VECTOR, new float[] {0.5f, 0.5f})));
    refresh(runtime);
    return total;
  }

  private static int[] childIds(Path index, String id) throws Exception {
    try (var directory = FSDirectory.open(index); var reader = DirectoryReader.open(directory)) {
      var searcher = new IndexSearcher(reader);
      var query = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, id));
      return java.util.Arrays.stream(searcher.search(query, 10000).scoreDocs).mapToInt(hit -> hit.doc).sorted().toArray();
    }
  }

  private static IndexWriter writer(RunningRuntime runtime) throws ReflectiveOperationException {
    var sessionField = RunningRuntime.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    Object session = sessionField.get(runtime);
    var snapshotField = session.getClass().getDeclaredField("snapshot");
    snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    var writerMethod = snapshot.getClass().getDeclaredMethod("writer");
    writerMethod.setAccessible(true);
    return (IndexWriter) writerMethod.invoke(snapshot);
  }

  private static Map<String, String> vectors(Path index) throws Exception {
    var result = new java.util.HashMap<String, String>();
    try (var directory = FSDirectory.open(index); var reader = DirectoryReader.open(directory)) {
      for (var leaf : reader.leaves()) {
        for (String field : List.of(SchemaFields.VECTOR, SchemaFields.CHUNK_VECTOR)) {
          var values = leaf.reader().getFloatVectorValues(field);
          if (values == null) continue;
          var iterator = values.iterator();
          while (iterator.nextDoc() != org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS) {
            if (leaf.reader().getLiveDocs() != null && !leaf.reader().getLiveDocs().get(iterator.docID())) continue;
            String docId = leaf.reader().storedFields().document(iterator.docID()).get(SchemaFields.DOC_ID);
            result.put(docId, java.util.Arrays.toString(values.vectorValue(iterator.index())));
          }
        }
      }
    }
    return result;
  }

  private static JobQueue.IndexJob job(Path file) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, file);
  }

  private static JobBatchExtractor extractor(RunningRuntime runtime, TimeboxedContentExtractor extraction,
      IngestionOutcomeJournal journal) {
    var identities = mock(DocumentIdentityStore.class);
    when(identities.resolve(anyString(), anyLong())).thenReturn(new DocumentIdentityStore.Identity("hash", "test-uid", 1L, 1L));
    return new JobBatchExtractor(new WorkerIngestionAuthority(), journal, mock(JobQueue.class), extraction,
        runtime.documentFieldOps(), runtime.indexCountOps(), mock(BatchStats.class), mock(StaleSnapshotResolver.class),
        mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), java.util.concurrent.ConcurrentHashMap.newKeySet(),
        () -> mock(PathResolutionStore.class), () -> identities, mock(IndexingDocumentOps.StageRecorder.class),
        () -> false, delta -> {}, new FileMutationFence());
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
