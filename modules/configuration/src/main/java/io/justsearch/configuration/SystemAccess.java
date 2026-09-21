/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

/**
 * Centralized access to JVM system properties and environment variables.
 *
 * <p>Workstream B guardrail: prefer routing all {@link System#getProperty(String)} / {@link System#getenv(String)}
 * calls through {@code modules/configuration}, so other modules do not couple to global-state primitives directly.
 */
public final class SystemAccess {
  private SystemAccess() {}

  public static String sysProp(String key) {
    if (key == null || key.isBlank()) return null;
    String v = System.getProperty(key);
    return (v == null || v.isBlank()) ? null : v;
  }

  public static String sysProp(String key, String defaultValue) {
    if (key == null || key.isBlank()) return defaultValue;
    String v = System.getProperty(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  public static void setSysProp(String key, String value) {
    if (key == null || key.isBlank()) return;
    if (value == null) {
      clearSysProp(key);
      return;
    }
    System.setProperty(key, value);
  }

  public static void clearSysProp(String key) {
    if (key == null || key.isBlank()) return;
    System.clearProperty(key);
  }

  public static String envVar(String key) {
    String v = rawEnvVar(key);
    return (v == null || v.isBlank()) ? null : v;
  }

  /** Presence-sensitive process selectors must distinguish an invalid blank value from absence. */
  public static String rawEnvVar(String key) {
    if (key == null || key.isBlank()) return null;
    return System.getenv(key);
  }
}
