/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * b71: completeness preparation on searcher reopen costs what changed, not the index size. Stored
 * chunk metadata is read once per segment; a reopen that adds a segment reads only that segment,
 * and a reopen that only changes deletions reads nothing, while assessment stays exact.
 */
final class ChunkCompletenessReopenCostTest extends LuceneExecutorTestBase {
  private static final int PARENTS = 30;
  private static final int CHUNKS = 20;
  private static final String CONTENT = "reopen cost fixture content";
  private static final String HASH = ChunkParentRevision.sha256Hex(CONTENT);

  @Test
  void reopenReadsOnlyNewSegmentsAndDeletionOnlyReopensReadNothing() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (int parent = 0; parent < PARENTS; parent++) {
        parent(runtime, "p" + parent);
        for (int ordinal = 0; ordinal < CHUNKS; ordinal++) chunk(runtime, "p" + parent, ordinal);
      }
      refresh(runtime);
      // The seeded segment now carries a deletion, so every later reader opens a new segment
      // reader for it even when its deletions do not change.
      runtime.indexingCoordinator().deleteById("p0#0");
      refresh(runtime);
      assertFalse(runtime.documentFieldOps().isUnmodified("p0", 123), "p0 lost a chunk");
      assertTrue(runtime.documentFieldOps().isUnmodified("p1", 123), "p1 is complete");

      var reads = new AtomicInteger();
      try (var formatter = mockStatic(SearchResultFormatter.class, invocation -> {
        if (invocation.getMethod().getName().equals("extractFromStoredFields")
            && invocation.getArguments().length == 4
            && (((Set<?>) invocation.getArgument(3)).contains(SchemaFields.CHUNK_INDEX)
                || ((Set<?>) invocation.getArgument(3)).contains(SchemaFields.CONTENT_SHA256))) {
          reads.incrementAndGet();
        }
        return invocation.callRealMethod();
      })) {
        parent(runtime, "new");
        refresh(runtime);
        assertTrue(runtime.documentFieldOps().isUnmodified("new", 123), "the new zero-chunk parent");
        assertTrue(reads.get() <= 2, "a reopen adding one parent must read only that parent's row,"
            + " not re-read the " + (PARENTS * (CHUNKS + 1)) + " unchanged rows; observed " + reads.get());

        reads.set(0);
        runtime.indexingCoordinator().deleteById("p1#3");
        refresh(runtime);
        assertFalse(runtime.documentFieldOps().isUnmodified("p1", 123),
            "a deletion-only reopen must still expose the missing chunk");
        assertTrue(runtime.documentFieldOps().isUnmodified("p2", 123), "p2 stays complete");
        assertEquals(0, reads.get(),
            "a reopen that only changes deletions must not read any stored metadata again");
      }
    }
  }

  private static void parent(RunningRuntime runtime, String id) {
    var fields = new HashMap<String, Object>(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id, SchemaFields.PATH, id,
        SchemaFields.CONTENT, CONTENT, SchemaFields.CONTENT_SHA256, HASH, SchemaFields.MODIFIED_AT, 123L,
        SchemaFields.CHUNK_TOTAL, id.equals("new") ? 0 : CHUNKS,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, HASH));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void chunk(RunningRuntime runtime, String parent, int ordinal) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parent + "#" + ordinal, SchemaFields.DOC_UID, parent + "#" + ordinal,
        SchemaFields.PATH, parent, SchemaFields.IS_CHUNK, "true", SchemaFields.PARENT_DOC_ID, parent,
        SchemaFields.CHUNK_INDEX, ordinal, SchemaFields.CHUNK_TOTAL, CHUNKS,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, HASH)));
  }

  private static void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
