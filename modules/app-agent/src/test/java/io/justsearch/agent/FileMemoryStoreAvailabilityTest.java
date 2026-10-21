package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.memory.MemoryRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileMemoryStoreAvailabilityTest {
  private static final String VALID = "{\"id\":\"old\",\"kind\":\"preference\",\"content\":\"kept\",\"sourceConversationId\":\"conv\",\"actor\":\"chat\",\"createdAt\":\"2026-01-01T00:00:00Z\"}";

  static final class Key implements DataKeyState {
    boolean locked = true;
    final AtomicInteger opens = new AtomicInteger();
    @Override public boolean enabled() { return true; }
    @Override public boolean locked() { return locked; }
    @Override public byte[] dek() { opens.incrementAndGet(); return new byte[32]; }
  }

  private static MemoryRecord record(String id) {
    return new MemoryRecord(id, "fact", "new", "conv", "agent", Instant.now());
  }

  private static void unavailable(Runnable access) {
    RuntimeException failure = assertThrows(RuntimeException.class, access::run);
    assertEquals("MemoriesUnavailableException", failure.getClass().getSimpleName());
    assertTrue(failure.getMessage().contains("unavailable"));
  }

  @Test void failedPartialLoadRefusesEveryAccessAndRecovers(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("memory.json");
    String broken = "[" + VALID + ",{\"content\":\"missing id\"}]";
    Files.writeString(file, broken);
    Key key = new Key();
    var store = new FileMemoryStore(dir, new StoreCipher(key));
    key.locked = false;
    // The key listener's exception is isolated by DataKeyManager.
    assertThrows(RuntimeException.class, store::onKeyUnlocked);
    for (Runnable access : List.<Runnable>of(
        () -> store.remember(record("new")), () -> store.remember(record("old")),
        () -> store.forget("old"), () -> store.forget("unknown"), store::clear, store::whatItKnows)) {
      unavailable(access);
      assertEquals(broken, Files.readString(file));
    }
    Files.writeString(file, "[" + VALID + "]");
    assertEquals(List.of(new MemoryRecord("old", "preference", "kept", "conv", "chat", Instant.parse("2026-01-01T00:00:00Z"))), store.whatItKnows());
    store.remember(record("old"));
    assertEquals(1, new FileMemoryStore(dir, new StoreCipher(key)).whatItKnows().size());
    store.remember(record("new"));
    assertEquals(2, new FileMemoryStore(dir, new StoreCipher(key)).whatItKnows().size());
  }

  @Test void decryptFailureRetriesOncePerAccess(@TempDir Path dir) throws Exception {
    Key key = new Key();
    var cipher = new StoreCipher(key);
    key.locked = false;
    String good = cipher.seal("[" + VALID + "]");
    // A valid envelope encrypted with another key deterministically fails authentication.
    String bad = new StoreCipher(new DataKeyState() {
      public boolean enabled() { return true; }
      public boolean locked() { return false; }
      public byte[] dek() { byte[] bytes = new byte[32]; bytes[0] = 1; return bytes; }
    }).seal("[" + VALID + "]");
    Files.writeString(dir.resolve("memory.json"), bad);
    key.locked = true;
    var store = new FileMemoryStore(dir, cipher);
    key.locked = false;
    key.opens.set(0);
    for (int i = 1; i <= 3; i++) {
      unavailable(store::whatItKnows);
      assertEquals(i, key.opens.get(), "one decrypt attempt per access");
    }
    assertEquals(bad, Files.readString(dir.resolve("memory.json")));
    Files.writeString(dir.resolve("memory.json"), good);
    assertEquals("old", store.whatItKnows().getFirst().id());
  }

  @Test void indeterminateExistenceDoesNotBecomeFirstRun(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("memory.json");
    // A symlink loop makes exists() return false, but does not prove absence.
    Files.createSymbolicLink(file, Path.of("memory.json"));
    Key key = new Key();
    var store = new FileMemoryStore(dir, new StoreCipher(key));
    key.locked = false;
    unavailable(() -> store.remember(record("new")));
    assertTrue(Files.isSymbolicLink(file));
    Files.delete(file);
    assertTrue(store.whatItKnows().isEmpty(), "definite absence recovers as first run");
    store.remember(record("new"));
    assertEquals(1, new FileMemoryStore(dir, new StoreCipher(key)).whatItKnows().size());
  }

  @Test void ioFailureIsUnavailableAndRecovers(@TempDir Path dir) throws Exception {
    Path file = Files.createDirectory(dir.resolve("memory.json"));
    Key key = new Key();
    var store = new FileMemoryStore(dir, new StoreCipher(key));
    key.locked = false;
    unavailable(store::whatItKnows);
    unavailable(store::clear);
    assertTrue(Files.isDirectory(file));
    Files.delete(file);
    Files.writeString(file, "[" + VALID + "]");
    assertEquals(1, store.whatItKnows().size());
  }

  @Test void parseFailureRefusesAndRecovers(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("memory.json");
    Files.writeString(file, "{not-json");
    Key key = new Key();
    var store = new FileMemoryStore(dir, new StoreCipher(key));
    key.locked = false;
    unavailable(store::whatItKnows);
    unavailable(() -> store.remember(record("new")));
    assertEquals("{not-json", Files.readString(file));
    Files.writeString(file, "[" + VALID + "]");
    assertEquals(1, store.whatItKnows().size());
  }

  @Test void keyLockDuringReadinessCheckCannotReportEmpty(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("memory.json"), "[" + VALID + "]");
    var published = new java.util.concurrent.atomic.AtomicBoolean();
    var checks = new AtomicInteger();
    var store = new FileMemoryStore(dir, new StoreCipher(new DataKeyState() {
      public boolean enabled() { return true; }
      // Constructor: locked. First access pre-check: unlocked. Load pre-check: locked again.
      public boolean locked() { return !published.get() || checks.incrementAndGet() > 1; }
      public byte[] dek() { return new byte[32]; }
    }));
    published.set(true);
    assertThrows(io.justsearch.agent.api.encryption.KeyLockedException.class, store::whatItKnows);
  }

  @Test void persistenceRollbackAndDeletionSurviveRestart(@TempDir Path dir) throws Exception {
    var store = new FileMemoryStore(dir);
    MemoryRecord first = record("old");
    store.remember(first);
    Path file = dir.resolve("memory.json");
    Path saved = dir.resolve("saved.json");
    Files.move(file, saved);
    Files.createDirectory(file);
    assertThrows(RuntimeException.class, () -> store.remember(record("phantom")));
    assertEquals(List.of(first), store.whatItKnows());
    Files.delete(file);
    Files.move(saved, file);
    store.forget("old");
    assertTrue(new FileMemoryStore(dir).whatItKnows().isEmpty());
    store.remember(first);
    store.clear();
    assertTrue(new FileMemoryStore(dir).whatItKnows().isEmpty());
  }
}
