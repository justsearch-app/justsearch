/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Persistence adapter for watched roots (watched_roots.json).
 *
 * <p>This is extracted from {@link KnowledgeClient} to keep the gRPC client focused on
 * transport/retry behavior.
 */
final class WatchedRootsStore {
  private static final int CURRENT_SCHEMA_VERSION = 1;
  private static final ObjectMapper JSON = new ObjectMapper();
  /**
   * Sentinel value used when a root is tracked but has never completed an indexing submission.
   *
   * <p>We intentionally avoid null timestamps because {@link java.util.concurrent.ConcurrentHashMap}
   * rejects null values and some callers store watched roots in concurrent maps.
   *
   * <p>We do NOT persist this sentinel; it is represented as a missing {@code lastIndexed} field.
   */
  static final Instant NEVER_INDEXED = Instant.EPOCH;

  private final Path rootsFile;
  private final Logger log;

  WatchedRootsStore(Path rootsFile, Logger log) {
    this.rootsFile = rootsFile;
    this.log = log;
  }

  void migrateLegacyRootsFileIfNeeded() {
    if (rootsFile == null) {
      return;
    }
    try {
      if (Files.exists(rootsFile)) {
        return;
      }
      Path userHome = PlatformPaths.resolveUserHome();
      Path legacy = userHome.resolve(".justsearch").resolve("watched_roots.json");
      if (!Files.exists(legacy)) {
        return;
      }
      Path normalizedLegacy = legacy.toAbsolutePath().normalize();
      Path normalizedTarget = rootsFile.toAbsolutePath().normalize();
      if (normalizedLegacy.equals(normalizedTarget)) {
        return;
      }

      AtomicFileWrites.replace(rootsFile, Files.readAllBytes(legacy));
      if (log != null) {
        log.info("Migrated watched roots from legacy path {} to {}", legacy, rootsFile);
      }

      // Best-effort: rename legacy file so we don't repeatedly migrate.
      Path migrated = legacy.resolveSibling("watched_roots.json.migrated");
      if (!Files.exists(migrated)) {
        try {
          Files.move(legacy, migrated);
        } catch (Exception ignored) {
          // copy was successful; leaving legacy file behind is acceptable
        }
      }
    } catch (Exception e) {
      if (log != null) {
        log.warn(
            "Failed to migrate legacy watched_roots.json to {} (continuing without migration): {}",
            rootsFile,
            e.getMessage());
      }
    }
  }

  /**
   * Loads persisted roots from JSON file into memory.
   *
   * <p>Supports both old format (list of strings) and new format (object with roots array).
   * Walk errors are returned in a separate map (backward compatible — old files have no walkError field).
   */
  /**
   * @param walkCompleted roots whose filesystem walk has terminated at least once (tempdoc 599
   *     Fix 1) — distinguishes "walked, nothing to index" (empty) from "walk in progress / never
   *     walked" (both have no {@code lastIndexed}/{@code walkError}). Old files lack this field, so
   *     it is absent there (treated as not-completed).
   * @param collections the collection label each root was added under. Old files lack this field,
   *     so a pre-existing root has no entry and every arm falls back to the default label exactly
   *     as it did before (tempdoc 885 §UD open item 4 / 821 §L.3).
   */
  record LoadResult(
      Map<Path, Instant> roots,
      Map<Path, String> walkErrors,
      java.util.Set<Path> walkCompleted,
      Map<Path, String> collections) {}

