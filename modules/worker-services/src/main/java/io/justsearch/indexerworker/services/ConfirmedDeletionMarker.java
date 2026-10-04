/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one Worker-side gate between "a document left the index" and "this path's identity is
 * deleted" (tempdoc 931 §C.6).
 *
 * <p>Several paths remove a parent document, and most of them are NOT deletions of the file: a
 * watched root being un-watched, a collection being dropped, an exclude rule matching. Only the
 * paths that removed a document because the file itself is gone may start the identity grace
 * clock, and even those re-verify absence here rather than trusting their own trigger — a stat that
 * threw {@code IOException}, a momentarily-hidden file, or a cloud placeholder must never establish
 * a deletion. {@link Files#notExists} is deliberate over {@code !Files.exists}: an indeterminate
 * answer (permission denied, dead mount) is NOT absence and marks nothing.
 */
public final class ConfirmedDeletionMarker {

  private static final Logger log = LoggerFactory.getLogger(ConfirmedDeletionMarker.class);

  private final java.util.function.Supplier<DocumentIdentityStore> identityStoreSupplier;

  public ConfirmedDeletionMarker(DocumentIdentityStore identityStore) {
    this(() -> identityStore);
  }

  /**
   * For call sites whose identity store is wired AFTER construction (the in-process port ingest service's
   * setter), so the marker reads the current authority rather than capturing the sentinel.
   */
  public ConfirmedDeletionMarker(
      java.util.function.Supplier<DocumentIdentityStore> identityStoreSupplier) {
    this.identityStoreSupplier =
        identityStoreSupplier != null ? identityStoreSupplier : () -> null;
  }

  /** Marks {@code file}'s identity deleted when the file is verifiably gone. */
  public void markIfAbsent(Path file) {
    if (file == null) {
      return;
    }
    if (!Files.notExists(file)) {
      return;
    }
    mark(PathNormalizer.normalizeKey(file));
  }

  /**
   * Marks {@code rawPath}'s identity deleted when the file is verifiably gone. Accepts the
   * path-shaped id the index stores, which is already in {@link PathNormalizer} key form.
   */
  public void markIfAbsent(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return;
    }
    try {
      markIfAbsent(Path.of(rawPath));
    } catch (java.nio.file.InvalidPathException e) {
      log.debug("Not a filesystem path, no deletion mark recorded: {}", rawPath);
    }
  }

  private void mark(String normalizedKey) {
    if (normalizedKey == null || normalizedKey.isBlank()) {
      return;
    }
    DocumentIdentityStore identityStore = identityStoreSupplier.get();
    if (identityStore == null || identityStore == DocumentIdentityStore.UNAVAILABLE) {
      // Deferred/unwired compositions carry no identity authority. Skipping is correct here (unlike
      // minting, which must fail closed): a mark is bookkeeping about a deletion the index already
      // applied, and there is no row to bookkeep against.
      return;
    }
    try {
      identityStore.markDeleted(
          DocumentIdentityStore.pathHash(normalizedKey), System.currentTimeMillis());
    } catch (RuntimeException e) {
      // Identity bookkeeping must never fail a deletion the index has already applied; the next
      // confirmed deletion of the same path re-marks it.
      log.debug("Failed to record confirmed deletion for {}: {}", normalizedKey, e.toString());
    }
  }
}
