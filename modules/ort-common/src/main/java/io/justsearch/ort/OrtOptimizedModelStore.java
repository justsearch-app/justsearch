/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.util.Sha256SidecarCache;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the machine-wide, content-addressed, bounded store of optimized ORT graphs. */
public final class OrtOptimizedModelStore {
  private static final Logger log = LoggerFactory.getLogger(OrtOptimizedModelStore.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<Path> INITIALIZED = new HashSet<>();
  // Native process probes are not the authority for another thread's private snapshot.
  // Reserve before mkdir so reconciliation cannot see an unregistered local creator.
  private static final Set<Path> ACTIVE_STAGING = ConcurrentHashMap.newKeySet();
  private static final Set<Path> PATH_LIMIT_LOGGED = ConcurrentHashMap.newKeySet();
  private static final int WINDOWS_PATH_LIMIT = 240;
  // An empty, versioned marker directory is claimed with one atomic mkdir: a crash cannot
  // leave a partially written ownership file that permanently disables reconciliation.
  private static final String ROOT_MARKER = ".ort-optimized-store-v1";
  private static final String LEASE_FILE = "lease.json";
  private static final Pattern STAGE_NAME = Pattern.compile("s-[0-9a-f]{16}");
  private static final Pattern PREPARING_NAME = Pattern.compile("p-([1-9][0-9]*)-([0-9]+)-[0-9a-f]{16}");
  private static final Pattern QUARANTINE_NAME = Pattern.compile("q-[0-9a-f]{16}");
  private static final Pattern LEGACY_STAGE_NAME = Pattern.compile(
      "[0-9a-f]{64}\\.tmp-([1-9][0-9]*)-([0-9]+)-[A-Za-z0-9-]+");
  private static final Pattern LEGACY_QUARANTINE_NAME = Pattern.compile(
      "[0-9a-f]{64}\\.quarantine-[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
  private static final List<String> LEGACY_SUFFIXES =
      List.of(".optimized", ".opt-meta", ".cuda.optimized", ".cuda.opt-meta");

  private final Path root;
  private final String ortVersion;
  private final long maxBytes;
  private final LongSupplier clock;
  private final QuarantineMove quarantineMove;
  private final int nativePathLimit;

  @FunctionalInterface
  interface QuarantineMove {
    void move(Path entry, Path quarantine) throws IOException;
  }

  private OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes) {
    this(root, ortVersion, maxBytes, System::currentTimeMillis);
  }

  // Deterministic clock and byte cap for acceptance tests; production uses the resolved MiB cap.
  OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes, LongSupplier clock) {
    this(root, ortVersion, maxBytes, clock,
        (entry, quarantine) -> Files.move(entry, quarantine, StandardCopyOption.ATOMIC_MOVE));
  }