  LoadResult loadPersistedRootsWithErrors() {
    Map<Path, Instant> roots = new LinkedHashMap<>();
    Map<Path, String> errors = new LinkedHashMap<>();
    java.util.Set<Path> completed = new java.util.LinkedHashSet<>();
    Map<Path, String> collections = new LinkedHashMap<>();
    if (rootsFile == null || !Files.exists(rootsFile)) {
      return new LoadResult(Map.of(), Map.of(), java.util.Set.of(), Map.of());
    }
    try {
      String content = Files.readString(rootsFile);
      if (content.trim().startsWith("{")) {
        var node = JSON.readTree(content);
        requireReadableObject(node);
        var rootsArray = node.get("roots");
        if (rootsArray != null && rootsArray.isArray()) {
          for (var entry : rootsArray) {
            String pathStr = entry.has("path") ? entry.get("path").asText() : null;
            if (pathStr == null) continue;
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) continue;
            String lastIndexedStr = entry.has("lastIndexed") ? entry.get("lastIndexed").asText() : null;
            Instant lastIndexed = (lastIndexedStr != null && !lastIndexedStr.isBlank())
                ? Instant.parse(lastIndexedStr) : NEVER_INDEXED;
            roots.put(path, lastIndexed);
            if (entry.has("collection")) {
              String collection = entry.get("collection").asText(null);
              if (collection != null && !collection.isBlank()) {
                collections.put(path, collection);
              }
            }
            if (entry.has("walkError")) {
              String err = entry.get("walkError").asText(null);
              if (err != null && !err.isBlank()) {
                errors.put(path, err);
              }
            }
            // A real lastIndexed or a walkError both imply the walk terminated; the explicit
            // walkCompleted flag additionally captures the "walked, zero admitted files" case.
            if (!NEVER_INDEXED.equals(lastIndexed)
                || errors.containsKey(path)
                || (entry.has("walkCompleted") && entry.get("walkCompleted").asBoolean(false))) {
              completed.add(path);
            }
          }
        }
      } else {
        // Old format: delegate to existing loader
        for (var e : loadPersistedRoots().entrySet()) {
          roots.put(e.getKey(), e.getValue());
        }
      }
    } catch (CorruptDurableStoreException | UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException("watched-roots", "cannot parse " + rootsFile, e);
    }
    return new LoadResult(
        java.util.Collections.unmodifiableMap(roots),
        java.util.Collections.unmodifiableMap(errors),
        java.util.Collections.unmodifiableSet(completed),
        java.util.Collections.unmodifiableMap(collections));
  }

  /**
   * Loads persisted roots from JSON file into memory.
   *
   * <p>Supports both old format (list of strings) and new format (object with roots array).
   */
  Map<Path, Instant> loadPersistedRoots() {
    if (rootsFile == null) {
      return Map.of();
    }

    Map<Path, Instant> out = new LinkedHashMap<>();
    try {
      if (!Files.exists(rootsFile)) {
        return Map.of();
      }

      String content = Files.readString(rootsFile);
      int loaded = 0;

      // Try new format first: {"roots": [{"path": "...", "lastIndexed": "..."}]}
      if (content.trim().startsWith("{")) {
        var node = JSON.readTree(content);
        requireReadableObject(node);
        var rootsArray = node.get("roots");
        if (rootsArray != null && rootsArray.isArray()) {
          for (var entry : rootsArray) {
            String pathStr = entry.has("path") ? entry.get("path").asText() : null;
            String lastIndexedStr = entry.has("lastIndexed") ? entry.get("lastIndexed").asText() : null;
            if (pathStr != null) {
              Path path = Path.of(pathStr);
              if (Files.exists(path)) {
                Instant lastIndexed =
                    (lastIndexedStr != null && !lastIndexedStr.isBlank())
                        ? Instant.parse(lastIndexedStr)
                        : NEVER_INDEXED;
                out.put(path, lastIndexed);
                loaded++;
              } else {
                if (log != null) log.debug("Skipping non-existent persisted root: {}", path);
              }
            }
          }
        }
      } else {
        // Old format: ["path1", "path2"]
        List<String> paths = JSON.readValue(content, new TypeReference<List<String>>() {});
        for (String pathStr : paths) {
          Path path = Path.of(pathStr);
          if (Files.exists(path)) {
            out.put(path, NEVER_INDEXED); // No timestamp in old format
            loaded++;
          } else {
            if (log != null) log.debug("Skipping non-existent persisted root: {}", path);
          }
        }
      }

      if (loaded > 0 && log != null) {
        log.info("Loaded {} persisted roots from {} (will re-index after connection)", loaded, rootsFile);
      }
      // Values may be null for old-format roots (no timestamps), so avoid Map.copyOf().
      return java.util.Collections.unmodifiableMap(out);
    } catch (CorruptDurableStoreException | UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException("watched-roots", "cannot parse " + rootsFile, e);
    }
  }

  /**
   * Persists current roots to JSON file in the new format.
   *
   * <p>Synchronized to prevent concurrent file writes from the walk-bg thread and caller threads.
   * Uses temp-file-then-rename to avoid corruption if the process crashes mid-write.
   */
  synchronized void persistRoots(
      Map<Path, Instant> watchedRoots,
      Map<Path, String> walkErrors,
      java.util.Set<Path> walkCompleted,
      Map<Path, String> collections) {
    if (rootsFile == null) {
      return;
    }
    try {
      // Build v1 format: {"schemaVersion": 1, "roots": [...]}
      List<Map<String, Object>> rootEntries = new ArrayList<>();
      for (var entry : watchedRoots.entrySet()) {
        Map<String, Object> rootEntry = new LinkedHashMap<>();
        Path p = entry.getKey();
        rootEntry.put("path", p.toString());
        Instant v = entry.getValue();
        if (v != null && !NEVER_INDEXED.equals(v)) {
          rootEntry.put("lastIndexed", v.toString());
        }
        // Tempdoc 885 §UD open item 4 / 821 §L.3 — the root's collection label. Persisted because
        // it is the ONLY record of it: the watcher registration and the scan RPC both consume it
        // and neither can be read back, so before this field a restart (and every GET
        // /api/indexing/roots) reported "default" for a root the user labelled.
        if (collections != null) {
          String collection = collections.get(p);
          if (collection != null && !collection.isBlank()) {
            rootEntry.put("collection", collection);
          }
        }
        if (walkErrors != null) {
          String err = walkErrors.get(p);
          if (err != null && !err.isBlank()) {
            rootEntry.put("walkError", err);
          }
        }
        // Only persist the flag for the case it uniquely captures: walked, zero admitted files
        // (no lastIndexed, no walkError). The other terminal outcomes are already implied.
        if (walkCompleted != null && walkCompleted.contains(p)) {
          rootEntry.put("walkCompleted", true);
        }
        rootEntries.add(rootEntry);
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("schemaVersion", CURRENT_SCHEMA_VERSION);
      data.put("roots", rootEntries);
      AtomicFileWrites.replace(
          rootsFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
      if (log != null) {
        log.debug("Persisted {} roots to {}", rootEntries.size(), rootsFile);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist watched roots to " + rootsFile, e);
    }
  }

  private static void requireReadableObject(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new CorruptDurableStoreException(
          "watched-roots", "expected a legacy array or versioned object");
    }
    JsonNode versionNode = root.get("schemaVersion");
    if (versionNode != null && !versionNode.isInt()) {
      throw new CorruptDurableStoreException(
          "watched-roots", "schemaVersion must be an integer");
    }
    Integer observedVersion = versionNode == null ? null : versionNode.asInt();
    StoreFormatVersions.requireReadable(
        "watched-roots", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
  }
}
