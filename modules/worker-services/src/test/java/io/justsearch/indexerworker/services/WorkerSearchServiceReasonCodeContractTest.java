package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerSearchServiceReasonCodeContractTest {

  private static final Set<String> EMBEDDING_COMPAT_REASON_CODES =
      Set.of(
          "INITIALIZING",
          "NO_EMBEDDING_MODEL",
          "NEW_INDEX_NO_FINGERPRINT",
          "LEGACY_INDEX_NO_FINGERPRINT",
          "FINGERPRINT_MATCH",
          "FINGERPRINT_MISMATCH",
          "REBUILD_IN_PROGRESS",
          "REBUILD_COMPLETED",
          // Tempdoc 819 defect B: the rebuild drained with zero successful embeddings, so the
          // fingerprint attestation was refused instead of stamped over an empty vector space.
          "REBUILD_FAILED_NO_VECTORS",
          // Tempdoc 517: fall-through for unrecognised compat strings
          // via SearchReasonCode.fromCompatString(...). Surfaces when the boundary
          // controller hands back a string the enum doesn't know.
          "EMBEDDING_COMPATIBILITY_UNKNOWN");

  private static final Set<String> SEARCH_REASON_CODES =
      Set.of(
          "UNKNOWN",
          "EMBEDDING_COMPATIBILITY_BLOCKED",
          "NO_EMBEDDING_SERVICE",
          "EMBEDDING_GENERATION_FAILED",
          "EMBEDDING_EXCEPTION",
          "SKIPPED_SHORT_QUERY",
          "SKIPPED_NO_DISCRIMINATIVE_TERM");

  private static final Set<String> CHUNK_MERGE_REASON_CODES =
      Set.of(
          "APPLIED",
          "SKIPPED_DISABLED",
          "SKIPPED_EMPTY_BASE_RESULTS",
          "SKIPPED_PAGINATED",
          "SKIPPED_QUERY_SYNTAX",
          "SKIPPED_SORT_NOT_RELEVANCE",
          "SKIPPED_NO_CHUNK_DOCS",
          // Tempdoc 517 follow-up: missing from the prior allowlist. Emitted at
          // SearchOrchestrator.java:817 in the legacy implementation when the
          // corpus has chunks but is short (corpusSupportsChunks=false). My
          // refactor preserves this emission via the typed
          // SearchReasonCode.SKIPPED_SHORT_CORPUS member; production hits this
          // path whenever the indexed corpus is small.
          "SKIPPED_SHORT_CORPUS",
          "SKIPPED_UNKNOWN",
          "SKIPPED_VECTOR_BLOCKED",
          "SKIPPED_EMPTY_QUERY");

  private static final Set<String> RAG_RETRIEVAL_MODE_REASONS =
      Set.of(
          "EMPTY_REQUEST",
          "NO_CHUNKS_FOUND",
          "CHUNKS_BELOW_THRESHOLD",
          "BM25_CONFIGURED",
          "HYBRID_AVAILABLE",
          "NO_EMBEDDING_SERVICE",
          "EMBEDDING_UNAVAILABLE",
          "EMBEDDING_EMPTY",
          "EMBEDDING_GENERATION_FAILED",
          "CHUNK_VECTOR_COVERAGE_INCOMPLETE");

  @Test
  void searchResponsesOnlyEmitAllowlistedReasonCodes() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      // Vector blocked path
      {
        var service = new WorkerSearchService(lifecycle);
        EmbeddingCompatibilityController controller = new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("ignored")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_VECTOR)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getVectorBlocked());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertTrue(
            union(EMBEDDING_COMPAT_REASON_CODES, SEARCH_REASON_CODES)
                .contains(response.getSearchTrace().getDegradation().getVectorBlockedReason()),
            () -> "Unexpected vectorBlockedReason=" + response.getSearchTrace().getDegradation().getVectorBlockedReason());
      }

      // Hybrid blocked by compatibility controller -> fallback to TEXT with compat reason
      {
        var service = new WorkerSearchService(lifecycle);
        EmbeddingCompatibilityController controller = new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertTrue(
            union(EMBEDDING_COMPAT_REASON_CODES, SEARCH_REASON_CODES)
                .contains(response.getSearchTrace().getDegradation().getHybridFallbackReason()),
            () -> "Unexpected hybridFallbackReason=" + response.getSearchTrace().getDegradation().getHybridFallbackReason());
      }

      // Hybrid with no embedding service -> fallback to TEXT with NO_EMBEDDING_SERVICE
      {
        var service = new WorkerSearchService(lifecycle);
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getHybridFallbackReason());
      }

      // Hybrid with embedding service "available" but embed returns empty -> fallback to TEXT with EMBEDDING_GENERATION_FAILED
      {
        EmbeddingService embeddingService = embeddingServiceAvailableButNullEmbed();
        var service = new WorkerSearchService(lifecycle, embeddingService);
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertEquals("EMBEDDING_GENERATION_FAILED", response.getSearchTrace().getDegradation().getHybridFallbackReason());
        embeddingService.close();
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  void retrieveContextResponsesOnlyEmitAllowlistedReasonCodes() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    String prevMode = System.getProperty("rag.retrieve.mode");
    try (RunningRuntime lifecycle = newLifecycleEmpty()) {
      // EMPTY_REQUEST short-circuits before touching the index.
      {
        var service = new WorkerSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMPTY_REQUEST", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("rag.retrieve.mode", prevMode);
    }
  }

  @Test
  void retrieveContextChunkSearchReasonsAreStable() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    String prevMode = System.getProperty("rag.retrieve.mode");
    try (RunningRuntime lifecycle = newLifecycleWithChunk("doc-1", "Hello world", "Hello chunk")) {
      // BM25_CONFIGURED (explicit)
      {
        System.setProperty("rag.retrieve.mode", "bm25");
        refreshResolvedConfig(lifecycle);
        var service = new WorkerSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("BM25_CONFIGURED", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }

      // NO_EMBEDDING_SERVICE (hybrid configured but no embedding service)
      {
        System.setProperty("rag.retrieve.mode", "hybrid");
        refreshResolvedConfig(lifecycle);
        var service = new WorkerSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("NO_EMBEDDING_SERVICE", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }

      // EMBEDDING_UNAVAILABLE (embedding service present but not available)
      {
        System.setProperty("rag.retrieve.mode", "auto");
        refreshResolvedConfig(lifecycle);
        EmbeddingService embeddingService = new EmbeddingService(Path.of("does-not-exist.gguf"), EmbeddingConfig.DISABLED);
        var service = new WorkerSearchService(lifecycle, embeddingService);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMBEDDING_UNAVAILABLE", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
        embeddingService.close();
      }

      // EMBEDDING_EMPTY (embedding service "available" but embed yields empty)
      {
        System.setProperty("rag.retrieve.mode", "auto");
        refreshResolvedConfig(lifecycle);
        EmbeddingService embeddingService = embeddingServiceAvailableButNullEmbed();
        var service = new WorkerSearchService(lifecycle, embeddingService);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMBEDDING_EMPTY", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
        embeddingService.close();
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("rag.retrieve.mode", prevMode);
    }
  }

  @Test
  void retrieveContextFallbackReasonNoChunksFoundIsStable() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      var service = new WorkerSearchService(lifecycle);
      // Tempdoc 749: a term-matching question is now served via the doc-level union leg in
      // the PRIMARY path, so the fallback (and its NO_CHUNKS_FOUND reason) is only reachable
      // when neither the chunk search nor the union matches — use a no-overlap question to
      // keep this reason-string contract exercised on its genuine remaining path.
      RetrieveContextResponse response =
          invokeRetrieveContext(
              service,
              RetrieveContextRequest.newBuilder()
                  .setQuestion("zebra migration seasons")
                  .addDocIds("doc-1")
                  .setTopK(5)
                  .build());
      assertEquals("NO_CHUNKS_FOUND", response.getRetrievalModeReason());
      assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // ============================================================
  // Tempdoc 774 Stage 1 — chunk-branch base-results gate lever (lever 4) + flag-off equivalence.
  // ============================================================

  @Test
  void chunkBranch_defaultConfig_baseNonEmpty_appliesChunkMerge() throws Exception {
    // Flag-off equivalence: with the shipping defaults and a query that matches the parent doc,
    // the chunk branch still fires (chunkMergeApplied) exactly as before Stage 1.
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle =
        newLifecycleWithChunk("doc-1", "alpha reliability report", "alpha reliability chunk body")) {
      refreshResolvedConfig(lifecycle);
      var service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("reliability")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertEquals(
          "APPLIED",
          TraceStageAccess.chunkMergeReason(response),
          () -> "default config + non-empty base must still apply chunk merge");
      assertTrue(TraceStageAccess.chunkMergeApplied(response));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  void chunkBranch_defaultRequiresBaseResults_emptyBaseSkips() throws Exception {
    // A query matching ONLY the chunk (not the parent's whole-doc content) leaves the doc legs
    // empty. With the default flag (true), the chunk branch is gated → SKIPPED_EMPTY_BASE_RESULTS.
    String prevConfig = System.getProperty("justsearch.config");
    String prevFlag = System.getProperty("index.hybrid.chunk_branch_requires_base_results");
    System.clearProperty("index.hybrid.chunk_branch_requires_base_results");
    try (RunningRuntime lifecycle =
        newLifecycleWithChunk("doc-1", "alpha alpha alpha", "betamarker gamma delta")) {
      refreshResolvedConfig(lifecycle);
      var service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("betamarker")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertEquals(
          "SKIPPED_EMPTY_BASE_RESULTS",
          TraceStageAccess.chunkMergeReason(response),
          () -> "default gate: empty base must skip the chunk branch");
      assertFalse(TraceStageAccess.chunkMergeApplied(response));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("index.hybrid.chunk_branch_requires_base_results", prevFlag);
    }
  }

  @Test
  void chunkBranch_requiresBaseResultsFalse_emptyBaseAppliesChunkMerge() throws Exception {
    // Lever 4 ON: with the gate disabled, the same chunk-only-matching query now runs the chunk
    // branch even though the doc legs returned empty → APPLIED with chunk-branch results.
    String prevConfig = System.getProperty("justsearch.config");
    String prevFlag = System.getProperty("index.hybrid.chunk_branch_requires_base_results");
    System.setProperty("index.hybrid.chunk_branch_requires_base_results", "false");
    try (RunningRuntime lifecycle =
        newLifecycleWithChunk("doc-1", "alpha alpha alpha", "betamarker gamma delta")) {
      refreshResolvedConfig(lifecycle);
      var service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("betamarker")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertEquals(
          "APPLIED",
          TraceStageAccess.chunkMergeReason(response),
          () -> "gate disabled: empty base must still apply the chunk branch");
      assertTrue(TraceStageAccess.chunkMergeApplied(response));
      // The chunk-only match surfaces its parent doc.
      assertTrue(response.getTotalHits() > 0, "chunk branch contributes the parent as a result");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("index.hybrid.chunk_branch_requires_base_results", prevFlag);
    }
  }

  @Test
  void chunkSideRecallComplete_rescuesLegTopNParentDroppedByCollapse() throws Exception {
    // Tempdoc 774 Stage 1 — the gap case. p-d's chunk is in the BM25 leg's top-N but its parent is
    // dropped by the collapse cap (collapse keeps the top-3 by fused chunk score). The whole-doc base
    // finds only p-a/p-b/p-c (p-d's parent content lacks the term), so p-d reaches the judge window
    // ONLY if chunk-side recall-complete splices it in from the PRE-collapse leg-top-N set.
    String prevConfig = System.getProperty("justsearch.config");
    String prevMult = System.getProperty("index.hybrid.chunk_collapse_limit_multiplier");
    String prevRecall = System.getProperty("index.hybrid.chunk_leg_recall_complete_enabled");
    System.setProperty("index.hybrid.chunk_collapse_limit_multiplier", "1");
    try (RunningRuntime lifecycle =
        newLifecycleWithChunkedParents(
            List.of(
                new String[] {
                  "p-a", "reliability overview alpha", "reliability reliability reliability reliability"
                },
                new String[] {
                  "p-b", "reliability overview beta", "reliability reliability reliability zzq1"
                },
                new String[] {
                  "p-c", "reliability overview gamma", "reliability reliability zzq1 zzq2"
                },
                new String[] {"p-d", "unrelated delta epsilon", "reliability zzq1 zzq2 zzq3"}))) {

      // Flag OFF (default): p-d is dropped by collapse and missing from base → absent from results.
      System.clearProperty("index.hybrid.chunk_leg_recall_complete_enabled");
      refreshResolvedConfig(lifecycle);
      Set<String> offIds = resultIds(new WorkerSearchService(lifecycle));
      assertTrue(offIds.contains("p-a"), () -> "sanity: top chunk parent present, got " + offIds);
      assertFalse(
          offIds.contains("p-d"),
          () -> "flag off: collapse-dropped leg-topN parent must be absent, got " + offIds);

      // Flag ON: p-d (BM25 leg top-N, captured pre-collapse) is spliced back into the judge window.
      System.setProperty("index.hybrid.chunk_leg_recall_complete_enabled", "true");
      refreshResolvedConfig(lifecycle);
      Set<String> onIds = resultIds(new WorkerSearchService(lifecycle));
      assertTrue(
          onIds.contains("p-d"),
          () -> "flag on: collapse-dropped leg-topN parent must be rescued, got " + onIds);
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("index.hybrid.chunk_collapse_limit_multiplier", prevMult);
      restoreProperty("index.hybrid.chunk_leg_recall_complete_enabled", prevRecall);
    }
  }

  private static Set<String> resultIds(WorkerSearchService service) {
    SearchResponse response =
        invokeSearch(
            service,
            SearchRequest.newBuilder()
                .setQuery("reliability")
                .setLimit(3)
                .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                .build());
    return response.getResultsList().stream()
        .map(r -> r.getId())
        .collect(java.util.stream.Collectors.toSet());
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    java.util.HashSet<String> out = new java.util.HashSet<>();
    out.addAll(a);
    out.addAll(b);
    return java.util.Collections.unmodifiableSet(out);
  }

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private static RetrieveContextResponse invokeRetrieveContext(
      WorkerSearchService service, RetrieveContextRequest request) {
    RetrieveContextResponse response = service.retrieveContext(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleEmpty() throws Exception {
    return newLifecycleWithCatalog(FieldCatalogDef.forChunkTesting(4));
  }

  private static RunningRuntime newLifecycleWithOneDoc(String docId, String content) throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    RunningRuntime lifecycle = newLifecycleWithCatalog(catalog);
    var runtime = lifecycle;
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, docId,
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static RunningRuntime newLifecycleWithChunk(String parentDocId, String parentContent, String chunkContent)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    RunningRuntime lifecycle = newLifecycleWithCatalog(catalog);
    var runtime = lifecycle;

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, parentDocId,
                SchemaFields.DOC_UID, parentDocId + "#0",
                SchemaFields.PATH, parentDocId,
                SchemaFields.CONTENT, parentContent)));

    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, "chunk-1");
    fields.put(SchemaFields.DOC_UID, "chunk-1#0");
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, parentDocId);
    fields.put(SchemaFields.CHUNK_INDEX, "0");
    fields.put(SchemaFields.CHUNK_TOTAL, "1");
    fields.put(SchemaFields.CHUNK_CONTENT, chunkContent);
    fields.put(SchemaFields.CHUNK_START_CHAR, "0");
    fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(Math.max(0, chunkContent.length())));
    // Tempdoc 931 §C.1: the revision the offsets above address. Without it the read path refuses
    // to re-slice the chunk's text out of the parent, and the chunk reads as textless.
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
    fields.put(SchemaFields.PATH, parentDocId);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  /**
   * Indexes N (parent, chunk) pairs into one corpus. Each entry is {@code {parentId, parentContent,
   * chunkContent}}. Used by the 774 Stage 1 chunk-side recall-complete gap-case test.
   */
  private static RunningRuntime newLifecycleWithChunkedParents(List<String[]> docs) throws Exception {
    RunningRuntime lifecycle = newLifecycleWithCatalog(FieldCatalogDef.forChunkTesting(4));
    int idx = 0;
    for (String[] d : docs) {
      String parentId = d[0];
      lifecycle
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, parentId,
                      SchemaFields.DOC_UID, parentId + "#0",
                      SchemaFields.PATH, parentId,
                      SchemaFields.CONTENT, d[1])));

      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "chunk-" + idx);
      fields.put(SchemaFields.DOC_UID, "chunk-" + idx + "#0");
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parentId);
      fields.put(SchemaFields.CHUNK_INDEX, "0");
      fields.put(SchemaFields.CHUNK_TOTAL, "1");
      fields.put(SchemaFields.CHUNK_CONTENT, d[2]);
      fields.put(SchemaFields.CHUNK_START_CHAR, "0");
      fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(Math.max(0, d[2].length())));
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(d[1]));
      fields.put(SchemaFields.PATH, parentId);
      fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
      idx++;
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static RunningRuntime newLifecycleWithCatalog(FieldCatalogDef catalog) throws Exception {
    Path base = Files.createTempDirectory("justsearch-reason-code-contract-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: reasoncode\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void forceEmbeddingCompatState(
      EmbeddingCompatibilityController controller, State state, String reasonCode) throws Exception {
    Field stateField = EmbeddingCompatibilityController.class.getDeclaredField("state");
    stateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<State> stateRef = (AtomicReference<State>) stateField.get(controller);
    stateRef.set(state);

    Field reasonField = EmbeddingCompatibilityController.class.getDeclaredField("reasonCode");
    reasonField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> reasonRef = (AtomicReference<String>) reasonField.get(controller);
    reasonRef.set(reasonCode);
  }

  private static EmbeddingService embeddingServiceAvailableButNullEmbed() throws Exception {
    EmbeddingService svc = new EmbeddingService(Path.of("dummy.gguf"), EmbeddingConfig.DISABLED);

    Field initializedField = EmbeddingService.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    AtomicBoolean initialized = (AtomicBoolean) initializedField.get(svc);
    initialized.set(true);

    Field availableField = EmbeddingService.class.getDeclaredField("available");
    availableField.setAccessible(true);
    availableField.setBoolean(svc, true);

    // backend remains null; embed() returns null, exercising degradation paths without loading llama.cpp.
    return svc;
  }

  /** Re-resolve config from current system properties so sysprop changes take effect. */
  private static void refreshResolvedConfig(RunningRuntime lifecycle) {
    try {
      // Tempdoc 406 Gap A: resolvedConfig now lives directly on RuntimeSession
      // (RuntimeContext was deleted; field is reachable via session.resolvedConfig).
      Field sessionField = RunningRuntime.class.getDeclaredField("session");
      sessionField.setAccessible(true);
      Object session = sessionField.get(lifecycle);
      Field rcField = session.getClass().getDeclaredField("resolvedConfig");
      rcField.setAccessible(true);

      var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
      builder.contributeEnvRegistry();
      io.justsearch.configuration.JustSearchConfigurationLoader.loadYamlRoot()
          .ifPresent(builder::contributeYaml);
      rcField.set(session, builder.build());
    } catch (Exception e) {
      throw new RuntimeException("Failed to refresh resolvedConfig", e);
    }
  }
}
