/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.PathPresence;
import io.justsearch.adapters.lucene.runtime.PruneOps;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.services.ConfirmedDeletionMarker;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Deletion of an index entry whose source disappeared or changed between
 * admission and validation.
 *
 * <p>Tempdoc 516 Slice 3 substrate (Appendix A.1). Lifted from {@code IndexingLoop}'s
 * {@code bestEffortDeleteMissingSource} so the upcoming Slice 4a Extractor + Writer
 * extractions can both invoke it without straddling the seam (today the method mutates
 * {@code indexedSinceCommit} as a side effect — that side effect moves to the caller per
 * the return-outcomes contract).
 */
public final class StaleSourceHandler {

  private final IndexingCoordinator indexingCoordinator;
  private final ConfirmedDeletionMarker deletionMarker;
  private final Supplier<IndexingCoordinator> activeLexicalSource;

  public StaleSourceHandler(IndexingCoordinator indexingCoordinator) {
    this(indexingCoordinator, DocumentIdentityStore.UNAVAILABLE);
  }

  public StaleSourceHandler(
      IndexingCoordinator indexingCoordinator, DocumentIdentityStore identityStore) {
    this(indexingCoordinator, identityStore, () -> null);
  }

  /**
   * {@code activeLexicalSource} is the serving generation A while a Green producer projects its
   * text to A, or null. A retired source leaves A as well: A keeps serving, and a refused
   * candidate leaves A in charge, so A must not keep text whose source A's policy retired.
   */
  public StaleSourceHandler(IndexingCoordinator indexingCoordinator,
      DocumentIdentityStore identityStore, Supplier<IndexingCoordinator> activeLexicalSource) {
    this.indexingCoordinator = indexingCoordinator;
    this.deletionMarker = new ConfirmedDeletionMarker(identityStore);
    this.activeLexicalSource = activeLexicalSource;
  }

  /**
   * Attempts to delete the index entry for {@code filePath} (and any associated chunks).
   *
   * @return {@code 1} when the delete was issued (the caller should bump its
   *     {@code indexedSinceCommit} counter so the commit driver sees pending work);
   *     failures propagate so the queue obligation can be retried.
   */
  public int deleteMissingSource(Path filePath, Path root, SourcePresence.RootCheck rootCheck) {
    Path file = filePath.toAbsolutePath().normalize();
    boolean rooted = SourcePresence.answersAsRoot(file, root);
    Path boundary = rooted ? root.toAbsolutePath().normalize() : file.getParent();
    var guard = new PruneOps.PresenceGuard() {
      @Override public boolean rootCurrent() {
        return rooted ? rootCheck.vouches(boundary) : rootCheck.vouchesForFile(boundary);
      }
      @Override public PathPresence presence(Path source) {
        try {
          SourceAccess.requireSafeAncestors(source, boundary);
          PathPresence presence = PathPresence.probeSource(source, boundary);
          return presence == PathPresence.ABSENT
              ? PathPresence.absentBeneathListableFolder(source, boundary, null) : presence;
        } catch (java.io.IOException | SecurityException unknown) {
          return PathPresence.UNKNOWN;
        }
      }
    };
    if (!guard.rootCurrent() || guard.presence(file) != PathPresence.ABSENT) {
      throw new IllegalStateException("Source retirement no longer has confirmed absence");
    }
    String normalizedPath = PathNormalizer.normalizeKey(file);
    IndexingCoordinator active = activeLexicalSource.get();
    if (active != null) active.pruneFile(normalizedPath, boundary, guard);
    indexingCoordinator.pruneFile(normalizedPath, boundary, guard);
    if (!guard.rootCurrent() || guard.presence(file) != PathPresence.ABSENT) {
      throw new IllegalStateException("Source retirement could not confirm absence after pruning");
    }
    // Tempdoc 931 §C.6 — a confirmed deletion point: the document left the index because its
    // source is gone. The marker re-verifies absence, so the DELETED classification that reaches
    // here from an IOException on stat (unreadable, not absent) records nothing.
    deletionMarker.markIfAbsent(filePath);
    return 1;
  }
}
