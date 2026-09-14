/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema 1 -> 2 migration (tempdoc 883): a stored {@code contextLength} of 4096 is the pre-883
 * shipped default and becomes 0 = auto.
 *
 * <p>Every existing install has 4096 on disk — {@code settings.json} is serialized whole on every
 * save, and saves fire from AI install and activation — so without this migration the derived
 * window would be unreachable on every upgraded machine while looking perfectly configured.
 *
 * <p>The first assertion here is not the migration itself but that a version-1 file is READ at all:
 * {@code StoreFormatVersions.requireReadable} throws for any version not listed as readable-legacy,
 * and {@code load()} does not catch that, so a bump without the listing bricks startup rather than
 * quarantining the file.
 */
@DisplayName("UiSettingsStore schema 1 -> 2 migration")
final class UiSettingsStoreContextLengthMigrationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a schema-1 file is migratable, not fatal and not quarantined")
  void schemaOneIsReadable() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":1,\"settings\":{\"maxTokens\":777}}");

    UiSettingsStore store = new UiSettingsStore(READ_WRITE, file);
    UiSettings loaded = store.load();

    assertEquals(777, loaded.getMaxTokens(), "the payload must survive the version bump");
    assertTrue(
        store.lastRecovery().isEmpty(),
        "an older schema is a migration, not a corruption — quarantining it would silently discard"
            + " the user's preferences");
  }

  @Test
  @DisplayName("schema-1 contextLength 4096 (the old default) migrates to 0 = auto")
  void legacyDefaultBecomesAuto() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":1,\"settings\":{\"contextLength\":4096}}");

    assertEquals(0, new UiSettingsStore(READ_WRITE, file).load().getContextLength());
  }

  @Test
  @DisplayName("a schema-1 value that is not the old default is preserved as a real override")
  void deliberateOverrideSurvives() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":1,\"settings\":{\"contextLength\":16384}}");

    assertEquals(16384, new UiSettingsStore(READ_WRITE, file).load().getContextLength());
  }

  @Test
  @DisplayName("an unversioned legacy file migrates the same way")
  void unversionedLegacyFileMigrates() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"contextLength\":4096,\"maxTokens\":1024}");

    assertEquals(0, new UiSettingsStore(READ_WRITE, file).load().getContextLength());
  }

  @Test
  @DisplayName("at schema 2 a deliberate 4096 is left alone — the migration is not re-applied")
  void currentVersionIsNotMigratedAgain() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":2,\"settings\":{\"contextLength\":4096}}");

    assertEquals(
        4096,
        new UiSettingsStore(READ_WRITE, file).load().getContextLength(),
        "at schema 2 the value can only have been set deliberately, because 0 is the default");
  }

  @Test
  @DisplayName("migration is idempotent across repeated loads before the next save")
  void migrationIsIdempotent() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":1,\"settings\":{\"contextLength\":4096}}");

    UiSettingsStore store = new UiSettingsStore(READ_WRITE, file);
    assertEquals(0, store.load().getContextLength());
    assertEquals(0, store.load().getContextLength());
  }

  @Test
  @DisplayName("a save after migration rewrites the file at the current version")
  void saveRewritesAtCurrentVersion() throws Exception {
    Path file = tempDir.resolve("settings.json");
    Files.writeString(file, "{\"schemaVersion\":1,\"settings\":{\"contextLength\":4096}}");

    UiSettingsStore store = new UiSettingsStore(READ_WRITE, file);
    store.save(store.load());

    String persisted = Files.readString(file);
    assertTrue(persisted.contains("\"schemaVersion\" : 4"), persisted);
    assertEquals(0, new UiSettingsStore(READ_WRITE, file).load().getContextLength());
  }

  @Test
  @DisplayName("a FUTURE version is still fatal — migrating forward is not the same as downgrading")
  void futureVersionStaysFatal() throws Exception {
    Path file = tempDir.resolve("settings.json");
    String future = "{\"schemaVersion\":99,\"settings\":{\"contextLength\":4096}}";
    Files.writeString(file, future);

    UiSettingsStore store = new UiSettingsStore(READ_WRITE, file);
    assertThrows(UnsupportedStoreVersionException.class, store::load);
    assertEquals(future, Files.readString(file), "the bytes must not be touched");
  }
}
