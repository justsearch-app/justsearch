/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Unified registry of API error codes across all REST and SSE endpoints.
 *
 * <p>Every code carries an {@link ErrorClass} that classifies the error by its nature
 * (transient, permanent, policy, or validation). This lets consumers (frontend retry
 * logic, telemetry, monitoring) make data-driven decisions without maintaining hardcoded
 * lists of individual codes.
 *
 * <p>Adding a new error code requires:
 * <ol>
 *   <li>Add the enum value here with its {@code ErrorClass}</li>
 *   <li>Add its key in {@code messages/errors.en.properties}; the webview loads that catalog
 *       through {@code i18n/errorCatalog.ts}</li>
 *   <li>{@code ErrorMessagePropertiesContractTest} and {@code ErrorCatalogJsonArtifactTest}
 *       enforce catalog completeness and the generated JSON projection</li>
 * </ol>
 *
 * <p>This enum covers API-layer errors only. Agent-domain errors use
 * {@link io.justsearch.agent.api.AgentErrorCode} which has its own classification
 * ({@link io.justsearch.agent.api.AgentErrorClass}).
 */
public enum ApiErrorCode {

    // ── Index errors ───────────────────────────────────────────────────────

    /** Disk is full — cannot write to index. */
    INDEX_DISK_FULL(ErrorClass.PERMANENT),

    /** Index files are corrupted — rebuild required. */
    INDEX_CORRUPT(ErrorClass.PERMANENT),

    /** Index is locked by another process. */
    INDEX_LOCKED(ErrorClass.TRANSIENT),

    /** Index schema version mismatch — reindex required. */
    INDEX_SCHEMA_MISMATCH(ErrorClass.PERMANENT),

    /** Index is overloaded with writes — try again shortly. */
    INDEX_BACKPRESSURE(ErrorClass.TRANSIENT),

    /** Generic index I/O error. */
    INDEX_ERROR(ErrorClass.PERMANENT),

    /** Search index (worker) is not reachable. */
    INDEX_UNAVAILABLE(ErrorClass.TRANSIENT),

    // ── Search / general ───────────────────────────────────────────────────

    /** Request timed out. */
    TIMEOUT(ErrorClass.TRANSIENT),

    /** Backend service is temporarily unavailable. */
    SERVICE_UNAVAILABLE(ErrorClass.TRANSIENT),

    /** Unclassified internal error. */
    INTERNAL_ERROR(ErrorClass.PERMANENT),

    /** Requested resource not found. */
    NOT_FOUND(ErrorClass.VALIDATION),

    /** Operation is not supported. */
    NOT_SUPPORTED(ErrorClass.PERMANENT),

    /** Request parameters are invalid. */
    INVALID_REQUEST(ErrorClass.VALIDATION),

    /** Operation cannot be performed in the current state. */
    INVALID_STATE(ErrorClass.PERMANENT),

    /** Settings are read-only (e.g., IN_MEMORY persistence mode). */
    SETTINGS_READ_ONLY(ErrorClass.POLICY),

    /** File system I/O error. */
    IO_ERROR(ErrorClass.PERMANENT),

    /** Search cursor is invalid or expired. */
    CURSOR_INVALID(ErrorClass.VALIDATION),

    /** File path is invalid. */
    INVALID_PATH(ErrorClass.VALIDATION),

    /** Request body is not valid JSON. */
    INVALID_JSON(ErrorClass.VALIDATION),

    /** Request schema is invalid or unsupported. */
    INVALID_SCHEMA(ErrorClass.VALIDATION),

    // ── AI availability ────────────────────────────────────────────────────

    /** AI runtime is starting up — retry shortly. */
    AI_STARTING(ErrorClass.TRANSIENT),

    /** AI runtime is offline (not installed or crashed). */
    AI_OFFLINE(ErrorClass.PERMANENT),

    /** AI summarization service is not available. */
    AI_UNAVAILABLE(ErrorClass.PERMANENT),

    /** Text translation service is not available. */
    TRANSLATOR_UNAVAILABLE(ErrorClass.PERMANENT),

    // ── AI runtime ─────────────────────────────────────────────────────────

    /** Variant ID is required for runtime activation. */
    VARIANT_ID_REQUIRED(ErrorClass.VALIDATION),

