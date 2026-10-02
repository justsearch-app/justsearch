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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Sealed, keyed identity projection. A lookup opens one row, never the snapshot archive. */
final class FeatureSnapshotLookup {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  // Different archive handles (search, agent and restore) can update the same projection.
  private static final Object WRITES = new Object();
  private final Path directory;
  private final StoreCipher cipher;

  FeatureSnapshotLookup(Path archive, StoreCipher cipher) {
    this.directory = archive.resolveSibling(archive.getFileName() + ".lookup");
    this.cipher = cipher;
  }

  boolean initialize(Initializer initializer) throws IOException {
    synchronized (WRITES) {
      if (cipher.enabled() && cipher.locked()) return false;
      if (activeGeneration() != null) return true;
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

  void append(FeatureSnapshot snapshot) throws IOException {
    synchronized (WRITES) {
      Path generation = activeGeneration();
      if (generation == null) throw new IOException("Feedback lookup is not initialized");
      append(generation, snapshot);
    }
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
    Path generation = activeGeneration();
    if (generation == null) return Optional.empty();
    Resolution row = read(key(generation, interactionId, sourceDocId));
    if (row == null || row.schemaVersion() != 1
        || !interactionId.equals(row.interactionId()) || !sourceDocId.equals(row.sourceDocId())
        || row.docUid() == null || row.docUid().isBlank()) return Optional.empty();
    return Optional.of(row.docUid());
  }

  private Resolution read(Path file) throws IOException {
    if (!Files.exists(file)) return null;
    return MAPPER.readValue(cipher.open(Files.readString(file, StandardCharsets.UTF_8)), Resolution.class);
  }

  private Path activeGeneration() throws IOException {
    Path current = directory.resolve("CURRENT");
    if (!Files.exists(current)) {
      // A completed lookup from the previous format remains usable; incomplete prefixes do not.
      return Files.exists(directory.resolve("ready-v1")) ? directory : null;
    }
    String name = Files.readString(current, StandardCharsets.UTF_8);
    if (!name.matches("generation-[a-f0-9-]{36}")) throw new IOException("Invalid feedback lookup generation");
    Path generation = directory.resolve(name);
    return Files.exists(generation.resolve("ready-v1")) ? generation : null;
  }

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
