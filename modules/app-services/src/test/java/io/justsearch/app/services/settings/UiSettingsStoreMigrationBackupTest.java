/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import java.io.IOException;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiSettingsStoreMigrationBackupTest {
  @TempDir Path dir;

  @Test
  void legacyBytesSurviveMigrationAndAnotherBootCannotOverwriteThem() throws Exception {
    Path file = dir.resolve("settings.json");
    byte[] original = ("{\r\n  \"schemaVersion\":2, \"settings\":{\"maxTokens\":777},"
        + "\"unknown\":\"caf\u00e9\"\r\n}\r\n").getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    var first = new UiSettingsStore(READ_WRITE, file);
    assertEquals(777, first.load().getMaxTokens());
    Path backup = dir.resolve("settings.v2.bak.json");
    assertArrayEquals(original, Files.readAllBytes(backup));
    first.replacePrepared(first.prepare(first.load(), new SettingsWitness(0, null)));
    assertEquals(777, new UiSettingsStore(READ_WRITE, file).load().getMaxTokens());
    assertArrayEquals(original, Files.readAllBytes(backup));
    // Reintroducing a different legacy file must also retain the first snapshot.
    Files.writeString(file, "{\"schemaVersion\":2,\"settings\":{\"maxTokens\":888}}");
    assertEquals(888, new UiSettingsStore(READ_WRITE, file).load().getMaxTokens());
    assertArrayEquals(original, Files.readAllBytes(backup));
  }

  @Test
  void currentVersionLoadDoesNotCreateABackup() throws Exception {
    Path file = dir.resolve("settings.json");
    var store = new UiSettingsStore(READ_WRITE, file);
    store.replacePrepared(store.prepare(new UiSettings(), new SettingsWitness(0, null)));
    new UiSettingsStore(READ_WRITE, file).load();
    try (var paths = Files.list(dir)) {
      assertEquals(java.util.List.of("settings.json"),
          paths.map(p -> p.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void preservationFailureDoesNotQuarantineValidSettings() throws Exception {
    Path file = dir.resolve("settings.json");
    String legacy = "{\"schemaVersion\":2,\"settings\":{\"maxTokens\":777}}";
    Files.writeString(file, legacy);
    // An invalid backup destination must neither be overwritten nor quarantine valid settings.
    var store = new UiSettingsStore(READ_WRITE, file);
    Path backup = dir.resolve("settings.v2.bak.json");
    Files.createDirectory(backup);
    assertEquals(777, assertDoesNotThrow(store::load).getMaxTokens());
    assertTrue(Files.isDirectory(backup));
    assertEquals(legacy, Files.readString(file));
    assertTrue(store.lastRecovery().isEmpty());
  }
  @Test
  void everyBackupWriteFailureStillReturnsMigratedSettings() throws Exception {
    Path file = dir.resolve("settings.json");
    String legacy = "{\"schemaVersion\":1,\"settings\":{\"maxTokens\":777,\"contextLength\":4096}}";
    Files.writeString(file, legacy);
    var store = new UiSettingsStore(READ_WRITE, file);
    try (var writes = org.mockito.Mockito.mockStatic(AtomicFileWrites.class, invocation -> {
      throw new IOException("all writes denied by test filesystem");
    })) {
      var settings = assertDoesNotThrow(store::load);
      assertEquals(777, settings.getMaxTokens());
      assertEquals(0, settings.getContextLength(), "legacy default must still migrate");
      writes.verify(() -> AtomicFileWrites.createOnceStrict(
          org.mockito.ArgumentMatchers.eq(dir.resolve("settings.v1.bak.json")),
          org.mockito.ArgumentMatchers.any(byte[].class)));
    }
    assertEquals(legacy, Files.readString(file));
    assertTrue(store.lastRecovery().isEmpty());
    assertFalse(Files.exists(dir.resolve("settings.v1.bak.json")));
  }

}