    /** Runtime activation is already in progress. */
    RUNTIME_ACTIVATION_RUNNING(ErrorClass.TRANSIENT),

    /** Failed to start AI runtime. */
    RUNTIME_ACTIVATION_START_FAILED(ErrorClass.PERMANENT),

    /** Failed to stop AI runtime. */
    RUNTIME_DEACTIVATION_START_FAILED(ErrorClass.PERMANENT),

    /** Failed to switch AI mode. */
    MODE_SWITCH_FAILED(ErrorClass.PERMANENT),

    /** Failed to reload AI inference engine. */
    INFERENCE_RELOAD_FAILED(ErrorClass.PERMANENT),

    /** Failed to detach inference engine. */
    INFERENCE_DETACH_FAILED(ErrorClass.PERMANENT),

    /** Failed to detect GPU capabilities. */
    GPU_CAPABILITIES_FAILED(ErrorClass.PERMANENT),

    /** AI runtime is not installed. */
    RUNTIME_MISSING(ErrorClass.PERMANENT),

    // ── AI install ─────────────────────────────────────────────────────────

    /** User must accept model terms before downloading. */
    TERMS_REQUIRED(ErrorClass.POLICY),

    /** Downloads are disabled by administrator policy. */
    DOWNLOADS_DISABLED(ErrorClass.POLICY),

    /** AI installation is already in progress. */
    INSTALL_ALREADY_RUNNING(ErrorClass.TRANSIENT),

    /** Failed to start AI installation. */
    INSTALL_START_FAILED(ErrorClass.PERMANENT),

    /** Failed to cancel AI installation. */
    INSTALL_CANCEL_FAILED(ErrorClass.PERMANENT),

    /** Failed to repair AI installation. */
    INSTALL_REPAIR_FAILED(ErrorClass.PERMANENT),

    /** Pause/resume was asked for while no AI install run is in flight. */
    INSTALL_NOT_RUNNING(ErrorClass.VALIDATION),

    /** No AI model package with the requested id exists in the registry. */
    PACKAGE_NOT_FOUND(ErrorClass.VALIDATION),

    /**
     * The requested AI component cannot be declined — its {@code Necessity} is {@code required} or
     * {@code infrastructure}, so search (or chat) does not work without it.
     */
    PACKAGE_NOT_DECLINABLE(ErrorClass.POLICY),

    /** AI model manifest is unavailable (network issue). */
    MANIFEST_UNAVAILABLE(ErrorClass.TRANSIENT),

    /** AI model manifest is invalid or has no assets. */
    MANIFEST_INVALID(ErrorClass.PERMANENT),

    /** AI model manifest is not configured. */
    MANIFEST_NOT_CONFIGURED(ErrorClass.PERMANENT),

    /** Generic AI installation error. */
    AI_INSTALL_ERROR(ErrorClass.PERMANENT),

    /** Failed to create AI directories (disk/permissions). */
    INSTALL_IO_ERROR(ErrorClass.PERMANENT),

    /** AI model download failed (network issue). */
    DOWNLOAD_FAILED(ErrorClass.TRANSIENT),

    /** AI model verification failed (corrupt download). */
    VERIFY_FAILED(ErrorClass.PERMANENT),

    /** Failed to move AI files into place. */
    INSTALL_MOVE_FAILED(ErrorClass.PERMANENT),

    /** Failed to apply AI installation. */
    APPLY_FAILED(ErrorClass.PERMANENT),

    /** AI smoke test failed after installation. */
    SMOKE_TEST_FAILED(ErrorClass.PERMANENT),

    // ── AI pack ────────────────────────────────────────────────────────────

    /** Pack file not found at specified path. */
    PACK_NOT_FOUND(ErrorClass.VALIDATION),

    /** Pack path is invalid. */
    PACK_INVALID_PATH(ErrorClass.VALIDATION),

    /** Pack ZIP file is invalid. */
    PACK_ZIP_INVALID(ErrorClass.VALIDATION),

    /** Pack ZIP contains duplicate entries. */
    PACK_ZIP_DUPLICATE_ENTRY(ErrorClass.VALIDATION),

