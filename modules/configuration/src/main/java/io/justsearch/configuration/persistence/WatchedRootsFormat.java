/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import tools.jackson.databind.JsonNode;

/** Shared format authority for the authored watched-roots registry and its migration reader. */
public final class WatchedRootsFormat {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private WatchedRootsFormat() {}

  /** Reads declarations without probing their volumes or requiring the folders to exist. */
  public static java.util.Set<java.nio.file.Path> declaredPaths(java.nio.file.Path dataDir)
      throws java.io.IOException {
    return declaredCollections(dataDir).keySet();
  }

  /** Authored labels, including null for a declared default root; no watch or volume probe. */
  public static java.util.Map<java.nio.file.Path, String> declaredCollections(java.nio.file.Path dataDir)
      throws java.io.IOException {
    if (dataDir == null) return java.util.Map.of();
    java.nio.file.Path file = dataDir.resolve("watched_roots.json");
    String content;
    try { content = java.nio.file.Files.readString(file); }
    catch (java.nio.file.NoSuchFileException absent) {
      if (!java.nio.file.Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw absent;
      return java.util.Map.of();
    }
    var document = tools.jackson.databind.json.JsonMapper.builder().build().readTree(content);
    boolean object = document != null && document.isObject();
    if (object) requireReadableObject(document);
    var entries = object ? document.get("roots") : document;
    if (entries == null || !entries.isArray()) throw new java.io.IOException("Invalid watched-roots array");
    var paths = new java.util.LinkedHashMap<java.nio.file.Path, String>();
    for (var entry : entries) {
      var path = object ? entry.get("path") : entry;
      if (path == null || !path.isString() || path.stringValue().isBlank()) {
        throw new java.io.IOException("Invalid watched-roots declaration");
      }
      paths.put(java.nio.file.Path.of(path.stringValue()).toAbsolutePath().normalize(),
          object ? entry.path("collection").asText(null) : null);
    }
    return java.util.Collections.unmodifiableMap(paths);
  }

  public static void requireReadableObject(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new CorruptDurableStoreException(
          "watched-roots", "expected a legacy array or versioned object");
    }
    JsonNode versionNode = root.get("schemaVersion");
    if (versionNode != null && !versionNode.isInt()) {
      throw new CorruptDurableStoreException("watched-roots", "schemaVersion must be an integer");
    }
    Integer observedVersion = versionNode == null ? null : versionNode.asInt();
    StoreFormatVersions.requireReadable(
        "watched-roots", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
  }
}
