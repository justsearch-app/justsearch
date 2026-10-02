/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.agent.api.encryption.StoreCipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
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

  void initialize(Initializer initializer) throws IOException {
    synchronized (WRITES) {
      Path marker = directory.resolve("ready-v1");
      if (Files.exists(marker) || (cipher.enabled() && cipher.locked())) return;
      initializer.run();
      Files.createDirectories(directory);
      Files.writeString(marker, "1\n", StandardCharsets.UTF_8);
    }
  }

  @FunctionalInterface
  interface Initializer {
    void run() throws IOException;
  }

  void append(FeatureSnapshot snapshot) throws IOException {
    if (snapshot.interactionId() == null || snapshot.interactionId().isBlank()
        || snapshot.hits() == null) return;
    synchronized (WRITES) {
      for (FeatureSnapshot.HitFeatures hit : snapshot.hits()) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback lookup write interrupted");
        if (hit == null || hit.sourceDocId() == null || hit.sourceDocId().isBlank()) continue;
        Path file = key(snapshot.interactionId(), hit.sourceDocId());
        Resolution previous = read(file);
        String uid = hit.docId() == null ? "" : hit.docId();
        if (previous != null && !Objects.equals(previous.docUid(), uid)) uid = "";
        Resolution row = new Resolution(1, snapshot.interactionId(), hit.sourceDocId(), uid);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "lookup-", ".tmp");
        try {
          Files.writeString(temporary, cipher.seal(MAPPER.writeValueAsString(row)), StandardCharsets.UTF_8);
          Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
    }
  }

  Optional<String> resolve(String interactionId, String sourceDocId) throws IOException {
    if (interactionId == null || interactionId.isBlank()
        || sourceDocId == null || sourceDocId.isBlank()
        || (cipher.enabled() && cipher.locked())) return Optional.empty();
    Resolution row = read(key(interactionId, sourceDocId));
    if (row == null || row.schemaVersion() != 1
        || !interactionId.equals(row.interactionId()) || !sourceDocId.equals(row.sourceDocId())
        || row.docUid() == null || row.docUid().isBlank()) return Optional.empty();
    return Optional.of(row.docUid());
  }

  private Resolution read(Path file) throws IOException {
    if (!Files.exists(file)) return null;
    return MAPPER.readValue(cipher.open(Files.readString(file, StandardCharsets.UTF_8)), Resolution.class);
  }

  private Path key(String interactionId, String sourceDocId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // Length-prefix the interaction to distinguish all pairs, even if either contains NUL.
      digest.update((interactionId.length() + ":" + interactionId).getBytes(StandardCharsets.UTF_8));
      String hash = HexFormat.of().formatHex(digest.digest(sourceDocId.getBytes(StandardCharsets.UTF_8)));
      return directory.resolve(hash.substring(0, 2)).resolve(hash + ".json");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Resolution(int schemaVersion, String interactionId, String sourceDocId, String docUid) {}
}