    /** Pack manifest file is missing from the archive. */
    PACK_MANIFEST_MISSING(ErrorClass.VALIDATION),

    /** Pack manifest is malformed or invalid. */
    PACK_MANIFEST_INVALID(ErrorClass.VALIDATION),

    /** Pack I/O error during processing. */
    PACK_IO_ERROR(ErrorClass.PERMANENT),

    /** Pack path parameter is required. */
    PACK_PATH_REQUIRED(ErrorClass.VALIDATION),

    /** A pack import is already in progress. */
    PACK_IMPORT_RUNNING(ErrorClass.TRANSIENT),

    /** Failed to start pack import. */
    PACK_IMPORT_START_FAILED(ErrorClass.PERMANENT),

    /** Pack verification (preflight) failed. */
    PACK_PREFLIGHT_FAILED(ErrorClass.PERMANENT),

    /** Pack manifest schema version is unsupported. */
    PACK_MANIFEST_SCHEMA_UNSUPPORTED(ErrorClass.VALIDATION),

    /** Pack kind is unsupported. */
    PACK_KIND_UNSUPPORTED(ErrorClass.VALIDATION),

    /** Invalid file entry in pack manifest. */
    PACK_FILE_INVALID(ErrorClass.VALIDATION),

    /** Duplicate file ID in pack manifest. */
    PACK_FILE_DUPLICATE_ID(ErrorClass.VALIDATION),

    /** Duplicate file path in pack manifest. */
    PACK_FILE_DUPLICATE_PATH(ErrorClass.VALIDATION),

    /** Invalid SHA-256 hash for a file in pack manifest. */
    PACK_FILE_SHA_INVALID(ErrorClass.VALIDATION),

    /** Invalid asset entry in pack manifest. */
    PACK_ASSET_INVALID(ErrorClass.VALIDATION),

    /** Asset references unknown file ID in pack manifest. */
    PACK_ASSET_UNKNOWN_FILE(ErrorClass.VALIDATION),

    /** Unsupported asset role in pack manifest. */
    PACK_ASSET_ROLE_UNSUPPORTED(ErrorClass.VALIDATION),

    /** Required asset role missing from pack manifest. */
    PACK_ASSET_MISSING_REQUIRED(ErrorClass.VALIDATION),

    /** Pack contains unreferenced files (fail-closed validation). */
    PACK_UNUSED_FILES(ErrorClass.VALIDATION),

    /** Invalid runtime variant ID in pack manifest. */
    PACK_RUNTIME_VARIANT_INVALID(ErrorClass.VALIDATION),

    /** Unsupported file type in runtime pack. */
    PACK_RUNTIME_FILE_UNSUPPORTED(ErrorClass.VALIDATION),

    /** Duplicate file reference across assets in pack manifest. */
    PACK_ASSET_DUPLICATE_FILE(ErrorClass.VALIDATION),

    /** Asset variant ID does not match manifest variant ID. */
    PACK_RUNTIME_VARIANT_MISMATCH(ErrorClass.VALIDATION),

    /** Invalid path inside pack manifest (e.g. traversal, wrong prefix). */
    PACK_PATH_INVALID(ErrorClass.VALIDATION),

    /** Runtime pack executable has unexpected filename. */
    PACK_RUNTIME_EXE_INVALID(ErrorClass.VALIDATION),

    // ── Summary / streaming ────────────────────────────────────────────────

    /** Content is too large for the LLM context window. */
    CONTEXT_TOO_LARGE(ErrorClass.VALIDATION),

    /** LLM server is overloaded — retry later. */
    LLM_OVERLOADED(ErrorClass.TRANSIENT),

    /** The declared caller already has its allowed concurrent work in flight. */
    ADMISSION_CONTEXT_LIMIT(ErrorClass.TRANSIENT),

    /** The Engine's aggregate work or executor capacity is occupied. */
    ADMISSION_ENGINE_LIMIT(ErrorClass.TRANSIENT),

    /** Summary generation failed. */
    SUMMARIZE_FAILED(ErrorClass.PERMANENT),

    /** Document has no content to process. */
    NO_CONTENT(ErrorClass.VALIDATION),

    /** No files selected for processing. */
    NO_FILES(ErrorClass.VALIDATION),

