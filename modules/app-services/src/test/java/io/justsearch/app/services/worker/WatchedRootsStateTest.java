package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WatchedRootsState")
final class WatchedRootsStateTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("markIndexed uses the injected clock and persists timestamp")
  void markIndexedPersistsClockInstant() throws Exception {
    Path root = tempDir.resolve("indexed-root");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    Instant now = Instant.parse("2026-02-09T12:34:56Z");
    WatchedRootsState state =
        new WatchedRootsState(watchedRoots, store, Clock.fixed(now, ZoneOffset.UTC));

    Path normalized = root.toAbsolutePath().normalize();
    state.markIndexed(normalized);
    state.persist();

    assertEquals(now, watchedRoots.get(normalized));
    assertEquals(now, store.loadPersistedRoots().get(normalized));
  }

  @Test
  @DisplayName("§Recency: clearing the unverified flag stamps lastVerifiedAt via the injected clock")
  void verifyingStampsLastVerifiedAt() {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Instant now = Instant.parse("2026-06-21T08:00:00Z");
    WatchedRootsState state =
        new WatchedRootsState(new ConcurrentHashMap<>(), store, Clock.fixed(now, ZoneOffset.UTC));
    Path root = tempDir.resolve("verified-root").toAbsolutePath().normalize();

    // A cap-skipped scan marks UNVERIFIED and must NOT stamp a verification time.
    state.setDeleteDetectionUnverified(root, true);
    assertTrue(state.isDeleteDetectionUnverified(root));
    assertEquals(null, state.getLastVerifiedAt(root), "marking unverified is not a verification");

    // A clean reconcile clears the flag → that IS the verification: stamp the heartbeat.
    state.setDeleteDetectionUnverified(root, false);
    assertFalse(state.isDeleteDetectionUnverified(root));
    assertEquals(now, state.getLastVerifiedAt(root), "clearing unverified stamps lastVerifiedAt");

    // Cleanup removes the heartbeat with the root.
    state.removeRootAndNested(root);
    assertEquals(null, state.getLastVerifiedAt(root), "removed root drops its lastVerifiedAt");
  }

  @Test
  @DisplayName("markNeverIndexed persists sentinel-backed entry without timestamp")
  void markNeverIndexedPersistsSentinel() throws Exception {
    Path root = tempDir.resolve("never-indexed-root");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);

    Path normalized = root.toAbsolutePath().normalize();
    state.markNeverIndexed(normalized);
    state.persist();

    assertEquals(WatchedRootsStore.NEVER_INDEXED, watchedRoots.get(normalized));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, store.loadPersistedRoots().get(normalized));
  }

  @Test
  @DisplayName("Fix 1: markWalkedEmpty flags walkCompleted (distinct from registration) and persists")
  void markWalkedEmptyFlagsWalkCompleted() throws Exception {
    Path empty = tempDir.resolve("empty-root");
    Path scanning = tempDir.resolve("scanning-root");
    Files.createDirectories(empty);
    Files.createDirectories(scanning);
    Path emptyN = empty.toAbsolutePath().normalize();
    Path scanningN = scanning.toAbsolutePath().normalize();

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    WatchedRootsState state = new WatchedRootsState(new ConcurrentHashMap<>(), store);

    // Registration only (walk not finished) → NOT completed; walked-empty terminal → completed.
    state.markNeverIndexed(scanningN);
    state.markWalkedEmpty(emptyN);
    state.persist();

    assertFalse(state.isWalkCompleted(scanningN));
    assertTrue(state.isWalkCompleted(emptyN));
    // Both have no lastIndexed timestamp — the flag is the ONLY distinguishing signal.
    assertEquals(WatchedRootsStore.NEVER_INDEXED, store.loadPersistedRoots().get(emptyN));

    // Round-trips: a reload preserves walkCompleted for the empty root, not the scanning one.
    WatchedRootsState reloaded = new WatchedRootsState(new ConcurrentHashMap<>(), store);
    reloaded.loadPersistedRoots();
    assertTrue(reloaded.isWalkCompleted(emptyN));
    assertFalse(reloaded.isWalkCompleted(scanningN));
  }

  @Test
  @DisplayName("removeRootAndNested removes root and child entries but keeps siblings")
  void removeRootAndNestedRemovesOnlyTargetBranch() throws Exception {
    Path root = tempDir.resolve("root");
    Path child = root.resolve("child");
    Path sibling = tempDir.resolve("sibling");
    Files.createDirectories(child);
    Files.createDirectories(sibling);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);

    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedChild = child.toAbsolutePath().normalize();
    Path normalizedSibling = sibling.toAbsolutePath().normalize();
    watchedRoots.put(normalizedRoot, Instant.now());
    watchedRoots.put(normalizedChild, Instant.now());
    watchedRoots.put(normalizedSibling, Instant.now());

    state.removeRootAndNested(normalizedRoot);

    assertFalse(watchedRoots.containsKey(normalizedRoot));
    assertFalse(watchedRoots.containsKey(normalizedChild));
    assertTrue(watchedRoots.containsKey(normalizedSibling));
  }

  @Test
  @DisplayName("loadPersistedRoots hydrates state map from store")
  void loadPersistedRootsHydratesState() throws Exception {
    Path root = tempDir.resolve("persisted-root");
    Files.createDirectories(root);
    Path normalized = root.toAbsolutePath().normalize();
    Instant ts = Instant.parse("2026-02-09T13:00:00Z");

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    store.persistRoots(Map.of(normalized, ts), Map.of(), Set.of(), Map.of());

    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);
    state.loadPersistedRoots();

    assertEquals(ts, watchedRoots.get(normalized));
  }

  @Test
  @DisplayName("load preloads timestamps, collections, and terminal walk flags before returning")
  void loadPreloadsPersistedRootMetadata(@TempDir Path dataDirectory) throws Exception {
    Path indexed = dataDirectory.resolve("indexed");
    Path empty = dataDirectory.resolve("empty");
    Path failed = dataDirectory.resolve("failed");
    Path scanning = dataDirectory.resolve("scanning");
    Files.createDirectories(indexed);
    Files.createDirectories(empty);
    Files.createDirectories(failed);
    Files.createDirectories(scanning);
    Path indexedN = indexed.toAbsolutePath().normalize();
    Path emptyN = empty.toAbsolutePath().normalize();
    Path failedN = failed.toAbsolutePath().normalize();
    Path scanningN = scanning.toAbsolutePath().normalize();
    Instant indexedAt = Instant.parse("2026-09-14T08:30:00Z");

    Path rootsFile = dataDirectory.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    store.persistRoots(
        Map.of(
            indexedN, indexedAt,
            emptyN, WatchedRootsStore.NEVER_INDEXED,
            failedN, WatchedRootsStore.NEVER_INDEXED,
            scanningN, WatchedRootsStore.NEVER_INDEXED),
        Map.of(failedN, "walk failed"),
        Set.of(indexedN, emptyN, failedN),
        Map.of(indexedN, "documents", emptyN, "empty-collection"));

    WatchedRootsState state = WatchedRootsState.load(dataDirectory);

    assertEquals(Set.of(indexedN, emptyN, failedN, scanningN), Set.copyOf(state.watchedPaths()));
    assertEquals(indexedAt, state.rootsMap().get(indexedN));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, state.rootsMap().get(emptyN));
    assertEquals("documents", state.getCollection(indexedN));
    assertEquals("empty-collection", state.getCollection(emptyN));
    assertEquals("walk failed", state.getWalkError(failedN));
    assertTrue(state.isWalkCompleted(indexedN));
    assertTrue(state.isWalkCompleted(emptyN));
    assertTrue(state.isWalkCompleted(failedN));
    assertFalse(state.isWalkCompleted(scanningN));
  }

  @Test
  @DisplayName("load fails closed on a corrupt persisted roots file")
  void corruptPersistedRootsRefusePreload(@TempDir Path dataDirectory) throws Exception {
    Path rootsFile = dataDirectory.resolve("watched_roots.json");
    Files.writeString(rootsFile, "{not-json");

    assertThrows(CorruptDurableStoreException.class, () -> WatchedRootsState.load(dataDirectory));
  }

  @Test
  @DisplayName("watchedPaths is a read-only membership snapshot and tracks state mutations")
  void watchedPathsSnapshotCannotMutateState(@TempDir Path dataDirectory) {
    WatchedRootsState state = WatchedRootsState.inMemory();
    Path root = dataDirectory.resolve("root").toAbsolutePath().normalize();
    state.register(root, "documents", true);

    var paths = state.watchedPaths();
    assertEquals(List.of(root), paths);
    assertThrows(UnsupportedOperationException.class, () -> paths.add(dataDirectory));

    state.removeRootAndNested(root);
    assertTrue(state.watchedPaths().isEmpty());
  }

  @Test
  @DisplayName("rootsMap exposes the sole backing map used by state mutations")
  void rootsMapIsIdentitySharedWithState(@TempDir Path dataDirectory) {
    WatchedRootsState state = WatchedRootsState.inMemory();
    Map<Path, Instant> roots = state.rootsMap();
    Path root = dataDirectory.resolve("root").toAbsolutePath().normalize();

    assertSame(roots, state.rootsMap());
    state.markNeverIndexed(root);
    assertEquals(WatchedRootsStore.NEVER_INDEXED, roots.get(root));

    Path injected = dataDirectory.resolve("injected").toAbsolutePath().normalize();
    roots.put(injected, Instant.parse("2026-09-14T09:00:00Z"));
    assertTrue(state.watchedPaths().contains(injected));
  }
}
