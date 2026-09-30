/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SpladeFeatureCounts;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 931 §E item 8 — {@link IndexCountOps#queryChunkSpladeCounts()} and {@link
 * IndexCountOps#maxDoc()}, the two count primitives the chunk-SPLADE status snapshot projects over.
 *
 * <p>The existing {@code querySpladeFeatureCounts()} scopes to whole documents ({@code IS_CHUNK}
 * MUST_NOT), so nothing on the status surface described the chunk population the chunk-SPLADE
 * retrieval leg actually scores against. The two properties that make the new number honest are
 * asserted adversely here: parents must not leak into the chunk scope, and a chunk carrying NO
 * {@code splade_status} (what {@code ChunkDocumentWriter} writes while {@code
 * rag.chunk_splade.enabled} is off) must be outside the denominator rather than pinned below 100%
 * forever.
 */
@DisplayName("chunk-SPLADE counts + reader merge state (931 §E item 8)")
class ChunkSpladeCountsTest extends LuceneExecutorTestBase {

  private static final String SEP = File.separator;
  private static final String ROOT = SEP + "lib" + SEP + "chunksplade";

  @TempDir Path tempDir;

  private RunningRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = openRuntime(tempDir.resolve("index"));
    // Parents carrying splade_status — they must NOT enter the chunk scope.
    index(parent("p1", SchemaFields.SPLADE_STATUS_COMPLETED));
    index(parent("p2", SchemaFields.SPLADE_STATUS_PENDING));
    // Chunks: 2 completed (one via COMPLETED_EMPTY), 1 pending, 1 failed.
    index(chunk("c1", SchemaFields.SPLADE_STATUS_COMPLETED));
    index(chunk("c2", SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY));
    index(chunk("c3", SchemaFields.SPLADE_STATUS_PENDING));
    index(chunk("c4", SchemaFields.SPLADE_STATUS_FAILED));
    commit();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  @DisplayName("the counts are chunk-scoped: parents carrying splade_status never leak in")
  void countsAreChunkScoped() {
    SpladeFeatureCounts chunks = runtime.indexCountOps().queryChunkSpladeCounts();
    assertEquals(4, chunks.total(), "only the four chunk documents");
    assertEquals(
        2, chunks.completed(), "COMPLETED_EMPTY is a terminal SPLADE success, like the parent twin");
    assertEquals(1, chunks.pending());
    assertEquals(1, chunks.failed());
    assertEquals(50.0, chunks.coveragePercent(), 0.0001);

    // The whole-doc twin sees exactly the parents — the two scopes must not overlap.
    SpladeFeatureCounts parents = runtime.indexCountOps().querySpladeFeatureCounts();
    assertEquals(2, parents.total(), "the parent scope is IS_CHUNK MUST_NOT");
    assertEquals(1, parents.completed());
    assertEquals(1, parents.pending());
  }

  @Test
  @DisplayName("a chunk with NO splade_status is outside the denominator (flag-off chunks)")
  void chunkWithoutSpladeStatusIsOutsideTheDenominator() {
    // This is what ChunkDocumentWriter writes while rag.chunk_splade.enabled is off. Counting it
    // would pin coverage below 100% forever for a chunk no backfill lane can ever select — the
    // backfill selects by status VALUE, so a chunk with no status field is never picked up.
    index(chunk("c5", null));
    index(chunk("c6", null));
    commit();

    SpladeFeatureCounts chunks = runtime.indexCountOps().queryChunkSpladeCounts();
    assertEquals(4, chunks.total(), "no splade_status ⇒ outside the chunk-SPLADE denominator");
    assertEquals(2, chunks.completed());
    assertEquals(50.0, chunks.coveragePercent(), 0.0001, "coverage is unchanged by flag-off chunks");
  }

  @Test
  @DisplayName("an empty index reports zeros, never a vacuous 100%")
  void emptyIndexReportsZeros() throws Exception {
    runtime.close();
    runtime = openRuntime(tempDir.resolve("index-empty"));

    SpladeFeatureCounts chunks = runtime.indexCountOps().queryChunkSpladeCounts();
    assertEquals(0, chunks.total());
    assertEquals(0, chunks.completed());
    assertEquals(0.0, chunks.coveragePercent(), 0.0001);
  }

  @Test
  @DisplayName("maxDoc keeps deleted-but-unmerged docs; docCount does not — the merge-state signal")
  void maxDocExposesTheUnmergedDeleteBacklog() {
    assertEquals(6, runtime.indexCountOps().maxDoc());
    assertEquals(6, runtime.indexCountOps().docCount());

    // No forceMerge: the delete is a tombstone until a merge reclaims it.
    runtime.indexingCoordinator().deleteById("c4");
    commit();

    assertEquals(
        6,
        runtime.indexCountOps().maxDoc(),
        "maxDoc still counts the tombstoned document — that is the merge-state signal");
    assertEquals(5, runtime.indexCountOps().docCount(), "numDocs counts live documents only");
    assertTrue(
        runtime.indexCountOps().maxDoc() - runtime.indexCountOps().docCount() == 1,
        "the difference is the deleted backlog no merge has reclaimed yet");
  }

  // ---- fixture ------------------------------------------------------------------

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private void commit() {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static IndexDocument parent(String id, String spladeStatus) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, ROOT + SEP + id + ".txt");
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (spladeStatus != null) {
      fields.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    }
    return new IndexDocument(fields);
  }

  /** A {@code null} status omits the FIELD — the flag-off chunk shape. */
  private static IndexDocument chunk(String id, String spladeStatus) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, ROOT + SEP + "parent.txt");
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.CONTENT, "chunk " + id);
    if (spladeStatus != null) {
      fields.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    }
    return new IndexDocument(fields);
  }

  private RunningRuntime openRuntime(Path indexDir) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] }
            ]
          }
          """;
      var fieldMapper = new FieldMapper(new ObjectMapper().readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .atPath(indexDir).withExecutorRegistrations(testLuceneExecutors())
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
