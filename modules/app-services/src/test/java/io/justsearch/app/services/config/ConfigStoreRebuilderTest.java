package io.justsearch.app.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConfigStoreRebuilder} — verifies that rebuild picks up runtime sysprop changes.
 */
@DisplayName("ConfigStoreRebuilder")
final class ConfigStoreRebuilderTest {

  private static final String TEST_SYSPROP = "justsearch.api.port";

  @AfterEach
  void cleanup() {
    System.clearProperty(TEST_SYSPROP);
  }

  @Test
  @DisplayName("rebuild picks up sysprop change written after initial build")
  void rebuildPicksUpSyspropChange() {
    // Build initial config with default port
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    ResolvedConfig initial = builder.build();
    ConfigStore store = new ConfigStore(initial);

    int originalPort = store.get().ports().apiPort();
    assertNotEquals(7777, originalPort, "precondition: default port must differ from the rebuilt value");

    // Simulate runtime sysprop write (as RuntimeActivationService does)
    System.setProperty(TEST_SYSPROP, "7777");

    // Rebuild should pick up the new sysprop value
    ConfigStoreRebuilder.rebuild(store, null);

    assertEquals(7777, store.get().ports().apiPort(), "Rebuild should reflect new sysprop value");
  }

  @Test
  @DisplayName("rebuild with null store is a safe no-op")
  void rebuildWithNullStoreIsNoOp() {
    // Should not throw
    ConfigStoreRebuilder.rebuild(null, null);
  }

  @Test
  @DisplayName("rebuild preserves config completeness")
  void rebuildPreservesCompleteness() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    ResolvedConfig initial = builder.build();
    ConfigStore store = new ConfigStore(initial);

    ConfigStoreRebuilder.rebuild(store, null);

