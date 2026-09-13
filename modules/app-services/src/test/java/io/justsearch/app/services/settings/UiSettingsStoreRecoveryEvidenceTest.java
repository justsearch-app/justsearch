/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiSettingsStoreRecoveryEvidenceTest {
  @TempDir Path directory;

  @Test
  void bindsEveryNameAndByteAcrossRestartWithoutDependingOnEnumerationOrder() throws Exception {
    Path file = directory.resolve("settings.json");
    var store = new UiSettingsStore(READ_WRITE, file);
    Path second = directory.resolve("settings.json.corrupt-b");
    Path first = directory.resolve("settings.json.corrupt-a");
    Files.writeString(second, "second");
    Files.writeString(first, "first");
    String original = store.recoveryFingerprint();
    // Independently calculated SHA-256 over the specified v1 bytes (Python hashlib/struct).
    assertEquals("d537ca8993172d84d6299f920a7f90d58f456f0a37f5d274eb9c43939bfe1df3", original);
    assertEquals(original, new UiSettingsStore(READ_WRITE, file).recoveryFingerprint());
    Files.writeString(directory.resolve("unrelated"), "ignored");
    assertEquals(original, store.recoveryFingerprint());
    Files.delete(second);
    Files.delete(first);
    Files.writeString(first, "first");
    Files.writeString(second, "second");
    assertEquals(original, store.recoveryFingerprint());
    Files.writeString(first, "other");
    assertNotEquals(original, store.recoveryFingerprint(), "same name and byte length still bind content");
    Files.writeString(first, "first");
    assertEquals(original, store.recoveryFingerprint());
    Files.move(first, directory.resolve("settings.json.corrupt-renamed"));
    assertNotEquals(original, store.recoveryFingerprint(), "identical content under a different name differs");
  }

  @Test
  void finalMembershipValidationRejectsCaseOnlyRenameOnWindowsToo() throws Exception {
    var before = java.util.List.of(directory.resolve("settings.json.corrupt-A"));
    var after = java.util.List.of(directory.resolve("settings.json.corrupt-a"));
    UiSettingsStore.requireSameQuarantineNames(before, before);
    assertThrows(IOException.class, () -> UiSettingsStore.requireSameQuarantineNames(before, after));
  }

  @Test
  void addedAndRemovedEvidenceCannotMatchTheAcceptedReset() throws Exception {
    Path file = directory.resolve("settings.json");
    var store = new UiSettingsStore(READ_WRITE, file);
    Path first = directory.resolve("settings.json.corrupt-a");
    Path second = directory.resolve("settings.json.corrupt-b");
    Files.writeString(first, "original corruption");
    String original = store.recoveryFingerprint();
    Files.writeString(second, "later committed reset became corrupt");
    assertNotEquals(original, store.recoveryFingerprint());
    Files.delete(first);
    assertNotEquals(original, store.recoveryFingerprint());
    Files.delete(second);
    assertThrows(IOException.class, store::recoveryFingerprint);
  }

  @Test
  void refusesLiveFutureFileAndNonRegularOrMissingEvidence() throws Exception {
    Path file = directory.resolve("settings.json");
    var store = new UiSettingsStore(READ_WRITE, file);
    assertThrows(IOException.class, store::recoveryFingerprint);
    Path backup = directory.resolve("settings.json.corrupt-a");
    Files.createDirectory(backup);
    assertThrows(IOException.class, store::recoveryFingerprint);
    Files.delete(backup);
    Files.writeString(backup, "corrupt bytes");
    Files.writeString(file, "{\"schemaVersion\":999,\"settings\":{}}");
    assertThrows(IOException.class, store::recoveryFingerprint);
    assertTrue(Files.readString(file).contains("999"));
    Files.delete(file);
    assertThrows(IOException.class, new UiSettingsStore(IN_MEMORY, file)::recoveryFingerprint);
    assertThrows(IOException.class,
        new UiSettingsStore(READ_WRITE, directory.resolve("missing/settings.json"))::recoveryFingerprint);
  }

  @Test
  void actualQuarantineSurvivesRestartAndLaterCorruptionChangesIdentity() throws Exception {
    Path file = directory.resolve("settings.json");
    Files.writeString(file, "broken original");
    var store = new UiSettingsStore(READ_WRITE, file);
    store.load();
    String original = store.recoveryFingerprint();
    assertFalse(Files.exists(file));
    var restarted = new UiSettingsStore(READ_WRITE, file);
    assertEquals(original, restarted.recoveryFingerprint());
    Files.writeString(file, "broken later reset");
    restarted.load();
    assertNotEquals(original, restarted.recoveryFingerprint());
    assertEquals(restarted.recoveryFingerprint(), store.recoveryFingerprint());
  }
}