  // Injectable atomic-move boundary for deterministic cross-creator/termination regressions.
  OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes, LongSupplier clock,
      QuarantineMove quarantineMove) {
    this(root, ortVersion, maxBytes, clock, quarantineMove,
        PlatformPaths.isWindows() ? WINDOWS_PATH_LIMIT : Integer.MAX_VALUE);
  }

  // Exercise the Windows native-path budget without changing the host OS or global properties.
  OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes, LongSupplier clock,
      int nativePathLimit) {
    this(root, ortVersion, maxBytes, clock,
        (entry, quarantine) -> Files.move(entry, quarantine, StandardCopyOption.ATOMIC_MOVE),
        nativePathLimit);
  }

  private OrtOptimizedModelStore(Path root, String ortVersion, long maxBytes, LongSupplier clock,
      QuarantineMove quarantineMove, int nativePathLimit) {
    this.root = root.toAbsolutePath().normalize();
    this.ortVersion = pathSegment(ortVersion);
    if (maxBytes < 0) throw new IllegalArgumentException("Negative optimized cache cap");
    this.maxBytes = maxBytes;
    this.clock = clock;
    this.quarantineMove = quarantineMove;
    if (nativePathLimit < 1) throw new IllegalArgumentException("Invalid ORT native path limit");
    this.nativePathLimit = nativePathLimit;
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

  /** Cache hits do no reconciliation scan or source snapshot copy. */
  <T> T loadOrCreate(Path model, String ep, OptLevel level, Optimizer<T> optimizer)
      throws IOException, OrtException {
    try {
      cleanupLegacy(model);
    } catch (IOException e) {
      log.debug("Legacy ORT cache cleanup refused for {}: {}", model, e.getMessage());
    }
    // ORT's native file opens can reject a path that Java NIO successfully creates on Windows.
    // Check every native input/output before mkdir, pruning, hashing or copying into the store.
    if (!nativePathsFit(ep, level)) return optimizer.create(new GraphPlan(model, level, null, false));
    Path entry;
    boolean cached = false;
    try {
      initialize();
      if (maxBytes == 0 || OnnxExternalData.hasExternalData(model)) {
        entry = null;
      } else {
        entry = entryPath(model, ep, level);
        if (committed(entry)) {
          cached = true;
          touch(entry);
        } else {
          quarantine(entry);
        }
      }
    } catch (IOException e) {
      log.debug("ORT optimized store unavailable for {}: {}", model, e.getMessage());
      entry = null;
    }
    if (entry == null) return optimizer.create(new GraphPlan(model, level, null, false));
    if (!cached) return createFresh(model, ep, level, entry, optimizer);

    log.info("Loading pre-optimized {} ONNX model from store: {}", ep, entry);
    try {
      return optimizer.create(new GraphPlan(entry.resolve("model.onnx"), level, null, true));
    } catch (OrtException cachedFailure) {
      // Detach the entire suspect generation atomically before retrying the source once.
      // Cleanup only touches its quarantine name, never a later publication at the content key.
      log.warn("Cached {} ONNX graph failed to load; retrying source: {}", ep, entry);
      try {
        quarantine(entry);
      } catch (IOException e) {
        // Refusing a linked/unowned entry must not prevent loading a healthy source.
        log.debug("Cannot quarantine failed ORT graph: {}", e.getMessage());
        cachedFailure.addSuppressed(e);
      }
      try {
        return createFresh(model, ep, level, entry, optimizer);
      } catch (IOException | OrtException sourceFailure) {
        sourceFailure.addSuppressed(cachedFailure);
        throw sourceFailure;
      }
    }
  }

  private <T> T createFresh(Path model, String ep, OptLevel level, Path entry,
      Optimizer<T> optimizer) throws IOException, OrtException {
    Path staging = null;
    Path input;
    boolean cacheable;
    long sourceSize = 0;
    try {
      try {
        checkNoLinks(entry.getParent());
        Files.createDirectories(entry.getParent());
        checkNoLinks(entry.getParent());
        staging = createStaging(entry);
        // The source path may be replaced at any point. ORT opens this private snapshot instead.
        input = staging.resolve("source.onnx");
        Files.copy(model, input);
        String snapshotHash = Sha256SidecarCache.getOrCompute(input, false)
            .orElseThrow(() -> new IOException("Cannot hash ONNX source snapshot"));
        cacheable = snapshotHash.equals(entry.getFileName().toString())
            && !OnnxExternalData.hasExternalData(input);
        sourceSize = Files.size(input);
        if (!cacheable) {
          // A replaced model may refer to external files relative to its original directory.
          // Do not publish a graph under the previously selected content key.
          input = model;
        }
      } catch (IOException e) {
        log.debug("Cannot stage ONNX source; optimizing in memory: {}", e.getMessage());
        input = model;
        cacheable = false;
      }
      Path output = cacheable ? staging.resolve("model.tmp.onnx") : null;
      log.info("Optimizing {} ONNX model into store: {} [optLevel={}]", ep, model, level);
      T result = optimizer.create(new GraphPlan(input, level, output, false));
      if (!cacheable) return result;
      try {
        checkNoLinks(output);
        if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) == 0) {
          throw new IOException("Optimizer did not produce a complete graph");
        }
        Files.move(output, staging.resolve("model.onnx"), StandardCopyOption.ATOMIC_MOVE);
        checkNoLinks(staging.resolve("source.onnx"));
        Files.delete(staging.resolve("source.onnx"));
        long now = clock.getAsLong();
        writeMetadata(staging, sourceSize, now, now);
        publish(staging, entry);
      } catch (IOException e) {
        log.debug("Failed to commit optimized ONNX graph (non-fatal): {}", e.getMessage());
      }
      return result;
    } finally {
      if (staging != null) {
        try {
          deleteOwnedEntry(staging, true);
        } catch (IOException e) {
          log.debug("Failed to clean partial ORT optimized entry: {}", e.getMessage());
        } finally {
          ACTIVE_STAGING.remove(staging);
        }
      }
      // Include other live creators' staging in the cap, and reconcile creators that died
      // since initialization. Reconciliation never runs on an ordinary cache hit.
      if (staging != null) {
        try {
          evict();
        } catch (IOException e) {
          log.debug("Cannot reconcile/bound ORT optimized store: {}", e.getMessage());
        }
      }
    }
  }

  /** A probe must still have the source bytes to identify a content-addressed graph. */
  public boolean contains(Path model, String ep, OptLevel level) {
    if (maxBytes == 0 || !Files.isRegularFile(model)) return false;
    if (!nativePathsFit(ep, level)) return false;
    try {
      initialize();
      return !OnnxExternalData.hasExternalData(model) && committed(entryPath(model, ep, level));
    } catch (IOException e) {
      log.debug("Cannot probe optimized ONNX graph: {}", e.getMessage());
      return false;
    }
  }

  private Path providerRoot(String ep, OptLevel level) {
    if (!"cpu".equals(ep) && !"cuda".equals(ep)) {
      throw new IllegalArgumentException("Unknown ORT execution provider: " + ep);
    }
    return root.resolve(ortVersion).resolve(ep + "-" + level.name());
  }

  private boolean nativePathsFit(String ep, OptLevel level) {
    Path provider = providerRoot(ep, level);
    Path stage = provider.resolve("s-" + "0".repeat(16));
    // Keep the full content key: the committed path is the longest of the new native paths.
    // Use the conservative Windows budget even when Java/OS long-path support is enabled;
    // it does not establish that ORT's native file-open implementation accepts longer paths.
    List<Path> paths = List.of(stage.resolve("source.onnx"), stage.resolve("model.tmp.onnx"),
        provider.resolve("0".repeat(64)).resolve("model.onnx"));
    if (paths.stream().allMatch(path -> path.toString().length() <= nativePathLimit)) return true;
    if (PATH_LIMIT_LOGGED.add(root)) {
      log.info("ORT optimized cache skipped at {}: native paths exceed {} characters; optimizing source in memory",
          root, nativePathLimit);
    }
    return false;
  }

  Path entryPath(Path model, String ep, OptLevel level) throws IOException {
    Path provider = providerRoot(ep, level);
    String hash = Sha256SidecarCache.getOrCompute(model, false)
        .orElseThrow(() -> new IOException("Cannot hash ONNX source: " + model));
    return provider.resolve(hash);
  }

  private void initialize() throws IOException {
    checkNoLinks(root);
    Files.createDirectories(root);
    checkNoLinks(root);
    // Disabling caching must never prune any existing root contents.
    if (maxBytes == 0) return;
    Path versionRoot = root.resolve(ortVersion);
    synchronized (INITIALIZED) {
      if (INITIALIZED.contains(versionRoot)) {
        verifyRootMarker();
        return;
      }
      claimRoot();
      try (DirectoryStream<Path> versions = Files.newDirectoryStream(root)) {
        for (Path version : versions) {
          if (!recognizedVersion(version)) continue;
          reconcileArtifacts(version);
          if (!version.equals(versionRoot)) {
            try {
              pruneOldVersion(version);
            } catch (IOException e) {
              log.debug("Refused old ORT version cleanup for {}: {}", version, e.getMessage());
            }
          }
        }
      }
      INITIALIZED.add(versionRoot);
    }
  }

  private void verifyRootMarker() throws IOException {
    Path marker = root.resolve(ROOT_MARKER);
    checkNoLinks(marker);
    if (!Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("ORT cache root has no valid ownership marker: " + root);
    }
    try (DirectoryStream<Path> contents = Files.newDirectoryStream(marker)) {
      if (contents.iterator().hasNext()) throw new IOException("Invalid ORT ownership marker: " + marker);
    }
  }

  private void claimRoot() throws IOException {
    Path marker = root.resolve(ROOT_MARKER);
    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
      verifyRootMarker();
      return;
    }
    // Adopt an older markerless store only when EVERY child has the recognized store layout.
    // An operator's existing folder containing unrelated contents is never claimed or pruned.
    try (DirectoryStream<Path> contents = Files.newDirectoryStream(root)) {
      for (Path child : contents) {
        if (!recognizedVersion(child)) throw new IOException("Ambiguous ORT cache root: " + root);
      }
    }
    try {
      Files.createDirectory(marker);
    } catch (FileAlreadyExistsException race) {
      verifyRootMarker();
    }
  }

  private static String pathSegment(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("Invalid ORT version path segment: " + value);
    }
    return value;
  }

  private static boolean providerName(String value) {
    for (OptLevel level : OptLevel.values()) {
      if (value.equals("cpu-" + level.name()) || value.equals("cuda-" + level.name())) return true;
    }
    return false;
  }

  private static boolean artifactName(String name) {
    return Set.of("model.onnx", "entry.json", "source.onnx", "model.tmp.onnx", LEASE_FILE).contains(name)
        || name.matches("entry-[A-Za-z0-9-]+\\.tmp");
  }

  private static boolean stagingName(String name) {
    return STAGE_NAME.matcher(name).matches() || PREPARING_NAME.matcher(name).matches()
        || LEGACY_STAGE_NAME.matcher(name).matches();
  }

  private static boolean quarantineName(String name) {
    return QUARANTINE_NAME.matcher(name).matches() || LEGACY_QUARANTINE_NAME.matcher(name).matches();
  }

  private static boolean ownedEntry(Path entry) throws IOException {
    checkNoLinks(entry);
    String name = entry.getFileName().toString();
    if (!name.matches("[0-9a-f]{64}") && !stagingName(name) && !quarantineName(name)) return false;
    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return false;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(entry)) {
      for (Path file : files) {
        checkNoLinks(file);
        if (!artifactName(file.getFileName().toString())
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
      }
    }
    return true;
  }

  private static boolean recognizedVersion(Path version) {
    try {
      checkNoLinks(version);
      if (!version.getFileName().toString().matches("(?:[0-9]+(?:\\.[0-9]+)+(?:[-+][A-Za-z0-9._-]+)?|unknown)")
          || !Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)) return false;
      try (DirectoryStream<Path> providers = Files.newDirectoryStream(version)) {
        for (Path provider : providers) {
          checkNoLinks(provider);
          if (!providerName(provider.getFileName().toString())
              || !Files.isDirectory(provider, LinkOption.NOFOLLOW_LINKS)) return false;
          try (DirectoryStream<Path> entries = Files.newDirectoryStream(provider)) {
            for (Path entry : entries) if (!ownedEntry(entry)) return false;
          }
        }
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static String randomToken() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private static Path createStaging(Path entry) throws IOException {
    ProcessHandle owner = ProcessHandle.current();
    long started = owner.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    Path staging;
    Path preparing;
    while (true) {
      String token = randomToken();
      staging = entry.getParent().resolve("s-" + token);
      preparing = entry.getParent().resolve("p-" + owner.pid() + "-" + started + "-" + token);
      if (!ACTIVE_STAGING.add(staging)) continue;
      if (ACTIVE_STAGING.add(preparing)) break;
      ACTIVE_STAGING.remove(staging);
    }
    boolean created = false;
    try {
      // Bootstrap holds only the lease. Its name retains an owner even if mkdir is the last
      // operation before a crash. Other JVMs never see a short stage with a half-written lease.
      Files.createDirectory(preparing);
      created = true;
      String lease = JSON.createObjectNode().put("pid", owner.pid()).put("started", started).toString();
      Files.writeString(preparing.resolve(LEASE_FILE), lease);
      return Files.move(preparing, staging, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException | Error failure) {
      if (created) {
        try {
          deleteOwnedEntry(preparing, true);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      ACTIVE_STAGING.remove(staging);
      throw failure;
    } finally {
      ACTIVE_STAGING.remove(preparing);
    }
  }

  private static boolean liveStage(Path stage) {
    String name = stage.getFileName().toString();
    if (!stagingName(name)) return false;
    if (ACTIVE_STAGING.contains(stage.toAbsolutePath().normalize())) return true;
    try {
      long pid;
      long started;
      var match = PREPARING_NAME.matcher(name);
      if (!match.matches()) match = LEGACY_STAGE_NAME.matcher(name);
      if (match.matches()) {
        pid = Long.parseLong(match.group(1));
        started = Long.parseLong(match.group(2));
      } else {
        Path leasePath = stage.resolve(LEASE_FILE);
        checkNoLinks(leasePath);
        JsonNode lease = JSON.readTree(Files.readString(leasePath));
        if (lease == null || !lease.path("pid").canConvertToLong()
            || !lease.path("started").canConvertToLong()) return true;
        pid = lease.path("pid").asLong();
        started = lease.path("started").asLong();
        if (pid < 1 || started < 0) return true;
      }
      var process = ProcessHandle.of(pid);
      if (process.isEmpty() || !process.get().isAlive()) return false;
      var actualStart = process.get().info().startInstant();
      // If the OS cannot expose start time, preserving a live PID is the safe answer.
      return started == 0 || actualStart.isEmpty() || actualStart.get().toEpochMilli() == started;
    } catch (IOException | JacksonException | NumberFormatException | SecurityException e) {
      // An unreadable lease cannot prove abandonment. Refuse deletion conservatively.
      return true;
    }
  }

  private static List<Path> entriesIn(Path version) throws IOException {
    List<Path> result = new ArrayList<>();
    checkNoLinks(version);
    if (!Files.isDirectory(version, LinkOption.NOFOLLOW_LINKS)) return result;
    try (DirectoryStream<Path> providers = Files.newDirectoryStream(version)) {
      for (Path provider : providers) {
        if (!providerName(provider.getFileName().toString())) continue;
        checkNoLinks(provider);
        if (!Files.isDirectory(provider, LinkOption.NOFOLLOW_LINKS)) continue;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(provider)) {
          for (Path entry : entries) result.add(entry);
        }
      }
    }
    return result;
  }

  private static void reconcileArtifacts(Path version) throws IOException {
    for (Path entry : entriesIn(version)) {
      String name = entry.getFileName().toString();
      boolean quarantined = quarantineName(name);
      if (!quarantined && (!stagingName(name) || liveStage(entry))) continue;
      try {
        deleteOwnedEntry(entry);
      } catch (IOException e) {
        log.debug("Refused ORT quarantine/abandoned staging cleanup for {}: {}", entry, e.getMessage());
      }
    }
  }

  private static void pruneOldVersion(Path version) throws IOException {
    // Never recursively delete a version directory: a different JVM can start a live
    // optimization after the scan. Delete individual owned entries and remove only empty
    // parents; a concurrent mkdir makes the nonrecursive parent delete fail safely.
    if (!recognizedVersion(version)) return;
    for (Path entry : entriesIn(version)) {
      if (stagingName(entry.getFileName().toString()) && liveStage(entry)) continue;
      try {
        deleteOwnedEntry(entry);
      } catch (IOException e) {
        log.debug("Refused old ORT entry cleanup for {}: {}", entry, e.getMessage());
      }
    }
    try (DirectoryStream<Path> providers = Files.newDirectoryStream(version)) {
      for (Path provider : providers) {
        checkNoLinks(provider);
        if (providerName(provider.getFileName().toString())) deleteEmptyDirectory(provider);
      }
    }
    deleteEmptyDirectory(version);
  }

  private static void deleteEmptyDirectory(Path directory) throws IOException {
    checkNoLinks(directory);
    try {
      Files.deleteIfExists(directory);
    } catch (DirectoryNotEmptyException active) {
      // Preserve concurrently created entries, including a live optimizer's staging.
      log.debug("Keeping nonempty ORT cache directory: {}", directory);
    }
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
      JsonNode metadata = JSON.readTree(Files.readString(marker));
      return metadata != null && metadata.path("sourceSize").canConvertToLong()
          && metadata.path("sourceSize").asLong() >= 0
          && metadata.path("created").canConvertToLong()
          && metadata.path("lastUsed").canConvertToLong();
    } catch (IOException | JacksonException e) {
      return false;
    }
  }

  private void touch(Path entry) throws IOException {
    JsonNode metadata = JSON.readTree(Files.readString(entry.resolve("entry.json")));
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

  private void quarantine(Path entry) throws IOException {
    if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) return;
    if (!ownedEntry(entry)) throw new IOException("Unrecognized ORT entry contents: " + entry);
    Path quarantine = entry.resolveSibling("q-" + randomToken());
    checkNoLinks(quarantine);
    try {
      // A different creator may publish a healthy graph after our failed load/layout check.
      // Renaming that graph is allowed: all its bytes move together, the source is untouched,
      // and the residual cost is one extra optimization later. Never compare-then-delete here.
      quarantineMove.move(entry, quarantine);
    } catch (NoSuchFileException race) {
      // Another creator already detached this entry. Proceed through ordinary publication.
    }
  }

  private void publish(Path staging, Path entry) throws IOException {
    checkNoLinks(entry);
    if (committed(entry)) return;
    // An incomplete entry that appeared during creation follows the same atomic detachment
    // protocol. It may race with a healthy publication; quarantine never deletes at the key.
    quarantine(entry);
    checkNoLinks(staging);
    checkNoLinks(entry);
    try {
      Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE);
      // The lease travels with the atomic publication; removing it earlier would expose a
      // live short stage with no owner. Committed hash directories no longer need a lease.
      Path lease = entry.resolve(LEASE_FILE);
      checkNoLinks(lease);
      Files.deleteIfExists(lease);
    } catch (IOException race) {
      if (!committed(entry)) throw race;
    }
  }

  private static void deleteOwnedEntry(Path entry) throws IOException {
    deleteOwnedEntry(entry, false);
  }

  private static void deleteOwnedEntry(Path entry, boolean ownStaging) throws IOException {
    if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) return;
    if (!ownStaging && stagingName(entry.getFileName().toString())
        && liveStage(entry)) throw new IOException("Refusing live ORT staging deletion: " + entry);
    if (!ownedEntry(entry)) throw new IOException("Unrecognized ORT entry contents: " + entry);
    deleteTree(entry);
  }

  private record Victim(Path path, long bytes, long used) {}

  private static long artifactBytes(Path entry) throws IOException {
    checkNoLinks(entry);
    long bytes = 0;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(entry)) {
      for (Path file : files) {
        if (!artifactName(file.getFileName().toString())) continue;
        checkNoLinks(file);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(file);
      }
    }
    return bytes;
  }

  private void evict() throws IOException {
    verifyRootMarker();
    List<Victim> entries = new ArrayList<>();
    long total = 0;
    try (DirectoryStream<Path> versions = Files.newDirectoryStream(root)) {
      for (Path version : versions) {
        if (!version.getFileName().toString().matches("(?:[0-9]+(?:\\.[0-9]+)+(?:[-+][A-Za-z0-9._-]+)?|unknown)")) continue;
        try {
          reconcileArtifacts(version);
          for (Path entry : entriesIn(version)) {
            String name = entry.getFileName().toString();
            if (!name.matches("[0-9a-f]{64}") && !stagingName(name) && !quarantineName(name)) continue;
            try {
              long bytes = artifactBytes(entry);
              total += bytes;
              // A live optimizer's input/output may transiently exceed the cap. Its bytes
              // reduce the budget for committed entries, but are never deleted underneath it.
              if (stagingName(name) && liveStage(entry)) continue;
              long used = committed(entry)
                  ? JSON.readTree(Files.readString(entry.resolve("entry.json"))).path("lastUsed").asLong() : 0;
              entries.add(new Victim(entry, bytes, used));
            } catch (IOException | JacksonException e) {
              log.debug("Cannot inspect ORT eviction candidate {}: {}", entry, e.getMessage());
            }
          }
        } catch (IOException e) {
          log.debug("Cannot inspect ORT version for eviction {}: {}", version, e.getMessage());
        }
      }
    }
    entries.sort(Comparator.comparingLong(Victim::used).thenComparing(v -> v.path().toString()));
    for (Victim entry : entries) {
      if (total <= maxBytes) break;
      try {
        deleteOwnedEntry(entry.path());
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