    ResolvedConfig rebuilt = store.get();
    assertNotNull(rebuilt.paths(), "paths sub-record must exist after rebuild");
    assertNotNull(rebuilt.ports(), "ports sub-record must exist after rebuild");
    assertNotNull(rebuilt.ai(), "ai sub-record must exist after rebuild");
    assertNotNull(rebuilt.index(), "index sub-record must exist after rebuild");
    assertNotNull(rebuilt.search(), "search sub-record must exist after rebuild");
  }

  // ==================== context window (tempdoc 883) ====================

  private static final String CONTEXT_SIZE = "justsearch.context.size";

  @Test
  @DisplayName("contextLength 0 (auto) contributes nothing, leaving the derived window to win")
  void autoContextLengthContributesNothing() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    // The derived window as the Head contributes it at ordinal 150.
    builder.contributeAutoDetected(java.util.Map.of(CONTEXT_SIZE, "32768"));
    UiSettings settings = new UiSettings();
    settings.setContextLength(0);

    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigResolution resolution = builder.build().resolution(CONTEXT_SIZE);

    assertEquals("32768", resolution.value(), "auto must not shadow the derived rung");
    assertEquals("auto_detected", resolution.sourceName());
    assertEquals(
        ResolvedConfigBuilder.ORDINAL_AUTO_DETECT,
        resolution.sourceOrdinal(),
        "a settings contribution at 300 here would make every install's default outrank the"
            + " hardware probe, which is the defect tempdoc 883 fixes");
  }

  @Test
  @DisplayName("a positive contextLength contributes at settings_json (300) and outranks the probe")
  void explicitContextLengthContributesAtSettingsOrdinal() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeAutoDetected(java.util.Map.of(CONTEXT_SIZE, "32768"));
    UiSettings settings = new UiSettings();
    settings.setContextLength(16384);

    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigResolution resolution = builder.build().resolution(CONTEXT_SIZE);

    assertEquals("16384", resolution.value());
    assertEquals("settings.json", resolution.sourceName());
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, resolution.sourceOrdinal());
  }

  @Test
  @DisplayName("desired API port contributes zero at the settings ordinal")
  void explicitEphemeralApiPortContributesAtSettingsOrdinal() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    UiSettings settings = new UiSettings();
    settings.setApiPort(0);

    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigResolution resolution = builder.build().resolution("justsearch.api.port");

    assertEquals("0", resolution.value());
    assertEquals("settings.json", resolution.sourceName());
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, resolution.sourceOrdinal());
  }

  @Test
  @DisplayName("rebuild re-contributes the remembered hardware probe at ordinal 150")
  void rebuildPreservesTheDerivedWindow() {
    try {
      ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of(CONTEXT_SIZE, "32768"));
      ConfigStore store = new ConfigStore(new ResolvedConfigBuilder().build());

      ConfigStoreRebuilder.rebuild(store, new UiSettings());

      ConfigResolution resolution = store.get().resolution(CONTEXT_SIZE);
      assertNotNull(
          resolution,
          "rebuild re-derives from scratch, and the hardware probe runs once at startup: without"
              + " re-contributing it, the first settings PUT would erase the derived window's"
              + " provenance and leave effective-config explaining nothing");
      assertEquals("32768", resolution.value());
      assertEquals("auto_detected", resolution.sourceName());
      assertEquals(ResolvedConfigBuilder.ORDINAL_AUTO_DETECT, resolution.sourceOrdinal());
    } finally {
      ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of());
    }
  }

  @Test
  @DisplayName("a user override still outranks the remembered probe after a rebuild")
  void rebuildKeepsTheOrdinalChainIntact() {
    try {
      ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of(CONTEXT_SIZE, "32768"));
      ConfigStore store = new ConfigStore(new ResolvedConfigBuilder().build());
      UiSettings settings = new UiSettings();
      settings.setContextLength(8192);

      ConfigStoreRebuilder.rebuild(store, settings);

      ConfigResolution resolution = store.get().resolution(CONTEXT_SIZE);
      assertEquals("8192", resolution.value());
      assertEquals("settings.json", resolution.sourceName());
    } finally {
      ConfigStoreRebuilder.rememberAutoDetected(java.util.Map.of());
    }
  }

  @Test
  @DisplayName("with nothing contributed at all the key resolves to nothing, not to a second default")
  void noContributorMeansNoValue() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    UiSettings settings = new UiSettings();

    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ResolvedConfig config = builder.build();

    ConfigResolution resolution = config.resolution(CONTEXT_SIZE);
    assertNull(resolution, "no source contributed the key");
    assertEquals(
        0,
        config.ai().contextSize(),
        "0 = auto is the resolver's default; a second shipped number here is what let 8192 and"
            + " 4096 disagree for six months");
  }

  @Test
  @DisplayName("preparation failure propagates without publishing a partial snapshot")
  void preparationFailureDoesNotPublish() throws ReflectiveOperationException {
    ResolvedConfig initial = new ResolvedConfigBuilder().build();
    ConfigStore store = new ConfigStore(initial);
    UiSettings settings = new UiSettings();

    // UiSettings normally cleans incoming values. Install a cyclic list directly so the real
    // exclude-pattern serializer fails during preparation, exercising the no-swallowed-failure
    // boundary without introducing a production-only test serializer.
    List<Object> cyclic = new ArrayList<>();
    cyclic.add(cyclic);
    Field patterns = UiSettings.class.getDeclaredField("excludePatterns");
    patterns.setAccessible(true);
    patterns.set(settings, cyclic);

    assertThrows(RuntimeException.class, () -> ConfigStoreRebuilder.rebuild(store, settings));
    assertSame(initial, store.get(), "failed preparation must not publish a new snapshot");
  }

  @Test
  @DisplayName("snapshot swap and listener notification are independently ordered")
  void swapSeparatesPublicationFromNotification() {
    ResolvedConfig initial = new ResolvedConfigBuilder().build();
    ConfigStore store = new ConfigStore(initial);
    List<Object> observed = new ArrayList<>();
    store.addListener(observed::add);
    ResolvedConfig next = new ResolvedConfigBuilder().build();

    var event = store.swap(next);

    assertSame(next, store.get());
    assertTrue(observed.isEmpty(), "swap must not invoke listeners");

    store.notifyListeners(event);

    assertFalse(observed.isEmpty(), "explicit notification must invoke listeners");
    assertSame(event, observed.getFirst());
  }
}
