/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
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

  public enum SaveOutcome { SAVED, FAILED_ROLLED_BACK, SAVED_WITHOUT_REPLICA }

  public record SaveResult(SaveOutcome outcome, IOException failure) {
    public void requireSaved() {
      if (outcome == SaveOutcome.FAILED_ROLLED_BACK) {
        throw new UncheckedIOException("Encryption change failed; previous credentials remain in force", failure);
      }
    }

    public String warning() {
      return outcome == SaveOutcome.SAVED_WITHOUT_REPLICA
          ? "Your encryption change was saved, but its recovery replica could not be written. "
              + "The new credentials are in force. Keep your recovery key safe and retry the change to restore the replica."
          : null;
    }
  }

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private final Path file;
  private final Path replica;
  private boolean restoredFromReplica;
  private boolean replicaUnavailable;

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
        KeystoreRecord authoritative = decode(primary);
        try {
          byte[] backup = readIfPresent(replica);
          boolean matches = false;
          if (backup != null) {
            try {
              matches = authoritative.equals(decode(backup));
            } catch (IllegalStateException invalidReplica) {
              // A valid primary owns the current credentials, even when the replica is invalid.
            }
          }
          if (!matches) {
            AtomicFileWrites.replaceStrict(replica, primary);
            LoggerFactory.getLogger(EncryptionKeystore.class)
                .warn("Repaired encryption keystore recovery replica from authoritative primary: {}", replica);
          }
        } catch (IOException | RuntimeException replicaFailure) {
          // A read failure is not absence: do not restore or rewrite either copy in that case.
          // A failed repair must not prevent the Engine from using the validated primary.
          replicaUnavailable = true;
          LoggerFactory.getLogger(EncryptionKeystore.class)
              .warn("Could not read or repair encryption keystore recovery replica; using valid primary", replicaFailure);
          return Optional.of(authoritative);
        }
        replicaUnavailable = false;
        return Optional.of(authoritative);
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
      restoredFromReplica = true;
      LoggerFactory.getLogger(EncryptionKeystore.class)
          .warn("Restored encryption keystore from recovery replica: {}", replica);
      return Optional.of(restored);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read or restore encryption keystore", e);
    }
  }

  public String recoveryNotice() {
    if (replicaUnavailable) {
      return "Your encryption key is available, but its recovery replica could not be read or repaired. "
          + "Keep your recovery key safe. Repair will be retried on the next start or successful encryption change.";
    }
    return restoredFromReplica ? "Your encryption key was restored from its recovery replica." : null;
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

  public SaveResult save(KeystoreRecord record) {
    validate(record);
    try {
      byte[] bytes = MAPPER.writeValueAsBytes(record);
      byte[] previous = readIfPresent(file);
      removeAbandonedTemps();
      AtomicFileWrites.replaceStrict(file, bytes);
      try {
        AtomicFileWrites.replaceStrict(replica, bytes);
      } catch (IOException replicaFailure) {
        try {
          if (previous == null) Files.deleteIfExists(file);
          else AtomicFileWrites.replaceStrict(file, previous);
        } catch (IOException rollbackFailure) {
          replicaFailure.addSuppressed(rollbackFailure);
          try {
            Files.deleteIfExists(replica);
          } catch (IOException invalidationFailure) {
            replicaFailure.addSuppressed(invalidationFailure);
            LoggerFactory.getLogger(EncryptionKeystore.class)
                .error("Could not invalidate stale encryption keystore replica", invalidationFailure);
          }
          LoggerFactory.getLogger(EncryptionKeystore.class)
              .warn("Encryption change saved without recovery replica; rollback also failed", replicaFailure);
          return new SaveResult(SaveOutcome.SAVED_WITHOUT_REPLICA, replicaFailure);
        }
        return new SaveResult(SaveOutcome.FAILED_ROLLED_BACK, replicaFailure);
      }
      restoredFromReplica = false;
      replicaUnavailable = false;
      return new SaveResult(SaveOutcome.SAVED, null);
    } catch (IOException e) {
      return new SaveResult(SaveOutcome.FAILED_ROLLED_BACK, e);
    }
  }

  private void removeAbandonedTemps() {
    if (!Files.exists(file.getParent())) return;
    try (var entries = Files.newDirectoryStream(file.getParent(), path -> {
      String name = path.getFileName().toString();
      return (name.startsWith("keystore.json.") || name.startsWith("keystore.replica.json."))
          && name.endsWith(".tmp");
    })) {
      for (Path path : entries) {
        try {
          Files.delete(path);
        } catch (IOException cleanupFailure) {
          LoggerFactory.getLogger(EncryptionKeystore.class)
              .warn("Could not remove abandoned encryption keystore temp; retrying on next save: {}", path, cleanupFailure);
        }
      }
    } catch (IOException | DirectoryIteratorException cleanupFailure) {
      LoggerFactory.getLogger(EncryptionKeystore.class)
          .warn("Could not inspect abandoned encryption keystore temps; retrying on next save", cleanupFailure);
    }
  }
}
