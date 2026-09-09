/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §C.6 — {@code content_sha256} has to reach the Head on the SAME wire route
 * {@code doc_uid} does, for both hit shapes. Feedback captures the pair, so a revision that arrives
 * for whole-document hits but not chunk hits would silently make every chunk-sourced label
 * un-ageable.
 */
final class SearchResponseContentRevisionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final FieldCatalogDef CATALOG = FieldCatalogDef.forChunkTesting(0);
  private static final String PARENT_CONTENT = "the parent revision this label is attached to";
  private static final String PARENT_REVISION = ChunkParentRevision.sha256Hex(PARENT_CONTENT);

  private RunningRuntime lifecycle;
  private SearchResponseBuilder builder;

  @BeforeEach
  void setUp() throws Exception {
    lifecycle = IndexSchema.fromCatalog(CATALOG).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "parent-1",
                    SchemaFields.DOC_UID, "parent-1-uid",
                    SchemaFields.TITLE, "Parent Title",
                    SchemaFields.CONTENT, PARENT_CONTENT,
                    SchemaFields.CONTENT_SHA256, PARENT_REVISION)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    builder =
        new SearchResponseBuilder(
            lifecycle.indexCountOps(),
            lifecycle.documentFieldOps(),
            lifecycle.textQueryOps(),
            lifecycle.facetingEngine(),
            lifecycle::indexAnalyzerOrNull,
            lifecycle::resolvedConfig);
  }

  @AfterEach
  void tearDown() {
    if (lifecycle != null) lifecycle.close();
  }

  @Test
  void aChunkHitCarriesItsParentUidAndItsParentContentRevision() {
    // A chunk's stored allowlist cannot reach the parent's fields, so both values are lifted from
    // the parent by the same enrichment. This asserts they arrive together.
    SearchResponse response = respond(chunkHit());

    assertEquals(1, response.getResultsCount());
    var hit = response.getResults(0);
    assertEquals("parent-1-uid", hit.getFieldsMap().get(SchemaFields.DOC_UID));
    assertEquals(PARENT_REVISION, hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256));
    assertEquals(
        ChunkParentRevision.sha256Hex(PARENT_CONTENT),
        hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256),
        "the wire value is the digest of the parent's stored content, not of the chunk's");
  }

  @Test
  void aWholeDocumentHitCarriesItsRevisionThroughTheStoredFieldProjection() {
    SearchResponse response = respond(wholeDocumentHit());

    assertEquals(1, response.getResultsCount());
    var hit = response.getResults(0);
    assertEquals("parent-1-uid", hit.getFieldsMap().get(SchemaFields.DOC_UID));
    assertEquals(PARENT_REVISION, hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256));
    assertNull(
        hit.getFieldsMap().get(SchemaFields.CONTENT),
        "the revision travels, the content does not");
  }

  @Test
  void aChunkHitWhoseParentPredatesTheFieldCarriesNoRevisionRatherThanAWrongOne() throws Exception {
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "legacy-parent",
                    SchemaFields.DOC_UID, "legacy-parent-uid",
                    SchemaFields.CONTENT, "content written before the revision field existed")));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response =
        respond(
            new LuceneRuntimeTypes.SearchHit(
                "chunk:legacy-parent#0",
                1.0f,
                Map.of(
                    SchemaFields.PARENT_DOC_ID, "legacy-parent",
                    SchemaFields.IS_CHUNK, "true",
                    SchemaFields.CHUNK_INDEX, "0",
                    SchemaFields.CHUNK_CONTENT, "needle in a legacy chunk")));

    var hit = response.getResults(0);
    assertEquals("legacy-parent-uid", hit.getFieldsMap().get(SchemaFields.DOC_UID));
    assertNull(
        hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256),
        "absent is honest; an empty or borrowed revision would read as a mismatch downstream");
  }

  /**
   * Tempdoc 931 §E item 5 — when the read-path revision guard refuses to reconstruct a chunk's text
   * (parent rewritten, chunks not regenerated yet), the hit reaches the builder with no
   * {@code chunk_content}. The excerpt must come out EMPTY rather than sliced from the newer parent
   * revision. The reconstructible sibling below is the positive control: it proves the empty result
   * is the guard's doing and not a fixture that never produces excerpts.
   */
  @Test
  void aChunkHitWithNoReconstructibleTextYieldsAnEmptyExcerptNotBorrowedText() {
    var withText =
        new LuceneRuntimeTypes.SearchHit(
            "chunk:parent-1#0",
            1.0f,
            Map.of(
                SchemaFields.PARENT_DOC_ID, "parent-1",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.CHUNK_INDEX, "0",
                SchemaFields.CHUNK_CONTENT, "the needle lives in this reconstructed chunk"));
    var control = respondWithExcerpts(withText).getResults(0);
    assertTrue(
        control.getExcerptRegionsCount() > 0,
        "positive control: a reconstructible chunk does produce excerpt regions here");

    var refused =
        new LuceneRuntimeTypes.SearchHit(
            "chunk:parent-1#0",
            1.0f,
            Map.of(
                SchemaFields.PARENT_DOC_ID, "parent-1",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.CHUNK_INDEX, "0"));
    var hit = respondWithExcerpts(refused).getResults(0);

    assertEquals(
        0,
        hit.getExcerptRegionsCount(),
        "no excerpt is the honest answer; text from the newer parent revision is not");
    assertNull(
        hit.getFieldsMap().get(SchemaFields.CHUNK_CONTENT),
        "the builder must not conjure chunk text the read path refused to reconstruct");
    assertEquals(
        PARENT_REVISION,
        hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256),
        "the hit itself is still delivered — it loses its snippet, not its identity");
  }

  private SearchResponse respondWithExcerpts(LuceneRuntimeTypes.SearchHit hit) {
    LuceneRuntimeTypes.SearchResult result =
        new LuceneRuntimeTypes.SearchResult(List.of(hit), 1, 5L);
    PipelineConfig pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).build();
    return builder
        .toGrpcResponseBuilder(
            result, 5L, "needle", pipeline, null, /* includeExcerpts= */ true,
            /* includeDetail= */ false)
        .build();
  }

  private SearchResponse respond(LuceneRuntimeTypes.SearchHit hit) {
    LuceneRuntimeTypes.SearchResult result =
        new LuceneRuntimeTypes.SearchResult(List.of(hit), 1, 5L);
    PipelineConfig pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).build();
    return builder
        .toGrpcResponseBuilder(
            result, 5L, "needle", pipeline, null, /* includeExcerpts= */ false,
            /* includeDetail= */ false)
        .build();
  }

  private static LuceneRuntimeTypes.SearchHit chunkHit() {
    return new LuceneRuntimeTypes.SearchHit(
        "chunk:parent-1#0",
        1.0f,
        Map.of(
            SchemaFields.PARENT_DOC_ID, "parent-1",
            SchemaFields.IS_CHUNK, "true",
            SchemaFields.CHUNK_INDEX, "0",
            SchemaFields.CHUNK_CONTENT, "needle in the parent revision"));
  }

  private static LuceneRuntimeTypes.SearchHit wholeDocumentHit() {
    return new LuceneRuntimeTypes.SearchHit(
        "parent-1",
        1.0f,
        Map.of(
            SchemaFields.DOC_ID, "parent-1",
            SchemaFields.DOC_UID, "parent-1-uid",
            SchemaFields.TITLE, "Parent Title",
            SchemaFields.CONTENT, PARENT_CONTENT,
            SchemaFields.CONTENT_SHA256, PARENT_REVISION));
  }
}
