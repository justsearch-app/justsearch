/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Centralized registry for all JustSearch environment variables and system properties.
 *
 * <p>This class provides type-safe access to configuration values. The direct resolution
 * order within EnvRegistry is:
 * <ol>
 *   <li>System property (e.g., {@code -Djustsearch.data.dir=/path})</li>
 *   <li>Environment variable (e.g., {@code JUSTSEARCH_DATA_DIR=/path})</li>
 *   <li>Default value (if provided)</li>
 * </ol>
 *
 * <p><strong>Important:</strong> {@link io.justsearch.configuration.resolved.ResolvedConfigBuilder}
 * is the authority on precedence across all sources, not this class. It registers every source at
 * an ordinal and the highest ordinal wins:
 * <ol>
 *   <li>System property, ordinal 500 (highest)</li>
 *   <li>Environment variable, ordinal 400</li>
 *   <li>YAML configuration (e.g., {@code config.yaml}), ordinal 200</li>
 *   <li>Code default, ordinal 100 (lowest)</li>
 * </ol>
 * So a system property or environment variable set for a key overrides its YAML value; YAML only
 * wins when no system property or environment variable is set for that key.
 *
 * <p>Every declaration explicitly classifies its governance lifecycle. Experimental and deprecated
 * declarations additionally require a joined row in {@code governance/config-lifecycle.v1.json}.
 *
 * <p>Usage:
 * <pre>{@code
 * Path dataDir = EnvRegistry.DATA_DIR.getPath();
 * int port = EnvRegistry.API_PORT.getInt(8080);
 * }</pre>
 */
public enum EnvRegistry {
    /** Root data directory for all JustSearch artifacts. */
    DATA_DIR("justsearch.data.dir", "JUSTSEARCH_DATA_DIR", LifecycleStage.PERMANENT),

    /** Explicit base path for Lucene index storage. */
    INDEX_BASE_PATH("justsearch.index.base_path", "JUSTSEARCH_INDEX_BASE_PATH", LifecycleStage.PERMANENT),

    /** Explicit path to SSOT directory (overrides auto-discovery). */
    SSOT_PATH("justsearch.ssot.path", "JUSTSEARCH_SSOT_PATH", LifecycleStage.PERMANENT),

    /** Path to field catalog JSON (overrides SSOT lookup). */
    FIELD_CATALOG("justsearch.fieldCatalog", "JUSTSEARCH_FIELD_CATALOG", LifecycleStage.PERMANENT),

    /** Path to application config YAML. */
    CONFIG_PATH("justsearch.config", "JUSTSEARCH_CONFIG", LifecycleStage.PERMANENT),

    /** Path to the MCP-host server list JSON (tempdoc 560 §6); unset disables the MCP-host. */
    MCP_HOST_CONFIG("justsearch.mcp.host.config", "JUSTSEARCH_MCP_HOST_CONFIG", LifecycleStage.PERMANENT),

    /** API server port. */
    API_PORT("justsearch.api.port", "JUSTSEARCH_API_PORT", LifecycleStage.PERMANENT),

    /** Telemetry flush interval (ms). */
    TELEMETRY_FLUSH_MS("justsearch.telemetry.flushMs", "JUSTSEARCH_TELEMETRY_FLUSH_MS", LifecycleStage.PERMANENT),

    /** Indexer-worker version string (override). */
    INDEXER_WORKER_VERSION("indexer.worker.version", "JUSTSEARCH_INDEXER_WORKER_VERSION", LifecycleStage.PERMANENT),

    /** Production mode flag. */
    PROD_MODE("justsearch.prod", "JUSTSEARCH_PROD", LifecycleStage.PERMANENT),

    /** Egress block-all flag for isolated testing. */
    EGRESS_BLOCK_ALL("egress.block_all", "JUSTSEARCH_EGRESS_BLOCK_ALL", LifecycleStage.PERMANENT),

    /** Query Understanding enabled flag (366 — disabled by default, experimental). */
    QU_ENABLED(
        "justsearch.qu.enabled", "JUSTSEARCH_QU_ENABLED", LifecycleStage.EXPERIMENTAL),

    /** Filter value normalization enabled flag (366 — disabled by default, experimental). */
    FILTER_NORM_ENABLED(
        "justsearch.filter_norm.enabled",
        "JUSTSEARCH_FILTER_NORM_ENABLED",
        LifecycleStage.EXPERIMENTAL),

    /** LLM enabled flag. */
    LLM_ENABLED("justsearch.llm.enabled", "JUSTSEARCH_LLM_ENABLED", LifecycleStage.PERMANENT),

    /** LLM model path. */
    LLM_MODEL_PATH("justsearch.llm.model_path", "JUSTSEARCH_LLM_MODEL_PATH", LifecycleStage.PERMANENT),

    /**
     * Install/runtime intent (tempdoc 657): {@code full-desktop} | {@code headless} |
     * {@code mcp-lite}. Set at launch by whichever launcher started the backend (the Tauri shell
     * declares {@code full-desktop}; the headless launcher declares {@code headless}/{@code mcp-lite}).
     * Resolved to {@code io.justsearch.configuration.model.InstallIntent} (default Full Desktop when
     * unset), consumed by the install planner and the runtime-manifest publisher.
     */
    MODE("justsearch.mode", "JUSTSEARCH_MODE", LifecycleStage.PERMANENT),




















    /** Enables thinking mode (reasoning_content parsing, --reasoning-format deepseek). Default true. */
    USE_THINKING("justsearch.llm.use_thinking", "JUSTSEARCH_USE_THINKING", LifecycleStage.PERMANENT),

    /**
     * Reasoning token budget for llama-server (--reasoning-budget). Default 512 (bounded reasoning,
     * on — tempdoc 835). 0 disables reasoning. Unbounded (-1) and any value >= the engine's default
     * completion ceiling are refused and clamped back to the default: reasoning shares the
     * completion budget with the answer, so those shapes silently return empty answers.
     */
    REASONING_BUDGET("justsearch.llm.reasoning_budget", "JUSTSEARCH_REASONING_BUDGET", LifecycleStage.PERMANENT),

