package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.path.PathResolutionStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §P2 — {@link InfraContext} is the seam through which the durable identity store
 * reaches every application service. Two things must hold at that seam: a composition that does
 * not wire a store gets the fail-closed {@link DocumentIdentityStore#UNAVAILABLE}, never {@code
 * null} (which would surface as an NPE deep inside admission instead of a named refusal), and the
 * back-compatible constructor that predates the store field lands on the same default rather than
 * on a second, permissive one.
 */
final class InfraContextDefaultsTest {

  @Test
  void aNullIdentityStoreBecomesTheFailClosedDefault() {
    InfraContext ctx =
        new InfraContext(
            null, null, null, null, null, null, null, null, null, null, 0L, null, null);
    assertSame(DocumentIdentityStore.UNAVAILABLE, ctx.documentIdentityStore());
    assertSame(PathResolutionStore.NOOP, ctx.pathResolutionStore());
    // The default is the refusing store, not an empty one.
    assertThrows(IllegalStateException.class, () -> ctx.documentIdentityStore().identityCount());
  }

  @Test
  void theBackCompatibleConstructorLandsOnTheSameDefault() {
    PathResolutionStore pathStore = new RecordingPathStore();
    InfraContext ctx =
        new InfraContext(null, null, null, null, null, null, null, null, null, null, 0L, pathStore);
    assertSame(pathStore, ctx.pathResolutionStore(), "an explicitly wired store is kept");
    assertSame(DocumentIdentityStore.UNAVAILABLE, ctx.documentIdentityStore());
  }

  @Test
  void anExplicitlyWiredIdentityStoreIsKeptAsIs() {
    DocumentIdentityStore wired =
        new DocumentIdentityStore() {
          @Override
          public Identity resolve(String pathHash, long nowMs) {
            return new Identity(pathHash, "uid", nowMs, nowMs);
          }

          @Override
          public Identity importExisting(String pathHash, String docUid, long nowMs) {
            return new Identity(pathHash, docUid, nowMs, nowMs);
          }

          @Override
          public int importExisting(
              java.util.Collection<ImportedIdentity> identities, long nowMs) {
            return 0;
          }

          @Override
          public long identityCount() {
            return 0L;
          }

          @Override
          public boolean hasImportRecord(String generationId) {
            return false;
          }

          @Override
          public void recordImport(ImportRecord record) {}

          @Override
          public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
            return RekeyResult.NOT_FOUND;
          }

          @Override
          public void markDeleted(String pathHash, long nowMs) {}

          @Override
          public Optional<Identity> lookup(String pathHash) {
            return Optional.empty();
          }
        };
    InfraContext ctx =
        new InfraContext(
            null, null, null, null, null, null, null, null, null, null, 0L, null, wired);
    assertSame(wired, ctx.documentIdentityStore());
  }

  /** Minimal non-NOOP path store so the "kept as wired" assertion cannot pass by identity. */
  private static final class RecordingPathStore implements PathResolutionStore {
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
  }
}
