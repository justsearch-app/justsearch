/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

import java.util.List;

/**
 * Exclude-pattern enforcement surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (LibraryView Preview/Apply Excludes cluster):
 * backs the {@code core.preview-excludes} (LOW, dryRun=true) and
 * {@code core.apply-excludes} (HIGH-DESTRUCTIVE, dryRun=false) Operations
 * without forcing a cross-module cycle through the modules/ui-side
 * {@code IndexingController}. Production wiring: {@code IndexingController}
 * implements this interface; {@code LocalApiServer} late-binds it onto
 * {@code HeadAssembly} after both are constructed.
 *
 * <p>Stability: stable (API contract).
 */
public interface ExcludesService {

  /**
   * Apply (or preview) exclude patterns. When {@code dryRun=true}, walks the
   * configured roots and counts matches per pattern without mutating the
   * index. When {@code dryRun=false}, deletes already-indexed documents whose
   * paths match the configured globs (delegated to the Worker via an in-process port; no
   * Lucene/queue DB IO in Head).
   *
   * @param dryRun true for preview-excludes (LOW); false for apply-excludes
   *               (HIGH-DESTRUCTIVE)
   * @return summary of the operation
   * @throws Exception if the policy or watched roots cannot be obtained; per-scope failures are returned
   */
  ExcludesResult applyExcludes(boolean dryRun, EngineContext engineContext) throws Exception;

  /** Effective matcher policy and the resolver's winning source; reading it does not walk disk. */
  default ExcludesPolicy effectivePolicy() {
    throw new UnsupportedOperationException("Effective exclusion policy unavailable");
  }

  record ExcludesPolicy(String source, String sourceDetail, List<String> patterns) {
    public ExcludesPolicy {
      java.util.Objects.requireNonNull(source, "source");
      patterns = List.copyOf(patterns);
    }
  }

  /**
   * Result of an apply (or preview) operation. Mirrors the pre-existing
   * {@code POST /api/indexing/excludes/apply} response shape. Existing fields are retained;
   * confirmedRemovals counts Worker-confirmed file or folder deletion requests, including
   * matches that were not indexed. Unchecked scopes distinguish partial coverage from completion.
   * Both HTTP and Operation handlers expose these additions.
   */
  record ExcludesResult(
      boolean dryRun,
      int patterns,
      int rootsProcessed,
      int deletedByPathJobs,
      int deletedById,
      int matchedFiles,
      List<PatternMatch> perPattern,
      boolean capped,
      String message,
      int confirmedRemovals,
      List<UncheckedScope> uncheckedScopes,
      ExcludesPolicy effectivePolicy) {

    /** Retain the existing result constructor for callers without policy metadata. */
    public ExcludesResult(boolean dryRun, int patterns, int rootsProcessed, int deletedByPathJobs,
        int deletedById, int matchedFiles, List<PatternMatch> perPattern, boolean capped, String message,
        int confirmedRemovals, List<UncheckedScope> uncheckedScopes) {
      this(dryRun, patterns, rootsProcessed, deletedByPathJobs, deletedById, matchedFiles, perPattern,
          capped, message, confirmedRemovals, uncheckedScopes, null);
    }

    /** Keep the previous source/serialization fields usable by existing callers. */
    public ExcludesResult(boolean dryRun, int patterns, int rootsProcessed, int deletedByPathJobs,
        int deletedById, int matchedFiles, List<PatternMatch> perPattern, boolean capped, String message) {
      this(dryRun, patterns, rootsProcessed, deletedByPathJobs, deletedById, matchedFiles, perPattern,
          capped, message, 0, List.of(new UncheckedScope("Watched folders", "not_checked", "Deletion coverage unavailable")));
    }

    public ExcludesResult {
      perPattern = List.copyOf(perPattern);
      uncheckedScopes = List.copyOf(uncheckedScopes);
    }

    /** An unchecked disk scope or an unconfirmed deletion; never counted as removed. */
    public record UncheckedScope(String path, String status, String reason) {}

    /** Per-pattern match count. */
    public record PatternMatch(String pattern, int matches) {}
  }
}
