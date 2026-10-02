/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.agent.api.encryption.StoreCipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Sealed, keyed identity projection. A lookup opens one row, never the snapshot archive. */
final class FeatureSnapshotLookup {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Logger log = LoggerFactory.getLogger(FeatureSnapshotLookup.class);
  // Different archive handles (search, agent and restore) can update the same projection.
  private static final Object WRITES = new Object();
  // Entries exist only while a process-owned maintenance registration is alive.
  private static final Map<Path, List<Runnable>> INVALIDATION_LISTENERS = new HashMap<>();
  private final Path archive;
  private final Path directory;
  private final StoreCipher cipher;

  FeatureSnapshotLookup(Path archive, StoreCipher cipher) {
    this.archive = archive.toAbsolutePath().normalize();
    this.directory = archive.resolveSibling(archive.getFileName() + ".lookup");
    this.cipher = cipher;
  }

  boolean initialize(Initializer initializer) throws IOException {
    synchronized (WRITES) {
      if (cipher.enabled() && cipher.locked()) return false;
      try {
        if (activeGeneration() != null) return true;
      } catch (IOException failure) {
        log.debug("Feedback lookup metadata is unavailable; rebuilding from the archive", failure);
      }
      Files.createDirectories(directory);
      // Retire unpublished generations left by process interruption before creating another.
      try (var abandoned = Files.newDirectoryStream(directory, "generation-*")) {
        for (Path path : abandoned) deleteGeneration(path);
      }
      Path generation = Files.createDirectory(directory.resolve("generation-" + UUID.randomUUID()));
      boolean published = false;
      try {
        initializer.run(snapshot -> append(generation, snapshot));
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback backfill interrupted");
        replace(generation.resolve("archive-size-v1"), Long.toString(archiveSize()));
        Files.writeString(generation.resolve("ready-v1"), "1\n", StandardCharsets.UTF_8);
        replace(directory.resolve("CURRENT"), generation.getFileName().toString());
        published = true;
        return true;
      } finally {
        if (!published && !Thread.currentThread().isInterrupted()) deleteGeneration(generation);
      }
    }
  }

  @FunctionalInterface
  interface Initializer {
    void run(SnapshotWriter writer) throws IOException;
  }

  @FunctionalInterface
  interface SnapshotWriter {
    void append(FeatureSnapshot snapshot) throws IOException;
  }

  boolean initialized() throws IOException {
    return !(cipher.enabled() && cipher.locked()) && activeGeneration() != null;
  }

  @FunctionalInterface
  interface ArchiveAppender {
    void append() throws IOException;
  }

  Runnable onInvalidation(Runnable listener) {
    synchronized (WRITES) {
      INVALIDATION_LISTENERS.computeIfAbsent(archive, ignored -> new ArrayList<>()).add(listener);
    }
    return () -> {
      synchronized (WRITES) {
        List<Runnable> listeners = INVALIDATION_LISTENERS.get(archive);
        if (listeners == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty()) INVALIDATION_LISTENERS.remove(archive);
      }
    };
  }

  void append(FeatureSnapshot snapshot, ArchiveAppender archival,
      boolean initializeOnAppend, Initializer initializer) throws IOException {
    synchronized (WRITES) {
      Generation generation = null;
      try {
        generation = activeGeneration();
      } catch (Exception failure) {
        log.debug("Feedback lookup unavailable before archival append", failure);
      }
      // Backfill shares this monitor across all handles. An accepted write cannot escape its scan.
      // A larger archive invalidates the persisted coverage before any projection row changes.
      try {
        archival.append();
      } catch (IOException | RuntimeException failure) {
        notifyInvalidation(); // A partial archive write also invalidates coverage.
        throw failure;
      }
      try {
        if (generation != null) {
          append(generation.path(), snapshot);
          replace(generation.path().resolve("archive-size-v1"), Long.toString(archiveSize()));
          return;
        }
        if (initializeOnAppend && initialize(initializer)) return;
      } catch (Exception failure) {
        // Never publish new coverage after failed projection maintenance. The archive survives,
        // while both this process and reopened handles fail closed on the length mismatch.
        log.warn("Feedback snapshot archived but identity lookup maintenance failed", failure);
      }
      notifyInvalidation();
    }
  }

  private void notifyInvalidation() {
    List<Runnable> listeners = INVALIDATION_LISTENERS.get(archive);
    if (listeners != null) listeners.forEach(Runnable::run);
  }

