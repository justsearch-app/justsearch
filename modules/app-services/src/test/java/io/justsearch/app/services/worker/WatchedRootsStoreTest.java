package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WatchedRootsStore")
final class WatchedRootsStoreTest {

  @TempDir Path tempDir;

  @Test
  void failedLegacyCopyRefusesStartupAndPreservesTheSource() throws Exception {
    Path legacy = tempDir.resolve("legacy.json");
    Files.writeString(legacy, "[]");
    Path blocked = tempDir.resolve("blocked");
    Files.writeString(blocked, "not-a-directory");
    Path target = blocked.resolve("watched_roots.json");
    var store = new WatchedRootsStore(target, null);
    assertThrows(
        CorruptDurableStoreException.class,
        () -> store.migrateLegacyRootsFileIfNeeded(legacy));
    assertEquals("[]", Files.readString(legacy));
    assertFalse(Files.exists(target));
  }

  @Test
  void legacyCopyLoadsBeforeBestEffortSourceRetirement() throws Exception {
    Path legacy = tempDir.resolve("legacy.json");
    Files.writeString(legacy, "[]");
    Path target = tempDir.resolve("new").resolve("watched_roots.json");
    var store = new WatchedRootsStore(target, null);
    store.migrateLegacyRootsFileIfNeeded(legacy);
    assertEquals("[]", Files.readString(target));
    assertTrue(store.loadPersistedRoots().isEmpty());
    assertTrue(Files.exists(legacy.resolveSibling("watched_roots.json.migrated")));
  }


