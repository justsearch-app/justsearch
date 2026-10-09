/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unchanged-source admission must certify the complete persisted chunk set. */
final class ChunkCompletenessAdmissionTest extends LuceneExecutorTestBase {
  private static final String PARENT = "document.txt";
  private static final String CONTENT = "alpha beta gamma delta ".repeat(400);

  @Test
  void missingChunksAreNotCertifiedByTheParentTimestamp() throws Exception {
    try (var runtime = open()) {
      seed(runtime);
      assertTrue(runtime.documentFieldOps().isUnmodified(PARENT, 123), () ->
          "declared total=" + runtime.documentFieldOps().getDocumentField(PARENT, SchemaFields.CHUNK_TOTAL)
              + " declared revision=" + runtime.documentFieldOps().getDocumentField(PARENT, SchemaFields.CHUNK_PARENT_CONTENT_SHA256)
              + " content revision=" + runtime.documentFieldOps().getDocumentField(PARENT, SchemaFields.CONTENT_SHA256));
      runtime.indexingCoordinator().deleteById("chunk-0");
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "a matching timestamp must not certify a missing chunk");
      runtime.indexingCoordinator().deleteChunksForParentDocId(PARENT);
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "a matching timestamp must not certify a completely missing set");
    }
  }

  @Test
  void equalCountsWithDuplicateOrdinalsOrWrongRevisionsAreIncomplete() throws Exception {
    try (var runtime = open()) {
      int total = seed(runtime);
      chunk(runtime, 1, 0, total, ChunkParentRevision.sha256Hex(CONTENT));
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "equal row counts cannot certify duplicate ordinals");
      chunk(runtime, 1, 1, total, "old-revision");
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "every chunk must address this parent revision");
      chunk(runtime, 1, 1, total - 1, ChunkParentRevision.sha256Hex(CONTENT));
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "the declared totals must agree with the entire expected set");
    }
  }

  private RunningRuntime open() throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static int seed(RunningRuntime runtime) {
    int total = ChunkSplitter.splitWithMetadata(CONTENT, ChunkSplitter.DEFAULT_CHUNK_TOKENS,
        ChunkSplitter.DEFAULT_OVERLAP_TOKENS).size();
    String revision = ChunkParentRevision.sha256Hex(CONTENT);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, PARENT, SchemaFields.DOC_UID, "parent-uid",
        SchemaFields.PATH, PARENT, SchemaFields.CONTENT, CONTENT,
        SchemaFields.MODIFIED_AT, 123L, SchemaFields.CONTENT_SHA256, revision,
        SchemaFields.CHUNK_TOTAL, total, SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
    for (int i = 0; i < total; i++) chunk(runtime, i, i, total, revision);
    refresh(runtime);
    return total;
  }

  @Test
  void parentContentChangesInvalidateTheDeclarationEvenWithoutAnExplicitHashUpdate() throws Exception {
    try (var runtime = open()) {
      seed(runtime);
      runtime.indexingCoordinator().updateDocument(PARENT,
          Map.of(SchemaFields.CONTENT, CONTENT + "new revision"));
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "a declaration for the previous parent content must not certify unchanged admission");
    }
  }

  @Test
  void legacyCompleteParentIsRewrittenToInstallTheDeclaration() throws Exception {
    try (var runtime = open()) {
      seed(runtime);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, PARENT, SchemaFields.DOC_UID, "parent-uid",
          SchemaFields.PATH, PARENT, SchemaFields.CONTENT, CONTENT,
          SchemaFields.MODIFIED_AT, 123L)));
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified(PARENT, 123),
          "even a complete legacy set must be rewritten once to install its declaration");
    }
  }

  private static void chunk(RunningRuntime runtime, int id, int ordinal, int total, String revision) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk-" + id, SchemaFields.DOC_UID, "parent-uid#" + id,
        SchemaFields.PATH, PARENT, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, PARENT,
        SchemaFields.CHUNK_INDEX, ordinal, SchemaFields.CHUNK_TOTAL, total,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision)));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
