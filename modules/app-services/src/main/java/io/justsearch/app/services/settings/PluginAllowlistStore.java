/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists the operator plugin-trust allowlist — the set of SHA-256 hexes of plugin artifacts an
 * operator has explicitly trusted — to {@code $JUSTSEARCH_HOME/ui/plugin-allowlist.json}, a sibling
 * of {@code settings.json}.
 *
 * <p>Tempdoc 560 §28 (the delivery slice): an operator-approval allowlist is the production-real
 * trust path for a URL-loaded third-party plugin (short of full Sigstore, which stays dep-gated).
 * Without persistence an approval would not survive a restart, so a trusted plugin would silently
 * fall back to UNTRUSTED. This store gives the allowlist durability with the same shape as
 * {@link UiSettingsStore} (same {@link PersistenceMode}, same base directory), so the two stay
 * consistent across modes (IN_MEMORY for prod/CI isolation; READ_WRITE for real operator use).
 */
public final class PluginAllowlistStore {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final PersistenceMode mode;
  private final Path file;

  public PluginAllowlistStore(PersistenceMode mode, Path file) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.file = Objects.requireNonNull(file, "file");
  }

  /** Loads the persisted allowlist. Returns an empty set in IN_MEMORY mode or when no file exists. */
  public Set<String> load() {
    if (mode == PersistenceMode.IN_MEMORY || !Files.exists(file)) {
      return new LinkedHashSet<>();
    }
    try {
      JsonNode root = MAPPER.readTree(file.toFile());
      if (root == null) {
        throw new CorruptDurableStoreException("plugin-allowlist", "JSON document is empty");
      }
      if (root.isArray()) {
        String[] entries = MAPPER.treeToValue(root, String[].class);
        return entries == null ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(entries));
      }
      if (!root.isObject()) {
        throw new CorruptDurableStoreException(
            "plugin-allowlist", "expected a legacy array or versioned object");
      }
      JsonNode versionNode = root.get("schemaVersion");
      Integer observedVersion =
          versionNode == null || versionNode.isNull() ? null : versionNode.asInt();
      StoreFormatVersions.requireReadable(
          "plugin-allowlist", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
      PersistedAllowlist persisted = MAPPER.treeToValue(root, PersistedAllowlist.class);
      return persisted.entries() == null
          ? new LinkedHashSet<>()
          : new LinkedHashSet<>(persisted.entries());
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "plugin-allowlist", "cannot parse " + file, e);
    }
  }

  /** Persists the allowlist atomically. No-op in IN_MEMORY mode. */
  public void save(Set<String> entries) {
    if (mode == PersistenceMode.IN_MEMORY) {
      return;
    }
    try {
      byte[] bytes =
          MAPPER
              .writerWithDefaultPrettyPrinter()
              .writeValueAsBytes(
                  new PersistedAllowlist(
                      CURRENT_SCHEMA_VERSION, entries == null ? Set.of() : entries));
      AtomicFileWrites.replace(file, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist plugin allowlist to " + file, e);
    }
  }

  private record PersistedAllowlist(int schemaVersion, Set<String> entries) {}

  public Path path() {
    return file;
  }

  public PersistenceMode mode() {
    return mode;
  }
}
