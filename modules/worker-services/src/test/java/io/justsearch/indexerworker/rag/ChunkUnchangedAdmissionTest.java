/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the real timestamp admission path; the splitter trap covers all static entry points. */
final class ChunkUnchangedAdmissionTest extends LuceneExecutorTestBase {
  // Parent declarations reuse existing physical fields, so old index fingerprints remain valid.
  private static final String TOTAL = "chunk_total";
  private static final String REVISION = "chunk_parent_content_sha256";

  @Test
  void unchangedAdmissionNeverInvokesTheSplitter() throws Exception {
    try (var runtime = open()) {
      String content = "alpha beta gamma delta ".repeat(400);
      var chunks = ChunkSplitter.splitWithMetadata(content);
      seed(runtime, "large.txt", content, chunks);
      refresh(runtime);
      try (var splitter = mockStatic(ChunkSplitter.class)) {
        boolean unchanged = runtime.documentFieldOps().isUnmodified("large.txt", 123);
        assertDoesNotThrow(splitter::verifyNoInteractions,
            "unchanged admission must not invoke any splitter entry point");
        assertTrue(unchanged, "the declared complete set must retain unchanged admission");
      }
    }
  }

  @Test
  void legacyFallbackBackfillsAndRunsOnlyOnce() throws Exception {
    try (var runtime = open()) {
      String content = "alpha beta gamma delta ".repeat(400);
      seed(runtime, "legacy.txt", content, ChunkSplitter.splitWithMetadata(content));
      parent(runtime, "legacy.txt", content, -1);
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified("legacy.txt", 123),
          "a complete legacy parent must install its declaration without reprocessing");
      try (var splitter = mockStatic(ChunkSplitter.class)) {
        assertTrue(runtime.documentFieldOps().isUnmodified("legacy.txt", 123));
        assertDoesNotThrow(splitter::verifyNoInteractions,
            "later admission of the same legacy parent must not repeat its split");
      }
      seed(runtime, "legacy.txt", content, ChunkSplitter.splitWithMetadata(content));
      refresh(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified("legacy.txt", 123));
    }
  }

  @Test
  void canonicalWriterDeclaresBothChunkedAndZeroChunkParents() throws Exception {
    try (var runtime = open()) {
      for (String content : List.of("small content", "alpha beta gamma delta ".repeat(400))) {
        String id = "parent-" + content.length();
        parent(runtime, id, content, -1);
        refresh(runtime);
        int written = ChunkDocumentWriter.regenerateChunksFromExistingParent(
            runtime.documentFieldOps(), runtime.indexingCoordinator(), id, content, false);
        refresh(runtime);
        assertEquals(String.valueOf(written), runtime.documentFieldOps().getDocumentField(id, TOTAL));
        assertEquals(ChunkParentRevision.sha256Hex(content),
            runtime.documentFieldOps().getDocumentField(id, REVISION));
        assertTrue(runtime.documentFieldOps().isUnmodified(id, 123));
      }
    }
  }

  @Test
  void measureFiveThousandChunksAndOneThousandSmallFiles() throws Exception {
    try (var runtime = open()) {
      String candidate = ("alpha beta gamma delta ".repeat(83) + "\n\n").repeat(5001);
      var split = ChunkSplitter.splitWithMetadata(candidate);
      String content = candidate.substring(0, split.get(4999).endChar());
      var chunks = ChunkSplitter.splitWithMetadata(content);
      assertEquals(5000, chunks.size(), "the measurement must use exactly 5,000 chunks");
      seed(runtime, "large.txt", content, chunks);
      var smallIds = new ArrayList<String>();
      for (int i = 0; i < 1000; i++) {
        String id = "small-" + i;
        parent(runtime, id, "small file content ".repeat(40), 0);
        smallIds.add(id);
      }
      refresh(runtime);
      measure(runtime, "5000-chunk-file", List.of("large.txt"), 10);
      measure(runtime, "1000-small-files", smallIds, 5);
    }
  }

  private static void measure(RunningRuntime runtime, String label, List<String> ids, int repeats) {
    for (int i = 0; i < 3; i++)
      for (String id : ids) assertTrue(runtime.documentFieldOps().isUnmodified(id, 123));
    var milliseconds = new ArrayList<Double>();
    for (int i = 0; i < repeats; i++) {
      long start = System.nanoTime();
      for (String id : ids) assertTrue(runtime.documentFieldOps().isUnmodified(id, 123));
      milliseconds.add((System.nanoTime() - start) / 1_000_000.0);
    }
    System.out.println("UNCHANGED_COST " + label + " contentChecks=" + ids.size()
        + " samplesMs=" + milliseconds);
  }

  private RunningRuntime open() throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void seed(
      RunningRuntime runtime, String id, String content, List<ChunkSplitter.Chunk> chunks) {
    parent(runtime, id, content, chunks.size());
    String revision = ChunkParentRevision.sha256Hex(content);
    for (var chunk : chunks)
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, id + "#" + chunk.index(), SchemaFields.DOC_UID, id + "#" + chunk.index(),
          SchemaFields.PATH, id, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, id,
          SchemaFields.CHUNK_INDEX, chunk.index(), SchemaFields.CHUNK_TOTAL, chunks.size(),
          SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
  }

  private static void parent(RunningRuntime runtime, String id, String content, int total) {
    String revision = ChunkParentRevision.sha256Hex(content);
    var fields = new java.util.HashMap<String, Object>(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PATH, id,
        SchemaFields.CONTENT, content, SchemaFields.CONTENT_SHA256, revision, SchemaFields.MODIFIED_AT, 123L));
    if (total >= 0) {
      fields.put(TOTAL, total);
      fields.put(REVISION, revision);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
