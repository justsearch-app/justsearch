/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.util.Objects;

/**
 * Head-side wire record for a single watched-root row. Backs the
 * {@code core.indexed-roots} TABULAR Resource (slice 449 phase 7a).
 *
 * <p>Privacy contract: {@code pathHash} is the SHA-256 hex of the absolute
 * normalized path. Raw paths NEVER appear on this wire — callers resolve
 * {@code pathHash} via the {@code core.resolve-path-hash} Operation when a
 * user gesture demands the path string. Pinned by ADR-0028 +
 * {@code LibraryResolveHashOnlyCallerPin}.
 *
 * <p>Mirrors {@link IndexingJobView}'s shape decision: typed wire record with
 * hashed paths is the established TABULAR-Resource convention (per slice 445
 * + slice 3a.1.9 §B.B.D).
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@link #pathHash}: SHA-256 hex of the watched root's absolute path.
 *   <li>{@link #collection}: collection identifier (default for V1; user-
 *       collection support is a future extension).
 *   <li>{@link #fileCount}: last-known indexed file count (cached at
 *       indexing time). Zero is a valid value for an empty / not-yet-indexed
 *       root; not nullable.
 *   <li>{@link #lastIndexedIsoTime}: ISO-8601 wall-clock of last successful
 *       indexing pass; empty string if never indexed.
 *   <li>{@link #status}: derived enum string — "indexed" / "indexing" /
 *       "error" / "pending". Derivation lives in the controller (file
 *       count > 0 → indexed; walkError present → error; otherwise pending).
 *       NOTE (tempdoc 599): this walk-derived {@code status} means "scanned",
 *       NOT "searchable". The truthful folder state is derived FE-side from
 *       {@link #inFlightCount}/{@link #failedCount} (job drain), not this field.
 *   <li>{@link #walkError}: error message string from the last indexing
 *       pass; empty string when no error.
 *   <li>{@link #inFlightCount}: PENDING+PROCESSING indexing jobs under this
 *       root's path prefix (tempdoc 599 §9.2). Drives the per-folder
 *       "indexing · N remaining → searchable" projection. Reaper-bounded
 *       (no zombie PROCESSING rows). Zero is valid (drained or never-started).
 *   <li>{@link #failedCount}: permanently FAILED jobs under this root's prefix.
 *   <li>{@link #walkCompleted}: whether the folder's filesystem walk has
 *       terminated at least once (tempdoc 599 Fix 1). Lets the FE distinguish
 *       "walked, nothing to index" (walkCompleted + no lastIndexed → "empty")
 *       from "walk in progress / never walked" (→ "scanning"); both otherwise
 *       have an empty {@code lastIndexedIsoTime} and no {@code walkError}.
 *   <li>{@link #deleteDetectionUnverified}: tempdoc 626 §Axis-C — the last
 *       reconcile could NOT verify index-vs-disk delete correspondence for this
 *       root (the delete-detection scan was skipped because the indexed-path set
 *       exceeded the scan cap). The FE renders a per-root "couldn't verify —
 *       reindex to be sure" state instead of a false "✓ indexed". {@code false}
 *       for the overwhelming majority of roots (under the cap).
 *   <li>{@link #lastVerifiedIsoTime}: tempdoc 626 §Recency — ISO-8601 wall-clock
 *       of the last reconcile that actually CONFIRMED index↔disk correspondence
 *       for this root (distinct from {@link #lastIndexedIsoTime}, the last
 *       <em>write</em>). The FE shows "Verified Xm ago" so a calm "✓" proves it
 *       is fresh and a cap-skipped root reads as visibly stale. Empty string if
 *       never verified.
 *   <li>{@code parentDocs*} / {@code chunkDocs*}: tempdoc 813 §1c — per-root
 *       ENRICHMENT coverage, counted from the index under this root's path
 *       prefix. {@link #inFlightCount} covers only the first phase (a file
 *       becomes keyword-searchable); enrichment backfill carries no per-root
 *       job rows, so without these a folder that is 5% embedded and one that is
 *       100% embedded look identical. Denominator discipline: the parent totals
 *       EXCLUDE chunk documents, each parent stage counts only documents that
 *       CARRY its status field (an absent status means the stage does not apply
 *       to that document, post-798; the backfill selects by status value, so
 *       counting such a document would pin the folder below 100% forever),
 *       and the chunk tier is its own denominator —
 *       {@link #chunkDocsSettled} over {@link #chunkDocsTotal} is never "N of M
 *       files". Numerator discipline: "settled" = terminal state (COMPLETED +
 *       COMPLETED_EMPTY where the stage defines it + FAILED), so a permanently
 *       failed document does not pin the folder below 100% forever. All zero
 *       when the Worker's index runtime is unavailable.
 * </ul>
 */
public record IndexedRootView(
    String pathHash,
    String collection,
    long fileCount,
    String lastIndexedIsoTime,
    String status,
    String walkError,
    long inFlightCount,
    long failedCount,
    boolean walkCompleted,
    boolean deleteDetectionUnverified,
    String lastVerifiedIsoTime,
    long parentDocsTotalEmbedding,
    long parentDocsSettledEmbedding,
    long parentDocsTotalSplade,
    long parentDocsSettledSplade,
    long parentDocsTotalNer,
    long parentDocsSettledNer,
    long chunkDocsTotal,
    long chunkDocsSettled,
    java.util.Map<String, Boolean> failedStages,
    java.util.Map<String, Boolean> missingArtifactStages) {

  public IndexedRootView {
    Objects.requireNonNull(pathHash, "pathHash");
    Objects.requireNonNull(collection, "collection");
    lastIndexedIsoTime = lastIndexedIsoTime == null ? "" : lastIndexedIsoTime;
    Objects.requireNonNull(status, "status");
    walkError = walkError == null ? "" : walkError;
    lastVerifiedIsoTime = lastVerifiedIsoTime == null ? "" : lastVerifiedIsoTime;
  }
}
