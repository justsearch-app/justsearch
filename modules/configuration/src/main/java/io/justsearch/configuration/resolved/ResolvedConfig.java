/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable snapshot of all resolved configuration.
 *
 * <p>Built at startup by {@link ResolvedConfigBuilder}, replacing all {@code System.getProperty}
 * reads and {@code EnvRegistry.get()} calls with typed accessor methods. When the user changes
 * settings at runtime (via the GUI), a new snapshot is built and atomically swapped in via {@code
 * ConfigStore}.
 *
 * <p>The resolution uses numeric ordinals (modeled on SmallRye): each source has a fixed ordinal,
 * higher ordinal wins. Every resolved value carries a {@link ConfigResolution} trace recording which
 * source won and all sources considered.
 *
 * <p>Sub-records group related configuration:
 *
 * <ul>
 *   <li>{@link Paths} — file system paths (data dir, index path, home, models, SSOT, repo root)
 *   <li>{@link Ports} — network ports (API, AI worker, llama-server)
 *   <li>{@link Ai} — AI/inference feature flags, GPU layers, model paths
 *   <li>{@link Agent} — agent tool configuration (limits, compression)
 *   <li>{@link Summary} — summary pipeline configuration
 *   <li>{@link Search} — search pipeline configuration
 *   <li>{@link Telemetry} — telemetry flush and retention settings
 *   <li>{@link Policy} — enterprise policy flags
 *   <li>{@link Ui} — UI settings mode and automation flags
 * </ul>
 *
 * @see ResolvedConfigBuilder
 */
