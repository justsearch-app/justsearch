package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.FileMemoryStore;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.memory.MemoryRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MemoryUnlockAvailabilityTest {
  private static MemoryRecord record(String id) {
    return new MemoryRecord(id, "fact", id, "conv", "agent", Instant.EPOCH);
  }

  @Test void publishedKeyBeforeListenerProtectsReadAndWrite(@TempDir Path dir) throws Exception {
    var keys = new DataKeyManager(new EncryptionKeystore(dir));
    keys.setup("long-test-passphrase".toCharArray());
    var cipher = new StoreCipher(keys);
    new FileMemoryStore(dir, cipher).remember(record("old"));
    keys.lock();
    var store = new FileMemoryStore(dir, cipher);
    var published = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    keys.addListener((from, to) -> {
      if (to == DataKeyManager.State.UNLOCKED) {
        published.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        } catch (InterruptedException e) { throw new AssertionError(e); }
      }
    });
    keys.addListener((from, to) -> {
      if (to == DataKeyManager.State.UNLOCKED) store.onKeyUnlocked();
      else if (to == DataKeyManager.State.LOCKED) store.onKeyLocked();
    });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var unlock = pool.submit(() -> keys.unlock("long-test-passphrase".toCharArray()));
      assertTrue(published.await(10, TimeUnit.SECONDS));
      try {
        // Store access occurs while unlock owns the key manager monitor. A callback through a
        // synchronized key accessor would deadlock; bounded futures make that failure observable.
        var read = pool.submit(store::whatItKnows);
        assertEquals(List.of(record("old")), read.get(5, TimeUnit.SECONDS));
        var write = pool.submit(() -> store.remember(record("new")));
        write.get(5, TimeUnit.SECONDS);
      } finally { release.countDown(); }
      unlock.get(5, TimeUnit.SECONDS);
      assertEquals(List.of(record("old"), record("new")), new FileMemoryStore(dir, cipher).whatItKnows());
      pool.submit(keys::lock).get(5, TimeUnit.SECONDS);
      assertTrue(store.whatItKnows().isEmpty());
      pool.submit(() -> keys.unlock("long-test-passphrase".toCharArray())).get(5, TimeUnit.SECONDS);
      assertEquals(2, store.whatItKnows().size());
    }
  }

  @Test void writeFirstBeforeListenerLoadsExistingMemories(@TempDir Path dir) throws Exception {
    var keys = new DataKeyManager(new EncryptionKeystore(dir));
    keys.setup("long-test-passphrase".toCharArray());
    var cipher = new StoreCipher(keys);
    new FileMemoryStore(dir, cipher).remember(record("old"));
    keys.lock();
    var store = new FileMemoryStore(dir, cipher);
    var published = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    keys.addListener((from, to) -> {
      published.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out"); }
      catch (InterruptedException e) { throw new AssertionError(e); }
      store.onKeyUnlocked();
    });
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var unlock = pool.submit(() -> keys.unlock("long-test-passphrase".toCharArray()));
      assertTrue(published.await(10, TimeUnit.SECONDS));
      try { pool.submit(() -> store.remember(record("new"))).get(5, TimeUnit.SECONDS); }
      finally { release.countDown(); }
      unlock.get(5, TimeUnit.SECONDS);
      assertEquals(List.of(record("old"), record("new")), new FileMemoryStore(dir, cipher).whatItKnows());
    }
  }

  @Test void everyUnlockTransitionGuardsFailedLoads(@TempDir Path dir) throws Exception {
    for (String mode : List.of("passphrase", "recovery", "setup", "change")) {
      Path root = Files.createDirectory(dir.resolve(mode));
      var keys = new DataKeyManager(new EncryptionKeystore(root));
      String recovery = mode.equals("setup") ? null : keys.setup("long-test-passphrase".toCharArray());
      if (!mode.equals("setup")) keys.lock();
      Path file = root.resolve("memory.json");
      // Setup begins with a readable plaintext cache; all other paths begin locked.
      Files.writeString(file, "[]");
      var store = new FileMemoryStore(root, new StoreCipher(keys));
      keys.addListener((from, to) -> { if (to == DataKeyManager.State.UNLOCKED) store.onKeyUnlocked(); });
      String broken = "[{\"id\":\"old\",\"content\":\"kept\"},{\"content\":\"bad\"}]";
      Files.writeString(file, broken);
      switch (mode) {
        case "passphrase" -> keys.unlock("long-test-passphrase".toCharArray());
        case "recovery" -> keys.recover(recovery);
        case "setup" -> keys.setup("long-test-passphrase".toCharArray());
        case "change" -> keys.changePassphrase("long-test-passphrase".toCharArray(), "new-long-passphrase".toCharArray());
        default -> throw new AssertionError(mode);
      }
      RuntimeException failure = assertThrows(RuntimeException.class, () -> store.remember(record("new")), mode);
      assertEquals("MemoriesUnavailableException", failure.getClass().getSimpleName());
      assertEquals(broken, Files.readString(file));
      Files.writeString(file, "[{\"id\":\"old\",\"content\":\"kept\"}]");
      var transitions = new java.util.concurrent.atomic.AtomicInteger();
      keys.addListener((from, to) -> transitions.incrementAndGet());
      keys.unlock((mode.equals("change") ? "new-long-passphrase" : "long-test-passphrase").toCharArray());
      assertEquals(0, transitions.get(), "an already unlocked key does not fire listeners");
      assertEquals("old", store.whatItKnows().getFirst().id());
      store.remember(record("new"));
      assertEquals(2, new FileMemoryStore(root, new StoreCipher(keys)).whatItKnows().size());
    }
  }
}
