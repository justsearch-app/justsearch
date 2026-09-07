/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C2 (review fix 2) — collection scoping under {@code useHybrid=true}.
 *
 * <p>Why this class exists separately from {@link WorkerSearchServiceRetrieveContextTest}: that class
 * builds {@code new WorkerSearchService(lifecycle)}, which wires {@code NoOpEmbeddingProvider}, whose
 * {@code isAvailable()} is false — so every one of its cases runs the BM25 leg, where
 * {@code ChunkSearchOps#searchChunksFiltered} treats an empty {@code docIds} as UNSCOPED. Under
 * hybrid the same request behaves differently: both {@code searchChunksHybrid} overloads
 * early-return EMPTY on an empty {@code docIds}. Production only survives that because the Head's
 * open-retrieval pre-search populates {@code doc_ids} first — a load-bearing coupling that no
 * BM25-only test can see. This class makes it visible and regression-guarded.
 */
@DisplayName("RAG collection scope under hybrid retrieval (821 §3-C2)")
final class RagContextOpsHybridCollectionScopeTest {

  private static final String IN_SCOPE = "d:/agent/session-7.md";
  private static final String OUT_OF_SCOPE = "d:/docs/handbook.md";

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  /** Available embedder returning a fixed unit vector — enough to make {@code useHybrid} true. */
  private static final class StubEmbeddingProvider implements EmbeddingProvider {
    private static final float[] VECTOR = {0.5f, 0.5f, 0.5f, 0.5f};

    @Override
    public float[] embedDocument(String text) {
      return VECTOR.clone();
    }

    @Override
    public float[] embedQuery(String text) {
      return VECTOR.clone();
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      return texts.stream().map(t -> VECTOR.clone()).toList();
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return VECTOR.length;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isUsingGpu() {
      return false;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(tempDir).open();
    service = new WorkerSearchService(lifecycle, new StubEmbeddingProvider());

    indexDocWithChunk(IN_SCOPE, "agent-history", "Rollback checklist AGENTMARKER from the run.");
    indexDocWithChunk(OUT_OF_SCOPE, null, "Rollback checklist HANDBOOKMARKER for operators.");
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private void indexDocWithChunk(String docId, String collection, String text) throws Exception {
    Map<String, Object> parent = new HashMap<>(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, docId + "#0",
        SchemaFields.PATH, docId,
        SchemaFields.CONTENT, text,
        SchemaFields.MIME, "text/markdown"));
    Map<String, Object> chunk = new HashMap<>(Map.of(
        SchemaFields.DOC_ID, "chunk:" + docId,
        SchemaFields.DOC_UID, "chunk:" + docId + "#0",
        SchemaFields.PATH, docId,
        SchemaFields.PARENT_DOC_ID, docId,
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.CHUNK_INDEX, "0",
        SchemaFields.CHUNK_TOTAL, "1",
        SchemaFields.CHUNK_CONTENT, text,
        SchemaFields.CHUNK_START_CHAR, "0",
        SchemaFields.CHUNK_END_CHAR, String.valueOf(text.length())));
    // Tempdoc 931 §C.1: the parent revision the chunk offsets address; the read path refuses to
    // reconstruct chunk text without it.
    chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
    if (collection != null) {
      parent.put(SchemaFields.COLLECTION, collection);
      chunk.put(SchemaFields.COLLECTION, collection);
    }
    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(parent));
    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(chunk));
  }

  @Test
  @DisplayName("hybrid actually engages — otherwise every assertion here degrades to BM25")
  void hybridActuallyEngages() {
    var response = call(RetrieveContextRequest.newBuilder()
        .setQuestion("rollback checklist")
        .setTopK(5)
        .addDocIds(IN_SCOPE)
        .addDocIds(OUT_OF_SCOPE)
        .build());

    // retrievalMode is the EFFECTIVE leg; retrievalModeReason gets refined after the hybrid
    // decision (e.g. to CHUNK_VECTOR_COVERAGE_INCOMPLETE), so the mode is the honest signal.
    assertEquals("HYBRID", response.getRetrievalMode(),
        "the stub embedder must put this class on the doc-first hybrid leg; \"BM25\" here means the"
            + " remaining tests prove nothing about hybrid");
  }

  @Test
  @DisplayName("with pre-search-populated doc_ids, the scope binds under hybrid")
  void scopeBindsUnderHybridWithPopulatedDocIds() {
    // This is the shape the Head actually sends: RemoteDocumentService#preSearchForDocIds resolved
    // the doc universe first (scoped by the same collection since 821 §3-C2) and put it on doc_ids.
    var response = call(RetrieveContextRequest.newBuilder()
        .setQuestion("rollback checklist")
        .setTopK(5)
        .addDocIds(IN_SCOPE)
        .addDocIds(OUT_OF_SCOPE)
        .addCollection("agent-history")
        .build());

    assertTrue(response.getUsedChunks(), "hybrid chunk retrieval must answer: " + response);
    assertTrue(response.getContext().contains("AGENTMARKER"),
        "the in-scope document must be retrieved under hybrid: " + response.getContext());
    assertFalse(response.getContext().contains("HANDBOOKMARKER"),
        "the scope must exclude out-of-scope chunks on the hybrid leg too: "
            + response.getContext());
  }

  @Test
  @DisplayName("the coupling itself: without doc_ids the hybrid chunk legs early-return empty")
  void hybridWithoutDocIdsRetrievesNothing() {
    // Documents the dependency the WHY comment on RagContextOps#buildRagFilters names. This is NOT
    // a desired behaviour — it is the reason the collection-only-stays-on-the-chunk-path routing
    // decision is only safe while the Head pre-search populates doc_ids. If the pre-search is ever
    // dropped or left unscoped, this test flips to green-with-content and the routing must be
    // revisited rather than the filter.
    var response = call(RetrieveContextRequest.newBuilder()
        .setQuestion("rollback checklist")
        .setTopK(5)
        .addCollection("agent-history")
        .build());

    assertFalse(response.getUsedChunks(),
        "ChunkSearchOps#searchChunksHybrid early-returns on empty docIds — a doc_ids-less hybrid"
            + " request cannot retrieve chunks however well the collection filter is built");
    assertTrue(response.getContext().isEmpty(),
        "and the whole-document fallback keeps its return-empty-on-empty-scope contract: "
            + response.getContext());
  }

  private RetrieveContextResponse call(RetrieveContextRequest request) {
    RetrieveContextResponse response;
    try {
      response = service.retrieveContext(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("RetrieveContext failed: " + e.getMessage());
    }
    assertNotNull(response, "Response should not be null");
    return response;
  }
}