    /** Enable deterministic context compression for older agent tool outputs. */
    AGENT_CONTEXT_COMPRESSION_ENABLED(
        "justsearch.agent.context_compression.enabled",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED", LifecycleStage.PERMANENT),

    /** Minimum tool output size (characters) before compression is applied. */
    AGENT_CONTEXT_COMPRESSION_MIN_CHARS(
        "justsearch.agent.context_compression.min_chars",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS", LifecycleStage.PERMANENT),

    /** Number of most-recent tool outputs to keep uncompressed in conversation context. */
    AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS(
        "justsearch.agent.context_compression.keep_last_results",
        "JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS", LifecycleStage.PERMANENT),

    /** Default search result limit for the agent search tool. */
    AGENT_SEARCH_DEFAULT_LIMIT(
        "justsearch.agent.search.default_limit", "JUSTSEARCH_AGENT_SEARCH_DEFAULT_LIMIT", LifecycleStage.PERMANENT),

    /** Default search mode for the agent search tool (text, hybrid, vector). Empty = text. */
    AGENT_SEARCH_DEFAULT_MODE(
        "justsearch.agent.search.default_mode", "JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE", LifecycleStage.PERMANENT),

    /** Default max folders for the agent browse tool. */
    AGENT_BROWSE_DEFAULT_MAX_FOLDERS(
        "justsearch.agent.browse.default_max_folders",
        "JUSTSEARCH_AGENT_BROWSE_DEFAULT_MAX_FOLDERS", LifecycleStage.PERMANENT),

    /** Maximum characters preserved per tool result before truncation. */
    AGENT_MAX_TOOL_RESULT_CHARS(
        "justsearch.agent.max_tool_result_chars", "JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS", LifecycleStage.PERMANENT),

    /** Maximum completion tokens per agent LLM call (-1 = use llama-server default). */
    AGENT_MAX_COMPLETION_TOKENS(
        "justsearch.agent.max_completion_tokens", "JUSTSEARCH_AGENT_MAX_COMPLETION_TOKENS", LifecycleStage.PERMANENT),









    /** Summary pipeline identifier. */
    SUMMARY_PIPELINE("justsearch.summary.pipeline", "JUSTSEARCH_SUMMARY_PIPELINE", LifecycleStage.PERMANENT),

    /** Summary max estimated tokens before rejection. */
    SUMMARY_MAX_TOKENS("justsearch.summary.max_tokens", "JUSTSEARCH_SUMMARY_MAX_TOKENS", LifecycleStage.PERMANENT),

    /** Embedding dimension override for worker/runtime compatibility. */
    EMBED_DIMENSION_OVERRIDE("justsearch.embed.dimension", "JUSTSEARCH_EMBED_DIM", LifecycleStage.PERMANENT),

    /** Embedding backend to use: "auto" (default) or "onnx". "llama" was removed in March 2026. */
    EMBED_BACKEND("justsearch.embed.backend", "JUSTSEARCH_EMBED_BACKEND", LifecycleStage.PERMANENT),

    /**
     * Explicit path to ONNX embedding model directory. When unset, discovery
     * resolves via {@code OnnxModelDiscovery}: {@code <modelRoot>/onnx/<modelName>/}
     * (modelName defaults to {@code gte-multilingual-base}). Set this env var
     * to override discovery and force a specific path.
     */
    EMBED_ONNX_MODEL_PATH("justsearch.embed.onnx.model_path", "JUSTSEARCH_EMBED_ONNX_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for ONNX embedding inference (default false). */
    EMBED_GPU_ENABLED("justsearch.embed.gpu.enabled", "JUSTSEARCH_EMBED_GPU_ENABLED", LifecycleStage.PERMANENT),

    /** CUDA device ID for ONNX embedding sessions (default 0). */
    EMBED_GPU_DEVICE_ID("justsearch.embed.gpu.device_id", "JUSTSEARCH_EMBED_GPU_DEVICE_ID", LifecycleStage.PERMANENT),

    /** GPU memory arena limit for ONNX embedding sessions (MB, default 6144 — 691 §N/F-031). */
    EMBED_GPU_MEM_MB("justsearch.embed.gpu_mem_mb", "JUSTSEARCH_EMBED_GPU_MEM_MB", LifecycleStage.PERMANENT),

    /**
     * Tempdoc 691 Phase 2/4: long-doc single-pass VECTOR embed — a chunked parent doc's whole-doc
     * vector comes from ONE batch-1 forward pass at {@code late_chunking_context_length} tokens
     * ({@code OnnxEmbeddingEncoder#embedWithSpans}, technique per arXiv:2409.04701) instead of the
     * base window-mean; chunk docs keep their own per-chunk path (per-span reuse dropped by
     * measurement, 691 §Phase M). DEFAULT ON since 691 §Phase N (D-004 template: default-off →
     * measured → default-on): legal-clerc vector nDCG@10 0.0597→0.2967 at defaults, all three
     * quality gates green; measured cost: background enrichment slower on long-doc corpora
     * (enron 7.7→4.5 docs/s at the default 3072MB arena — see 691 §N-7).
     */
    EMBED_LATE_CHUNKING_ENABLED(
        "justsearch.embed.late_chunking_enabled",
        "JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED",
        "true", LifecycleStage.PERMANENT),

    /**
     * Single-pass whole-doc VECTOR limit for the late-chunking path — tempdoc 691 Phase 2. Raises
     * the eligibility ceiling for the batch-1 {@code embedWithSpans} long-doc pass independently of
     * the base {@code justsearch.embed.context_length} (whose batch path would OOM at this length).
     * Default 8192, clamped to the model's trained context ceiling.
     */
    EMBED_LATE_CHUNKING_CONTEXT_LENGTH(
        "justsearch.embed.late_chunking_context_length",
        "JUSTSEARCH_EMBED_LATE_CHUNKING_CONTEXT_LENGTH",
        "8192", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for SPLADE inference (default false). */
    SPLADE_GPU_ENABLED("justsearch.splade.gpu_enabled", "JUSTSEARCH_SPLADE_GPU_ENABLED", LifecycleStage.PERMANENT),

    /** CUDA device ID for SPLADE inference (default 0). */
    SPLADE_GPU_DEVICE_ID("justsearch.splade.gpu_device_id", "JUSTSEARCH_SPLADE_GPU_DEVICE_ID", LifecycleStage.PERMANENT),

    /** GPU memory arena limit for SPLADE sessions (MB, default 4096). */
    SPLADE_GPU_MEM_MB("justsearch.splade.gpu_mem_mb", "JUSTSEARCH_SPLADE_GPU_MEM_MB", LifecycleStage.PERMANENT),

    /** GPU memory arena limit for ONNX reranker sessions (MB, default 2048). */
    RERANK_GPU_MEM_MB("justsearch.rerank.gpu_mem_mb", "JUSTSEARCH_RERANK_GPU_MEM_MB", "2048", LifecycleStage.PERMANENT),

    /** Explicit path to the reranker model directory. */
    RERANK_MODEL_PATH("justsearch.rerank.model_path", "JUSTSEARCH_RERANK_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for reranker inference (default true — reranker runs in Worker with GPU ORT JAR). */
    RERANK_GPU_ENABLED("justsearch.rerank.gpu.enabled", "JUSTSEARCH_RERANK_GPU_ENABLED", "true", LifecycleStage.PERMANENT),

    /** CUDA device ID for reranker inference (default 0). */
    RERANK_GPU_DEVICE_ID("justsearch.rerank.gpu.device_id", "JUSTSEARCH_RERANK_GPU_DEVICE_ID", "0", LifecycleStage.PERMANENT),

    /** Explicit path to the chunk reranker model directory. */
    RERANK_CHUNKS_MODEL_PATH(
        "justsearch.rerank.chunks.model_path", "JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for chunk reranker inference (default false). */
    RERANK_CHUNKS_GPU_ENABLED(
        "justsearch.rerank.chunks.gpu.enabled", "JUSTSEARCH_RERANK_CHUNKS_GPU_ENABLED", "false", LifecycleStage.PERMANENT),

    /** CUDA device ID for chunk reranker inference (default 0). */
    RERANK_CHUNKS_GPU_DEVICE_ID(
        "justsearch.rerank.chunks.gpu.device_id", "JUSTSEARCH_RERANK_CHUNKS_GPU_DEVICE_ID", "0", LifecycleStage.PERMANENT),

    /** Search pipeline profile. */
    SEARCH_PROFILE("justsearch.search.pipeline.profile", "JUSTSEARCH_SEARCH_PROFILE", LifecycleStage.PERMANENT),

    /** Primary index collection override (legacy escape hatch; prefer YAML). */
    INDEX_COLLECTION("justsearch.index.collection", "JUSTSEARCH_INDEX_COLLECTION", LifecycleStage.DEPRECATED),

    /** Index parity guard escape hatch (allow opening read-only on mismatch). */
    INDEX_PARITY_ALLOW_MISMATCH(
        "justsearch.index.parity.allow_mismatch", "JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH", LifecycleStage.PERMANENT),

    /** Schema mismatch policy (e.g., blue_green_migrate, rebuild_backup_first). */
    INDEX_SCHEMA_MISMATCH_POLICY(
        "index.schema_mismatch.policy", "JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY", LifecycleStage.PERMANENT),

    /** Migration cutover: max tolerable failed jobs before aborting (default -1 = unlimited). */
    INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS(
        "index.migration.cutover.max_failed_jobs",
        "JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS", LifecycleStage.PERMANENT),

    /** Explicit repository root path (overrides auto-discovery). */
    REPO_ROOT("justsearch.repo.root", "JUSTSEARCH_REPO_ROOT", LifecycleStage.PERMANENT),

    // ==================== AI/Inference Configuration (Fix #7) ====================

    /** JustSearch home/base directory. */
    HOME("justsearch.home", "JUSTSEARCH_HOME", LifecycleStage.PERMANENT),

    /** Explicit path to the Tesseract executable or containing directory for OCR extraction. */
    TESSERACT_PATH("justsearch.tesseract.path", "JUSTSEARCH_TESSERACT_PATH", LifecycleStage.PERMANENT),

    /** Explicit path to tessdata for OCR language packs. */
    TESSDATA_PATH("justsearch.tessdata.path", "JUSTSEARCH_TESSDATA_PATH", LifecycleStage.PERMANENT),

    /** Path to llama-server executable. */
    SERVER_EXE("justsearch.server.exe", "JUSTSEARCH_SERVER_EXE", LifecycleStage.PERMANENT),

    /** Models directory for AI model files. */
    MODELS_DIR("justsearch.models.dir", "JUSTSEARCH_MODELS_DIR", LifecycleStage.PERMANENT),

    /** Vision-Language Model filename. */
    VLM_MODEL("justsearch.vlm.model", "JUSTSEARCH_VLM_MODEL", LifecycleStage.PERMANENT),

    /** Vision projector model filename. */
    MMPROJ_MODEL("justsearch.mmproj.model", "JUSTSEARCH_MMPROJ_MODEL", LifecycleStage.PERMANENT),

    /**
     * VLM extraction profile name (tempdoc 580 Track D / F-009). Atomically selects the
     * (vlm-model, mmproj) pair for document extraction so a half-swap is unrepresentable:
     * {@code qwen-vl} (default — today's behavior) or {@code paddle-ocr-vl} (the F-009 pilot).
     * The per-file {@link #VLM_MODEL}/{@link #MMPROJ_MODEL} overrides still win when set.
     */
    VLM_PROFILE("justsearch.vlm.profile", "JUSTSEARCH_VLM_PROFILE", LifecycleStage.DEPRECATED),

    /**
     * Chat model profile (tempdoc 842) — selects the llama-server engine model pair:
     * {@code standard} (Qwen3.5-9B, the user-facing default) or {@code compact} (the dev-tier small
     * model). Consumed at engine start. {@link #VLM_PROFILE} remains a legacy fallback read by the
     * inference layer.
     */
    CHAT_PROFILE("justsearch.chat.profile", "JUSTSEARCH_CHAT_PROFILE", "standard", LifecycleStage.PERMANENT),

    /** HTTP port for llama-server. */
    SERVER_PORT("justsearch.server.port", "JUSTSEARCH_SERVER_PORT", LifecycleStage.PERMANENT),

    /** LLM context window size. */
    CONTEXT_SIZE("justsearch.context.size", "JUSTSEARCH_CONTEXT_SIZE", LifecycleStage.PERMANENT),

    /** Number of GPU layers to offload. */
    GPU_LAYERS("justsearch.gpu.layers", "JUSTSEARCH_GPU_LAYERS", LifecycleStage.PERMANENT),

    /** Master GPU switch for ONNX models (auto-set when CUDA detected). Per-model overrides win. */
    GPU_ENABLED("justsearch.gpu.enabled", "JUSTSEARCH_GPU_ENABLED", LifecycleStage.PERMANENT),

    /** GPU acceleration policy gate (true = allow GPU, false = CPU-only). */
    POLICY_GPU_ACCELERATION_ENABLED(
        "policy.gpu_acceleration_enabled",
        "JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED", LifecycleStage.PERMANENT),

    /** Enable/disable embedding feature independently (escape hatch; default from YAML/SSOT). */
    AI_EMBED_ENABLED("justsearch.ai.embed.enabled", "JUSTSEARCH_AI_EMBED_ENABLED", LifecycleStage.PERMANENT),

    /** Disable all AI features. */
    AI_DISABLED("justsearch.ai.disabled", "JUSTSEARCH_AI_DISABLED", LifecycleStage.PERMANENT),

    // ==================== RAG Configuration ====================

    /** Number of chunks to retrieve for RAG context (default 5). */
    RAG_TOP_K("justsearch.rag.top_k", "JUSTSEARCH_RAG_TOP_K", LifecycleStage.PERMANENT),

    /** VRAM threshold for 12GB-tier classification in bytes. */
    VRAM_THRESHOLD_12GB("justsearch.vram.threshold.12gb", "JUSTSEARCH_VRAM_THRESHOLD_12GB", LifecycleStage.PERMANENT),

    /** VRAM threshold for 8GB-tier classification in bytes. */
    VRAM_THRESHOLD_8GB("justsearch.vram.threshold.8gb", "JUSTSEARCH_VRAM_THRESHOLD_8GB", LifecycleStage.PERMANENT),

    /** VRAM threshold for 4GB-tier classification in bytes. */
    VRAM_THRESHOLD_4GB("justsearch.vram.threshold.4gb", "JUSTSEARCH_VRAM_THRESHOLD_4GB", LifecycleStage.PERMANENT),

    /** Cosine similarity threshold for post-hoc citation matching (default 0.5). */
    CITATION_MATCH_THRESHOLD(
        "justsearch.citation.match_threshold", "JUSTSEARCH_CITATION_MATCH_THRESHOLD", LifecycleStage.PERMANENT),

    /** OnnxRuntime native variant ID override. */
    ONNXRUNTIME_VARIANT_ID("justsearch.onnxruntime.variantId", "JUSTSEARCH_ONNXRUNTIME_VARIANT_ID", LifecycleStage.PERMANENT),

    /** ONNX Runtime native library path for CUDA provider. */
    ORT_NATIVE_PATH("justsearch.onnxruntime.native_path", "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", LifecycleStage.PERMANENT),

    /** ORT per-session profile directory (diagnostic, tempdoc 397 §14.24 FB). */
    ORT_PROFILING_DIR("justsearch.ort.profiling_dir", "JUSTSEARCH_ORT_PROFILING_DIR", LifecycleStage.PERMANENT),

    /** ORT VERBOSE-level session logging toggle (diagnostic, tempdoc 397 §14.24 FB). */
    ORT_VERBOSE_LOGGING("justsearch.ort.verbose", "JUSTSEARCH_ORT_VERBOSE", LifecycleStage.PERMANENT),

    /** Search pipeline definition file path (full path override). */
    SEARCH_PIPELINE("justsearch.search.pipeline", "JUSTSEARCH_SEARCH_PIPELINE", LifecycleStage.PERMANENT),

    /** 306: enable/disable query classification for A/B eval (default: true via builder). */
    SEARCH_QUERY_CLASSIFICATION_ENABLED(
        "justsearch.search.query_classification.enabled",
        "JUSTSEARCH_SEARCH_QUERY_CLASSIFICATION_ENABLED", LifecycleStage.PERMANENT),
    /** 306: title field boost in BM25 DisjunctionMaxQuery (default: 3.0 via builder, 0 to disable). */
    SEARCH_TITLE_BOOST("justsearch.search.title_boost", "JUSTSEARCH_SEARCH_TITLE_BOOST", LifecycleStage.PERMANENT),

    /** 343: enable/disable chunk-aware merge in search (default: true via builder). */
    SEARCH_CHUNK_AWARE_ENABLED(
        "search.chunk_aware.enabled", "JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED", LifecycleStage.PERMANENT),

    /**
     * Tempdoc 774 Stage 2: when enabled, chunk-sourced hits carry the winning chunk's text as
     * {@code content_preview} (evidence-coherent CE input + delivery). Default false via builder.
     */
    SEARCH_EVIDENCE_PREVIEW_ENABLED(
        "search.evidence_preview.enabled", "JUSTSEARCH_SEARCH_EVIDENCE_PREVIEW_ENABLED", LifecycleStage.PERMANENT),
    /** 775: enable answer-bearing EvidenceSpan-backed excerpt selection (default: false via builder). */
    SEARCH_EVIDENCE_SPAN_ENABLED(
        "search.evidence_span.enabled", "JUSTSEARCH_SEARCH_EVIDENCE_SPAN_ENABLED", LifecycleStage.PERMANENT),
    /** 775: EvidenceSpan distinguishing-entity signal — df_rarity|ner_membership (default: ner_membership, the §F probe winner). */
    SEARCH_EVIDENCE_SPAN_ENTITY_SIGNAL(
        "search.evidence_span.entity_signal", "JUSTSEARCH_SEARCH_EVIDENCE_SPAN_ENTITY_SIGNAL", LifecycleStage.PERMANENT),
    /**
     * 775 §E: MCP delivery-governor serialized-JSON budget in bytes (default: 45000 via builder; 0
     * disables the governor). Escape-hatch operator override for the 770 §E.3 truncation-cliff
     * degradation.
     */
    SEARCH_MCP_DELIVERY_BUDGET_BYTES(
        "search.mcp_delivery.budget_bytes", "JUSTSEARCH_SEARCH_MCP_DELIVERY_BUDGET_BYTES", LifecycleStage.PERMANENT),

    /**
     * 771 item (b): append the document's indexed NER entity names to a delivered {@code
     * justsearch_search} hit whose excerpt does not already carry them (default: false via builder).
     * The engine-side half of the hop-2 fix — an agent cannot re-query a bridge entity whose name
     * was never delivered. OFF ships byte-identical delivery.
     */
    SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED(
        "search.mcp_delivery.entity_carriage_enabled",
        "JUSTSEARCH_SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED", LifecycleStage.EXPERIMENTAL),
    /**
     * 771 item (b): byte ceiling for the whole rendered entity-carriage line, per hit (default: 200
     * via builder). Only consulted when {@code entity_carriage_enabled} is on.
     */
    SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS(
        "search.mcp_delivery.entity_carriage_max_chars",
        "JUSTSEARCH_SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS", LifecycleStage.EXPERIMENTAL),

    /**
     * 789 Phase 2 (F1): append a continuation line to a delivered excerpt that names an indexed
     * entity the query did not (default: false via builder). Probe substrate for the hop-1
     * satisficing effect measured by the 782 hero campaign; OFF ships byte-identical delivery.
     */
    SEARCH_MCP_FRAMING_CONTINUATION(
        "search.mcp_framing.continuation_enabled",
        "JUSTSEARCH_SEARCH_MCP_FRAMING_CONTINUATION_ENABLED", LifecycleStage.EXPERIMENTAL),
    /**
     * 789 Phase 2 (F2): frame search/answer deliveries explicitly as retrieval evidence rather than
     * verified answers (default: false via builder).
     */
    SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER(
        "search.mcp_framing.evidence_not_answer_enabled",
        "JUSTSEARCH_SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER_ENABLED", LifecycleStage.EXPERIMENTAL),
    /**
     * 789 Phase 2 (F3): carry corpus coverage + absence-is-not-evidence framing on zero-result and
     * thin-result deliveries (default: false via builder).
     */
    SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE(
        "search.mcp_framing.calibrated_absence_enabled",
        "JUSTSEARCH_SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE_ENABLED", LifecycleStage.EXPERIMENTAL),
    /**
     * 789 Phase 2 (F3): delivered-body byte floor below which a non-empty result set still counts as
     * "thin" and receives the calibrated-absence framing (default: 400 via builder).
     */
    SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES(
        "search.mcp_framing.thin_result_floor_bytes",
        "JUSTSEARCH_SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES", LifecycleStage.EXPERIMENTAL),
    /**
     * 789 (post-Amendment-3): normalized top-relevance floor below which a non-empty result set is
     * framed as a weak-relevance delivery (default: 0.40 via builder). Only consulted where the
     * fused score is bounded [0,1] — the {@code cc}/{@code hybrid} fusion methods; RRF and
     * single-leg deliveries are out of scope for the arm. {@code 0} disables it.
     */
    SEARCH_MCP_FRAMING_WEAK_SCORE_FLOOR(
        "search.mcp_framing.weak_score_floor", "JUSTSEARCH_SEARCH_MCP_FRAMING_WEAK_SCORE_FLOOR", LifecycleStage.EXPERIMENTAL),

    /** UI automation mode enabled flag. */
    UI_AUTOMATION_ENABLED("justsearch.ui.automation.enabled", "JUSTSEARCH_UI_AUTOMATION", LifecycleStage.PERMANENT),

    /** UI automation: force infra diagnostics overrides. */
    UI_AUTOMATION_FORCE_DIAGNOSTICS(
        "justsearch.ui.automation.forceDiagnostics",
        "JUSTSEARCH_UI_AUTOMATION_FORCE_DIAGNOSTICS", LifecycleStage.PERMANENT),

    /** UI settings persistence mode (read-only, in-memory, etc.). */
    UI_SETTINGS_MODE("justsearch.ui.settings.mode", "JUSTSEARCH_UI_SETTINGS_MODE", LifecycleStage.PERMANENT),

    /** Source of server executable selection (environment_variable / operator / etc.). */
    SERVER_EXE_SOURCE("justsearch.server.exe.source", "JUSTSEARCH_SERVER_EXE_SOURCE", LifecycleStage.PERMANENT),

    /** LambdaMART reranker enabled flag (default: true when model file exists). */
    LAMBDAMART_ENABLED("justsearch.lambdamart.enabled", "JUSTSEARCH_LAMBDAMART_ENABLED", LifecycleStage.PERMANENT),

    // ==================== NER Configuration ====================

    /** Enable NER extraction (auto if model found). */
    NER_ENABLED("justsearch.ner.enabled", "JUSTSEARCH_NER_ENABLED", LifecycleStage.PERMANENT),

    /** Explicit path to NER model directory. */
    NER_MODEL_PATH("justsearch.ner.model_path", "JUSTSEARCH_NER_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Max token sequence length for NER inference (default 512). */
    NER_MAX_SEQ_LEN("justsearch.ner.max_seq_len", "JUSTSEARCH_NER_MAX_SEQ_LEN", LifecycleStage.PERMANENT),

    /** NER confidence threshold (default 0.5). */
    NER_CONFIDENCE_THRESHOLD(
        "justsearch.ner.confidence_threshold", "JUSTSEARCH_NER_CONFIDENCE_THRESHOLD", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for NER inference. */
    NER_GPU_ENABLED("justsearch.ner.gpu_enabled", "JUSTSEARCH_NER_GPU_ENABLED", LifecycleStage.PERMANENT),

    /** CUDA device index for NER GPU inference (default 0). */
    NER_GPU_DEVICE_ID("justsearch.ner.gpu_device_id", "JUSTSEARCH_NER_GPU_DEVICE_ID", LifecycleStage.PERMANENT),

    /** GPU memory arena limit in MB for NER inference (default 2048 — tempdoc 691). */
    NER_GPU_MEM_MB("justsearch.ner.gpu_mem_mb", "JUSTSEARCH_NER_GPU_MEM_MB", LifecycleStage.PERMANENT),

    // ==================== Extraction Sandbox Configuration ====================

    /**
     * Worker extraction sandbox mode. Values: {@code auto} (default — per-family routing:
     * PDF/Office/archives/images out of process, text/markdown/code/CSV/JSON in process),
     * {@code in_process} (everything in the Worker JVM) or {@code process} (everything in the
     * child pool). See tempdoc 410 for the failure-domain design and tempdoc 885 item 14 for the
     * persistent pool that made it shippable.
     */
    EXTRACTION_SANDBOX_MODE(
        "justsearch.extraction.sandbox.mode", "JUSTSEARCH_EXTRACTION_SANDBOX_MODE", LifecycleStage.PERMANENT),

    /**
     * Whitespace-separated command used to spawn the extraction child JVM. Optional operator
     * override; when unset the Worker builds the command in-process from {@code java.home} +
     * {@code java.class.path}. The child's parent-PID argument is appended by the pool.
     */
    EXTRACTION_SANDBOX_COMMAND(
        "justsearch.extraction.sandbox.command", "JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND", LifecycleStage.PERMANENT),

    // ==================== Ingestion Skip Policy (tempdoc 410 §13) ====================

    /**
     * Comma-separated lowercase file-name fragments treated as skip patterns by
     * {@code IngestionSkipPolicy}. Operator override; defaults to the built-in system/junk set
     * ({@code thumbs.db}, {@code .ds_store}, {@code desktop.ini}, {@code .git}, {@code .svn},
     * {@code $recycle.bin}). The default applies when this key is unset; setting it replaces the
     * defaults wholesale.
     */
    INGESTION_SKIP_PATTERNS(
        "justsearch.ingestion.skip.patterns", "JUSTSEARCH_INGESTION_SKIP_PATTERNS", LifecycleStage.PERMANENT),

    /**
     * Comma-separated lowercase file extensions (no leading dot) treated as build/cache output by
     * {@code IngestionSkipPolicy}. Defaults to {@code pyc,pyo,class,o,obj}.
     */
    INGESTION_SKIP_EXTENSIONS(
        "justsearch.ingestion.skip.extensions", "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS", LifecycleStage.PERMANENT),

    /**
     * Comma-separated lowercase directory basenames a tree walk should never descend into.
     * Defaults to the standard VCS / cache / recycle-bin set ({@code .git}, {@code .svn},
     * {@code .hg}, {@code .bzr}, {@code cvs}, {@code node_modules}, {@code bower_components},
     * {@code __pycache__}, {@code .tox}, {@code .pytest_cache}, {@code .mypy_cache},
     * {@code $recycle.bin}, {@code system volume information}).
     */
    INGESTION_SKIP_DIRECTORY_NAMES(
        "justsearch.ingestion.skip.directory_names",
        "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES", LifecycleStage.PERMANENT),

    /**
     * Lite mode for ingestion-only test scenarios (tempdoc 419 T6.1). When {@code true} the
     * Head process skips InferenceLifecycleManager initialization (the AI stack), cascading
     * through the existing {@code OnlineAiService.unavailable()} fallback in
     * {@code HeadAssembly}. Equivalent in effect to {@code JUSTSEARCH_AI_DISABLED=true}
     * but named for the test-harness use case so future test-mode skips have an obvious home.
     *
     * <p>Saves ~3-8s of startup time depending on hardware (avoids llama-server probe and
     * model-file checks). Used by the per-class isolated test backend fixture
     * ({@code IsolatedBackendFixture}, T6.2) so integration tests can spawn a backend in
     * single-digit seconds.
     */
    LITE_MODE("justsearch.lite.mode", "JUSTSEARCH_LITE_MODE", "false", LifecycleStage.PERMANENT),

    /**
     * Retention window (in days) for entries in the {@code path_resolution} table after a
     * file's deletion has been observed. ADR-0028 / tempdoc 419 T5.1. After this window
     * elapses, rows with non-null {@code removed_at} are pruned by the periodic job-cleanup
     * task. The default of 90 days lets the activity panel still answer "this file was
     * deleted on X" for recently-removed entries while keeping the table size bounded.
     * Removing a watched root immediately prunes everything under it regardless of retention.
     */
    PATH_RESOLUTION_RETENTION_DAYS(
        "justsearch.path_resolution.retention_days",
        "JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS",
        "90", LifecycleStage.PERMANENT),

    // ==================== VDU Configuration ====================

    /**
     * Quality score threshold for VDU routing. PDFs with extraction quality
     * below this threshold are queued for VLM re-extraction. Range: 0.0–1.0.
     * Default 0.3 (conservative). Experiments showed VLM improves pages up to 0.7.
     */
    VDU_QUALITY_THRESHOLD(
        "justsearch.vdu.quality_threshold", "JUSTSEARCH_VDU_QUALITY_THRESHOLD", LifecycleStage.PERMANENT),

    // ==================== SPLADE Configuration ====================

    /** Enable SPLADE sparse retrieval (auto if model found). */
    SPLADE_ENABLED("justsearch.splade.enabled", "JUSTSEARCH_SPLADE_ENABLED", LifecycleStage.PERMANENT),

    /** Explicit path to SPLADE model directory. */
    SPLADE_MODEL_PATH("justsearch.splade.model_path", "JUSTSEARCH_SPLADE_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Max token sequence length for SPLADE inference (default 512). */
    SPLADE_MAX_SEQ_LEN("justsearch.splade.max_seq_len", "JUSTSEARCH_SPLADE_MAX_SEQ_LEN", LifecycleStage.PERMANENT),

    /** SPLADE query encoding mode: "onnx" (neural) or "idf" (IDF-weighted lookup). */
    SPLADE_QUERY_MODE("justsearch.splade.query_mode", "JUSTSEARCH_SPLADE_QUERY_MODE", LifecycleStage.PERMANENT),

    /** SPLADE post-processing activation: "log1p" or "double_log1p". */
    SPLADE_ACTIVATION("justsearch.splade.activation", "JUSTSEARCH_SPLADE_ACTIVATION", LifecycleStage.PERMANENT),

    /** Path to SPLADE truncation evidence directory. */
    SPLADE_EVIDENCE_PATH("justsearch.splade.evidence_path", "JUSTSEARCH_SPLADE_EVIDENCE_PATH", LifecycleStage.PERMANENT),

    // ==================== BGE-M3 Configuration ====================

    /** Sparse retrieval model selection: "splade" (default) or "bge-m3". */
    SPARSE_MODEL("justsearch.sparse_model", "JUSTSEARCH_SPARSE_MODEL", LifecycleStage.PERMANENT),

    /** Enable BGE-M3 sparse+dense retrieval (auto if model found). */
    BGE_M3_ENABLED("justsearch.bgem3.enabled", "JUSTSEARCH_BGE_M3_ENABLED", LifecycleStage.PERMANENT),

    /** Explicit path to BGE-M3 model directory. */
    BGE_M3_MODEL_PATH("justsearch.bgem3.model_path", "JUSTSEARCH_BGE_M3_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Max token sequence length for BGE-M3 inference (default 8192). */
    BGE_M3_MAX_SEQ_LEN("justsearch.bgem3.max_seq_len", "JUSTSEARCH_BGE_M3_MAX_SEQ_LEN", LifecycleStage.PERMANENT),

    /** Enable GPU acceleration for BGE-M3 inference (default false). */
    BGE_M3_GPU_ENABLED("justsearch.bgem3.gpu_enabled", "JUSTSEARCH_BGE_M3_GPU_ENABLED", LifecycleStage.PERMANENT),

    /** CUDA device ID for BGE-M3 inference (default 0). */
    BGE_M3_GPU_DEVICE_ID("justsearch.bgem3.gpu_device_id", "JUSTSEARCH_BGE_M3_GPU_DEVICE_ID", LifecycleStage.PERMANENT),

    /** GPU memory arena limit for BGE-M3 sessions (MB, default 3072). */
    BGE_M3_GPU_MEM_MB("justsearch.bgem3.gpu_mem_mb", "JUSTSEARCH_BGE_M3_GPU_MEM_MB", LifecycleStage.PERMANENT),

    // ==================== Backfill Pacing Configuration (tempdoc 710 Wave-1.5 Move 4) ====================
    // Advanced/tuning knobs — converts the LoopPacingPolicy / BackfillScheduler /
    // CombinedEnrichmentBackfillOps pacing constants to a config surface. Defaults are
    // byte-identical to the pre-Move-4 hardcoded literals; see ResolvedConfig.Ai.BackfillPacing
    // for per-field derivation notes.

    /**
     * Primary-indexing job-queue poll batch size (default 16 — tempdoc 278 Phase 1 item 1b:
     * raised from 1 to amortize per-batch queue overhead).
     */
    BACKFILL_POLL_BATCH_SIZE(
        "justsearch.backfill.poll_batch_size", "JUSTSEARCH_BACKFILL_POLL_BATCH_SIZE", "16", LifecycleStage.PERMANENT),

    /** Doc-count per embedding backfill batch, parent and chunk (default 100). */
    BACKFILL_EMBEDDING_BATCH_SIZE(
        "justsearch.backfill.embedding_batch_size",
        "JUSTSEARCH_BACKFILL_EMBEDDING_BATCH_SIZE",
        "100", LifecycleStage.PERMANENT),

    /** Doc-count per NER backfill batch (default 100). */
    BACKFILL_NER_BATCH_SIZE(
        "justsearch.backfill.ner_batch_size", "JUSTSEARCH_BACKFILL_NER_BATCH_SIZE", "100", LifecycleStage.PERMANENT),

    /** Doc-count per disambiguation backfill batch (default 500). */
    BACKFILL_DISAMBIGUATION_BATCH_SIZE(
        "justsearch.backfill.disambiguation_batch_size",
        "JUSTSEARCH_BACKFILL_DISAMBIGUATION_BATCH_SIZE",
        "500", LifecycleStage.PERMANENT),

    /** Doc-count per idle-branch SPLADE backfill batch (default 200). */
    BACKFILL_SPLADE_BATCH_SIZE(
        "justsearch.backfill.splade_batch_size", "JUSTSEARCH_BACKFILL_SPLADE_BATCH_SIZE", "200", LifecycleStage.PERMANENT),

    /**
     * Doc-count per SPLADE batch interleaved into primary indexing (default 10 — tempdoc 278
     * Phase 4c; smaller than the idle-branch batch so interleaving stays cheap).
     */
    BACKFILL_SPLADE_INTERLEAVE_BATCH_SIZE(
        "justsearch.backfill.splade_interleave_batch_size",
        "JUSTSEARCH_BACKFILL_SPLADE_INTERLEAVE_BATCH_SIZE",
        "10", LifecycleStage.PERMANENT),

    /**
     * Minimum time (ms) between interleaved SPLADE/BGE-M3 batches during primary indexing
     * (default 5000 — tempdoc 278 Phase 4a; time-gated to limit primary-indexing overhead to
     * ~13%).
     */
    BACKFILL_SPLADE_INTERLEAVE_INTERVAL_MS(
        "justsearch.backfill.splade_interleave_interval_ms",
        "JUSTSEARCH_BACKFILL_SPLADE_INTERLEAVE_INTERVAL_MS",
        "5000", LifecycleStage.PERMANENT),

    /**
     * Time-based commit trigger interval in ms (default 10000). Despite the {@code backfill.} key,
     * this is read by {@code LoopPacingPolicy.isTimeCommitTriggered} for the PRIMARY indexing loop;
     * no backfill op reads it (tempdoc 912 §E — the mislabelling is what led 885's A3 arm to
     * believe it had relaxed backfill commits when it had relaxed none).
     */
    BACKFILL_COMMIT_INTERVAL_MS(
        "justsearch.backfill.commit_interval_ms",
        "JUSTSEARCH_BACKFILL_COMMIT_INTERVAL_MS",
        "10000", LifecycleStage.PERMANENT),

    /**
     * Buffer-based commit trigger: doc count since last commit (default 1000). Despite the
     * {@code backfill.} key, this is read by {@code LoopPacingPolicy.isBufferCommitTriggered} for
     * the PRIMARY indexing loop; no backfill op reads it (tempdoc 912 §E).
     */
    BACKFILL_MAX_DOCS_BEFORE_COMMIT(
        "justsearch.backfill.max_docs_before_commit",
        "JUSTSEARCH_BACKFILL_MAX_DOCS_BEFORE_COMMIT",
        "1000", LifecycleStage.PERMANENT),

    /**
     * Chunk-doc cache slots populated per combined-backfill batch (default 50). Tempdoc 691 §F-1
     * measured this cap is NOT the dense-corpus chunk-only-tail throughput lever (that tail is
     * GPU-embedding-compute-bound, not cap-throttled) — this knob exists for experimentation, not
     * because raising it is known to help.
     */
    BACKFILL_CHUNK_SLOTS_PER_BATCH(
        "justsearch.backfill.chunk_slots_per_batch",
        "JUSTSEARCH_BACKFILL_CHUNK_SLOTS_PER_BATCH",
        "50", LifecycleStage.EXPERIMENTAL),

    /**
     * Doc-count per idle-branch BGE-M3 backfill batch (default 50). Previously a stray literal in
     * {@code BackfillScheduler} that bypassed {@code LoopPacingPolicy} entirely; unified here.
     */
    BACKFILL_BGE_M3_BATCH_SIZE(
        "justsearch.backfill.bge_m3_batch_size",
        "JUSTSEARCH_BACKFILL_BGE_M3_BATCH_SIZE",
        "50", LifecycleStage.PERMANENT),

    /** Doc-count per BGE-M3 batch interleaved into primary indexing (default 10). */
    BACKFILL_BGE_M3_INTERLEAVE_BATCH_SIZE(
        "justsearch.backfill.bge_m3_interleave_batch_size",
        "JUSTSEARCH_BACKFILL_BGE_M3_INTERLEAVE_BATCH_SIZE",
        "10", LifecycleStage.PERMANENT),

    // ==================== Model Capability Contract (tempdoc 710 Wave 2 Move 1) ====================

    /**
     * When {@code true}, a model-capability fact ({@code io.justsearch.ort.ModelCapabilities} —
     * pooling mode, trained context length, embedding dimension, precision, prefixes) left
     * undeclared by every source (manifest {@code capabilities}, sentence-transformers ecosystem
     * files, legacy sidecar) is a startup failure for that encoder lane instead of a WARN +
     * documented fallback (TEI fail-closed precedent, tempdoc 710 S-C.R). Default {@code false} —
     * held off until tempdoc 657 ships capability manifests inside install packs; flipping true
     * today would fail every lane whose model directory predates Wave 2's authored manifests.
     */
    CAPABILITY_CONTRACT_STRICT(
        "justsearch.models.capability_contract_strict",
        "JUSTSEARCH_MODELS_CAPABILITY_CONTRACT_STRICT",
        "false", LifecycleStage.EXPERIMENTAL),

    // ==================== Reranker Configuration ====================

    /** Enable document reranking (auto if model found). */
    RERANK_ENABLED("justsearch.rerank.enabled", "JUSTSEARCH_RERANK_ENABLED", LifecycleStage.PERMANENT),

    /** Top-K documents to rerank (default 20). */
    RERANK_TOP_K("justsearch.rerank.top_k", "JUSTSEARCH_RERANK_TOP_K", "20", LifecycleStage.PERMANENT),

    /** Reranker deadline in milliseconds (default 200). */
    RERANK_DEADLINE_MS("justsearch.rerank.deadline_ms", "JUSTSEARCH_RERANK_DEADLINE_MS", "200", LifecycleStage.PERMANENT),

    /** Minimum hits before reranking is attempted (default 5). */
    RERANK_MIN_HITS("justsearch.rerank.min_hits", "JUSTSEARCH_RERANK_MIN_HITS", "5", LifecycleStage.PERMANENT),

    /** Max token sequence length for reranker inference (default 512; model supports 8192 but O(n²) attention cost and GPU VRAM make that impractical). */
    RERANK_MAX_SEQ_LEN("justsearch.rerank.max_seq_len", "JUSTSEARCH_RERANK_MAX_SEQ_LEN", "512", LifecycleStage.PERMANENT),

    /**
     * Max average document length in chars for reranker eligibility (default 0 = gate disabled).
     * Tempdoc 774 §J.2/§K live probe (run 96da7851): the {@code DOCS_TOO_LONG} gate reads a
     * Head-side cache populated ONLY by {@code GET /api/knowledge/status}, which evals never poll,
     * so every register/eval baseline measured the CE-on (gate-off) pipeline while a production
     * session whose client polls {@code /api/knowledge/status} silently loses the CE on long-doc
     * corpora. Default flipped 16000 → 0 so production matches the measured configuration; an
     * operator can still set &gt;0 to restore the gate.
     */
    RERANK_MAX_AVG_DOC_LENGTH_CHARS(
        "justsearch.rerank.max_avg_doc_length_chars",
        "JUSTSEARCH_RERANK_MAX_AVG_DOC_LENGTH_CHARS",
        "0", LifecycleStage.PERMANENT),

    /**
     * Tempdoc 643: judge-stage refinement floor — blend the cross-encoder's reorder with the
     * pre-rerank (fusion/LambdaMART) order instead of letting the CE replace it outright, so a
     * low-confidence/wrong CE call cannot regress a hit past what the blend weight allows (D-004's
     * arbitration shape applied to the judge stage). Default off; D-004 went default-off ->
     * measured -> default-on, same template.
     */
    RERANK_JUDGE_BLEND_ENABLED(
        "justsearch.rerank.judge_blend_enabled",
        "JUSTSEARCH_RERANK_JUDGE_BLEND_ENABLED",
        "false", LifecycleStage.EXPERIMENTAL),

    /**
     * Tempdoc 643: weight on the fusion-stage score in the judge blend, in [0,1]. 1.0 = ignore the
     * CE (pure fusion order); 0.0 = today's CE-replaces-fusion behavior. Only consulted when
     * {@link #RERANK_JUDGE_BLEND_ENABLED} is true.
     */
    RERANK_JUDGE_BLEND_ALPHA(
        "justsearch.rerank.judge_blend_alpha",
        "JUSTSEARCH_RERANK_JUDGE_BLEND_ALPHA",
        "0.5", LifecycleStage.EXPERIMENTAL),

    /**
     * Tempdoc 643 (E1/E2): controls TWO independent effects, each with its own dependency:
     *
     * <ol>
     *   <li>Compute the judge-blend alpha per query from a runtime confidence signal (CE margin +
     *       leg-agreement — {@code KnowledgeSearchEngine.computeJudgeArbitrationAlpha}) instead of
     *       reading the static {@link #RERANK_JUDGE_BLEND_ALPHA}. The CE-side instance of D-004's
     *       {@code index.hybrid.leg_arbitration_enabled} shape. This effect requires {@link
     *       #RERANK_JUDGE_BLEND_ENABLED}; when that is false, {@code judge_blend_alpha} is used
     *       unchanged (byte-identical to the pre-arbitration behavior).
     *   <li>Gate {@link #RERANK_JUDGE_ARBITRATION_SKIP_ENABLED} (perf-skip). This effect is
     *       INDEPENDENT of {@link #RERANK_JUDGE_BLEND_ENABLED} — perf-skip decides whether to call
     *       the cross-encoder at all, so it does not need the blend to be enabled to fire (critical-
     *       analysis-pass finding: an earlier version of this javadoc stated a blanket "requires
     *       judge_blend_enabled" that only actually held for effect 1).
     * </ol>
     *
     * Default off; same default-off -> measured -> default-on template.
     */
    RERANK_JUDGE_ARBITRATION_ENABLED(
        "justsearch.rerank.judge_arbitration_enabled",
        "JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ENABLED",
        "false", LifecycleStage.EXPERIMENTAL),

    /**
     * Tempdoc 643 (E1/E2): alpha to use when the arbitration gate decides fusion is decisive and the
     * CE is not confident (protect via fusion). Mirrors {@code index.hybrid.leg_arbitration_alpha_diverge}
     * but in the opposite direction (D-004 raises alpha toward dense; this raises it toward fusion).
     * Only consulted when {@link #RERANK_JUDGE_ARBITRATION_ENABLED} is true.
     */
    RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE(
        "justsearch.rerank.judge_arbitration_alpha_diverge",
        "JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE",
        "0.85", LifecycleStage.EXPERIMENTAL),

    /**
     * Tempdoc 643 (perf-skip): when the arbitration gate says fusion is decisive, skip the
     * cross-encoder RPC entirely instead of just re-weighting its influence (the 643<->648 perf/quality
     * unification). Kept as a SEPARATE flag from {@link #RERANK_JUDGE_ARBITRATION_ENABLED}: skipping the
     * CE call (vs. re-weighting it) can lose a query the CE would have fixed — a sharper risk than
     * re-weighting — so it needs its own acceptance evidence, not a free ride on the blend's. Requires
     * {@link #RERANK_JUDGE_ARBITRATION_ENABLED}. Default off.
     */
    RERANK_JUDGE_ARBITRATION_SKIP_ENABLED(
        "justsearch.rerank.judge_arbitration_skip_enabled",
        "JUSTSEARCH_RERANK_JUDGE_ARBITRATION_SKIP_ENABLED",
        "false", LifecycleStage.EXPERIMENTAL),

    /** Enable chunk reranking (auto if model found). */
    RERANK_CHUNKS_ENABLED(
        "justsearch.rerank.chunks.enabled", "JUSTSEARCH_RERANK_CHUNKS_ENABLED", LifecycleStage.PERMANENT),

    /** Top-K chunks to rerank (default 10). */
    RERANK_CHUNKS_TOP_K("justsearch.rerank.chunks.top_k", "JUSTSEARCH_RERANK_CHUNKS_TOP_K", "10", LifecycleStage.PERMANENT),

    /** Max GPU candidates for chunk reranker (default 50). */
    RERANK_CHUNKS_MAX_GPU_CANDIDATES(
        "justsearch.rerank.chunks.max_gpu_candidates",
        "JUSTSEARCH_RERANK_CHUNKS_MAX_GPU_CANDIDATES",
        "50", LifecycleStage.PERMANENT),

    /** Chunk reranker deadline in milliseconds (default 150). */
    RERANK_CHUNKS_DEADLINE_MS(
        "justsearch.rerank.chunks.deadline_ms", "JUSTSEARCH_RERANK_CHUNKS_DEADLINE_MS", "150", LifecycleStage.PERMANENT),

    /** Minimum hits before chunk reranking is attempted (default 3). */
    RERANK_CHUNKS_MIN_HITS(
        "justsearch.rerank.chunks.min_hits", "JUSTSEARCH_RERANK_CHUNKS_MIN_HITS", "3", LifecycleStage.PERMANENT),

    /** Max token sequence length for chunk reranker (default 512). */
    RERANK_CHUNKS_MAX_SEQ_LEN(
        "justsearch.rerank.chunks.max_seq_len", "JUSTSEARCH_RERANK_CHUNKS_MAX_SEQ_LEN", "512", LifecycleStage.PERMANENT),

    /** Chunk reranker result ordering: "auto", "score", or "position" (default "auto"). */
    RERANK_CHUNKS_ORDER("justsearch.rerank.chunks.order", "JUSTSEARCH_RERANK_CHUNKS_ORDER", "auto", LifecycleStage.PERMANENT),

    // ==================== Citation Scorer Configuration ====================

    /** Enable citation scorer (auto if model found). */
    CITATION_SCORER_ENABLED(
        "justsearch.citation.scorer.enabled", "JUSTSEARCH_CITATION_SCORER_ENABLED", LifecycleStage.PERMANENT),

    /** Explicit path to citation scorer model directory. */
    CITATION_SCORER_MODEL_PATH(
        "justsearch.citation.scorer.model_path", "JUSTSEARCH_CITATION_SCORER_MODEL_PATH", LifecycleStage.PERMANENT),

    /** Citation scorer confidence threshold (default 0.5). */
    CITATION_SCORER_THRESHOLD(
        "justsearch.citation.scorer.threshold", "JUSTSEARCH_CITATION_SCORER_THRESHOLD", LifecycleStage.PERMANENT),

    /** Max token sequence length for citation scorer (default 512). */
    CITATION_SCORER_MAX_SEQ_LEN(
        "justsearch.citation.scorer.max_seq_len", "JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN", LifecycleStage.PERMANENT),

    /** Citation scorer deadline in milliseconds (default 2000). */
    CITATION_SCORER_DEADLINE_MS(
        "justsearch.citation.scorer.deadline_ms", "JUSTSEARCH_CITATION_SCORER_DEADLINE_MS", LifecycleStage.PERMANENT),

    // ==================== Misc Subsystem Configuration ====================

    /** Embedding context length override (default 2048). */
    EMBED_CONTEXT_LENGTH(
        "justsearch.embed.context_length", "JUSTSEARCH_EMBED_CONTEXT_LENGTH", LifecycleStage.PERMANENT),

    /** GPL revalidation size factor (default 2.0). */
    GPL_REEVAL_SIZE_FACTOR(
        "justsearch.gpl.reeval_size_factor", "JUSTSEARCH_GPL_REEVAL_SIZE_FACTOR", LifecycleStage.PERMANENT),

    // ==================== Worker Indexer Connection (tempdoc 314 C1) ====================

    /** Whether the indexer worker gRPC client is enabled. */
    INDEXER_ENABLED("workers.indexer.enabled", "JUSTSEARCH_INDEXER_ENABLED", LifecycleStage.PERMANENT),

    /** Indexer worker client host (gRPC connection from Head). */
    INDEXER_HOST("justsearch.indexer.host", "JUSTSEARCH_INDEXER_HOST", LifecycleStage.PERMANENT),

    /** Indexer worker client port (gRPC connection from Head). */
    INDEXER_PORT("justsearch.indexer.port", "JUSTSEARCH_INDEXER_PORT", LifecycleStage.PERMANENT),

    /** Indexer worker client deadline (ms). */
    INDEXER_DEADLINE_MS("justsearch.indexer.deadlineMs", "JUSTSEARCH_INDEXER_DEADLINE_MS", LifecycleStage.PERMANENT),

    /** Indexer worker ingest queue size. */
    INDEXER_QUEUE_SIZE("justsearch.indexer.queueSize", "JUSTSEARCH_INDEXER_QUEUE_SIZE", LifecycleStage.PERMANENT),

    /** Indexer worker max in-flight bytes. */
    INDEXER_MAX_INFLIGHT_BYTES(
        "justsearch.indexer.maxInFlightBytes", "JUSTSEARCH_INDEXER_MAX_INFLIGHT_BYTES", LifecycleStage.PERMANENT),

    // ==================== Infra Health (tempdoc 314 Phase F) ====================

    /** Infra health gRPC server host. */
    INFRA_HEALTH_HOST("justsearch.infra.health.host", "JUSTSEARCH_INFRA_HEALTH_HOST", LifecycleStage.PERMANENT),
    /** Infra health gRPC server port. */
    INFRA_HEALTH_PORT("justsearch.infra.health.port", "JUSTSEARCH_INFRA_HEALTH_PORT", LifecycleStage.PERMANENT),

    // ==================== Indexing Tracing (tempdoc 312 Phase 0) ====================

    /** Indexing pipeline tracing level: none (default), sample (1%), detailed (100%). */
    INDEX_TRACING_LEVEL("justsearch.index.tracing_level", "JUSTSEARCH_INDEX_TRACING_LEVEL", LifecycleStage.PERMANENT),

    /**
     * Head process tracing level: none (default), sample (1%), detailed (100%). Tempdoc 518
     * Appendix G W4.2 — when non-none, the head's {@code HeadlessApp} initializes a
     * {@link io.justsearch.telemetry.TracingBootstrap} so the existing span-authoring code
     * ({@code AgentLoopService}, {@code KnowledgeHttpApiAdapter}) emits to the NDJSON
     * exporter + optional OTLP fan-out. Required for tempdoc 518's
     * {@code justsearch.inference.generation} span attribute to attach to exported spans.
     */
    HEAD_TRACING_LEVEL("justsearch.head.tracing_level", "JUSTSEARCH_HEAD_TRACING_LEVEL", LifecycleStage.PERMANENT),

    // ==================== Dev Hot-Reload (tempdoc 305 Phase 2) ====================

    /** Enables dev hot-reload service restart on recompile (default false). */
    DEV_HOTRELOAD("justsearch.dev.hotreload", "JUSTSEARCH_DEV_HOTRELOAD", LifecycleStage.PERMANENT),

    /** Path to worker-services classes directory (dev only). */
    DEV_HOTRELOAD_CLASSES_DIR(
        "justsearch.dev.hotreload.classesDir", "JUSTSEARCH_DEV_HOTRELOAD_CLASSES_DIR", LifecycleStage.PERMANENT),

    /** JDWP debug port for HotSwapPush bytecode updates (default 5005). */
    DEV_DEBUG_PORT("justsearch.dev.debug.port", "JUSTSEARCH_DEV_DEBUG_PORT", LifecycleStage.PERMANENT),

    /** 371: Content hash of the Worker distribution (stale-JVM detection). */
    BUILD_STAMP("justsearch.build.stamp", "JUSTSEARCH_BUILD_STAMP", LifecycleStage.PERMANENT),

    /**
     * Tempdoc 606 Piece 2b: content hash of the Head distribution the dev-runner launched.
     * Injected by the dev-runner ({@code -Djustsearch.head.stamp}); echoed on
     * {@code /api/runtime/manifest} (HeadInfo.buildStamp) so a dev tool can detect a stale
     * Head answering on a reused port. Dev-only; absent in production.
     */
    HEAD_BUILD_STAMP("justsearch.head.stamp", "JUSTSEARCH_HEAD_BUILD_STAMP", LifecycleStage.PERMANENT),

    // ==================== Worker Bootstrap (tempdoc 329) ====================

    /** Path to worker config snapshot JSON (set by HeadlessApp at runtime). */
    WORKER_CONFIG_SNAPSHOT(
        "justsearch.worker.config_snapshot", "JUSTSEARCH_WORKER_CONFIG_SNAPSHOT", LifecycleStage.PERMANENT),

    /**
     * Main (Head) process PID, forwarded Head→Worker so the Worker can probe Head liveness and
     * distinguish a real Head death from a benign OS-resume stale heartbeat (tempdoc 630). Absent
     * on standalone worker runs, where the Worker falls back to heartbeat-only suicide.
     */
    HEAD_PID("justsearch.head.pid", "JUSTSEARCH_HEAD_PID", LifecycleStage.PERMANENT),

    /**
     * Dev/test override for the OS energy-intent poll (tempdoc 630): {@code reduced} or {@code full}
     * forces the energy state, bypassing the {@code GetSystemPowerStatus} probe so the throttle +
     * "Paused" UI can be exercised on AC / without toggling Windows Energy Saver. Empty = probe.
     */
    POWER_FORCE_ENERGY_STATE("justsearch.power.force_energy_state", "JUSTSEARCH_POWER_FORCE_ENERGY_STATE", LifecycleStage.PERMANENT),

    // ==================== Index Vector HNSW (tempdoc 347 D2: sysProp = configKey) ====================

    /** HNSW M parameter (number of connections per node). */
    INDEX_VECTOR_HNSW_M("index.vector.hnsw.m", "JUSTSEARCH_INDEX_VECTOR_HNSW_M", LifecycleStage.PERMANENT),
    /** HNSW ef_construction parameter. */
    INDEX_VECTOR_HNSW_EF_CONSTRUCTION("index.vector.hnsw.ef_construction",
        "JUSTSEARCH_INDEX_VECTOR_HNSW_EF_CONSTRUCTION", LifecycleStage.PERMANENT),
    /** HNSW ef_search parameter for query-time. */
    INDEX_VECTOR_EF_SEARCH("index.vector.ef_search", "JUSTSEARCH_INDEX_VECTOR_EF_SEARCH", LifecycleStage.PERMANENT),
    /** Enable vector quantization for index storage. */
    INDEX_VECTOR_QUANTIZATION_ENABLED("index.vector.quantization.enabled",
        "JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED", LifecycleStage.PERMANENT),

    // ==================== RAG Pipeline (tempdoc 347 D2: sysProp = configKey) ====================

    /** RAG retrieval mode (hybrid, vector, text). */
    RAG_RETRIEVE_MODE("rag.retrieve.mode", "JUSTSEARCH_RAG_RETRIEVE_MODE", LifecycleStage.PERMANENT),
    /** RAG over-retrieval factor for diverse sampling. */
    RAG_OVERRETRIEVE_FACTOR("rag.retrieve.overretrieve_factor", "JUSTSEARCH_RAG_OVERRETRIEVE_FACTOR", LifecycleStage.PERMANENT),
    /** RAG diversification mode (none, mmr). */
    RAG_DIVERSIFY_MODE("rag.diversify.mode", "JUSTSEARCH_RAG_DIVERSIFY_MODE", LifecycleStage.PERMANENT),
    /** MMR lambda parameter for diversity-relevance trade-off. */
    RAG_MMR_LAMBDA("rag.mmr.lambda", "JUSTSEARCH_RAG_MMR_LAMBDA", LifecycleStage.PERMANENT),
    /** MMR max candidate pool size. */
    RAG_MMR_MAX_CANDIDATES("rag.mmr.max_candidates", "JUSTSEARCH_RAG_MMR_MAX_CANDIDATES", LifecycleStage.PERMANENT),
    /** Enable chunk-level vector retrieval for RAG. */
    RAG_CHUNK_VECTORS_ENABLED("rag.chunk_vectors.enabled", "JUSTSEARCH_RAG_CHUNK_VECTORS_ENABLED", LifecycleStage.PERMANENT),
    /**
     * Controls the chunk-SPLADE stage on BOTH the write side (backfill lanes encode chunk docs'
     * {@code chunk_content}, and {@code ChunkDocumentWriter} enrols the chunk on
     * {@code splade_status}) and the query-side leg ({@code SearchExecutor}'s chunk-SPLADE
     * retrieval leg). Tempdoc 712; default false, evidence-gated (931 §E item 8).
     */
    RAG_CHUNK_SPLADE_ENABLED("rag.chunk_splade.enabled", "JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED", LifecycleStage.PERMANENT),
    /** Enable the RAG doc-level union leg for chunkless docs (tempdoc 749; default true). */
    RAG_UNION_ENABLED("rag.union.enabled", "JUSTSEARCH_RAG_UNION_ENABLED", LifecycleStage.PERMANENT),

    // ==================== Worker Limits (tempdoc 347 D2: sysProp = configKey) ====================

    /** Max content length per document (characters). */
    WORKER_MAX_CONTENT_LENGTH("worker.limits.max_content_length",
        "JUSTSEARCH_WORKER_MAX_CONTENT_LENGTH", LifecycleStage.PERMANENT),
    /** Max file size for ingestion (bytes). */
    WORKER_MAX_FILE_SIZE("worker.limits.max_file_size", "JUSTSEARCH_WORKER_MAX_FILE_SIZE", LifecycleStage.PERMANENT),

    // ==================== Hybrid Search (tempdoc 347 D2: sysProp = configKey) ====================

    /** RRF K constant for reciprocal rank fusion. */
    HYBRID_RRF_K("index.hybrid.rrf_k", "JUSTSEARCH_INDEX_RRF_K", LifecycleStage.PERMANENT),
    /** Min chars before vector search is skipped (short-query optimization). */
    HYBRID_VECTOR_SKIP_MIN_CHARS("index.hybrid.vector_skip_min_chars",
        "JUSTSEARCH_INDEX_VECTOR_SKIP_MIN_CHARS", LifecycleStage.PERMANENT),
    /** Minimum content-field document-frequency fraction for skipping a redundant dense leg. */
    HYBRID_VECTOR_SKIP_MIN_DF_FRACTION(
        "index.hybrid.vector_skip_min_df_fraction",
        "JUSTSEARCH_INDEX_VECTOR_SKIP_MIN_DF_FRACTION",
        LifecycleStage.PERMANENT),
    /** Max candidate limit for hybrid search results. */
    HYBRID_CANDIDATE_LIMIT_MAX("index.hybrid.candidate_limit_max",
        "JUSTSEARCH_INDEX_HYBRID_CANDIDATE_LIMIT_MAX", LifecycleStage.PERMANENT),
    /** Text candidate multiplier for over-retrieval. */
    HYBRID_TEXT_CANDIDATE_MULTIPLIER("index.hybrid.text_candidate_multiplier",
        "JUSTSEARCH_INDEX_HYBRID_TEXT_CANDIDATE_MULTIPLIER", LifecycleStage.PERMANENT),
    /** Vector candidate multiplier for over-retrieval. */
    HYBRID_VECTOR_CANDIDATE_MULTIPLIER("index.hybrid.vector_candidate_multiplier",
        "JUSTSEARCH_INDEX_HYBRID_VECTOR_CANDIDATE_MULTIPLIER", LifecycleStage.PERMANENT),
    /** Vector RRF weight in normal-signal fusion. */
    HYBRID_VECTOR_RRF_WEIGHT("index.hybrid.vector_rrf_weight",
        "JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT", LifecycleStage.PERMANENT),
    /** BM25 score boost weight. */
    HYBRID_BM25_SCORE_BOOST_WEIGHT("index.hybrid.bm25_score_boost_weight",
        "JUSTSEARCH_INDEX_BM25_SCORE_BOOST_WEIGHT", LifecycleStage.PERMANENT),
    /** Vector low-signal top score threshold. */
    HYBRID_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD("index.hybrid.vector_low_signal_top_score_threshold",
        "JUSTSEARCH_INDEX_VECTOR_LOW_SIGNAL_TOP_SCORE_THRESHOLD", LifecycleStage.PERMANENT),
    /** BM25 low-signal top score threshold. */
    HYBRID_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD("index.hybrid.bm25_low_signal_top_score_threshold",
        "JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOP_SCORE_THRESHOLD", LifecycleStage.PERMANENT),
    /** BM25 low-signal total hits threshold. */
    HYBRID_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD("index.hybrid.bm25_low_signal_total_hits_threshold",
        "JUSTSEARCH_INDEX_BM25_LOW_SIGNAL_TOTAL_HITS_THRESHOLD", LifecycleStage.PERMANENT),
    /** Vector-only cap for low-signal scenarios. */
    HYBRID_VECTOR_ONLY_CAP_LOW_SIGNAL("index.hybrid.vector_only_cap_low_signal",
        "JUSTSEARCH_INDEX_VECTOR_ONLY_CAP_LOW_SIGNAL", LifecycleStage.PERMANENT),
    /** Vector RRF weight in low-signal scenarios. */
    HYBRID_VECTOR_RRF_WEIGHT_LOW_SIGNAL("index.hybrid.vector_rrf_weight_low_signal",
        "JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT_LOW_SIGNAL", LifecycleStage.PERMANENT),
    /** Fusion strategy (rrf, cc). */
    HYBRID_FUSION_STRATEGY("index.hybrid.fusion_strategy", "JUSTSEARCH_HYBRID_FUSION_STRATEGY", LifecycleStage.PERMANENT),
    /** CC fusion alpha parameter. */
    HYBRID_CC_ALPHA("index.hybrid.cc_alpha", "JUSTSEARCH_HYBRID_CC_ALPHA", LifecycleStage.PERMANENT),
    /** CC fusion: exclude zero-score channels. */
    HYBRID_CC_ZERO_EXCLUDE("index.hybrid.cc_zero_exclude", "JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE", LifecycleStage.PERMANENT),
    /** CC fusion: sparse channel weight. */
    HYBRID_CC_WEIGHT_SPARSE("index.hybrid.cc_weight_sparse", "JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE", LifecycleStage.PERMANENT),
    /** CC fusion: dense channel weight. */
    HYBRID_CC_WEIGHT_DENSE("index.hybrid.cc_weight_dense", "JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE", LifecycleStage.PERMANENT),
    /** CC fusion: SPLADE channel weight. */
    HYBRID_CC_WEIGHT_SPLADE("index.hybrid.cc_weight_splade", "JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE", LifecycleStage.PERMANENT),
    /** Tempdoc 580 §13.3: per-query adaptive CC-weight selection (default off). */
    HYBRID_ADAPTIVE_WEIGHTS_ENABLED(
        "index.hybrid.adaptive_weights_enabled", "JUSTSEARCH_HYBRID_ADAPTIVE_WEIGHTS_ENABLED", LifecycleStage.EXPERIMENTAL),
    /**
     * Tempdoc 636 Design v2: per-query leg arbitration — raise the 2-way CC dense weight (alpha)
     * when dense is bounded-confident AND the legs diverge (low cross-leg rank overlap), so the
     * lexical leg cannot suppress a confident dense answer on grep-defeating paraphrase queries
     * (default off; static alpha wins).
     */
    HYBRID_LEG_ARBITRATION_ENABLED(
        "index.hybrid.leg_arbitration_enabled", "JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED", LifecycleStage.PERMANENT),
    /** Tempdoc 636 Design v2: dense weight (CC alpha) applied when leg arbitration fires. */
    HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE(
        "index.hybrid.leg_arbitration_alpha_diverge",
        "JUSTSEARCH_HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE", LifecycleStage.PERMANENT),
    /**
     * Tempdoc 636 review fix: BM25 top2/top1 ratio at/above which BM25 counts as "incoherent" (flat
     * top, no clear lexical winner) — leg arbitration only fires when BM25 is incoherent, so a
     * peaked BM25 winner on BM25-dominant corpora (legal/email) is not down-weighted.
     */
    HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN(
        "index.hybrid.leg_arbitration_bm25_incoherence_min",
        "JUSTSEARCH_HYBRID_LEG_ARBITRATION_BM25_INCOHERENCE_MIN", LifecycleStage.PERMANENT),
    /**
     * Tempdoc 636 Design v3: recall-complete rerank pool — guarantee each retrieval leg's top-N
     * candidates survive into the returned list (the cross-encoder's rerank window), so a confident
     * dense answer that fused-score truncation would bury still reaches the relevance model. Unlike
     * leg arbitration (v2) it never down-weights a leg, so it is keyword-neutral by construction
     * (default off).
     */
    HYBRID_RERANK_POOL_RECALL_COMPLETE(
        "index.hybrid.leg_recall_complete_enabled",
        "JUSTSEARCH_HYBRID_RERANK_POOL_RECALL_COMPLETE", LifecycleStage.PERMANENT),
    /** Tempdoc 636 Design v3: per-leg top-N guaranteed into the recall-complete rerank pool. */
    HYBRID_RERANK_POOL_TOP_N(
        "index.hybrid.leg_recall_complete_top_n", "JUSTSEARCH_HYBRID_RERANK_POOL_TOP_N", LifecycleStage.PERMANENT),
    /** Branch-level fusion strategy. */
    HYBRID_BRANCH_FUSION_STRATEGY("index.hybrid.branch_fusion_strategy",
        "JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY", LifecycleStage.PERMANENT),
    /** Branch CC fusion: exclude zero-score branches. */
    HYBRID_BRANCH_CC_ZERO_EXCLUDE("index.hybrid.branch_cc_zero_exclude",
        "JUSTSEARCH_HYBRID_BRANCH_CC_ZERO_EXCLUDE", LifecycleStage.PERMANENT),
    /** Branch CC fusion: whole-doc weight. */
    HYBRID_BRANCH_CC_WEIGHT_WHOLE("index.hybrid.branch_cc_weight_whole",
        "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE", LifecycleStage.PERMANENT),
    /** Branch CC fusion: chunk weight. */
    HYBRID_BRANCH_CC_WEIGHT_CHUNK("index.hybrid.branch_cc_weight_chunk",
        "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK", LifecycleStage.PERMANENT),
    /** Branch chunk minimum weight multiplier. */
    HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER("index.hybrid.branch_chunk_min_weight_multiplier",
        "JUSTSEARCH_HYBRID_BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER", LifecycleStage.PERMANENT),
    /**
     * Tempdoc 854 W1 (F-036 §K wrong-gate fix): Stage-3B whole-vs-chunk branch-ramp full-weight
     * ceiling — parent token count at/below which the chunk branch keeps its full base weight.
     * Previously this shared {@code justsearch.splade.full_weight_max_tokens} with the unrelated
     * Stage-3A SPLADE parent-length fade, so tuning one silently retuned the other (784 §K:
     * raising the SPLADE bound past a corpus's token range flipped the branch-ramp multiplier
     * 1.0→0.25, a ~4× de-weight of the chunk branch with zero SPLADE involvement). Own bound now;
     * default 1024 reproduces the pre-split shared-constant default byte-for-byte.
     */
    HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS(
        "index.hybrid.branch_ramp.full_weight_max_tokens",
        "JUSTSEARCH_HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS", LifecycleStage.PERMANENT),
    /**
     * Tempdoc 854 W1 (F-036 §K wrong-gate fix): Stage-3B whole-vs-chunk branch-ramp zero-weight
     * floor — parent token count at/above which the chunk branch ramp reaches its minimum
     * multiplier (see {@link #HYBRID_BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS}). Default 4096
     * reproduces the pre-split shared-constant default byte-for-byte.
     */
    HYBRID_BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS(
        "index.hybrid.branch_ramp.zero_weight_min_tokens",
        "JUSTSEARCH_HYBRID_BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-branch CC sparse weight (defaults to the doc-level cc_weight_sparse). */
    HYBRID_CHUNK_CC_WEIGHT_SPARSE("index.hybrid.chunk_cc_weight_sparse",
        "JUSTSEARCH_HYBRID_CHUNK_CC_WEIGHT_SPARSE", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-branch CC dense weight (defaults to the doc-level cc_weight_dense). */
    HYBRID_CHUNK_CC_WEIGHT_DENSE("index.hybrid.chunk_cc_weight_dense",
        "JUSTSEARCH_HYBRID_CHUNK_CC_WEIGHT_DENSE", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-branch CC SPLADE weight (defaults to the doc-level cc_weight_splade). */
    HYBRID_CHUNK_CC_WEIGHT_SPLADE("index.hybrid.chunk_cc_weight_splade",
        "JUSTSEARCH_HYBRID_CHUNK_CC_WEIGHT_SPLADE", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-branch CC zero-exclude (single-leg passage keeps that leg's weight). */
    HYBRID_CHUNK_CC_ZERO_EXCLUDE("index.hybrid.chunk_cc_zero_exclude",
        "JUSTSEARCH_HYBRID_CHUNK_CC_ZERO_EXCLUDE", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-branch collapse cap multiplier (parents = limit × this). */
    HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER("index.hybrid.chunk_collapse_limit_multiplier",
        "JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_LIMIT_MULTIPLIER", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-side recall-complete — splice chunk-leg top-N parents into the pool. */
    HYBRID_CHUNK_LEG_RECALL_COMPLETE_ENABLED("index.hybrid.chunk_leg_recall_complete_enabled",
        "JUSTSEARCH_HYBRID_CHUNK_LEG_RECALL_COMPLETE_ENABLED", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: chunk-side recall-complete per-leg top-N. */
    HYBRID_CHUNK_LEG_RECALL_COMPLETE_TOP_N("index.hybrid.chunk_leg_recall_complete_top_n",
        "JUSTSEARCH_HYBRID_CHUNK_LEG_RECALL_COMPLETE_TOP_N", LifecycleStage.PERMANENT),
    /** Tempdoc 774 Stage 1: when false, the chunk branch runs even when the doc legs return empty. */
    HYBRID_CHUNK_BRANCH_REQUIRES_BASE_RESULTS("index.hybrid.chunk_branch_requires_base_results",
        "JUSTSEARCH_HYBRID_CHUNK_BRANCH_REQUIRES_BASE_RESULTS", LifecycleStage.PERMANENT),
    /**
     * UI exclude patterns (JSON array string). User-side preference, written by the SettingsController
     * Apply Excludes flow and read by IndexingController.applyExcludes + KnowledgeSearchController
     * for search-time exclusion. Tempdoc 519 §9 Block B3.0.b promoted this from raw
     * System.getProperty to EnvRegistry to satisfy the checkNoDirectJustsearchSysProp build gate
     * after ExcludeGlobs moved to app-services.
     */
    UI_EXCLUDE_PATTERNS("justsearch.ui.exclude_patterns", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", LifecycleStage.PERMANENT),
    /**
     * UI settings read-only flag. When true, the UiSettingsStore operates in IN_MEMORY mode
     * regardless of other settings (no disk persistence). Tempdoc 519 §9 Block B3.0.d:
     * promoted from raw System.getenv access after UiSettingsStore moved to app-services.
     */
    UI_SETTINGS_READONLY("justsearch.ui.settings.readOnly", "JUSTSEARCH_UI_SETTINGS_READONLY", LifecycleStage.PERMANENT),
    /**
     * Rule-engine tick interval (milliseconds). Controls how often the RuleRunner evaluates
     * rules. Tempdoc 519 §10 endpoint: extracted from the bootstrap's
     * RuleRunnerBuilder helper to satisfy the checkNoDirectJustsearchSysProp build gate
     * after the rule-engine wiring was lifted into a phase-helper class.
     */
    RULE_TICK_MS("justsearch.rule.tick.ms", "JUSTSEARCH_RULE_TICK_MS", LifecycleStage.PERMANENT),
    /**
     * Running application version, injected by the desktop shell. Read by the upgrade surfaces
     * (LocalApiServer's upgrade reconciliation wiring and HeadlessApp's assembly) to decide whether
     * a durable update intent describes the source build or the target build — the distinction that
     * keeps restart reconciliation from treating a version match as proof of a successful install.
     * Tempdoc 617: promoted from raw System.getProperty to satisfy the
     * checkNoDirectJustsearchSysProp build gate.
     */
    APP_VERSION("justsearch.app.version", "JUSTSEARCH_APP_VERSION", LifecycleStage.PERMANENT),

    // ==================== Append region (tempdoc 883 lane rules) ====================
    // Keep new entries at the END of this enum so parallel lanes merge trivially.

    /**
     * llama-server parallel slots ({@code -np}). Two by default (tempdoc 883 decision 2) so a
     * background delegate cannot evict the foreground turn's prompt-cache prefix — a scheduling
     * choice, not a memory one. Passing {@code -np} explicitly disables llama-server's automatic
     * {@code kv_unified}, which is why {@code -kvu} is passed alongside it.
     */
    LLM_SLOTS("justsearch.llm.slots", "JUSTSEARCH_LLM_SLOTS", "2", LifecycleStage.PERMANENT),

    /**
     * llama-server KV cache type for both K and V ({@code -ctk} / {@code -ctv}). {@code q8_0} by
     * default (tempdoc 883 decision 2); requires flash attention, which is why {@code -fa on} is
     * passed explicitly rather than left to {@code auto}.
     */
    LLM_KV_TYPE("justsearch.llm.kv_type", "JUSTSEARCH_LLM_KV_TYPE", "q8_0", LifecycleStage.PERMANENT),

    /** Number of persistent extraction child processes (default 1, one request in flight each). */
    EXTRACTION_SANDBOX_POOL(
        "justsearch.extraction.sandbox.pool", "JUSTSEARCH_EXTRACTION_SANDBOX_POOL", LifecycleStage.PERMANENT),

    /**
     * Max heap for an extraction child (e.g. {@code 768m}). Default: at least 4x the largest
     * accepted input, floor 512m.
     */
    EXTRACTION_SANDBOX_HEAP(
        "justsearch.extraction.sandbox.heap", "JUSTSEARCH_EXTRACTION_SANDBOX_HEAP", LifecycleStage.PERMANENT),

    /** Requests one extraction child handles before it is recycled (leak guard; default 500). */
    EXTRACTION_SANDBOX_MAX_REQUESTS(
        "justsearch.extraction.sandbox.max_requests",
        "JUSTSEARCH_EXTRACTION_SANDBOX_MAX_REQUESTS", LifecycleStage.PERMANENT),

    // ==================== Foreground-contention pacing (tempdoc 885 item 3) ====================

    /**
     * Minimum share of wall time (1..100, default 20) that indexing and enrichment backfill keep
     * while foreground search-family RPCs are in flight. Replaces the breath-hold pause, which was
     * a full stop and starved indexing to zero under a continuous search loop (885 baseline arm
     * (c)). 100 disables throttling. Resolved onto {@code ResolvedConfig.Ai.BackfillPacing}, so the
     * Worker reads it from the ordinal-450 config snapshot rather than from its own sysprops.
     */
    INDEXING_FOREGROUND_DUTY_PCT(
        "justsearch.indexing.foreground_duty_pct", "JUSTSEARCH_INDEXING_FOREGROUND_DUTY_PCT", "20", LifecycleStage.PERMANENT),

    /**
     * Milliseconds after the last foreground RPC completes during which the Worker still counts as
     * contended (default 500), so a burst of short queries does not read as idle in the gaps
     * between them.
     */
    INDEXING_FOREGROUND_COOLDOWN_MS(
        "justsearch.indexing.foreground_cooldown_ms",
        "JUSTSEARCH_INDEXING_FOREGROUND_COOLDOWN_MS",
        "500", LifecycleStage.PERMANENT),

    // ==================== NRT reopen strategy (tempdoc 885 item 19) ====================

    /**
     * NRT reopen strategy. {@code continuous} (default) is today's behaviour: the
     * {@code ControlledRealTimeReopenThread} reopens on the {@code index.nrt.*} staleness bounds
     * (500 ms / 50 ms) whether or not anyone is searching. {@code on_demand} is the measurement
     * candidate: the background thread drops to {@code index.nrt.background_reopen_ms} and each
     * foreground search refreshes the searcher itself before acquiring it, so the segment-open cost
     * lands on the first query after new documents instead of on every 500 ms tick.
     *
     * <p>An unrecognised value falls back to {@code continuous} with a WARN — this knob exists to
     * be A/B-measured, so a typo must not silently change cadence.
     */
    INDEX_NRT_MODE("index.nrt.mode", "JUSTSEARCH_INDEX_NRT_MODE", "continuous", LifecycleStage.EXPERIMENTAL),

    /**
     * Background reopen cadence, in ms, while {@code index.nrt.mode=on_demand} (default 2000).
     * Ignored in {@code continuous} mode. The thread still wakes on this period when nothing has
     * been written, but Lucene's {@code openIfChanged} returns null on an unchanged index, so an
     * idle Worker performs no reopen.
     */
    INDEX_NRT_BACKGROUND_REOPEN_MS(
        "index.nrt.background_reopen_ms", "JUSTSEARCH_INDEX_NRT_BACKGROUND_REOPEN_MS", "2000", LifecycleStage.EXPERIMENTAL),

    /**
     * Age, in ms, past which a foreground search in {@code on_demand} mode escalates from the
     * non-blocking {@code maybeRefresh()} to {@code maybeRefreshBlocking()} (default 1000), so a
     * query cannot silently return a view older than this bound. Ignored in {@code continuous} mode.
     */
    INDEX_NRT_ON_DEMAND_MAX_STALE_MS(
        "index.nrt.on_demand_max_stale_ms", "JUSTSEARCH_INDEX_NRT_ON_DEMAND_MAX_STALE_MS", "1000", LifecycleStage.EXPERIMENTAL),

    // ==================== Commit cadence (tempdoc 885 item 19 follow-up) ====================

    /**
     * Period, in ms, of the Worker's safety-net commit timer (default 10000 — the constant it
     * replaces, so behaviour is unchanged). {@code CommitOps} commits whenever {@code pendingDocs >
     * 0} on this period, which is why it is the ceiling on every other commit-cadence lever: 885's
     * live window measured the indexing loop's own triggers going to zero while this timer's share
     * rose 16 → 46, because deferring the loop's commits keeps {@code pendingDocs} above zero for
     * longer and hands the timer MORE work. A commit-cadence arm cannot be measured until this is a
     * knob, which is what the tracked item asked for.
     *
     * <p>Resolved onto {@code ResolvedConfig.Index} and read by the Worker from the ordinal-450
     * config snapshot, not from a raw sysprop read inside the Worker JVM (the [R1] defect shape).
     */
    INDEX_COMMIT_TIMER_INTERVAL_MS(
        "index.commit.timer_interval_ms", "JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS", "10000", LifecycleStage.PERMANENT),

    // ============ Document-identity deletion grace (tempdoc 931 §C.6) ============

    /**
     * How long, in ms, a confirmed-deleted path keeps its document identity before a file
     * reappearing at that path is treated as a NEW document (default 30 days).
     *
     * <p>Identity rows have no GC, so before this key a replacement file at a previously-deleted
     * path inherited the old uid — and with it the old document's accumulated feedback. Deleting
     * the row on removal would be worse: an unmounted drive, a sync client that momentarily hides a
     * file, or a cloud placeholder would each look like a deletion and permanently break identity
     * across an ordinary restore. The grace window is the compromise: within it a reappearance is
     * the SAME document; past it, a new uid is minted.
     *
     * <p>Resolved onto {@code ResolvedConfig.Index} and read by the Worker from the ordinal-450
     * config snapshot, not from a raw sysprop read inside the Worker JVM.
     */
    INDEX_IDENTITY_DELETION_GRACE_MS(
        "index.identity.deletion_grace_ms",
        "JUSTSEARCH_INDEX_IDENTITY_DELETION_GRACE_MS",
        "2592000000",
        LifecycleStage.PERMANENT),

    // ============ Exact kNN capture switch (lane F PR 0b) ============

    /**
     * Make every kNN query EXACT instead of approximate (default false — today's behaviour).
     *
     * <p>Lane F PR 0b, the deterministic-capture switch. HNSW is approximate, and Lucene 10.4's
     * {@code AbstractKnnVectorQuery.getLeafResults} has NO exact path for an UNFILTERED query at
     * any {@code k}: raising {@code index.vector.ef_search} widens the beam but cannot pin the
     * result, so two index builds of the same corpus can return different neighbours at the
     * top-k margin. With a filter present the query takes {@code exactSearch} when the filter's
     * cost is within the per-leaf top-k, which {@code k >= reader.maxDoc()} guarantees for every
     * leaf. So this switch does two things at {@code ReadPathOps}'s one kNN factory: raises
     * {@code k} to at least {@code maxDoc} and supplies a {@code MatchAllDocsQuery} filter when
     * the caller had none.
     *
     * <p>Only the APPROXIMATION changes, not the pipeline's branches: in this mode the dense
     * leg's inflated {@code totalHits} is excluded from the candidate-budget saturation test
     * ({@code SearchExecutor#isCandidateBudgetSaturated}), so the chunk-retry branch fires on
     * exactly the same queries it did with the switch off.
     *
     * <p>ONE display-only number does move with it: {@code RagContextOps} reports the dense leg's
     * {@code totalHits} (plus the union leg's size) as the RAG meta's {@code chunks_found}
     * ({@code RAGContext.java:355}), so in exhaustive mode that field reads as the whole
     * vector-bearing corpus. It is already documented as a composite that "may exceed the returned
     * chunks list" and nothing branches on it — {@code chunks_used} is the number that matters.
     *
     * <p>Cost is O(vectors) per query. It is a capture/diagnostic knob, not a production default.
     */
    INDEX_VECTOR_EXHAUSTIVE_SEARCH("index.vector.exhaustive_search",
        "JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH", LifecycleStage.PERMANENT),

    // ============ ORT intra-op thread pin (lane F PR 0b) ============

    /**
     * Fixed intra-op thread count for every ONNX Runtime session; unset (the default) leaves
     * ORT's own choice, which is today's behaviour.
     *
     * <p>ORT sizes the intra-op pool from hardware concurrency. On the CPU execution provider
     * that count decides how a GEMM's reduction is partitioned, and a different partition sums
     * the same floats in a different ORDER — so an embedding's low bits move with the thread
     * count. Within one machine the count is stable, so this changes nothing there; ACROSS two
     * machines, or a machine whose available parallelism differs between runs, it is a silent
     * source of vector drift. A deterministic capture pins it (lane F: alongside the CPU
     * execution-provider pins) so bit-stability does not depend on the host's core count.
     *
     * <p>Applies to CPU and CUDA sessions alike ({@code SessionOptionsApplier.applyBase}); it is
     * only load-bearing for CPU, where the reduction happens on those threads.
     */
    ORT_INTRA_OP_THREADS("justsearch.onnxruntime.intra_op_threads",
        "JUSTSEARCH_ORT_INTRA_OP_THREADS", LifecycleStage.PERMANENT);

    // YAML-only keys moved to ConfigKey.java (tempdoc 347 D1).

    private final String sysProp;
    private final String envVar;
    private final String defaultValue;
    private final LifecycleStage lifecycleStage;

    EnvRegistry(String sysProp, String envVar, LifecycleStage lifecycleStage) {
        this(sysProp, envVar, null, lifecycleStage);
    }

    EnvRegistry(
        String sysProp, String envVar, String defaultValue, LifecycleStage lifecycleStage) {
        this.sysProp = Objects.requireNonNull(sysProp);
        this.envVar = Objects.requireNonNull(envVar);
        this.defaultValue = defaultValue;
        this.lifecycleStage = Objects.requireNonNull(lifecycleStage);
    }

    /** Returns the system property name. */
    public String sysProp() {
        return sysProp;
    }

    /** Returns the environment variable name. */
    public String envVar() {
        return envVar;
    }

    /**
     * Returns the default value for this entry, or null if no default is defined.
     *
     * <p>Used by {@code ResolvedConfigBuilder} to register a programmatic default at ordinal 100.
     * Defaults defined here centralize values that were previously scattered across call sites.
     */
    public String defaultValue() {
        return defaultValue;
    }

    /** Returns the governance lifecycle classification for this configuration declaration. */
    public LifecycleStage lifecycleStage() {
        return lifecycleStage;
    }

    /** Runtime-configuration lifecycle stages; richer non-permanent metadata lives in governance. */
    public enum LifecycleStage {
        PERMANENT,
        EXPERIMENTAL,
        DEPRECATED
    }

    /**
     * Returns the config key used in the {@code ResolvedConfigBuilder} ordinal chain.
     *
     * <p>Always equals {@code sysProp()}. The separate {@code configKey} field was removed in
     * tempdoc 347 D2 by standardizing all sysProp names to match the ordinal chain key.
     */
    public String configKey() {
        return sysProp;
    }

    /**
     * Gets the raw string value, or empty if not set.
     *
     * @return the configured value, or empty
     */
    public Optional<String> get() {
        String val = System.getProperty(sysProp);
        if (val != null && !val.isBlank()) {
            return Optional.of(val);
        }
        val = System.getenv(envVar);
        if (val != null && !val.isBlank()) {
            return Optional.of(val);
        }
        return Optional.empty();
    }

    /**
     * Gets the string value, or the provided default.
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default value
     */
    public String getString(String defaultValue) {
        return get().orElse(defaultValue);
    }

    /**
     * Gets the value as an integer, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public int getInt(int defaultValue) {
        return get().map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a long, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public long getLong(long defaultValue) {
        return get().map(s -> {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a double, or the provided default.
     *
     * @param defaultValue fallback if not configured or not parseable
     * @return the configured or default value
     */
    public double getDouble(double defaultValue) {
        return get().map(s -> {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a boolean.
     *
     * <p>Recognized true values: "true", "1", "yes" (case-insensitive).
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default value
     */
    public boolean getBoolean(boolean defaultValue) {
        return get().map(s -> {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
        }).orElse(defaultValue);
    }

    /**
     * Gets the value as a Path, or the provided default.
     *
     * @param defaultValue fallback if not configured
     * @return the configured or default path
     */
    public Path getPath(Path defaultValue) {
        return get().map(Path::of).orElse(defaultValue);
    }

    /**
     * Gets the value as a Path, or null if not set.
     *
     * @return the configured path, or null
     */
    public Path getPath() {
        return get().map(Path::of).orElse(null);
    }

    /**
     * Checks if this configuration is explicitly set.
     *
     * @return true if set via system property or environment variable
     */
    public boolean isSet() {
        return get().isPresent();
    }

    /**
     * Config keys checked for Head→Worker divergence after gRPC handshake (tempdoc 329).
     *
     * <p>If the Head's {@link #get()} value for any of these keys differs from the Worker's value,
     * a WARN is logged. This turns silent misconfiguration (tempdoc 312 item 20) into a visible
     * signal. The set focuses on keys that caused actual bugs or are critical for correctness.
     */
    public static final Set<EnvRegistry> CONFIG_DIVERGENCE_CHECK_KEYS = EnumSet.of(
        DATA_DIR,
        CONFIG_PATH,
        REPO_ROOT,
        SSOT_PATH,
        EMBED_ONNX_MODEL_PATH,
        ORT_NATIVE_PATH,
        INDEX_BASE_PATH,
        INDEX_SCHEMA_MISMATCH_POLICY,
        INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS
        // POLICY_GPU_ACCELERATION_ENABLED removed (347): EnterprisePolicyService writes
        // System.setProperty() on Head, causing expected divergence with Worker's env var.
        // The Worker gets the correct value from the config snapshot.
    );
}
