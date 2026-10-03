/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.util.Sha256SidecarCache;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the machine-wide, content-addressed, bounded store of optimized ORT graphs. */
public final class OrtOptimizedModelStore {
  private static final Logger log = LoggerFactory.getLogger(OrtOptimizedModelStore.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<Path> INITIALIZED = new HashSet<>();
  private static final List<String> LEGACY_SUFFIXES =
      List.of(".optimized", ".opt-meta", ".cuda.optimized", ".cuda.opt-meta");

  private final Path root;
  private final String ortVersion;
  private final long maxBytes;
  private final LongSupplier clock;

  private OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes) {
    this(root, ortVersion, maxBytes, System::currentTimeMillis);
  }

  // Deterministic clock and byte cap for acceptance tests; production uses the resolved MiB cap.
  OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes, LongSupplier clock) {
    this.root = root.toAbsolutePath().normalize();
    this.ortVersion = pathSegment(ortVersion);
    if (maxBytes < 0) throw new IllegalArgumentException("Negative optimized cache cap");
    this.maxBytes = maxBytes;
    this.clock = clock;
  }

  /** Uses the same resolved-config/global-store route as ORT native-library discovery. */
  public static OrtOptimizedModelStore configured() {
    ConfigStore store = ConfigStore.globalOrNull();
    ResolvedConfig cfg = store != null
        ? store.get() : ResolvedConfig.builder().contributeBaseSources().build();
    return fromConfig(cfg.ai().optimizedCache(), OnnxSessionCache.ortVersion());
  }

  static OrtOptimizedModelStore fromConfig(ResolvedConfig.Ai.OptimizedCache cfg, String version) {
    Path directory = cfg.directory() != null ? cfg.directory()
        : PlatformPaths.getPlatformDefault().resolve("cache").resolve("ort-optimized");
    return new OrtOptimizedModelStore(directory, version, cfg.maxMb() * 1024 * 1024);
  }

  /** Option values are carried to the sole setter site, SessionOptionsApplier. */
  record GraphPlan(Path input, OptLevel optimizationLevel, Path optimizedOutput, boolean cached) {}

  @FunctionalInterface
  interface Optimizer<T> {
    T create(GraphPlan plan) throws IOException, OrtException;
  }

  /**
   * Invokes the session creator once, with either a committed graph, a private output path, or
   * an in-memory optimization plan. Cache I/O failure never loses an already-created session.
   */
  <T> T loadOrCreate(Path model, String ep, OptLevel level, Optimizer<T> optimizer)
      throws IOException, OrtException {
    try {
      cleanupLegacy(model);
    } catch (IOException e) {
      log.debug("Legacy ORT cache cleanup refused for {}: {}", model, e.getMessage());
    }
    Path entry;
    Path staging = null;
    Path cached = null;
    try {
      initialize();
      entry = null;
      if (maxBytes > 0 && !OnnxExternalData.hasExternalData(model)) {
        entry = entryPath(model, ep, level);
        if (committed(entry)) {
          touch(entry);
          log.info("Loading pre-optimized {} ONNX model from store: {}", ep, entry);
          cached = entry.resolve("model.onnx");
        } else {
          checkNoLinks(entry.getParent());
          Files.createDirectories(entry.getParent());
          checkNoLinks(entry.getParent());
          staging = Files.createTempDirectory(entry.getParent(), entry.getFileName() + ".tmp-");
        }
      }
    } catch (IOException e) {
      log.debug("ORT optimized store unavailable for {}: {}", model, e.getMessage());
      return optimizer.create(new GraphPlan(model, level, null, false));
    }

    if (cached != null) {
      // Another process may evict between lookup and open. Retry only that disappearance.
      try {
        return optimizer.create(new GraphPlan(cached, level, null, true));
      } catch (OrtException e) {
        if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) throw e;
        return optimizer.create(new GraphPlan(model, level, null, false));
      }
    }
    if (staging == null) return optimizer.create(new GraphPlan(model, level, null, false));

    try {
      Path output = staging.resolve("model.tmp.onnx");
      log.info("Optimizing {} ONNX model into store: {} [optLevel={}]", ep, model, level);
      T result = optimizer.create(new GraphPlan(model, level, output, false));
      try {
        checkNoLinks(output);
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0) {
          throw new IOException("Optimizer did not produce a complete graph");
        }
        Files.move(output, staging.resolve("model.onnx"), StandardCopyOption.ATOMIC_MOVE);
        long now = clock.getAsLong();
        // The commit marker is written last, before publishing the complete directory.
        writeMetadata(staging, Files.size(model), now, now);
        publish(staging, entry);
        evict();
      } catch (IOException e) {
        log.debug("Failed to commit/evict optimized ONNX graph (non-fatal): {}", e.getMessage());
      }
      return result;
    } finally {
      try {
        deleteTree(staging);
      } catch (IOException e) {
        log.debug("Failed to clean partial ORT optimized entry: {}", e.getMessage());
      }
    }
  }

  /** A probe must still have the source bytes to identify a content-addressed graph. */
  public boolean contains(Path model, String ep, OptLevel level) {
    if (maxBytes == 0 || !Files.isRegularFile(model)) return false;
    try {
      initialize();
      return !OnnxExternalData.hasExternalData(model)
          && committed(entryPath(model, ep, level));
    } catch (IOException e) {
      log.debug("Cannot probe optimized ONNX graph: {}", e.getMessage());
      return false;
    }
  }

  Path entryPath(Path model, String ep, OptLevel level) throws IOException {
    if (!"cpu".equals(ep) && !"cuda".equals(ep)) {
      throw new IllegalArgumentException("Unknown ORT execution provider: " + ep);
    }
    String hash = Sha256SidecarCache.getOrCompute(model, false)
        .orElseThrow(() -> new IOException("Cannot hash ONNX source: " + model));
    return root.resolve(ortVersion).resolve(ep + "-" + level.name()).resolve(hash);
  }

  private void initialize() throws IOException {
    Path versionRoot = root.resolve(ortVersion);
    synchronized (INITIALIZED) {
      if (INITIALIZED.contains(versionRoot)) {
        checkNoLinks(root);
        return;
      }
      checkNoLinks(root);
      Files.createDirectories(root);
      checkNoLinks(root);
      try (DirectoryStream<Path> versions = Files.newDirectoryStream(root)) {
        for (Path version : versions) {
          if (!version.equals(versionRoot)) {
            try {
              deleteTree(version);
            } catch (IOException e) {
              log.debug("Refused old ORT version cleanup for {}: {}", version, e.getMessage());
            }
          }
        }
      }
      INITIALIZED.add(versionRoot);
    }
  }

  private static String pathSegment(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid ORT version path segment: " + value);
    }
    return value;
  }

  private static boolean committed(Path entry) throws IOException {
    checkNoLinks(entry);
    Path graph = entry.resolve("model.onnx");
    Path marker = entry.resolve("entry.json");
    checkNoLinks(graph);
    checkNoLinks(marker);
    if (!Files.isRegularFile(graph, LinkOption.NOFOLLOW_LINKS) || Files.size(graph) == 0
        || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
    try {
      JsonNode metadata = JSON.readTree(marker.toFile());
      return metadata != null && metadata.path("sourceSize").canConvertToLong()
          && metadata.path("sourceSize").asLong() >= 0
          && metadata.path("created").canConvertToLong()
          && metadata.path("lastUsed").canConvertToLong();
    } catch (IOException e) {
      return false;
    }
  }

  private void touch(Path entry) throws IOException {
    JsonNode metadata = JSON.readTree(entry.resolve("entry.json").toFile());
    writeMetadata(entry, metadata.path("sourceSize").asLong(),
        metadata.path("created").asLong(), clock.getAsLong());
  }

  private static void writeMetadata(Path entry, long size, long created, long used)
      throws IOException {
    checkNoLinks(entry);
    Path marker = entry.resolve("entry.json");
    checkNoLinks(marker);
    Path temp = Files.createTempFile(entry, "entry-", ".tmp");
    try {
      String json = JSON.createObjectNode().put("sourceSize", size)
          .put("created", created).put("lastUsed", used).toString();
      Files.writeString(temp, json);
      checkNoLinks(marker);
      Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      checkNoLinks(temp);
      Files.deleteIfExists(temp);
    }
  }

  private static void publish(Path staging, Path entry) throws IOException {
    checkNoLinks(entry);
    if (committed(entry)) return;
    // A nonempty committed directory cannot be replaced by an atomic directory rename, even
    // on platforms where ATOMIC_MOVE may replace an existing *file*. No filesystem lock.
    if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
      if (committed(entry)) return;
      deleteTree(entry);
    }
    checkNoLinks(staging);
    checkNoLinks(entry);
    try {
      Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException race) {
      if (!committed(entry)) throw race;
    }
  }

  private record Victim(Path path, long bytes, long used) {}

  private void evict() throws IOException {
    List<Victim> entries = new ArrayList<>();
    Path version = root.resolve(ortVersion);
    checkNoLinks(version);
    try (DirectoryStream<Path> providers = Files.newDirectoryStream(version)) {
      for (Path provider : providers) {
        try {
          checkNoLinks(provider);
          if (!Files.isDirectory(provider, LinkOption.NOFOLLOW_LINKS)) continue;
        } catch (IOException e) {
          log.debug("Refused linked ORT provider cache: {}", provider);
          continue;
        }
        try (DirectoryStream<Path> hashes = Files.newDirectoryStream(provider)) {
          for (Path entry : hashes) {
            if (!entry.getFileName().toString().matches("[0-9a-f]{64}")) continue;
            try {
              if (!committed(entry)) continue;
              JsonNode metadata = JSON.readTree(entry.resolve("entry.json").toFile());
              entries.add(new Victim(entry,
                  Files.size(entry.resolve("model.onnx")) + Files.size(entry.resolve("entry.json")),
                  metadata.path("lastUsed").asLong()));
            } catch (IOException e) {
              log.debug("Cannot inspect ORT eviction candidate {}: {}", entry, e.getMessage());
            }
          }
        }
      }
    }
    long total = 0;
    for (Victim entry : entries) total += entry.bytes();
    entries.sort(Comparator.comparingLong(Victim::used).thenComparing(v -> v.path().toString()));
    for (Victim entry : entries) {
      if (total <= maxBytes) break;
      try {
        deleteTree(entry.path());
        total -= entry.bytes();
        log.debug("Evicted least-recently-used optimized ONNX graph: {}", entry.path());
      } catch (IOException e) {
        log.debug("Refused ORT cache eviction for {}: {}", entry.path(), e.getMessage());
      }
    }
  }

  /** Deletes only the four exact legacy sibling names; never a link or a directory. */
  static void cleanupLegacy(Path model) throws IOException {
    IOException refusal = null;
    for (String suffix : LEGACY_SUFFIXES) {
      Path sibling = model.resolveSibling(model.getFileName() + suffix);
      try {
        checkNoLinks(sibling);
        if (Files.isRegularFile(sibling, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(sibling);
      } catch (IOException e) {
        refusal = e;
      }
    }
    if (refusal != null) throw refusal;
  }

  /** Checks all existing ancestors, including Windows directory junctions. */
  static void checkNoLinks(Path path) throws IOException {
    // Keep dot segments until their preceding ancestors have been inspected: normalizing a
    // linked-directory/../file path first would hide the link that actual I/O traverses.
    Path absolute = path.toAbsolutePath();
    Path cursor = absolute.getRoot();
    for (Path component : absolute) {
      cursor = cursor.resolve(component);
      BasicFileAttributes attrs;
      try {
        attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
        return;
      }
      if (attrs.isSymbolicLink() || attrs.isOther()
          || !cursor.toRealPath().equals(cursor.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
        throw new IOException("Refusing linked ORT cache path: " + cursor);
      }
    }
  }

  private static void deleteTree(Path path) throws IOException {
    checkNoLinks(path);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    // Preflight before deleting anything, and check before ENTERING each directory: on Windows
    // a junction can be reported as a directory rather than as a symbolic link.
    List<Path> paths = new ArrayList<>();
    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
          throws IOException {
        checkNoLinks(directory);
        paths.add(directory);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        checkNoLinks(file);
        paths.add(file);
        return FileVisitResult.CONTINUE;
      }
    });
    paths.sort(Comparator.reverseOrder());
    for (Path item : paths) {
      checkNoLinks(item);
      Files.deleteIfExists(item);
    }
  }
}
