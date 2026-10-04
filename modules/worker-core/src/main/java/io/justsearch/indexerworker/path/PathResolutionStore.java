/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.path;

import java.util.Optional;

/**
 * Scoped reverse path-hash lookup store (ADR-0028, tempdoc 419 T5.1).
 *
 * <p>Backs the {@code POST /api/library/resolve-hash} endpoint. The single persistent home for
 * raw paths after admission, pinned by the ArchUnit guard
 * {@code LibraryResolveHashOnlyCallerPin} (T5.4) so no class in the diagnostic export call tree
 * can transitively depend on it.
 *
 * <p>Interface lives in {@code worker-core} so both {@code worker-services} (service handler) and
 * {@code indexer-worker} (SQLite impl) can reference the same contract without violating module
 * dependency direction.
 *
 * <p>Lifecycle (ADR-0028 §"Decision"):
 *
 * <ul>
 *   <li>{@link #record} on every successful or partial admission. Sets {@code last_seen_at = now},
 *       clears any prior {@code removed_at} marker (UPSERT semantics).
 *   <li>{@link #markRemoved} on observed file deletion. Row stays for retention.
 *   <li>{@link #pruneByRootPrefix} when a watched root is unwatched. Immediate delete.
 *   <li>{@link #pruneOldRemoved} periodic. Deletes rows where {@code removed_at < cutoffMs}.
 * </ul>
 */
public interface PathResolutionStore {

  /**
   * No-op default — used in tests, deferred-mode boot, and any composition that omits explicit
   * wiring. Lookups always return empty; mutations are silent.
   */
  PathResolutionStore NOOP = new PathResolutionStore() {
    @Override
    public void record(String pathHash, String normalizedPath, long nowMs) {}

    @Override
    public void markRemoved(String pathHash, long nowMs) {}

    @Override
    public Optional<Resolution> lookup(String pathHash) {
      return Optional.empty();
    }

    @Override
    public int pruneByRootPrefix(String rootPrefix) {
      return 0;
    }

    @Override
    public int pruneOldRemoved(long cutoffMs) {
      return 0;
    }
  };

  /** Records or refreshes a {@code (pathHash, normalizedPath)} pair. UPSERT semantics. */
  void record(String pathHash, String normalizedPath, long nowMs);

  /** Marks the row as removed at {@code nowMs}; row persists until retention prunes it. */
  void markRemoved(String pathHash, long nowMs);

  /**
   * Returns the resolution row for the given hash, or empty if never recorded. Callers (the
   * resolve-hash HTTP handler) layer their own watched-root authority check on top.
   */
  Optional<Resolution> lookup(String pathHash);

  /** Deletes every row whose {@code normalized_path} starts with {@code rootPrefix}. */
  int pruneByRootPrefix(String rootPrefix);

  /** Deletes rows where {@code removed_at} is non-null and older than {@code cutoffMs}. */
  int pruneOldRemoved(long cutoffMs);

  /** Stored resolution row. {@code removedAtMs} is null while the file is still observed. */
  record Resolution(
      String pathHash, String normalizedPath, long lastSeenAtMs, Long removedAtMs) {}
}
