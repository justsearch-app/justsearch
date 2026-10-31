package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.path.PathResolutionStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 419 / T5.1 (ADR-0028) — exercises the SQLite-backed {@link
 * SqlitePathResolutionStore}. Each test starts from a fresh {@code jobs.db} migrated to V7
 * via {@link SqliteJobQueue#open()} so the {@code path_resolution} table exists.
 */
final class SqlitePathResolutionStoreTest {

  @TempDir Path tempDir;

  private SqliteJobQueue jobQueue;
  private SqlitePathResolutionStore store;

  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    store = new SqlitePathResolutionStore(dbPath);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (store != null) store.close();
    if (jobQueue != null) jobQueue.close();
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings={"proj_a", "proj%a", "proj\\a"})
  void pruningUsesLiteralCaseSensitivePathBoundaries(String name) {
    String root = "/workspace/" + name;
    store.record("child", root + "/child", 1);
    store.record("nested", root + "/nested/file", 1);
    store.record("sibling", root + "-other/file", 1);
    store.record("wildcard", "/workspace/projXa/file", 1);
    store.record("case", "/Workspace/" + name + "/file", 1);
    assertEquals(2, store.pruneByRootPrefix(root + "/"));
    assertTrue(store.lookup("child").isEmpty());
    assertTrue(store.lookup("nested").isEmpty());
    assertTrue(store.lookup("sibling").isPresent());
    assertTrue(store.lookup("wildcard").isPresent());
    assertTrue(store.lookup("case").isPresent());
    assertEquals(0, store.pruneByRootPrefix(root));
  }

  @Test
  @DisplayName("Lookup of an unknown hash returns empty")
  void unknownHashReturnsEmpty() {
    Optional<PathResolutionStore.Resolution> result = store.lookup("never-recorded");
    assertTrue(result.isEmpty(), "Unknown hash must return empty");
  }

  @Test
  @DisplayName("record then lookup returns the path with non-null lastSeenAtMs and null removedAtMs")
  void recordThenLookupRoundTrips() {
    store.record("hash-a", "/x/y/z.txt", 1_000L);
    Optional<PathResolutionStore.Resolution> result = store.lookup("hash-a");
    assertTrue(result.isPresent());
    PathResolutionStore.Resolution r = result.get();
    assertEquals("hash-a", r.pathHash());
    assertEquals("/x/y/z.txt", r.normalizedPath());
    assertEquals(1_000L, r.lastSeenAtMs());
    assertNull(r.removedAtMs(), "Fresh record must have null removedAtMs");
  }

  @Test
  @DisplayName("Re-recording the same hash UPSERTs and clears removedAt")
  void reRecordingClearsRemovedAt() {
    store.record("hash-b", "/file.txt", 1_000L);
    store.markRemoved("hash-b", 2_000L);
    assertNotNull(store.lookup("hash-b").get().removedAtMs(), "removedAt is set after markRemoved");
    store.record("hash-b", "/file.txt", 3_000L);
    PathResolutionStore.Resolution after = store.lookup("hash-b").get();
    assertEquals(3_000L, after.lastSeenAtMs(), "lastSeenAt updated by re-record");
    assertNull(after.removedAtMs(), "removedAt cleared by re-record (file came back)");
  }

  @Test
  @DisplayName("markRemoved sets removedAt without deleting the row")
  void markRemovedKeepsRowForRetention() {
    store.record("hash-c", "/file.txt", 1_000L);
    store.markRemoved("hash-c", 2_000L);
    Optional<PathResolutionStore.Resolution> result = store.lookup("hash-c");
    assertTrue(result.isPresent(), "Row must remain after markRemoved (retention window)");
    assertEquals(2_000L, result.get().removedAtMs());
  }

  @Test
  @DisplayName("markRemoved on already-removed row does not change removedAt")
  void markRemovedIsIdempotent() {
    store.record("hash-d", "/file.txt", 1_000L);
    store.markRemoved("hash-d", 2_000L);
    store.markRemoved("hash-d", 5_000L); // should be no-op since removedAt is already set
    assertEquals(2_000L, store.lookup("hash-d").get().removedAtMs());
  }

  @Test
  @DisplayName("pruneByRootPrefix deletes only rows under the given prefix")
  void pruneByRootPrefixIsScoped() {
    store.record("h1", "/users/alice/docs/a.txt", 1_000L);
    store.record("h2", "/users/alice/photos/b.jpg", 1_000L);
    store.record("h3", "/users/bob/c.txt", 1_000L);

    int deleted = store.pruneByRootPrefix("/users/alice");
    assertEquals(2, deleted, "Only the two alice paths should be pruned");
    assertTrue(store.lookup("h1").isEmpty());
    assertTrue(store.lookup("h2").isEmpty());
    assertTrue(store.lookup("h3").isPresent(), "Bob's file is unaffected");
  }

  @Test
  @DisplayName("pruneByRootPrefix handles trailing slash equivalently")
  void pruneByRootPrefixHandlesTrailingSlash() {
    store.record("h1", "/root/file.txt", 1_000L);
    int deletedNoSlash = store.pruneByRootPrefix("/root");
    assertEquals(1, deletedNoSlash);

    store.record("h2", "/root/file.txt", 1_000L);
    int deletedWithSlash = store.pruneByRootPrefix("/root/");
    assertEquals(1, deletedWithSlash);
  }

  @Test
  @DisplayName("pruneOldRemoved deletes only removed rows older than cutoff")
  void pruneOldRemovedRespectsCutoff() {
    store.record("alive", "/a.txt", 1_000L);
    store.record("old-deleted", "/o.txt", 1_000L);
    store.markRemoved("old-deleted", 2_000L);
    store.record("new-deleted", "/n.txt", 1_000L);
    store.markRemoved("new-deleted", 5_000L);

    int deleted = store.pruneOldRemoved(3_000L);
    assertEquals(1, deleted, "Only old-deleted (removedAt=2000 < 3000) should be pruned");
    assertTrue(store.lookup("alive").isPresent(), "Live row never pruned by retention sweep");
    assertTrue(store.lookup("old-deleted").isEmpty());
    assertTrue(store.lookup("new-deleted").isPresent(), "Newer deletion preserved");
  }

  @Test
  @DisplayName("Concurrent record + lookup is consistent (single-writer-multi-reader contract)")
  void concurrentRecordLookupIsConsistent() throws InterruptedException {
    int n = 200;
    Thread writer =
        new Thread(
            () -> {
              for (int i = 0; i < n; i++) {
                store.record("h-" + i, "/file-" + i, i);
              }
            });
    writer.start();
    writer.join();
    int found = 0;
    for (int i = 0; i < n; i++) {
      if (store.lookup("h-" + i).isPresent()) found++;
    }
    assertEquals(n, found, "All records visible after writer join");
  }

  @Test
  @DisplayName("NOOP store is queryable but always returns empty")
  void noopStoreSatisfiesContract() {
    PathResolutionStore noop = PathResolutionStore.NOOP;
    noop.record("anything", "/path", 1L);
    assertFalse(noop.lookup("anything").isPresent());
    assertEquals(0, noop.pruneByRootPrefix("/anything"));
    assertEquals(0, noop.pruneOldRemoved(Long.MAX_VALUE));
  }
}
