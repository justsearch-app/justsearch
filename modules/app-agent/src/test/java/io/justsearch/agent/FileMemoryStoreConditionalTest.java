/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.memory.MemoryRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMemoryStoreConditionalTest {
  @TempDir Path dir;

  private static MemoryRecord record(String content) {
    return new MemoryRecord("same", "fact", content, "conv", "primary", Instant.EPOCH);
  }

  // Reflection lets the identical probe run on the baseline and fail because the operation is absent.
  private static String revision(FileMemoryStore store) throws Exception {
    Object entry = ((List<?>) FileMemoryStore.class.getMethod("versionedMemories").invoke(store)).getFirst();
    return (String) entry.getClass().getMethod("revision").invoke(entry);
  }

  private static Object forget(FileMemoryStore store, String revision) throws Exception {
    return FileMemoryStore.class.getMethod("forgetIf", String.class, String.class)
        .invoke(store, "same", revision);
  }

  private static Object field(Object result, String name) throws Exception {
    return result.getClass().getMethod(name).invoke(result);
  }

  @Test
  void staleRevisionKeepsAndReportsReplacementThenCurrentRevisionRemovesIt() throws Exception {
    var store = new FileMemoryStore(dir);
    store.remember(record("original"));
    String stale = revision(store);
    store.remember(record("replacement"));
    Object refused = forget(store, stale);
    assertEquals(false, field(refused, "removed"));
    assertEquals(record("replacement"), field(refused, "current"));
    assertEquals(List.of(record("replacement")), new FileMemoryStore(dir).whatItKnows());
    Object removed = forget(store, revision(store));
    assertEquals(true, field(removed, "removed"));
    assertNull(field(removed, "current"));
    assertTrue(new FileMemoryStore(dir).whatItKnows().isEmpty());
    assertEquals(false, field(forget(store, stale), "removed"));
  }

  @Test
  void identicalReinsertionAndRestartInvalidateButReadsOrderFormattingAndLockDoNot() throws Exception {
    var key = new io.justsearch.agent.api.encryption.DataKeyState() {
      boolean locked;
      public boolean enabled() { return false; }
      public boolean locked() { return locked; }
      public byte[] dek() { return new byte[32]; }
    };
    var store = new FileMemoryStore(dir, new io.justsearch.agent.api.encryption.StoreCipher(key));
    store.remember(record("original"));
    String first = revision(store);
    assertEquals(first, revision(store));
    store.remember(new MemoryRecord("older", "fact", "older", null, null, Instant.MIN));
    assertEquals(first, revision(store));
    Path file = dir.resolve("memory.json");
    String saved = Files.readString(file);
    Files.writeString(file, saved.replace("\n", " "));
    store.onKeyLocked();
    store.onKeyUnlocked();
    assertEquals(first, revision(store));
    assertFalse(Files.readString(file).contains("revision"));
    assertNotEquals(first, revision(new FileMemoryStore(dir)));
    assertNotNull(field(forget(new FileMemoryStore(dir), first), "current"));
    store.remember(record("original"));
    assertNotEquals(first, revision(store));
    assertNotNull(field(forget(store, first), "current"));
    String second = revision(store);
    store.forget("same");
    store.remember(record("original"));
    assertNotEquals(second, revision(store));
    assertNotNull(field(forget(store, second), "current"));
  }

  @Test
  void encryptedLockDoesNotChangeRevisionOrAllowConditionalWrites() throws Exception {
    var key = new io.justsearch.agent.api.encryption.DataKeyState() {
      boolean locked;
      public boolean enabled() { return true; }
      public boolean locked() { return locked; }
      public byte[] dek() { return new byte[32]; }
    };
    var store = new FileMemoryStore(dir, new io.justsearch.agent.api.encryption.StoreCipher(key));
    store.remember(record("secret"));
    String before = revision(store);
    key.locked = true;
    store.onKeyLocked();
    assertTrue(store.whatItKnows().isEmpty());
    var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> forget(store, before));
    assertInstanceOf(io.justsearch.agent.api.encryption.KeyLockedException.class, failure.getCause());
    key.locked = false;
    store.onKeyUnlocked();
    assertEquals(before, revision(store));
  }

  @Test
  void everyRecordFieldAndUnambiguousBoundariesAffectRevision() throws Exception {
    var store = new FileMemoryStore(dir);
    store.remember(record("original"));
    String original = revision(store);
    String json = Files.readString(dir.resolve("memory.json"));
    for (String[] change : List.of(
        new String[]{"same", "other"}, new String[]{"fact", "preference"},
        new String[]{"original", "updated"}, new String[]{"conv", "conversation"},
        new String[]{"primary", "secondary"}, new String[]{"1970-01-01T00:00:00Z", "1970-01-01T00:00:01Z"})) {
      Files.writeString(dir.resolve("memory.json"), json.replace(change[0], change[1]));
      store.onKeyUnlocked();
      assertNotEquals(original, revision(store), change[0]);
    }
    for (MemoryRecord changed : List.of(
        new MemoryRecord("same", "ab", "c", null, null, Instant.EPOCH),
        new MemoryRecord("same", "a", "bc", null, null, Instant.EPOCH),
        new MemoryRecord("same", "fact", "original", "", null, Instant.EPOCH))) {
      store.remember(changed);
      assertNotEquals(original, revision(store));
    }
  }

  @Test
  void legacyForgetRememberAndFormatArePreserved() throws Exception {
    var store = new FileMemoryStore(dir);
    store.remember(record("original"));
    store.remember(record("replacement"));
    assertEquals(List.of(record("replacement")), new FileMemoryStore(dir).whatItKnows());
    String saved = Files.readString(dir.resolve("memory.json"));
    assertTrue(saved.contains("\"schemaVersion\" : 1"));
    assertFalse(saved.contains("revision"));
    assertFalse(saved.contains("incarnation"));
    store.forget("same");
    store.forget("same");
    assertTrue(new FileMemoryStore(dir).whatItKnows().isEmpty());
  }
}
