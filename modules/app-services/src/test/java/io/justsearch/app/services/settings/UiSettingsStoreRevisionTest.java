/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiSettingsStoreRevisionTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";
  @TempDir Path directory;

  @Test
  void preparesPrivateCandidateBeforeReplacingFile() throws Exception {
    Path path = directory.resolve("settings.json");
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
    UiSettings initial = new UiSettings();
    initial.setMaxTokens(100);
    store.save(initial);
    byte[] before = Files.readAllBytes(path);
    UiSettings next = store.load();
    next.setMaxTokens(200);
    next.setExcludePatterns(new java.util.ArrayList<>(java.util.List.of("original")));
    var prepared = store.prepare(next, new UiSettingsStore.Witness(1, KEY));
    next.setMaxTokens(300);
    next.getExcludePatterns().add("mutated-original");
    prepared.settings().setMaxTokens(400);
    prepared.settings().getExcludePatterns().add("mutated-returned-copy");
    assertArrayEquals(before, Files.readAllBytes(path));
    assertEquals(200, prepared.settings().getMaxTokens());
    store.replacePrepared(prepared);
    var snapshot = new UiSettingsStore(READ_WRITE, path).inspect();
    assertEquals(200, snapshot.settings().getMaxTokens());
    assertEquals(java.util.List.of("original"), snapshot.settings().getExcludePatterns());
    assertEquals(new UiSettingsStore.Witness(1, KEY), snapshot.witness());
    assertThrows(IllegalStateException.class, () -> store.save(initial));
    assertEquals(snapshot.witness(), store.inspect().witness());
  }

  @Test
  void legacyTwoStartsAtZeroAndOldReaderRefusesThree() throws Exception {
    Path path = directory.resolve("settings.json");
    Files.writeString(path, "{\"schemaVersion\":2,\"settings\":{\"contextLength\":4096}}");
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
    assertEquals(new UiSettingsStore.Witness(0, null), store.inspect().witness());
    assertEquals(4096, store.load().getContextLength());
    store.save(store.load());
    assertTrue(Files.readString(path).contains("\"schemaVersion\" : 3"));
    assertThrows(UnsupportedStoreVersionException.class,
        () -> StoreFormatVersions.requireReadable("ui-settings", 3, 2, 0, 0, 1));
  }

  @Test
  void invalidWitnessCannotBecomeSuccessfulDefaultDuringInspection() throws Exception {
    Path path = directory.resolve("settings.json");
    String[] invalid = {
      "\"acceptedRevision\":1,\"lastCommittedOperationKey\":null",
      "\"acceptedRevision\":0,\"lastCommittedOperationKey\":\"" + KEY + "\"",
      "\"acceptedRevision\":-1,\"lastCommittedOperationKey\":null",
      "\"acceptedRevision\":1,\"lastCommittedOperationKey\":\"not-a-key\"",
      "\"acceptedRevision\":9223372036854775808,\"lastCommittedOperationKey\":null",
      "\"acceptedRevision\":0.5,\"lastCommittedOperationKey\":null",
      "\"acceptedRevision\":0"
    };
    for (String fields : invalid) {
      String raw = "{\"schemaVersion\":3,\"settings\":{}," + fields + "}";
      Files.writeString(path, raw);
      UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
      assertThrows(CorruptDurableStoreException.class, store::inspect, fields);
      assertEquals(raw, Files.readString(path));
      assertTrue(store.lastRecovery().isEmpty());
      store.load();
      assertTrue(store.lastRecovery().isPresent());
      assertThrows(CorruptDurableStoreException.class, store::inspect);
      UiSettingsStore restarted = new UiSettingsStore(READ_WRITE, path);
      restarted.load();
      assertThrows(CorruptDurableStoreException.class, restarted::inspect);
      assertThrows(CorruptDurableStoreException.class, () -> store.save(new UiSettings()));
      assertThrows(CorruptDurableStoreException.class, () -> restarted.save(new UiSettings()));
    }
  }

  @Test
  void oversizedVersionCannotWrapIntoReadableLegacySchema() throws Exception {
    Path path = directory.resolve("settings.json");
    String raw = "{\"schemaVersion\":4294967298,\"settings\":{}}";
    Files.writeString(path, raw);
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
    assertThrows(CorruptDurableStoreException.class, store::inspect);
    assertEquals(raw, Files.readString(path));
  }

  @Test
  void incompleteEnvelopesCannotMasqueradeAsLegacyDefaults() throws Exception {
    Path path = directory.resolve("settings.json");
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
    Files.writeString(path, "{\"schemaVersion\":3,\"acceptedRevision\":1,\"lastCommittedOperationKey\":\"" + KEY + "\"}");
    assertThrows(CorruptDurableStoreException.class, store::inspect);
    Files.writeString(path, "{\"schemaVersion\":2,\"settings\":{},\"acceptedRevision\":1,\"lastCommittedOperationKey\":\"" + KEY + "\"}");
    assertThrows(CorruptDurableStoreException.class, store::inspect);
    assertThrows(CorruptDurableStoreException.class, () -> store.save(new UiSettings()));
    Files.writeString(path, "{\"schemaVersion\":99}");
    assertThrows(UnsupportedStoreVersionException.class, store::inspect);
    Files.writeString(path, "{\"schemaVersion\":1,\"maxTokens\":987}");
    assertEquals(987, store.inspect().settings().getMaxTokens());
    assertEquals(new UiSettingsStore.Witness(0, null), store.inspect().witness());
  }

  @Test
  void nonDirectoryParentCannotProveEmptyStore() throws Exception {
    Path parent = directory.resolve("not-a-directory");
    Files.writeString(parent, "obstruction");
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, parent.resolve("settings.json"));
    assertThrows(RuntimeException.class, store::inspect);
  }

  @Test
  void foreignPreparationAndReadOnlyModeCannotWrite() throws Exception {
    UiSettingsStore first = new UiSettingsStore(READ_WRITE, directory.resolve("a.json"));
    UiSettingsStore second = new UiSettingsStore(READ_WRITE, directory.resolve("b.json"));
    var prepared = first.prepare(new UiSettings(), new UiSettingsStore.Witness(1, KEY));
    assertThrows(IllegalArgumentException.class, () -> second.replacePrepared(prepared));
    assertFalse(Files.exists(second.settingsPath()));
    UiSettingsStore memory = new UiSettingsStore(IN_MEMORY, directory.resolve("memory.json"));
    assertThrows(IllegalStateException.class,
        () -> memory.prepare(new UiSettings(), new UiSettingsStore.Witness(1, KEY)));
    memory.save(new UiSettings());
    assertFalse(Files.exists(memory.settingsPath()));
  }

  @Test
  void recoveryNotificationIsOutsideReplacement() throws Exception {
    Path path = directory.resolve("settings.json");
    Files.writeString(path, "broken");
    UiSettingsStore store = new UiSettingsStore(READ_WRITE, path);
    UiSettings recovered = store.load();
    store.setOnRecoveryCleared(() -> { throw new IllegalStateException("listener"); });
    var prepared = store.prepare(recovered, new UiSettingsStore.Witness(1, KEY));
    store.replacePrepared(prepared);
    assertEquals(new UiSettingsStore.Witness(1, KEY), store.inspect().witness());
    assertThrows(IllegalStateException.class, store::notifyRecoveryCleared);
    assertEquals(new UiSettingsStore.Witness(1, KEY), store.inspect().witness());
  }
}
