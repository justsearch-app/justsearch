/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

/**
 * Typed reason-code enum for Worker-side search execution (tempdoc 517).
 *
 * <p>Replaces raw string emissions like {@code Markers.append("reason_code", "...")}
 * and proto-field writes such as {@code response.setVectorBlockedReason("...")}.
 * Every wire-visible reason code emitted by {@code SearchOrchestrator} maps to one
 * of these enum members.
 *
 * <p>Three partitions, matching the wire contract in
 * {@code docs/reference/contracts/search-and-rag-reason-codes.md}:
 *
 * <ul>
 *   <li><b>9 embedding-compat codes</b> — produced upstream by {@code
 *       EmbeddingCompatibilityController} and mapped at the orchestrator boundary
 *       via {@link #fromCompatString(String)}.
 *   <li><b>7 search-routing codes</b> — emitted for vector/hybrid degradation
 *       (encoding failed, service unavailable, etc.).
 *   <li><b>11 chunk-merge codes</b> — emitted by chunk-augmentation gating.
 * </ul>
 *
 * <p>Plus {@link #EMBEDDING_COMPATIBILITY_UNKNOWN} as the
 * fall-through for unrecognised compat strings.
 *
 * <p>This type is package-private to {@code services/} — minimum-surface
 * promotion per C-018; promote only when a second consumer outside {@code
 * services/} emerges. The cross-module wire-shape backstop is
 * {@code WorkerSearchServiceReasonCodeContractTest}.
 */
public enum SearchReasonCode {
  // === 8 embedding-compat codes (mapped at boundary) ===
  INITIALIZING,
  NO_EMBEDDING_MODEL,
  NEW_INDEX_NO_FINGERPRINT,
  LEGACY_INDEX_NO_FINGERPRINT,
  FINGERPRINT_MATCH,
  FINGERPRINT_MISMATCH,
  REBUILD_IN_PROGRESS,
  REBUILD_COMPLETED,
  /**
   * Tempdoc 819 defect B: the rebuild drained ({@code pending_embedding == 0}) but not one
   * embedding succeeded, so the fingerprint attestation was refused rather than stamped over an
   * index with zero vectors. Terminal for the boot — the state stays REBUILDING and dense/hybrid
   * stays blocked until the embedding runtime is fixed and the worker restarts.
   */
  REBUILD_FAILED_NO_VECTORS,

  /** Fall-through for unrecognised compat strings via {@link #fromCompatString}. */
  EMBEDDING_COMPATIBILITY_UNKNOWN,

  // === 7 search-routing codes ===
  UNKNOWN,
  EMBEDDING_COMPATIBILITY_BLOCKED,
  NO_EMBEDDING_SERVICE,
  EMBEDDING_GENERATION_FAILED,
  EMBEDDING_EXCEPTION,
  SKIPPED_SHORT_QUERY,
  SKIPPED_NO_DISCRIMINATIVE_TERM,

  // === 11 chunk-merge codes ===
  APPLIED,
  SKIPPED_DISABLED,
  SKIPPED_EMPTY_BASE_RESULTS,
  SKIPPED_PAGINATED,
  SKIPPED_QUERY_SYNTAX,
  SKIPPED_SORT_NOT_RELEVANCE,
  SKIPPED_NO_CHUNK_DOCS,
  SKIPPED_SHORT_CORPUS,
  SKIPPED_UNKNOWN,
  SKIPPED_VECTOR_BLOCKED,
  SKIPPED_EMPTY_QUERY;

  /**
   * Wire string form — preserved verbatim from the pre-refactor implementation.
   * Used for proto field writes and the contract-test allowlist check.
   */
  public String wire() {
    return name();
  }

  /**
   * Map a compat-controller string into the enum. Unrecognised strings resolve
   * to {@link #EMBEDDING_COMPATIBILITY_UNKNOWN}. Null/blank input also resolves
   * to UNKNOWN — preserved to match the boundary's null-tolerance.
   */
  public static SearchReasonCode fromCompatString(String compatString) {
    if (compatString == null || compatString.isBlank()) {
      return EMBEDDING_COMPATIBILITY_UNKNOWN;
    }
    try {
      return SearchReasonCode.valueOf(compatString);
    } catch (IllegalArgumentException e) {
      return EMBEDDING_COMPATIBILITY_UNKNOWN;
    }
  }
}
