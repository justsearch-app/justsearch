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
   * paths match the configured globs (delegated to the Worker via gRPC; no
   * Lucene/queue DB IO in Head).
   *
   * @param dryRun true for preview-excludes (LOW); false for apply-excludes
   *               (HIGH-DESTRUCTIVE)
   * @return summary of the operation
   * @throws Exception on Worker IO failure, walk failure, or filesystem error
   */
  ExcludesResult applyExcludes(boolean dryRun, EngineContext engineContext) throws Exception;

  /**
   * Result of an apply (or preview) operation. Mirrors the pre-existing
   * {@code POST /api/indexing/excludes/apply} response shape (FE-side
   * {@code ApplyExcludesResponse}) so the wire surface is unchanged when the
   * Operation handlers serialize this back into {@code structuredData}.
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
      String message) {

    /** Per-pattern match count. */
    public record PatternMatch(String pattern, int matches) {}
  }
}
