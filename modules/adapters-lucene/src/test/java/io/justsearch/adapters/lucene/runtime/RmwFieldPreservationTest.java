package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 711 Item 1 — RMW field-preservation engine regression tests.
 *
 * <p>The read-modify-write choke point preserves non-stored, non-docValues data-bearing fields the
 * caller omits, per their declared {@code rmwPolicy}: vectors are re-read and carried forward
 * ({@code preserve-reread}); SPLADE data cannot be re-read, so its status field is driven
 * ({@code reset-status}) to force a re-encode. Startup fail-fast rejects an undeclared fragile field.
 */
class RmwFieldPreservationTest extends LuceneExecutorTestBase {

  private static final float[] VEC = {0.25f, -0.5f, 0.75f, 1.0f};

  /** The parent content every chunk-text fixture below is cut from, and its revision identity. */
  private static final String PARENT_ALPHA_BETA = "alpha beta";
  private static final String REV_ALPHA_BETA = ChunkParentRevision.sha256Hex(PARENT_ALPHA_BETA);

  /** A NER-style RMW (updates = entity only) preserves a present vector bit-exactly. */
  @Test
  void vectorSurvivesNerStyleRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive an RMW that omits it (preserve-reread)");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** A VDU SUCCESS_EMPTY-style RMW (updates = VDU fields only) preserves the vector. */
  @Test
  void vectorSurvivesVduOnlyRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0",
                  Map.of(SchemaFields.VDU_STATUS, "COMPLETED", SchemaFields.VDU_PROCESSED, true)));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive a VDU-only RMW");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** A doc indexed without a vector is a no-op for preservation — RMW must not fail. */
  @Test
  void missingVectorRmwIsNoOp() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", null, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")),
              "RMW on a vectorless doc must still succeed");
          commit(runtime);

          assertNull(
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "a doc with no vector stays vectorless — nothing to preserve");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** Two updates to the same doc in one batch: the vector survives (re-read at the batch snapshot). */
  @Test
  void sameDocTwiceInOneBatchPreservesVector() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          List<Map.Entry<String, Map<String, Object>>> batch = new ArrayList<>();
          batch.add(Map.entry("doc-0", Map.of("entity_persons_raw", "Alice")));
          batch.add(Map.entry("doc-0", Map.of(SchemaFields.VDU_STATUS, "COMPLETED")));
          runtime.indexingCoordinator().updateDocumentsBatch(batch);
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive two same-doc RMWs in one batch");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** SPLADE cannot be re-read: an RMW dropping its data downgrades COMPLETED status to PENDING. */
  @Test
  void spladeDataDropTriggersStatusDowngrade() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> doc = new HashMap<>();
          doc.put(SchemaFields.DOC_ID, "doc-0");
          doc.put(SchemaFields.DOC_UID, "doc-0#0");
          doc.put(SchemaFields.PATH, "test/doc-0.txt");
          doc.put(SchemaFields.CONTENT, "content");
          doc.put(SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f));
          doc.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
          doc.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
          commit(runtime);
          assertTrue(spladeHits(runtime, "alpha") >= 1, "precondition: SPLADE data present");

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")));
          commit(runtime);

          // The SPLADE FeatureField data is unavoidably dropped (no re-read lane) ...
          assertEquals(0, spladeHits(runtime, "alpha"), "SPLADE data is dropped by the rewrite");
          // ... but the status is now PENDING so the backfill will re-encode — no silent
          // COMPLETED-but-empty doc (the second silent-loss bug is fixed).
          assertEquals(
              SchemaFields.SPLADE_STATUS_PENDING,
              runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS),
              "status must downgrade to PENDING when SPLADE data is dropped");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /**
   * Tempdoc 712 (chunk-level SPLADE): sparse postings written on a chunk doc are retrievable
   * through the production chunk-sparse query ({@code searchChunksSplade}, {@code is_chunk=true}
   * filtered), and an unrelated RMW cannot silently lose them — the data is dropped (no re-read
   * lane for FeatureFields) but {@code splade_status} downgrades to PENDING so the backfill
   * re-derives. The F-032 no-silent-loss guard, applied to the chunk sparse leg.
   */
  @Test
  void chunkSpladeSearchableAndRmwDowngradesStatus() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f));
          chunk.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
          chunk.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
          seedParentForChunk(runtime, chunk, "chunk body");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          var result = runtime.chunkSearchOps().searchChunksSplade(Map.of("alpha", 1.0f), 10, null);
          assertEquals(1, result.hits().size(), "chunk sparse postings must be retrievable");
          assertEquals("chunk-0", result.hits().get(0).docId());

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.CHUNK_TOTAL, "1")));
          commit(runtime);

          assertEquals(
              0,
              runtime.chunkSearchOps().searchChunksSplade(Map.of("alpha", 1.0f), 10, null)
                  .hits().size(),
              "SPLADE data is dropped by the rewrite (no re-read lane)");
          assertEquals(
              SchemaFields.SPLADE_STATUS_PENDING,
              runtime.documentFieldOps().getDocumentField("chunk-0", SchemaFields.SPLADE_STATUS),
              "status must downgrade to PENDING so the backfill re-derives the chunk's sparse data");
        },
        this::createRuntimeWithChunkSplade);
  }

  /** chunk_vector on a chunk doc survives an RMW that touches only a chunk lifecycle field. */
  @Test
  void chunkVectorSurvivesRmw() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.CHUNK_VECTOR, VEC);
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          seedParentForChunk(runtime, chunk, "chunk body");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.PATH, "test/renamed.txt")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "chunk_vector must survive an RMW that omits it");
        },
        this::createRuntimeWithChunkVector);
  }

  /**
   * Tempdoc 717 (D-7 regression anchor): the artifact-truthful chunk-vector presence count detects
   * the silent "chunk-death" state — a chunk marked {@code CHUNK_EMBEDDING_STATUS=COMPLETED} but
   * carrying no {@code chunk_vector} — that the status-derived count is fooled by.
   */
  @Test
  void chunkVectorPresenceCountDetectsDegenerateState() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          // Deliberately NO CHUNK_VECTOR — the degenerate state.
          seedParentForChunk(runtime, chunk, "chunk body");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          assertNull(
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "precondition: the chunk genuinely has no vector");

          // The status-derived count is fooled — it reports the chunk COMPLETED.
          var statusCounts = runtime.indexCountOps().queryChunkEmbeddingCounts();
          assertEquals(1, statusCounts.completed(), "status count reports COMPLETED (the lie)");

          // The artifact-truthful presence count is not fooled (tempdoc 717).
          var presence = runtime.indexCountOps().queryChunkVectorPresenceCount();
          assertEquals(1, presence.totalChunks());
          assertEquals(0, presence.vectorsPresent(), "no live chunk_vector present");
          assertEquals(0.0, presence.coveragePercent(), 1e-9);
          assertFalse(presence.isReady(95.0), "readiness must fail closed on a dead chunk leg");
        },
        this::createRuntimeWithChunkVector,
        ValidationMode.WARN);
  }

  /**
   * Tempdoc 717 (P2): a subset-field RMW that omits {@code chunk_vector} on a doc whose vector
   * re-read is null must downgrade the paired {@code chunk_embedding_status} COMPLETED&rarr;PENDING
   * (self-healing re-queue), not silently leave the status lying — the {@code
   * preserve-reread-or-reset} fallback that closes the hole plain {@code preserve-reread} left.
   */
  @Test
  void preserveRereadOrResetDowngradesStatusWhenVectorAbsent() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          // COMPLETED but vectorless — the F-032 "status lies" state.
          seedParentForChunk(runtime, chunk, "chunk body");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          // A subset RMW omitting chunk_vector: the re-read is null, so the fallback must reset.
          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.PATH, "test/renamed.txt")));
          commit(runtime);

          var counts = runtime.indexCountOps().queryChunkEmbeddingCounts();
          assertEquals(0, counts.completed(), "status must no longer read COMPLETED");
          assertEquals(1, counts.pending(), "status downgraded to PENDING for re-embed (tempdoc 717)");
        },
        this::createRuntimeWithChunkVector,
        ValidationMode.WARN);
  }

  /**
   * Tempdoc 717 (P2, parent vector): the same self-heal applies to the parent {@code vector} field
   * (also {@code preserve-reread-or-reset}). A COMPLETED-but-vectorless parent RMW'd on an unrelated
   * field must downgrade {@code embedding_status} COMPLETED&rarr;PENDING so a backfill re-embeds.
   */
  @Test
  void preserveRereadOrResetDowngradesParentStatusWhenVectorAbsent() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> doc = new HashMap<>();
          doc.put(SchemaFields.DOC_ID, "doc-0");
          doc.put(SchemaFields.DOC_UID, "doc-0#0");
          doc.put(SchemaFields.PATH, "test/doc-0.txt");
          doc.put(SchemaFields.CONTENT, "content for doc-0");
          doc.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
          doc.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          // COMPLETED but vectorless parent — the F-032 "status lies" state.
          runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
          commit(runtime);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")));
          commit(runtime);

          var counts = runtime.indexCountOps().queryEmbeddingCounts();
          assertEquals(0, counts.completed(), "parent status must no longer read COMPLETED");
          assertEquals(1, counts.pending(), "parent status downgraded to PENDING for re-embed");
        },
        this::createRuntimeWithVectorAndSplade,
        ValidationMode.WARN);
  }

  /**
   * Tempdoc 717: a chunk that legitimately HAS its vector is preserved by {@code
   * preserve-reread-or-reset} exactly like plain {@code preserve-reread} — the reset fallback only
   * fires on a null re-read, so the healthy path is unchanged.
   */
  @Test
  void preserveRereadOrResetPreservesPresentVector() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.CHUNK_VECTOR, VEC);
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          seedParentForChunk(runtime, chunk, "chunk body");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.PATH, "test/renamed.txt")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "present chunk_vector must survive an RMW that omits it (preserve-reread-or-reset)");
          assertEquals(
              1,
              runtime.indexCountOps().queryChunkEmbeddingCounts().completed(),
              "status stays COMPLETED when the vector was actually preserved");
        },
        this::createRuntimeWithChunkVector);
  }

  @Test
  void nonStoredChunkContentPostingsSurviveSingleRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexParent(runtime, "parent-0", "alpha beta");
          indexChunk(runtime, "chunk-0", "parent-0", REV_ALPHA_BETA, "alpha", 0, 5);
          commit(runtime);
          assertEquals(1, chunkTextHits(runtime, "alpha"));

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.CHUNK_TOTAL, "1")));
          commit(runtime);

          assertEquals(
              1,
              chunkTextHits(runtime, "alpha"),
              "a partial rewrite must carry the reconstructed text postings forward");
          assertEquals("alpha", runtime.documentFieldOps().getDocumentContent("chunk-0"));
          assertEquals(
              REV_ALPHA_BETA,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_PARENT_CONTENT_SHA256),
              "the rewrite carries the revision identity forward, so the next RMW is still guarded"
                  + " (tempdoc 931 §C.1)");
        },
        this::createRuntimeWithChunkText);
  }

  @Test
  void chunkGeometryUpdateReindexesTheNewExactParentSlice() throws Exception {
    withConfig(
        (runtime) -> {
          indexParent(runtime, "parent-0", "alpha beta");
          indexChunk(runtime, "chunk-0", "parent-0", REV_ALPHA_BETA, "alpha", 0, 5);
          commit(runtime);

          assertTrue(
              runtime
                  .indexingCoordinator()
                  .updateDocument(
                      "chunk-0",
                      Map.of(
                          SchemaFields.CHUNK_START_CHAR,
                          "6",
                          SchemaFields.CHUNK_END_CHAR,
                          "10")));
          commit(runtime);

          assertEquals(0, chunkTextHits(runtime, "alpha"));
          assertEquals(1, chunkTextHits(runtime, "beta"));
          assertEquals("beta", runtime.documentFieldOps().getDocumentContent("chunk-0"));
        },
        this::createRuntimeWithChunkText);
  }

  /**
   * Tempdoc 931 §C.1 — the silent-wrong-text case. Parent write and chunk regeneration are separate
   * coordinator calls, so an NRT refresh between them exposes the NEW parent content next to the
   * OLD chunk documents. With an equal-length rewrite the offsets still fit, so nothing but the
   * revision hash can tell the two apart: without the guard this RMW rewrites chunk-0's postings
   * from "gamma zeta" while its geometry describes "alpha beta".
   */
  @Test
  void equalLengthParentRewriteRefusesTheChunkRmwInsteadOfReslicingTheNewRevision()
      throws Exception {
    withConfig(
        (runtime) -> {
          indexParent(runtime, "parent-0", PARENT_ALPHA_BETA);
          indexChunk(runtime, "chunk-0", "parent-0", REV_ALPHA_BETA, "alpha", 0, 5);
          commit(runtime);
          assertEquals(1, chunkTextHits(runtime, "alpha"));

          // Parent rewritten to a DIFFERENT revision of the SAME length; chunks not regenerated yet.
          assertTrue(
              runtime
                  .indexingCoordinator()
                  .updateDocument("parent-0", Map.of(SchemaFields.CONTENT, "gamma zeta")));
          commit(runtime);
          assertEquals(
              PARENT_ALPHA_BETA.length(),
              "gamma zeta".length(),
              "precondition: the rewrite is exactly the length the old offsets still fit");

          IndexRuntimeIOException thrown =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      runtime
                          .indexingCoordinator()
                          .updateDocument("chunk-0", Map.of(SchemaFields.CHUNK_INDEX, "7")));
          assertTrue(
              thrown.getCause().getMessage().contains("parent content revision mismatch"),
              thrown.getCause().getMessage());

          commit(runtime);
          assertEquals(
              1,
              chunkTextHits(runtime, "alpha"),
              "the refused RMW must leave the original postings untouched");
          assertEquals(
              0, chunkTextHits(runtime, "gamma"), "no text from the newer parent revision landed");
          assertEquals(
              "0",
              runtime.documentFieldOps().getDocumentField("chunk-0", SchemaFields.CHUNK_INDEX),
              "the refused RMW wrote nothing at all, not even the field it was asked to set");
        },
        this::createRuntimeWithChunkText);
  }

  /**
   * Tempdoc 931 §C.1 — a chunk written before the revision field existed carries no identity, so
   * the reader cannot prove the parent it sees is the one the offsets address. Fail closed: the
   * bundle that adds the field reindexes anyway, and regeneration deletes such a chunk.
   */
  @Test
  void aChunkWithoutARevisionHashFailsClosed() throws Exception {
    withConfig(
        (runtime) -> {
          indexParent(runtime, "parent-0", PARENT_ALPHA_BETA);
          indexChunk(runtime, "chunk-0", "parent-0", null, "alpha", 0, 5);
          commit(runtime);
          assertEquals(1, chunkTextHits(runtime, "alpha"), "precondition: legacy chunk is indexed");

          IndexRuntimeIOException thrown =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      runtime
                          .indexingCoordinator()
                          .updateDocument("chunk-0", Map.of(SchemaFields.CHUNK_TOTAL, "1")));
          assertTrue(
              thrown.getCause().getMessage().contains("stored <absent>"),
              thrown.getCause().getMessage());
        },
        this::createRuntimeWithChunkText);
  }

  @Test
  void nonStoredSiblingChunkPostingsSurviveBatchRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexParent(runtime, "parent-0", "alpha beta");
          indexChunk(runtime, "chunk-0", "parent-0", REV_ALPHA_BETA, "alpha", 0, 5);
          indexChunk(runtime, "chunk-1", "parent-0", REV_ALPHA_BETA, "beta", 6, 10);
          commit(runtime);

          var result =
              runtime
                  .indexingCoordinator()
                  .updateDocumentsBatch(
                      List.of(
                          Map.entry(
                              "chunk-0",
                              Map.<String, Object>of(SchemaFields.CHUNK_TOTAL, "2")),
                          Map.entry(
                              "chunk-1",
                              Map.<String, Object>of(SchemaFields.CHUNK_TOTAL, "2"))));
          commit(runtime);

          assertEquals(2, result.updatedCount());
          assertEquals(0, result.notFoundCount());
          assertEquals(1, chunkTextHits(runtime, "alpha"));
          assertEquals(1, chunkTextHits(runtime, "beta"));
        },
        this::createRuntimeWithChunkText);
  }

  @Test
  void nonStoredChunkContentSurvivesParentPathUpdate() throws Exception {
    withConfig(
        (runtime) -> {
          String oldPath = "C:\\docs\\old.txt";
          String newPath = "C:\\docs\\renamed.txt";
          indexParent(runtime, oldPath, "alpha beta");
          indexChunk(runtime, "chunk-0", oldPath, REV_ALPHA_BETA, "alpha", 0, 5);
          commit(runtime);

          assertEquals(2, runtime.indexingCoordinator().updateDocumentPaths(oldPath, newPath));
          commit(runtime);

          assertEquals(
              1,
              chunkTextHits(runtime, "alpha"),
              "renaming the parent must not erase the chunk's indexed text");
          assertEquals(
              "alpha",
              runtime.documentFieldOps().getDocumentContent("chunk-0"),
              "the renamed parent id must still resolve the exact stored offset slice");
          assertEquals(
              newPath,
              runtime.documentFieldOps().getDocumentField("chunk-0", SchemaFields.PARENT_DOC_ID));
        },
        this::createRuntimeWithChunkText);
  }

  /**
   * Startup fail-fast (tempdoc 717): {@code preserve-reread-or-reset} with a reset target that is
   * not docValues-backed is rejected — the status could not be restored across RMW.
   */
  @Test
  void startupFailFastRejectsPreserveRereadOrResetTargetNotDocValues() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
            { "id": "chunk_vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread-or-reset:chunk_embedding_status", "vector": { "dimension": 4 } }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("docValues-backed"), ex.getMessage());
  }

  /**
   * Startup fail-fast (tempdoc 717): {@code preserve-reread-or-reset} is vector-only, like plain
   * preserve-reread — the re-read lane is a float-vector read-back.
   */
  @Test
  void startupFailFastRejectsPreserveRereadOrResetOnNonVectorField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": [] },
            { "id": "ghost_text", "type": "text", "stored": false, "docValues": false, "roles": [], "analyzer": "icu", "rmwPolicy": "preserve-reread-or-reset:embedding_status" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("only vector fields"), ex.getMessage());
  }

  /** Startup fail-fast: a fragile (non-stored, non-docValues) vector field without a policy. */
  @Test
  void startupFailFastRejectsUndeclaredFragileField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "vector", "type": "vector", "stored": false, "docValues": false, "vector": { "dimension": 4 } }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("rmwPolicy"), ex.getMessage());
  }

  /** Startup fail-fast: a reset-status target that is not docValues-backed. */
  @Test
  void startupFailFastRejectsResetStatusTargetNotDocValues() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "splade_status", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
            { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("docValues-backed"), ex.getMessage());
  }

  /**
   * Startup fail-fast is type-generic (tempdoc 714): a fragile field of a non-vector/splade type
   * (here text — the content_all shape) without a policy must be rejected, not silently shipped.
   */
  @Test
  void startupFailFastRejectsUndeclaredFragileNonVectorField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "ghost_text", "type": "text", "stored": false, "docValues": false, "roles": [], "analyzer": "icu" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("ghost_text"), ex.getMessage());
    assertTrue(ex.getMessage().contains("rmwPolicy"), ex.getMessage());
  }

  /**
   * Startup fail-fast: preserve-reread is only legal on vector fields (tempdoc 714) — on any other
   * type the engine's float-vector re-read would silently no-op instead of preserving.
   */
  @Test
  void startupFailFastRejectsPreserveRereadOnNonVectorField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "ghost_text", "type": "text", "stored": false, "docValues": false, "roles": [], "analyzer": "icu", "rmwPolicy": "preserve-reread" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("preserve-reread"), ex.getMessage());
    assertTrue(ex.getMessage().contains("only vector fields"), ex.getMessage());
  }

  @Test
  void startupAcceptsParentSlicePolicyOnChunkContent() throws Exception {
    String catalogJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "chunk_content", "type": "text", "stored": false, "docValues": false, "roles": [], "analyzer": "icu", "rmwPolicy": "rederive-parent-slice" },
            { "id": "chunk_parent_content_sha256", "type": "keyword", "stored": true, "docValues": false, "roles": [] }
          ]
        }
        """;

    assertDoesNotThrow(() -> new FieldMapper(json(catalogJson)).validateRmwPolicies());
  }

  @Test
  void startupRejectsParentSlicePolicyOnAnyOtherField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "ghost_text", "type": "text", "stored": false, "docValues": false, "roles": [], "analyzer": "icu", "rmwPolicy": "rederive-parent-slice" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("only supported on the chunk_content"), ex.getMessage());
  }

  // ---- helpers ----

  private void indexDoc(RunningRuntime runtime, String id, float[] vec, String spladeStatus) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, id);
    doc.put(SchemaFields.DOC_UID, id + "#0");
    doc.put(SchemaFields.PATH, "test/" + id + ".txt");
    doc.put(SchemaFields.CONTENT, "content for " + id);
    doc.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    if (SchemaFields.SPLADE_STATUS_COMPLETED.equals(spladeStatus)) {
      // The write-time contract (tempdoc 798) rejects a COMPLETED status with no witnessing
      // artifact — a COMPLETED-but-SPLADE-less seed is exactly the lie it exists to stop.
      doc.put(SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f));
    }
    if (vec != null) doc.put(SchemaFields.VECTOR, vec);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
    commit(runtime);
  }

  private static void indexParent(RunningRuntime runtime, String parentId, String content) {
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    parentId,
                    SchemaFields.DOC_UID,
                    parentId + "#0",
                    SchemaFields.PATH,
                    parentId,
                    SchemaFields.CONTENT,
                    content)));
  }

  /**
   * @param parentContentRevision the {@code chunk_parent_content_sha256} the chunk carries; null
   *     seeds a legacy-shape chunk (written before tempdoc 931 §C.1) that has no revision identity
   */
  private static void indexChunk(
      RunningRuntime runtime,
      String chunkId,
      String parentId,
      String parentContentRevision,
      String content,
      int startChar,
      int endChar) {
    Map<String, Object> chunk = new HashMap<>();
    chunk.put(SchemaFields.DOC_ID, chunkId);
    chunk.put(SchemaFields.DOC_UID, chunkId + "#0");
    chunk.put(SchemaFields.PATH, parentId);
    chunk.put(SchemaFields.IS_CHUNK, "true");
    chunk.put(SchemaFields.PARENT_DOC_ID, parentId);
    chunk.put(SchemaFields.CHUNK_INDEX, "0");
    chunk.put(SchemaFields.CHUNK_TOTAL, "1");
    chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(startChar));
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(endChar));
    chunk.put(SchemaFields.CHUNK_CONTENT, content);
    if (parentContentRevision != null) {
      chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, parentContentRevision);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
  }

  private static void seedParentForChunk(
      RunningRuntime runtime, Map<String, Object> chunk, String parentContent) {
    String parentId = chunk.get(SchemaFields.PARENT_DOC_ID).toString();
    indexParent(runtime, parentId, parentContent);
    chunk.put(SchemaFields.CHUNK_START_CHAR, "0");
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(parentContent.length()));
    chunk.put(
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static int spladeHits(RunningRuntime runtime, String feature) throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher ->
                searcher.count(
                    org.apache.lucene.document.FeatureField.newSaturationQuery(
                        SchemaFields.SPLADE, feature)));
  }

  private static int chunkTextHits(RunningRuntime runtime, String term) throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher ->
                searcher.count(
                    new TermQuery(new Term(SchemaFields.CHUNK_CONTENT, term))));
  }

  private static float[] readVector(RunningRuntime runtime, String field, String docId)
      throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher -> {
              var td = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), 1);
              if (td.scoreDocs.length == 0) return null;
              int gid = td.scoreDocs[0].doc;
              var leaves = searcher.getIndexReader().leaves();
              var leaf = leaves.get(ReaderUtil.subIndex(gid, leaves));
              int docInLeaf = gid - leaf.docBase;
              FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
              if (values == null) return null;
              var iter = values.iterator();
              if (iter.advance(docInLeaf) != docInLeaf) return null;
              float[] v = values.vectorValue(iter.index());
              return v == null ? null : v.clone();
            });
  }

  private static tools.jackson.databind.JsonNode json(String s) {
    try {
      return new ObjectMapper().readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private interface RuntimeConsumer {
    void accept(RunningRuntime runtime) throws Exception;
  }

  private void withConfig(RuntimeConsumer body, java.util.function.Supplier<RunningRuntime> factory)
      throws Exception {
    withConfig(body, factory, ValidationMode.FAIL);
  }

  /**
   * WARN-mode variant. The tests below that seed an F-032 "status lies" document (a COMPLETED
   * status with no artifact) need it: the write-time contract (tempdoc 798) rejects that shape at
   * the front door in FAIL mode, and the RMW self-heal these tests exercise is precisely the
   * backstop for such state — which reaches disk from a pre-798 index, or from a WARN-mode
   * deployment. WARN mode is how the test reproduces that on-disk state; it does not weaken the
   * contract, whose FAIL behaviour is asserted in {@link StatusArtifactContractTest}.
   */
  private void withConfig(
      RuntimeConsumer body,
      java.util.function.Supplier<RunningRuntime> factory,
      ValidationMode validationMode)
      throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    RunningRuntime runtime = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-preserve-");
      cfg = writeTestConfig(base, validationMode);
      System.setProperty("justsearch.config", cfg.toString());
      runtime = factory.get();
      body.accept(runtime);
    } finally {
      if (runtime != null) runtime.close();
      restoreConfig(prev, base, cfg);
    }
  }

  private Path writeTestConfig(Path base, ValidationMode validationMode) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: rmwpreservetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n"
            + "  validation:\n    mode: "
            + (validationMode == ValidationMode.WARN ? "warn" : "fail")
            + "\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    return cfg;
  }

  private RunningRuntime createRuntimeWithVectorAndSplade() {
    return open(
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "content", "type": "text", "stored": true, "docValues": false },
            { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "vdu_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "vdu_processed", "type": "boolean", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "embedding_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter", "sort"] },
            { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
            { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
            { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" },
            { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread-or-reset:embedding_status", "vector": { "dimension": 4 } }
          ]
        }
        """);
  }

  private RunningRuntime createRuntimeWithChunkSplade() {
    return open(
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "content", "type": "text", "stored": true, "docValues": false },
            { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "chunk_content", "type": "text", "stored": false, "docValues": false, "rmwPolicy": "rederive-parent-slice" },
            { "id": "chunk_parent_content_sha256", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
            { "id": "chunk_start_char", "type": "long", "stored": true, "docValues": true },
            { "id": "chunk_end_char", "type": "long", "stored": true, "docValues": true },
            { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
            { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
            { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" }
          ]
        }
        """);
  }

  private RunningRuntime createRuntimeWithChunkVector() {
    return open(
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "content", "type": "text", "stored": true, "docValues": false },
            { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "chunk_content", "type": "text", "stored": false, "docValues": false, "rmwPolicy": "rederive-parent-slice" },
            { "id": "chunk_parent_content_sha256", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
            { "id": "chunk_start_char", "type": "long", "stored": true, "docValues": true },
            { "id": "chunk_end_char", "type": "long", "stored": true, "docValues": true },
            { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "chunk_embedding_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter", "sort"] },
            { "id": "chunk_vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread-or-reset:chunk_embedding_status", "vector": { "dimension": 4 } }
          ]
        }
        """);
  }

  private RunningRuntime createRuntimeWithChunkText() {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private RunningRuntime open(String json) {
    try {
      var fieldMapper = new FieldMapper(json(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .ephemeral().withExecutorRegistrations(testLuceneExecutors())
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void restoreConfig(String prev, Path base, Path cfg) {
    if (prev == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prev);
    }
    try {
      if (cfg != null) Files.deleteIfExists(cfg);
      if (base != null) {
        try (var walk = Files.walk(base)) {
          walk.sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                      // best-effort cleanup; a locked file (e.g. Windows file lock) should not fail the test
                    }
                  });
        }
      }
    } catch (Exception ignored) {
      // best-effort cleanup; failures here must not mask the test's actual assertions
    }
  }
}