  private void append(Path generation, FeatureSnapshot snapshot) throws IOException {
    if (snapshot.interactionId() == null || snapshot.interactionId().isBlank()
        || snapshot.hits() == null) return;
    synchronized (WRITES) {
      Map<String, String> identities = new LinkedHashMap<>();
      for (FeatureSnapshot.HitFeatures hit : snapshot.hits()) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback lookup write interrupted");
        if (hit == null || hit.sourceDocId() == null || hit.sourceDocId().isBlank()) continue;
        String uid = hit.docId() == null ? "" : hit.docId();
        identities.merge(hit.sourceDocId(), uid, (first, second) -> first.equals(second) ? first : "");
      }
      // Resolve each identity over the whole snapshot before publishing any of its rows.
      for (var identity : identities.entrySet()) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback lookup write interrupted");
        Path file = key(generation, snapshot.interactionId(), identity.getKey());
        Resolution previous = read(file);
        String uid = identity.getValue();
        if (previous != null && !Objects.equals(previous.docUid(), uid)) uid = "";
        Resolution row = new Resolution(1, snapshot.interactionId(), identity.getKey(), uid);
        Files.createDirectories(file.getParent());
        replace(file, cipher.seal(MAPPER.writeValueAsString(row)));
      }
    }
  }

  Optional<String> resolve(String interactionId, String sourceDocId) throws IOException {
    if (interactionId == null || interactionId.isBlank()
        || sourceDocId == null || sourceDocId.isBlank()
        || (cipher.enabled() && cipher.locked())) return Optional.empty();
    Generation generation = activeGeneration();
    if (generation == null) return Optional.empty();
    Resolution row = read(key(generation.path(), interactionId, sourceDocId));
    if (row == null || row.schemaVersion() != 1
        || !interactionId.equals(row.interactionId()) || !sourceDocId.equals(row.sourceDocId())
        || row.docUid() == null || row.docUid().isBlank()) return Optional.empty();
    // An append may have invalidated coverage, or finished publishing conflicting evidence,
    // while this row was being read. Compare the covered length as well as the generation path.
    return generation.equals(activeGeneration()) ? Optional.of(row.docUid()) : Optional.empty();
  }

  private Resolution read(Path file) throws IOException {
    if (!Files.exists(file)) return null;
    return MAPPER.readValue(cipher.open(Files.readString(file, StandardCharsets.UTF_8)), Resolution.class);
  }

  private Generation activeGeneration() throws IOException {
    Path current = directory.resolve("CURRENT");
    Path generation;
    if (!Files.exists(current)) {
      generation = directory;
    } else {
      String name = Files.readString(current, StandardCharsets.UTF_8);
      if (!name.matches("generation-[a-f0-9-]{36}")) throw new IOException("Invalid feedback lookup generation");
      generation = directory.resolve(name);
    }
    Path coverage = generation.resolve("archive-size-v1");
    // Older generations lack coverage and must be rebuilt before serving identities.
    if (!Files.exists(generation.resolve("ready-v1")) || !Files.exists(coverage)) return null;
    long covered;
    try {
      covered = Long.parseLong(Files.readString(coverage, StandardCharsets.UTF_8));
    } catch (NumberFormatException failure) {
      throw new IOException("Invalid feedback lookup coverage", failure);
    }
    return covered == archiveSize() ? new Generation(generation, covered) : null;
  }

  private long archiveSize() throws IOException {
    return Files.exists(archive) ? Files.size(archive) : 0L;
  }

  private record Generation(Path path, long archiveLength) {}

  private static void replace(Path file, String value) throws IOException {
    Path temporary = Files.createTempFile(file.getParent(), "lookup-", ".tmp");
    try {
      Files.writeString(temporary, value, StandardCharsets.UTF_8);
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void deleteGeneration(Path generation) throws IOException {
    Files.walkFileTree(generation, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback lookup cleanup interrupted");
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
        if (failure != null) throw failure;
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback lookup cleanup interrupted");
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private Path key(Path generation, String interactionId, String sourceDocId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // Length-prefix the interaction to distinguish all pairs, even if either contains NUL.
      digest.update((interactionId.length() + ":" + interactionId).getBytes(StandardCharsets.UTF_8));
      String hash = HexFormat.of().formatHex(digest.digest(sourceDocId.getBytes(StandardCharsets.UTF_8)));
      return generation.resolve(hash.substring(0, 2)).resolve(hash + ".json");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Resolution(int schemaVersion, String interactionId, String sourceDocId, String docUid) {}
}
