/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 629 (LAYER) — the durable at-rest keystore file at {@code <dataDir>/encryption/keystore.json}.
 *
 * <p>Persists UNCONDITIONALLY (atomic temp+rename, mirroring {@code FileOperationLog.atomicWrite}). It
 * deliberately does NOT use {@code UiSettingsStore}/{@code DurableGrantStore}, which resolve to
 * IN_MEMORY in prod (629 §L5/§P5B — the trap that would silently drop key material). Uses the
 * codebase's Jackson 3 mapper ({@code tools.jackson}).
 */
public final class EncryptionKeystore {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private final Path file;

  public EncryptionKeystore(Path dataDir) {
    this.file = Objects.requireNonNull(dataDir, "dataDir").resolve("encryption").resolve("keystore.json");
  }

  public boolean exists() {
    prepareDirectory();
    return Files.exists(file);
  }

  public Optional<KeystoreRecord> load() {
    prepareDirectory();
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    KeystoreRecord record = MAPPER.readValue(file.toFile(), KeystoreRecord.class);
    StoreFormatVersions.requireReadable(
        "encryption-keystore",
        record.version(),
        EncryptionEnvelope.VERSION,
        EncryptionEnvelope.VERSION);
    return Optional.of(record);
  }

  private void prepareDirectory() {
    try {
      Files.createDirectories(file.toAbsolutePath().getParent().getParent());
      io.justsearch.configuration.persistence.OwnerOnlyDirectory.ensureOwnerOnlyDirectory(file.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot prepare encryption directory", e);
    }
  }

  public void save(KeystoreRecord record) {
    try {
      prepareDirectory();
      AtomicFileWrites.replaceStrict(file, MAPPER.writeValueAsBytes(record));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write encryption keystore", e);
    }
  }
}
