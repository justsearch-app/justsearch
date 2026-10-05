package io.justsearch.configuration.resolved;

import java.util.Map;

/**
 * Shared helper for constructing {@link ResolvedConfig} and {@link ConfigStore} in tests.
 *
 * <p>Lives in the {@code testFixtures} source set so any module depending on
 * {@code testFixtures(project(":modules:configuration"))} can use it without starting HeadlessApp.
 */
public final class TestResolvedConfigHelper {

  private static final String ORT_CACHE_KEY = "justsearch.ort.optimized_cache_dir";
  private static final String ORT_CACHE_ENV = "JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR";

  private TestResolvedConfigHelper() {}

  /** Builds defaults plus the Gradle test task's isolated ORT cache root, when supplied. */
  public static ResolvedConfig withDefaults() {
    return builderWithIsolatedOrtCache().build();
  }

  /**
   * Builds defaults and the isolated ORT cache root, overridden by the given entries.
   * A nonblank explicit cache root wins; a blank root retains test isolation. Other
   * env/sysprop/YAML sources are not contributed.
   *
   * @param entries key-value pairs contributed at ordinal 100 (default)
   */
  public static ResolvedConfig fromEntries(Map<String, String> entries) {
    ResolvedConfigBuilder b = builderWithIsolatedOrtCache();
    entries.forEach((key, value) -> {
      if (!ORT_CACHE_KEY.equals(key) || (value != null && !value.isBlank())) {
        b.putDefault(key, value);
      }
    });
    return b.build();
  }

  private static ResolvedConfigBuilder builderWithIsolatedOrtCache() {
    ResolvedConfigBuilder b = ResolvedConfig.builder();
    // Global test snapshots bypass production environment contributions. Carry just the
    // Gradle-owned cache root so native assembly cannot initialize/prune the developer store.
    String directory = System.getenv(ORT_CACHE_ENV);
    if (directory != null && !directory.isBlank()) b.putDefault(ORT_CACHE_KEY, directory);
    return b;
  }

  /**
   * Creates a ConfigStore with defaults and the isolated ORT cache root, then publishes it globally.
   *
   * <p>Callers should reset the global after their test (e.g., in {@code @AfterEach}).
   */
  public static ConfigStore storeWithDefaults() {
    ConfigStore store = new ConfigStore(withDefaults());
    ConfigStore.setGlobal(store);
    return store;
  }

  /**
   * Creates a ConfigStore that reads system properties and env vars (via EnvRegistry),
   * then publishes it globally. Use this in tests that set system properties before
   * building the config.
   */
  public static ConfigStore storeFromEnvironment() {
    ResolvedConfigBuilder b = builderWithIsolatedOrtCache();
    b.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(b.build());
    ConfigStore.setGlobal(store);
    return store;
  }

  /**
   * Restores the previous ConfigStore global state. If {@code previous} is null, clears the global
   * entirely (returning to the uninitialized state).
   *
   * <p>Use in {@code finally} blocks or {@code @AfterEach} to undo {@link #storeWithDefaults()} or
   * {@link #storeFromEnvironment()}.
   */
  public static void restoreGlobal(ConfigStore previous) {
    if (previous != null) {
      ConfigStore.setGlobal(previous);
    } else {
      ConfigStore.clearGlobal();
    }
  }
}