    /** Document ID is required. */
    NO_DOC_ID(ErrorClass.VALIDATION),

    /** Question is required for Q&A. */
    NO_QUESTION(ErrorClass.VALIDATION),

    /** Network request to backend service failed. */
    FETCH_FAILED(ErrorClass.TRANSIENT),

    /** Streaming connection failed. */
    STREAM_FAILED(ErrorClass.TRANSIENT),

    /** LLM stream ended without completion sentinel — response may be truncated. */
    STREAM_TRUNCATED(ErrorClass.TRANSIENT),

    /** User confirmation required before proceeding (e.g. too many files). */
    CONFIRM_REQUIRED(ErrorClass.VALIDATION),

    /** Summary synthesis step failed. */
    SYNTHESIS_FAILED(ErrorClass.PERMANENT),

    /** Hierarchical summary step failed. */
    HIERARCHICAL_FAILED(ErrorClass.PERMANENT),

    /** Q&A generation failed. */
    QA_FAILED(ErrorClass.PERMANENT),

    /**
     * Tempdoc 806 B.2 — retrieval exceeded its budget, so nothing was searched. TRANSIENT because a
     * retry is exactly the right response (round 12's cold reranker answered in ~9.5s on the retry);
     * distinct from {@code NO_CONTENT}, which is a claim ABOUT the corpus and must never be reported
     * for a retrieval that did not complete.
     */
    RETRIEVAL_TIMEOUT(ErrorClass.TRANSIENT),

    /** Tempdoc 806 B.2 — retrieval could not run at all; likewise not a claim about the corpus. */
    RETRIEVAL_FAILED(ErrorClass.TRANSIENT),

    /** Operation was interrupted (e.g. by user cancellation). Not retryable — user chose to stop. */
    INTERRUPTED(ErrorClass.PERMANENT),

    // ── Policy ─────────────────────────────────────────────────────────────

    /** Online AI is disabled by administrator policy. */
    POLICY_ONLINE_AI_DISABLED(ErrorClass.POLICY),

    /** GPU acceleration is disabled by administrator policy. */
    POLICY_GPU_DISABLED(ErrorClass.POLICY),

    /** Policy validation failed. */
    POLICY_VALIDATE_FAILED(ErrorClass.PERMANENT),

    /** Failed to compute effective policy. */
    POLICY_EFFECTIVE_FAILED(ErrorClass.PERMANENT),

    /** External server connection disallowed by policy. */
    POLICY_EXTERNAL_SERVER_DISALLOWED(ErrorClass.POLICY),

    /** AI model is not in the policy allowlist. */
    POLICY_MODEL_NOT_ALLOWLISTED(ErrorClass.POLICY),

    /** AI pack is not in the user's allowlist. */
    PACK_NOT_ALLOWLISTED_BY_USER_POLICY(ErrorClass.POLICY),

    /** AI pack is blocked by administrator policy. */
    PACK_NOT_ALLOWLISTED_BY_MACHINE_POLICY(ErrorClass.POLICY),

    /** Manifest SHA-256 parameter is required. */
    MANIFEST_SHA_REQUIRED(ErrorClass.VALIDATION),

    /** Pack manifest SHA-256 is invalid. */
    PACK_MANIFEST_SHA_INVALID(ErrorClass.VALIDATION),

    /** Failed to create user policy. */
    USER_POLICY_CREATE_FAILED(ErrorClass.PERMANENT),

    /** Failed to update user policy. */
    USER_POLICY_UPDATE_FAILED(ErrorClass.PERMANENT),

    /** Generic user policy write failure. */
    USER_POLICY_WRITE_FAILED(ErrorClass.PERMANENT),

    /** Effective policy is unavailable. */
    POLICY_UNAVAILABLE(ErrorClass.PERMANENT),

    /** Machine policy is present — user policy changes blocked. */
    MACHINE_POLICY_PRESENT(ErrorClass.POLICY),

    /** User policy file path could not be determined. */
    USER_POLICY_PATH_UNAVAILABLE(ErrorClass.PERMANENT),

    /** User policy already exists — refusing to overwrite. */
    USER_POLICY_ALREADY_EXISTS(ErrorClass.PERMANENT),

