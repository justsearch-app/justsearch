/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.identity;

import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import java.util.Collection;
import java.util.Optional;

/**
 * Durable, path-free document identity authority.
 *
 * <p>The store maps the SHA-256 hash of a normalized absolute path to one content-independent
 * document uid. The mapping deliberately outlives path-resolution retention, deletion, and index
 * generation rebuilds.
 */
public interface DocumentIdentityStore {

  /**
   * Fail-closed default for deferred compositions and tests that do not wire the durable store.
   * Production indexing must never mint identity from a second authority.
   */
  DocumentIdentityStore UNAVAILABLE = new DocumentIdentityStore() {
    private IngestionServiceUnavailableException unavailable() {
      return new IngestionServiceUnavailableException(
          "Document identity store is unavailable");
    }

    @Override
    public Identity resolve(String pathHash, long nowMs) {
      throw unavailable();
    }

    @Override
    public Identity importExisting(String pathHash, String docUid, long nowMs) {
      throw unavailable();
    }

    @Override
    public int importExisting(Collection<ImportedIdentity> identities, long nowMs) {
      throw unavailable();
    }

    @Override
    public long identityCount() {
      throw unavailable();
    }

    @Override
    public boolean hasImportRecord(String generationId) {
      throw unavailable();
    }

    @Override
    public void recordImport(ImportRecord record) {
      throw unavailable();
    }

    @Override
    public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
      throw unavailable();
    }

    @Override
    public void markDeleted(String pathHash, long nowMs) {
      throw unavailable();
    }

    @Override
    public Optional<Identity> lookup(String pathHash) {
      throw unavailable();
    }
  };

  /**
   * Returns the existing identity for {@code pathHash}, or atomically mints and persists one.
   *
   * <p>A row previously marked by {@link #markDeleted} resolves through the deletion grace window
   * (tempdoc 931 §C.6): within the window the mark is cleared and the uid is KEPT, because a file
   * reappearing that soon is the same document returning (a restored backup, a sync client that
   * momentarily hid it). Past the window a NEW uid is minted onto the same row and
   * {@code firstSeenAtMs} is reset, because a file appearing at a long-deleted path is a different
   * document and must not inherit the old one's feedback.
   */
  Identity resolve(String pathHash, long nowMs);

  /**
   * Imports a uid already stored in the active index when neither its path hash nor uid is known.
   * Any existing store mapping remains authoritative during normal or mid-migration restarts.
   */
  Identity importExisting(String pathHash, String docUid, long nowMs);

  /**
   * Imports one batch of the active-index snapshot atomically.
   *
   * @return how many of them inserted a NEW identity row; rows already mapped by path hash or uid
   *     stay authoritative and are not counted
   */
  int importExisting(Collection<ImportedIdentity> identities, long nowMs);

  /** Number of persisted identity rows. Zero means the store carries no authority yet. */
  long identityCount();

  /** Whether the boot scan of {@code generationId} has already been recorded. */
  boolean hasImportRecord(String generationId);

  /** Records that one generation's parent identities have been scanned into the store. */
  void recordImport(ImportRecord record);

  /**
   * Moves an existing identity to a renamed path hash without changing its uid or first-seen time.
   *
   * @return whether the row moved, was already converged at the destination, or was not found
   */
  RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs);

  /**
   * Records that the Worker removed this path's document because the file is VERIFIED absent.
   *
   * <p>The row is kept, not dropped: dropping it would make every temporary absence — an unmounted
   * drive, a cloud placeholder, a sync client mid-write — permanently break identity. The mark only
   * starts the grace clock {@link #resolve} reads. Marking an unknown path, or a path already
   * marked, is a no-op (the FIRST confirmed deletion owns the clock).
   */
  void markDeleted(String pathHash, long nowMs);

  /** Looks up an identity by path hash. */
  Optional<Identity> lookup(String pathHash);

  /** Checks a normal-width identity write/read under the store lock, rolling back all effects. */
  default void probe() {
    throw new IngestionServiceUnavailableException("Identity health probe is unavailable");
  }

  /** SHA-256 hex over the already-normalized absolute path. */
  static String pathHash(String normalizedPath) {
    return PathHash.sha256(normalizedPath);
  }

  /**
   * Persisted identity row. {@code deletedAtMs} is {@code null} unless a confirmed deletion has
   * been recorded for this path and no later admission has cleared it.
   */
  record Identity(
      String pathHash,
      String docUid,
      long firstSeenAtMs,
      long lastSeenAtMs,
      Long deletedAtMs) {

    /** Source-compatible constructor for rows and callers that carry no deletion mark. */
    public Identity(String pathHash, String docUid, long firstSeenAtMs, long lastSeenAtMs) {
      this(pathHash, docUid, firstSeenAtMs, lastSeenAtMs, null);
    }
  }

  /** Path-free row supplied by the serving-index bootstrap scan. */
  record ImportedIdentity(String pathHash, String docUid) {}

  /** One completed bootstrap scan of an index generation. */
  record ImportRecord(
      String generationId,
      long importedAtMs,
      long parentsSeen,
      long parentsImported,
      long parentsSkipped) {}

  /** Durable outcome of an idempotent path rekey. */
  enum RekeyResult {
    MOVED,
    ALREADY_AT_DESTINATION,
    NOT_FOUND
  }
}
