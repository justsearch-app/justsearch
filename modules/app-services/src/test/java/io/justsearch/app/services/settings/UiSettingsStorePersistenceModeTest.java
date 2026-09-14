package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UiSettingsStore.PersistenceMode#resolveMode()}.
 *
 * <p>Note: Environment variable code paths ({@code JUSTSEARCH_UI_SETTINGS_MODE}, {@code
 * JUSTSEARCH_UI_SETTINGS_READONLY}) cannot be tested because {@link System#getenv()} is immutable
 * at runtime. The env fallback logic uses the same parsing as sysprops once a value is obtained.
 */
@DisplayName("UiSettingsStore.PersistenceMode.resolveMode()")
class UiSettingsStorePersistenceModeTest {

  private static final String MODE_PROP = "justsearch.ui.settings.mode";
  private static final String READONLY_PROP = "justsearch.ui.settings.readOnly";
  private static final String PROD_PROP = "justsearch.prod";

  @Nested
  @DisplayName("Explicit mode override (highest priority)")
  class ExplicitModeOverride {

    @Test
    @DisplayName("justsearch.ui.settings.mode=in_memory returns IN_MEMORY")
    void explicitModeInMemory_viaSysprop() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "in_memory")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.mode=read_write returns READ_WRITE")
    void explicitModeReadWrite_viaSysprop() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "read_write")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode overrides prod mode")
    void explicitModeOverridesProdMode() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "read_write").set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode overrides readOnly flag")
    void explicitModeOverridesReadOnly() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "read_write").set(READONLY_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Mode aliases (parseMode coverage)")
  class ModeAliases {

    @ParameterizedTest(name = "mode={0} → READ_WRITE")
    @ValueSource(strings = {"rw", "read_write", "file", "persist"})
    @DisplayName("READ_WRITE aliases")
    void parseModeAliases_readWrite(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} → IN_MEMORY")
    @ValueSource(strings = {"memory", "in_memory", "mem", "readonly", "read_only"})
    @DisplayName("IN_MEMORY aliases")
    void parseModeAliases_inMemory(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Invalid mode value falls through to next check")
    void parseModeInvalid_fallsThrough() {
      // Invalid mode should fall through; with readOnly=true, resolves to IN_MEMORY
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "invalid").set(READONLY_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Blank mode value falls through to next check")
    void parseModeBlank_fallsThrough() {
      // Blank mode should fall through; with readOnly=true, resolves to IN_MEMORY
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "   ").set(READONLY_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} with mixed case → READ_WRITE")
    @ValueSource(strings = {"RW", "Read_Write", "FILE", "PERSIST"})
    @DisplayName("Case insensitivity for READ_WRITE aliases")
    void parseModeAliases_caseInsensitive_readWrite(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} with mixed case → IN_MEMORY")
    @ValueSource(strings = {"MEMORY", "In_Memory", "MEM", "ReadOnly", "READ_ONLY"})
    @DisplayName("Case insensitivity for IN_MEMORY aliases")
    void parseModeAliases_caseInsensitive_inMemory(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Whitespace-padded value is trimmed before parsing")
    void parseModeTrimmed_whitespace() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "  rw  ")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Read-only flags (second priority)")
  class ReadOnlyFlags {

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=true returns IN_MEMORY")
    void readOnlySysprop_returnsInMemory() {
      try (var ignored = new SysProps().clearAll().set(READONLY_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=false falls through to the default, even in prod")
    void readOnlyFalse_fallsThrough() {
      try (var ignored =
          new SysProps().clearAll().set(READONLY_PROP, "false").set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=false with no prod defaults to READ_WRITE")
    void readOnlyFalse_noProd_defaultsToReadWrite() {
      try (var ignored = new SysProps().clearAll().set(READONLY_PROP, "false")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Prod mode is not a persistence axis (tempdoc 804 §B1)")
  class ProdMode {

    @Test
    @DisplayName("justsearch.prod=true with no explicit mode returns READ_WRITE")
    void prodMode_doesNotImplyInMemory() {
      // The shipped desktop app boots with justsearch.prod=true; settings must still persist.
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.prod=false falls to default READ_WRITE")
    void prodModeFalse_fallsToDefault() {
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "false")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Default behavior")
  class DefaultBehavior {

    @Test
    @DisplayName("No overrides defaults to READ_WRITE")
    void noOverrides_defaultsToReadWrite() {
      try (var ignored = new SysProps().clearAll()) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Priority order verification")
  class PriorityOrder {

    @Test
    @DisplayName("Explicit mode beats readOnly flag")
    void priorityOrder_explicitBeatsReadOnly() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "rw").set(READONLY_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode beats both readOnly and prod")
    void priorityOrder_explicitBeatsAll() {
      try (var ignored =
          new SysProps()
              .clearAll()
              .set(MODE_PROP, "rw")
              .set(READONLY_PROP, "true")
              .set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("readOnly flag beats the default regardless of prod mode")
    void priorityOrder_readOnlyBeatsDefault() {
      try (var ignored =
          new SysProps().clearAll().set(READONLY_PROP, "true").set(PROD_PROP, "false")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Prod mode does not beat the default")
    void priorityOrder_prodDoesNotBeatDefault() {
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("isWritable() method")
  class IsWritableMethod {

    @Test
    @DisplayName("READ_WRITE.isWritable() returns true")
    void readWriteIsWritable() {
      assertEquals(true, READ_WRITE.isWritable());
    }

    @Test
    @DisplayName("IN_MEMORY.isWritable() returns false")
    void inMemoryIsNotWritable() {
      assertEquals(false, IN_MEMORY.isWritable());
    }
  }

  @Nested
  @DisplayName("load() behavior")
  class LoadBehavior {

    @TempDir Path tempDir;

    @Test
    @DisplayName("IN_MEMORY mode returns defaults without reading disk")
    void inMemory_loadReturnsDefaults() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      Files.writeString(settingsFile, "{\"maxTokens\": 999}");

      var store = new UiSettingsStore(IN_MEMORY, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(new UiSettings().getMaxTokens(), loaded.getMaxTokens());
    }

    @Test
    @DisplayName("READ_WRITE mode reads from disk")
    void readWrite_loadReadsFromDisk() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      Files.writeString(settingsFile, "{\"maxTokens\": 999}");

      var store = new UiSettingsStore(READ_WRITE, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(999, loaded.getMaxTokens());
    }

    @Test
    @DisplayName("IN_MEMORY mode ignores missing file gracefully")
    void inMemory_missingFileReturnsDefaults() {
      Path settingsFile = tempDir.resolve("nonexistent.json");

      var store = new UiSettingsStore(IN_MEMORY, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(new UiSettings().getMaxTokens(), loaded.getMaxTokens());
    }

    @Test
    @DisplayName("READ_WRITE prepared replacement emits the current-version envelope and reloads it")
    void readWrite_preparedReplacementWritesVersionedEnvelope() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      UiSettings settings = new UiSettings();
      settings.setMaxTokens(777);

      var store = new UiSettingsStore(READ_WRITE, settingsFile);
      store.replacePrepared(store.prepare(settings, new SettingsWitness(0, null)));

      String persisted = Files.readString(settingsFile);
      // Bumped to 2 by tempdoc 883 (contextLength 4096 -> 0 = auto migration).
      assertTrue(persisted.contains("\"schemaVersion\" : 4"));
      assertTrue(persisted.contains("\"settings\""));
      assertEquals(777, new UiSettingsStore(READ_WRITE, settingsFile).load().getMaxTokens());
    }

    @Test
    @DisplayName("future envelope is refused without changing its bytes")
    void readWrite_futureVersionIsRefusedWithoutOverwrite() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      String future = "{\"schemaVersion\":99,\"settings\":{\"maxTokens\":321}}";
      Files.writeString(settingsFile, future);

      UiSettingsStore store = new UiSettingsStore(READ_WRITE, settingsFile);
      assertThrows(UnsupportedStoreVersionException.class, store::load);
      assertEquals(future, Files.readString(settingsFile));
    }

    @Test
    @DisplayName("malformed state is quarantined beside the file and defaults are loaded")
    void readWrite_malformedStateIsQuarantinedAndDefaulted() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      String malformed = "{not-json";
      Files.writeString(settingsFile, malformed);

      UiSettingsStore store = new UiSettingsStore(READ_WRITE, settingsFile);
      UiSettings loaded = store.load();

      UiSettings defaults = new UiSettings();
      assertEquals(defaults.getMaxTokens(), loaded.getMaxTokens());
      assertEquals(defaults.getTheme(), loaded.getTheme());
      // Preserve-then-default: the unreadable bytes moved, so nothing stands at the live path until
      // the next save. Asserting non-existence (not "exists but empty") is the point: a zero-byte
      // settings.json would be a second corrupt state, not a recovery.
      assertFalse(Files.exists(settingsFile), "the unreadable file must be moved, not left behind");

      List<Path> backups;
      try (Stream<Path> siblings = Files.list(tempDir)) {
        backups =
            siblings
                .filter(p -> p.getFileName().toString().startsWith("settings.json.corrupt-"))
                .toList();
      }
      assertEquals(1, backups.size(), "exactly one quarantined sibling: " + backups);
      assertEquals(malformed, Files.readString(backups.get(0)));

      Optional<UiSettingsStore.RecoveredFromCorrupt> recovery = store.lastRecovery();
      assertTrue(recovery.isPresent(), "load() must report the recovery it performed");
      assertEquals(backups.get(0), recovery.get().backupPath());
      assertTrue(recovery.get().detail().contains("cannot parse"),
          "detail must be the parse-failure reason, not some other default cause: "
              + recovery.get().detail());
    }

    @Test
    @DisplayName("prepared replacement after recovery clears lastRecovery when notification runs")
    void readWrite_preparedRecoveryClearsRecoveryAndFiresCallback() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      Files.writeString(settingsFile, "{not-json");

      UiSettingsStore store = new UiSettingsStore(READ_WRITE, settingsFile);
      AtomicBoolean cleared = new AtomicBoolean(false);
      store.setOnRecoveryCleared(() -> cleared.set(true));

      UiSettings recovered = store.load();
      assertTrue(store.lastRecovery().isPresent());
      assertFalse(cleared.get(), "nothing is cleared until the user re-authors settings");

      store.replacePrepared(store.prepare(recovered, new SettingsWitness(0, null)));
      assertFalse(cleared.get(), "replacement must not run notification under the apply lock");
      store.notifyRecoveryCleared();

      assertTrue(store.lastRecovery().isEmpty(), "a successful save supersedes the recovery");
      assertTrue(cleared.get(), "the condition-clearing callback must fire exactly on that save");
    }
  }

  /** Minimal sysprop helper that restores previous values on close. */
  private static final class SysProps implements AutoCloseable {
    private final java.util.Map<String, String> prev = new java.util.HashMap<>();

    SysProps set(String key, String value) {
      if (!prev.containsKey(key)) {
        prev.put(key, System.getProperty(key));
      }
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      return this;
    }

    SysProps clearAll() {
      return set(MODE_PROP, null).set(READONLY_PROP, null).set(PROD_PROP, null);
    }

    @Override
    public void close() {
      for (var e : prev.entrySet()) {
        if (e.getValue() == null) {
          System.clearProperty(e.getKey());
        } else {
          System.setProperty(e.getKey(), e.getValue());
        }
      }
    }
  }
}