    /** User policy file path is invalid. */
    USER_POLICY_PATH_INVALID(ErrorClass.PERMANENT),

    /** Failed to create user policy directory. */
    USER_POLICY_DIR_CREATE_FAILED(ErrorClass.PERMANENT),

    /** Failed to serialize user policy to JSON. */
    USER_POLICY_SERIALIZE_FAILED(ErrorClass.PERMANENT),

    /** User policy file does not exist (required for update). */
    USER_POLICY_MISSING(ErrorClass.PERMANENT),

    /** User policy file contains invalid JSON. */
    USER_POLICY_INVALID_JSON(ErrorClass.VALIDATION),

    /** User policy schema version is unsupported. */
    USER_POLICY_UNSUPPORTED_SCHEMA(ErrorClass.PERMANENT),

    // ── Agent SSE ──────────────────────────────────────────────────────────

    /** Bad request to agent endpoint. */
    BAD_REQUEST(ErrorClass.VALIDATION),

    /** Agent session resume failed. */
    RESUME_FAILED(ErrorClass.PERMANENT),

    // ── Worker / infrastructure ────────────────────────────────────────────

    /** Failed to restart the worker process. */
    WORKER_RESTART_FAILED(ErrorClass.PERMANENT),

    /**
     * The worker's bounded boot-recovery budget is spent, so a restart request was declined
     * (tempdoc 825 review). Deliberately not {@code SERVICE_UNAVAILABLE}: that class is
     * {@code TRANSIENT}, and the live leg showed the wire saying {@code retryable: true} for a state
     * where a retried request provably returns the same answer until the application is restarted.
     */
    WORKER_RECOVERY_EXHAUSTED(ErrorClass.PERMANENT),

    /** Settings store is temporarily unavailable. */
    SETTINGS_UNAVAILABLE(ErrorClass.TRANSIENT),

    /** Diagnostics export failed. */
    DIAGNOSTICS_EXPORT_FAILED(ErrorClass.PERMANENT),

    /** UI authentication token is required. */
    UI_TOKEN_REQUIRED(ErrorClass.POLICY),

    // ── Encrypted-at-rest stores ───────────────────────────────────────────

    /**
     * Tempdoc 806 W1 — an AUTHORED store is encrypted at rest and its data key is LOCKED, so the
     * request cannot be served: reads would answer empty (indistinguishable from "nothing there") and
     * writes cannot be sealed. Carried on HTTP 423 Locked alongside {@code "locked": true}, matching
     * the tempdoc-629 global {@code KeyLockedException} mapping the shell already consumes. The remedy
     * is the user's, not a retry: unlock on the Security surface.
     */
    STORE_LOCKED(ErrorClass.POLICY),

    // ── Intent layer (slice 487 §4.4 trust lattice) ────────────────────────

    /**
     * The (SourceTier × RiskTier) trust lattice produced a non-AUTO gate behavior
     * (INLINE_CONFIRM or TYPED_CONFIRM) and the caller did not supply a confirmation
     * token. The HTTP response (status 428) carries {@code gateBehavior}, {@code
     * sourceTier}, {@code confirmStrategy}, and {@code operationId} so the caller can
     * render the trust-aware elicitation UX and re-invoke with a token. Per slice 487
     * §4.4 + Appendix B.8 (MCP elicitation framing).
     */
    CONFIRMATION_REQUIRED(ErrorClass.VALIDATION),

    /**
     * The trust lattice produced {@code GateBehavior.DENY} for the (source × operation)
     * pair. The dispatch is categorically refused; no elicitation flow. Today's V1
     * lattice cell values produce no DENY outcomes — the code is reserved for future
     * plugin-emitted UNTRUSTED ops on HIGH-risk operations.
     */
    TRUST_DENIED(ErrorClass.POLICY),
    ;

    private final ErrorClass errorClass;

    ApiErrorCode(ErrorClass errorClass) {
        this.errorClass = errorClass;
    }

    /** Returns the classification of this error. */
    public ErrorClass errorClass() {
        return errorClass;
    }

    /** Whether this error is safe to retry (shorthand for {@code errorClass == TRANSIENT}). */
    public boolean isRetryable() {
        return errorClass == ErrorClass.TRANSIENT;
    }
}
