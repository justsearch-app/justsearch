/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 931 §C.6 — the gate between "a document left the index" and "this path's identity is
 * deleted". Every confirmed-deletion wiring point routes through this class, so its refusals are
 * what stop a temporary absence from starting the grace clock.
 */
final class ConfirmedDeletionMarkerTest {

  @TempDir Path tempDir;

  @Test
  void marksTheIdentityOfAFileThatIsVerifiablyGone() throws Exception {
    Path file = tempDir.resolve("gone.txt");
    Files.writeString(file, "body");
    Files.delete(file);
    RecordingStore store = new RecordingStore();

    new ConfirmedDeletionMarker(store).markIfAbsent(file);

    assertEquals(
        List.of(DocumentIdentityStore.pathHash(PathNormalizer.normalizeKey(file))), store.marked);
  }

  @Test
  void marksNothingForAFileThatStillExists() throws Exception {
    // The watcher's DELETE event and the loop's DELETED classification can both arrive for a file
    // that is present again (an atomic replace, a sync client that recreated it). The index delete
    // already happened; the identity must not be dated by it.
    Path file = tempDir.resolve("present.txt");
    Files.writeString(file, "body");
    RecordingStore store = new RecordingStore();

    new ConfirmedDeletionMarker(store).markIfAbsent(file);

    assertTrue(store.marked.isEmpty());
  }

  @Test
  void marksNothingForADirectoryOrAnUnparseablePath() {
    RecordingStore store = new RecordingStore();
    ConfirmedDeletionMarker marker = new ConfirmedDeletionMarker(store);

    marker.markIfAbsent(tempDir);
    marker.markIfAbsent((Path) null);
    marker.markIfAbsent("");
    marker.markIfAbsent((String) null);

    assertTrue(store.marked.isEmpty());
  }

  @Test
  void acceptsTheIndexStoredPathShapeAndKeysItTheSameWayResolveDoes() throws Exception {
    // The prune sink hands over the `path` field as the index stored it, already in
    // PathNormalizer key form. It must hash to the SAME key admission resolves, or the mark lands
    // on a row nothing ever reads.
    Path file = tempDir.resolve("stored-shape.txt");
    Files.writeString(file, "body");
    String storedPath = PathNormalizer.normalizeKey(file);
    Files.delete(file);
    RecordingStore store = new RecordingStore();

    new ConfirmedDeletionMarker(store).markIfAbsent(storedPath);

    assertEquals(List.of(DocumentIdentityStore.pathHash(storedPath)), store.marked);
  }

  @Test
  void anUnwiredIdentityAuthorityIsSkippedRatherThanThrowing() throws Exception {
    // A deferred composition has no identity store. The index delete has already been applied, so
    // failing here would turn bookkeeping into a deletion failure.
    Path file = tempDir.resolve("deferred.txt");
    Files.writeString(file, "body");
    Files.delete(file);

    assertDoesNotThrow(
        () -> new ConfirmedDeletionMarker(DocumentIdentityStore.UNAVAILABLE).markIfAbsent(file));
    assertDoesNotThrow(() -> new ConfirmedDeletionMarker((DocumentIdentityStore) null).markIfAbsent(file));
  }

  @Test
  void aStoreFailureNeverPropagatesOutOfADeletionThatAlreadyHappened() throws Exception {
    Path file = tempDir.resolve("throwing.txt");
    Files.writeString(file, "body");
    Files.delete(file);
    DocumentIdentityStore throwing =
        new RecordingStore() {
          @Override
          public void markDeleted(String pathHash, long nowMs) {
            throw new IllegalStateException("sqlite is busy");
          }
        };

    assertDoesNotThrow(() -> new ConfirmedDeletionMarker(throwing).markIfAbsent(file));
  }

  @Test
  void readsTheCurrentAuthorityFromItsSupplierRatherThanCapturingTheSentinel() throws Exception {
    // WorkerIngestService wires its identity store AFTER constructing the marker, so a marker that
    // captured the field at construction would hold UNAVAILABLE forever and silently mark nothing.
    Path file = tempDir.resolve("late-wired.txt");
    Files.writeString(file, "body");
    Files.delete(file);
    DocumentIdentityStore[] authority = {DocumentIdentityStore.UNAVAILABLE};
    ConfirmedDeletionMarker marker = new ConfirmedDeletionMarker(() -> authority[0]);

    RecordingStore wiredLater = new RecordingStore();
    authority[0] = wiredLater;
    marker.markIfAbsent(file);

    assertEquals(1, wiredLater.marked.size());
  }

  /** Records marks; every other operation is unreachable on this path and says so. */
  private static class RecordingStore implements DocumentIdentityStore {
    final List<String> marked = new ArrayList<>();

    @Override
    public void markDeleted(String pathHash, long nowMs) {
      marked.add(pathHash);
    }

    @Override
    public Identity resolve(String pathHash, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Identity importExisting(String pathHash, String docUid, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int importExisting(Collection<ImportedIdentity> identities, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long identityCount() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasImportRecord(String generationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordImport(ImportRecord record) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Identity> lookup(String pathHash) {
      throw new UnsupportedOperationException();
    }
  }
}