public record ResolvedConfig(
    Paths paths,
    Ports ports,
    Ai ai,
    Agent agent,
    Summary summary,
    Search search,
    Telemetry telemetry,
    Policy policy,
    Ui ui,
    Watcher watcher,
    Ocr ocr,
    Index index,
    Rag rag,
    HybridSearch hybridSearch,
    Worker worker,
    Collections collections,
    WorkerIndexer workerIndexer,
    InfraHealth infraHealth,
    Map<String, ConfigResolution> resolutions) {

  public ResolvedConfig {
    Objects.requireNonNull(paths, "paths");
    Objects.requireNonNull(ports, "ports");
    Objects.requireNonNull(ai, "ai");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(search, "search");
    Objects.requireNonNull(telemetry, "telemetry");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(ui, "ui");
    Objects.requireNonNull(watcher, "watcher");
    Objects.requireNonNull(ocr, "ocr");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(rag, "rag");
    Objects.requireNonNull(hybridSearch, "hybridSearch");
    Objects.requireNonNull(worker, "worker");
    Objects.requireNonNull(collections, "collections");
    Objects.requireNonNull(workerIndexer, "workerIndexer");
    Objects.requireNonNull(infraHealth, "infraHealth");
    resolutions = Map.copyOf(resolutions);
  }

  /** Returns the {@link ConfigResolution} for a given key, or null if not tracked. */
  public ConfigResolution resolution(String key) {
    return resolutions.get(key);
  }

  /** Creates a new builder for constructing a {@link ResolvedConfig}. */
  public static ResolvedConfigBuilder builder() {
    return new ResolvedConfigBuilder();
  }

  // ==================== Sub-records ====================

  /**
   * File system paths resolved from env vars, sysprops, YAML, and platform defaults.
   *
   * @param dataDir root data directory for all JustSearch artifacts
   * @param indexBasePath base path for index storage (derived from dataDir if not explicit)
   * @param home JustSearch home directory
   * @param modelsDir directory for AI model files
   * @param ssotPath path to SSOT directory
   * @param repoRoot repository root path (for dev/test)
   */
  public record Paths(
      Path dataDir,
      Path indexBasePath,
      Path home,
      Path modelsDir,
      Path ssotPath,
      Path repoRoot,
      Path ortNativePath) {}

  /**
   * Network ports for API and inference services.
   *
   * @param apiPort local API server port (default 8080)
   * @param serverPort llama-server HTTP port
   */
  public record Ports(int apiPort, int serverPort) {}

  /**
   * AI and inference feature configuration — model paths, GPU layers, feature flags, and VRAM
   * thresholds.
   *
   * <p>This is the record the inference layer actually reads ({@code InferenceConfig},
   * {@code LlamaServerOps}). A parallel {@code Llm} record once claimed to hold "LLM runtime
   * tuning" and was consumed by nothing — its ten components resolved, were documented, and were
   * read by no production code. Retired by tempdoc 799 §R; sampling parameters are set per purpose
   * in {@code SamplingParams} and per call, not by a global knob.
   */
  public record Ai(
      Path serverExe,
      int gpuLayers,
      Path llmModelPath,
      boolean disabled,
      boolean llmEnabled,
      int contextSize,
      String vlmModel,
      String mmprojModel,
      // Tempdoc 842: the named (model, mmproj) bundle the llama-server engine starts with —
      // "standard" (default) or "compact". Resolve via ChatModelProfile; the explicit
      // llmModelPath override still wins.
      String chatProfile,
      boolean useThinking,
      int reasoningBudget,
      String onnxruntimeVariantId,
      String serverExeSource,
      long vramThreshold12gb,
      long vramThreshold8gb,
      long vramThreshold4gb,
      double gplReevalSizeFactor,
      Embedding embedding,
      Splade splade,
      Ner ner,
      Reranker reranker,
      CitationScorer citationScorer,
      BgeM3 bgeM3,
      Profiling profiling,
      String sparseModel,
      boolean devHotReload,
      BackfillPacing backfillPacing,
      // Tempdoc 710 Wave 2 Move 1: undeclared model-capability facts fail startup for that
      // encoder lane instead of WARN + fallback (default false until 657 ships manifests in
      // packs — see EnvRegistry.CAPABILITY_CONTRACT_STRICT).
      boolean capabilityContractStrict,
      // Tempdoc 883 decision 2 (append region — keep new components last so parallel lanes merge
      // trivially): llama-server slot count (-np) and KV cache type (-ctk/-ctv). Both are launch
      // arguments the engine used to choose for itself; naming them makes the choice reviewable
      // and the argv reproducible.
      int llmSlots,
      String llmKvType) {

    /** BGE-M3 multi-vector retrieval configuration. */
    public record BgeM3(
        Boolean enabled,
        Path modelPath,
        int maxSeqLen,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb) {}

    public record Embedding(
        Boolean enabled,
        String backend,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        int contextLength,
        // Tempdoc 691 Phase 1: late-chunking embed pass (single forward pass for a chunked
        // parent + its chunk docs) — default off.
        boolean lateChunkingEnabled,
        // Tempdoc 691 Phase 2: single-pass whole-doc VECTOR limit for the late-chunking path
        // (independent of contextLength — the base batch path OOMs at this length; the
        // late-chunking path is batch-1 by construction). Default 8192, clamped to
        // [contextLength, 8192].
        int lateChunkingContextLength) {}

    public record Splade(
        Boolean enabled,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        Path modelPath,
        int maxSeqLen,
        String queryMode,
        String activation,
        Path evidencePath) {}

    public record Ner(
        Boolean enabled,
        Path modelPath,
        int maxSeqLen,
        double confidenceThreshold,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb) {}

    public record Reranker(
        Boolean enabled,
        Path modelPath,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        int topK,
        int deadlineMs,
        int minHits,
        int maxSeqLen,
        int maxAvgDocLengthChars,
        // Tempdoc 643: judge-stage refinement floor (blend CE reorder with fusion order).
        boolean judgeBlendEnabled,
        double judgeBlendAlpha,
        // Tempdoc 643 (E1/E2): per-query confidence-driven arbitration + perf-skip.
        boolean judgeArbitrationEnabled,
        double judgeArbitrationAlphaDiverge,
        boolean judgeArbitrationSkipEnabled,
        ChunkReranker chunks) {

      public record ChunkReranker(
          Boolean enabled,
          Path modelPath,
          boolean gpuEnabled,
          int gpuDeviceId,
          int topK,
          int maxGpuCandidates,
          int deadlineMs,
          int minHits,
          int maxSeqLen,
          String order) {}
    }

    public record CitationScorer(
        Boolean enabled, Path modelPath, double threshold, int maxSeqLen, int deadlineMs) {}

    /**
     * ORT diagnostic-observability knobs (tempdoc 397 §14.24 FB).
     *
     * <p>Both are process-wide and policy-typed — they flow through the same resolver chain
     * as every other runtime option, so {@code /api/debug/session-policies} reflects them and
     * the {@link io.justsearch.ort.SessionOptionsApplier} reads them via
     * {@code runtime.profiling()} instead of {@code System.getenv}.
     *
     * @param ortProfilingDir directory for per-session profile files; null = disabled
     * @param verboseLogging enables ORT VERBOSE-level session logging
     * @param intraOpThreads fixed ONNX Runtime intra-op thread count; null (the default) leaves
     *     ORT's own hardware-derived choice, i.e. today's behaviour. Lane F PR 0b: on the CPU
     *     execution provider the intra-op count decides how a GEMM's reduction is partitioned, so
     *     it decides the summation ORDER and therefore an embedding's low bits. Pinned for
     *     deterministic captures so bit-stability does not depend on the host's core count.
     */
    public record Profiling(Path ortProfilingDir, boolean verboseLogging, Integer intraOpThreads) {

      /** Back-compat constructor (pre-lane-F-PR-0b): no intra-op pin. */
      public Profiling(Path ortProfilingDir, boolean verboseLogging) {
        this(ortProfilingDir, verboseLogging, null);
      }
    }

    /**
     * Enrichment-backfill pacing knobs (tempdoc 710 Wave-1.5 Move 4). Previously bare literals in
     * {@code LoopPacingPolicy} / {@code BackfillScheduler} / {@code
     * CombinedEnrichmentBackfillOps} with zero config surface; converted 1:1 to {@code
     * justsearch.backfill.*} keys with identical defaults so behavior is unchanged unless an
     * operator explicitly overrides one for experimentation.
     *
     * @param pollBatchSize primary-indexing job-queue poll batch size. Raised from 1 to 16 in
     *     tempdoc 278 Phase 1 item 1b to amortize per-batch queue overhead (paired with item 1a's
     *     per-document responsiveness check — since tempdoc 885 item 3 that is the per-file
     *     {@code IndexingPacing.pace()} tick, not the retired {@code isUserActive()} gate).
     * @param embeddingBackfillBatchSize doc-count per embedding backfill batch (parent docs and,
     *     when {@code chunkVectorsEnabled}, the chunk cache populated by the same batch size).
     * @param nerBackfillBatchSize doc-count per NER backfill batch.
     * @param disambiguationBackfillBatchSize doc-count per disambiguation backfill batch.
     * @param spladeBackfillBatchSize doc-count per idle-branch SPLADE backfill batch.
     * @param spladeInterleaveBatchSize doc-count per SPLADE batch interleaved into the primary
     *     indexing branch (tempdoc 278 Phase 4c — smaller than the idle-branch batch so
     *     interleaving stays cheap).
     * @param spladeInterleaveIntervalMs minimum time between interleaved SPLADE/BGE-M3 batches
     *     during primary indexing (tempdoc 278 Phase 4a — time-gated to limit primary-indexing
     *     overhead to ~13%).
     * @param commitIntervalMs time-based commit trigger: commit if this much time has elapsed
     *     since the last commit and at least one document is pending.
     * @param maxDocsBeforeCommit buffer-based commit trigger: commit once this many documents have
     *     been indexed since the last commit, regardless of elapsed time.
     * @param chunkSlotsPerBatch chunk-doc cache slots populated per combined-backfill batch
     *     (formerly the bare {@code chunkSlotsPerBatch = 50} literal in {@code
     *     CombinedEnrichmentBackfillOps}). Tempdoc 691 §F-1 measured this cap is NOT the
     *     dense-corpus chunk-only-tail throughput lever — that tail is GPU-embedding-compute-bound
     *     (82% ORT time at the compute floor), not cap-throttled — so this exists as a config
     *     surface for experimentation, not because raising it is known to help.
     * @param bgeM3BackfillBatchSize doc-count per idle-branch BGE-M3 backfill batch (BGE-M3's own
     *     pacing constant — previously bypassed {@code LoopPacingPolicy} entirely as a stray
     *     literal in {@code BackfillScheduler}; unified onto this record).
     * @param bgeM3InterleaveBatchSize doc-count per BGE-M3 batch interleaved into the primary
     *     indexing branch (BGE-M3's counterpart to {@code spladeInterleaveBatchSize}).
     * @param foregroundDutyPct minimum share of wall time (1..100) that indexing and backfill keep
     *     while foreground search-family RPCs are in flight (tempdoc 885 item 3,
     *     {@code justsearch.indexing.foreground_duty_pct}, default 20). This is a duty cycle, not a
     *     pause: the pre-885 behaviour was a full stop, which starved indexing to zero under a
     *     continuous search loop. 100 disables throttling.
     * @param foregroundCooldownMs how long after the last foreground completion the Worker still
     *     counts as contended ({@code justsearch.indexing.foreground_cooldown_ms}, default 500), so
     *     a burst of short queries does not read as idle in the gaps between them.
     */
    public record BackfillPacing(
        int pollBatchSize,
        int embeddingBackfillBatchSize,
        int nerBackfillBatchSize,
        int disambiguationBackfillBatchSize,
        int spladeBackfillBatchSize,
        int spladeInterleaveBatchSize,
        long spladeInterleaveIntervalMs,
        long commitIntervalMs,
        int maxDocsBeforeCommit,
        int chunkSlotsPerBatch,
        int bgeM3BackfillBatchSize,
        int bgeM3InterleaveBatchSize,
        int foregroundDutyPct,
        long foregroundCooldownMs) {

      /**
       * The historical hardcoded values, used as a defensive fallback when no {@link
       * ResolvedConfig} is available (e.g. a test double supplying a null config supplier).
       * Identical to every field's {@code justsearch.backfill.*} / {@code justsearch.indexing.*}
       * default in {@link ResolvedConfigBuilder} — kept in sync by construction since both
       * originate from the same pre-Move-4 literals.
       */
      public static final BackfillPacing DEFAULTS =
          new BackfillPacing(16, 100, 100, 500, 200, 10, 5_000L, 10_000L, 1000, 50, 50, 10, 20, 500L);
    }
  }

  /** Agent tool configuration — search/browse limits, context compression. */
  public record Agent(
      int searchDefaultLimit,
      String searchDefaultMode,
      int browseDefaultMaxFolders,
      int maxToolResultChars,
      int maxCompletionTokens,
      boolean contextCompressionEnabled,
      int contextCompressionMinChars,
      int contextCompressionKeepLastResults) {}

  /** Summary pipeline configuration. */
  public record Summary(String pipeline, int maxTokens) {}

  /**
   * Search pipeline configuration.
   *
   * @param profile search pipeline profile name
   * @param pipeline search pipeline definition file path
   * @param collection primary index collection name
   * @param queryClassificationEnabled 306: enable query classification for CE/expansion gating
   * @param titleBoost 306: title field boost in DisjunctionMaxQuery (0 to disable)
   * @param evidenceSpanEnabled 775: enable answer-bearing EvidenceSpan-backed excerpt selection
   *     (default TRUE since the 775 §I flip, 2026-07-22; flag-off reproduces the IDF-only delivery
   *     excerpt byte-for-byte)
   * @param evidenceSpanEntitySignal 775: the distinguishing-entity signal used by the EvidenceSpan
   *     selector — {@code df_rarity} or {@code ner_membership}
   * @param mcpDeliveryBudgetBytes 775: the MCP delivery governor's serialized-JSON budget in bytes
   *     (default {@link Search#DEFAULT_MCP_DELIVERY_BUDGET_BYTES}; 0 disables the governor)
   * @param mcpFraming 789 Phase 2: the agent-delivery framing flags (all default OFF)
   * @param mcpEntityCarriage 771 item (b): the MCP entity-carriage settings (default OFF)
   */
  public record Search(
      String profile,
      String pipeline,
      String collection,
      boolean queryClassificationEnabled,
      double titleBoost,
      boolean chunkAwareEnabled,
      // Tempdoc 774 Stage 2 — when true, chunk-sourced hits emit the winning chunk's text as
      // content_preview (evidence-coherent CE input + delivery). Default TRUE since the 775 §I flip
      // (2026-07-22, founder decision / F-041); flag-off is byte-equivalent to pre-774 (chunk text
      // is never emitted). §F.1-5.
      boolean evidencePreviewEnabled,
      boolean lambdamartEnabled,
      boolean evidenceSpanEnabled,
      String evidenceSpanEntitySignal,
      // Tempdoc 775 §E/§C: the MCP delivery governor's serialized-JSON budget in bytes. The assembled
      // justsearch_search payload is degraded deterministically (numeric provenance first, then whole
      // tail results, never mid-payload) to fit this budget before delivery — a margin under the
      // lowest characterized 770 §E.3 client truncation cliff (46,617). 0 disables the governor.
      int mcpDeliveryBudgetBytes,
      // Tempdoc 789 Phase 2: the agent-delivery framing flags. Probe substrate for the 782 hero
      // campaign's delivery-shape finding (register F-043) — the MCP response shape terminates
      // agent reasoning at intermediate facts. Each framing is independently selectable and ALL
      // default OFF; the probe runs one framing per arm.
      McpFraming mcpFraming,
      // Tempdoc 771 item (b) / F-039 component (b): entity carriage. On the legal strata the
      // delivered excerpt carried the bridge entity in only ~45% of successful retrievals (vs ~93%
      // on email) because long documents bury the bridge sentence past the 4 KB content_preview
      // window — so even a successful hop-1 retrieval could not seed hop-2. Default OFF.
      EntityCarriage mcpEntityCarriage,
      Corrections corrections) {

    /**
     * Default MCP delivery-governor budget (tempdoc 775 §E, settled by the orchestrator's live
     * measurement 2026-07-22): 45,000 bytes of serialized result JSON — a margin under the lowest
     * characterized 770 §E.3 truncation cliff at 46,617 bytes.
     */
    public static final int DEFAULT_MCP_DELIVERY_BUDGET_BYTES = 45_000;

    /**
     * Tempdoc 789 Phase 2 — the three flag-gated MCP delivery framings. Content-only: no MCP tool
     * schema or parameter changes (F-016: schema complexity measurably hurts agents), no retrieval
     * changes. Framings compose — any subset may be enabled at once.
     *
     * @param continuationEnabled F1: a delivered excerpt naming an indexed entity absent from the
     *     query carries one appended continuation line
     * @param evidenceNotAnswerEnabled F2: search and answer deliveries carry an explicit
     *     retrieval-evidence header instead of reading as answers
     * @param calibratedAbsenceEnabled F3: zero-result and thin-result deliveries carry corpus
     *     coverage, what was searched, and explicit absence-is-not-evidence framing
     * @param thinResultFloorBytes F3: delivered-body byte floor below which a non-empty result set
     *     is still treated as thin (default {@link #DEFAULT_THIN_RESULT_FLOOR_BYTES})
     * @param weakScoreFloor F3: normalized top-relevance floor below which a non-empty result set is
     *     treated as a weak-relevance delivery (default {@link #DEFAULT_WEAK_SCORE_FLOOR}). Only
     *     consulted where the fused score is bounded [0,1] (the {@code cc}/{@code hybrid} fusion
     *     methods). {@code 0} disables the arm: the trigger compares strictly less-than and rendered
     *     scores are never negative, so no delivery can fall under a floor of zero.
     */
    public record McpFraming(
        boolean continuationEnabled,
        boolean evidenceNotAnswerEnabled,
        boolean calibratedAbsenceEnabled,
        int thinResultFloorBytes,
        double weakScoreFloor) {

      /** Every framing off — the shipped default, byte-identical to pre-789 delivery. */
      public static final McpFraming OFF =
          new McpFraming(
              false, false, false, DEFAULT_THIN_RESULT_FLOOR_BYTES, DEFAULT_WEAK_SCORE_FLOOR);
    }

    /**
     * Default delivered-body byte floor for the F3 thin-result trigger (tempdoc 789 Phase 2): 400
     * bytes of rendered hit body. Below this a result set carries so little text that an agent
     * treating it as coverage evidence is the 2x-abstention failure the framing targets.
     */
    public static final int DEFAULT_THIN_RESULT_FLOOR_BYTES = 400;

    /**
     * Tempdoc 771 item (b) — MCP entity carriage. Content-only at the delivery layer: no MCP tool
     * schema or parameter change (F-016), no retrieval or ranking change. When enabled, a delivered
     * {@code justsearch_search} hit whose excerpt does not already name the document's indexed NER
     * entities carries one bounded line listing the missing names, so an agent that needs a bridge
     * entity for a follow-up (hop-2) search is actually handed it.
     *
     * @param enabled 771 item (b): emit the per-hit entity-carriage line (default false)
     * @param maxChars ceiling on the whole rendered carriage line per hit, in characters (default
     *     {@link #DEFAULT_ENTITY_CARRIAGE_MAX_CHARS}); values &lt;= 0 suppress the line
     */
    public record EntityCarriage(boolean enabled, int maxChars) {

      /** Carriage off — the shipped default, byte-identical to pre-771 delivery. */
      public static final EntityCarriage OFF =
          new EntityCarriage(false, DEFAULT_ENTITY_CARRIAGE_MAX_CHARS);
    }

    /**
     * Default per-hit ceiling for the rendered entity-carriage line (tempdoc 771 item (b)): 200
     * characters. Sized against the 775 §E delivery budget — at the 50-hit tool ceiling a fully
     * saturated carriage costs ~10 KB, which the delivery governor degrades deterministically like
     * any other body text rather than cliffing.
     */
    public static final int DEFAULT_ENTITY_CARRIAGE_MAX_CHARS = 200;

    /**
     * Default normalized top-relevance floor for the F3 weak-score trigger (tempdoc 789, post-
     * Amendment-3 redesign): 0.40.
     *
     * <p>Calibrated against the Amendment-3 live measurement, which is also what motivated the arm:
     * the byte signal had no dynamic range (gibberish, rare-phrase and healthy queries all delivered
     * ~1,630-1,725 content bytes, a 6% spread), while the rendered top score did — a gibberish query
     * scored 0.22 and a gold-bearing healthy query scored 1.00. 0.40 sits above the measured weak
     * regime and below both the measured healthy value and the structural landmark at {@code alpha =
     * 0.5} (the default {@code index.hybrid.cc_alpha}), which is the fused score a document topping
     * exactly one normalized leg receives.
     */
    public static final double DEFAULT_WEAK_SCORE_FLOOR = 0.40;

    /** Spelling/fuzzy correction settings. */
    public record Corrections(
        boolean enabled,
        int dfThreshold,
        int maxEditDistance,
        boolean zeroHitRetryEnabled) {}
  }

  /**
   * Telemetry flush and retention settings.
   *
   * @param flushMs telemetry flush interval in milliseconds
   */
  public record Telemetry(long flushMs) {}

  /**
   * Enterprise policy flags.
   *
   * @param egressBlockAll true to block all egress (isolated testing)
   * @param prodMode true if running in production mode
   * @param indexParityAllowMismatch true to allow opening index read-only on schema mismatch
   */
  public record Policy(
      boolean egressBlockAll, boolean prodMode, boolean indexParityAllowMismatch) {}

  /**
   * UI configuration.
   *
   * @param automationEnabled true if UI automation mode is enabled
   * @param forceDiagnostics true to force infra diagnostics overrides
   * @param excludePatterns the user's exclude globs as a raw JSON array string, {@code ""} when
   *     unset — the resolved form of the key {@code ConfigStoreRebuilder.contributeUiSettings}
   *     contributes at ordinal 300 (tempdoc 883 decision 4 slice 2; before it, the key was
   *     contributed but never resolved, so every reader had to go to the promoted sysprop instead)
   */
  public record Ui(
      boolean automationEnabled, boolean forceDiagnostics, String excludePatterns) {}

  /**
   * File-system watcher configuration.
   *
   * <p>Its only component, {@code overflowRescanOnOverflow}, was removed by tempdoc 799 §N.2 —
   * shadowed by the hardcoded {@code force=true} at {@code WorkerMethvinWatcher}, and read by
   * nothing. The record is retained (empty) rather than deleted so the {@code watcher()} slot on
   * {@link ResolvedConfig} stays available for the next real watcher knob.
   */
  public record Watcher() {}

  /**
   * OCR (optical character recognition) pipeline configuration.
   *
   * @param enabled whether OCR is enabled
   * @param languages list of OCR languages
   * @param perFileTimeoutMs per-file OCR timeout in milliseconds
   * @param maxPages maximum pages to process
   * @param maxImageDimension maximum image dimension
   * @param maxImagePixels maximum total image pixels
   * @param renderDpi PDF page render DPI for OCR
   * @param workers OCR worker pool size (0 or absent = auto, derived from available cores)
   */
  public record Ocr(
      Boolean enabled,
      List<String> languages,
      Integer perFileTimeoutMs,
      Integer maxPages,
      Integer maxImageDimension,
      Integer maxImagePixels,
      Integer renderDpi,
      Integer workers) {

    public Ocr {
      languages = languages != null ? List.copyOf(languages) : List.of();
    }
  }

  /**
   * Index writer, commit, NRT, soft-delete, and vector configuration.
   *
   * @param writerRamBufferMb RAM buffer size for IndexWriter
   * @param writerMaxBufferedDocs max buffered docs before flush
   * @param writerMaxQueueDepth max writer queue depth
   * @param commitMetadataEnabled whether commit metadata is enabled
   * @param nrtTargetMaxStaleMs NRT target max stale time in ms
   * @param nrtHardMaxStaleMs NRT hard max stale time in ms
   * @param softDeletesField field used for soft deletes
   * @param softDeletesRetentionEnabled whether soft-delete retention is enabled
   * @param softDeletesRetentionDays retention period in days
   * @param softDeletesRetentionMaxVersions max versions to retain
   * @param vectorDimension vector dimension
   * @param vectorHnswM HNSW M parameter
   * @param vectorHnswEfConstruction HNSW ef_construction parameter
   * @param vectorEfSearch ef_search parameter
   * @param vectorExhaustiveSearch when true, every kNN query is made EXACT rather than approximate
   *     — {@code ReadPathOps}'s kNN factory raises {@code k} to at least {@code reader.maxDoc()}
   *     and supplies a {@code MatchAllDocsQuery} filter when the caller had none, which is the
   *     only branch Lucene 10.4 exposes with an exact path (lane F PR 0b, the deterministic-capture
   *     switch). Default false = today's approximate HNSW behaviour. O(vectors) per query: a
   *     capture/diagnostic knob, not a production default.
   * @param vectorQuantizationEnabled whether vector quantization is enabled
   * @param indexAutoRecovery whether auto-recovery is enabled for corrupted index
   * @param schemaMismatchPolicy schema mismatch handling policy
   * @param indexIntegrityCheck open-time integrity verification tier ({@code OFF} / {@code STRUCTURAL}
   *     / {@code FULL}); STRUCTURAL verifies the small commit/segment-info file checksums on open, FULL
   *     additionally verifies every segment data file's footer checksum (bounded-vs-thorough knob,
   *     tempdoc 628 G1)
   * @param indexRecoveryPolicy the single orchestration-layer corruption-recovery authority
   *     ({@code BACKUP_REBUILD} / {@code BACKUP_ONLY} / {@code FAIL_CLOSED}); BACKUP_REBUILD (default)
   *     backs up the damaged index, serves degraded, and rebuilds from the source files on disk;
   *     BACKUP_ONLY recovers to empty without an auto-rebuild; FAIL_CLOSED never auto-recovers
   *     (tempdoc 628 Stage B/G2)
   * @param migrationCutoverMaxFailedJobs max failed jobs before migration cutover is blocked
   * @param nrtMode NRT reopen strategy — {@code continuous} (default) or {@code on_demand}
   *     (tempdoc 885 item 19). Unrecognised values fall back to {@code continuous}.
   * @param nrtBackgroundReopenMs background reopen cadence in {@code on_demand} mode; ignored in
   *     {@code continuous} mode
   * @param nrtOnDemandMaxStaleMs age past which a foreground search in {@code on_demand} mode
   *     escalates to a blocking refresh; ignored in {@code continuous} mode
   * @param commitTimerIntervalMs period of the safety-net commit timer that fires whenever
   *     {@code pendingDocs > 0} (default 10000 — unchanged behaviour). The ceiling on every other
   *     commit-cadence lever, which is why it is configurable at all (885's tracked item).
   * @param identityDeletionGraceMs how long a confirmed-deleted path keeps its document identity
   *     before a file reappearing there is treated as a NEW document (default 30 days, tempdoc 931
   *     §C.6). Temporary absence — an unmounted drive, a sync client hiding a file — must not
   *     permanently break identity, and a confirmed replacement must not inherit the old
   *     document's feedback; the window is what separates the two.
   */
  public record Index(
      Integer writerRamBufferMb,
      Integer writerMaxBufferedDocs,
      Integer writerMaxQueueDepth,
      boolean commitMetadataEnabled,
      Integer nrtTargetMaxStaleMs,
      Integer nrtHardMaxStaleMs,
      String softDeletesField,
      Boolean softDeletesRetentionEnabled,
      Integer softDeletesRetentionDays,
      Integer softDeletesRetentionMaxVersions,
      Integer vectorDimension,
      Integer vectorHnswM,
      Integer vectorHnswEfConstruction,
      Integer vectorEfSearch,
      boolean vectorExhaustiveSearch,
      Boolean vectorQuantizationEnabled,
      boolean indexAutoRecovery,
      String schemaMismatchPolicy,
      String indexIntegrityCheck,
      String indexRecoveryPolicy,
      int migrationCutoverMaxFailedJobs,
      String directoryType,
      Integer mergeTieredSegsPerTier,
      Integer mergeTieredMaxMergedSegmentMb,
      String similarityTextType,
      Double similarityTextK1,
      Double similarityTextB,
      String validationMode,
      List<IndexSortItem> sort,
      Map<String, Double> boosts,
      String nrtMode,
      int nrtBackgroundReopenMs,
      int nrtOnDemandMaxStaleMs,
      int commitTimerIntervalMs,
      long identityDeletionGraceMs) {

    /** Default deletion grace for document identity: 30 days in ms (tempdoc 931 §C.6). */
    public static final long DEFAULT_IDENTITY_DELETION_GRACE_MS = 2_592_000_000L;

    /** Wire value of the default NRT reopen strategy (today's behaviour). */
    public static final String NRT_MODE_CONTINUOUS = "continuous";

    /** Wire value of the reopen-on-demand candidate (tempdoc 885 item 19). */
    public static final String NRT_MODE_ON_DEMAND = "on_demand";

    /** HNSW max-connections used when {@code index.vector.hnsw.m} is unset. */
    public static final int DEFAULT_VECTOR_HNSW_M = 16;

    /** HNSW beam width used when {@code index.vector.hnsw.ef_construction} is unset. */
    public static final int DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION = 200;

    /**
     * The HNSW max-connections the graph is actually built with. Both the codec and the
     * {@code index_fingerprint} read this rather than applying their own fallback: writing the
     * default out explicitly is a no-op change, and a fingerprint that moved on it would demand a
     * full reindex for an edit that changes nothing (tempdoc 915 §C).
     */
    public int effectiveVectorHnswM() {
      return this.vectorHnswM() != null ? this.vectorHnswM() : DEFAULT_VECTOR_HNSW_M;
    }

    /** The HNSW beam width the graph is actually built with. See {@link #effectiveVectorHnswM}. */
    public int effectiveVectorHnswEfConstruction() {
      return this.vectorHnswEfConstruction() != null
          ? this.vectorHnswEfConstruction()
          : DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION;
    }

    public Index {
      sort = sort != null ? List.copyOf(sort) : List.of();
      boosts = boosts != null
          ? java.util.Collections.unmodifiableMap(new TreeMap<>(boosts)) : Map.of();
    }

    /** Index-time sort field specification from YAML {@code index.sort[]}. */
    public record IndexSortItem(String field, Boolean reverse, String type) {}
  }

  /** Collection configuration from YAML {@code index.collections[]}. */
  public record CollectionCfg(String name, List<Path> roots) {
    public CollectionCfg {
      roots = roots != null ? List.copyOf(roots) : List.of();
    }
  }

  /** Named collection list with primary collection derivation. */
  public record Collections(List<CollectionCfg> items) {
    public Collections {
      items = items != null ? List.copyOf(items) : List.of();
    }
  }

  /** Indexer worker gRPC client connection config (Head→Body). */
  public record WorkerIndexer(
      boolean enabled, String host, int port, long deadlineMs,
      int queueSize, int maxInFlightBytes, String backpressureMode) {}

  /** Infrastructure health check thresholds from YAML {@code infra.health.*}. */
  public record InfraHealth(
      long pollIntervalMs, long nrtStaleMs, long translatorHandshakeStaleMs,
      int annCacheReadyPercent) {}

  /**
   * RAG (Retrieval-Augmented Generation) retrieval configuration.
   *
   * @param retrieveMode retrieval mode (bm25, hybrid, auto)
   * @param overretrieveFactor over-retrieval factor
   * @param diversifyMode diversification mode (position, mmr)
   * @param mmrLambda MMR lambda parameter
   * @param mmrMaxCandidates max MMR candidates
   * @param chunkVectorsEnabled whether chunk-level vector retrieval is enabled
   * @param chunkSpladeEnabled whether chunk-level SPLADE enrichment is enabled (tempdoc 712:
   *     encodes chunk docs' {@code chunk_content} into the {@code splade} FeatureField so the
   *     chunk-merge sparse sub-leg has data; default false — evidence-gated flip, F-033/Q-017)
   * @param unionEnabled whether the RAG doc-level union leg for chunkless docs is enabled
   *     (tempdoc 749; default true)
   * @param ragTopK env var override for RAG top-k (justsearch.rag.top_k)
   * @param citationMatchThreshold cosine-similarity floor for citation matching, [0,1] (tempdoc
   *     799 N.2: was typed String and never parsed; the live value was a hardcoded 0.5). This is the
   *     ONE cutoff shared by the RAG and agent citation paths (tempdoc 565 15.A) — both composition
   *     roots must read it, or the two paths cite differently.
   * @param maxChunksPerArticle 385: max chunks per parent document in RAG context (diversity cap)
   */
  public record Rag(
      String retrieveMode,
      int overretrieveFactor,
      String diversifyMode,
      double mmrLambda,
      int mmrMaxCandidates,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled,
      boolean unionEnabled,
      int ragTopK,
      double citationMatchThreshold,
      int maxChunksPerArticle) {}

  /**
   * Hybrid-search tuning knobs for RRF, candidate limits, weights, and low-signal gating.
   *
   * @param rrfK RRF constant K
   * @param vectorSkipMinChars min query chars before vector search is attempted
   * @param vectorSkipMinDfFraction minimum analyzed-term document-frequency fraction at which a
   *     redundant dense leg is skipped
   * @param candidateLimitMax max candidates per retrieval system
   * @param textCandidateMultiplier BM25 candidate multiplier
   * @param vectorCandidateMultiplier vector candidate multiplier
   * @param vectorRrfWeight vector RRF weight
   * @param bm25ScoreBoostWeight additive BM25 score boost weight
   * @param vectorLowSignalTopScoreThreshold low-signal vector top-score threshold (EUCLIDEAN score
   *     space — the dense field is EUCLIDEAN, not cosine; default 0.294 corresponds to intended
   *     cosine-score 0.40, tempdoc 702)
   * @param bm25LowSignalTopScoreThreshold low-signal BM25 top-score threshold
   * @param bm25LowSignalTotalHitsThreshold low-signal BM25 total-hits threshold
   * @param vectorOnlyCapLowSignal max vector-only docs in low-signal fusion
   * @param vectorRrfWeightLowSignal vector RRF weight for low-signal queries
   * @param fusionStrategy fusion algorithm: "cc" (convex combination, default) or "rrf"
   * @param ccAlpha CC dense weight (0.0=pure sparse, 1.0=pure dense, default 0.5)
   * @param ccZeroExclude if true, single-leg docs use only that leg's weight as denominator
   *     instead of being penalized with 0.0 for the missing leg (default false)
   */
  public record HybridSearch(
      int rrfK,
      int vectorSkipMinChars,
      double vectorSkipMinDfFraction,
      int candidateLimitMax,
      int textCandidateMultiplier,
      int vectorCandidateMultiplier,
      double vectorRrfWeight,
      double bm25ScoreBoostWeight,
      double vectorLowSignalTopScoreThreshold,
      double bm25LowSignalTopScoreThreshold,
      int bm25LowSignalTotalHitsThreshold,
      int vectorOnlyCapLowSignal,
      double vectorRrfWeightLowSignal,
      String fusionStrategy,
      double ccAlpha,
      boolean ccZeroExclude,
      double ccWeightSparse,
      double ccWeightDense,
      double ccWeightSplade,
      String branchFusionStrategy,
      boolean branchCcZeroExclude,
      double branchCcWeightWhole,
      double branchCcWeightChunk,
      double branchChunkMinWeightMultiplier,
      // Tempdoc 854 W1 (F-036 §K wrong-gate fix) — the Stage-3B whole-vs-chunk branch ramp's OWN
      // parent-token bounds, separated from the Stage-3A SPLADE parent-length-fade bounds they
      // used to share (justsearch.splade.full_weight_max_tokens / .zero_weight_min_tokens).
      // Defaults (1024 / 4096) reproduce the pre-split shared-constant behavior byte-identically.
      long branchRampFullWeightMaxTokens,
      long branchRampZeroWeightMinTokens,
      boolean adaptiveWeightsEnabled,
      boolean legArbitrationEnabled,
      double legArbitrationAlphaDiverge,
      double legArbitrationBm25IncoherenceMin,
      boolean legRecallCompleteEnabled,
      int legRecallCompleteTopN,
      // Tempdoc 774 Stage 1 — independent chunk-branch CC leg weights + zero-exclude. Each defaults
      // to the resolved doc-level value (cc_weight_* / cc_zero_exclude), so an explicit doc-level
      // override still flows through unless the chunk key is set. Decouples the chunk branch from the
      // doc legs (§F.1-2 silent coupling).
      double chunkCcWeightSparse,
      double chunkCcWeightDense,
      double chunkCcWeightSplade,
      boolean chunkCcZeroExclude,
      // Tempdoc 774 Stage 1 — chunk-branch collapse cap multiplier (parents delivered to branch
      // fusion = limit × this; default 2 reproduces the old hardcoded 2×limit).
      int chunkCollapseLimitMultiplier,
      // Tempdoc 774 Stage 1 — chunk-side recall-complete: splice the chunk branch's per-leg top-N
      // parents into the merged list (the passage-granularity twin of the doc-side guarantee).
      boolean chunkLegRecallCompleteEnabled,
      int chunkLegRecallCompleteTopN,
      // Tempdoc 774 Stage 1 — when false, the chunk branch runs even when the doc legs return empty
      // ("fusion is a ranking step, not a recall gate"); default true reproduces today's base-gate.
      boolean chunkBranchRequiresBaseResults) {}


  /**
   * Worker resource limits and service configuration.
   *
   * @param maxBatchSize maximum files in a single batch request
   * @param maxQueueDepth maximum queue depth before rejecting
   * @param maxContentLength maximum content length in bytes
   * @param maxFileSize maximum file size in bytes
   */
  /**
   * Worker ingest limits.
   *
   * <p>{@code maxBatchSize} / {@code maxQueueDepth} were removed by tempdoc 799 §N.2: both were
   * shadowed by {@code WorkerIngestService}'s hardcoded {@code MAX_BATCH_SIZE} / {@code
   * MAX_QUEUE_DEPTH}, and gRPC batching is an internal transport concern rather than a user
   * preference. {@code maxContentLength} / {@code maxFileSize} are retained and wired — those are
   * genuine user-facing choices about which files to index.
   */
  public record Worker(int maxContentLength, long maxFileSize) {}
}
