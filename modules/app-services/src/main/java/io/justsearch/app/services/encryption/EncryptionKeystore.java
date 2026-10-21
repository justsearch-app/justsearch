/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 629 (LAYER) — the durable at-rest keystore file at {@code <dataDir>/encryption/keystore.json}.
 *
 * <p>Forces both the primary and a current-wrap recovery replica before reporting a save. It
 * survives process termination; parent directories are not flushed, so physical power-loss
 * durability is not promised. It
 * deliberately does NOT use {@code UiSettingsStore}/{@code DurableGrantStore}, which resolve to
 * IN_MEMORY in prod (629 §L5/§P5B — the trap that would silently drop key material). Uses the
 * codebase's Jackson 3 mapper ({@code tools.jackson}).
 */
public final class EncryptionKeystore {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private final Path file;
  private final Path replica;

  public EncryptionKeystore(Path dataDir) {
    this.file = Objects.requireNonNull(dataDir, "dataDir").resolve("encryption").resolve("keystore.json");
    this.replica = file.resolveSibling("keystore.replica.json");
  }

  public boolean exists() {
    return Files.exists(file);
  }

  public Optional<KeystoreRecord> load() {
    try {
      byte[] primary = readIfPresent(file);
      if (primary != null && primary.length != 0) {
        return Optional.of(decode(primary));
      }
      byte[] backup = readIfPresent(replica);
      if (backup == null && primary == null) {
        return Optional.empty();
      }
      if (backup == null || backup.length == 0) {
        throw corrupt("primary is missing or empty and no valid recovery replica exists", null);
      }
      KeystoreRecord restored = decode(backup);
      AtomicFileWrites.replaceStrict(file, backup);
      LoggerFactory.getLogger(EncryptionKeystore.class)
          .warn("Restored encryption keystore from recovery replica: {}", replica);
      return Optional.of(restored);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read or restore encryption keystore", e);
    }
  }

  private static byte[] readIfPresent(Path path) throws IOException {
    try {
      return Files.readAllBytes(path);
    } catch (NoSuchFileException absent) {
      return null;
    }
  }

  private static KeystoreRecord decode(byte[] bytes) {
    KeystoreRecord record;
    try {
      record = MAPPER.readValue(bytes, KeystoreRecord.class);
    } catch (JacksonException invalid) {
      throw corrupt("invalid JSON", invalid);
    }
    validate(record);
    return record;
  }

  private static void validate(KeystoreRecord record) {
    if (record == null) throw corrupt("missing record", null);
    StoreFormatVersions.requireReadable(
        "encryption-keystore",
        record.version(),
        EncryptionEnvelope.VERSION,
        EncryptionEnvelope.VERSION);
    if (record.kdf() == null || record.kdf().isBlank() || record.iterations() <= 0) {
      throw corrupt("missing KDF parameters", null);
    }
    if (!EncryptionEnvelope.KDF.equals(record.kdf())
        && !record.kdf().startsWith(EncryptionEnvelope.ARGON2_PREFIX)) {
      throw corrupt("unsupported KDF", null);
    }
    try {
      if (Base64.getDecoder().decode(record.salt()).length != 16
          || Base64.getDecoder().decode(record.recoverySalt()).length != 16
          || Base64.getDecoder().decode(record.wrappedDek()).length != 60
          || Base64.getDecoder().decode(record.recoveryWrappedDek()).length != 60) {
        throw corrupt("invalid wrapped key or salt length", null);
      }
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw corrupt("invalid wrapped key encoding", invalid);
    }
  }

  private static CorruptDurableStoreException corrupt(String message, Throwable cause) {
    return new CorruptDurableStoreException("encryption-keystore", message, cause);
  }

  public void save(KeystoreRecord record) {
    validate(record);
    try {
      byte[] bytes = MAPPER.writeValueAsBytes(record);
      AtomicFileWrites.replaceStrict(file, bytes);
      AtomicFileWrites.replaceStrict(replica, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write encryption keystore", e);
    }
  }
}