  @Test
  @DisplayName("Loads new-format roots with lastIndexed when paths exist")
  void loadsNewFormat() throws Exception {
    Path root = tempDir.resolve("root1");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    Map<String, Object> data =
        Map.of(
            "roots",
            List.of(
                Map.of(
                    "path",
                    root.toAbsolutePath().toString(),
                    "lastIndexed",
                    "2026-01-01T00:00:00Z")));
    new ObjectMapper().writeValue(rootsFile.toFile(), data);

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(1, loaded.size());
    assertTrue(loaded.containsKey(root.toAbsolutePath()));
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), loaded.get(root.toAbsolutePath()));
  }

  @Test
  @DisplayName("Loads old-format roots list when paths exist")
  void loadsOldFormat() throws Exception {
    Path root = tempDir.resolve("root2");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    new ObjectMapper().writeValue(rootsFile.toFile(), List.of(root.toAbsolutePath().toString()));

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(1, loaded.size());
    assertTrue(loaded.containsKey(root.toAbsolutePath()));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, loaded.get(root.toAbsolutePath()), "Old format has no lastIndexed timestamps");
  }

  @Test
  @DisplayName("Persists roots in new format and round-trips")
  void persistsAndRoundTrips() throws Exception {
    Path root = tempDir.resolve("root3");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);

    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    store.persistRoots(Map.of(root.toAbsolutePath(), ts), Map.of(), java.util.Set.of(), Map.of());

    assertTrue(Files.exists(rootsFile));
    String written = Files.readString(rootsFile);
    assertTrue(written.contains("\"schemaVersion\" : 1"));
    assertTrue(written.contains("\"roots\""));

    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(ts, loaded.get(root.toAbsolutePath()));
  }

  @Test
  @DisplayName("A root's collection label round-trips, and a file without one loads as unlabeled")
  void collectionRoundTripsAndIsOptional() throws Exception {
    Path labeled = tempDir.resolve("labeled");
    Path unlabeled = tempDir.resolve("unlabeled");
    Files.createDirectories(labeled);
    Files.createDirectories(unlabeled);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    store.persistRoots(
        Map.of(
            labeled.toAbsolutePath(), WatchedRootsStore.NEVER_INDEXED,
            unlabeled.toAbsolutePath(), WatchedRootsStore.NEVER_INDEXED),
        Map.of(),
        java.util.Set.of(),
        Map.of(labeled.toAbsolutePath(), "my-notes"));

    assertTrue(Files.readString(rootsFile).contains("\"my-notes\""));

    var loaded = store.loadPersistedRootsWithErrors();
    assertEquals("my-notes", loaded.collections().get(labeled.toAbsolutePath()));
    assertNull(
        loaded.collections().get(unlabeled.toAbsolutePath()),
        "a root with no label must stay unlabeled rather than acquiring an empty one");
  }

  @Test
  @DisplayName("A pre-collection file loads with no labels instead of failing")
  void legacyFileWithoutCollectionField() throws Exception {
    Path root = tempDir.resolve("legacy");
    Files.createDirectories(root);
    Path rootsFile = tempDir.resolve("watched_roots.json");
    Files.writeString(
        rootsFile,
        "{\"schemaVersion\":1,\"roots\":[{\"path\":\""
            + root.toAbsolutePath().toString().replace("\\", "\\\\")
            + "\"}]}");

    var loaded = new WatchedRootsStore(rootsFile, null).loadPersistedRootsWithErrors();

    assertEquals(1, loaded.roots().size());
    assertTrue(loaded.collections().isEmpty(), "no label recorded, none invented");
  }

  @Test
  @DisplayName("Refuses a future version without changing it")
  void refusesFutureVersion() throws Exception {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    String future = "{\"schemaVersion\":99,\"roots\":[]}";
    Files.writeString(rootsFile, future);

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    assertThrows(UnsupportedStoreVersionException.class, store::loadPersistedRoots);
    assertEquals(future, Files.readString(rootsFile));
  }

  @Test
  @DisplayName("Refuses malformed state without treating it as empty")
  void refusesMalformedState() throws Exception {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    String malformed = "{not-json";
    Files.writeString(rootsFile, malformed);

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    assertThrows(CorruptDurableStoreException.class, store::loadPersistedRootsWithErrors);
    assertEquals(malformed, Files.readString(rootsFile));
  }

  @Test
  void heldReadLockIsUnavailableRatherThanCorruptForBothLoaders() throws Exception {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    String original = "{\"schemaVersion\":1,\"roots\":[]}";
    Files.writeString(rootsFile, original);
    var store = new WatchedRootsStore(rootsFile, null);
    List<Runnable> readers = List.of(store::loadPersistedRoots, store::loadPersistedRootsWithErrors);
    try (var channel = java.nio.channels.FileChannel.open(rootsFile,
        java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
        var held = channel.lock()) {
      assertTrue(held.isValid());
      for (Runnable reader : readers) {
        var failure = assertThrows(java.io.UncheckedIOException.class, reader::run);
        assertInstanceOf(io.justsearch.configuration.persistence.ContendedFileReads.FileReadContendedException.class,
            failure.getCause());
      }
    }
    assertEquals(original, Files.readString(rootsFile));
    assertTrue(store.loadPersistedRoots().isEmpty());
    assertTrue(store.loadPersistedRootsWithErrors().roots().isEmpty());
  }

  @Test
  void invalidUtf8InsideJsonStringRemainsCorruptForBothLoaders() throws Exception {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.writeBytes("{\"schemaVersion\":1,\"roots\":[{\"path\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    bytes.writeBytes(new byte[] {(byte) 0xc3, 0x28});
    bytes.writeBytes("\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] original = bytes.toByteArray();
    Files.write(rootsFile, original);
    var store = new WatchedRootsStore(rootsFile, null);
    assertThrows(CorruptDurableStoreException.class, store::loadPersistedRoots);
    assertThrows(CorruptDurableStoreException.class, store::loadPersistedRootsWithErrors);
    assertArrayEquals(original, Files.readAllBytes(rootsFile));
  }

  @Test
  void interruptedAuthorityReadRetainsInterruptionWithoutClaimingCorruption() throws Exception {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    Files.writeString(rootsFile, "{\"schemaVersion\":1,\"roots\":[]}");
    var store = new WatchedRootsStore(rootsFile, null);
    List<Runnable> readers = List.of(store::loadPersistedRoots, store::loadPersistedRootsWithErrors);
    for (Runnable reader : readers) {
      try {
        Thread.currentThread().interrupt();
        var failure = assertThrows(java.io.UncheckedIOException.class, reader::run);
        assertInstanceOf(java.io.InterruptedIOException.class, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }
}
