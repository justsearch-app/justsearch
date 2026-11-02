/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.WindowsOwnerOnlyDirectory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Ownership evidence shared by generation management and Lucene recovery. */
public final class IndexOwnership {
  public static final String SENTINEL = ".justsearch-generation.sentinel";
  public static final String MANIFEST = ".justsearch-index-generation.json";
  private static final Pattern NAME = Pattern.compile(
      "([A-Za-z0-9][A-Za-z0-9._-]*?)(\\.bak-[0-9]{8}-[0-9]{6})?(\\.del-[0-9]{8}-[0-9]{6})?");
  private static final Set<String> MANIFEST_FIELDS = Set.of("format_version", "generation_id",
      "source", "created_at_ms", "target_index_fingerprint", "models", "sparse_model",
      "vector_dimension", "projection_source_ids");
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private IndexOwnership() {}

  public enum Role { CANONICAL, BACKUP, MARKED }
  public record Representation(String identity, Role role, boolean backup) {}
  public record Evidence(String identity, byte[] sentinel, byte[] manifest, long createdAtMs) {}

  public static final class Unproven extends IOException {
    private final Path path;
    private final String evidenceClass;

    public Unproven(Path path, String evidenceClass) {
      super("index.ownership_unproven: " + evidenceClass + ": " + rejectedDirectory(path));
      this.path = rejectedDirectory(path);
      this.evidenceClass = evidenceClass;
    }

    private static Path rejectedDirectory(Path path) {
      Path exact = path.toAbsolutePath().normalize();
      String name = exact.getFileName() == null ? "" : exact.getFileName().toString();
      return name.equals(SENTINEL) || name.equals(MANIFEST) ? exact.getParent() : exact;
    }

    public Path path() { return path; }
    public String evidenceClass() { return evidenceClass; }
  }

  public static Representation representation(String name) throws IOException {
    var match = NAME.matcher(name);
    if (!match.matches() || name.contains("..")) throw new IOException("Invalid generation name: " + name);
    String id = match.group(1);
    if (id.contains(".bak-") || id.contains(".del-")) throw new IOException("Invalid generation representation: " + name);
    return new Representation(id, match.group(3) != null ? Role.MARKED
        : match.group(2) != null ? Role.BACKUP : Role.CANONICAL, match.group(2) != null);
  }

  /** Only a missing entry proves absence; permission and inspection failures propagate. */
  public static boolean entryExists(Path path) throws IOException {
    try {
      Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return true;
    } catch (NoSuchFileException absent) { return false; }
  }

  public static void requireAbsent(Path path) throws IOException {
    try {
      if (entryExists(path)) throw new Unproven(path, "missing");
    } catch (Unproven collision) { throw collision;
    } catch (IOException unreadable) { throw new Unproven(path, "unreadable"); }
  }

