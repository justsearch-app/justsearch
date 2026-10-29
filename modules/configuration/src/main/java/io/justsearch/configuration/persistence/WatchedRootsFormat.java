/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import tools.jackson.databind.JsonNode;

/** Shared format authority for the authored watched-roots registry and its migration reader. */
public final class WatchedRootsFormat {
  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final String REMOVAL_INCOMPLETE =
      "Removal incomplete. Live watching is not confirmed; retry Remove.";
  /** Worker acknowledgement: no watcher, journal, index or queue effect was performed. */
  public static final String REMOVAL_REFUSED_DURING_SWITCHING =
      "Removal did not complete because the index is rebuilding. Retry Remove when the rebuild finishes.";

  private WatchedRootsFormat() {}

  /** Reads declarations without probing their volumes or requiring the folders to exist. */
  public static java.util.Set<java.nio.file.Path> declaredPaths(java.nio.file.Path dataDir)
      throws java.io.IOException {
    var document = readDocument(dataDir);
    if (document == null) return java.util.Set.of();
    boolean object = document.isObject();
    var entries = object ? document.get("roots") : document;
    var paths = new java.util.HashSet<java.nio.file.Path>();
    for (var entry : entries) {
      var path = object ? entry.get("path") : entry;
      if (path == null || !path.isString() || path.stringValue().isBlank()) {
        throw new java.io.IOException("Invalid watched-roots declaration");
      }
      paths.add(java.nio.file.Path.of(path.stringValue()).toAbsolutePath().normalize());
    }
    return java.util.Set.copyOf(paths);
  }

  /** Durable removal intent survives a stop before the Worker has journalled its deletion. */
  public static java.util.Set<java.nio.file.Path> pendingRemovals(java.nio.file.Path dataDir)
      throws java.io.IOException {
    var document = readDocument(dataDir);
    if (document == null || !document.isObject()) return java.util.Set.of();
    var paths = new java.util.HashSet<java.nio.file.Path>();
    for (var entry : document.get("roots")) {
      var error = entry.get("walkError");
      if (error != null && REMOVAL_INCOMPLETE.equals(error.asText())) {
        var path = entry.get("path");
        if (path == null || !path.isString() || path.stringValue().isBlank()) {
          throw new java.io.IOException("Invalid pending root removal");
        }
        paths.add(java.nio.file.Path.of(path.stringValue()).toAbsolutePath().normalize());
      }
    }
    return java.util.Set.copyOf(paths);
  }

  private static JsonNode readDocument(java.nio.file.Path dataDir) throws java.io.IOException {
    if (dataDir == null) return null;
    java.nio.file.Path file = dataDir.resolve("watched_roots.json");
    String content;
    try { content = java.nio.file.Files.readString(file); }
    catch (java.nio.file.NoSuchFileException absent) {
      if (!java.nio.file.Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw absent;
      return null;
    }
    var document = tools.jackson.databind.json.JsonMapper.builder().build().readTree(content);
    boolean object = document != null && document.isObject();
    if (object) requireReadableObject(document);
    var entries = object ? document.get("roots") : document;
    if (entries == null || !entries.isArray()) throw new java.io.IOException("Invalid watched-roots array");
    return document;
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
