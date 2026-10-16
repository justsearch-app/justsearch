/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** O7: reader replacement must not retain a per-chunk boxed membership set for each reader. */
@Tag("heap-measurement")
@Timeout(120)
final class ChunkAdmissionRefreshRetentionTest extends LuceneExecutorTestBase {
  private static final String CONTENT = "refresh retention fixture content";
  private static final String HASH = ChunkParentRevision.sha256Hex(CONTENT);

  @Test
  void repeatedRefreshDoesNotRetainAnotherChunkSizedHeap(@TempDir Path directory) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      for (int parent = 0; parent < 100; parent++) {
        parent(runtime, parent);
        String id = "heap-" + parent;
        for (int ordinal = 0; ordinal < 1000; ordinal++) {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
              "doc_id", id + "#" + ordinal, "doc_uid", id + "#" + ordinal,
              "path", id, "parent_doc_id", id, "is_chunk", "true", "chunk_index", ordinal,
              "chunk_total", 1000, "chunk_parent_content_sha256", HASH)));
        }
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
    }
    // Reopen the settled corpus, matching the verifier's refresh-cycle measurement rather than
    // measuring a writer that still carries all initial indexing allocations.
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      scan(runtime);
      long initial = heap();
      long peak = initial;
      for (int cycle = 0; cycle < 12; cycle++) {
        parent(runtime, cycle);
        runtime.commitOps().commitAndTrack();
        // A durable commit does not join Lucene's background merges. Their codec buffers are
        // temporary working memory, not retained admission membership; join them before sampling.
        ((ConcurrentMergeScheduler) runtime.session().snapshot.writer().getConfig().getMergeScheduler())
            .sync();
        runtime.commitOps().maybeRefreshBlocking();
        scan(runtime);
        peak = Math.max(peak, heap());
      }
      System.out.println("REFRESH_RETENTION chunks=100000 initialBytes=" + initial + " peakBytes=" + peak);
      assertTrue(peak - initial < 5_000_000,
          "refresh must not keep a second boxed ordinal set: initial=" + initial + ", peak=" + peak);
    }
  }

  private static void parent(RunningRuntime runtime, int number) {
    String id = "heap-" + number;
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        "doc_id", id, "doc_uid", id, "path", id, "content", CONTENT, "content_sha256", HASH,
        "modified_at", 123L, "chunk_total", 1000, "chunk_parent_content_sha256", HASH)));
  }

  private static void scan(RunningRuntime runtime) {
    for (int parent = 0; parent < 100; parent++)
      assertTrue(runtime.documentFieldOps().isUnmodified("heap-" + parent, 123), "complete parent " + parent);
  }

  private static long heap() throws InterruptedException {
    for (int attempt = 0; attempt < 3; attempt++) { System.gc(); Thread.sleep(250); }
    var runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
