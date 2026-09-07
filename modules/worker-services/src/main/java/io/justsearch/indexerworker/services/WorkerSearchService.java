/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.ipc.logging.MdcContext;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.FolderBrowseEngine;
import io.justsearch.adapters.lucene.runtime.SuggestOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderBrowseResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderFilesResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderInfo;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ListAllDocumentIdsResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListAllDocumentIdsRequest;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import io.justsearch.ipc.ListFolderFilesRequest;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersRequest;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;

import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.reranker.RerankerConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process Search service supporting text, vector, and hybrid search.
 *
 * <p>Lane F stage A item A3: this is a plain service — it returns its response object and reports
 * failure by throwing {@link WorkerServiceException}. Between A3 and A9 it was reached through a
 * wire adapter that did the transport framing and mapped each failure back onto the identical
 * status code; item A9 deleted that adapter, and callers now reach this instance directly through
 * {@code WorkerAppServices.searchService()}.
 *
 * <p>Executes search queries against the Lucene index and returns results. Every method here is
 * <b>foreground</b>: {@code ForegroundLoadGate} counts each call in the Worker's foreground-load
 * gauge for its duration, and the indexing loop throttles itself to a minimum duty while any is in
 * flight (tempdoc 885 item 3). Search itself is never throttled — it is what indexing yields to.
 * The one exception is {@code listAllDocumentIds}, whose caller is a background pager, not a user.
 *
 * <p>Returned content is trimmed to {@link #MAX_CONTENT_CHARS} to prevent memory issues with very
 * large documents.
 *
 * <p>Pipeline modes: TEXT (BM25), VECTOR (KNN), HYBRID (BM25+KNN+RRF), SPLADE.
 * Mode is resolved from {@code PipelineConfig} on the request (or expanded from
 * the deprecated {@code SearchMode} field for backwards compatibility).
 */
public final class WorkerSearchService {
  private static final Logger log = LoggerFactory.getLogger(WorkerSearchService.class);

  /**
   * Maximum content characters to return to prevent memory issues. Shared with the Head's
   * {@code BoundedDocumentFetch} pager, which sizes its byte budget by this number.
   */
  private static final int MAX_CONTENT_CHARS =
      io.justsearch.ipc.grpc.GrpcMessageLimits.MAX_DOCUMENT_CONTENT_CHARS;

  /** Default/max slice sizes for FetchDocumentSlice. */
  private static final int DEFAULT_SLICE_CHARS = 20_000;
  private static final int MAX_SLICE_CHARS = 200_000;

  private final CommitOps commitOps;
  private final SuggestOps suggestOps;
  private final DocumentFieldOps documentFieldOps;
  private final FolderBrowseEngine folderBrowseEngine;
  private final String documentIdSnapshotEpoch = UUID.randomUUID().toString();
  private volatile EmbeddingCompatibilityController embeddingCompatController;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();

  private final SearchOrchestrator searchOrchestrator;
  private final CitationMatchOps citationMatchOps;
  private final RagContextOps ragContextOps;
  // Tempdoc 598 review Fix A: the current query-embedding provider, tracked here so the AUTO
  // resolution can gate the dense leg on embedder availability (not just embedding-compat),
  // avoiding a wasted query-embed attempt when the embedder is unloaded (e.g. Online mode).
  private volatile EmbeddingProvider embeddingProvider;

  /** 360: Worker-side search reranker (GPU-capable). Set via deferred wiring. */
  private volatile CrossEncoderReranker searchReranker;

  /**
   * Tempdoc 397 §14.28 U3: supplier for the {@code modelReadyLatch} in KnowledgeServer.
   * Query handlers await this latch before first use (with a 120 s timeout). Closes the
   * T2-E1 boot-race gap where queries arriving before {@code initDeferredModels} completed
   * silently missed reranker / NER wiring. Null = no latch wired (e.g., unit tests that
   * construct this service directly without KnowledgeServer) — in which case await is a
   * no-op.
   */
  private volatile java.util.function.Supplier<java.util.concurrent.CountDownLatch>
      modelReadyLatchSupplier;

  /**
   * Await timeout for the models-ready gate. Matches the migration-enumerator ceiling in
   * {@code KnowledgeServer}. Hardcoded rather than env-var-driven because worker-services is
   * under an ArchUnit rule that forbids direct {@code System.getenv} reads — the gate's
   * timeout is a structural invariant, not an operational tuning knob.
   */
  private static final long MODEL_READY_TIMEOUT_MS = 120_000L;

  /**
   * Creates a new WorkerSearchService backed by the specified Lucene lifecycle manager.
   *
   * @param searchLifecycle The Lucene lifecycle manager
   */
  public WorkerSearchService(io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle) {
    this(searchLifecycle, NoOpEmbeddingProvider.INSTANCE, null);
  }

  /**
   * Creates a new WorkerSearchService with embedding support for hybrid search.
   *
   * @param searchLifecycle The Lucene lifecycle manager
   * @param embeddingProvider Embedding provider for query-time embedding
   */
  public WorkerSearchService(io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle, EmbeddingProvider embeddingProvider) {
    this(searchLifecycle, embeddingProvider, null);
  }

  /**
   * Canonical ctor that takes the shared {@link io.justsearch.indexerworker.server.EncoderBindings}
   * registry. Production composition (DefaultWorkerAppServices) passes the same instance to
   * IndexingLoop so the wire* methods can bind once and both sides observe the update.
   * Tempdoc 516 P3 / Slice 5 (W7.2).
   *
   * @param searchLifecycle The Lucene lifecycle manager
   * @param embeddingProvider Embedding provider for query-time embedding (may be null)
   * @param encoderBindings Shared encoder/service registry (may be null — defaults to empty)
   */
  public WorkerSearchService(
      io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle,
      EmbeddingProvider embeddingProvider,
      io.justsearch.indexerworker.server.EncoderBindings encoderBindings) {
    EmbeddingProvider provider =
        embeddingProvider != null ? embeddingProvider : NoOpEmbeddingProvider.INSTANCE;
    this.embeddingProvider = provider;
    io.justsearch.indexerworker.server.EncoderBindings bindings =
        encoderBindings != null
            ? encoderBindings
            : new io.justsearch.indexerworker.server.EncoderBindings();
    this.commitOps = searchLifecycle.commitOps();
    this.suggestOps = searchLifecycle.suggestOps();
    this.documentFieldOps = searchLifecycle.documentFieldOps();
    this.folderBrowseEngine = searchLifecycle.folderBrowseEngine();
    this.searchOrchestrator = new SearchOrchestrator(searchLifecycle, provider, bindings);
    this.citationMatchOps = new CitationMatchOps(
        searchLifecycle.readPathOps(), searchLifecycle.commitOps(), provider);
    this.ragContextOps = new RagContextOps(
        searchLifecycle.chunkSearchOps(),
        searchLifecycle.indexCountOps(),
        searchLifecycle.commitOps(),
        searchLifecycle::resolvedConfig,
        provider,
        searchLifecycle.documentFieldOps());
  }

  /**
   * Deferred injection of the embedding provider for query-time embedding.
   *
   * <p>The embedding model is loaded asynchronously after the gRPC service is created, so the
   * constructor receives null. This setter distributes the provider to all sub-components that need
   * it for query-time vector generation.
   */
  public void setEmbeddingProvider(EmbeddingProvider provider) {
    this.embeddingProvider = provider != null ? provider : NoOpEmbeddingProvider.INSTANCE;
    this.searchOrchestrator.setEmbeddingProvider(provider);
    this.citationMatchOps.setEmbeddingProvider(provider);
    this.ragContextOps.setEmbeddingProvider(provider);
  }

  /**
   * Sets the embedding compatibility controller.
   *
   * <p>This is called after construction to inject the controller, which is created
   * after the gRPC service due to circular dependencies.
   *
   * @param controller the embedding compatibility controller
   */
  public void setEmbeddingCompatController(EmbeddingCompatibilityController controller) {
    this.embeddingCompatController = controller;
  }

  /**
   * Sets the chunk reranker configuration (Phase 5).
   *
   * <p>This is called after construction to inject the config.
   * If config is ready (enabled + model path set), the reranker will be lazily initialized.
   *
   * @param config the chunk reranker configuration
   */
  public void setChunkRerankerConfig(RerankerConfig.ChunkRerankerConfig config) {
    this.ragContextOps.setChunkRerankerConfig(config);
  }

  /**
   * Sets the citation scorer configuration.
   *
   * <p>When config is ready (enabled + model path set), the scorer will be lazily initialized
   * on first use. The scorer runs on CPU only, avoiding GPU contention with the LLM.
   *
   * @param config the citation scorer configuration
   */
  public void setCitationScorerConfig(CitationScorerConfig config) {
    this.citationMatchOps.setCitationScorerConfig(config);
  }

  /**
   * Sets the eagerly-constructed {@link io.justsearch.reranker.CitationScorer} from the
   * composition root (tempdoc 397 §14.26 T2-E1).
   */
  public void setCitationScorer(io.justsearch.reranker.CitationScorer scorer) {
    this.citationMatchOps.setCitationScorer(scorer);
  }

  /**
   * Sets the entity cluster snapshot supplier for disambiguation (Phase C).
   *
   * <p>When set, the search orchestrator will merge entity facet counts by canonical form
   * and expand entity filters to include all variant forms.
   *
   * @param supplier the cluster snapshot supplier
   */
  public void setClusterSnapshotSupplier(
      java.util.function.Supplier<
              io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot>
          supplier) {
    this.searchOrchestrator.setClusterSnapshotSupplier(supplier);
  }

  /**
   * Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): inject the active
   * Lucene IndexSearcher generation supplier. Wired from the composition
   * root once {@code IndexStatusOps} (or another stateSnapshot source) is
   * available. The supplier's string value lands on every {@code
   * search/retrieval} span as {@code search.searcher_generation}.
   */
  public void setActiveGenerationSupplier(java.util.function.Supplier<String> supplier) {
    this.searchOrchestrator.setActiveGenerationSupplier(supplier);
  }

  // Tempdoc 516 P3 / Slice 5 (W7.2): setSpladeEncoder + setBgeM3Encoder removed —
  // DefaultWorkerAppServices.wireX now binds once on the shared EncoderBindings and the
  // SearchOrchestrator reads through the same registry. setSpladeIdfQueryEncoder stays
  // (different async path — query-side IDF helper, not the indexing-side encoder).
  public void setSpladeIdfQueryEncoder(
      io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder encoder) {
    this.searchOrchestrator.setSpladeIdfQueryEncoder(encoder);
  }

  /**
   * 360: Sets the Worker-side search reranker (deferred wiring from initDeferredModels).
   *
   * <p>When set, the {@link #rerank} method delegates to this instance. Also shared with
   * {@link RagContextOps} for chunk reranking, replacing its CPU-only instance.
   */
  public void setSearchReranker(CrossEncoderReranker reranker) {
    this.searchReranker = reranker;
    this.ragContextOps.setSearchReranker(reranker);
  }

  /**
   * Tempdoc 397 §14.28 U3: wires a supplier for the {@code modelReadyLatch} that inference-
   * dependent query handlers ({@link #search}, {@link #retrieveContext}, {@link #rerank},
   * {@link #matchCitations}) await before first use. Closes the T2-E1 boot-race gap.
   */
  public void setModelReadyLatchSupplier(
      java.util.function.Supplier<java.util.concurrent.CountDownLatch> supplier) {
    this.modelReadyLatchSupplier = supplier;
  }

  /**
   * Awaits the models-ready latch (§14.28 U3) with a timeout. No-op when no latch is wired
   * (e.g., unit tests that construct this service directly). Logs a warn if the wait times
   * out — caller proceeds anyway; per-method degradation semantics are handled downstream.
   *
   * <p>Package-private for {@code WorkerSearchServiceModelReadyLatchTest}.
   *
   * @param rpcName for log context
   */
  void awaitModelsReady(String rpcName) {
    var supplier = modelReadyLatchSupplier;
    if (supplier == null) {
      return;
    }
    java.util.concurrent.CountDownLatch latch = supplier.get();
    if (latch == null || latch.getCount() == 0) {
      return;
    }
    try {
      if (!latch.await(MODEL_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        log.warn(
            "{}: models not ready after {} ms; proceeding with degraded path",
            rpcName,
            MODEL_READY_TIMEOUT_MS);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("{}: interrupted while waiting for models-ready latch", rpcName);
    }
  }

  /**
   * Called when Main process claims GPU. Releases reranker GPU session to yield VRAM.
   */
  public void onMainClaimedGpu() {
    ragContextOps.onMainClaimedGpu();
    CrossEncoderReranker sr = searchReranker;
    if (sr != null) {
      sr.releaseGpuSession();
    }
  }

  /**
   * Returns the ORT CUDA status for the search reranker (360), falling back to chunk reranker.
   *
   * @return current ORT CUDA status, or null if no reranker available
   */
  public OrtCudaStatus getOrtCudaStatus() {
    CrossEncoderReranker sr = searchReranker;
    if (sr != null) {
      return sr.getOrtCudaStatus();
    }
    return ragContextOps.getOrtCudaStatus();
  }

  /** Returns true if the citation scorer is initialized and ready for inference (368 RC3). */
  public boolean isCitationScorerActive() {
    return citationMatchOps.isCitationScorerActive();
  }

  /** Snapshot of embedding compatibility state for a single request. */
  private record EmbeddingCompat(boolean allowed, String reasonCode) {}

  /**
   * Takes a consistent snapshot of embedding compatibility state.
   *
   * <p>Reads the volatile controller reference once, extracting both the allowed flag
   * and reason code from the same snapshot to avoid TOCTOU races.
   */
  private EmbeddingCompat embeddingCompat() {
    var controller = embeddingCompatController;
    return new EmbeddingCompat(
        controller == null || controller.allowQueryEmbeddings(),
        controller != null ? controller.reasonCode() : null);
  }

  /**
   * Tempdoc 598 R1: resolve the capability-derived AUTO marker into a concrete dense decision.
   * When {@code pipeline.dense_auto} is set, the caller deferred the dense leg to the engine
   * (generalizing the RAG {@code retrieveMode="auto"} rule); enable it iff {@code denseServiceable}.
   * Returns the request unchanged when AUTO is not set.
   *
   * <p>Review Fix A: {@code denseServiceable} is computed at the call site as
   * {@code allowQueryEmbeddings (index COMPATIBLE) && embeddingProvider.isAvailable()} — gating on
   * embedder availability too, so AUTO does not request a query-embed that would just fail when the
   * embedder is unloaded (e.g. Online mode). The planner's hybrid-fallback remains the safety net for
   * the residual check-vs-encode race.
   *
   * <p>Package-private (static + pure) for direct unit testing (tempdoc 598 R1 regression).
   */
  static SearchRequest resolveAutoDense(SearchRequest request, boolean denseServiceable) {
    if (!request.hasPipeline() || !request.getPipeline().getDenseAuto()) {
      return request;
    }
    var resolved =
        request.getPipeline().toBuilder().setDenseEnabled(denseServiceable).build();
    return request.toBuilder().setPipeline(resolved).build();
  }

  /** Opens an MDC scope with the trace_id and request_id the caller supplied on {@link CallContext}. */
  private MdcContext openRequestMdc(CallContext ctx) { // NOPMD - AutoCloseable for logging context side-effect
    return MdcContext.request(ctx.traceId(), ctx.requestId());
  }

  /**
   * Tempdoc 687 R3d: boot-time search-path warm-up. Called in-process by {@code
   * KnowledgeServer} once all encoders are wired (the same lifecycle point as the existing
   * search-reranker warm-up), so the first real user query doesn't pay the Lucene/ICU
   * analyzer + query-builder + {@code IndexSearcher} JIT/class-load cold-start cost. Does
   * NOT go through the {@link #search} RPC method (no {@code awaitModelsReady} gate needed —
   * this runs before the model-ready latch is released — and no gRPC framing). Delegates to
   * {@link SearchOrchestrator#warmUp()}; see its Javadoc for exactly what is (and is
   * deliberately not) exercised.
   *
   * @return {@code true} if the warm-up pass ran, {@code false} if skipped (empty index)
   */
  public boolean warmUpSearchPath() {
    return searchOrchestrator.warmUp();
  }

  public SearchResponse search(SearchRequest request, CallContext ctx) {
    awaitModelsReady("search");
    try (var ignored = openRequestMdc(ctx)) {
      // Pipeline MDC context from proto fields set by Head (298). pipeline_hash + budget_profile
      // were retired by tempdoc 400 LR2-d (orphan fields per ADR 0014); only pipeline_name
      // remains populated.
      var pipeline = request.hasPipeline() ? request.getPipeline() : null;
      try (var ignored2 = MdcContext.pipeline( // NOPMD
          pipeline != null ? pipeline.getPipelineName() : null)) {
        try {
          var compat = embeddingCompat();
          // Tempdoc 598 R1: resolve the capability-derived AUTO marker into a concrete dense
          // decision before the orchestrator runs, so both SearchInputCapture and SearchPlanner
          // (which independently read pipeline.dense_enabled) see the resolved value. Review Fix A:
          // dense is serviceable only when the index is COMPATIBLE AND the embedder is available.
          boolean denseServiceable = compat.allowed() && embeddingProvider.isAvailable();
          SearchRequest effectiveRequest = resolveAutoDense(request, denseServiceable);
          return searchOrchestrator.execute(
              effectiveRequest, compat.allowed(), compat.reasonCode(), ctx);
        } catch (IllegalArgumentException e) {
          metrics.recordSearchFailed();
          // The CALLER still gets the full message below — it is their own query. This LOG line does
          // not: a Lucene ParseException quotes the query verbatim, and engine.log is bundled into
          // the diagnostics export (path-only redaction), which is exactly why the sibling
          // parse-failure site keeps query text at TRACE (SearchExecutor:160-168). Same split here.
          log.warn("Invalid search request: {}", withoutQuotedQuery(e.getMessage()));
          log.trace("Invalid search request detail", e);
          throw WorkerServiceException.invalidArgument(e.getMessage());
        } catch (WorkerServiceException e) {
          // Review B3. The stage-boundary polls below the orchestrator raise CANCELLED, and
          // CANCELLED is a RuntimeException — so without this arm the generic catch would have
          // relabelled a normal, expected cancellation as INTERNAL, logged it at ERROR, and
          // counted it as a search failure. That is three wrong answers from one missing arm: a
          // 500 where the caller has already gone, an error log per abandoned search, and a
          // failure metric that rises when users navigate away.
          //
          // The arm is not narrowed to CANCELLED, because re-wrapping ANY of this service's own
          // statuses as INTERNAL is the same defect the review found at the client boundary (B1):
          // a status that was chosen deliberately, discarded on the way out. The failure metric
          // still counts every one of them except the cancellation, which is not a failure of the
          // search.
          if (e.status() != WorkerServiceException.Status.CANCELLED) {
            metrics.recordSearchFailed();
            log.error("Search failed", e);
          } else {
            log.debug("Search cancelled by caller: {}", e.getMessage());
          }
          throw e;
        } catch (RuntimeException e) {
          metrics.recordSearchFailed();
          log.error("Search failed", e);
          throw WorkerServiceException.internal("Search failed: " + e.getMessage());
        }
      }
    }
  }


  /**
   * 360: Cross-encoder reranking RPC. Delegates to the Worker's GPU-capable
   * {@link CrossEncoderReranker} instance, or returns skipped if not loaded.
   */
  public RerankResponse rerank(RerankRequest request, CallContext ctx) {
    awaitModelsReady("rerank");
    try (var ignored = openRequestMdc(ctx)) {
      CrossEncoderReranker reranker = searchReranker;
      if (reranker == null) {
        return RerankResponse.newBuilder()
            .setSkipped(true)
            .setSkipReason("MODEL_NOT_LOADED")
            .build();
      }

      try {
        List<String> docTexts = request.getDocumentTextsList();
        long deadlineMs = request.getDeadlineMs();
        CrossEncoderReranker.RerankedResult result = reranker.rerank(
            request.getQuery(), docTexts, deadlineMs > 0 ? deadlineMs : 200);

        RerankResponse.Builder resp = RerankResponse.newBuilder()
            .setSkipped(result.skipped())
            .setElapsedMs(result.latencyMs());

        if (result.skipped()) {
          resp.setSkipReason(wireSkipReason(result.skipCause()));
        } else {
          for (int idx : result.sortedIndices()) {
            resp.addSortedIndices(idx);
          }
          for (float score : result.scores()) {
            resp.addScores(score);
          }
        }
        return resp.build();
      } catch (RuntimeException e) {
        log.error("Rerank failed", e);
        throw WorkerServiceException.internal("Rerank failed: " + e.getMessage());
      }
    }
  }

  /**
   * Maps the reranker's local skip cause onto the Head-owned {@code CrossEncoderSkipReason} wire
   * vocabulary carried by {@code RerankResponse.skip_reason} (register F-054).
   *
   * <p>Only a budget pre-check is a genuine deadline miss. An inference failure used to be stamped
   * {@code DEADLINE_EXCEEDED} too, which named a knob that cannot fix it — a measured campaign
   * found 199/200 "deadline misses" were ONNX Runtime arena exhaustion. {@code NONE} is
   * unreachable here (the caller only asks on the skipped branch) and resolves to the empty string,
   * which the Head normalises to {@code UNKNOWN} rather than guessing a cause.
   */
  private static String wireSkipReason(io.justsearch.reranker.RerankSkipCause cause) {
    return switch (cause) {
      case TOKENIZE_BUDGET_EXCEEDED, PREP_BUDGET_EXCEEDED -> "DEADLINE_EXCEEDED";
      case INFERENCE_FAILED -> "INFERENCE_FAILED";
      case NONE -> "";
    };
  }

  public SuggestResponse suggest(SuggestRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
      String queryPrefix = request.getQuery();
      int limit = request.getLimit();

      if (queryPrefix.isBlank()) {
        return SuggestResponse.newBuilder().build();
      }

      // Clamp limit
      if (limit <= 0) {
        limit = 5;
      } else if (limit > 20) {
        limit = 20;
      }

      log.debug("Suggest request: prefix='{}', limit={}", queryPrefix, limit);

      try {
        // Ensure index is refreshed for near-real-time results
        commitOps.maybeRefresh();

        // Get suggestions from the Lucene index
        List<String> suggestions = suggestOps.suggest(queryPrefix, limit);

        // Build response
        SuggestResponse.Builder responseBuilder = SuggestResponse.newBuilder();
        responseBuilder.addAllSuggestions(suggestions);

        log.debug("Suggest completed: {} suggestions for prefix '{}'", suggestions.size(), queryPrefix);

        return responseBuilder.build();

      } catch (RuntimeException e) {
        log.error("Suggest failed", e);
        throw WorkerServiceException.internal("Suggest failed: " + e.getMessage());
      }
    }
  }

  /**
   * Fetches document content by ID.
   *
   * <p>This method allows the Main process to retrieve document content without
   * directly accessing the Lucene index, avoiding MMapDirectory/write.lock conflicts.
   *
   * @param request contains list of document IDs to fetch
   * @param ctx the per-call context
   * @return the fetched documents
   */
  public FetchDocumentsResponse fetchDocuments(FetchDocumentsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    log.debug("FetchDocuments request: {} doc_ids", request.getDocIdsCount());

    try {
      // Ensure index is refreshed for latest data
      commitOps.maybeRefresh();

      FetchDocumentsResponse.Builder response = FetchDocumentsResponse.newBuilder();

      for (String docId : request.getDocIdsList()) {
        // Normalize docId to match indexed format (lowercase on Windows)
        String normalizedDocId = PathNormalizer.normalizePath(docId);

        DocumentContent.Builder doc = DocumentContent.newBuilder()
            .setDocId(docId);

        try {
          String content = documentFieldOps.getDocumentContent(normalizedDocId);
          if (content != null) {
            doc.setContent(ParseUtils.trimToLength(content, MAX_CONTENT_CHARS));
            doc.setFound(true);

            // Add common metadata fields if available
            String title = documentFieldOps.getDocumentField(normalizedDocId, "title");
            if (title != null && !title.isBlank()) {
              doc.putMetadata("title", title);
            }
            String path = documentFieldOps.getDocumentField(normalizedDocId, "path");
            if (path != null && !path.isBlank()) {
              doc.putMetadata("path", path);
            }
            String mime = documentFieldOps.getDocumentField(normalizedDocId, "mime");
            if (mime != null && !mime.isBlank()) {
              doc.putMetadata("mime", mime);
            }
          } else {
            doc.setFound(false);
            doc.setError("Document not found in index");
          }
        } catch (Exception e) {
          log.warn("Failed to fetch document {}", docId, e);
          doc.setFound(false);
          doc.setError(e.getMessage() != null ? e.getMessage() : "Unknown error");
        }

        response.addDocuments(doc.build());
      }

      log.debug("FetchDocuments completed: {} documents", response.getDocumentsCount());
      return response.build();

    } catch (RuntimeException e) {
      log.error("FetchDocuments failed", e);
      throw WorkerServiceException.internal("FetchDocuments failed: " + e.getMessage());
    }
    }
  }

  /**
   * Fetches a slice of document content by ID.
   *
   * <p>This endpoint is used for paging through extracted/indexed text (preview and full-coverage summarization)
   * without relying on the fixed-size trimming applied by {@link #fetchDocuments(FetchDocumentsRequest, CallContext)}.
   */
  public FetchDocumentSliceResponse fetchDocumentSlice(
      FetchDocumentSliceRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    String docId = request.getDocId();
    int offsetChars = Math.max(0, request.getOffsetChars());
    int maxChars = request.getMaxChars() <= 0 ? DEFAULT_SLICE_CHARS : request.getMaxChars();
    maxChars = Math.min(maxChars, MAX_SLICE_CHARS);

    if (docId.isBlank()) {
      throw WorkerServiceException.invalidArgument("doc_id is required");
    }

    // Normalize docId to match indexed format (lowercase on Windows)
    String normalizedDocId = PathNormalizer.normalizePath(docId);

    log.debug("FetchDocumentSlice request: doc_id={}, normalizedDocId={}, offsetChars={}, maxChars={}", docId, normalizedDocId, offsetChars, maxChars);

    try {
      commitOps.maybeRefresh();

      FetchDocumentSliceResponse.Builder response =
          FetchDocumentSliceResponse.newBuilder().setDocId(docId);

      // Content and provenance must describe one revision, including during VDU replacement.
      Map<String, String> sliceFields = documentFieldOps.getDocumentFieldsBatch(
          List.of(normalizedDocId),
          Set.of(
              SchemaFields.CONTENT, SchemaFields.CONTENT_SHA256, SchemaFields.TITLE,
              SchemaFields.PATH, SchemaFields.MIME, SchemaFields.EXTRACTION_METHOD,
              SchemaFields.EXTRACTION_REASON_CODE, SchemaFields.EXTRACTION_STATUS,
              SchemaFields.CONTENT_TRUNCATED, SchemaFields.EXTRACTION_POLICY_ID,
              SchemaFields.EXTRACTION_PARSER_ID, SchemaFields.SOURCE_SHA256,
              SchemaFields.VDU_STATUS, SchemaFields.VDU_PROCESSED, SchemaFields.VDU_PAGE_COUNT,
              SchemaFields.VDU_ENRICHMENT, SchemaFields.VISUAL_EXTRACTION_EVIDENCE))
          .getOrDefault(normalizedDocId, Map.of());
      String content = sliceFields.get(SchemaFields.CONTENT);
      if (content == null) {
        response.setFound(false).setError("Document not found in index");
        return response.build();
      }

      int totalLen = content.length();
      int start = Math.min(offsetChars, totalLen);
      if (start > 0
          && start < totalLen
          && Character.isHighSurrogate(content.charAt(start - 1))
          && Character.isLowSurrogate(content.charAt(start))) {
        throw WorkerServiceException.invalidArgument(
            "offset_chars splits a Unicode surrogate pair");
      }
      int end = (int) Math.min((long) start + maxChars, totalLen);
      if (end > start
          && end < totalLen
          && Character.isHighSurrogate(content.charAt(end - 1))
          && Character.isLowSurrogate(content.charAt(end))) {
        end++;
      }

      String slice = start >= end ? "" : content.substring(start, end);
      boolean truncated = end < totalLen;

      response.setFound(true);
      response.setContent(slice);
      response.setTruncated(truncated);
      response.setNextOffsetChars(end);
      // Tempdoc 878: the caller cannot choose between paging and sampling without a denominator,
      // and the Worker is the only place that knows one. It was already computed above.
      response.setTotalChars(totalLen);
      String contentSha256 = sliceFields.get(SchemaFields.CONTENT_SHA256);
      if (contentSha256 != null && !contentSha256.isBlank()) {
        response.putMetadata(SchemaFields.CONTENT_SHA256, contentSha256);
      }

      // Add common metadata fields if available
      String title = sliceFields.get("title");
      if (title != null && !title.isBlank()) {
        response.putMetadata("title", title);
      }
      String path = sliceFields.get("path");
      if (path != null && !path.isBlank()) {
        response.putMetadata("path", path);
      }
      String mime = sliceFields.get("mime");
      if (mime != null && !mime.isBlank()) {
        response.putMetadata("mime", mime);
      }
      String extractionMethod =
          sliceFields.get(SchemaFields.EXTRACTION_METHOD);
      if (extractionMethod != null && !extractionMethod.isBlank()) {
        response.putMetadata("extraction_method", extractionMethod);
      }
      // Tempdoc 790: an extraction dropout is an honest hole only if it is visible. The reason code
      // is what distinguishes "this document has no text because there is none" from "no tier could
      // read this document" — surface it next to the method that produced (or failed to produce) it.
      String extractionReasonCode =
          sliceFields.get(SchemaFields.EXTRACTION_REASON_CODE);
      if (extractionReasonCode != null && !extractionReasonCode.isBlank()) {
        response.putMetadata("extraction_reason_code", extractionReasonCode);
      }
      String extractionStatus =
          sliceFields.get(SchemaFields.EXTRACTION_STATUS);
      if (extractionStatus != null && !extractionStatus.isBlank()) {
        response.setExtractionStatus(extractionStatus);
        response.putMetadata("extraction_status", extractionStatus);
      }
      String contentTruncated =
          sliceFields.get(SchemaFields.CONTENT_TRUNCATED);
      if (contentTruncated != null && !contentTruncated.isBlank()) {
        // Boolean fields project from Lucene numeric doc values as 1/0; accept the textual form too
        // so the response remains correct if a stored-field fallback supplies true/false.
        if ("1".equals(contentTruncated) || "true".equalsIgnoreCase(contentTruncated)) {
          response.setContentTruncated(true);
        } else if ("0".equals(contentTruncated) || "false".equalsIgnoreCase(contentTruncated)) {
          response.setContentTruncated(false);
        }
        response.putMetadata("content_truncated", contentTruncated);
      }
      String extractionPolicyId =
          sliceFields.get(SchemaFields.EXTRACTION_POLICY_ID);
      if (extractionPolicyId != null && !extractionPolicyId.isBlank()) {
        response.setExtractionPolicyId(extractionPolicyId);
        response.putMetadata("extraction_policy_id", extractionPolicyId);
      }
      String extractionParserId =
          sliceFields.get(SchemaFields.EXTRACTION_PARSER_ID);
      if (extractionParserId != null && !extractionParserId.isBlank()) {
        response.setExtractionParserId(extractionParserId);
        response.putMetadata("extraction_parser_id", extractionParserId);
      }
      String sourceSha256 =
          sliceFields.get(SchemaFields.SOURCE_SHA256);
      if (sourceSha256 != null && !sourceSha256.isBlank()) {
        response.setSourceSha256(sourceSha256);
        response.putMetadata("source_sha256", sourceSha256);
      }

      // Add VDU-related metadata for provenance tracking
      String vduStatus = sliceFields.get(SchemaFields.VDU_STATUS);
      if (vduStatus != null && !vduStatus.isBlank()) {
        response.putMetadata("vdu_status", vduStatus);
      }
      String vduProcessed = sliceFields.get(SchemaFields.VDU_PROCESSED);
      if (vduProcessed != null && !vduProcessed.isBlank()) {
        response.putMetadata("vdu_processed", vduProcessed);
      }
      String vduPageCount = sliceFields.get(SchemaFields.VDU_PAGE_COUNT);
      if (vduPageCount != null && !vduPageCount.isBlank()) {
        response.putMetadata("vdu_page_count", vduPageCount);
      }
      String vduEnrichment = sliceFields.get(SchemaFields.VDU_ENRICHMENT);
      if (vduEnrichment != null && !vduEnrichment.isBlank()) {
        response.putMetadata("vdu_enrichment", vduEnrichment);
      }
      String visualEvidence =
          sliceFields.get(SchemaFields.VISUAL_EXTRACTION_EVIDENCE);
      if (visualEvidence != null && !visualEvidence.isBlank()) {
        response.putMetadata("visual_extraction_evidence", visualEvidence);
      }

      return response.build();

    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: the surrogate-pair check above throws
      // INVALID_ARGUMENT from inside this try, and a catch-all would silently degrade it to
      // INTERNAL (the wire status the Head sees would change).
      throw e;
    } catch (RuntimeException e) {
      log.error("FetchDocumentSlice failed", e);
      throw WorkerServiceException.internal("FetchDocumentSlice failed: " + e.getMessage());
    }
    }
  }

  /**
   * Retrieves relevant context for Q&A using RAG (chunk search) or full document search.
   *
   * <p>First searches for relevant chunks using BM25 on the chunk_content field.
   * If no chunks are found, falls back to full document content search.
   *
   * @param request contains the question, document IDs, and top-K parameter
   * @param ctx the per-call context
   * @return the retrieved context
   */
  public RetrieveContextResponse retrieveContext(
      RetrieveContextRequest request, CallContext ctx) {
    awaitModelsReady("retrieveContext");
    try (var ignored = openRequestMdc(ctx)) {
      String question = request.getQuestion();
      List<String> docIds = request.getDocIdsList();
      int topK = request.getTopK() <= 0 ? 5 : Math.min(request.getTopK(), 20);
      int maxContextTokens = Math.max(0, request.getMaxContextTokens());

      log.debug("RetrieveContext request: question='{}', docIds={}, topK={}, maxTokens={}, "
          + "entityPersons={}, pathPrefix='{}', autoEntityExtract={}",
          question, docIds.size(), topK, maxContextTokens,
          request.getEntityPersonsCount(), request.getPathPrefix(),
          request.getAutoEntityExtract());

      // Allow empty doc_ids for open retrieval (filters or unscoped search).
      // Only reject if question is blank.
      if (question.isBlank()) {
        return RetrieveContextResponse.newBuilder()
            .setContext("")
            .setUsedChunks(false)
            .setChunksFound(0)
            .setRetrievalMode("")
            .setRetrievalModeReason("EMPTY_REQUEST")
            .setContextTruncated(false)
            .setQuality(io.justsearch.ipc.QualitySignals.getDefaultInstance())
            .build();
      }

      try {
        return ragContextOps.executeRetrieval(
            request, new HashSet<>(docIds), topK, maxContextTokens,
            embeddingCompat().allowed());
      } catch (RuntimeException e) {
        log.error("RetrieveContext failed", e);
        throw WorkerServiceException.internal("RetrieveContext failed: " + e.getMessage());
      }
    }
  }

  // ==================== Post-hoc Citation Matching ====================

  public io.justsearch.ipc.MatchCitationsResponse matchCitations(
      io.justsearch.ipc.MatchCitationsRequest request, CallContext ctx) {
    awaitModelsReady("matchCitations");
    try (var ignored = openRequestMdc(ctx)) {
      // Tempdoc 836 §1.4 — a passage_texts length that is neither 0 nor sources.size() is a caller
      // bug. It must fail loudly, never be absorbed by a Math.min: a silently-shortened passage
      // list would mis-align text to sources, which is the F-049 mis-targeting class re-opened
      // through the back door.
      int sourceCount = request.getChunkDocIdsCount();
      int passageCount = request.getPassageTextsCount();
      if (passageCount != 0 && passageCount != sourceCount) {
        throw WorkerServiceException.invalidArgument(
            "passage_texts must be empty or exactly chunk_doc_ids.size() ("
                + sourceCount
                + "), got "
                + passageCount);
      }
      try {
        double threshold = request.getSimilarityThreshold() > 0
            ? request.getSimilarityThreshold()
            : CitationMatchOps.DEFAULT_SIMILARITY_THRESHOLD;
        return citationMatchOps.execute(
            request.getAnswerText(),
            request.getChunkDocIdsList(),
            request.getChunkIndicesList(),
            request.getPassageTextsList(),
            threshold);
      } catch (RuntimeException e) {
        log.error("MatchCitations failed", e);
        throw WorkerServiceException.internal("MatchCitations failed: " + e.getMessage());
      }
    }
  }

  // ==================== Folder Browse ====================

  public ListFoldersResponse listFolders(ListFoldersRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    String parentPath = request.getParentPath();
    if (parentPath.isBlank()) {
      throw WorkerServiceException.invalidArgument("parent_path is required");
    }

    try {
      commitOps.maybeRefresh();
      FolderBrowseResult result =
          folderBrowseEngine.enumerateFolders(parentPath, request.getMaxFolders());

      ListFoldersResponse.Builder response = ListFoldersResponse.newBuilder();
      for (FolderInfo folder : result.folders()) {
        response.addFolders(
            FolderEntry.newBuilder()
                .setPath(folder.path())
                .setName(folder.name())
                .setFileCount(folder.fileCount())
                .setTotalSizeBytes(folder.totalSizeBytes())
                .setLastIndexedAt(folder.lastIndexedAt())
                .build());
      }
      response.setTookMs(result.tookMs());
      response.setTruncated(result.truncated());

      return response.build();
    } catch (IllegalArgumentException e) {
      throw WorkerServiceException.invalidArgument(e.getMessage());
    } catch (RuntimeException e) {
      log.error("ListFolders failed for path={}", parentPath, e);
      throw WorkerServiceException.internal("ListFolders failed: " + e.getMessage());
    }
    }
  }

  public ListFolderFilesResponse listFolderFiles(
      ListFolderFilesRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    String folderPath = request.getFolderPath();
    if (folderPath.isBlank()) {
      throw WorkerServiceException.invalidArgument("folder_path is required");
    }

    try {
      commitOps.maybeRefresh();
      FolderFilesResult result =
          folderBrowseEngine.listFolderFiles(
              folderPath, request.getLimit(), new HashSet<>(request.getProjectionList()));

      ListFolderFilesResponse.Builder response = ListFolderFilesResponse.newBuilder();
      for (SearchHit hit : result.files()) {
        response.addFiles(
            FolderFileEntry.newBuilder()
                .setDocId(hit.docId())
                .putAllFields(hit.fields())
                .build());
      }
      response.setTotalCount(result.totalCount());
      response.setTookMs(result.tookMs());

      return response.build();
    } catch (IllegalArgumentException e) {
      throw WorkerServiceException.invalidArgument(e.getMessage());
    } catch (RuntimeException e) {
      log.error("ListFolderFiles failed for path={}", folderPath, e);
      throw WorkerServiceException.internal("ListFolderFiles failed: " + e.getMessage());
    }
    }
  }

  public ListAllDocumentIdsResponse listAllDocumentIds(
      ListAllDocumentIdsRequest request, CallContext ctx) {
    try (var ignored = openRequestMdc(ctx)) {
    try {
      Long expectedReaderVersion = parseDocumentIdSnapshot(request);
      commitOps.maybeRefresh();
      ListAllDocumentIdsResult result =
          folderBrowseEngine.listAllDocumentIds(
              request.getOffset(), request.getLimit(), expectedReaderVersion);

      return ListAllDocumentIdsResponse.newBuilder()
          .addAllDocIds(result.docIds())
          .setTotalCount(result.totalCount())
          .setTookMs(result.tookMs())
          .setSnapshotToken(documentIdSnapshotEpoch + ":" + result.readerVersion())
          .build();
    } catch (WorkerServiceException e) {
      // Must precede the RuntimeException catch-all: parseDocumentIdSnapshot throws
      // INVALID_ARGUMENT / ABORTED from inside this try, and a catch-all would silently
      // degrade those to INTERNAL (the wire status the Head sees would change).
      throw e;
    } catch (FolderBrowseEngine.StaleDocumentIdSnapshotException e) {
      throw WorkerServiceException.aborted(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw WorkerServiceException.invalidArgument(e.getMessage());
    } catch (RuntimeException e) {
      log.error("ListAllDocumentIds failed", e);
      throw WorkerServiceException.internal("ListAllDocumentIds failed: " + e.getMessage());
    }
    }
  }

  private Long parseDocumentIdSnapshot(ListAllDocumentIdsRequest request) {
    String token = request.getSnapshotToken();
    if (request.getOffset() == 0) {
      if (!token.isEmpty()) {
        throw WorkerServiceException.invalidArgument(
            "snapshot_token must be empty for the first page");
      }
      return null;
    }
    if (request.getOffset() < 0) {
      throw WorkerServiceException.invalidArgument("offset must be non-negative");
    }
    if (token.isEmpty()) {
      throw WorkerServiceException.invalidArgument(
          "snapshot_token is required when offset is non-zero");
    }

    int separator = token.lastIndexOf(':');
    if (separator <= 0 || separator == token.length() - 1) {
      throw WorkerServiceException.invalidArgument("snapshot_token is malformed");
    }
    long readerVersion;
    try {
      readerVersion = Long.parseLong(token.substring(separator + 1));
    } catch (NumberFormatException e) {
      throw WorkerServiceException.invalidArgument("snapshot_token is malformed");
    }
    if (!documentIdSnapshotEpoch.equals(token.substring(0, separator))) {
      throw WorkerServiceException.aborted(
          "Document ID snapshot belongs to a different Worker instance");
    }
    return readerVersion;
  }

  /**
   * Strips the quoted user query out of an error message before it reaches a server-side log.
   *
   * <p>Lucene's {@code ParseException} renders as {@code Cannot parse '<query>': Encountered ...},
   * so logging the raw message writes the user's search text into engine.log — which the
   * diagnostics export bundles with path-only redaction. Everything between the first and last
   * quote is replaced (over-redacting is the safe direction) and the result is length-capped; the
   * diagnostic shape — which parser rejected it, and where — survives.
   */
  static String withoutQuotedQuery(String message) {
    if (message == null || message.isBlank()) {
      return "(no message)";
    }
    int first = message.indexOf('\'');
    int last = message.lastIndexOf('\'');
    String out =
        (first >= 0 && last > first)
            ? message.substring(0, first + 1) + "[REDACTED]" + message.substring(last)
            : message;
    return out.length() > 200 ? out.substring(0, 200) + "..." : out;
  }
}