  /** The threat model excludes another process swapping entries after this inspection. */
  public static BasicFileAttributes plainAttributes(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || attributes.isOther()) throw new Unproven(path, "link");
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      try { WindowsOwnerOnlyDirectory.requireNonReparsePoint(path); }
      catch (IOException failure) {
        if (failure.getMessage().startsWith("Reparse point")) throw new Unproven(path, "link");
        throw failure;
      }
    }
    return attributes;
  }

  public static void requireDirectory(Path directory) throws IOException {
    if (!plainAttributes(directory).isDirectory()) throw new Unproven(directory, "missing");
  }

  public static void validateParents(Path directory) throws IOException {
    Path absolute = directory.toAbsolutePath().normalize();
    for (Path component = absolute; component != null; component = component.getParent()) {
      try { requireDirectory(component); }
      catch (NoSuchFileException absent) { /* A missing parent is created only after inspection. */ }
    }
  }

  /** Reads at most limit+1 bytes from the same non-following handle used for the read. */
  public static byte[] readBounded(Path path, int limit) throws IOException {
    long started = System.nanoTime();
    long budget = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    for (;;) {
      if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Interrupted reading " + path);
      try {
        if (!plainAttributes(path).isRegularFile()) throw new Unproven(path, "malformed");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          java.nio.channels.FileLock lock;
          try { lock = channel.tryLock(0, Long.MAX_VALUE, true); }
          catch (java.nio.channels.OverlappingFileLockException held) { lock = null; }
          if (lock != null) {
            try (var ignored = lock) {
              ByteBuffer bytes = ByteBuffer.allocate(limit + 1);
              while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
              if (bytes.position() > limit) throw new Unproven(path, "malformed");
              return java.util.Arrays.copyOf(bytes.array(), bytes.position());
            }
          }
        }
      } catch (Unproven refusal) { throw refusal;
      } catch (NoSuchFileException missing) { throw new Unproven(path, "missing");
      } catch (java.nio.file.AccessDeniedException denied) {
        // An atomic writer can briefly deny a fresh open on Windows.
      } catch (IOException unreadable) { throw new Unproven(path, "unreadable"); }
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) throw new Unproven(path, "unreadable");
      try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 10_000_000)); }
      catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("Interrupted reading " + path);
      }
    }
  }

  private static long parseSentinel(Path path, String identity, byte[] bytes) throws IOException {
    try {
      String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
      String[] lines = text.lines().toArray(String[]::new);
      if (lines.length != 3 || !lines[0].equals("justsearch_generation_sentinel_v1")) {
        throw new Unproven(path, "malformed");
      }
      var fields = new HashMap<String, String>();
      for (int i = 1; i < lines.length; i++) {
        int separator = lines[i].indexOf('=');
        if (separator < 1 || fields.putIfAbsent(lines[i].substring(0, separator),
            lines[i].substring(separator + 1)) != null) throw new Unproven(path, "malformed");
      }
      if (!fields.keySet().equals(Set.of("generation_id", "created_at_ms"))
          || Long.parseLong(fields.get("created_at_ms")) <= 0) throw new Unproven(path, "malformed");
      if (!identity.equals(fields.get("generation_id"))) throw new Unproven(path, "wrong identity");
      return Long.parseLong(fields.get("created_at_ms"));
    } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException malformed) {
      throw new Unproven(path, "malformed");
    }
  }

  public static byte[] validateManifest(Path path, String identity) throws IOException {
    byte[] bytes = readBounded(path, 16_384);
    try {
      var manifest = JSON.readTree(bytes);
      if (manifest == null || !manifest.isObject()) throw new Unproven(path, "malformed");
      for (String field : manifest.propertyNames()) {
        if (!MANIFEST_FIELDS.contains(field)) throw new Unproven(path, "malformed");
      }
      int version = manifest.path("format_version").asInt(0);
      if (!manifest.path("format_version").isIntegralNumber() || version < 1 || version > 3 || !manifest.path("created_at_ms").isIntegralNumber()
          || manifest.path("created_at_ms").asLong() <= 0) throw new Unproven(path, "malformed");
      if (!manifest.path("generation_id").isString()
          || !identity.equals(manifest.path("generation_id").asString())) throw new Unproven(path, "wrong identity");
      if (!manifest.path("source").isString()) throw new Unproven(path, "malformed");
      var models = manifest.get("models");
      if (models != null && !models.isNull()) {
        if (!models.isObject() || models.size() > 128) throw new Unproven(path, "malformed");
        for (String role : models.propertyNames()) {
          var model = models.get(role);
          if (!role.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || !model.isObject()
              || !Set.copyOf(model.propertyNames()).equals(Set.of("id", "sha256"))
              || !model.path("id").isString() || !model.path("sha256").isString()
              || !model.path("sha256").asString().matches("[0-9a-f]{64}")) {
            throw new Unproven(path, "malformed");
          }
          Path artifact = Path.of(model.path("id").asString());
          if (!artifact.isAbsolute() || !artifact.normalize().toString().equals(model.path("id").asString())) {
            throw new Unproven(path, "malformed");
          }
        }
      }
      var fingerprint = manifest.get("target_index_fingerprint");
      if (fingerprint != null && !fingerprint.isNull() && !fingerprint.isString()) throw new Unproven(path, "malformed");
      var sparse = manifest.get("sparse_model");
      if (sparse != null && !sparse.isNull()
          && (!sparse.isString() || !sparse.asString().matches("[a-z0-9-]{1,64}"))) throw new Unproven(path, "malformed");
      var dimension = manifest.get("vector_dimension");
      if (dimension != null && !dimension.isNull()
          && (!dimension.isIntegralNumber() || dimension.asLong() < 1 || dimension.asLong() > 16_384)) {
        throw new Unproven(path, "malformed");
      }
      var sources = manifest.get("projection_source_ids");
      if (sources != null && !sources.isNull()) {
        if (!sources.isArray() || sources.size() > 64) throw new Unproven(path, "malformed");
        var identities = new java.util.HashSet<String>();
        int length = 0;
        for (var source : sources) {
          if (!source.isString() || source.asString().isBlank() || source.asString().length() > 256
              || source.asString().chars().anyMatch(Character::isISOControl)
              || !identities.add(source.asString())) throw new Unproven(path, "malformed");
          length += source.asString().length();
        }
        if (length > 4_096) throw new Unproven(path, "malformed");
      }
      return bytes;
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException malformed) { throw new Unproven(path, "malformed"); }
  }

  public static Evidence inspect(Path directory, Path root) throws IOException {
    Path exact = directory.toAbsolutePath().normalize();
    Path parent = root.toAbsolutePath().normalize();
    if (!parent.equals(exact.getParent())) throw new Unproven(exact, "wrong identity");
    validateParents(parent);
    Representation representation;
    try { representation = representation(exact.getFileName().toString()); }
    catch (IOException invalid) { throw new Unproven(exact, "wrong identity"); }
    return inspectIdentity(exact, representation.identity());
  }

  public static Evidence inspectCanonical(Path directory, Path root) throws IOException {
    var evidence = inspect(directory, root);
    if (representation(directory.getFileName().toString()).role() != Role.CANONICAL) {
      throw new Unproven(directory, "wrong identity");
    }
    return evidence;
  }

  private static Evidence inspectIdentity(Path directory, String identity) throws IOException {
    requireDirectory(directory);
    Path sentinelPath = directory.resolve(SENTINEL);
    byte[] sentinel = readBounded(sentinelPath, 1_024);
    long created = parseSentinel(sentinelPath, identity, sentinel);
    byte[] manifest = entryExists(directory.resolve(MANIFEST))
        ? validateManifest(directory.resolve(MANIFEST), identity) : null;
    return new Evidence(identity, sentinel, manifest, created);
  }

  public static Evidence inspectStaging(Path directory, Path stagingRoot) throws IOException {
    if (!directory.toAbsolutePath().normalize().getParent().equals(stagingRoot.toAbsolutePath().normalize())) {
      throw new Unproven(directory, "wrong identity");
    }
    String name = directory.getFileName().toString();
    int separator = name.lastIndexOf('.');
    if (separator < 1) throw new Unproven(directory, "wrong identity");
    try { UUID.fromString(name.substring(separator + 1)); }
    catch (IllegalArgumentException invalid) { throw new Unproven(directory, "wrong identity"); }
    String identity = name.substring(0, separator);
    try {
      if (representation(identity).role() != Role.CANONICAL) throw new Unproven(directory, "wrong identity");
    } catch (IOException invalid) { throw new Unproven(directory, "wrong identity"); }
    return inspectIdentity(directory, identity);
  }

  public record Warning(Path path, String reason) {}
  public record NamespaceInspection(java.util.List<Path> canonicalOwned, java.util.List<Warning> warnings) {
    public NamespaceInspection {
      canonicalOwned = java.util.List.copyOf(canonicalOwned);
      warnings = java.util.List.copyOf(warnings);
    }
  }

  /** Common read-only physical gate; pointer and recorded-operation decisions remain with their owner. */
  public static NamespaceInspection inspectNamespace(Path basePath) throws IOException {
    try { return inspectNamespaceEntries(basePath); }
    catch (Unproven refusal) { throw refusal; }
    catch (IOException unreadable) { throw new Unproven(basePath, "unreadable"); }
  }

  private static NamespaceInspection inspectNamespaceEntries(Path basePath) throws IOException {
    Path indicesDir = basePath.resolve("indices");
    Path statePath = basePath.resolve("state.json");
    Path statePrevPath = basePath.resolve("state.json.prev");
    var warnings = new java.util.ArrayList<Warning>();
    validateParents(basePath);
    if (Files.notExists(basePath, LinkOption.NOFOLLOW_LINKS)) return new NamespaceInspection(java.util.List.of(), java.util.List.of());
    if (flatLegacy(basePath)) throw new IndexOwnership.Unproven(basePath, "missing");
    for (Path pointer : java.util.List.of(statePath, statePrevPath, basePath.resolve("migration-journal-admissions"))) {
      if (entryExists(pointer)
          && !plainAttributes(pointer).isRegularFile()) {
        throw new IndexOwnership.Unproven(pointer, "unexpected control object");
      }
    }
    try (var entries = Files.list(basePath)) {
      for (Path path : entries.toList()) {
        String name = path.getFileName().toString();
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !Set.of("state.json", "state.json.prev", "migration-journal-admissions").contains(name)) {
          addWarning(warnings, path, "unrecognized file left unchanged");
        }
      }
    }
    validateParents(indicesDir);
    java.util.List<Path> ownedCanonical = new java.util.ArrayList<>();
    if (entryExists(indicesDir)) {
      try (var entries = Files.list(indicesDir)) {
        for (Path entry : entries.toList()) {
          String name = entry.getFileName().toString();
          if (name.endsWith(".rebuild-pending") || name.endsWith(".clean-shutdown")) {
            BasicFileAttributes control;
            try { control = plainAttributes(entry); }
            catch (NoSuchFileException consumed) { continue; }
            if (!control.isRegularFile()) throw new Unproven(entry, "unexpected control object");
            if (name.endsWith(".rebuild-pending") && "malformed".equals(IndexRecoveryMarker.readReason(
                entry.resolveSibling(name.substring(0, name.length() - ".rebuild-pending".length()))))) {
              addWarning(warnings, entry, "malformed rebuild obligation retained");
            }
            continue;
          }
          BasicFileAttributes attributes;
          try { attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
          catch (NoSuchFileException consumed) { continue; }
          if (attributes.isRegularFile()) {
            addWarning(warnings, entry, "unrecognized file left unchanged");
            continue;
          }
          IndexOwnership.Representation representation = null;
          try { representation = representation(name); }
          catch (IOException invalidName) { /* Invalid names cannot prove ownership. */ }
          boolean marked = representation != null && representation.role() == IndexOwnership.Role.MARKED
              || attributes.isDirectory() && !attributes.isOther()
                  && Files.exists(entry.resolve("DELETEME"), LinkOption.NOFOLLOW_LINKS);
          try {
            inspect(entry, indicesDir);
            if (representation != null && representation.role() == IndexOwnership.Role.CANONICAL) {
              ownedCanonical.add(entry);
            }
          } catch (IOException unproven) {
            if (marked) { addWarning(warnings, entry, unproven.getMessage()); continue; }
            if (unproven instanceof Unproven refusal && "link".equals(refusal.evidenceClass())) throw refusal;
            if (!attributes.isSymbolicLink() && !attributes.isOther() && attributes.isDirectory()) {
              try (var contents = Files.list(entry)) {
                if (contents.findAny().isEmpty()) continue;
              }
            }
            if (unproven instanceof IndexOwnership.Unproven refusal) throw refusal;
            throw new IndexOwnership.Unproven(entry, "unreadable");
          }
        }
      }
    }
    Path staging = basePath.resolve("staging");
    validateParents(staging);
    if (entryExists(staging)) {
      try (var entries = Files.list(staging)) {
        for (Path path : entries.toList()) {
          try { inspectStaging(path, staging); }
          catch (IOException unproven) { addWarning(warnings, path, unproven.getMessage()); }
        }
      }
    }
    return new NamespaceInspection(ownedCanonical, warnings);
  }

  private static void addWarning(java.util.List<Warning> warnings, Path path, String reason) {
    if (warnings.size() < 64) warnings.add(new Warning(path.toAbsolutePath().normalize(), reason));
  }

  private static boolean flatLegacy(Path basePath) throws IOException {
    try (var entries = Files.list(basePath)) {
      return entries.anyMatch(path -> path.getFileName().toString()
          .toLowerCase(java.util.Locale.ROOT).startsWith("segments"));
    }
  }

  /** Publish complete ownership evidence before the directory can be named by a pointer. */
  public static Path create(Path base, String identity, byte[] sentinel, byte[] manifest) throws IOException {
    if (representation(identity).role() != Role.CANONICAL) throw new Unproven(base.resolve(identity), "wrong identity");
    Path indices = base.resolve("indices");
    Path staging = base.resolve("staging");
    validateParents(indices);
    validateParents(staging);
    Files.createDirectories(indices);
    Files.createDirectories(staging);
    Path candidate = Files.createDirectory(staging.resolve(identity + "." + UUID.randomUUID()));
    writeNewForced(candidate.resolve(SENTINEL), sentinel);
    writeNewForced(candidate.resolve(MANIFEST), manifest);
    inspectIdentity(candidate, identity);
    forceDirectory(candidate);
    Path target = indices.resolve(identity);
    requireAbsent(target);
    Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE);
    forceDirectory(indices);
    return target;
  }

  private static void writeNewForced(Path path, byte[] bytes) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
  }

  public static void replaceControl(Path path, byte[] bytes) throws IOException {
    AtomicFileWrites.replaceStrict(path, bytes);
    forceDirectory(path.getParent());
  }

  public static void forceDirectory(Path directory) throws IOException {
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      // AtomicFileWrites promises forced files and atomic moves on Windows, not
      // parent-directory entry durability across physical power loss. POSIX supports this force.
      return;
    }
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
  }
}
