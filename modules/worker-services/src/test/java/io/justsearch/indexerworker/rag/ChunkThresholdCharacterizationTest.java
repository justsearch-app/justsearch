/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.core.util.TokenEstimation;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chunk threshold characterization")
final class ChunkThresholdCharacterizationTest {

  @Test
  @DisplayName("threshold is one default window under the canonical typical-prose estimate")
  void thresholdIsOneCanonicalTypicalProseWindow() {
    assertAll(
        () -> assertEquals(2000, ChunkSplitter.CHUNK_THRESHOLD_CHARS),
        () ->
            assertEquals(
                TokenEstimation.charsForTokens(ChunkSplitter.DEFAULT_CHUNK_TOKENS),
                ChunkSplitter.CHUNK_THRESHOLD_CHARS),
        () ->
            assertEquals(
                ChunkSplitter.CHUNK_THRESHOLD_CHARS,
                ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS));
  }

  @Test
  @DisplayName("length prefilter plus a single splitter chunk emits no chunk documents")
  void lengthPrefilterAndSingleChunkGuardBothSuppressWrites() {
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

    String belowThreshold = "x".repeat(ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS - 1);
    assertEquals(
        0,
        ChunkDocumentWriter.regenerateChunks(
            documentFieldOps, indexingCoordinator, "below", belowThreshold, null, false));

    String oneEffectiveChunk =
        "x".repeat(ChunkSplitter.DEFAULT_CHUNK_TOKENS)
            + " ".repeat(
                ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS
                    - ChunkSplitter.DEFAULT_CHUNK_TOKENS);
    assertEquals(ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS, oneEffectiveChunk.length());
    assertEquals(1, ChunkSplitter.splitWithMetadata(oneEffectiveChunk).size());
    assertEquals(
        0,
        ChunkDocumentWriter.regenerateChunks(
            documentFieldOps, indexingCoordinator, "one-chunk", oneEffectiveChunk, null, false));

    verify(indexingCoordinator, times(1)).deleteChunksForParentDocId("below");
    verify(indexingCoordinator, times(1)).deleteChunksForParentDocId("one-chunk");
    verify(indexingCoordinator, never()).indexSingle(any(IndexDocument.class));
  }

  @Test
  @DisplayName("failed old-chunk deletion propagates before replacement writes")
  void failedOldChunkDeletionPropagatesBeforeReplacementWrites() {
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    RuntimeException deletionFailure = new RuntimeException("delete failed");
    String parentDocId = "parent-with-stale-chunks";
    String content = "chunk regeneration failure ".repeat(500);
    assertTrue(ChunkSplitter.splitWithMetadata(content).size() > 1);

    doThrow(deletionFailure)
        .when(indexingCoordinator)
        .deleteChunksForParentDocId(parentDocId);

    RuntimeException observed =
        assertThrows(
            RuntimeException.class,
            () ->
                ChunkDocumentWriter.regenerateChunks(
                    documentFieldOps,
                    indexingCoordinator,
                    parentDocId,
                    content,
                    new ChunkDocumentWriter.ParentChunkMetadata(
                        "text/plain", "text/plain", "text", "en", null, null, "parent-uid"),
                    false));

    assertSame(deletionFailure, observed);
    verify(indexingCoordinator, times(1)).deleteChunksForParentDocId(parentDocId);
    verify(indexingCoordinator, never()).indexSingle(any(IndexDocument.class));
  }
}
